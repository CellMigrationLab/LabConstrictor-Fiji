"""Negative controls of the Fiji UI audit: every seeded failure must turn the audit RED, with the id and the reason it names. A driver that cannot fail proves nothing.

    python tests/ui_audit_negative.py [scenario ...]          # LC_FIJI_HOME is required; no scenario names = all of them

Each scenario registers its apps in a scratch LC_HOME, starts tests/ui_audit.py as a subprocess (the real driver, real Fiji under xvfb) with one seeded failure and checks that the lines
`CHECK <id> FAIL <detail>` it prints contain the expected ids and words. Prints `NEGATIVE <scenario> PASS|FAIL <what was seen>` and, for the record, the real CHECK lines of the audit;
exit 1 when a seeded failure did NOT go red. The seeds: a control removed from the form, a control added, a dialog whose OK button is off the screen, blank screenshots, a tool whose run
fails, a tool that completes with a wrong value, a tool that completes and shows nothing (no value, an empty image), a tool that hangs (the watchdog kills it and the audit goes on with
the next tool), an app that is listed when it must not be, an app that is hidden but expected listed.
"""

import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

HERE = Path(__file__).resolve().parent
APPS = HERE / "ui_audit_apps"
CHECK_LINE = re.compile(r"^CHECK (\S+) (PASS|FAIL) ?(.*)$")
SYNTHETIC = ["--module", "labconstrictor_tools.examples.synthetic"]
BLANK = ["--module", "tools", "--pythonpath", str(APPS / "negative" / "blank_app")]


@dataclass
class Scenario:
    name: str
    app: str
    tests: Path
    tools: str
    env: dict[str, str] = field(default_factory=dict)
    red: list[tuple[str, str]] = field(default_factory=list)       # (check id, a word its FAIL detail must contain)
    green: list[str] = field(default_factory=list)                 # ids that must still PASS (the audit went on after the failure)
    timeout: str = "120"


SCENARIOS = [
    Scenario("removed_control", "synthetic", APPS / "synthetic" / "lc_tests", "scalar_echo", {"LC_UI_INJECT": "remove_control", "LC_UI_PARTS": "tools"},
             red=[("ui_fiji_form_scalar_echo_opens", "missing")]),
    Scenario("extra_control", "synthetic", APPS / "synthetic" / "lc_tests", "scalar_echo", {"LC_UI_INJECT": "extra_control", "LC_UI_PARTS": "tools"},
             red=[("ui_fiji_form_scalar_echo_opens", "Injected"), ("ui_fiji_controls_all_known", "Injected")]),
    Scenario("ok_off_screen", "synthetic", APPS / "synthetic" / "lc_tests", "scalar_echo", {"LC_UI_INJECT": "offscreen_ok", "LC_UI_PARTS": "tools"},
             red=[("ui_fiji_form_scalar_echo_opens", "not on the screen")]),
    Scenario("blank_screenshots", "synthetic", APPS / "synthetic" / "lc_tests", "scalar_echo", {"LC_UI_INJECT": "blank_screens", "LC_UI_PARTS": "tools"},
             red=[("ui_fiji_screens_scalar_echo", "flat colour")]),
    Scenario("tool_fails_and_wrong_value", "synthetic", APPS / "negative" / "lc_tests", "explode,scalar_echo", {"LC_UI_PARTS": "tools"},
             red=[("ui_fiji_run_explode", "FAILED"), ("ui_fiji_run_scalar_echo", "999")]),
    Scenario("blank_results", "blank", APPS / "negative" / "blank_app_tests", "blank_values,blank_image", {"LC_UI_PARTS": "tools"},
             red=[("ui_fiji_run_blank_values", "empty or blank"), ("ui_fiji_run_blank_image", "bad_return")]),
    Scenario("hung_tool_then_next_tool", "synthetic", APPS / "negative" / "lc_tests", "stubborn,scalar_echo", {"LC_UI_PARTS": "tools"},
             red=[("ui_fiji_run_stubborn", "watchdog"), ("ui_fiji_run_scalar_echo", "999")], green=["ui_fiji_form_scalar_echo_opens"], timeout="75"),
    Scenario("listed_but_must_be_hidden", "synthetic", APPS / "synthetic" / "lc_tests", "scalar_echo", {"LC_EXPECT_FIJI": "not_listed"},
             red=[("ui_fiji_app_not_in_chooser", "must not be")]),
    Scenario("hidden_but_expected_listed", "hidden", APPS / "synthetic" / "lc_tests", "scalar_echo", {"LC_EXPECT_FIJI": "listed"},
             red=[("ui_fiji_driver_crashed", "hidden")]),
]


