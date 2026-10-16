"""The alpha.3 FJ-1b rows of the Fiji UI audit: Cancel on the untouched form of a tool with a required file/folder/table input ends the command (nothing runs, no run record, the status
line says cancelled), and OK on the untouched form of a tool with a required image, with no image open, still refuses with `missing_parameter`.

Which tools get a row is decided by `fj1b_targets` ALONE, and the same function names the row ids that are written to the expected list before anything runs (`fj1b_ids`): a start that
is killed or never reached leaves its id without a row, which ends as NOT_RUN, never as a silently missing row. There is no cap on the tools: every tool that qualifies gets its own row.
"""

import json
from typing import TYPE_CHECKING, Any

from ui_audit_forms import settle_problems
from ui_audit_matrix import sanitize

if TYPE_CHECKING:
    from ui_audit_app import Audit

PATH_TYPES = ("file", "folder", "table")
IMAGE_TYPES = ("image", "labels")


def fj1b_targets(tools: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """(tools whose untouched form is cancelled, the one tool whose untouched form is OK'd). The Cancel rows cover every tool with a required path input on a form of several parameters (a
    single file input is SciJava's native file prompt, not a form). The refusal of a missing image is the host's one rule, so ONE tool with a required image is enough."""
    needs_path = [t for t in tools if any(p.get("required", True) and p["type"] in PATH_TYPES for p in t["inputs"]) and len(t["inputs"]) > 1]
    with_image = [t for t in tools if any(p.get("required", True) and p["type"] in IMAGE_TYPES for p in t["inputs"])][:1]
    return needs_path, with_image


def fj1b_ids(tools: list[dict[str, Any]]) -> list[str]:
    """The row ids `fj1b_rows` writes for `tools` (a HUMAN row stands in when no tool qualifies)."""
    needs_path, with_image = fj1b_targets(tools)
    return (["ui_fiji_fj1b_cancel_form_" + sanitize(t["id"]) for t in needs_path] or ["ui_fiji_fj1b_cancel_form"]) + (
        ["ui_fiji_fj1b_missing_image_" + sanitize(t["id"]) for t in with_image] or ["ui_fiji_fj1b_missing_image"])


def cancel_rows(audit: "Audit", tools: list[dict[str, Any]]) -> None:
    if not tools:
        audit.m.add("ui_fiji_fj1b_cancel_form", "HUMAN", "no tool of this app has a required file/folder/table input on a form of several parameters: nothing to cancel with an empty required path")
    for tool in tools:
        before = audit.runs_now()
        launch, _rows = audit.start("cancel_form", tool, cwd=audit.scratch_copy())
        finish = launch.finish()
        problems = settle_problems(launch.records) + audit.click_problems(launch, ["form_cancel"]) + audit.ignored_cancel(launch, finish)
        if finish is not None:
            summary = finish["summary"]
            problems += [] if summary.get("cancelled") is True else ["the summary does not say cancelled"]
            problems += ["a run started (status %r, error %r)" % (summary.get("status"), summary.get("error"))] if "status" in summary else []
            text = finish.get("status_text") or ""
            if text and not text.startswith("unreadable") and "cancelled" not in text.lower():
                problems.append("the status line says %r, not 'LabConstrictor: cancelled'" % text)
        new = audit.runs_now() - before
        problems += ["%d run record(s) were created (%s)" % (len(new), ", ".join(sorted(new)))] if new else []
        detail = "; ".join(problems) if problems else "plain Cancel on the untouched form ended the command: cancelled, nothing ran, no run record, status line %r" % ((finish or {}).get("status_text"),)
        audit.m.add("ui_fiji_fj1b_cancel_form_" + sanitize(tool["id"]), "FAIL" if problems else "PASS", detail, tool=tool["id"], seconds=launch.seconds)


def missing_image_rows(audit: "Audit", tools: list[dict[str, Any]]) -> None:
    if not tools:
        audit.m.add("ui_fiji_fj1b_missing_image", "HUMAN", "no tool of this app has a required image input: nothing to refuse")
    for tool in tools:
        launch, _rows = audit.start("ok_empty", tool, cwd=audit.scratch_copy())
        finish = launch.finish()
        problems = settle_problems(launch.records) + audit.click_problems(launch, ["form_ok"])
        if finish is None:
            problems.append("the plugin did not end (%s)" % launch.ended())
        else:
            summary = finish["summary"]
            said = str(summary.get("error") or "") + " " + str(summary.get("code") or "") + " " + str(finish.get("log") or "")
            if "missing_parameter" not in said:
                problems.append("no missing_parameter refusal: summary %s" % json.dumps(summary)[:300])
            problems += ["the tool ran to COMPLETE with no image open"] if summary.get("status") == "COMPLETE" else []
        summary = (finish or {}).get("summary") or {}
        audit.m.add("ui_fiji_fj1b_missing_image_" + sanitize(tool["id"]), "FAIL" if problems else "PASS",
                    "; ".join(problems) if problems else "no image open, OK on the untouched form: refused: error %r, code %r, status %r, status line %r" % (
                        str(summary.get("error"))[:240], summary.get("code"), summary.get("status"), (finish or {}).get("status_text")), tool=tool["id"], seconds=launch.seconds)


def fj1b_rows(audit: "Audit", tools: list[dict[str, Any]]) -> None:
    """The FJ-1b rows for `tools` (see the module text)."""
    needs_path, with_image = fj1b_targets(tools)
    cancel_rows(audit, needs_path)
    missing_image_rows(audit, with_image)
