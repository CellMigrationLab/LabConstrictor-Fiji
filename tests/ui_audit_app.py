"""The audit of ONE app in Fiji: which Fiji starts it makes (see tests/ui_audit.py) and how each is judged.

Every job is its own Fiji process under a watchdog (tests/ui_audit_launch.py); the harness (tests/ui_audit_body.groovy) acts and reports, and every verdict is made HERE, from the app's schema,
its cases, the files on disk and the clipboard text, never from the state of the dialog under test. Whatever a start did not reach is recorded (FAIL when the watchdog killed it, NOT_RUN
otherwise) and the audit goes on with the next job.
"""

import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Callable, Optional

from ui_audit_forms import (absent_problems, chooser_problems, control_table, form_problems, geometry_problems, norm, tool_choice_names, window_problems)
from ui_audit_launch import Launch, discover_apps, launch_arguments, run_fiji
from ui_audit_matrix import Matrix, sanitize
from ui_audit_runs import (absolute_inputs, build_fills, is_ordered, load_cases, load_no_case, number_text, order_tools, preload_for, replay_before, run_problems,
                           screenshot_problems, tool_case)
from ui_audit_settings import Settings

HERE = Path(__file__).resolve().parent
ALLOWED_FRAMES = ["Log", "ROI Manager", "Results"]
COPY_OPTIONS = {"empty": "copy_last=true", "terminal": "copy_last=true kind=terminal", "python": "copy_last=true kind=python"}
COPY_ENTRY = "Copy last run as command"                  # the jar's menu entry that copies the last command
MENU_SCRIPT = ["labconstrictor tools"]
MENU_JAR = ["labconstrictor tools...", "copy last run as command"]
PANEL_IDS = ["chooser_app", "chooser_tool", "chooser_ok", "chooser_cancel", "form_ok", "form_cancel", "menu", "copy_empty", "copy_terminal", "copy_python", "log"]
Runner = Callable[..., Launch]


def expected_ids(schema: dict[str, Any], settings: Settings, tool_ids: list[str], multi_apps: list[str], app: str) -> list[str]:
    """Every id the audit of `app` intends to produce (written to expected_fiji_<app>.json before anything runs)."""
    if not settings.listed:
        return ["ui_fiji_app_not_in_chooser", "ui_fiji_forms_not_checked"]
    ids = ["ui_fiji_controls_all_known"]
    if "panel" in settings.parts:
        ids += ["ui_fiji_panel_" + name for name in PANEL_IDS]
    if "tools" in settings.parts:
        for tool_id in tool_ids:
            key = sanitize(tool_id)
            ids += ["ui_fiji_form_%s_opens" % key, "ui_fiji_form_%s_controls" % key, "ui_fiji_run_%s" % key, "ui_fiji_screens_%s" % key]
    if multi_apps:
        ids.append("ui_fiji_multi_lists_" + app)
        ids += ["ui_fiji_multi_switch_%s_%s" % (a, b) for a, b in zip(multi_apps, multi_apps[1:]) if a == app]
    return ids


