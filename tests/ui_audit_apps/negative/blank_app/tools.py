"""Seeded failure for the UI audit's negative controls (tests/ui_audit_negative.py): tools that COMPLETE but show nothing."""

from typing import Annotated

from labconstrictor_tools import Image, ImageOut, Name, Scalars, tool


@tool("Blank values")
def blank_values(a: float = 1.0) -> Scalars:
    """Completes and returns no value at all."""
    return {}


@tool("Blank image")
def blank_image(a: float = 1.0) -> Annotated[ImageOut, Name("empty")]:
    """Returns an image with no pixels (Tools refuses it: the run FAILS with bad_return, which the audit must report)."""
    import numpy as np

    return np.zeros((0, 0), dtype=np.uint8)
