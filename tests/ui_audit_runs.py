"""The runs of the Fiji UI audit: which case of the app's own lc_tests/cases.json drives each tool, how its inputs reach the form, and what a completed run must have shown.

Cases are the app's (the format of `labconstrictor-tools test`: tool, inputs, expect {status, results, ...}). The audit uses the FIRST case of a tool that expects COMPLETE (a case that
expects FAILED, cancels, is skipped, or belongs to a `stress_*` tool is never run: memory stress such as stress_real_memory must not run in a GUI). File inputs become Fiji
inputs the way a person gives them: an image or labels file is OPENED as an ImagePlus (its title is the file name; Fiji reads the calibration from the file itself) and chosen in the
form; a table, file or folder path is typed into its field (relative paths are made absolute against the cases file's folder, as the Tools' own runner does).
Apps whose tools depend on each other in order (Guess the Condition: a game whose state is in the results folder) are "ordered": LC_UI_ORDERED names them (default GuessTheCondition), or the
app's `lc_tests/ui_audit.json` says `{"ordered": true}`. The tools are then audited in the order of their chosen cases, and before each one every earlier case of the file is replayed through
the same dialog (without audit), whatever status it expects, because a case that expects FAILED can still change the state (Guess the Condition: the last guess is saved by the call that
says "last image"); only a skipped, cancelling or stress case is left out.
Everything the checks compare with comes from the schema, the files on disk and the case; never from the state of the dialog.
"""

import json
import math
from pathlib import Path
from typing import Any, Callable, Optional

from ui_audit_forms import base_label
from ui_audit_placement import placement_problems

PATH_TYPES = ("image", "labels", "table", "file", "folder")
SCALAR_TYPES = ("integer", "float", "string", "boolean", "choice")
RESULT_KEYS = {"image": "image_", "labels": "image_", "table": "table_", "values": "values_", "message": "message_", "points": "points_", "shapes": "shapes_", "file": "file_"}
DTYPES = {8: "uint8", 16: "uint16", 32: "float32"}
TOLERANCE = 1e-6


def load_cases(tests: Path) -> list[dict[str, Any]]:
    """The cases of `<tests>/cases.json` (a list, or {"cases": [...]}), in file order; a malformed file is an error, never an empty list."""
    cases = json.loads((tests / "cases.json").read_text(encoding="utf-8"))
    if isinstance(cases, dict):
        cases = cases["cases"]
    if not isinstance(cases, list) or not all(isinstance(c, dict) and isinstance(c.get("tool"), str) for c in cases):
        raise ValueError("%s/cases.json: expected a list of cases, each with a 'tool'" % tests)
    return cases


def load_no_case(tests: Path) -> dict[str, str]:
    """{tool id: reason} of `lc_tests/no_case.json` (absent: none)."""
    path = tests / "no_case.json"
    if not path.is_file():
        return {}
    declared = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(declared, dict) or not all(isinstance(v, str) and v for v in declared.values()):
        raise ValueError("%s: expected an object {tool id: reason}" % path)
    return declared


def is_ordered(tests: Path) -> bool:
    path = tests / "ui_audit.json"
    return bool(json.loads(path.read_text(encoding="utf-8")).get("ordered")) if path.is_file() else False


def runnable(case: dict[str, Any]) -> bool:
    """A case the audit may run through the form: it expects COMPLETE, does not cancel, is not skipped, and is not a stress tool."""
    return (not case.get("skip") and not case.get("cancel_after_s") and case.get("expect", {}).get("status", "COMPLETE") == "COMPLETE"
            and not case["tool"].startswith("stress_"))


def replayable(case: dict[str, Any]) -> bool:
    """A case that an ordered app replays as a prerequisite: any expected status, but not skipped, not cancelling, not a stress tool."""
    return not case.get("skip") and not case.get("cancel_after_s") and not case["tool"].startswith("stress_")


