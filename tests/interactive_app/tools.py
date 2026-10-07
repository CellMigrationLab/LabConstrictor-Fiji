"""Manifest interaction hints: ChoicesFrom (options asked of another tool), Replace (a new result replaces the old window)."""

from pathlib import Path
from typing import Annotated, Optional

import numpy as np
from labconstrictor_tools import ChoicesFrom, ClearAfterRun, Folder, ImageOut, Name, Replace, Scalars, tool


@tool("List options")
def list_options(folder: Folder) -> Scalars:
    """The options found in options.txt of the folder (none when the file is missing)."""
    file = Path(folder) / "options.txt"
    return {"choices": file.read_text().split() if file.exists() else []}


@tool("Answer")
def answer(
    folder: Folder,
    guess: Annotated[Optional[str], ChoicesFrom("list_options", depends=["folder"]), ClearAfterRun()] = None,
) -> tuple[Annotated[ImageOut, Name("view"), Replace()], Scalars]:
    """Shows a small image and repeats the guess."""
    size = 16 + len(guess or "")
    return np.full((size, size), 7, dtype=np.uint8), {"guess": guess or ""}
