"""Run Fiji test cases: desktop Fiji on a virtual screen, the real SciJava dialogs answered by test_harness.groovy.

    python tests/run_cases.py [case-name ...]            # default: all cases in tests/cases/ (example app, portable)
    python tests/run_cases.py --real-apps [case ...]     # tests/cases_real_apps/ against the apps registered in your LC_HOME

Needs: a Fiji installation in $LC_FIJI_HOME, xvfb-run (Linux), and `pip install labconstrictor-tools numpy pandas tifffile`
in the Python running this script (it registers the example app in a private registry). With --real-apps the registry is
yours: set LC_HOME and $LC_REAL_FIXTURES to a folder with nucleisky/, celltracks/, vlab4mic/ data.
LC_FIJI_MODE=jar builds the jar (mvn) and tests the menu command instead of the loose Groovy script.
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
V3 = HERE.parent  # repository root
FIXTURES = HERE / "fixtures"
REAL_FIXTURES = Path(os.environ.get("LC_REAL_FIXTURES", FIXTURES))
REAL_APPS = "--real-apps" in sys.argv
FIJI = Path(os.environ["LC_FIJI_HOME"]) if os.environ.get("LC_FIJI_HOME") else None
OUT = V3 / "evidence"
SCRIPT_DIR = (FIJI or Path(".")) / "scripts" / "Plugins" / "LabConstrictor"
TIMEOUT_S = int(os.environ.get("FIJI_TIMEOUT", "300"))


def expand(value):
    if isinstance(value, str):
        return (
            value.replace("${V3}", str(V3))
            .replace("${PREFIX}", sys.prefix)
            .replace("${REAL_FIXTURES}", str(REAL_FIXTURES))
            .replace("${FIXTURES}", str(FIXTURES))
            .replace("${OUT}", str(OUT))
        )
    if isinstance(value, list):
        return [expand(v) for v in value]
    if isinstance(value, dict):
        return {k: expand(v) for k, v in value.items()}
    return value


def check(expect, report):
    """Return a list of failed expectations (empty = pass)."""
    failed = []
    if "status" in expect and report.get("status") != expect["status"]:
        failed.append("status %r != %r" % (report.get("status"), expect["status"]))
    if "status_in" in expect and report.get("status") not in expect["status_in"]:
        failed.append("status %r not in %r" % (report.get("status"), expect["status_in"]))
    if (
        "min_progress_events" in expect
        and len(report.get("progress_events", [])) < expect["min_progress_events"]
    ):
        failed.append("only %d progress events" % len(report.get("progress_events", [])))
    for dotted, (value, tolerance) in expect.get("approx", {}).items():
        found = report
        for part in dotted.split("."):
            found = found.get(part) if isinstance(found, dict) else None
        if found is None or abs(found - value) > tolerance:
            failed.append("%s = %r, expected %r +- %r" % (dotted, found, value, tolerance))
    for key, value in expect.get("equals", {}).items():
        if report.get(key) != value:
            failed.append("%s = %r, expected %r" % (key, report.get(key), value))
    for text in expect.get("log_contains", []):
        if text not in report.get("log_text", ""):
            failed.append("log lacks %r" % text)
    for dotted, text in expect.get("match", {}).items():
        found = report
        for part in dotted.split("."):
            found = found.get(part) if isinstance(found, dict) else None
        if not isinstance(found, str) or text not in found:
            failed.append("%s = %r does not contain %r" % (dotted, found, text))
    for key, limit in expect.get("max", {}).items():
        if report.get(key) is None or report[key] > limit:
            failed.append("%s = %r > %r" % (key, report.get(key), limit))
    if "worker_alive_after" in expect and report.get("worker_alive_after") != expect["worker_alive_after"]:
        failed.append("worker_alive_after = %r" % report.get("worker_alive_after"))
    return failed


PLUGIN = V3
GROOVY = (
    PLUGIN
    / "src"
    / "main"
    / "resources"
    / "org"
    / "cellmigrationlab"
    / "labconstrictor"
    / "LabConstrictor.groovy"
)
JAR_IN_FIJI = (FIJI or Path(".")) / "plugins" / "labconstrictor-fiji.jar"


def install_into_fiji():
    """LC_FIJI_MODE=script (default): the loose Groovy script. LC_FIJI_MODE=jar: build the plugin jar and call its menu command.
    Returns the launcher arguments that start the tool."""
    if os.environ.get("LC_FIJI_MODE", "script") == "jar":
        subprocess.run(["mvn", "-q", "-B", "package"], cwd=PLUGIN, check=True, capture_output=True)
        shutil.copyfile(next((PLUGIN / "target").glob("labconstrictor-fiji-*.jar")), JAR_IN_FIJI)
        shutil.rmtree(SCRIPT_DIR, ignore_errors=True)  # never both: the menu would list the tool twice
        macro = Path(tempfile.mkdtemp()) / "start.ijm"
        macro.write_text('run("LabConstrictor Tools...");\n')
        return ["-macro", str(macro)]
    JAR_IN_FIJI.unlink(missing_ok=True)
    SCRIPT_DIR.mkdir(parents=True, exist_ok=True)
    (SCRIPT_DIR / "LabConstrictor.groovy").write_text(GROOVY.read_text())
    return ["--run", str(SCRIPT_DIR / "LabConstrictor.groovy")]


def _tail(process, lines=12):
    """Last lines of Fiji's own output: the reason a case hangs or crashes is usually printed there."""
    text = ""
    for stream in (getattr(process, "stdout", None), getattr(process, "stderr", None)):
        if stream:
            text += stream.decode("utf-8", "replace") if isinstance(stream, bytes) else stream
    tail = "\n".join(text.strip().splitlines()[-lines:])
    return "\n--- Fiji output (tail) ---\n" + tail if tail else ""


