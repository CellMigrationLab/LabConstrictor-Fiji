"""A stand-in for the compat module of an older Tools (only what the host's check asks)."""
import re


def parse_version(text):
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)(?:(a|b|rc)(\d+))?$", text or "")
    if not match:
        return None
    major, minor, patch, pre, number = match.groups()
    return (int(major), int(minor), int(patch), {"a": 0, "b": 1, "rc": 2, None: 3}[pre], int(number or 0))
