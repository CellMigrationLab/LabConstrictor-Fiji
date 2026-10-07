
import time
from typing import Annotated, Optional
import numpy as np
from labconstrictor_tools import ApplyTo, Image, ChoicesFrom, ClearAfterRun, ImageOut, MessageOut, Name, PointsOut, Replace, Scalars, ShapesOut, ToolError, tool

@tool("Options: broken")
def broken_options(mode: str = "x") -> Scalars:
    raise RuntimeError("the source tool is broken")
@tool("Options: not a list")
def odd_options(mode: str = "x") -> Scalars:
    return {"choices": "not-a-list"}
@tool("Options: many")
def many_options(mode: str = "x") -> Scalars:
    return {"choices": ["opt %05d é中" % i for i in range(5000)] + ["", "dup", "dup", 7]}
@tool("Pick")
def pick(mode: Annotated[str, ChoicesFrom("many_options")] = "x",
         a: Annotated[Optional[str], ChoicesFrom("broken_options"), ClearAfterRun()] = None,
         b: Annotated[Optional[str], ChoicesFrom("odd_options")] = None,
         c: Annotated[Optional[str], ChoicesFrom("many_options"), ClearAfterRun()] = None) -> Scalars:
    return {"mode": mode, "a": a or "", "c": c or ""}
@tool("Dims")
def dims(ndim: int = 2, rows: int = 3, text_length: int = 10, fail: bool = False) -> tuple[
        Annotated[ImageOut, Name("img"), Replace()], Annotated[PointsOut, Name("pts"), Replace()], Annotated[MessageOut, Name("msg")]]:
    if fail:
        raise ToolError("boom", "deliberate failure")
    import pandas as pd
    shape = (16,) * ndim
    pts = pd.DataFrame({"y": np.arange(rows, dtype=float), "x": np.arange(rows, dtype=float)}) if rows else pd.DataFrame({"y": [], "x": []})
    return np.ones(shape, np.uint8), pts, ("word " * text_length) + "<b>not bold</b> [link](http://example.com) **bold**"

@tool("Spots")
def spots(image: Image, rows: int = 3) -> Annotated[PointsOut, Name("pts"), ApplyTo("image"), Replace()]:
    import pandas as pd
    return pd.DataFrame({"y": np.arange(rows, dtype=float) * 10, "x": np.arange(rows, dtype=float) * 10}) if rows else pd.DataFrame({"y": [], "x": []})


@tool("Outlines")
def outlines(image: Image, count: int = 3, holes: bool = False) -> Annotated[ShapesOut, Name("shp"), ApplyTo("image"), Replace()]:
    """`count` small triangles on a grid (0 = none); with holes=True each is a square with a hole, split in two parts."""
    features = []
    for i in range(count):
        x0, y0 = (i % 300) * 3.0, (i // 300) * 3.0
        outer = [[x0, y0], [x0 + 2, y0], [x0 + 2, y0 + 2], [x0, y0 + 2], [x0, y0]]
        if holes:
            hole = [[x0 + 0.5, y0 + 0.5], [x0 + 1.5, y0 + 0.5], [x0 + 1.5, y0 + 1.5], [x0 + 0.5, y0 + 1.5], [x0 + 0.5, y0 + 0.5]]
            far = [[x0 + 100, y0], [x0 + 102, y0], [x0 + 102, y0 + 2], [x0 + 100, y0]]
            geometry = {"type": "MultiPolygon", "coordinates": [[outer, hole], [far]]}
        else:
            geometry = {"type": "Polygon", "coordinates": [[[x0, y0], [x0 + 2, y0], [x0, y0 + 2], [x0, y0]]]}
        features.append({"type": "Feature", "properties": {"label": i + 1, "name": "noyau µ→✓"}, "geometry": geometry})
    return {"type": "FeatureCollection", "features": features}
