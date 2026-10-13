"""What the retention cases (tests/cases/ret_*.json) need besides the Fiji run: folders that exist before it, and a description of the folders after it.

`prefill` builds LC_HOME/results and LC_HOME/runs as a person's machine could have them (owned and finished, owned and unfinished, from an older version, a
person's own); every folder holds a sentinel file, so that a survivor is judged by its contents, not by its count (SPEC_WRONG_SCIENCE 3.7).
`inspect` reads what is there afterwards with the rules of 3.8 written out here (not through Tools' own reader); the cases that need the real `prune` let the
host start it (it is the real Tools CLI), so that a marker written wrongly cannot pass by being parsed by the code that wrote it.
"""

import json
import re
from pathlib import Path

SENTINEL = "keep.txt"
TOKEN = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
CREATED = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$")
NAME = re.compile(r"^[A-Za-z0-9_.-]+$")
KINDS = {"results": "result", "runs": "run"}
STATUSES = ("ok", "failed", "cancelled")
RESULT_NAME = re.compile(r"^\d{8}T\d{6}_[0-9a-f]{6}_[A-Za-z0-9_.-]+$")
RUN_NAME = re.compile(r"^\d{8}T\d{6}\d*_[A-Za-z0-9_.-]+$")


def _owned(folder: Path, kind: str, index: int, done: bool, status: str = "ok") -> None:
    folder.mkdir(parents=True)
    (folder / SENTINEL).write_text("keep")
    created = "2020-01-01T00:%02d:%02d.000Z" % (index // 60 % 60, index % 60)  # increasing with the index, far in the past
    marker = {"lc_marker": 1, "kind": kind, "name": folder.name, "host": "cli", "pid": 1, "created": created, "token": "tok-%08d" % index}
    (folder / ".lc-owner.json").write_text(json.dumps(marker))
    if done:
        (folder / ".lc-done.json").write_text(json.dumps({"finished": created, "status": status}))


def prefill(spec: dict, home: Path) -> None:
    """`spec`: owned_results / owned_runs (finished, owned: counts), unfinished_results / unfinished_runs (owned, no done file), legacy_results / legacy_runs
    (valid names, no marker), foreign (true: a person's own folder in each root, a name that does not look like ours)."""
    for root, kind in KINDS.items():
        for n in range(spec.get("owned_" + root, 0)):
            _owned(home / root / ("20200101T%06d_aaaaaa_old_tool" % n if root == "results" else "20200101T000000%03d_old_tool" % n), kind, n, True)
        for n in range(spec.get("unfinished_" + root, 0)):
            _owned(home / root / ("20200102T%06d_bbbbbb_old_tool" % n if root == "results" else "20200102T000000%03d_old_tool" % n), kind, 1000 + n, False)
        for n in range(spec.get("legacy_" + root, 0)):
            folder = home / root / ("20190101T%06d_cccccc_old_tool" % n if root == "results" else "20190101T000000%03d_old_tool" % n)
            folder.mkdir(parents=True)
            (folder / SENTINEL).write_text("keep")
        if spec.get("foreign"):
            folder = home / root / ("20261008T120000_reanalysis_backup" if root == "results" else "0000_keep_this_analysis")
            folder.mkdir(parents=True)
            (folder / "x.txt").write_text("keep")


def _read(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def judge(folder: Path, root: str) -> dict:
    """What one folder is, by the rules of 3.8 written out: a valid owner marker, a valid done marker, the pieces of the name."""
    owner, done = _read(folder / ".lc-owner.json"), _read(folder / ".lc-done.json")
    problems = []
    if owner is None:
        problems.append("no owner marker")
    else:
        if owner.get("lc_marker") != 1 or isinstance(owner.get("lc_marker"), bool):
            problems.append("lc_marker is not the integer 1")
        if owner.get("kind") != KINDS[root]:
            problems.append("kind %r" % owner.get("kind"))
        if owner.get("name") != folder.name:
            problems.append("name %r is not the folder name" % owner.get("name"))
        if not isinstance(owner.get("token"), str) or not TOKEN.match(owner["token"]):
            problems.append("token %r" % owner.get("token"))
        if not isinstance(owner.get("created"), str) or not CREATED.match(owner["created"]):
            problems.append("created %r" % owner.get("created"))
        if not isinstance(owner.get("pid"), int):
            problems.append("pid %r" % owner.get("pid"))
        if not NAME.match(folder.name):
            problems.append("the folder name has other characters")
    done_ok = done is not None and done.get("status") in STATUSES and isinstance(done.get("finished"), str)
    record = _read(folder / "run.json") or {}
    return {
        "run_json": {k: record.get(k) for k in ("run_id", "app", "tool", "started_utc", "finished_utc", "status", "error", "host", "reported_paths", "log_file")},
        "has_inputs": "inputs" in record, "stderr_tail": record.get("stderr_tail"),
        "name": folder.name, "owner_valid": owner is not None and not problems, "problems": problems, "host": (owner or {}).get("host"),
        "done": bool(done_ok), "status": (done or {}).get("status"), "files": sorted(p.name for p in folder.iterdir() if p.is_file()),
        "name_ok": bool((RESULT_NAME if root == "results" else RUN_NAME).match(folder.name)),
        "sentinel": (folder / SENTINEL).read_text() if (folder / SENTINEL).is_file() else None,
        "leftover_temporary": any(".tmp-" in p.name for p in folder.iterdir()),
    }


def inspect(home: Path) -> dict:
    """The folders of LC_HOME after the run: for each root every folder judged; `fiji` are those the host wrote, `others` the ones from `prefill`."""
    found = {}
    for root in KINDS:
        base = home / root
        folders = [judge(p, root) for p in sorted(base.iterdir()) if p.is_dir()] if base.is_dir() else []
        found[root] = {
            "all": folders,
            "fiji": [f for f in folders if f["host"] == "fiji"],
            "sentinels_intact": sum(1 for f in folders if f["sentinel"] == "keep"),
            "count": len(folders),
            "foreign_intact": all(
                (base / name / "x.txt").is_file() and (base / name / "x.txt").read_text() == "keep"
                for name in ("20261008T120000_reanalysis_backup", "0000_keep_this_analysis")
                if (base / name).is_dir()
            ),
        }
    return found


def prune_calls(shots: Path) -> list:
    """What the prune recorder saw (tests/prune_recorder.py): one entry per start, with the done markers present at that moment."""
    return _read(shots / "prune_calls.json") or []