def run_case(path):
    case = expand(json.loads(path.read_text()))
    name = path.stem
    case_dir = OUT / name
    case_dir.mkdir(parents=True, exist_ok=True)
    case.update(shots=str(case_dir), report=str(case_dir / "report.json"))
    (case_dir / "report.json").unlink(missing_ok=True)
    case_file = Path(tempfile.mkdtemp()) / "case.json"
    case_file.write_text(json.dumps(case))
    launch = install_into_fiji()
    env = dict(os.environ, LC_FIJI_HARNESS=str(HERE / "test_harness.groovy"), LC_FIJI_CASE=str(case_file))
    if "LC_HOME" not in env or not REAL_APPS:  # portable runs get a private registry holding only the example app
        env["LC_HOME"] = PRIVATE_HOME
    if case.get(
        "register"
    ):  # a private registry with just the apps this case needs (e.g. a deliberately failing one)
        env["LC_HOME"] = tempfile.mkdtemp(prefix="lchome_fiji_")
        for app in case["register"]:
            subprocess.run(
                [sys.executable, "-m", "labconstrictor_tools", "register", "--name", app["name"], "--prefix", app["prefix"],
                 "--module", app["module"], *[a for p in app["pythonpath"] for a in ("--pythonpath", p)]],
                check=True, capture_output=True, env=dict(env, PYTHONPATH=str(V3)),
            )  # fmt: skip
    command = [
        "xvfb-run",
        "-a",
        "-s",
        "-screen 0 1600x1000x24",
        str(FIJI / "fiji-linux-x64"),
        "--allow-multiple",
        *launch,
    ]
    try:
        # own process group: on a timeout the whole tree (xvfb-run, Xvfb, Fiji, its workers) is killed, not just xvfb-run
        process = subprocess.Popen(
            command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=os.name == "posix"
        )
        try:
            stdout, stderr = process.communicate(timeout=TIMEOUT_S)
        except subprocess.TimeoutExpired:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            stdout, stderr = process.communicate()
            return name, ["timed out after %d s%s" % (TIMEOUT_S, _tail(subprocess.CompletedProcess(command, -9, stdout, stderr)))], {}
        finished = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    except OSError as problem:
        return name, ["could not start Fiji: %s" % problem], {}
    report_path = case_dir / "report.json"
    if not report_path.exists():
        return name, ["no report written (script crashed or blocked)" + _tail(finished)], {}
    report = json.loads(report_path.read_text())
    log_file = Path(env["LC_HOME"]) / "logs" / "labconstrictor.log"
    report["log_text"] = log_file.read_text(encoding="utf-8") if log_file.exists() else ""
    return name, check(case.get("expect", {}), report), report


PRIVATE_HOME = tempfile.mkdtemp(prefix="lchome_fiji_")


def register_example_app():
    subprocess.run(
        [sys.executable, "-m", "labconstrictor_tools", "register", "--name", "synthetic", "--prefix", sys.prefix,
         "--module", "labconstrictor_tools.examples.synthetic", "--version", "0.0"],
        check=True, capture_output=True, env=dict(os.environ, LC_HOME=PRIVATE_HOME),
    )  # fmt: skip


def main(argv):
    if FIJI is None or not (FIJI / "jars").is_dir():
        raise SystemExit("set LC_FIJI_HOME to a Fiji installation (the folder containing jars/)")
    register_example_app()
    argv = [a for a in argv if a != "--real-apps"]
    wanted = set(argv)
    folder = "cases_real_apps" if REAL_APPS else "cases"
    cases = sorted(p for p in (HERE / folder).glob("*.json") if not wanted or p.stem in wanted)
    failures = 0
    for path in cases:
        name, failed, report = run_case(path)
        print("%-28s %s %s" % (name, "PASS" if not failed else "FAIL", "; ".join(failed)))
        failures += bool(failed)
    print("%d/%d cases passed" % (len(cases) - failures, len(cases)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
