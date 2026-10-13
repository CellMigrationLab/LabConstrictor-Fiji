"""A tool that reports the numbers it received and their Python types: the request must carry exactly what was typed (docs/SPEC_WRONG_SCIENCE.md section 6)."""

from typing import Annotated

from labconstrictor_tools import Max, Scalars, tool


@tool("Echo numbers")
def echo_numbers(n: int, x: float, m: Annotated[int, Max(10)] = 1) -> Scalars:
    """Whole number with no bounds, float with no bounds, whole number with a declared Max of 10."""
    return {"n": n, "n_is_int": float(type(n) is int), "x": x, "x_is_float": float(type(x) is float), "m": m}
