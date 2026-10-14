"""The environment of the Fiji UI audit (docs/UI_AUDIT.md). A missing or wrong variable is a usage error (exit 2), never a guessed default."""

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Optional

PARTS = ("panel", "tools")
INJECTIONS = ("extra_control", "remove_control", "offscreen_ok", "blank_screens")


@dataclass
class Settings:
    home: str                      # LC_HOME: the registry that holds the installed app(s)
    fiji: Path                     # LC_FIJI_HOME
    apps: list[str]                # LC_APP, or LC_APPS (the apps to audit, in this order)
    multi: bool                    # LC_APPS was given: the chooser must list every app named and switching must show the right tools
    tests: dict[str, Path]         # LC_APP_TESTS: each app's lc_tests folder
    shots: Path                    # LC_SHOTS
    listed: bool                   # LC_EXPECT_FIJI (listed | not_listed); the sandbox's LC_EXPECT_LISTED is read too
    timeout: float                 # LC_UI_TOOL_TIMEOUT: seconds a single Fiji start gets before the watchdog kills it
    tools: Optional[list[str]]     # LC_UI_TOOLS: only these tool ids (default: every tool of the schema)
    parts: list[str]               # LC_UI_PARTS: panel, tools (default: both)
    inject: Optional[str]          # LC_UI_INJECT: a seeded failure (negative controls)
    jar: bool                      # LC_FIJI_MODE=jar: the built jar's menu command instead of the loose script


def usage(message: str) -> SystemExit:
    return SystemExit("ui_audit.py: " + message)


def read_settings(env: Mapping[str, str] = os.environ) -> Settings:
    missing = [name for name in ("LC_HOME", "LC_FIJI_HOME", "LC_APP_TESTS") if not env.get(name)]
    if not env.get("LC_APP") and not env.get("LC_APPS") and not env.get("LC_EXPECT_APP"):
        missing.append("LC_APP (or LC_APPS)")
    expect = env.get("LC_EXPECT_FIJI") or env.get("LC_EXPECT_LISTED")
    if not expect:
        missing.append("LC_EXPECT_FIJI (listed | not_listed)")
    if missing:
        raise usage("set " + ", ".join(missing))
    if expect not in ("listed", "not_listed"):
        raise usage("LC_EXPECT_FIJI must be listed or not_listed, not %r" % expect)
    multi = bool(env.get("LC_APPS"))
    apps = [a.strip() for a in (env["LC_APPS"] if multi else env.get("LC_APP") or env["LC_EXPECT_APP"]).split(",") if a.strip()]
    tests: dict[str, Path] = {}
    if "=" in env["LC_APP_TESTS"]:
        tests = {pair.split("=", 1)[0].strip(): Path(pair.split("=", 1)[1].strip()) for pair in env["LC_APP_TESTS"].split(",")}
    elif len(apps) == 1:
        tests = {apps[0]: Path(env["LC_APP_TESTS"])}
    else:
        raise usage("with several apps LC_APP_TESTS is a list name=folder,name=folder")
    absent = [app for app in apps if app not in tests]
    if absent:
        raise usage("LC_APP_TESTS names no folder for %s" % absent)
    parts = [p.strip() for p in env.get("LC_UI_PARTS", ",".join(PARTS)).split(",") if p.strip()]
    if not parts or any(p not in PARTS for p in parts):
        raise usage("LC_UI_PARTS is a list of %s, not %r" % (PARTS, env.get("LC_UI_PARTS")))
    inject = env.get("LC_UI_INJECT") or None
    if inject is not None and inject not in INJECTIONS:
        raise usage("LC_UI_INJECT must be one of %s, not %r" % (INJECTIONS, inject))
    tools = [t.strip() for t in env["LC_UI_TOOLS"].split(",") if t.strip()] if env.get("LC_UI_TOOLS") else None
    mode = env.get("LC_FIJI_MODE", "script")
    if mode not in ("script", "jar"):
        raise usage("LC_FIJI_MODE must be script or jar, not %r" % mode)
    fiji = Path(env["LC_FIJI_HOME"])
    if not (fiji / "jars").is_dir():
        raise usage("LC_FIJI_HOME=%s is not a Fiji installation (no jars/ folder)" % fiji)
    return Settings(env["LC_HOME"], fiji, apps, multi, tests, Path(env.get("LC_SHOTS", "screens")), expect == "listed", float(env.get("LC_UI_TOOL_TIMEOUT", "300")), tools, parts, inject,
                    mode == "jar")
