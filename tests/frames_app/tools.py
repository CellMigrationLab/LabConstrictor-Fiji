"""Tools for the coordinate-frame cases (docs/SPEC_WRONG_SCIENCE.md section 1, conformance.json CF-01..CF-14).

The fixture numbers (the point, the rectangle, the image) are read from labconstrictor_tools.conformance when a tool runs, never copied here:
the tools only say what they received (pixels, mask) or return what the fixture says, in the frame of the image they were given."""

from typing import Annotated, Optional

from labconstrictor_tools import (
    ApplyTo,
    Axes,
    Image,
    ImageOut,
    Labels,
    LabelsOut,
    Name,
    PickChannel,
    PointsOut,
    RegionOf,
    Replace,
    Scalars,
    ShapesOut,
    ToolError,
    tool,
)


def _fixture():
    from labconstrictor_tools import conformance

    return conformance.load()["wrong_science"]["coordinate"]


@tool("Mask seen")
def mask_seen(
    image: Image, region: Annotated[Optional[Labels], Axes("YX"), RegionOf("image")] = None
) -> Annotated[Scalars, Name("seen")]:
    """What the worker received: the image and the mask sizes, how many mask pixels are set, and the image pixel under each of them."""
    import numpy as np

    if region is None:
        raise ToolError("no_region", "no selection was sent")
    mask = np.asarray(region)
    out = {
        "image_h": image.shape[0],
        "image_w": image.shape[1],
        "mask_h": mask.shape[0],
        "mask_w": mask.shape[1],
        "mask_count": int((mask > 0).sum()),
    }
    for y, x in zip(*np.nonzero(mask > 0)):
        out["v_%d_%d" % (y, x)] = int(image[y, x])
    return out


@tool("Mark the fixture")
def mark_fixture(image: Image) -> tuple[
    Annotated[PointsOut, Name("pts"), ApplyTo("image")],
    Annotated[ShapesOut, Name("shp"), ApplyTo("image")],
]:
    """The point and the rectangle of the fixture, as a tool writes them: pixel centres at integers, points (y, x), outlines [x, y]."""
    import pandas as pd

    fixture = _fixture()
    case = next(c for c in fixture["cases"] if c["id"] == "CF-01")
    y, x = case["point"]
    ring = [[vx, vy] for vy, vx in fixture["rect"]]
    ring.append(ring[0])
    polygon = {"type": "Feature", "properties": {"label": 1}, "geometry": {"type": "Polygon", "coordinates": [ring]}}
    return pd.DataFrame({"y": [float(y)], "x": [float(x)]}), {"type": "FeatureCollection", "features": [polygon]}


@tool("Image then marks")
def image_then_marks(image: Image) -> tuple[
    Annotated[ImageOut, Name("pic")], Annotated[PointsOut, Name("pts"), ApplyTo("image")]
]:
    """An earlier output (an image that is shown first) and points that belong to the input: the points must not follow the earlier output."""
    import numpy as np
    import pandas as pd

    y, x = next(c for c in _fixture()["cases"] if c["id"] == "CF-01")["point"]
    return np.zeros((6, 6), np.uint8), pd.DataFrame({"y": [float(y)], "x": [float(x)]})


@tool("Labels of the same size")
def labels_same_size(image: Image) -> Annotated[LabelsOut, Name("lab"), ApplyTo("image")]:
    import numpy as np

    return np.ones(image.shape[:2], np.uint16)


@tool("Labels of half the size")
def labels_half_size(image: Image) -> Annotated[LabelsOut, Name("lab"), ApplyTo("image")]:
    """Result shape [4, 6] on an exported source of 8 x 12 (CF-11)."""
    import numpy as np

    return np.ones((image.shape[0] // 2, image.shape[1] // 2), np.uint16)


@tool("Labels that replace")
def labels_replace(image: Image) -> Annotated[LabelsOut, Name("lab"), ApplyTo("image"), Replace()]:
    import numpy as np

    return np.ones(image.shape[:2], np.uint16)


@tool("Marks without an image")
def marks_without_image() -> tuple[Annotated[PointsOut, Name("pts")], Annotated[ShapesOut, Name("shp")]]:
    import pandas as pd

    polygon = {"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [[[1, 1], [4, 1], [4, 4], [1, 1]]]}}
    return pd.DataFrame({"y": [1.0, 2.0], "x": [3.0, 4.0]}), {"type": "FeatureCollection", "features": [polygon]}


@tool("Marks with planes")
def marks_with_planes(image: Image) -> tuple[
    Annotated[PointsOut, Name("pts"), ApplyTo("image")], Annotated[ShapesOut, Name("shp"), ApplyTo("image")]
]:
    """Points and outlines that name a plane (t, z): placing them on the planes of a stack is not done, so they must not be drawn on one plane."""
    import pandas as pd

    polygon = {
        "type": "Feature",
        "properties": {"t": 1, "z": 0},
        "geometry": {"type": "Polygon", "coordinates": [[[1, 1], [4, 1], [4, 4], [1, 1]]]},
    }
    return (
        pd.DataFrame({"y": [1.0], "x": [3.0], "t": [1], "z": [0]}),
        {"type": "FeatureCollection", "features": [polygon]},
    )


@tool("Marks of a stack")
def marks_of_a_stack(image: Image) -> tuple[
    Annotated[PointsOut, Name("pts"), ApplyTo("image")], Annotated[ShapesOut, Name("shp"), ApplyTo("image")]
]:
    """Points and outlines with no t / z on an image that has planes: they land on the plane that was exported (1.1.9)."""
    import pandas as pd

    polygon = {"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [[[1, 1], [4, 1], [4, 4], [1, 1]]]}}
    return pd.DataFrame({"y": [1.0], "x": [3.0]}), {"type": "FeatureCollection", "features": [polygon]}


@tool("Marks of a channel")
def marks_of_a_channel(
    image: Annotated[Image, PickChannel(), Axes("YX")],
) -> Annotated[PointsOut, Name("pts"), ApplyTo("image")]:
    """The tool receives one channel (a plane): its points belong to that channel of the image."""
    import pandas as pd

    return pd.DataFrame({"y": [1.0], "x": [3.0]})


@tool("Marks on the second image")
def marks_on_the_second(first: Image, second: Image) -> Annotated[PointsOut, Name("pts"), ApplyTo("second")]:
    """The points belong to the SECOND image (ApplyTo): not to the first image input, and not to the image that is current."""
    import pandas as pd

    y, x = next(c for c in _fixture()["cases"] if c["id"] == "CF-01")["point"]
    return pd.DataFrame({"y": [float(y)], "x": [float(x)]})
