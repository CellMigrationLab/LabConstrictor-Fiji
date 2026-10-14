"""Fiji UI audit: drives the LabConstrictor plugin of an INSTALLED app through its real SciJava dialogs, presses every control, opens every tool's form, runs each tool with the inputs of
the app's own lc_tests/cases.json and takes the screenshots (contract: LabConstrictor-Tools docs/TEST_STRATEGY.md section 3 and tests/release_sandbox/README.md; this file's own docs: docs/UI_AUDIT.md).

    python tests/ui_audit.py        # under a display is NOT needed: every Fiji start is made under xvfb-run

Environment: LC_HOME (registry with the app), LC_FIJI_HOME, LC_APP (or LC_APPS=a,b,c), LC_APP_TESTS (the app's lc_tests; with several apps: a=folder,b=folder), LC_EXPECT_FIJI
(listed | not_listed), LC_SHOTS (default ./screens), LC_UI_TOOL_TIMEOUT (seconds per Fiji start, default 300), LC_UI_TOOLS, LC_UI_PARTS, LC_UI_INJECT, LC_FIJI_MODE=jar.
Prints one line per assertion, `CHECK <id> PASS|FAIL <detail>` (or `NOTE <id> not run: <reason>`), and keeps <LC_SHOTS>/matrix_fiji_<app>.json up to date after every row. It never stops at the first
failure: every control, form and run is its own guarded job, and the exit code (1 when any check is FAIL or NOT_RUN) is decided only after everything ran.
"""

import sys
from pathlib import Path
from typing import Any, Callable, Optional

from ui_audit_app import Audit, expected_ids
from ui_audit_forms import tool_choice_names
from ui_audit_launch import Launch, discover_apps, launch_arguments, run_fiji
from ui_audit_matrix import Matrix
from ui_audit_settings import Settings, read_settings


def multi_verdicts(audits: dict[str, Audit], probes: dict[str, dict[str, Any]], apps: list[str]) -> dict[str, list[tuple[str, str, str]]]:
    """The rows of the several-apps checks, per app: [(id, status, detail)]. ui_fiji_multi_lists_<app>: the chooser lists EVERY app named in LC_APPS and offers every tool of this app
    (the schema decides); ui_fiji_multi_switch_<a>_<b>: after selecting a (every other app first), then b, each shows its own tools and the two lists are different."""
    rows: dict[str, list[tuple[str, str, str]]] = {app: [] for app in apps}
    offered: dict[str, list[str]] = {}
    for app in apps:
        schema, probe = audits[app].schema, probes[app]
        wanted = tool_choice_names(schema) if len(schema["tools"]) > 1 else [schema["tools"][0]["label"]]
        problems = []
        if probe["apps"] is None:
            problems.append("no app chooser was shown (%s)" % probe["launch"].ended())
        else:
            problems += ["the app chooser lacks '%s' (it lists %s)" % (a, probe["apps"]) for a in apps if a not in probe["apps"]]
        if probe["tools"] != wanted:
            problems.append("after choosing '%s' Fiji offers %s, its schema has %s" % (app, probe["tools"], wanted))
        offered[app] = probe["tools"] or []
        rows[app].append(("ui_fiji_multi_lists_" + app, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "the chooser lists %s and offers all %d tool(s) of '%s': %s" % (probe["apps"], len(wanted), app, wanted)))
    for first, second in zip(apps, apps[1:]):
        problems = [] if offered[first] and offered[second] else ["a probe did not reach the tools of '%s' or '%s'" % (first, second)]
        if not problems and offered[first] == offered[second]:
            problems.append("selecting '%s' and then '%s' shows the same tools %s" % (first, second, offered[first]))
        rows[first].append(("ui_fiji_multi_switch_%s_%s" % (first, second), "FAIL" if problems else "PASS", "; ".join(problems) if problems else
                            "choosing '%s' offers %s, choosing '%s' offers %s" % (first, offered[first], second, offered[second])))
    return rows


def main(settings: Settings, runner: Callable[..., Launch] = run_fiji, arguments: Callable[..., list[str]] = launch_arguments, discover: Callable[..., Any] = discover_apps,
         out: Optional[Any] = None) -> int:
    matrices = {app: Matrix(settings.shots, app, settings.parts, out) for app in settings.apps}
    audits = {app: Audit(settings, app, matrices[app], runner, arguments, discover) for app in settings.apps}
    for app, audit in audits.items():
        ids = expected_ids(audit.schema, settings, audit.tool_ids(), settings.apps if settings.multi else [], app) if audit.schema else ["ui_fiji_driver_crashed"]
        matrices[app].expect(ids)
    if len(settings.parts) < 2:
        for matrix in matrices.values():
            matrix.add("ui_fiji_parts", "HUMAN", "only %s ran (LC_UI_PARTS)" % settings.parts)
    rows: dict[str, list[tuple[str, str, str]]] = {app: [] for app in settings.apps}
    if settings.multi and settings.listed and all(a.schema for a in audits.values()):
        probes = {app: audit.probe_chooser() for app, audit in audits.items()}
        rows = multi_verdicts(audits, probes, settings.apps)
    code = 0
    for app, audit in audits.items():
        def write_multi(app: str = app) -> None:
            for check_id, status, detail in rows[app]:
                matrices[app].add(check_id, status, detail)

        code = max(code, audit.audit(write_multi))
    return code


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    sys.exit(main(read_settings()))
