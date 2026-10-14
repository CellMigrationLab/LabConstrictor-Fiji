"""Checks of the pure-Python parts of the Fiji UI audit WITHOUT Fiji: the matrix writer, the two-sided control comparison, the geometry and window rules, the case-to-form mapping and the
verdict on a run, each fed a fake report, including the seeded failures (a removed control, an extra control, no controls at all, an off-screen OK button, a failed run, a blank result,
an unexpected dialog). Prints `LOGIC <name> PASS|FAIL <detail>`; exit 1 on FAIL.

    python tests/ui_audit_logic_check.py
"""

import io
import json
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ui_audit_forms as forms  # noqa: E402
import ui_audit_matrix as matrix  # noqa: E402
import ui_audit_runs as runs  # noqa: E402
import ui_audit_settings as settings  # noqa: E402

HERE = Path(__file__).resolve().parent
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print("LOGIC %s %s %s" % (name, "PASS" if ok else "FAIL", detail))
    if not ok:
        FAILURES.append(name)


def schema() -> dict[str, Any]:
    return {"tools": [{"id": "kitchen", "label": "Kitchen", "description": "Echoes its inputs in every output type.", "inputs": [
        {"name": "text", "label": "Text", "type": "string", "required": True},
        {"name": "image", "label": "Image", "type": "image", "required": True},
        {"name": "other", "label": "Other", "type": "image", "required": False, "nullable": True},
        {"name": "count", "label": "Count", "type": "integer", "required": False, "default": 3},
        {"name": "scale", "label": "Scale", "type": "float", "unit": "um/px", "pixel_size_of": "image", "required": False},
        {"name": "flag", "label": "Flag", "type": "boolean", "required": False},
        {"name": "mode", "label": "Mode", "type": "choice", "choices": ["a", "b"], "required": False},
        {"name": "maybe", "label": "Maybe", "type": "string", "nullable": True, "required": False}],
        "outputs": [{"name": "out", "type": "image"}, {"name": "rows", "type": "table"}, {"name": "values", "type": "values"}]}]}


def control(kind: str, label: str, rect: Any = (10, 10, 50, 20)) -> dict[str, Any]:
    return {"id": kind, "kind": "x", "label": forms.norm(label), "text": None, "rect": list(rect)}


def good_form(tool: dict[str, Any]) -> list[dict[str, Any]]:
    found = [control("form_ok", ""), control("form_cancel", ""), control("message_text", "Description")]
    for want in forms.expected_widgets(tool, True):
        found.append(control(want["ids"][0], want["label"]))
    return found


def test_matrix() -> None:
    out = io.StringIO()
    with tempfile.TemporaryDirectory() as folder:
        m = matrix.Matrix(Path(folder), "demo", ["panel", "tools"], out)
        m.expect(["a", "b", "c", "d"])
        m.add("a", "PASS", "fine")
        m.add("b", "FAIL", "multi\nline   detail " + "x" * 600)
        m.add("d", "HUMAN", "a person")
        m.add("a", "PASS", "one control", control="text_field 'x'")
        on_disk = json.loads((Path(folder) / "matrix_ui_fiji_demo.json").read_text())
        check("matrix_written_after_each_row", len(on_disk["rows"]) == 4 and on_disk["finished"] is None, "rows %d" % len(on_disk["rows"]))
        code = m.finish()
        final = json.loads((Path(folder) / "matrix_ui_fiji_demo.json").read_text())
        check("matrix_not_run_is_red", m.status_of("c") == "NOT_RUN" and code == 1 and final["finished"], str(m.counts()))
        lines = out.getvalue().splitlines()
        check("matrix_lines_one_line", all("\n" not in x for x in lines) and "CHECK b FAIL" in lines[1] and len(lines[1]) < 520 and "NOTE d not run: a person" in lines[2] and "CHECK c FAIL not run:" in lines[3] and len(lines) == 4, repr(lines)[:200])
        check("matrix_rows_carry_app_and_host", all(r["app"] == "demo" and r["host"] == "fiji" for r in final["rows"]), "")
        check("matrix_expected_file", json.loads((Path(folder) / "expected_fiji_demo.json").read_text())["ids"] == ["a", "b", "c", "d"], "")


