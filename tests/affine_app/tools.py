"""A tool whose alignment matrix cannot be applied: Fiji must say so instead of drawing a blank overlay."""

from typing import Annotated

import numpy as np
from labconstrictor_tools import Affine, ApplyTo, Image, ImageOut, Name, Replace, tool


@tool("Singular alignment")
def singular(image: Image) -> Annotated[Affine, ApplyTo("image")]:
    return [[1.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 1.0]]


@tool("Identity alignment (replaces its overlay)")
def identity(image: Image) -> Annotated[Affine, ApplyTo("image"), Name("alignment"), Replace()]:
    return [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]


@tool("Alignment with an unrelated image")
def with_unrelated_image(image: Image) -> tuple[Annotated[Affine, ApplyTo("image"), Name("alignment")], Annotated[ImageOut, Name("preview")]]:
    """A valid alignment plus an image result that is not its warp (different size, no apply_to): the overlay must still be shown."""
    return [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]], np.zeros((3, 5), dtype=np.uint8)
