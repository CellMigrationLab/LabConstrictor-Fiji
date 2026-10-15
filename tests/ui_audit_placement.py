"""Do the results of a Fiji run sit on the right image, with the right scale? Judged from what Fiji's own windows hold after the run (the harness reads every open ImagePlus: its size, its
calibration, its ROI and overlay, and the ROI Manager) against the FIXTURE FILE's own metadata and the case; never from a control of the dialog and never from the plugin's own summary alone.

* a pixel size linked to an image (PixelSizeOf) is sent as the size the fixture TIFF declares (read with tifffile), unless the case gives the number itself;
* an image result with an ApplyTo whose size equals its source's has the source's calibration (ImageJ units), which is the one the fixture declares; with other sizes it stays at the pixel
  origin (scale 1, no unit);
* a points result is the ROI of the image it belongs to (ApplyTo, else the first image input), a point ROI with as many points as the case says (else as the tool returned), and on no other image;
* a shapes result is the overlay of ITS image (ApplyTo, else the first image input), with the case's number of outlines (else at least one). A tool with several shapes outputs (NucleiSky
  `relocalize`: reference_outlines on reference.tif, query_outlines on query.tif) is judged output by output against the image each one belongs to: the overlay an image holds must be
  exactly the outlines of the shapes outputs that belong to it, and an image that no shapes output belongs to carries none.
"""

from pathlib import Path
from typing import Any, Callable, Optional

UNIT_UM = {"um": 1.0, "µm": 1.0, "micron": 1.0, "microns": 1.0, "nm": 1e-3, "mm": 1e3, "cm": 1e4}   # ImageJ unit texts of a length, in micrometres
TOLERANCE = 1e-6


def close(found: Any, wanted: Any) -> bool:
    return isinstance(found, (int, float)) and isinstance(wanted, (int, float)) and abs(found - wanted) <= TOLERANCE * max(1.0, abs(wanted))


def fixture_pixel_size_um(path: str) -> Optional[float]:
    """The pixel size in micrometres that the TIFF itself declares (ImageJ unit text, or a centimetre resolution unit), read with tifffile, never with Fiji; None when the file declares none the
    Tools' unit rules accept (no resolution, the default 72 or 96 dpi, an unknown unit text)."""
    import tifffile

    with tifffile.TiffFile(path) as tif:
        page = tif.pages[0]
        tag = page.tags.get("XResolution")
        if tag is None or not tag.value[0]:
            return None
        per_unit = tag.value[0] / tag.value[1]
        unit = (tif.imagej_metadata or {}).get("unit")
        if unit:
            factor = UNIT_UM.get(str(unit))
            return None if factor is None else factor / per_unit
        resolution_unit = page.tags.get("ResolutionUnit")
        if resolution_unit is not None and int(resolution_unit.value) == 3 and abs(per_unit - 37.8) / 37.8 > 0.005:   # centimetres, not the 96 dpi default
            return 1e4 / per_unit
        return None


def sent_size_problems(tool: dict[str, Any], inputs: dict[str, Any], summary: dict[str, Any]) -> list[str]:
    """The pixel size sent to the worker for a calibrated image is the size the fixture file declares."""
    problems: list[str] = []
    sent = summary.get("request") or {}
    for p in tool["inputs"]:
        source = inputs.get(p.get("pixel_size_of") or "")
        if p.get("pixel_size_of") and isinstance(source, str) and Path(source).is_file() and inputs.get(p["name"]) is None:
            size = fixture_pixel_size_um(source)
            if size is not None and not close(sent.get(p["name"]), size):
                problems.append("the pixel size of '%s' is sent as %r, the file %s declares %.6g um" % (p["name"], sent.get(p["name"]), Path(source).name, size))
    return problems


def expected_count(case: dict[str, Any], name: str, key: str) -> Optional[int]:
    wanted = case.get("expect", {}).get("results", {}).get(name, {}).get(key)
    return wanted if isinstance(wanted, int) else None


