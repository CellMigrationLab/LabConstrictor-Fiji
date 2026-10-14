"""One Fiji start of the UI audit, under a watchdog.

A modal dialog blocks Fiji's event thread, so a hung form cannot be recovered from inside Fiji: every job (the choosers, one tool's form, one tool's run, one copy command) is its OWN Fiji
process in its own process group. The records the harness (tests/ui_audit_body.groovy) writes, one JSON object per line, are read WHILE Fiji runs and handed to `on_record` at once (so
the matrix is complete up to the moment of a crash); when the time limit passes the whole process tree (xvfb, Fiji, the app's workers) is killed and the orchestrator records the rest.
"""

import json
import os
import signal
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

POLL_S = 0.3
TAIL_LINES = 4
TAIL_CHARS = 220
SCREEN = "-screen 0 1600x1000x24"


@dataclass
class Launch:
    """What one Fiji start did: how it ended and every record it wrote."""

    returncode: Optional[int] = None
    timed_out: bool = False
    seconds: float = 0.0
    records: list[dict[str, Any]] = field(default_factory=list)
    tail: str = ""
    problem: str = ""

    def ended(self) -> str:
        """One sentence on how the process ended, for the detail of a check that was not reached."""
        if self.problem:
            return self.problem
        if self.timed_out:
            return "Fiji was killed by the watchdog after %.0f s%s" % (self.seconds, "; last output: " + self.tail if self.tail else "")
        return "Fiji ended (exit code %s) after %.0f s%s" % (self.returncode, self.seconds, "; last output: " + self.tail if self.tail else "")

    def finish(self) -> Optional[dict[str, Any]]:
        return next((r for r in self.records if r.get("kind") == "finish"), None)

    def fatal(self) -> Optional[str]:
        return next((r["detail"] for r in self.records if r.get("kind") == "fatal"), None)


def read_new(path: Path, offset: int) -> tuple[list[dict[str, Any]], int]:
    """The complete lines written to `path` after byte `offset`, parsed; a line that is not JSON is an error (never skipped)."""
    if not path.exists():
        return [], offset
    data = path.read_bytes()[offset:]
    end = data.rfind(b"\n") + 1
    records = []
    for line in data[:end].decode("utf-8", "replace").splitlines():
        if line.strip():
            try:
                records.append(json.loads(line))
            except ValueError as problem:
                records.append({"kind": "fatal", "detail": "the harness wrote a line that is not JSON (%s): %s" % (problem, line[:200])})
    return records, offset + end


def tail_of(*files: Path) -> str:
    text = ""
    for file in files:
        text += file.read_text(encoding="utf-8", errors="replace") if file.exists() else ""
    lines = [line for line in text.strip().splitlines() if line.strip() and not line.startswith("Picked up")]
    return " | ".join(lines[-TAIL_LINES:])[-TAIL_CHARS:]


def run_fiji(fiji: Path, arguments: list[str], env: dict[str, str], out: Path, timeout: float, on_record: Callable[[dict[str, Any]], None], cwd: Optional[Path] = None) -> Launch:
    """Start Fiji (xvfb-run fiji-linux-x64 --allow-multiple <arguments>) and wait for it at most `timeout` seconds, passing every record of `out` to `on_record` as it appears."""
    log = Path(tempfile.mkdtemp(prefix="lcaudit_fiji_"))
    command = ["xvfb-run", "-a", "-s", SCREEN, str(fiji / "fiji-linux-x64"), "--allow-multiple", *arguments]
    launch = Launch()
    began = time.monotonic()
    offset = 0
    try:
        with open(log / "stdout.txt", "wb") as stdout, open(log / "stderr.txt", "wb") as stderr:
            process = subprocess.Popen(command, env=env, stdout=stdout, stderr=stderr, start_new_session=True, cwd=cwd)
            while process.poll() is None:
                records, offset = read_new(out, offset)
                for record in records:
                    launch.records.append(record)
                    on_record(record)
                if time.monotonic() - began > timeout:
                    launch.timed_out = True
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                    break
                time.sleep(POLL_S)
            launch.returncode = process.returncode
    except OSError as problem:
        launch.problem = "could not start Fiji: %s" % problem
    records, offset = read_new(out, offset)
    for record in records:
        launch.records.append(record)
        on_record(record)
    launch.seconds = time.monotonic() - began
    launch.tail = tail_of(log / "stderr.txt", log / "stdout.txt")
    return launch


def launch_arguments(options: Optional[str], menu_entry: Optional[str], jar: bool) -> list[str]:
    """The arguments that start the plugin: the loose script (`--run`), or, with LC_FIJI_MODE=jar, the built jar's menu command through a macro (tests/run_cases.py installs both).
    `options`: a macro call with these options (jar mode); `menu_entry`: in jar mode, run that other menu entry instead (the "Copy last run as command" entry)."""
    import run_cases

    arguments = run_cases.install_into_fiji({"macro": options} if options is not None else None)
    if jar and menu_entry is not None:
        Path(arguments[1]).write_text('run("%s"%s);\n' % (menu_entry, ', "%s"' % options if options else ""))
    return arguments


def discover_apps(home: str, fiji: Path) -> Any:
    """What Fiji lists in the registry `home`, by the plugin's own discovery (tests/sandbox_listing.py): ({app: [tool ids]}, [problems]) or a sentence saying why it could not be asked."""
    import sandbox_listing

    return sandbox_listing.discover(home, fiji)
