# Fiji UI audit

A driver that presses every control of the LabConstrictor Fiji plugin, opens the form of every tool of an INSTALLED app, runs each tool with the inputs of the app's own
`lc_tests/cases.json` and takes the screenshots. Contract: LabConstrictor-Tools `docs/TEST_STRATEGY.md` section 3 and `tests/release_sandbox/README.md`. Linux, Xvfb.

    LC_HOME=<registry with the app> LC_APP=<name> LC_APP_TESTS=<lc_tests folder> LC_SHOTS=<out> LC_FIJI_HOME=<Fiji folder, the one with jars/> \
    LC_EXPECT_FIJI=listed|not_listed python tests/ui_audit.py

| Variable | Meaning |
|---|---|
| `LC_APPS=a,b` | several apps in one registry: each must be listed with all its tools (`ui_fiji_multi_lists_<app>`), switching shows the right tools (`ui_fiji_multi_switch_<a>_<b>`), then each is audited (`LC_APP_TESTS=a=dir,b=dir`) |
| `LC_UI_TOOL_TIMEOUT` | seconds one Fiji start gets before the watchdog kills it (default 300) |
| `LC_UI_TOOLS`, `LC_UI_PARTS` | only these tools / only `panel` or `tools` (the rest is reported as `HUMAN`) |
| `LC_UI_ORDERED` | apps whose tools are a sequence (default `GuessTheCondition`): tools run in the order of their cases, earlier cases are replayed first, whatever status they expect |
| `LC_FIJI_PLUGIN_DIR`, `LC_FIJI_MODE=jar` + `LC_FIJI_JAR`, `LC_HOST_VERSION` | test a RELEASED plugin: the unzipped release script folder, or the release jar (put into `plugins/` and removed afterwards); `LC_HOST_VERSION` (tag or SHA) is then required and is written to the matrix as `host_version`. Default: this repository's script, `repo@<sha>` |
| `LC_UI_INJECT` | a seeded failure (`extra_control`, `remove_control`, `offscreen_ok`, `blank_screens`) for the negative controls |

## What it does
Every job is its own Fiji process in its own process group under a watchdog (a modal dialog blocks the event thread): the choosers, each tool's form, each tool's run, each
copy-command entry. `tests/ui_audit_body.groovy` (the harness, selected through `LC_FIJI_HARNESS`) acts and reports; every verdict is made in Python
(`ui_audit_app.py`, `ui_audit_forms.py`, `ui_audit_runs.py`, `ui_audit_placement.py`) from the schema, the case, the files on disk, the clipboard text and the TIFF metadata of the fixtures,
never from the state of the dialog under test. Nothing stops at the first failure; what a killed start did not reach is `FAIL` (watchdog) or `NOT_RUN`.

Output: one `CHECK <id> PASS|FAIL <detail>` line per assertion, `NOTE <id> not run: <reason>` for what a machine cannot do, and `<LC_SHOTS>/matrix_ui_fiji_<app>.json`
(rewritten atomically after every row; schema of `tests/oneoff_2026-10/matrix.py` in LabConstrictor-Tools plus `host_version` and `parts`) with `<LC_SHOTS>/expected_fiji_<app>.json`
written first: every expected id without a row ends as `NOT_RUN`. Exit code 1 when anything is `FAIL` or `NOT_RUN`.

| Id | Passes when |
|---|---|
| `ui_fiji_controls_all_known` | every interactive component of every dialog read is in the control table (`ui_audit_controls.json`), and at least one form was read |
| `ui_fiji_panel_chooser_app/tool/ok/cancel`, `form_ok`, `form_cancel`, `menu`, `copy_empty/terminal/python`, `log` | the control did what the table says (Fiji has no Rescan, Restart worker, Details, Keep the worker running; finding one is a failure) |
| `ui_fiji_form_<tool>_opens` | one widget per declared parameter, right kind, nothing else but OK and Cancel; the description is shown; the dialog is inside the screen and its buttons are on it; the open windows are exactly the expected ones |
| `ui_fiji_form_<tool>_controls` | every control was activated with real events (Robot clicks for buttons, check boxes, radio buttons) and read back |
| `ui_fiji_run_<tool>` | the first case of the tool that expects COMPLETE ran through the form; completed; every declared result shown and not blank; the case's values; the values sent equal the case; results sit on the right image with the fixture's scale; one run record; the log exists |
| `ui_fiji_screens_<tool>` | the form and after-run screenshots exist, are fresh, plausibly sized, not one colour (evidence for a person: `<LC_SHOTS>/fiji/<app>/<tool>_form.png`, `_after_run.png`, `_result_<n>.png`) |

Cases that expect FAILED or cancel, and `stress_*` tools, are never run through the form. File inputs become Fiji inputs the way a person gives them: an image or labels file is
opened as an ImagePlus (Fiji reads the calibration from the file) and chosen in the form; a table, file or folder path is typed into its field.

## Negative controls
`python tests/ui_audit_negative.py` seeds a removed control, an extra control, an off-screen OK, blank screenshots, a failing tool, a wrong value, blank results, a hung tool and a wrongly
listed or hidden app, and fails if any of them does not turn the audit red. `python tests/ui_audit_logic_check.py` checks the Python judges on fake reports (no Fiji);
`tests/ui_audit_tree_check.groovy` checks the Swing side on a fake dialog. `sh tests/lint_groovy.sh` lints the harness.

## Notes on what Fiji does
* A tool with exactly one file input gets SciJava's native file prompt, not a form: `opens` passes as one parameter / one prompt, and the run puts the case's file into it.
* Case inputs the form cannot express (Guess the Condition's guess by number or in another case: the combo box lists the canonical names only) are replayed for their effect on an ordered app through
  `labconstrictor-tools run` and listed as `HUMAN` rows `ui_fiji_prerequisite_*`.
* Jar mode removes `scripts/Plugins/LabConstrictor` from the Fiji it uses and the jar afterwards: give it a Fiji of its own (script mode shares one Fiji safely).

## Known effects of the environment
* Xvfb has no window manager: after a native file dialog closes the form is not raised and gets no mouse events; the audit raises it (`toFront`) as a person's click would.
* SciJava's check boxes are wider than the panel they sit in; real clicks go to the visible part.
* Two audits may share one Fiji: the script is installed idempotently and atomically.
