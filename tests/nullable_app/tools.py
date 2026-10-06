"""Optional values with no default (nullable) must stay unset unless the user ticks 'Set ...'; Folder is a directory chooser."""

from typing import Optional

from labconstrictor_tools import Folder, Scalars, ToolError, tool


@tool("Optional settings")
def optional_settings(
    fixed_seed: Optional[int] = None,
    max_seconds: Optional[float] = None,
    notes: Optional[str] = None,
    output_folder: Optional[Folder] = None,
) -> Scalars:
    return {"seed": fixed_seed, "max_seconds": max_seconds, "notes": notes, "folder": str(output_folder)}


@tool("No match")
def no_match() -> Scalars:
    """A tool whose honest answer is 'nothing found' (shown as a message, not as an error)."""
    raise ToolError("no_match", "No match found: try another setting.")