def order_tools(tools: list[dict[str, Any]], cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The tools in the order of their chosen cases (a tool without one last, in schema order): the order an ordered app was written to be played in."""
    position = {t["id"]: (cases.index(c) if (c := tool_case(cases, t["id"])) is not None else len(cases)) for t in tools}
    return sorted(tools, key=lambda t: position[t["id"]])


def tool_case(cases: list[dict[str, Any]], tool_id: str) -> Optional[dict[str, Any]]:
    return next((case for case in cases if case["tool"] == tool_id and runnable(case)), None)


def replay_before(cases: list[dict[str, Any]], chosen: dict[str, Any], done: list[int]) -> list[dict[str, Any]]:
    """The replayable cases that stand before `chosen` in the file and have not been run yet (`done` = their indexes, extended), for an ordered app."""
    prerequisites = []
    for index, case in enumerate(cases):
        if case is chosen:
            break
        if replayable(case) and index not in done:
            prerequisites.append(case)
            done.append(index)
    return prerequisites


def absolute_inputs(case: dict[str, Any], tool: dict[str, Any], tests: Path) -> dict[str, Any]:
    """The case's inputs with every path-typed value made absolute against the folder of the cases file."""
    kinds = {p["name"]: p["type"] for p in tool["inputs"]}
    unknown = sorted(set(case.get("inputs", {})) - set(kinds))
    if unknown:
        raise ValueError("the case gives %s, which the tool '%s' does not declare" % (unknown, tool["id"]))
    return {name: (str((tests / value).resolve()) if kinds[name] in PATH_TYPES and isinstance(value, str) else value) for name, value in case.get("inputs", {}).items()}


def preload_for(tool: dict[str, Any], inputs: dict[str, Any], tests: Path) -> list[dict[str, str]]:
    """The images to open before the form: those the case names (each image or labels input that is an existing file); without a case, the first TIFF of the app's fixtures
    when the tool has an image input (so that the form is the one a person with an image open sees, and SciJava's single-input file prompt does not replace it)."""
    given = [inputs[p["name"]] for p in tool["inputs"] if p["type"] in ("image", "labels") and isinstance(inputs.get(p["name"]), str) and Path(inputs[p["name"]]).is_file()]
    if not given and any(p["type"] in ("image", "labels") for p in tool["inputs"]):
        fixtures = sorted(list((tests / "fixtures").glob("*.tif")) + list((tests / "fixtures").glob("*.tiff"))) if (tests / "fixtures").is_dir() else []
        given = [str(fixtures[0])] if fixtures else []
    titles: dict[str, str] = {}
    for path in given:
        titles.setdefault(path, Path(path).name)
    return [{"path": path, "title": title} for path, title in titles.items()]


def number_text(value: Any) -> str:
    return repr(value) if isinstance(value, float) else str(value)


def build_fills(tool: dict[str, Any], inputs: dict[str, Any], preload: list[dict[str, str]]) -> list[dict[str, Any]]:
    """What a person does in the form to give the case's inputs: [{label, value}] in the order of the parameters."""
    titles = {item["path"]: item["title"] for item in preload}
    fills: list[dict[str, Any]] = []
    for p in tool["inputs"]:
        if inputs.get(p["name"]) is None:
            continue
        value, kind, label = inputs[p["name"]], p["type"], p["label"]
        if p.get("nullable") and not p.get("pixel_size_of") and kind in ("string", "integer", "float", "choice", "boolean"):
            fills.append({"label": "Set " + label.lower(), "value": True})
        if kind in ("image", "labels"):
            if value in titles:
                if not p.get("required", True):
                    fills.append({"label": "Use " + label.lower(), "value": True})
                fills.append({"label": base_label(p), "value": titles[value]})
            else:
                fills.append({"label": label + " (or file)" if titles else base_label(p), "value": value})
        elif kind == "boolean":
            fills.append({"label": base_label(p), "value": bool(value)})
        elif p.get("pixel_size_of"):
            fills.append({"label": base_label(p), "value": float(value)})
        elif kind in ("integer", "float"):
            fills.append({"label": base_label(p), "value": number_text(value)})
        else:
            fills.append({"label": base_label(p), "value": str(value)})
    return fills


def close(found: Any, wanted: Any) -> bool:
    if isinstance(wanted, bool) or isinstance(found, bool):
        return found is wanted
    if isinstance(wanted, (int, float)) and isinstance(found, (int, float)):
        return math.isclose(found, wanted, rel_tol=TOLERANCE, abs_tol=TOLERANCE)
    return found == wanted


def request_problems(tool: dict[str, Any], inputs: dict[str, Any], summary: dict[str, Any]) -> list[str]:
    """What was SENT to the worker (summary.request) equals what the case gave, for every scalar input."""
    sent = summary.get("request") or {}
    problems = []
    for p in tool["inputs"]:
        if p["type"] in SCALAR_TYPES and inputs.get(p["name"]) is not None and not p.get("pixel_size_of"):
            got, wanted = sent.get(p["name"]), inputs[p["name"]]
            if not (close(got, wanted) or str(got) == str(wanted)):
                problems.append("sent %s=%r, the case gives %r" % (p["name"], got, wanted))
    return problems


def compare_result(name: str, kind: str, wanted: dict[str, Any], summary: dict[str, Any]) -> tuple[list[str], list[str]]:
    """(problems, expectations Fiji cannot check) of one `expect.results` entry against what the plugin summarised."""
    problems: list[str] = []
    unchecked: list[str] = []
    shown = summary.get(RESULT_KEYS.get(kind, "?") + name)
    if kind == "values":
        for key, value in wanted.get("equals", wanted).items():
            got = (shown or {}).get(key)
            ok = abs(got - value["approx"]) <= value.get("tol", TOLERANCE) if isinstance(value, dict) and isinstance(got, (int, float)) else (not isinstance(value, dict) and close(got, value))
            if not ok:
                problems.append("%s: %s is %r, expected %r" % (name, key, got, value))
    elif kind == "message":
        if "contains" in wanted and wanted["contains"] not in str(shown):
            problems.append("%s: the message lacks %r" % (name, wanted["contains"]))
    elif kind == "table":
        if "columns" in wanted and not set(wanted["columns"]) <= set((shown or {}).get("cols", [])):
            problems.append("%s: columns %s lack %s" % (name, (shown or {}).get("cols"), sorted(set(wanted["columns"]) - set((shown or {}).get("cols", [])))))
        if "rows" in wanted and (shown or {}).get("rows") != wanted["rows"]:
            problems.append("%s: %r rows, expected %r" % (name, (shown or {}).get("rows"), wanted["rows"]))
        unchecked += ["%s cells" % name] if "cells" in wanted else []
    elif kind == "points":
        if "rows" in wanted and (shown or {}).get("count") != wanted["rows"]:
            problems.append("%s: %r points, expected %r" % (name, (shown or {}).get("count"), wanted["rows"]))
        if "columns" in wanted and not set(wanted["columns"]) <= set((shown or {}).get("columns", [])):
            problems.append("%s: columns %s lack %s" % (name, (shown or {}).get("columns"), wanted["columns"]))
    elif kind == "shapes":
        if "features" in wanted and (shown or {}).get("count") != wanted["features"]:
            problems.append("%s: %r outlines, expected %r" % (name, (shown or {}).get("count"), wanted["features"]))
    elif kind in ("image", "labels") and isinstance(shown, list) and len(shown) == 4:
        width, height, planes, depth = shown
        if "shape" in wanted and list(wanted["shape"]) != ([height, width] if planes == 1 else [planes, height, width]):
            problems.append("%s: shape %s, expected %s" % (name, [height, width] if planes == 1 else [planes, height, width], wanted["shape"]))
        if "dtype" in wanted and DTYPES.get(depth) != wanted["dtype"]:
            problems.append("%s: dtype %s (%d bit), expected %s" % (name, DTYPES.get(depth), depth, wanted["dtype"]))
        unchecked += ["%s %s" % (name, key) for key in ("mean", "max", "n_labels") if key in wanted]
    return problems, unchecked


def not_blank(kind: str, name: str, shown: Any) -> Optional[str]:
    """A declared result that was not shown, or was shown empty (a blank result), is a failure of its own, whatever the case expects."""
    if shown is None:
        return "the result '%s' (%s) was not shown" % (name, kind)
    empty: dict[str, Callable[[Any], bool]] = {
        "image": lambda v: not isinstance(v, list) or not all(n > 0 for n in v[:2]),
        "labels": lambda v: not isinstance(v, list) or not all(n > 0 for n in v[:2]),
        "values": lambda v: not v,
        "message": lambda v: not str(v).strip(),
        "table": lambda v: not isinstance(v, dict) or not v.get("cols"),
        "file": lambda v: not Path(str(v)).is_file(),
        "affine": lambda v: not v,
    }
    return "the result '%s' (%s) is empty or blank: %r" % (name, kind, shown) if empty.get(kind, lambda v: False)(shown) else None


def run_problems(tool: dict[str, Any], case: dict[str, Any], inputs: dict[str, Any], preload: list[dict[str, str]], finish: Optional[dict[str, Any]],
                 files: dict[str, Any], app: str = "app") -> tuple[list[str], list[str]]:
    """(problems, expectations of the case that Fiji cannot check) for one run. `files` is what the audit found on disk: new_runs (run record folders), record_files, log_file."""
    if finish is None:
        return ["Fiji ended without the plugin reporting the run"], []
    summary = finish.get("summary", {})
    problems: list[str] = []
    unchecked: list[str] = []
    wanted_status = case.get("expect", {}).get("status", "COMPLETE")
    if summary.get("error") and wanted_status == "COMPLETE":
        problems.append("the run was refused: %s" % summary["error"])
    if summary.get("status") != wanted_status:
        problems.append("status %r, expected %r%s" % (summary.get("status"), wanted_status, ": " + str(summary["failure_message"]) if summary.get("failure_message") else ""))
    if summary.get("status") == "COMPLETE":
        for output in tool["outputs"]:
            shown = summary.get("affine_matrix") if output["type"] == "affine" else summary.get(RESULT_KEYS.get(output["type"], "?") + output["name"])
            blank = not_blank(output["type"], output["name"], shown)
            if blank:
                problems.append(blank)
        if summary.get("display_errors"):
            problems.append("results that could not be shown: %s" % summary["display_errors"])
        kinds = {o["name"]: o["type"] for o in tool["outputs"]}
        for name, wanted in case.get("expect", {}).get("results", {}).items():
            if name not in kinds:
                problems.append("the case expects a result '%s' the tool does not declare" % name)
                continue
            found, skipped = compare_result(name, kinds[name], wanted, summary)
            problems += found
            unchecked += skipped
        if case.get("expect", {}).get("progress_events_min", 0) > len(summary.get("progress_events") or []):
            problems.append("%d progress events, expected at least %d" % (len(summary.get("progress_events") or []), case["expect"]["progress_events_min"]))
        unchecked += ["max_seconds"] if "max_seconds" in case.get("expect", {}) else []
        problems += request_problems(tool, inputs, summary) + placement_problems(app, tool, case, inputs, finish, preload)
    if len(files.get("new_runs", [])) != 1:
        problems.append("%d new run record folder(s) under runs/, expected exactly 1" % len(files.get("new_runs", [])))
    elif not files.get("record_files"):
        problems.append("the run record folder %s is empty" % files["new_runs"][0])
    if not files.get("log_file"):
        problems.append("the Tools log file does not exist")
    return problems, unchecked


def screenshot_problems(obs: Optional[dict[str, Any]], which: str, began: float) -> list[str]:
    """Evidence for a person, not proof of a picture: the file exists, was written by this run, has a plausible size and is not one flat colour."""
    if obs is None:
        return ["no %s screenshot was taken" % which]
    path = Path(obs["path"])
    problems = []
    if not path.is_file():
        return ["%s does not exist (%s)" % (path.name, obs.get("error", "no error given"))]
    if path.stat().st_mtime < began - 1:
        problems.append("%s is older than this run (stale)" % path.name)
    if path.stat().st_size < 1000 or obs.get("width", 0) < 100 or obs.get("height", 0) < 50:
        problems.append("%s is implausibly small (%d bytes, %sx%s)" % (path.name, path.stat().st_size, obs.get("width"), obs.get("height")))
    if not obs.get("not_flat"):
        problems.append("%s is one flat colour" % path.name)
    return problems
