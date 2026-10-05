"""Tools that fail in ways that leave only the worker's last words behind."""

import os
import signal

from labconstrictor_tools import Scalars, tool


@tool("Killed by the OS")
def killed(a: float = 1.0) -> Scalars:
    os.kill(os.getpid(), signal.SIGKILL)  # what the Linux OOM killer does
    return {}


@tool("Raises with a traceback")
def raises(a: float = 1.0) -> Scalars:
    return {"x": 1 / 0}
