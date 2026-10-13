"""Expectation kinds of tests/run_cases.py `check` that read the report and the log in ways the older kinds do not (kept here so that run_cases.py stays small)."""


KINDS = {"same_after_dash", "log_count"}  # the expectation kinds `check` below reads (run_cases.py adds them to the kinds it knows)


def _walk(report, dotted):
    found = report
    for part in dotted.split("."):
        if isinstance(found, dict) and part in found:
            found = found[part]
        elif isinstance(found, list) and part.lstrip("-").isdigit() and -len(found) <= int(part) < len(found):
            found = found[int(part)]
        else:
            return None
    return found


def check(expect: dict, report: dict) -> list:
    """`same_after_dash`: [[path, path], ...] each pair holds texts like "A-1234" and "B-1234" whose part after the last dash is the same and not empty (two answers that came from one
    worker process: the answer names the pid of the process that gave it). `log_count`: {text: n} the log holds the text exactly n times."""
    failed = []
    for first, second in expect.get("same_after_dash", []):
        a, b = _walk(report, first), _walk(report, second)
        if not (isinstance(a, str) and isinstance(b, str) and a.rsplit("-", 1)[-1] and a.rsplit("-", 1)[-1] == b.rsplit("-", 1)[-1] and "-" in a and "-" in b):
            failed.append("%s = %r and %s = %r do not end in the same pid" % (first, a, second, b))
    for text, count in expect.get("log_count", {}).items():
        found = report.get("log_text", "").count(text)
        if found != count:
            failed.append("the log holds %r %d times, expected %d" % (text, found, count))
    return failed
