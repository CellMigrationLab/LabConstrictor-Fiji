"""Tools that fail in ways that leave only the worker's last words behind."""

import os
import signal

if os.environ.get("LC_TEST_IMPORT_FAILS") == "runtime":  # the worker imports this module when it starts: a failure here ends it with code 3 and the marker
    raise RuntimeError("deliberate failure while the tool module is imported")

from labconstrictor_tools import Scalars, tool


@tool("Killed by the OS")
def killed(a: float = 1.0) -> Scalars:
    os.kill(os.getpid(), signal.SIGKILL)  # what the Linux OOM killer does
    return {}


@tool("Raises with a traceback")
def raises(a: float = 1.0) -> Scalars:
    return {"x": 1 / 0}


@tool("Returns NaN and numpy values")
def nan_and_numpy(a: float = 1.0) -> Scalars:
    import numpy as np

    return {"nan": float("nan"), "inf": float("inf"), "np_int": np.int64(5), "plain": a}


@tool("Exits with code 3")
def exits_with_code_3(a: float = 1.0) -> Scalars:
    os._exit(3)  # what the Windows C runtime does for abort(): code 3, and no word about an import
    return {}
