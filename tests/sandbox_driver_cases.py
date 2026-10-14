"""CI cases of the sandbox driver tests/sandbox_listing.py: it must PASS when Fiji does what the expectation says and FAIL (exit 1, one FAIL line) when it does not.

    python tests/sandbox_driver_cases.py        # needs LC_FIJI_HOME and, in the Python running this script, labconstrictor-tools with app_hosts_v1

Each case builds a private registry with `labconstrictor-tools register` (as an installer does), then runs the driver with the contract's environment variables.
"""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DRIVER = HERE / "sandbox_listing.py"
APP = "victim"
NOT_LISTED = "'victim' is not listed in fiji: the app declares hosts: napari"


def registry(hosts):
    """A private LC_HOME with the example app registered under the name `victim`; `hosts` None = no declaration, "" = no registry entry at all."""
    home = tempfile.mkdtemp(prefix="lchome_driver_")
    if hosts == "":
        return home
    command = [sys.executable, "-m", "labconstrictor_tools", "register", "--name", APP, "--prefix", sys.prefix,
               "--module", "labconstrictor_tools.examples.synthetic", "--version", "0.0"]
    subprocess.run(command + ([] if hosts is None else ["--hosts", hosts]), check=True, capture_output=True, env=dict(os.environ, LC_HOME=home))
    return home


def drive(hosts, listed, sentence):
    """(exit code, stdout lines) of the driver for a registry with the given declaration and the given expectation."""
    env = dict(os.environ, LC_HOME=registry(hosts), LC_EXPECT_APP=APP, LC_EXPECT_LISTED=listed, LC_EXPECT_SENTENCE=sentence)
    done = subprocess.run([sys.executable, str(DRIVER)], env=env, capture_output=True, text=True, check=False)
    return done.returncode, done.stdout.splitlines()


CASES = [  # name, declaration, expectation, sentence, wanted CHECK line start (None = must be PASS), wanted exit code
    ("declared fiji is listed", "notebook,fiji", "listed", "", "CHECK fiji_lists_the_app PASS", 0),
    ("no declaration is listed", None, "listed", "", "CHECK fiji_lists_the_app PASS", 0),
    ("a declaration without fiji is not listed and the sentence is in the log", "napari", "not_listed", NOT_LISTED, "CHECK fiji_does_not_list_the_app PASS", 0),
    ("expecting listed for an app that is not listed fails", "napari", "listed", "", "CHECK fiji_lists_the_app FAIL", 1),
    ("expecting not_listed for an app that is listed fails", "fiji", "not_listed", NOT_LISTED, "CHECK fiji_does_not_list_the_app FAIL", 1),
    ("a wrong skip sentence fails", "napari", "not_listed", "'victim' is not listed in fiji: something else", "CHECK fiji_does_not_list_the_app FAIL", 1),
    ("an empty skip sentence for a registered app fails", "napari", "not_listed", "", "CHECK fiji_does_not_list_the_app FAIL", 1),
    ("an app with no registry entry is not listed, with a NOTE", "", "not_listed", "", "CHECK fiji_does_not_list_the_app PASS", 0),
]


def main():
    failures = 0
    for name, hosts, listed, sentence, wanted_line, wanted_code in CASES:
        code, lines = drive(hosts, listed, sentence)
        checks = [line for line in lines if line.startswith("CHECK ")]
        problems = []
        if code != wanted_code:
            problems.append("exit code %d, wanted %d" % (code, wanted_code))
        if len(checks) != 1 or not checks[0].startswith(wanted_line):
            problems.append("CHECK lines %r, wanted one starting %r" % (checks, wanted_line))
        if hosts == "" and not any(line.startswith("NOTE fiji_no_registry_entry") for line in lines):
            problems.append("no NOTE fiji_no_registry_entry line")
        print("%-80s %s %s" % (name, "PASS" if not problems else "FAIL", "; ".join(problems)))
        failures += bool(problems)
    print("%d/%d driver cases passed" % (len(CASES) - failures, len(CASES)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
