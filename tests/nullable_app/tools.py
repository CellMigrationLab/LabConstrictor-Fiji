"""Optional values with no default (nullable) must stay unset unless the user ticks 'Set ...'; Folder is a directory chooser."""

from typing import Optional

from labconstrictor_tools import Folder, Scalars, tool


@tool("Optional settings")
def optional_settings(
    fixed_seed: Optional[int] = None,
    max_seconds: Optional[float] = None,
    notes: Optional[str] = None,
    output_folder: Optional[Folder] = None,
) -> Scalars:
    return {"seed": fixed_seed, "max_seconds": max_seconds, "notes": notes, "folder": str(output_folder)}
