"""What the Fiji dialogs must hold, worked out from the app's SCHEMA (never from the dialog under test) and compared two-sidedly with what was found.

The widgets SciJava builds for a parameter follow docs/HOST_FEATURES.md (Fiji column) and the rules of the plugin script (addParameterItems): a number or text is a text field,
a choice a combo box (radio buttons for Widget("radio")), an image a chooser of the open images plus an "(or file)" field and its browse button, a nullable value a "Set <name>"
box, an optional image a "Use <name>" box, a pick-channel image a "<name> channel" number, a pixel size linked to an image a number box. Fiji has NO Rescan apps, Restart worker,
Details, Keep-the-worker-running or "use the image's calibration" control: any such control found is a failure (`absent_in_fiji` of the control table).
"""

import json
import re
from collections import Counter
from pathlib import Path
from typing import Any, Optional

STRUCTURAL = ("form_ok", "form_cancel", "message_text", "scrollbar")
BUTTON_IDS = ("form_ok", "form_cancel", "chooser_ok", "chooser_cancel")
CHECKBOXES = ["flag_checkbox", "use_image_checkbox", "set_checkbox", "use_selection_checkbox"]   # a yes/no parameter whose label starts with "Use " or "Set " is classified by that
MIN_CONTROLS = 3                                                                              # OK, Cancel and at least one widget: a form that shows fewer was not found at all
CONTROL_TABLE = Path(__file__).resolve().parent / "ui_audit_controls.json"


def norm(text: Any) -> str:
    """A label as tests/ui_audit_tree.groovy normalises it: no markup, one space, lower case, no trailing colon."""
    return re.sub(r":$", "", " ".join(re.sub(r"<[^>]*>", " ", str(text or "")).split()).lower()).strip()


def control_table() -> dict[str, Any]:
    return json.loads(CONTROL_TABLE.read_text(encoding="utf-8"))


def base_label(parameter: dict[str, Any]) -> str:
    return parameter["label"] + (" (%s)" % parameter["unit"] if parameter.get("unit") else "")


def tool_choice_names(schema: dict[str, Any]) -> list[str]:
    """The names the tool chooser lists: the label, or `label (id)` when two tools share a label (the plugin's toolChoices)."""
    labels = [tool["label"] for tool in schema["tools"]]
    return [tool["label"] if labels.count(tool["label"]) == 1 else "%s (%s)" % (tool["label"], tool["id"]) for tool in schema["tools"]]


def expected_widgets(tool: dict[str, Any], has_images: bool) -> list[dict[str, Any]]:
    """[{param, ids (any of), label}] for every widget the form of `tool` must hold; `has_images`: an image is open when the form is built."""
    wanted: list[dict[str, Any]] = []

    def add(parameter: dict[str, Any], ids: list[str], label: str) -> None:
        wanted.append({"param": parameter["name"], "ids": ids, "label": norm(label)})

    for p in tool["inputs"]:
        kind, label = p["type"], p["label"]
        if p.get("nullable") and not p.get("pixel_size_of") and kind in ("string", "integer", "float", "choice", "boolean"):
            add(p, ["set_checkbox"], "Set " + label.lower())
        if kind in ("image", "labels"):
            if p.get("region_of"):
                add(p, ["use_selection_checkbox"], "Use the selection as " + label.lower())
            field = base_label(p)
            if has_images:
                if not p.get("required", True):
                    add(p, ["use_image_checkbox"], "Use " + label.lower())
                add(p, ["image_choice"], field)
                field = label + " (or file)"
            add(p, ["text_field"], field)
            add(p, ["file_browse"], field)
            if p.get("pick_channel"):
                add(p, ["channel_spinner"], label + " channel")
        elif kind in ("table", "file", "folder"):
            add(p, ["text_field"], base_label(p))
            add(p, ["file_browse"], base_label(p))
        elif kind == "string":
            add(p, ["text_field", "choice_combo"], base_label(p))
        elif kind == "boolean":
            add(p, CHECKBOXES, base_label(p))
        elif kind == "choice":
            if p.get("widget") == "radio" and not p.get("nullable"):
                for choice in p["choices"]:
                    add(p, ["radio"], str(choice))
            else:
                add(p, ["choice_combo"], base_label(p))
        elif p.get("pixel_size_of"):
            add(p, ["number_spinner"], base_label(p))
        elif p.get("widget") == "slider" and not p.get("nullable") and p.get("minimum") is not None and p.get("maximum") is not None:
            add(p, ["slider"], base_label(p))
            add(p, ["number_spinner"], base_label(p))                 # SciJava's slider style is a slider and its number box, both in the row of the label
        else:
            add(p, ["text_field"], base_label(p))
    return wanted


