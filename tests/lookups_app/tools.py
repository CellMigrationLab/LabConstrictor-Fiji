"""Tools for the lookup and worker-lifetime cases (docs/SPEC_WRONG_SCIENCE.md section 5): sources that name the process that answered, and tools that never return."""

import os
import time
from pathlib import Path
from typing import Annotated

from labconstrictor_tools import ChoicesFrom, Scalars, progress, tool


def _note_pid() -> None:
    """Tell the test which process this worker is (the file is made by tests/process_support.py)."""
    where = os.environ.get("LC_TEST_PIDFILE")
    if where:
        Path(where).write_text(str(os.getpid()))


@tool("Options A")
def options_a(mode: str = "x") -> Scalars:
    return {"choices": ["A-%d" % os.getpid()]}


@tool("Options B")
def options_b(mode: str = "x") -> Scalars:
    return {"choices": ["B-%d" % os.getpid()]}


@tool("Options that never answer")
def options_never(mode: str = "x") -> Scalars:
    """Never answers and ignores Cancel, like a call into a compiled library that hangs."""
    _note_pid()
    time.sleep(300)
    return {"choices": ["late"]}


@tool("Pick from two")
def pick_from_two(
    a: Annotated[str, ChoicesFrom("options_a")] = "x", b: Annotated[str, ChoicesFrom("options_b")] = "x"
) -> Scalars:
    """Two parameters, each with its own source: one worker must ask both, and each answer belongs to its own dropdown."""
    return {"a_given": a, "b_given": b}


@tool("Pick from a source that never answers")
def pick_from_never(a: Annotated[str, ChoicesFrom("options_never")] = "x") -> Scalars:
    return {"a_given": a}


@tool("Blocks")
def blocks(seconds: float = 300.0) -> Scalars:
    """A run that never returns and ignores Cancel."""
    _note_pid()
    time.sleep(seconds)
    return {"slept": seconds}


@tool("Reports progress")
def reports_progress() -> Scalars:
    """Progress messages at known times, then a silence: the Log window shows the changed ones (throttled) and a heartbeat."""
    progress(0.1, "first step")
    time.sleep(0.1)
    progress(0.2, "second step")  # within the throttle interval: not in the Log window
    time.sleep(1.6)  # longer than the test heartbeat (1 s)
    progress(0.9, "third step")
    time.sleep(0.2)
    return {"done": 1}

