"""Run Fiji test cases: desktop Fiji on a virtual screen, the real SciJava dialogs answered by test_harness.groovy.

    python tests/run_cases.py [case-name ...]            # default: all cases in tests/cases/ (example app, portable)
    python tests/run_cases.py --real-apps [case ...]     # tests/cases_real_apps/ against the apps registered in your LC_HOME

Needs: a Fiji installation in $LC_FIJI_HOME, xvfb-run (Linux), and `pip install labconstrictor-tools numpy pandas tifffile`
in the Python running this script (it registers the example app in a private registry). With --real-apps the registry is
yours: set LC_HOME and $LC_REAL_FIXTURES to a folder with nucleisky/, celltracks/, vlab4mic/ data.
LC_FIJI_MODE=jar builds the jar (mvn) and tests the menu command instead of the loose Groovy script.
LC_FIJI_MACRO_PROPERTY=1 (script mode) also runs the cases that carry a macro call, through the property the menu command sets.
Cases with "unit_checks" call functions of the script directly (tests/unit_checks.groovy); "script_macro", "cwd", "env", "log_prefill" and
"runs_prefill" set up the situation a case needs (a macro call, Fiji's folder and environment, a log or a runs folder that already exists).
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

import retention_support

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


def conformance_expected(name):
    """What the shared fixture (conformance.json wrong_science.coordinate) says a Fiji run must show, written without the host's own placement code:
    ImageJ pixel i spans [i, i+1), so a vertex at pixel-centre coordinate v is drawn at v + 0.5 (x first in a ROI)."""
    fixture = json.loads(Path(CONFORMANCE).read_text(encoding="utf-8"))["wrong_science"]["coordinate"]
    rows, columns = fixture["image"]["shape"]
    if name == "mask_seen":  # the worker's own account of what it received: sizes, how many mask pixels, the image pixel under each
        seen = {"image_h": rows, "image_w": columns, "mask_h": rows, "mask_w": columns, "mask_count": len(fixture["rect_mask_pixels"])}
        for (y, x), value in zip(fixture["rect_mask_pixels"], fixture["rect_mask_values"]):
            seen["v_%d_%d" % (y, x)] = value
        return seen
    if name == "point_drawn":
        y, x = next(c for c in fixture["cases"] if c["id"] == "CF-01")["point"]
        return [[x + 0.5, y + 0.5]]
    if name == "rect_drawn":
        return [[x + 0.5, y + 0.5] for y, x in fixture["rect"]]
    raise KeyError("no conformance value called %r" % name)


def make_calibration_files(folder):
    """The TIFF files of the calibration fixtures (conformance.json CAL-10..CAL-14, and a few more of the same kind), written with tifffile into `folder`: the file names are what
    tests/unit_checks.groovy (group conformance_calibration) reads. Needs numpy and tifffile in the Python that runs this script."""
    import numpy as np
    import tifffile

    cases = {c["id"]: c for c in json.loads(Path(CONFORMANCE).read_text(encoding="utf-8"))["wrong_science"]["calibration"]["cases"]}
    data = np.zeros((4, 4), np.uint8)
    folder = Path(folder)

    def write(name, **options):
        tifffile.imwrite(folder / (name + ".tif"), data, **options)

    first, _none_tags, none_unit = cases["CAL-10"]["input"]["variants"]
    write("CAL-10_0", imagej=True, resolution=(1.0, 1.0), metadata={"unit": first["imagej_unit"], "spacing": first["spacing"]})
    write("CAL-10_1")  # no resolution of its own (tifffile writes 1 per NONE)
    write("CAL-10_2", resolution=(100, 100), resolutionunit=none_unit["ResolutionUnit"])
    for ident in ("CAL-11", "CAL-12"):
        dpi = cases[ident]["input"]["resolution"][0]
        write(ident, resolution=(dpi, dpi), resolutionunit=cases[ident]["input"]["ResolutionUnit"])
    for number, variant in enumerate(cases["CAL-13"]["input"]["variants"]):
        write("CAL-13_%d" % number, resolution=tuple(variant["resolution"]), resolutionunit=variant["ResolutionUnit"])
    dpi = cases["CAL-14"]["input"]["resolution"][0]
    write("CAL-14", resolution=(dpi, dpi), resolutionunit=cases["CAL-14"]["input"]["ResolutionUnit"])
    # beyond the fixtures: the default test is "within 0.5 %" of 72 or 96 dpi (and per cm), and an OME size is a calibration only with its unit
    write("INCH_72.3", resolution=(72.3, 72.3), resolutionunit="INCH")  # 0.4 % from 72: a default
    write("INCH_73", resolution=(73, 73), resolutionunit="INCH")  # 1.4 % from 72: not a default, 25400 / 73 um per pixel
    write("INCH_72_96", resolution=(72, 96), resolutionunit="INCH")  # one axis looks like a default: unknown
    write("CM_37.7", resolution=(37.7, 37.7), resolutionunit="CENTIMETER")  # 0.3 % from 37.8: a default
    write("CM_40000", resolution=(40000, 40000), resolutionunit="CENTIMETER")  # 0.25 um per pixel
    write("OME_nm", ome=True, metadata={"PhysicalSizeX": 325, "PhysicalSizeXUnit": "nm", "PhysicalSizeY": 325, "PhysicalSizeYUnit": "nm"})
    write("OME_not_square", ome=True, metadata={"PhysicalSizeX": 0.5, "PhysicalSizeXUnit": "um", "PhysicalSizeY": 0.25, "PhysicalSizeYUnit": "um"})
    xml = '<?xml version="1.0" encoding="UTF-8"?><OME xmlns="http://www.openmicroscopy.org/Schemas/OME/2016-06"><Image ID="Image:0"><Pixels ID="Pixels:0" DimensionOrder="XYCZT" Type="uint8" SizeX="4" SizeY="4" SizeZ="1" SizeC="1" SizeT="1" PhysicalSizeX="0.5" PhysicalSizeY="0.5"/></Image></OME>'
    write("OME_no_unit", description=xml, metadata=None, resolution=(1, 1))  # the number alone is no evidence
    write("IMAGEJ_nm", imagej=True, resolution=(2.0, 2.0), metadata={"unit": "nm"})  # 0.5 nm per pixel = 0.0005 um
    write("IMAGEJ_inch_text", imagej=True, resolution=(96.0, 96.0), metadata={"unit": "inch"})  # the text 'inch' is not in the catalog: unknown whatever the number


def resolve_conformance(value):
    """A string "$CF:name" anywhere in an expectation is replaced by what the conformance file says (conformance_expected)."""
    if isinstance(value, str) and value.startswith("$CF:"):
        return conformance_expected(value[4:])
    if isinstance(value, list):
        return [resolve_conformance(v) for v in value]
    if isinstance(value, dict):
        return {k: resolve_conformance(v) for k, v in value.items()}
    return value


def lookup(report, dotted):
    """The value under a dotted key of the report (None when a part is missing); a list of lists (the items of several dropdowns) is flattened one level."""
    found = report
    for part in dotted.split("."):
        found = found.get(part) if isinstance(found, dict) else None
    if isinstance(found, list) and found and all(isinstance(item, list) for item in found):
        found = [x for item in found for x in item]
    return found


def walk(report, dotted):
    """The value under a dotted path of the report; a part that is a whole number indexes a list (`windows.a_tif.overlay.0.xs`). `MISSING` when a part is absent."""
    found = report
    for part in dotted.split("."):
        if isinstance(found, dict) and part in found:
            found = found[part]
        elif isinstance(found, list) and part.lstrip("-").isdigit() and -len(found) <= int(part) < len(found):
            found = found[int(part)]
        else:
            return MISSING
    return found


MISSING = object()


def same(found, wanted):
    """Equal as JSON values; numbers within 1e-9 (a coordinate that went through a float32 or a unit factor), everything else exactly."""
    if isinstance(wanted, bool) or isinstance(found, bool):
        return found is wanted
    if isinstance(wanted, (int, float)) and isinstance(found, (int, float)):
        return abs(found - wanted) <= 1e-9 * max(1.0, abs(wanted))
    if isinstance(wanted, list) and isinstance(found, list):
        return len(wanted) == len(found) and all(same(f, w) for f, w in zip(found, wanted))
    if isinstance(wanted, dict) and isinstance(found, dict):
        return found.keys() == wanted.keys() and all(same(found[k], wanted[k]) for k in wanted)
    return found == wanted


KNOWN_EXPECT = {  # every kind of expectation `check` reads; anything else in a case would be silently ignored, so it is a failure of the case
    "at_set", "at", "status", "status_in", "min_progress_events", "approx", "equals", "log_contains", "match", "file_text_contains", "absent", "max", "min", "min_length",
    "contains", "not_contains", "absent_keys", "worker_alive_after",
}


def check(expect, report):
    """Return a list of failed expectations (empty = pass)."""
    failed = ["unknown expectation %r: it would be ignored (a path under `equals` or `at` is what was meant?)" % key for key in expect if key not in KNOWN_EXPECT]
    for dotted, wanted in expect.get("at_set", {}).items():  # a list of coordinate pairs (the vertices of a polygon) as a set: where a ring starts does not matter
        found = walk(report, dotted)
        if found is MISSING or {tuple(round(v, 9) for v in p) for p in found} != {tuple(round(v, 9) for v in p) for p in wanted}:
            failed.append("%s = %r, expected the set %r" % (dotted, None if found is MISSING else found, wanted))
    for dotted, wanted in expect.get("at", {}).items():  # exact value at a path (list indexes allowed; "$absent" = the path must not exist)
        found = walk(report, dotted)
        if wanted == "$absent":
            if found is not MISSING:
                failed.append("%s must be absent, is %r" % (dotted, found))
        elif wanted == "$present":  # there, and not empty
            if found is MISSING or found in (None, "", [], {}):
                failed.append("%s must be present and not empty, is %r" % (dotted, None if found is MISSING else found))
        elif found is MISSING or not same(found, wanted):
            failed.append("%s = %r, expected %r" % (dotted, None if found is MISSING else found, wanted))
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
        found = report.get(key) if "." not in key else lookup(report, key)
        if found != value:
            failed.append("%s = %r, expected %r" % (key, found, value))
    for text in expect.get("log_contains", []):
        if text not in report.get("log_text", ""):
            failed.append("log lacks %r" % text)
    for dotted, text in expect.get("match", {}).items():
        found = walk(report, dotted)  # (a part that is a whole number indexes a list)
        if not isinstance(found, str) or text not in found:
            failed.append("%s = %r does not contain %r" % (dotted, found, text))
    for dotted, text in expect.get("file_text_contains", {}).items():  # a file the report names (a kept result): it exists and holds the text
        path = walk(report, dotted)
        if path is MISSING or not Path(str(path)).is_file():
            failed.append("%s = %r is not a file" % (dotted, None if path is MISSING else path))
        elif text not in Path(path).read_text(encoding="utf-8"):
            failed.append("the file %s lacks %r" % (path, text))
    for dotted, texts in expect.get("absent", {}).items():
        found = report.get(dotted)
        for text in texts:
            if isinstance(found, str) and text in found:
                failed.append("%s must not contain %r" % (dotted, text))
    for key, limit in expect.get("max", {}).items():
        if report.get(key) is None or report[key] > limit:
            failed.append("%s = %r > %r" % (key, report.get(key), limit))
    for key, limit in expect.get("min", {}).items():
        if report.get(key) is None or report[key] < limit:
            failed.append("%s = %r < %r" % (key, report.get(key), limit))
    for dotted, size in expect.get("min_length", {}).items():
        found = report
        for part in dotted.split("."):
            found = found.get(part) if isinstance(found, dict) else None
        if not isinstance(found, list) or len(found) < size:
            failed.append("%s has %s items, expected at least %d" % (dotted, len(found) if isinstance(found, list) else found, size))
    for key, items in expect.get("contains", {}).items():
        for item in items:
            if item not in (lookup(report, key) or []):
                failed.append("%s lacks %r" % (key, item))
    for key, items in expect.get("not_contains", {}).items():
        for item in items:
            if item in (lookup(report, key) or []):
                failed.append("%s must not contain %r" % (key, item))
    for dotted in expect.get("absent_keys", []):
        found = report
        for part in dotted.split("."):
            found = found.get(part) if isinstance(found, dict) else None
        if found is not None:
            failed.append("%s must not be present" % dotted)
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
    / "LabConstrictor_Tools.groovy"
)
JAR_INSTALLED = False
JAR_IN_FIJI = (FIJI or Path(".")) / "plugins" / "labconstrictor-fiji.jar"


def install_into_fiji(case=None):
    """LC_FIJI_MODE=script (default): the loose Groovy script. LC_FIJI_MODE=jar: build the plugin jar and call its menu command.
    Returns the launcher arguments that start the tool."""
    if os.environ.get("LC_FIJI_MODE", "script") == "jar":
        global JAR_INSTALLED
        if not JAR_INSTALLED:  # one build and one copy per run of this script, not per case
            built = subprocess.run(["mvn", "-q", "-B", "package"], cwd=PLUGIN, capture_output=True, text=True)
            if built.returncode:  # the reason must be in the log of the run, not only in the exit code
                raise SystemExit("mvn package failed (exit code %d):\n%s\n%s" % (built.returncode, built.stdout[-4000:], built.stderr[-4000:]))
            shutil.copyfile(next((PLUGIN / "target").glob("labconstrictor-fiji-*.jar")), JAR_IN_FIJI)
            JAR_INSTALLED = True
        shutil.rmtree(SCRIPT_DIR, ignore_errors=True)  # never both: the menu would list the tool twice
        macro = Path(tempfile.mkdtemp()) / "start.ijm"
        options = (case or {}).get("macro")
        macro.write_text('run("LabConstrictor Tools...");\n' if options is None else 'run("LabConstrictor Tools...", "%s");\n' % options)
        return ["-macro", str(macro)]
    JAR_IN_FIJI.unlink(missing_ok=True)
    SCRIPT_DIR.mkdir(parents=True, exist_ok=True)
    (SCRIPT_DIR / "LabConstrictor_Tools.groovy").write_text(GROOVY.read_text())
    shutil.rmtree(SCRIPT_DIR / "lib", ignore_errors=True)
    shutil.copytree(GROOVY.parent / "lib", SCRIPT_DIR / "lib")  # a loose script finds its helpers in the lib folder next to it (the jar has them inside)
    return ["--run", str(SCRIPT_DIR / "LabConstrictor_Tools.groovy")]


def _tail(process, lines=int(os.environ.get("LC_TAIL_LINES", "12"))):
    """Last lines of Fiji's own output: the reason a case hangs or crashes is usually printed there."""
    text = ""
    for stream in (getattr(process, "stdout", None), getattr(process, "stderr", None)):
        if stream:
            text += stream.decode("utf-8", "replace") if isinstance(stream, bytes) else stream
    tail = "\n".join(text.strip().splitlines()[-lines:])
    return "\n--- Fiji output (tail) ---\n" + tail if tail else ""


