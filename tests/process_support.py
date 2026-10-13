"""Worker processes of a case, as the operating system sees them (the cases of docs/SPEC_WRONG_SCIENCE.md section 5.6: no worker outlives the close).

A case with "worker_pidfile": true gets a file path in `case["pidfile"]` (for the harness) and in the environment variable LC_TEST_PIDFILE (for the tool the worker runs:
tests/lookups_app writes its own pid there as soon as it starts working). After Fiji has ended, `settle` waits for that process to be gone: a real condition (the process
table), at most `worker_gone_within_s` seconds after Fiji's end, and kills a straggler so that a failing case leaves nothing running.
"""

import os
import signal
import tempfile
import time
from pathlib import Path


def prepare(case: dict, env: dict) -> None:
    """Give the case a pid file path (empty file: the tool has not started yet)."""
    if case.get("worker_pidfile"):
        handle, path = tempfile.mkstemp(prefix="lcpid_")
        os.close(handle)
        Path(path).write_text("")
        case["pidfile"] = path
        env["LC_TEST_PIDFILE"] = path


def alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    try:  # a zombie is gone as far as this is concerned
        return Path("/proc/%d/stat" % pid).read_text().split(") ")[-1].split()[0] != "Z"
    except OSError:
        return False


def settle(case: dict, report: dict) -> None:
    """After Fiji ended: `worker_pid` (0 when the tool never started), `worker_gone` (True when the process was gone within the allowed time), `worker_gone_after_s`."""
    if not case.get("pidfile"):
        return
    text = Path(case["pidfile"]).read_text().strip()
    report["worker_pid"] = int(text) if text else 0
    if not report["worker_pid"]:
        report["worker_gone"] = None  # nothing ran: the case did not reach the tool (its other expectations say so)
        return
    started = time.monotonic()
    limit = float(case.get("worker_gone_within_s", 4))
    while alive(report["worker_pid"]) and time.monotonic() - started < limit:
        time.sleep(0.05)  # the process table is the condition; this only keeps the look from spinning
    report["worker_gone"] = not alive(report["worker_pid"])
    report["worker_gone_after_s"] = round(time.monotonic() - started, 2)
    if not report["worker_gone"]:
        os.kill(report["worker_pid"], signal.SIGKILL)  # a failing case must not leave its worker running
