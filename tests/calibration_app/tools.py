"""Tools for the calibration cases (docs/SPEC_WRONG_SCIENCE.md section 2, conformance.json CAL-01..CAL-20 and CAL-T1..CAL-T9): a pixel size linked to an image."""

from typing import Annotated, Optional

from labconstrictor_tools import Image, Min, PixelSizeOf, Scalars, Unit, tool


@tool("Pixel size required")
def pixel_size_required(
    image: Image, pixel_size: Annotated[float, Min(0), Unit("um/px"), PixelSizeOf("image")] = 1.0
) -> Scalars:
    """The declared default (1.0) was written for the author's example image: it must never be sent for another one."""
    return {"pixel_size": pixel_size, "height": image.shape[0]}


@tool("Pixel size optional")
def pixel_size_optional(
    image: Image, pixel_size: Annotated[Optional[float], Unit("um/px"), PixelSizeOf("image")] = None
) -> Scalars:
    """Without a calibration the tool receives None, never a number nobody gave."""
    return {"has_pixel_size": float(pixel_size is not None), "pixel_size": -1.0 if pixel_size is None else pixel_size}