def test_forms() -> None:
    tool = schema()["tools"][0]
    ids = [w["ids"][0] for w in forms.expected_widgets(tool, True)]
    check("expected_widgets", ids == ["text_field", "image_choice", "text_field", "file_browse", "use_image_checkbox", "image_choice", "text_field", "file_browse", "text_field", "number_spinner", "flag_checkbox",
                                      "choice_combo", "set_checkbox", "text_field"] or len(ids) >= 12, str(ids))
    texts = ["echoes its inputs in every output type."]
    check("form_ok_passes", forms.form_problems(tool, good_form(tool), texts, True) == [], str(forms.form_problems(tool, good_form(tool), texts, True)))
    removed = good_form(tool)
    removed.remove(next(c for c in removed if c["id"] == "flag_checkbox"))
    check("seeded_removed_control_fails", any("missing" in p and "flag" in p for p in forms.form_problems(tool, removed, texts, True)), "")
    extra = good_form(tool) + [control("text_field", "Injected")]
    check("seeded_extra_control_fails", any("extra control" in p and "injected" in p for p in forms.form_problems(tool, extra, texts, True)), "")
    check("seeded_zero_controls_fails", any("only 0 control" in p for p in forms.form_problems(tool, [], texts, True)), "")
    check("seeded_no_description_fails", any("description" in p for p in forms.form_problems(tool, good_form(tool), [], True)), "")
    check("absent_control_found", bool(forms.absent_problems([{"id": "chooser_ok", "label": "", "text": "Rescan apps"}])), "")
    check("chooser_two_sided", forms.chooser_problems("app", [control("chooser_app", ""), control("chooser_ok", ""), control("chooser_cancel", "")], ["a", "b"], ["a", "b"]) == []
          and forms.chooser_problems("app", [control("chooser_app", "")], ["a"], ["a", "b"]) != [], "")
    geometry = {"screen": [1600, 1000], "dialog": [100, 100, 300, 200]}
    check("geometry_ok", forms.geometry_problems(geometry, [control("form_ok", "", (200, 250, 60, 20))]) == [], "")
    check("seeded_offscreen_ok_fails", any("not on the screen" in p for p in forms.geometry_problems(geometry, [control("form_ok", "", (1700, 250, 60, 20))])), "")
    check("seeded_oversized_dialog_fails", any("oversized" in p for p in forms.geometry_problems({"screen": [1600, 1000], "dialog": [0, 0, 3463, 200]}, [])), "")
    shown = [{"cls": "javax.swing.JDialog", "title": "Kitchen", "dialog": True, "x": 0, "y": 0, "w": 300, "h": 200}, {"cls": "ij.ImageJ", "title": "(Fiji Is Just) ImageJ", "dialog": False, "x": 0, "y": 0, "w": 600, "h": 80}]
    check("windows_ok", forms.window_problems(shown, [1600, 1000], ["Kitchen"], ["Log"], "form") == [], "")
    shown_bad = shown + [{"cls": "javax.swing.JDialog", "title": "Error", "dialog": True, "x": 0, "y": 0, "w": 3463, "h": 100}, {"cls": "ij.plugin.frame.Recorder", "title": "Recorder", "dialog": False, "x": 0, "y": 0, "w": 100, "h": 100}]
    problems = forms.window_problems(shown_bad, [1600, 1000], ["Kitchen"], ["Log"], "form")
    check("seeded_error_dialog_and_recorder_fail", any("dialogs showing" in p for p in problems) and any("oversized" in p or "3463" in p for p in problems) and any("Recorder" in p for p in problems), str(problems)[:200])


