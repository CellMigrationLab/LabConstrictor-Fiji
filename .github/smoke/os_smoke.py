"""Start Fiji with the release jar in plugins/ on this machine's display and run os_smoke.groovy (see it for what is checked).

    python os_smoke.py <Fiji.app folder> <jar> <output folder>

Registers the Tools example app in a scratch LC_HOME with the interpreter that runs this script. Exit code: 0 pass, 1 fail,
2 cannot show a GUI on this runner. The report, the registry entry and Fiji's output go to the output folder and the job summary."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

fiji, jar, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve()
out.mkdir(parents=True, exist_ok=True)
here = Path(__file__).resolve().parent
home = Path(tempfile.mkdtemp(prefix="lchome_smoke_"))
summary = []


def say(line):
    print(line, flush=True)
    summary.append(line)


def done(code, verdict):
    say("**%s**" % verdict)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write("\n".join(["### Fiji GUI smoke on %s" % sys.platform] + ["* " + s for s in summary]) + "\n")
    print("::%s::Fiji GUI smoke on %s: %s" % ("notice" if code == 0 else "error", sys.platform, verdict))
    sys.exit(code)


plugins = next(p for p in (fiji / "plugins", fiji / "Contents" / "Resources" / "plugins") if p.is_dir())
shutil.copyfile(jar, plugins / "labconstrictor-fiji.jar")
say("jar installed in %s" % plugins)

env = dict(os.environ, LC_HOME=str(home), LC_SMOKE_REPORT=str(out / "report.json"), LC_SMOKE_APP="synthetic")
registered = subprocess.run(
    [sys.executable, "-m", "labconstrictor_tools", "register", "--name", "synthetic", "--prefix", sys.prefix,
     "--module", "labconstrictor_tools.examples.synthetic", "--version", "0.0"],
    capture_output=True, text=True, env=env)
if registered.returncode:
    done(1, "FAIL: could not register the example app: " + (registered.stderr or registered.stdout)[-300:])
entry = json.loads((home / "apps" / "synthetic.json").read_text(encoding="utf-8"))
say("registered interpreter: %s (exists: %s; inside prefix %s: %s)" % (
    entry["python"], Path(entry["python"]).exists(), entry["prefix"], os.path.abspath(entry["python"]).startswith(os.path.abspath(entry["prefix"]) + os.sep)))
(out / "registry_entry.json").write_text(json.dumps(entry, indent=1), encoding="utf-8")

launcher = next((p for p in sorted(fiji.rglob("*")) if p.is_file() and re.fullmatch(r"(fiji|ImageJ)-(windows|win|macos|macosx)[-a-z0-9]*(\.exe)?", p.name, re.I)), None)
if launcher is None:
    done(1, "FAIL: no Fiji launcher found in %s" % fiji)
say("launcher: %s" % launcher.relative_to(fiji))
try:
    result = subprocess.run([str(launcher), "--allow-multiple", "--run", str(here / "os_smoke.groovy")], env=env,
                            capture_output=True, text=True, timeout=240, errors="replace")
    output, code = result.stdout + result.stderr, result.returncode
except subprocess.TimeoutExpired as problem:
    output, code = ((problem.stdout or b"").decode("utf-8", "replace") if isinstance(problem.stdout, bytes) else (problem.stdout or "")), None
(out / "fiji_output.txt").write_text(output, encoding="utf-8")
for log in (home / "logs").glob("*.log"):
    shutil.copyfile(log, out / log.name)
report_file = out / "report.json"
report = json.loads(report_file.read_text(encoding="utf-8")) if report_file.exists() else {}
say("Fiji exit code: %s; report stage: %s" % (code, report.get("stage")))
for key in ("os", "arch", "java", "imagej", "headless", "menu_entry", "dialog_opened", "app_listed", "dialog_titles", "dialog_texts", "problem"):
    if key in report:
        say("%s: %s" % (key, report[key]))
tail = "\n".join(output.strip().splitlines()[-15:])
if code == 0 and report.get("menu_entry") and report.get("app_listed"):
    done(0, "PASS: menu entry present and the dialog lists the registered app")
if report.get("headless") or re.search(r"HeadlessException|Can't connect to X11|no display", output):
    done(2, "CANNOT SHOW A GUI on this runner (see fiji_output.txt)\n" + tail)
if not report:
    done(1, "FAIL: Fiji wrote no report (did not start, or the script did not run)\n" + tail)
done(1, "FAIL: %s\n%s" % (report.get("problem") or "menu entry present: %s, app listed: %s" % (report.get("menu_entry"), report.get("app_listed")), tail))
