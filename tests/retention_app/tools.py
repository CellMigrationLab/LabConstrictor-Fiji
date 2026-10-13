"""Tools for the retention cases: results whose files live in the host's job folder."""

import glob
import os
import tempfile
from pathlib import Path
from typing import Annotated

from labconstrictor_tools import FileOut, Name, Scalars, ToolError, tool


def _job_folder() -> Path:
    """The job folder of the host that started this worker: the newest lcjob_fiji_* folder in the temporary area (the host makes it just before the run)."""
    places = {tempfile.gettempdir(), "/tmp"}  # Java makes the folder in its own temporary area (java.io.tmpdir, /tmp on Linux), Python reads TMPDIR
    found = sorted((p for place in places for p in glob.glob(os.path.join(place, "lcjob_fiji_*"))), key=os.path.getmtime)
    return Path(found[-1])


@tool("Writes a report")
def writes_a_report(a: float = 1.0) -> tuple[Annotated[FileOut, Name("report")], Annotated[Scalars, Name("seen")]]:
    """Writes report.txt (the text `data`) inside the host's job folder and returns it as a FileOut."""
    path = _job_folder() / "report.txt"
    path.write_text("data")
    return path, {"folder": str(path.parent)}


@tool("Writes a report and fails")
def writes_a_report_and_fails(a: float = 1.0) -> Scalars:
    """Leaves a file in the job folder and then fails: the job folder still goes, the run record says failed."""
    (_job_folder() / "report.txt").write_text("data")
    raise ToolError("the tool failed on purpose")
