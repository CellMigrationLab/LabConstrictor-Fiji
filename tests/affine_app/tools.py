"""A tool whose alignment matrix cannot be applied: Fiji must say so instead of drawing a blank overlay."""

from typing import Annotated

from labconstrictor_tools import Affine, ApplyTo, Image, tool


@tool("Singular alignment")
def singular(image: Image) -> Annotated[Affine, ApplyTo("image")]:
    return [[1.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 1.0]]