def placement_problems(app: str, tool: dict[str, Any], case: dict[str, Any], inputs: dict[str, Any], finish: dict[str, Any], preload: list[dict[str, str]]) -> list[str]:
    """Problems with where the results of a COMPLETE run were put (see the module text)."""
    summary = finish.get("summary", {})
    problems = sent_size_problems(tool, inputs, summary)
    images = {item["title"]: item for item in finish.get("images", [])}
    titles = {item["path"]: item["title"] for item in preload}
    image_inputs = [p["name"] for p in tool["inputs"] if p["type"] in ("image", "labels")]

    def title_of(output: dict[str, Any]) -> Optional[str]:
        source = inputs.get((output.get("display") or {}).get("apply_to") or (image_inputs[0] if image_inputs else ""))
        return titles.get(source) if isinstance(source, str) else None

    overlays = shape_overlays(tool, case, summary, title_of)
    for output in tool["outputs"]:
        name, kind = output["name"], output["type"]
        source = inputs.get((output.get("display") or {}).get("apply_to") or (image_inputs[0] if image_inputs else ""))
        title = title_of(output)
        if kind in ("image", "labels"):
            shown = next((i for t, i in images.items() if t == "%s:%s" % (app, name) or t.startswith("%s:%s [" % (app, name))), None)
            if shown is None:
                problems.append("no image window '%s:%s' is open after the run" % (app, name))
            elif (output.get("display") or {}).get("apply_to") and isinstance(source, str) and Path(source).is_file() and title in images:
                problems += scale_problems(name, shown, images[title], fixture_pixel_size_um(source), Path(source).name)
        elif kind in ("points", "shapes") and title is not None:
            wanted = overlays[title] if kind == "shapes" else expected_count(case, name, "rows")
            returned = None if kind == "shapes" else (summary.get("points_" + name) or {}).get("count")
            problems += roi_problems(app, name, kind, title, images, wanted, returned, set(overlays))
    return problems


def shape_overlays(tool: dict[str, Any], case: dict[str, Any], summary: dict[str, Any], title_of: Callable[[dict[str, Any]], Optional[str]]) -> dict[str, Optional[int]]:
    """Image title -> the number of outlines its overlay must hold: the sum over the shapes outputs that belong to it (the case's `features`, else what the tool returned); None when any of
    them is unknown (then at least one)."""
    counts: dict[str, Optional[int]] = {}
    for output in tool["outputs"]:
        title = title_of(output) if output["type"] == "shapes" else None
        if title is None:
            continue
        wanted = expected_count(case, output["name"], "features")
        count = wanted if wanted is not None else (summary.get("shapes_" + output["name"]) or {}).get("count")
        previous = counts.get(title, 0)
        counts[title] = previous + count if isinstance(count, int) and previous is not None else None
    return counts


def scale_problems(name: str, result: dict[str, Any], source: dict[str, Any], fixture_um: Optional[float], source_file: str) -> list[str]:
    """The result image has the scale of its source (same size), which is the scale the fixture declares; a different size stays at scale 1 without a unit."""
    same = (result["width"], result["height"]) == (source["width"], source["height"])
    if not same:
        return [] if result["unit"] in ("pixel", "pixels", "") and close(result["pixel_width"], 1.0) else ["the image '%s' (%dx%d) is not the size of its source (%dx%d) but is calibrated: %r %s" % (
            name, result["width"], result["height"], source["width"], source["height"], result["pixel_width"], result["unit"])]
    problems = []
    if not (close(result["pixel_width"], source["pixel_width"]) and close(result["pixel_height"], source["pixel_height"]) and result["unit"] == source["unit"]):
        problems.append("the image '%s' has the scale %r x %r %s, its source image has %r x %r %s" % (name, result["pixel_width"], result["pixel_height"], result["unit"], source["pixel_width"],
                                                                                                     source["pixel_height"], source["unit"]))
    if fixture_um is not None and not (close(source["pixel_width"] * UNIT_UM.get(source["unit"], float("nan")), fixture_um) and close(result["pixel_width"] * UNIT_UM.get(result["unit"], float("nan")), fixture_um)):
        problems.append("the file %s declares %.6g um per pixel, the image '%s' has %r %s" % (source_file, fixture_um, name, result["pixel_width"], result["unit"]))
    return problems


def roi_problems(app: str, name: str, kind: str, title: str, images: dict[str, dict[str, Any]], wanted: Optional[int], returned: Optional[int], shape_images: set[str]) -> list[str]:
    """A points result is the point ROI of the image `title`, a shapes result its overlay (`wanted` is what the overlay of `title` must hold in all); nothing of it on any other image: for
    shapes, an image that is none of `shape_images` (the images some shapes output belongs to) must carry no overlay."""
    image = images.get(title)
    if image is None:
        return ["the image '%s' the %s result '%s' belongs to is not open" % (title, kind, name)]
    problems: list[str] = []
    count = wanted if wanted is not None else returned
    if kind == "points":
        roi = image.get("roi") or {}
        if roi.get("type") != "point" or (count is not None and roi.get("points") != count) or not roi.get("points"):
            problems.append("the points '%s' are not on '%s': its ROI is %s, expected a point ROI with %s point(s)" % (name, title, roi or "none", count if count is not None else "some"))
        stray = [t for t, i in images.items() if t != title and (i.get("roi") or {}).get("name") == "%s:%s" % (app, name)]
    else:
        if image["overlay"] < 1 or (count is not None and image["overlay"] != count):
            problems.append("the outlines '%s' are not on '%s': its overlay holds %d element(s), expected %s" % (name, title, image["overlay"], count if count is not None else "at least 1"))
        stray = [t for t, i in images.items() if t not in shape_images and i["overlay"] > 0]
    return problems + ["the %s '%s' is also on '%s'" % (kind, name, t) for t in stray]