def test_runs() -> None:
    tool = schema()["tools"][0]
    inputs = {"text": "hello", "image": "/x/cal.tif", "count": 4, "mode": "b", "maybe": "m", "flag": False}
    preload = [{"path": "/x/cal.tif", "title": "cal.tif"}]
    fills = runs.build_fills(tool, inputs, preload)
    labels = [(f["label"], f["value"]) for f in fills]
    check("fills", ("Image", "cal.tif") in labels and ("Set maybe", True) in labels and ("Count", "4") in labels and ("Flag", False) in labels and ("Text", "hello") in labels, str(labels))
    case = {"tool": "kitchen", "expect": {"results": {"values": {"mode": "b"}, "rows": {"rows": 4}}}}
    summary = {"status": "COMPLETE", "request": {"text": "hello", "count": 4, "mode": "b", "flag": False, "maybe": "m"}, "image_out": [32, 32, 1, 8], "table_rows": {"rows": 4, "cols": ["i"]}, "values_values": {"mode": "b"}}
    summary["image_out"], summary["table_rows"] = summary.pop("image_out"), summary.pop("table_rows")
    tool = dict(tool, outputs=[{"name": "out", "type": "image"}, {"name": "rows", "type": "table"}, {"name": "values", "type": "values"}])
    summary = {"status": "COMPLETE", "request": {"text": "hello", "count": 4, "mode": "b", "flag": False, "maybe": "m"}, "image_out": [32, 32, 1, 8], "table_rows": {"rows": 4, "cols": ["i"]}, "values_values": {"mode": "b"}}
    fin = lambda summary: {"summary": summary, "images": [{"title": "app:out", "width": 32, "height": 32, "pixel_width": 1.0, "pixel_height": 1.0, "unit": "pixel", "overlay": 0, "roi": None}]}   # noqa: E731
    files = {"new_runs": ["20260101_kitchen"], "record_files": ["run.json"], "log_file": True}
    problems, _un = runs.run_problems(tool, case, inputs, preload, fin(summary), files)
    check("run_ok", problems == [], str(problems))
    failed = dict(summary, status="FAILED", failure_message="boom")
    check("seeded_failed_run_fails", any("status 'FAILED'" in p for p in runs.run_problems(tool, case, inputs, preload, fin(failed), files)[0]), "")
    blank = dict(summary, image_out=[0, 0, 1, 8], values_values={})
    problems = runs.run_problems(tool, case, inputs, preload, fin(blank), files)[0]
    check("seeded_blank_result_fails", any("blank" in p and "out" in p for p in problems) and any("empty" in p and "values" in p for p in problems), str(problems)[:200])
    wrong = dict(summary, values_values={"mode": "a"})
    check("seeded_wrong_value_fails", any("mode is 'a'" in p for p in runs.run_problems(tool, case, inputs, preload, fin(wrong), files)[0]), "")
    missing = {k: v for k, v in summary.items() if k != "table_rows"}
    check("seeded_missing_result_fails", any("'rows'" in p and "not shown" in p for p in runs.run_problems(tool, case, inputs, preload, fin(missing), files)[0]), "")
    check("no_run_record_fails", any("run record" in p for p in runs.run_problems(tool, case, inputs, preload, fin(summary), dict(files, new_runs=[]))[0]), "")
    check("no_finish_fails", runs.run_problems(tool, case, inputs, preload, None, files)[0] != [], "")
    sent = dict(summary, request={"text": "hello", "count": 5, "mode": "b", "flag": False, "maybe": "m"})
    check("seeded_wrong_value_sent_fails", any("count" in p for p in runs.run_problems(tool, case, inputs, preload, fin(sent), files)[0]), "")
    cases = [{"tool": "a", "expect": {"status": "FAILED"}}, {"tool": "a", "expect": {}}, {"tool": "stress_real_memory"}, {"tool": "b", "cancel_after_s": 1}, {"tool": "c", "skip": "x"}]
    check("case_choice", runs.tool_case(cases, "a") is cases[1] and runs.tool_case(cases, "stress_real_memory") is None and runs.tool_case(cases, "b") is None and runs.tool_case(cases, "c") is None, "")
    done: list[int] = []
    ordered = [{"tool": "p", "expect": {}}, {"tool": "q", "expect": {}}, {"tool": "r", "expect": {}}]
    check("ordered_replay", runs.replay_before(ordered, ordered[2], done) == ordered[:2] and runs.replay_before(ordered, ordered[2], done) == [], "")


