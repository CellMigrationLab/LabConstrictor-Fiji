"""Release-sandbox driver of Fiji: does Fiji LIST the installed app, or NOT list it, as the matrix says?

Contract (LabConstrictor-Tools tests/release_sandbox/README.md, "Driver contract for the host repositories"). Environment:
    LC_HOME              the registry that holds the installed app (an app with no entry there is "not registered")
    LC_EXPECT_APP        the registry name of the app
    LC_EXPECT_LISTED     listed | not_listed
    LC_EXPECT_SENTENCE   the exact sentence of labconstrictor_tools.app_hosts.listed_for (non-empty for a registered app that is not listed)
    LC_FIJI_HOME         the Fiji installation (needs xvfb-run on Linux)
Prints exactly one line, `CHECK fiji_lists_the_app PASS|FAIL <detail>` (listed) or `CHECK fiji_does_not_list_the_app PASS|FAIL <detail>` (not_listed), after any
`NOTE` lines, and exits 0 on PASS, 1 on FAIL. Fiji runs the discovery of the plugin script itself (tests/sandbox_listing.groovy), never a copy of the rule.
"""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
GROOVY = HERE.parent / "src" / "main" / "resources" / "org" / "cellmigrationlab" / "labconstrictor" / "LabConstrictor_Tools.groovy"
TIMEOUT_S = int(os.environ.get("FIJI_TIMEOUT", "300"))
LOG_NAME = "labconstrictor.log"


def settings():
    """The four variables of the contract; a missing or wrong one is a usage error (exit 2), never a guessed default."""
    wanted = {name: os.environ.get(name) for name in ("LC_HOME", "LC_EXPECT_APP", "LC_EXPECT_LISTED", "LC_FIJI_HOME")}
    missing = [name for name, value in wanted.items() if not value]
    if missing or "LC_EXPECT_SENTENCE" not in os.environ:
        raise SystemExit("sandbox_listing.py: set %s" % ", ".join(missing + ([] if "LC_EXPECT_SENTENCE" in os.environ else ["LC_EXPECT_SENTENCE (may be empty)"])))
    if wanted["LC_EXPECT_LISTED"] not in ("listed", "not_listed"):
        raise SystemExit("sandbox_listing.py: LC_EXPECT_LISTED must be listed or not_listed, not %r" % wanted["LC_EXPECT_LISTED"])
    return wanted["LC_HOME"], wanted["LC_EXPECT_APP"], wanted["LC_EXPECT_LISTED"] == "listed", os.environ["LC_EXPECT_SENTENCE"], Path(wanted["LC_FIJI_HOME"])


def discover(home, fiji):
    """What Fiji lists in the registry `home`: ({app: [tool ids]}, [problems]); or a sentence saying why it could not be asked."""
    report = Path(tempfile.mkdtemp(prefix="lclisting_")) / "report.json"
    env = dict(os.environ, LC_HOME=home, LC_LISTING_REPORT=str(report), LC_FIJI_SCRIPT=str(GROOVY), LC_FIJI_LIB=str(GROOVY.parent / "lib"))
    command = ["xvfb-run", "-a", "-s", "-screen 0 1600x1000x24", str(fiji / "fiji-linux-x64"), "--allow-multiple", "--run", str(HERE / "sandbox_listing.groovy")]
    try:
        subprocess.run(command, env=env, capture_output=True, timeout=TIMEOUT_S, check=False)
    except (OSError, subprocess.TimeoutExpired) as problem:
        return "could not run Fiji: %s" % problem
    if not report.exists():
        return "Fiji wrote no listing report"
    found = json.loads(report.read_text(encoding="utf-8"))
    return "the discovery failed: %s" % found["error"] if "error" in found else (found["apps"], found["problems"])


def schema_tool_ids(home, app):
    """The tool ids of the app's schema, read from its registry entry (None when there is no entry)."""
    entry = Path(home) / "apps" / ("%s.json" % app)
    if not entry.is_file():
        return None
    schema = json.loads(Path(json.loads(entry.read_text(encoding="utf-8"))["schema_path"]).read_text(encoding="utf-8"))
    return [tool["id"] for tool in schema["tools"]]


def verdict(home, app, expect_listed, sentence, found):
    """(passed, detail) for what Fiji listed; `found` is the pair of discover()."""
    apps, _problems = found
    if expect_listed:
        offered = apps.get(app)
        wanted = schema_tool_ids(home, app)
        if offered is None:
            return False, "'%s' is not offered (Fiji lists: %s)" % (app, ", ".join(sorted(apps)) or "nothing")
        if wanted is None or sorted(offered) != sorted(wanted):
            return False, "'%s' is offered with %s, its schema has %s" % (app, offered, wanted)
        return True, "'%s' is offered with %d tool(s)" % (app, len(offered))
    if app in apps:
        return False, "'%s' is offered but must not be" % app
    log = Path(home) / "logs" / LOG_NAME
    if schema_tool_ids(home, app) is None:
        print("NOTE fiji_no_registry_entry: there is no registry entry for '%s', so there is no skip sentence to look for" % app)
        return True, "'%s' is not offered (it is not registered)" % app
    if not sentence:
        return False, "LC_EXPECT_SENTENCE is empty, but '%s' is registered and not listed: the sentence is required" % app
    if not log.is_file() or sentence not in log.read_text(encoding="utf-8", errors="replace"):
        return False, "'%s' is not offered, but the Tools log lacks the sentence: %s" % (app, sentence)
    return True, "'%s' is not offered and the Tools log says why" % app


def main():
    home, app, expect_listed, sentence, fiji = settings()
    name = "fiji_lists_the_app" if expect_listed else "fiji_does_not_list_the_app"
    found = discover(home, fiji)
    passed, detail = (False, found) if isinstance(found, str) else verdict(home, app, expect_listed, sentence, found)
    print("CHECK %s %s %s" % (name, "PASS" if passed else "FAIL", detail))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