class Audit:
    def __init__(self, settings: Settings, app: str, matrix: Matrix, runner: Runner = run_fiji, arguments: Callable[..., list[str]] = launch_arguments,
                 discover: Callable[..., Any] = discover_apps) -> None:
        self.s, self.app, self.m, self.runner, self.arguments, self.discover = settings, app, matrix, runner, arguments, discover
        entry = Path(settings.home) / "apps" / ("%s.json" % app)
        self.entry: Optional[dict[str, Any]] = json.loads(entry.read_text(encoding="utf-8")) if entry.is_file() else None
        self.schema: Optional[dict[str, Any]] = json.loads(Path(self.entry["schema_path"]).read_text(encoding="utf-8")) if self.entry else None
        self.shots_dir = settings.shots / "fiji" / app
        self.tests_dir = settings.tests.get(app, Path("."))
        self.began = time.time()
        self.listed_apps: list[str] = []
        self.unknown: list[str] = []
        self.dialogs = {"chooser": 0, "form": 0}
        self.controls_seen = 0
        self.absent: list[str] = []
        self.last_run: Optional[tuple[dict[str, Any], dict[str, Any]]] = None
        self.logged = False
        self.replays = 0
        self.prompts = 0

    # ---------------------------------------------------------------- one Fiji start
    def start(self, mode: str, tool: Optional[dict[str, Any]] = None, emit_panel: bool = False, fills: Optional[list[dict[str, Any]]] = None,
              preload: Optional[list[dict[str, str]]] = None, option: Optional[str] = None, cwd: Optional[Path] = None) -> tuple[Launch, list[dict[str, Any]]]:
        """One Fiji start for `mode` (see tests/ui_audit_body.groovy); returns it and the activation rows it reported (they are in the matrix already)."""
        assert self.schema is not None
        names = tool_choice_names(self.schema)
        first = self.schema["tools"][0]
        chosen = tool or first
        plan = {"mode": mode, "app": self.app, "tool_choice": names[self.schema["tools"].index(chosen)], "tool_label": chosen["label"], "tool_id": chosen["id"],
                "tool_key": sanitize(chosen["id"]), "shots": str(self.shots_dir), "emit_panel": emit_panel, "fills": fills or [], "preload": preload or [],
                "script_macro": option if mode == "copy" else None, "inject": self.s.inject if mode in ("controls", "run") else None}
        work = Path(tempfile.mkdtemp(prefix="lcaudit_plan_"))
        (work / "plan.json").write_text(json.dumps(plan), encoding="utf-8")
        env = dict(os.environ, LC_HOME=self.s.home, LC_FIJI_HARNESS=os.environ.get("LC_BODY_OVERRIDE", str(HERE / "ui_audit_body.groovy")), LC_UI_PLAN=str(work / "plan.json"),
                   LC_UI_OUT=str(work / "out.jsonl"), LC_UI_TREE=os.environ.get("LC_UI_TREE_OVERRIDE", str(HERE / "ui_audit_tree.groovy")), LC_UI_CONTROLS=str(HERE / "ui_audit_controls.json"))
        rows: list[dict[str, Any]] = []

        def route(record: dict[str, Any]) -> None:
            if record.get("kind") == "check":
                rows.append(record)
                self.m.add(record["id"], record["status"], record["detail"], tool=record.get("tool"), control=record.get("control"), seconds=record.get("seconds", 0.0))

        arguments = self.arguments(option if mode == "copy" else None, COPY_ENTRY if mode == "copy" else None, self.s.jar)
        self.began = time.time()
        launch = self.runner(self.s.fiji, arguments, env, work / "out.jsonl", self.s.timeout, route, cwd or self.tests_dir)
        if not launch.timed_out and launch.returncode not in (0, 2, 3) and not launch.records:   # Fiji itself failed to start (nothing of the harness ran): once more, and say so
            self.m.add("ui_fiji_start_retry", "NOTE", "Fiji exited with %s after %.0f s before the harness wrote anything (%s); started again" % (launch.returncode, launch.seconds, launch.tail), tool=chosen["id"])
            launch = self.runner(self.s.fiji, arguments, env, work / "out.jsonl", self.s.timeout, route, cwd or self.tests_dir)
        for record in launch.records:
            if record.get("kind") == "components":
                self.record_components(record)
        return launch, rows

    def record_components(self, record: dict[str, Any]) -> None:
        self.dialogs["chooser" if record["scope"].startswith("chooser") else "form"] += 1
        self.controls_seen += len(record["controls"])
        self.unknown += ["%s: %s" % (record["scope"], u) for u in record["unknown"]]
        self.absent += ["%s: %s" % (record["scope"], p) for p in absent_problems(record["controls"])]

    def conclude(self, ids: list[str], launch: Launch, tool: Optional[str] = None) -> None:
        """Every id this start was meant to produce that has no row yet: FAIL when the watchdog killed it, NOT_RUN otherwise (the reason says how Fiji ended)."""
        for check_id in ids:
            if not self.m.has_row(check_id):
                reason = "; ".join(filter(None, [launch.fatal(), launch.ended()]))
                self.m.add(check_id, "FAIL" if launch.timed_out else "NOT_RUN", "not reached: " + reason, tool=tool, seconds=launch.seconds)

    def runs_now(self) -> set[str]:
        runs = Path(self.s.home) / "runs"
        return {p.name for p in runs.iterdir()} if runs.is_dir() else set()

    def files_since(self, before: set[str]) -> dict[str, Any]:
        new = sorted(self.runs_now() - before)
        runs = Path(self.s.home) / "runs"
        return {"new_runs": new, "record_files": sorted(p.name for p in (runs / new[0]).iterdir()) if len(new) == 1 else [],
                "log_file": (Path(self.s.home) / "logs" / "labconstrictor.log").is_file()}

    @staticmethod
    def obs(launch: Launch, what: str) -> list[dict[str, Any]]:
        return [r for r in launch.records if r.get("kind") == "obs" and r.get("what") == what]

    def click_problems(self, launch: Launch, ids: list[str]) -> list[str]:
        return [r["detail"] for r in self.obs(launch, "click_failed") if r["id"] in ids]

    # ---------------------------------------------------------------- the panel: choosers, menu, copy as command, log
    def listing(self) -> bool:
        """Ask Fiji (the plugin's own discovery) what it lists; assert it against the expectation. True when the audit of forms goes on."""
        found = self.discover(self.s.home, self.s.fiji)
        if isinstance(found, str):
            self.m.add("ui_fiji_driver_crashed", "FAIL", "Fiji could not be asked what it lists: " + found)
            return False
        apps, _problems = found
        self.listed_apps = sorted(apps)
        if not self.s.listed:
            self.m.add("ui_fiji_app_not_in_chooser", "FAIL" if self.app in apps else "PASS", "'%s' is offered but must not be (Fiji lists: %s)" % (self.app, ", ".join(sorted(apps)))
                       if self.app in apps else "'%s' is not in the chooser (Fiji lists: %s)" % (self.app, ", ".join(sorted(apps)) or "nothing"))
            self.m.add("ui_fiji_forms_not_checked", "HUMAN", "'%s' is not listed in Fiji, so there is no form to check" % self.app)
            return False
        wanted = sorted(t["id"] for t in (self.schema or {"tools": []})["tools"])
        if self.app not in apps or sorted(apps[self.app]) != wanted:
            self.m.add("ui_fiji_driver_crashed", "FAIL", "Fiji lists %s for '%s', its schema has %s: the forms cannot be audited" % (apps.get(self.app), self.app, wanted))
            return False
        return True

    def probe_chooser(self) -> dict[str, Any]:
        """The multi-app probe: select this app (every other one first), OK, read what is offered next, Cancel."""
        launch, _rows = self.start("chooser")
        apps = next((o["items"] for o in self.obs(launch, "dialog") if o["dialog"] == "app"), None)
        tools = next((o["items"] for o in self.obs(launch, "dialog") if o["dialog"] == "tool"), None)
        if tools is None:
            tools = [o["title"] for o in self.obs(launch, "dialog") if o["dialog"] == "form"] or None
        return {"apps": apps, "tools": tools, "launch": launch}

    def judge_chooser(self, launch: Launch, which: str, wanted_items: list[str], preload: list[dict[str, str]]) -> None:
        check_id = "ui_fiji_panel_chooser_" + which
        record = next((r for r in launch.records if r.get("kind") == "components" and r["scope"] == "chooser_" + which), None)
        if record is None:
            return
        items = next((o["items"] for o in self.obs(launch, "dialog") if o["dialog"] == which), [])
        title = next((o["title"] for o in self.obs(launch, "dialog") if o["dialog"] == which), "")
        problems = chooser_problems(which, record["controls"], items, wanted_items) + geometry_problems(record["geometry"], record["controls"])
        problems += window_problems(record["windows"], record["geometry"]["screen"], [title], [p["title"] for p in preload] + ALLOWED_FRAMES, "at the %s chooser" % which)
        subs = [r for r in launch.records if r.get("kind") == "check" and r["id"] == check_id]
        problems += ["activation: %s: %s" % (r["control"], r["detail"]) for r in subs if r["status"] == "FAIL"]
        problems += [] if subs else ["the chooser's items were never activated"]
        self.m.add(check_id, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "lists %s; the combo box, OK and Cancel are all it holds; each item selected and read back" % items,
                   seconds=launch.seconds)

    def panel_cancel(self, tool_count: int) -> None:
        if len(self.listed_apps) < 2 and tool_count < 2:
            self.m.add("ui_fiji_panel_chooser_cancel", "HUMAN", "not applicable: Fiji shows no chooser for one app with one tool")
            return
        before = self.runs_now()
        launch, _rows = self.start("cancel")
        finish = launch.finish()
        problems = self.click_problems(launch, ["chooser_cancel"]) + self.ignored_cancel(launch, finish)
        if finish is not None:
            summary = finish["summary"]
            problems += [] if summary.get("cancelled") is True else ["the summary does not say cancelled"]
            problems += ["a run started (status %r)" % summary["status"]] if "status" in summary else []
        problems += ["%d run record(s) were created" % len(self.runs_now() - before)] if self.runs_now() - before else []
        self.m.add("ui_fiji_panel_chooser_cancel", "FAIL" if problems else "PASS", "; ".join(problems) if problems else "Cancel on the first chooser ended the plugin: cancelled, no run, no worker, no run record",
                   seconds=launch.seconds)
        self.conclude(["ui_fiji_panel_chooser_cancel"], launch)

    def ignored_cancel(self, launch: Launch, finish: Optional[dict[str, Any]]) -> list[str]:
        """What a Cancel that did not end the plugin did instead (the harness reports the dialogs showing; the run the plugin went on to make is a record under runs/)."""
        if finish is not None:
            return []
        said = [o["detail"] for o in self.obs(launch, "cancel_ignored")]
        return said or ["the plugin did not end after Cancel (%s)" % launch.ended()]

    def last_command(self) -> Optional[dict[str, Any]]:
        path = Path(self.s.home) / "state" / "last_command.json"
        return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None

    def panel_copy_empty_and_menu(self) -> None:
        existing = self.last_command()
        launch, _rows = self.start("copy", option=COPY_OPTIONS["empty"])
        finish = launch.finish()
        if finish is None:
            self.conclude(["ui_fiji_panel_copy_empty", "ui_fiji_panel_menu"], launch)
            return
        summary = finish["summary"]
        if existing is not None:
            self.m.add("ui_fiji_panel_copy_empty", "HUMAN", "LC_HOME already holds a last command (state/last_command.json), so the 'nothing to copy yet' state cannot be tested here")
        else:
            problems = [] if "nothing to copy yet" in str(summary.get("error")) else ["the entry said %r instead of 'nothing to copy yet'" % summary.get("error")]
            problems += ["something was copied: %r" % summary["copied_last"]] if summary.get("copied_last") else []
            self.m.add("ui_fiji_panel_copy_empty", "FAIL" if problems else "PASS", "; ".join(problems) if problems else "with no earlier run the entry says 'nothing to copy yet' and copies nothing", seconds=launch.seconds)
        menu = finish.get("menu")
        wanted = MENU_JAR if self.s.jar else MENU_SCRIPT
        found = sorted(norm(x) for x in menu) if menu is not None else None
        self.m.add("ui_fiji_panel_menu", "PASS" if found == sorted(wanted) else "FAIL",
                   "Plugins > LabConstrictor holds %s (%s)" % (found, "jar" if self.s.jar else "loose script") if found == sorted(wanted)
                   else "Plugins > LabConstrictor holds %s, expected %s (%s)" % (found, sorted(wanted), "jar" if self.s.jar else "loose script"), seconds=launch.seconds)

    def panel_copy(self, kind: str) -> None:
        check_id = "ui_fiji_panel_copy_" + kind
        last = self.last_command()
        if self.last_run is None or last is None:
            self.m.add(check_id, "NOT_RUN", "no tool completed a run in this audit, so there is no command to copy")
            return
        launch, _rows = self.start("copy", option=COPY_OPTIONS[kind])
        finish = launch.finish()
        if finish is None:
            self.conclude([check_id], launch)
            return
        want, summary, (tool, inputs) = last[kind], finish["summary"], self.last_run
        problems = []
        if summary.get("copied_last") != want:
            problems.append("the entry copied %r, the saved last command is %r" % (summary.get("copied_last"), want))
        if finish.get("clipboard_error"):
            problems.append("the clipboard cannot be read: %s" % finish["clipboard_error"])
        elif finish.get("clipboard") != want:
            problems.append("the clipboard holds %r, expected the saved last command" % finish.get("clipboard"))
        problems += self.command_problems(kind, want, tool, inputs, last)
        self.m.add(check_id, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "the clipboard holds the %s of the last run (%s: %s), equal to the saved command" % (
            "terminal line" if kind == "terminal" else "Python snippet", last.get("app"), last.get("tool")), seconds=launch.seconds)

    def command_problems(self, kind: str, text: str, tool: dict[str, Any], inputs: dict[str, Any], last: dict[str, Any]) -> list[str]:
        """The copied text has the form HOST_FEATURES gives (checked against the registry entry and the case, not against the text's own source)."""
        problems = [] if last.get("tool") == tool["id"] else ["the saved command is for '%s', the last run was '%s'" % (last.get("tool"), tool["id"])]
        if kind == "python":
            return problems + ([] if "run_once(" in text and tool["id"] in text else ["the Python snippet has no client.run_once call for '%s'" % tool["id"]])
        tokens = shlex.split(text)
        python = (self.entry or {}).get("python")
        if tokens[:4] != [python, "-m", "labconstrictor_tools", "run"]:
            problems.append("the line starts %s, expected [%r, '-m', 'labconstrictor_tools', 'run']" % (tokens[:4], python))
        if tool["id"] not in tokens[4:6]:
            problems.append("the line does not name the tool '%s'" % tool["id"])
        for p in tool["inputs"]:
            value = inputs.get(p["name"])
            if p["type"] in ("integer", "float", "string", "boolean", "choice") and value is not None and not p.get("pixel_size_of"):
                shown = {str(value), number_text(value), str(value).lower()}
                if not any(token.split("=", 1)[0] == p["name"] and token.split("=", 1)[-1] in shown for token in tokens):
                    problems.append("the line lacks %s=%s" % (p["name"], value))
        return problems

    # ---------------------------------------------------------------- the tools
    def tool_ids(self) -> list[str]:
        assert self.schema is not None
        ids = [t["id"] for t in self.schema["tools"]]
        if self.s.tools is not None:
            unknown = [t for t in self.s.tools if t not in ids]
            if unknown:
                raise SystemExit("ui_audit.py: LC_UI_TOOLS names %s, not tools of '%s' (%s)" % (unknown, self.app, ids))
            ids = [t for t in ids if t in self.s.tools]
        return ids

    def judge_form(self, tool: dict[str, Any], launch: Launch, rows: list[dict[str, Any]], preload: list[dict[str, str]], emit_panel: bool, before: set[str]) -> None:
        key = sanitize(tool["id"])
        record = next((r for r in launch.records if r.get("kind") == "components" and r["scope"] == "form_" + key), None)
        prompt = next(iter(self.obs(launch, "single_input_prompt")), None)
        if record is None and prompt is not None:
            self.prompts += 1
            fine = len(tool["inputs"]) == 1 and prompt["open"] and tool["inputs"][0]["type"] in ("file", "table", "image", "labels", "folder")
            detail = "one declared parameter, shown as SciJava's native file prompt '%s' (Fiji's way for a tool with one file input; there is no form to read)" % prompt["title"]
            self.m.add("ui_fiji_form_%s_opens" % key, "PASS" if fine else "FAIL", detail if fine else "a native file prompt '%s' appeared for a tool with %d input(s)" % (prompt["title"], len(tool["inputs"])), tool=tool["id"], seconds=launch.seconds)
            self.m.add("ui_fiji_form_%s_controls" % key, "HUMAN", "the only control is SciJava's native file prompt; the run check puts the case's file into it and presses OK", tool=tool["id"])
            self.conclude(["ui_fiji_form_%s_opens" % key], launch, tool["id"])
            return
        if record is not None:
            problems = form_problems(tool, record["controls"], record["texts"], bool(preload)) + geometry_problems(record["geometry"], record["controls"])
            problems += window_problems(record["windows"], record["geometry"]["screen"], [tool["label"]], [p["title"] for p in preload] + ALLOWED_FRAMES, "at the form")
            problems += absent_problems(record["controls"]) + [d["detail"] for d in self.obs(launch, "dialog_problem")]
            problems += ["unknown control(s), not in the control table: " + ", ".join(record["unknown"])] if record["unknown"] else []
            self.m.add("ui_fiji_form_%s_opens" % key, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "%d parameter(s): every declared widget found and nothing else but OK and Cancel; "
                       "inside the %dx%d screen; no other window or dialog" % (len(tool["inputs"]), *record["geometry"]["screen"]), tool=tool["id"], seconds=launch.seconds)
        plan = next(iter(self.obs(launch, "activation_plan")), None)
        done = [r for r in rows if r["id"] == "ui_fiji_form_%s_controls" % key]
        if plan is not None:
            failed = [r for r in done if r["status"] == "FAIL"]
            if plan["n"] == 0 and not tool["inputs"]:
                status, detail = "PASS", "the tool declares no parameter: the form holds only OK and Cancel (checked by _opens), so there is nothing to activate"
            elif plan["n"] == 0:
                status, detail = "FAIL", "no control of the form could be activated (0 found)"
            elif failed:
                status, detail = "FAIL", "%d of %d control(s) failed: %s" % (len(failed), plan["n"], "; ".join("%s: %s" % (r["control"], r["detail"]) for r in failed))
            elif len(done) < plan["n"]:
                status, detail = "FAIL", "only %d of %d control(s) were activated (%s)" % (len(done), plan["n"], launch.ended())
            else:
                status, detail = "PASS", "%d control(s) activated and read back: %s" % (plan["n"], ", ".join(sorted(set(plan["ids"]))))
            self.m.add("ui_fiji_form_%s_controls" % key, status, detail, tool=tool["id"], seconds=launch.seconds)
        if emit_panel:
            self.judge_form_cancel(launch, before)
        self.conclude(["ui_fiji_form_%s_opens" % key, "ui_fiji_form_%s_controls" % key], launch, tool["id"])

    def judge_form_cancel(self, launch: Launch, before: set[str]) -> None:
        finish = launch.finish()
        problems = self.click_problems(launch, ["form_cancel"]) + self.ignored_cancel(launch, finish)
        if finish is not None:
            problems += [] if finish["summary"].get("cancelled") is True else ["the summary does not say cancelled"]
            problems += ["a run started (status %r)" % finish["summary"]["status"]] if "status" in finish["summary"] else []
        new = self.runs_now() - before
        problems += ["%d run record(s) were created" % len(new)] if new else []
        self.m.add("ui_fiji_panel_form_cancel", "FAIL" if problems else "PASS", "; ".join(problems) if problems else "Cancel on the form ended the plugin: cancelled, no run, no worker, no run record", seconds=launch.seconds)

    def judge_run(self, tool: dict[str, Any], case: dict[str, Any], inputs: dict[str, Any], preload: list[dict[str, str]], launch: Launch, before: set[str], began: float) -> None:
        key = sanitize(tool["id"])
        finish = launch.finish()
        problems, unchecked = run_problems(tool, case, inputs, preload, finish, self.files_since(before), self.app) if finish is not None or launch.records else (["not reached"], [])
        problems += self.click_problems(launch, ["form_ok"]) + [o["detail"] for o in self.obs(launch, "fill_failed")] + [o["detail"] for o in self.obs(launch, "dialog_problem")]
        if finish is None:
            problems.append(launch.ended())                      # the watchdog kill, or how else Fiji ended
        else:
            problems += window_problems(finish.get("shown", []), finish.get("screen", [1600, 1000]), [], (finish.get("windows") or []) + (finish.get("tables") or []) + ALLOWED_FRAMES, "after the run")
        if finish is None and not launch.records:
            self.conclude(["ui_fiji_run_" + key], launch, tool["id"])
        else:
            summary = (finish or {}).get("summary", {})
            self.m.add("ui_fiji_run_" + key, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "status COMPLETE; every declared result shown (%s)%s" % (
                ", ".join("%s:%s" % (o["name"], o["type"]) for o in tool["outputs"]), "; inputs from case " + json.dumps(case.get("inputs", {}))[:120]), tool=tool["id"], seconds=launch.seconds)
            if unchecked:
                self.m.add("ui_fiji_run_" + key, "HUMAN", "expectations of the case that Fiji cannot check: " + ", ".join(unchecked), tool=tool["id"], control="not asserted in Fiji")
            if "status" in summary:
                self.last_run = (tool, inputs)
            if not problems and summary.get("status") == "COMPLETE":
                if not self.logged:
                    self.judge_log(finish or {}, summary)
        self.judge_form_ok(launch, finish)
        self.judge_screens(tool, launch, began, with_run=True)
        self.conclude(["ui_fiji_run_" + key, "ui_fiji_screens_" + key], launch, tool["id"])

    def judge_form_ok(self, launch: Launch, finish: Optional[dict[str, Any]]) -> None:
        """OK on the form (the first run of the audit): a real click closed the dialog and the plugin started a run (whatever its outcome: the outcome is ui_fiji_run_<tool>)."""
        if self.m.has_row("ui_fiji_panel_form_ok") or "panel" not in self.s.parts:
            return
        problems = self.click_problems(launch, ["form_ok"])
        problems += [] if finish is not None and "status" in finish.get("summary", {}) else ["the plugin reported no run after OK (%s)" % launch.ended()]
        status = finish["summary"]["status"] if finish is not None and "status" in finish.get("summary", {}) else None
        self.m.add("ui_fiji_panel_form_ok", "FAIL" if problems else "PASS",
                   "; ".join(problems) if problems else "a real click on OK closed the form and the plugin ran the tool (status %s)" % status, seconds=launch.seconds)

    def judge_log(self, finish: dict[str, Any], summary: dict[str, Any]) -> None:
        last, log = self.last_command(), finish.get("log", "")
        problems = []
        if last is None:
            problems.append("no state/last_command.json after a completed run")
        elif last["terminal"] not in log or last["python"] not in log:
            problems.append("the Log window lacks the command to repeat the run (the terminal line and the Python snippet of state/last_command.json)")
        if "LabConstrictor" not in log:
            problems.append("the Log window has no LabConstrictor line")
        self.m.add("ui_fiji_panel_log", "FAIL" if problems else "PASS", "; ".join(problems) if problems else "the Log window holds the run's command line and Python snippet, equal to the saved last command")
        self.logged = True

    def judge_screens(self, tool: dict[str, Any], launch: Launch, began: float, with_run: bool) -> None:
        key = sanitize(tool["id"])
        shots = {o["which"]: o for o in self.obs(launch, "screenshot")}
        problems = screenshot_problems(shots.get("form"), "form", began)
        if with_run:
            problems += screenshot_problems(shots.get("after_run"), "after_run", began)
        path = shots.get("after_run", {}).get("path") if with_run else shots.get("form", {}).get("path")
        if with_run:
            self.m.add("ui_fiji_screens_" + key, "FAIL" if problems else "PASS", "; ".join(problems) if problems else "the form and after-run screenshots exist, are fresh and plausible (evidence for a person, "
                       "not a check of the picture)", tool=tool["id"], seconds=launch.seconds, screenshot=path)
        else:
            self.m.add("ui_fiji_screens_" + key, "HUMAN", "no run, so no after-run screenshot; the form screenshot %s" % ("is not usable: " + "; ".join(problems) if problems else "was taken (evidence for a person)"),
                       tool=tool["id"], screenshot=path)

    def audit_tool(self, tool: dict[str, Any], first: bool, cases: list[dict[str, Any]], no_case: dict[str, str], done: list[int], ordered: bool) -> None:
        key = sanitize(tool["id"])
        case = tool_case(cases, tool["id"])
        inputs = absolute_inputs(case, tool, self.tests_dir) if case else {}
        preload = preload_for(tool, inputs, self.tests_dir)
        before = self.runs_now()
        if "tools" in self.s.parts or first:
            panel = first and "panel" in self.s.parts
            # the panel start presses Cancel and watches what the plugin does; a plugin whose Cancel runs the tool must not do it in the folder the real runs use
            launch, rows = self.start("controls", tool, emit_panel=panel, preload=preload, cwd=self.scratch_copy() if panel else None)
            if first and "panel" in self.s.parts:
                self.judge_panel_choosers(launch, tool, preload)
            self.judge_form(tool, launch, rows, preload, first and "panel" in self.s.parts, before)
            if case is None:
                self.judge_screens(tool, launch, self.began - 1, with_run=False)
        if "tools" not in self.s.parts:
            return
        if case is None:
            reason = no_case.get(tool["id"]) or ("a stress tool: it is never run through the form" if tool["id"].startswith("stress_") else None)
            if reason:
                self.m.add("ui_fiji_run_" + key, "HUMAN", "no run: " + reason, tool=tool["id"])
            else:
                self.m.add("ui_fiji_run_" + key, "FAIL", "the app has no case expecting COMPLETE for '%s' in lc_tests/cases.json and does not declare it in no_case.json" % tool["id"], tool=tool["id"])
            return
        if ordered:
            for earlier in replay_before(cases, case, done):
                self.replay(earlier)
        if cases.index(case) not in done:
            done.append(cases.index(case))
        fills = build_fills(tool, inputs, preload)
        before = self.runs_now()
        launch, _rows = self.start("run", tool, fills=fills, preload=preload)
        self.judge_run(tool, case, inputs, preload, launch, before, self.began)

    def scratch_copy(self) -> Path:
        """A throw-away copy of the app's tests folder (the working folder of the Cancel observation)."""
        target = Path(tempfile.mkdtemp(prefix="lcaudit_scratch_")) / "lc_tests"
        shutil.copytree(self.tests_dir, target)
        return target

    def replay(self, case: dict[str, Any]) -> None:
        """A prerequisite of an ordered app (an earlier case whose state a later one needs): run through the same form, not audited; a failure is a row of its own."""
        assert self.schema is not None
        self.replays += 1
        tool = next(t for t in self.schema["tools"] if t["id"] == case["tool"])
        inputs = absolute_inputs(case, tool, self.tests_dir)
        preload = preload_for(tool, inputs, self.tests_dir)
        before = self.runs_now()
        launch, _rows = self.start("run", tool, fills=build_fills(tool, inputs, preload), preload=preload)
        problems, _unchecked = run_problems(tool, case, inputs, preload, launch.finish(), self.files_since(before), self.app)
        unfilled = [o["detail"] for o in self.obs(launch, "fill_failed")]
        finish = launch.finish()
        if finish is not None and "status" in finish.get("summary", {}):
            self.last_run = (tool, inputs)
        refused_early = finish is not None and finish["summary"].get("error") and "status" not in finish["summary"] and not self.files_since(before)["new_runs"]
        if refused_early and case.get("expect", {}).get("status", "COMPLETE") != "COMPLETE":     # the plugin refused before any run: nothing changed, as the case expects
            return
        row = "ui_fiji_prerequisite_%s_%s" % (sanitize(tool["id"]), self.replays)
        if unfilled:
            wanted = case.get("expect", {}).get("status", "COMPLETE")
            done = subprocess.run([sys.executable, "-m", "labconstrictor_tools", "run", "--no-record", self.app, tool["id"],
                                   *["%s=%s" % (k, v if not isinstance(v, bool) else str(v).lower()) for k, v in inputs.items()]],
                                  env=dict(os.environ, LC_HOME=self.s.home), cwd=self.tests_dir, capture_output=True, text=True, check=False)
            agrees = (done.returncode == 0) == (wanted == "COMPLETE")
            self.m.add(row, "HUMAN" if agrees else "FAIL", "an earlier case (expects %s) was replayed through the command line for its effect on the app's state, because the Fiji form cannot express its input (%s)%s" % (
                wanted, "; ".join(unfilled), "" if agrees else "; the command line ended with exit code %d, which does not agree: %s" % (done.returncode, (done.stdout + done.stderr)[-200:])), tool=tool["id"])
        elif problems:
            self.m.add(row, "FAIL", "the earlier case of '%s' (needed for a later tool) failed: %s" % (tool["id"], "; ".join(problems)), tool=tool["id"], seconds=launch.seconds)

    def judge_panel_choosers(self, launch: Launch, tool: dict[str, Any], preload: list[dict[str, str]]) -> None:
        assert self.schema is not None
        if len(self.listed_apps) > 1:
            self.judge_chooser(launch, "app", self.listed_apps, preload)
        else:
            self.m.add("ui_fiji_panel_chooser_app", "HUMAN", "not applicable: only one app is listed, so Fiji shows no app chooser")
        if len(self.schema["tools"]) > 1:
            self.judge_chooser(launch, "tool", tool_choice_names(self.schema), preload)
        else:
            self.m.add("ui_fiji_panel_chooser_tool", "HUMAN", "not applicable: the app has one tool, so Fiji shows no tool chooser")
        opened = self.obs(launch, "chooser_ok")
        clicks = self.click_problems(launch, ["chooser_ok"])
        if opened and not clicks:
            self.m.add("ui_fiji_panel_chooser_ok", "PASS", "OK on the %s chooser opened '%s'" % (opened[0]["which"], opened[0]["opened"]))
        elif opened or clicks:
            self.m.add("ui_fiji_panel_chooser_ok", "FAIL", "; ".join(clicks) or "OK did not open the next dialog")
        elif len(self.listed_apps) > 1 or len(self.schema["tools"]) > 1:
            self.m.add("ui_fiji_panel_chooser_ok", "FAIL", "OK on the chooser did not open the next dialog (%s)" % launch.ended())
        else:
            self.m.add("ui_fiji_panel_chooser_ok", "HUMAN", "not applicable: Fiji shows no chooser for one app with one tool")

    # ---------------------------------------------------------------- the whole audit of the app
    def audit(self, multi_rows: Callable[[], None]) -> int:
        """Run every job of this app; `multi_rows` writes the rows of the several-apps checks (decided before). Returns the exit code of this app."""
        if self.schema is None:
            self.m.add("ui_fiji_driver_crashed", "FAIL", "no registry entry for '%s' under %s/apps: nothing to audit" % (self.app, self.s.home))
            return self.m.finish()
        try:
            self.run_jobs(multi_rows)
        # groovylint-disable-next-line CatchException
        except Exception as problem:  # broad on purpose: whatever stopped the driver is a FAIL row; the matrix written so far stays valid
            self.m.add("ui_fiji_driver_crashed", "FAIL", "%s: %s" % (type(problem).__name__, problem))
        return self.m.finish()

    def run_jobs(self, multi_rows: Callable[[], None]) -> None:
        assert self.schema is not None
        tests = self.s.tests[self.app]
        if not (tests / "cases.json").is_file():
            self.m.add("ui_fiji_driver_crashed", "FAIL", "LC_APP_TESTS=%s has no cases.json" % tests)
            return
        self.tests_dir = Path(tempfile.mkdtemp(prefix="lcaudit_tests_")) / "lc_tests"
        shutil.copytree(tests, self.tests_dir)   # the cases may write into their fixtures (a demo experiment): never into the app's own tree
        generator = self.tests_dir / "make_fixtures.py"
        if generator.is_file():                  # an app that generates its fixtures (the README's `python lc_tests/make_fixtures.py lc_tests/fixtures`): start from a pristine set
            shutil.rmtree(self.tests_dir / "fixtures", ignore_errors=True)
            made = subprocess.run([sys.executable, str(generator), str(self.tests_dir / "fixtures")], cwd=self.tests_dir, capture_output=True, text=True, check=False)
            if made.returncode:
                self.m.add("ui_fiji_driver_crashed", "FAIL", "make_fixtures.py failed (exit %d): %s" % (made.returncode, made.stderr[-300:]))
                return
        if not self.listing():
            return
        multi_rows()
        cases, no_case = load_cases(self.tests_dir), load_no_case(self.tests_dir)
        ordered = self.app in self.s.ordered or is_ordered(self.tests_dir)
        tool_ids = self.tool_ids()
        tools = [t for t in self.schema["tools"] if t["id"] in tool_ids]
        tools = order_tools(tools, cases) if ordered else tools
        if "panel" in self.s.parts:
            self.panel_cancel(len(self.schema["tools"]))
            self.panel_copy_empty_and_menu()
        done: list[int] = []
        for position, tool in enumerate(tools):
            self.audit_tool(tool, position == 0, cases, no_case, done, ordered)
        if "panel" in self.s.parts:
            self.panel_copy("terminal")
            self.panel_copy("python")
            if not self.logged:
                self.m.add("ui_fiji_panel_log", "NOT_RUN", "no tool completed a run, so there is no Log of a run to read")
        if "panel" in self.s.parts:
            self.fj1b_rows(tools)
        self.controls_row()

    def fj1b_rows(self, tools: list[dict[str, Any]]) -> None:
        """alpha.3 (FJ-1b): a plain Cancel on the untouched form of a tool with a required file/folder/table input ends the command (nothing runs, no run record, status line says cancelled);
        OK on the untouched form of a tool with a required image and no image open still refuses with missing_parameter."""
        needs_path = [t for t in tools if any(p.get("required", True) and p["type"] in ("file", "folder", "table") for p in t["inputs"]) and len(t["inputs"]) > 1][:6]
        if not needs_path:
            self.m.add("ui_fiji_fj1b_cancel_form", "HUMAN", "no tool of this app has a required file/folder/table input on a form of several parameters: nothing to cancel with an empty required path")
        for tool in needs_path:
            before = self.runs_now()
            launch, _rows = self.start("cancel_form", tool, cwd=self.scratch_copy())
            finish = launch.finish()
            problems = self.click_problems(launch, ["form_cancel"]) + self.ignored_cancel(launch, finish)
            if finish is not None:
                summary = finish["summary"]
                problems += [] if summary.get("cancelled") is True else ["the summary does not say cancelled"]
                problems += ["a run started (status %r, error %r)" % (summary.get("status"), summary.get("error"))] if "status" in summary else []
                text = finish.get("status_text") or ""
                if text and not text.startswith("unreadable") and "cancelled" not in text.lower():
                    problems.append("the status line says %r, not 'LabConstrictor: cancelled'" % text)
            new = self.runs_now() - before
            problems += ["%d run record(s) were created (%s)" % (len(new), ", ".join(sorted(new)))] if new else []
            detail = "; ".join(problems) if problems else "plain Cancel on the untouched form ended the command: cancelled, nothing ran, no run record, status line %r" % ((finish or {}).get("status_text"),)
            self.m.add("ui_fiji_fj1b_cancel_form_" + sanitize(tool["id"]), "FAIL" if problems else "PASS", detail, tool=tool["id"], seconds=launch.seconds)
        with_image = [t for t in tools if any(p.get("required", True) and p["type"] in ("image", "labels") for p in t["inputs"])][:1]
        if not with_image:
            self.m.add("ui_fiji_fj1b_missing_image", "HUMAN", "no tool of this app has a required image input: nothing to refuse")
        for tool in with_image:
            before = self.runs_now()
            launch, _rows = self.start("ok_empty", tool, cwd=self.scratch_copy())
            finish = launch.finish()
            problems = self.click_problems(launch, ["form_ok"])
            if finish is None:
                problems.append("the plugin did not end (%s)" % launch.ended())
            else:
                summary = finish["summary"]
                said = str(summary.get("error") or "") + " " + str(summary.get("code") or "") + " " + str(finish.get("log") or "")
                if "missing_parameter" not in said:
                    problems.append("no missing_parameter refusal: summary %s" % json.dumps(summary)[:300])
                problems += ["the tool ran to COMPLETE with no image open"] if summary.get("status") == "COMPLETE" else []
            self.m.add("ui_fiji_fj1b_missing_image_" + sanitize(tool["id"]), "FAIL" if problems else "PASS",
                       "; ".join(problems) if problems else "no image open, OK on the untouched form: refused: error %r, code %r, status %r, status line %r" % (
                           str(((finish or {}).get("summary") or {}).get("error"))[:240], ((finish or {}).get("summary") or {}).get("code"), ((finish or {}).get("summary") or {}).get("status"), (finish or {}).get("status_text")), tool=tool["id"], seconds=launch.seconds)

    def controls_row(self) -> None:
        """ui_fiji_controls_all_known, two-sided: no control found is outside the table AND every dialog the audit meant to read was read (a form with zero controls found fails)."""
        problems = list(self.unknown) + self.absent
        if self.dialogs["form"] == 0 and not self.prompts:
            problems.append("no form was enumerated: zero controls found")
        if "panel" in self.s.parts and (len(self.listed_apps) > 1 or (self.schema and len(self.schema["tools"]) > 1)) and self.dialogs["chooser"] == 0:
            problems.append("no chooser was enumerated although Fiji shows one")
        ids = {c["id"] for c in control_table()["controls"]}
        self.m.add("ui_fiji_controls_all_known", "FAIL" if problems else "PASS", ("unknown or forbidden control(s): " + "; ".join(problems)) if problems else
                   "%d control(s) in %d form(s) and %d chooser(s), every one in the control table (%d kinds)" % (self.controls_seen, self.dialogs["form"], self.dialogs["chooser"], len(ids)))
