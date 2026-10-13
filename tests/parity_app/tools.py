"""Tools for the host-parity cases: each one makes a rule of docs/HOST_PARITY.md visible in the result."""

from typing import Annotated, Literal

from labconstrictor_tools import (
    Axes,
    File as ToolFile,
    Folder,
    Max,
    Image,
    Min,
    Name,
    PixelSizeOf,
    PointsOut,
    Scalars,
    Replace,
    ShapesOut,
    TableOut,
    Unit,
    tool,
)


@tool("Plane only")
def plane_only(image: Annotated[Image, Axes("YX")]) -> Scalars:
    """A 2D tool: it must refuse a stack or a multi-channel image, never be handed a plane the person did not choose."""
    return {"ndim": image.ndim, "height": image.shape[0], "width": image.shape[1]}


@tool("Typed choices")
def typed_choices(
    count: Literal[1, 2, 3] = 2, ratio: Literal[0.5, 1.5] = 1.5, mode: Literal["a", "b"] = "a"
) -> Scalars:
    """Choices that are numbers must arrive as numbers: the worker refuses '2' and 2.0 for Literal[1, 2, 3]."""
    return {
        "count": count,
        "count_is_int": float(type(count) is int),
        "ratio": ratio,
        "ratio_is_float": float(type(ratio) is float),
        "mode_is_text": float(isinstance(mode, str)),
    }


@tool("Calibrated")
def calibrated(
    image: Image, scale: Annotated[float, Min(0), Unit("um/px"), PixelSizeOf("image")] = 0.5
) -> Scalars:
    """The pixel size field follows the calibration of the image (or file) until the person types a value."""
    return {"scale": scale, "height": image.shape[0]}


@tool("Path echo")
def path_echo(some_file: ToolFile, some_folder: Folder) -> Scalars:
    """Reports whether the paths arrived absolute: a relative one means something else in the worker's folder than in the host's."""
    from pathlib import Path

    return {
        "file_is_absolute": float(Path(some_file).is_absolute()),
        "folder_is_absolute": float(Path(some_folder).is_absolute()),
        "file_exists": float(Path(some_file).is_file()),
    }


@tool("Points without apply_to")
def points_without_apply_to(first: Image, second: Image) -> Annotated[PointsOut, Name("pts")]:
    """Points and outlines that name no image belong to the first image input (PROTOCOL.md), not to whichever image is current."""
    import pandas as pd

    return pd.DataFrame({"y": [1.0, 2.0], "x": [3.0, 4.0]})


@tool("Outlines without apply_to")
def outlines_without_apply_to(first: Image, second: Image) -> Annotated[ShapesOut, Name("shp")]:
    return {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {"label": 1},
                "geometry": {"type": "Polygon", "coordinates": [[[1, 1], [5, 1], [1, 5], [1, 1]]]},
            }
        ],
    }


@tool("Echo text")
def echo_text(text: str = "") -> Scalars:
    """Takes any text; the case looks at the command that repeats the run."""
    return {"length": len(text)}


@tool("Bounded numbers")
def bounded_numbers(
    above: Annotated[int, Min(5), Max(10)],
    below: Annotated[float, Min(-10.0), Max(-2.0)],
    around: Annotated[int, Min(-3), Max(3)],
    free: int,
) -> Scalars:
    """Required numbers with no default: each starts at the allowed value nearest to 0."""
    return {"above": above, "below": below, "around": around, "free": free}


@tool("Table out")
def table_out() -> Annotated[TableOut, Name("rows")]:
    """A table result: a second run (or a window of that name already open) must not overwrite the first."""
    return {"i": [1, 2], "v": [0.5, 1.5]}


@tool("Table out, replacing")
def table_out_replacing() -> Annotated[TableOut, Name("rows"), Replace()]:
    return {"i": [1, 2], "v": [0.5, 1.5]}


@tool("Twin", id="twin_a")
def twin_a() -> Scalars:
    """The first tool called Twin."""
    return {"which": 1}


@tool("Twin", id="twin_b")
def twin_b() -> Scalars:
    """The second tool called Twin."""
    return {"which": 2}


@tool("Worker environment")
def worker_environment() -> Scalars:
    """What the worker inherited from the host: nothing of the host's Python or Qt, the host's working directory, a safe import path."""
    import os
    from pathlib import Path

    return {
        "python_home_unset": float(not os.environ.get("PYTHONHOME")),
        "virtual_env_unset": float(not os.environ.get("VIRTUAL_ENV")),
        "conda_prefix_unset": float(not os.environ.get("CONDA_PREFIX")),
        "qt_plugin_path_unset": float(not os.environ.get("QT_PLUGIN_PATH")),
        "safe_path": float(os.environ.get("PYTHONSAFEPATH") == "1"),
        "in_host_directory": float((Path.cwd() / "sample.tif").is_file()),
    }
