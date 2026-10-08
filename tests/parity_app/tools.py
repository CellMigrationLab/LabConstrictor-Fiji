"""Tools for the host-parity cases: each one makes a rule of docs/HOST_PARITY.md visible in the result."""

from typing import Annotated, Literal

from labconstrictor_tools import Axes, Image, Scalars, tool


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