def register(home: str, python: str, name: str, arguments: list[str], hosts: Optional[str] = None) -> None:
    """Register an app in the scratch registry with the Tools CLI, as an installer does."""
    command = [python, "-m", "labconstrictor_tools", "register", "--name", name, "--prefix", sys.prefix, "--version", "0.0", *arguments]
    command += ["--hosts", hosts] if hosts else []
    done = subprocess.run(command, env=dict(os.environ, LC_HOME=home), capture_output=True, text=True, check=False)
    if done.returncode:
        raise SystemExit("could not register %s: %s" % (name, done.stderr[-500:]))


def run_scenario(scenario: Scenario, home: str, shots: Path) -> list[str]:
    """Run the audit with the seed; return the problems (empty: every expected red line was seen, every expected green one too)."""
    env = dict(os.environ, LC_HOME=home, LC_APP=scenario.app, LC_APP_TESTS=str(scenario.tests), LC_SHOTS=str(shots / scenario.name), LC_EXPECT_FIJI="listed",
               LC_UI_TOOLS=scenario.tools, LC_UI_TOOL_TIMEOUT=scenario.timeout)
    env.update(scenario.env)
    done = subprocess.run([sys.executable, str(HERE / "ui_audit.py")], env=env, capture_output=True, text=True, check=False)
    lines = {m.group(1): (m.group(2), m.group(3)) for line in done.stdout.splitlines() if (m := CHECK_LINE.match(line))}
    for line in done.stdout.splitlines():
        if line.startswith(("CHECK", "NOTE")):
            print("    " + line[:300])
    problems = []
    if done.returncode != 1:
        problems.append("the audit exited with %d, expected 1 (red)%s" % (done.returncode, (": " + done.stderr[-300:]) if done.stderr else ""))
    for check_id, word in scenario.red:
        status, detail = lines.get(check_id, ("missing", ""))
        if status != "FAIL" or word not in detail:
            problems.append("%s was %s %r, expected FAIL naming %r" % (check_id, status, detail[:120], word))
    for check_id in scenario.green:
        if lines.get(check_id, ("missing", ""))[0] != "PASS":
            problems.append("%s was %s, expected PASS (the audit must go on after a failure)" % (check_id, lines.get(check_id, ("missing",))[0]))
    return problems


def main(names: list[str]) -> int:
    if not os.environ.get("LC_FIJI_HOME"):
        raise SystemExit("ui_audit_negative.py: set LC_FIJI_HOME")
    wanted = [s for s in SCENARIOS if not names or s.name in names]
    unknown = sorted(set(names) - {s.name for s in SCENARIOS})
    if unknown:
        raise SystemExit("unknown scenario(s) %s; known: %s" % (unknown, [s.name for s in SCENARIOS]))
    scratch = Path(tempfile.mkdtemp(prefix="lcaudit_negative_"))
    home = str(scratch / "home")
    register(home, sys.executable, "synthetic", SYNTHETIC)
    register(home, sys.executable, "blank", BLANK)
    register(home, sys.executable, "hidden", SYNTHETIC, hosts="notebook")
    failed = 0
    for scenario in wanted:
        print("NEGATIVE %s: seeding the failure (%s); the audit says:" % (scenario.name, scenario.env or "see the cases"), flush=True)
        problems = run_scenario(scenario, home, scratch / "shots")
        failed += bool(problems)
        print("NEGATIVE %s %s %s" % (scenario.name, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "went red as it must: " + ", ".join("%s (%s)" % r for r in scenario.red)), flush=True)
    print("%d of %d seeded failure(s) did NOT go red" % (failed, len(wanted)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
