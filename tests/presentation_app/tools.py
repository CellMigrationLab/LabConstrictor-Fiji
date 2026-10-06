"""Manifest presentation hints: groups are shown together under a heading, advanced parameters come last under 'Advanced settings'."""

from typing import Annotated, Literal, Optional

from labconstrictor_tools import Advanced, EnabledWhen, Group, Min, Scalars, tool


@tool("Grouped settings")
def grouped(
    mode: Annotated[Literal["threshold", "cellpose"], Group("Segmentation")] = "threshold",
    size: Annotated[int, Min(0), Group("Images")] = 5,
    method: Annotated[Literal["otsu", "li"], Group("Segmentation"), EnabledWhen("mode", "threshold")] = "otsu",
    fixed_seed: Annotated[bool, Group("Seed"), Advanced()] = False,
    seed: Annotated[Optional[int], Group("Seed"), Advanced(), EnabledWhen("fixed_seed")] = None,
) -> Scalars:
    return {"mode": mode, "method": method, "size": size, "seed": seed}