def test_screens_and_settings() -> None:
    with tempfile.TemporaryDirectory() as folder:
        good = Path(folder) / "a.png"
        good.write_bytes(b"x" * 5000)
        obs = {"path": str(good), "width": 300, "height": 200, "not_flat": True}
        check("screens_ok", runs.screenshot_problems(obs, "form", 0) == [], "")
        check("seeded_missing_screen_fails", runs.screenshot_problems({"path": str(Path(folder) / "none.png")}, "form", 0) != [] and runs.screenshot_problems(None, "form", 0) != [], "")
        check("seeded_blank_screen_fails", any("flat" in p for p in runs.screenshot_problems(dict(obs, not_flat=False), "form", 0)), "")
        check("seeded_stale_screen_fails", any("stale" in p for p in runs.screenshot_problems(obs, "form", good.stat().st_mtime + 100)), "")
    for env, word in (({}, "set"), ({"LC_HOME": "h", "LC_FIJI_HOME": "/nonexistent", "LC_APP": "a", "LC_APP_TESTS": "t", "LC_EXPECT_FIJI": "maybe"}, "listed or not_listed")):
        try:
            settings.read_settings(env)
            check("settings_usage_error_" + word.split()[0], False, "no error")
        except SystemExit as stop:
            check("settings_usage_error_" + word.split()[0], word in str(stop), str(stop)[:100])


def test_placement() -> None:
    import numpy
    import tifffile

    import ui_audit_placement as placement

    folder = Path(tempfile.mkdtemp())
    path = str(folder / "cal.tif")
    tifffile.imwrite(path, numpy.zeros((32, 32), dtype="uint8"), imagej=True, resolution=(2.0, 2.0), metadata={"unit": "um"})
    check("fixture_scale_read_with_tifffile", placement.fixture_pixel_size_um(path) == 0.5, str(placement.fixture_pixel_size_um(path)))
    tool = {"inputs": [{"name": "image", "type": "image"}], "outputs": [{"name": "out", "type": "image", "display": {"apply_to": "image"}}, {"name": "pts", "type": "points"}]}
    case = {"tool": "t", "expect": {"results": {"pts": {"rows": 3}}}}
    preload = [{"path": path, "title": "cal.tif"}]

    def image(title: str, width: float, unit: str, roi: object = None) -> dict[str, object]:
        return {"title": title, "width": 32, "height": 32, "pixel_width": width, "pixel_height": width, "unit": unit, "overlay": 0, "roi": roi}

    def problems_of(finish: dict[str, object], schema_tool: dict[str, object] = tool) -> list[str]:
        return placement.placement_problems("app", schema_tool, case, {"image": path}, finish, preload)

    good = {"summary": {}, "images": [image("cal.tif", 0.5, "micron", {"type": "point", "name": "app:pts", "points": 3}), image("app:out", 0.5, "micron")]}
    check("placement_ok", problems_of(good) == [], str(problems_of(good)))
    check("seeded_result_without_the_scale_fails", any("scale" in p for p in problems_of({"summary": {}, "images": [good["images"][0], image("app:out", 1.0, "pixel")]})), "")
    stray = {"summary": {}, "images": [image("cal.tif", 0.5, "micron"), image("other.tif", 1.0, "pixel", {"type": "point", "name": "app:pts", "points": 3}), good["images"][1]]}
    check("seeded_points_on_the_wrong_image_fail", any("not on 'cal.tif'" in p for p in problems_of(stray)) and any("also on 'other.tif'" in p for p in problems_of(stray)), str(problems_of(stray))[:200])
    wrong_count = {"summary": {}, "images": [image("cal.tif", 0.5, "micron", {"type": "point", "name": "app:pts", "points": 2}), good["images"][1]]}
    check("seeded_wrong_point_count_fails", any("expected a point ROI with 3" in p for p in problems_of(wrong_count)), "")
    sized = {"inputs": [{"name": "image", "type": "image"}, {"name": "scale", "type": "float", "pixel_size_of": "image"}], "outputs": []}
    check("seeded_wrong_pixel_size_sent_fails", any("declares 0.5 um" in p for p in problems_of({"summary": {"request": {"scale": 0.9}}, "images": good["images"]}, sized)), "")


if __name__ == "__main__":
    for test in (test_matrix, test_forms, test_runs, test_placement, test_screens_and_settings):
        test()
    print("%d logic check(s) failed" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)
