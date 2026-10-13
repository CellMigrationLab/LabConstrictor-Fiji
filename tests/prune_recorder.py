"""Stands in for `labconstrictor-tools prune` in the host tests (HRET-04): records that it was started, and what the folders under LC_HOME looked like at that moment
(was the done marker of every owned folder already there?), then ends with exit code 0 ("recorder") or 3 and a message on stderr ("failing").
Usage: prune_recorder.py recorder|failing OUT.json <the command the host meant to start>"""

import json
import os
import sys
from pathlib import Path


def snapshot(home: Path) -> dict:
    folders = {}
    for root in ("results", "runs"):
        base = home / root
        for folder in sorted(base.iterdir()) if base.is_dir() else []:
            folders["%s/%s" % (root, folder.name)] = {"owner": (folder / ".lc-owner.json").is_file(), "done": (folder / ".lc-done.json").is_file()}
    return folders


def main(argv: list) -> int:
    mode, out, command = argv[0], Path(argv[1]), argv[2:]
    calls = json.loads(out.read_text()) if out.exists() else []
    folders = snapshot(Path(os.environ["LC_HOME"]))
    calls.append({
        "mode": mode, "command": command, "lc_home": os.environ.get("LC_HOME"), "folders": folders,
        "owned": sum(1 for f in folders.values() if f["owner"]), "unfinished": sorted(k for k, f in folders.items() if f["owner"] and not f["done"]),
    })  # owned: folders with an owner marker; unfinished: owned ones that had no done marker at the moment the prune was started
    out.write_text(json.dumps(calls))
    if mode == "failing":
        print("prune: deliberate failure of the test command", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