def run_case(path):
    case = expand(json.loads(path.read_text()))
    case["expect"] = resolve_conformance(case.get("expect", {}))
    name = path.stem
    if case.get("macro") is not None and os.environ.get("LC_FIJI_MODE", "script") != "jar" and os.environ.get("LC_FIJI_MACRO_PROPERTY") == "1":
        case["script_macro"] = case.pop("macro")  # LC_FIJI_MACRO_PROPERTY=1: macro calls through the property the menu command sets, in script mode (no mvn build per case)
    if case.get("make_files") == "calibration":
        case["files_dir"] = tempfile.mkdtemp(prefix="lccal_fiji_")
        make_calibration_files(case["files_dir"])
    case_dir = OUT / name
    case_dir.mkdir(parents=True, exist_ok=True)
    case.update(shots=str(case_dir), report=str(case_dir / "report.json"), script=str(GROOVY))
    (case_dir / "report.json").unlink(missing_ok=True)
    case_file = Path(tempfile.mkdtemp()) / "case.json"
    case_file.write_text(json.dumps(case))
    if case.get("unit_checks") and os.environ.get("LC_FIJI_MODE", "script") == "jar":
        return name, [], {}  # the checks call the functions of the loose script: nothing different to test through the jar
    launch = ["--run", str(HERE / "unit_checks.groovy")] if case.get("unit_checks") else install_into_fiji(case)
    if case.get("macro") is not None and os.environ.get("LC_FIJI_MODE", "script") != "jar":
        return name, [], {}  # macro calls need the menu command of the jar (LC_FIJI_MODE=jar): not applicable to the loose script
    if case.get("script_mode_only") and os.environ.get("LC_FIJI_MODE", "script") == "jar":
        return name, [], {}  # the harness reads the dialog through hooks that the jar's script engine does not report back (checked by eye: the dialog is right)
    env = dict(os.environ, LC_FIJI_HARNESS=str(HERE / "test_harness.groovy"), LC_FIJI_CASE=str(case_file), LC_CONFORMANCE=CONFORMANCE,
               LC_PYTHON=sys.executable, LC_PRUNE_RECORDER=str(HERE / "prune_recorder.py"))
    if case.get("unit_checks"):
        env["LC_UNIT_GROUPS"] = str(HERE)  # the groups kept in tests/unit_groups_*.groovy
        env["LC_FIJI_LIB"] = str(GROOVY.parent / "lib")  # the unit checks run from a script of their own: the helpers come from the repository's folder
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
    if case.get("log_prefill"):  # a shared log already past the rotation size, with five old backups (needs "register": a private home)
        logs = Path(env["LC_HOME"]) / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        (logs / "labconstrictor.log").write_text("filler\n" * 200000)
        for n in range(1, 6):
            (logs / ("labconstrictor.log.%d" % n)).write_text("old backup %d\n" % n)
    if case.get("runs_prefill"):  # old run records plus a folder that is not one (a person's own): the host never deletes any of them (retention is the Tools CLI's job); each holds a sentinel file
        runs = Path(env["LC_HOME"]) / "runs"
        for n in range(case["runs_prefill"]):
            (runs / ("20200101T000000%03d_old_tool" % n)).mkdir(parents=True)
            (runs / ("20200101T000000%03d_old_tool" % n) / "keep.txt").write_text("keep")
        (runs / "my_own_notes").mkdir()
        (runs / "my_own_notes" / "keep.txt").write_text("keep")
    if case.get("retention_prefill"):  # owned, unfinished, older-version and a person's own folders under results/ and runs/ (tests/retention_support.py)
        retention_support.prefill(case["retention_prefill"], Path(env["LC_HOME"]))
    if case.get("last_command"):  # a previous run's command text, as the script leaves it for the "Copy last run as command" entry
        state = Path(env["LC_HOME"]) / "state"
        state.mkdir(parents=True, exist_ok=True)
        (state / "last_command.json").write_text(json.dumps(case["last_command"]))
    for op in case.get("tamper", []):
        tamper(Path(env["LC_HOME"]) / "apps", op)
    env.update(case.get("env", {}))  # variables Fiji itself is started with (a conda or venv the person launched it from)
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
            command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=os.name == "posix", cwd=case.get("cwd")
        )  # "cwd": the folder Fiji is started in (a relative path typed in a dialog resolves against it)
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
    runs = Path(env["LC_HOME"]) / "runs"
    report["run_folders"] = sorted(p.name for p in runs.iterdir()) if runs.is_dir() else []
    report["run_record_count"] = sum(1 for name in report["run_folders"] if name != "my_own_notes")
    report["run_sentinels_intact"] = sum(1 for p in report["run_folders"] if (runs / p / "keep.txt").is_file() and (runs / p / "keep.txt").read_text() == "keep")
    job_dir = (report.get("request") or {}).get("_job_dir")
    report["job_dir_exists"] = Path(job_dir).exists() if job_dir else None  # the host's own temporary folder: removed whatever happened (RET-11)
    report["retention"] = retention_support.inspect(Path(env["LC_HOME"]))
    report["prune_calls"] = retention_support.prune_calls(case_dir)
    report["log_backups"] = {  # first line of each rotated copy of the log
        p.name: (p.read_text(encoding="utf-8", errors="replace").splitlines() or [""])[0]
        for p in sorted(log_file.parent.glob("labconstrictor.log.*"))
    } if log_file.parent.is_dir() else {}
    return name, check(retention_support.substitute(case.get("expect", {}), {"$LC_HOME": env["LC_HOME"]}), report), report