def form_problems(tool: dict[str, Any], found: list[dict[str, Any]], texts: list[str], has_images: bool) -> list[str]:
    """Two-sided: every expected widget is found AND every control found (but the structural ones) is expected; OK and Cancel once each; not an empty form."""
    problems: list[str] = []
    ids = Counter(control["id"] for control in found)
    if len(found) < MIN_CONTROLS:
        problems.append("only %d control(s) found (a form has at least %d): the dialog was not read" % (len(found), MIN_CONTROLS))
    for button in ("form_ok", "form_cancel"):
        if ids[button] != 1:
            problems.append("%d %s control(s), expected exactly 1" % (ids[button], button))
    free = [control for control in found if control["id"] not in STRUCTURAL]
    missing = []
    for want in expected_widgets(tool, has_images):
        match = next((c for c in free if c["id"] in want["ids"] and c["label"] == want["label"]), None)
        if match is None:
            missing.append("%s '%s' (parameter %s)" % (want["ids"][0], want["label"], want["param"]))
        else:
            free.remove(match)
    if missing:
        problems.append("missing: " + ", ".join(missing))
    if free:
        problems.append("extra control(s) the schema does not declare: " + ", ".join("%s '%s'" % (c["id"], c["label"]) for c in free))
    description = norm(tool.get("description") or "")[:25]
    if description and not any(description in text for text in texts):
        problems.append("the tool's description is not shown in the form")
    return problems


def chooser_problems(which: str, found: list[dict[str, Any]], items: list[str], wanted_items: Optional[list[str]]) -> list[str]:
    """The app or tool chooser: exactly its combo box, OK and Cancel (two-sided), and the items it lists are the ones the registry holds."""
    problems: list[str] = []
    ids = sorted(control["id"] for control in found if control["id"] != "scrollbar")   # the scroll bars of the combo box's list are not controls of the dialog
    wanted_ids = sorted(["chooser_" + which, "chooser_ok", "chooser_cancel"])
    if ids != wanted_ids:
        problems.append("the %s chooser holds %s, expected %s" % (which, ids, wanted_ids))
    if wanted_items is not None and items != wanted_items:
        problems.append("the %s chooser lists %s, expected %s" % (which, items, wanted_items))
    return problems


def absent_problems(found: list[dict[str, Any]]) -> list[str]:
    """Controls Fiji must not have (HOST_FEATURES: no Rescan, Restart worker, Details, Keep the worker running, "use the image's calibration")."""
    absent = {norm(text) for text in control_table()["absent_in_fiji"]}
    return ["%s '%s' exists but Fiji has no such control" % (c["id"], c.get("text") or c["label"]) for c in found if norm(c.get("text")) in absent or norm(c["label"]) in absent]


def geometry_problems(geometry: dict[str, Any], found: list[dict[str, Any]]) -> list[str]:
    """The dialog is inside the screen and its buttons can be reached (a 3463 px wide error dialog with the OK button off-screen is a known bug class)."""
    problems: list[str] = []
    width, height = geometry["screen"]
    x, y, w, h = geometry["dialog"]
    if w > width or h > height:
        problems.append("the dialog is %dx%d on a %dx%d screen (oversized)" % (w, h, width, height))
    elif x < 0 or y < 0 or x + w > width or y + h > height:
        problems.append("the dialog at (%d,%d) %dx%d is not inside the %dx%d screen" % (x, y, w, h, width, height))
    for control in found:
        if control["id"] in BUTTON_IDS:
            bx, by, bw, bh = control["rect"]
            if not (0 <= bx and 0 <= by and bx + bw <= width and by + bh <= height and bw > 0 and bh > 0):
                problems.append("%s is at (%d,%d) %dx%d: not on the screen, it cannot be pressed" % (control["id"], bx, by, bw, bh))
    return problems


def window_problems(windows: list[dict[str, Any]], screen: list[int], dialogs: list[str], allowed: list[str], where: str) -> list[str]:
    """The windows showing at a checkpoint are exactly the expected ones: the dialogs named in `dialogs`, no other dialog (error dialogs, pop-ups), nothing oversized, and
    only frames that are expected (Fiji's own window, the Log, the ROI Manager, the images and tables that are open)."""
    problems: list[str] = []
    shown = sorted(w["title"] for w in windows if w["dialog"])
    if shown != sorted(dialogs):
        problems.append("%s: dialogs showing %s, expected %s" % (where, shown, sorted(dialogs)))
    for window in windows:
        if window["w"] > screen[0] or window["h"] > screen[1]:
            problems.append("%s: the window '%s' is %dx%d on a %dx%d screen" % (where, window["title"], window["w"], window["h"], screen[0], screen[1]))
        if not window["dialog"] and not any(token in window["title"] for token in ("ImageJ", "Fiji")) and not any(window["title"].startswith(a) for a in allowed):
            problems.append("%s: unexpected window '%s' (%s)" % (where, window["title"], window["cls"]))
    return problems


def settle_problems(records: list[dict[str, Any]]) -> list[str]:
    """A dialog whose layout kept changing until the harness's time ran out (obs `settle_timeout`) was NOT read and NOT clicked: that is a labelled failure of the layout (H-F1), never a pass and
    never a set of false failures about a half-built dialog."""
    return ["LAYOUT NOT SETTLED, the dialog was not read: " + r["detail"] for r in records if r.get("kind") == "obs" and r.get("what") == "settle_timeout"]
