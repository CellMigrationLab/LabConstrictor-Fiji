"""The result record of the Fiji UI audit: one row per assertion, rewritten atomically after EVERY row, so a crash or a kill leaves a complete record of what was done.

    <LC_SHOTS>/expected_fiji_<app>.json     every id the audit intends to produce (written before anything runs)
    <LC_SHOTS>/matrix_ui_fiji_<app>.json    {host, app, family: "ui", host_version, started, finished|null, expected: <count>, parts, rows: [{id, family, host, app, tool, control, status, detail, seconds, screenshot, ts}]}
(the same schema as tests/oneoff_2026-10/matrix.py of LabConstrictor-Tools, plus `parts`)

Statuses: PASS, FAIL, NOT_RUN (expected, but no row was produced: red), HUMAN (cannot be automated here, or does not apply in this state: listed for a person, never green).
A row with `control` set belongs to a check (one control of a form, one fill): it is in the matrix only; a row without it is THE assertion and is also printed, one line:
    CHECK <id> PASS|FAIL <detail>            (NOT_RUN is printed as `CHECK <id> FAIL not run: <reason>`: red in the sandbox's missing-line guard too)
    NOTE <id> not run: <reason>              (HUMAN)
"""

import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any, Optional, TextIO

HOST = "fiji"
FAMILY = "ui"
MAX_DETAIL = 500
STATUSES = ("PASS", "FAIL", "NOT_RUN", "HUMAN", "NOTE")
RED = ("FAIL", "NOT_RUN")


def one_line(text: Any, limit: int = MAX_DETAIL) -> str:
    """`text` on one line of at most `limit` characters (cut with '...')."""
    flat = " ".join(str(text).split())
    return flat if len(flat) <= limit else flat[: limit - 3] + "..."


def sanitize(text: str) -> str:
    """A tool id as it appears in a check id: only [a-z0-9_]."""
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


class Matrix:
    """The rows of one app's audit, kept on disk after every change."""

    def __init__(self, shots: Path, app: str, parts: list[str], out: Optional[TextIO] = None, host_version: str = "unknown") -> None:
        self.app = app
        self.host_version = host_version
        self.out = out if out is not None else sys.stdout
        self.directory = shots
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = shots / ("matrix_%s_%s_%s.json" % (FAMILY, HOST, app))
        self.expected_path = shots / ("expected_%s_%s.json" % (HOST, app))
        self.expected: list[str] = []
        self.started = time.strftime("%Y-%m-%dT%H:%M:%S")
        self.finished: Optional[str] = None
        self.parts = parts
        self.rows: list[dict[str, Any]] = []
        self._write()

    def expect(self, ids: list[str]) -> None:
        """Declare every id this run intends to produce (before anything runs); `finish` marks the ones without a row NOT_RUN."""
        for check_id in ids:
            if check_id not in self.expected:
                self.expected.append(check_id)
        self._atomic(self.expected_path, {"host": HOST, "app": self.app, "ids": self.expected})
        self._write()

    def add(self, check_id: str, status: str, detail: str, tool: Optional[str] = None, control: Optional[str] = None, seconds: float = 0.0,
            screenshot: Optional[str] = None) -> None:
        """Record one row (and print it when it is the assertion itself, not one control of it)."""
        if status not in STATUSES:
            raise ValueError("status %r is not one of %s" % (status, STATUSES))
        text = one_line(detail)
        self.rows.append({"id": check_id, "family": FAMILY, "host": HOST, "app": self.app, "tool": tool, "control": control, "status": status, "detail": text,
                          "seconds": round(float(seconds), 2), "screenshot": screenshot, "ts": time.strftime("%Y-%m-%dT%H:%M:%S")})
        self._write()
        if control is None:
            self.out.write(self.line(check_id, status, text) + "\n")
            self.out.flush()

    @staticmethod
    def line(check_id: str, status: str, detail: str) -> str:
        if status == "NOTE":
            return "NOTE %s %s" % (check_id, detail)
        if status == "HUMAN":
            return "NOTE %s not run: %s" % (check_id, detail)
        if status == "NOT_RUN":
            return "CHECK %s FAIL not run: %s" % (check_id, detail)
        return "CHECK %s %s %s" % (check_id, status, detail)

    def status_of(self, check_id: str) -> Optional[str]:
        """The status of an assertion: FAIL if any of its rows failed, else PASS if it has one, else HUMAN/NOT_RUN, None without a row."""
        found = [row["status"] for row in self.rows if row["id"] == check_id and row["control"] is None]
        for status in ("FAIL", "NOT_RUN", "PASS", "HUMAN"):
            if status in found:
                return status
        return None

    def has_row(self, check_id: str) -> bool:
        return self.status_of(check_id) is not None

    def finish(self) -> int:
        """Mark every expected id without a row NOT_RUN, close the record; the exit code (1 when anything is red)."""
        for check_id in self.expected:
            if not self.has_row(check_id):
                self.add(check_id, "NOT_RUN", "the audit ended without producing this check (a crash, a timeout, or a step that was never reached)")
        self.finished = time.strftime("%Y-%m-%dT%H:%M:%S")
        self._write()
        return 1 if any(row["status"] in RED for row in self.rows if row["control"] is None) else 0

    def counts(self) -> dict[str, int]:
        result = {status: 0 for status in STATUSES}
        for row in self.rows:
            if row["control"] is None:
                result[row["status"]] += 1
        return result

    def _write(self) -> None:
        self._atomic(self.path, {"host": HOST, "app": self.app, "family": FAMILY, "host_version": self.host_version, "started": self.started, "finished": self.finished, "expected": len(self.expected), "parts": self.parts,
                                 "rows": self.rows})

    @staticmethod
    def _atomic(path: Path, data: dict[str, Any]) -> None:
        temporary = path.with_name(path.name + ".tmp")
        temporary.write_text(json.dumps(data, indent=1), encoding="utf-8")
        os.replace(temporary, path)