def tamper(apps, op):
    """Damage a registered app the way a hostile or broken registry could be (the host must refuse or explain it).
    Operations: world_writable_bin, schema_replace [old, new], schema_move_to_other_folder, entry {key: value}."""
    entry_file = apps / (op["app"] + ".json")
    entry = json.loads(entry_file.read_text())
    schema_file = Path(entry["schema_path"])
    if op.get("world_writable_bin"):
        prefix = Path(tempfile.mkdtemp(prefix="lcprefix_"))
        (prefix / "bin").mkdir()
        (prefix / "bin" / "python").symlink_to(sys.executable)
        (prefix / "bin").chmod(0o777)
        entry.update(prefix=str(prefix), python=str(prefix / "bin" / "python"))
    if "schema_replace" in op:
        old, new = op["schema_replace"]
        text = schema_file.read_text()
        assert old in text, old
        schema_file.write_text(text.replace(old, new, 1))
    if op.get("schema_move_to_other_folder"):
        elsewhere = Path(tempfile.mkdtemp(prefix="lcschema_")) / schema_file.name
        shutil.move(str(schema_file), str(elsewhere))
        elsewhere.chmod(0o644)
        entry["schema_path"] = str(elsewhere)
    entry.update(op.get("entry", {}))
    entry_file.write_text(json.dumps(entry))
    entry_file.chmod(0o644)
    if schema_file.exists():
        schema_file.chmod(0o644)


PRIVATE_HOME = tempfile.mkdtemp(prefix="lchome_fiji_")
# the shared rules as data (labconstrictor_tools/conformance.json, docs/CONFORMANCE.md): the harness and the unit checks read them from this file, never from copies
CONFORMANCE = subprocess.run(
    [sys.executable, "-m", "labconstrictor_tools.conformance", "--path"], check=True, capture_output=True, text=True
).stdout.strip()


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
