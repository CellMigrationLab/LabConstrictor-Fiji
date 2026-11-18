# Fiji plugin reference

What the LabConstrictor Fiji plugin does, rule by rule, how it works inside, how to run a tool from a macro, and how to build and test it. To install and use the plugin, read the [Fiji guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/fiji/) (its source is [user-guide.md](user-guide.md)).

## How it works

The menu entry **Plugins > LabConstrictor > LabConstrictor Tools...** is a small Java command (`LabConstrictorCommand.java`). It starts the Groovy script `LabConstrictor_Tools.groovy`, which is bundled in the jar and interpreted at run time; the jar is a packaging shell, not a port. Helpers in `lib/` are loaded on first use (`LCCompat` for the version checks, `LCHosts` and `LCHostRequirements` for which apps are listed, `LCNumbers` for number parsing, `LCLookups` for dropdowns filled by another tool, `LCWorkers` for worker processes, `LCRetention` and `LCRunRecord` for run folders).

The script:

1. reads the registry of installed apps (`<LC_HOME>/apps`, then the folders in `LC_APPS_PATH`, then the system folder) and checks that each entry can be trusted (owner and permissions);
2. builds a SciJava `ModuleInfo` from the tool's schema and lets Fiji's own input harvester show the dialog;
3. exports images as TIFF, or passes file paths;
4. runs the tool in the app's own Python through [Appose](https://github.com/apposed/appose)'s Java client, using the `lc:<tool>` task names, so Fiji never imports the app's packages;
5. shows each result by its type.

`<LC_HOME>` is `$LC_HOME`, or `~/.labconstrictor` when it is not set. The tool schema and its markers are defined in [LabConstrictor-Tools](https://github.com/CellMigrationLab/LabConstrictor-Tools).

## Install for developers

The install steps for users, the current version and the troubleshooting list are in the [Fiji guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/fiji/). What developers also need to know:

- Fiji must ship Appose (current Fiji builds do; the plugin is tested with Appose 0.12.0).
- An app needs **labconstrictor-tools 0.3.0a1 or newer** with the features `reserved_inputs_v1` and `app_hosts_v1`. See [Compatibility with an app](#compatibility-with-an-app).
- `labconstrictor-tools list` and `labconstrictor-tools doctor` show which apps are registered and why one is skipped.
- An app is listed in Fiji when its `labconstrictor-app.yaml` names `fiji` under `hosts:`. An app installed without that declaration (an older installer) is listed in every host, and the Log says `'<app>' declares no hosts, so it is listed in every host; ...`.
- Each release has `labconstrictor-fiji-<tag>.jar`, `labconstrictor-fiji-script-<tag>.zip` and `SHA256SUMS` (the checksums of the other two). The zip holds the script and the `lib` folder it needs; the script alone stops with a message saying where it looked for its helpers. Fiji also lists the files of `lib` in its menu, which is harmless. Running a tool from a macro needs the jar.
- There is no Fiji update site yet.

## The dialog

### Fields

- Each parameter becomes a SciJava dialog field: a text field for numbers (see [Numbers](#numbers)), a dropdown for choices, a checkbox, or a file or folder field.
- An optional value without a default (a seed, a time limit) gets a **Set &lt;name&gt;** checkbox. Unticked, the tool receives `None`.
- Choices that are numbers (`Literal[1, 2, 3]`) are shown as text; the tool receives the declared number.
- An input with a description has an ⓘ after its label. Hover over the label or the field to read the description. SciJava shows it only on hover, not on click or keyboard focus.
- Parameters of a `group` are shown together under a "— Group —" heading, and `advanced` ones last under "— Advanced settings —". `group_collapsed` is shown as a normal heading, because SciJava dialogs cannot fold sections.
- `enabled_when` is not applied: SciJava dialogs cannot grey fields out, so every field stays editable and the tool must accept any value.
- A tool with a parameter type the plugin does not know is left out of the chooser, with `tool 'id': this tool cannot be shown: parameter 'x' has the unknown type 't'` in the Log. The other tools of the app stay available.
- The tool's description is shown at the top of its dialog.

### Images

- Each image input has a chooser of open images and an "(or file)" field. If both are given, the file wins. With no image open, the file field is the only input. A file is read by the worker: TIFF always, other formats if the app has `imageio`.
- An image file given as input is also opened in its own window (titled with the file name), and its results are drawn there.
- **Channel** (`PickChannel()`): a "channel" number next to the image (1 = first). The tool receives only that channel, at the current Z and T, from an open image or a file. A channel the image does not have is refused.
- **Selection** (`RegionOf`): a "Use the selection as ..." box next to the region input. It sends the ROIs selected in the ROI Manager (else the image's own ROI) as a label image the size of the image; several ROIs become labels 1, 2, 3... Nothing selected, a selection outside the image, a file chosen for the image, or ROIs on different planes of a stack (`selection_mixed_planes`) are refused.

### Dropdowns filled by another tool (`choices_from`)

Fiji builds the dialog before anything is typed. So it asks the source tool with the values the app used in its previous run (kept in `<LC_HOME>/state/<app>.json`), and shows a plain text field until there are such values.

- All questions of one dialog are asked one at a time, in form order, by one worker process, which is closed after the last answer.
- A question that has not answered within 60 s is cancelled and its worker killed; the field stays a text field and the Log says so. **Esc** cancels the question in flight.
- Editing a value the lookup depends on does not ask again: the dropdown is filled before the dialog opens.

### Choosers, Run again and Back

- The app and tool choosers are skipped when they have one entry. They start on the app and tool picked last (`<LC_HOME>/state/last_tool.json`), or on the first entry. Apps are sorted; two tools with the same label are listed as `label (id)`.
- **Run again.** After a run started from the menu, the same tool's form comes back until Cancel is pressed. Each run has its own run record and Log lines. The form starts from the previous values, except:
  - an image's "(or file)" field starts empty, so the image follows the open window again;
  - a `clear_after_run` parameter goes back to its default after a run that succeeded (it keeps its value after a failed or cancelled run, so it can be corrected);
  - a dropdown keeps its previous entry only if it still lists it;
  - a pixel size linked to an image keeps a value you typed, and otherwise follows the image again.
- The form is modal, like every SciJava dialog. To zoom or open another image, press Cancel and start the tool again.
- **< Back** sits beside OK and Cancel and goes one window back: from the form to the tool chooser, from the tool chooser to the app chooser. A skipped chooser is skipped by Back too, and a window with nothing before it has no Back. Going back and picking the same tool again restores what the form held. The button is added to SciJava's button row (`OptionPane.buttonArea`); if a look and feel has no such row, the Log says so and there is no Back.

## Numbers

A number is checked when OK is pressed, or when a macro is read. **A value beyond a limit is refused, never clamped, rounded or shortened**: no run starts and the message names the field and the limit.

| Field | Limit when the tool declares no `Min` / `Max` |
|---|---|
| whole number | ±9007199254740991 (2^53 − 1, the largest integer every JSON number holds exactly) |
| float | ±1e15 |

A declared bound beyond these is narrowed to them. The number fields are text fields, so the value sent is exactly what was typed: the integer, or the double nearest to the text. Blanks around the text are ignored in the dialog.

Messages:

- `'L' must be <= 9007199254740991, got 9007199254740992` (and `>=` for the other side; with a declared bound: `'M' must be <= 10, got 11`);
- `'L' is required: type a number` (empty required field), `'L' is empty: type a number` (an optional field ticked "Set" and left empty);
- `'L' must be an integer, got '2.5'`, `'L' must be a number, got 'abc'`;
- `'L' must be a finite number, got 'x'` (for `NaN`, `Infinity`, `1e999`);
- `'L' is too close to zero to be held exactly, got '1e-400'`.

Two kinds of field still use a toolkit number widget and can cut or revert a typed number to their own range: a `Widget("slider")` with both bounds, and a pixel size field linked to an image by `PixelSizeOf`.

## Pixel size

A field linked to an image with `PixelSizeOf` follows that image's calibration. Only explicit metadata counts: a length unit and a number.

- Units are read through the unit catalog of the app's Tools (`python -m labconstrictor_tools.conformance --path`). If the app's Tools has none, the copy built into the plugin is used and the Log says so.
- Symbols (`um`, `µm`, `μm`, `nm`, `mm`, `cm`, `m`, `Å`) are exact and case-sensitive. Words (`micron`, `micrometre`, `millimeter`, `angstroms`) match in any case after trimming. ImageJ's `micron` is 1 µm.
- The calibration is unknown when the unit is `pixel`, empty or ImageJ's default; when the unit is not a length (`unit 's' is not a length`); when the micro sign is damaged (`unit text '�m' is damaged; enter the pixel size`); or when X and Y differ by more than 0.1 % (`pixels are not square`). Within 0.1 % the mean is used.
- For a TIFF file, ImageJ's `unit=` line or the OME physical size wins over the resolution tags. The tags count only with the unit INCH or CENTIMETER, and not when they look like a default (72 or 96 dpi, 28.35 or 37.80 per cm).
- A file chosen for the image wins over the open image. The calibration is read again when OK is pressed.
- **Unknown is a value.** The field is cleared (it shows 0), the Log says `not calibrated: 'image' has no pixel size`, the declared default is never used instead, and a run that needs the field is refused: `'pixel_size' needs a value: 'image' has no pixel size. Enter it in um.` An optional pixel size is then sent as nothing.
- **A value you type is yours** and survives a change of image, file or "use" box. Typing 0 makes the field follow the image again.
- SciJava reports an edit only when the value changes. Typing the value the field already shows is therefore not seen as typed: on an uncalibrated image the field is cleared and the run is refused, so a wrong value is never sent.
- The request carries the reserved input `_calibration` (per axis, in µm, with its source and the raw scale and unit), which tools never see. It is also stored in the run record.

## Checks before a run

These refusals use the worker's messages and codes:

- `[missing_parameter] 'L' is required: ...`
- `[file_not_found] image file not found: P`
- `[folder_not_found] 'L': folder not found: P`
- `[wrong_dimensions] 'L' must be a 2D image (YX) but got 3D with shape (3, 32, 32)`: a tool declaring `Axes("YX")` refuses a Z-stack, time series, multi-channel or RGB image. Fiji never picks the plane on screen for you; declare `PickChannel()`, or duplicate the plane first.

Also:

- An open image of more than 4 GB (all planes, channels and samples) is refused, never cropped: `image 'T' is 5.0 GB; exporting more than 4 GB is not supported`. Image files are not checked here.
- A relative file, table or folder path is resolved against Fiji's working folder and sent to the worker as an absolute path.

## Results

| Output | Shown as |
|---|---|
| image, labels | an image window `<app>:<result name>` |
| table | a Results window named after the output (`name [1]`, `name [2]`... when that name is taken) |
| value | `name=value` in the Log (`(not a finite number)` for NaN or infinity) |
| message | the Log and, when a person ran the tool, a dialog (markdown emphasis removed) |
| file | its path in the Log |
| points | a multi-point ROI on their image, kept in the ROI Manager as `<app>:<name>`, with their properties in a table |
| outlines (`ShapesOut`) | an overlay on their image, holes kept; the first 1000 are also in the ROI Manager |
| alignment | an overlay window |

- `Replace()` on an output closes the previous window of that result (image, table, overlay, ROI Manager entries) before showing the new one.
- Points and outlines go on the `apply_to` image, else on the first image input of the tool. It is always that image, never the current image.
- Up to 50 000 outlines are drawn; beyond that the Log says `'name': showing the first 50000 of T outlines`.
- If there is no image to draw on (the tool has no image input, or the file cannot be opened), points become a table and outlines a file, and the Log says where the file is.
- A tool that fails with the code `no_match` or `no_result` gets a plain message window, not the error dialog.
- A singular alignment matrix is applied and the Log warns: `the alignment matrix is singular (determinant d): ...`. A matrix with values that are not finite is refused.

### Coordinates

- A tool works on the pixels of the image Fiji wrote for it and never sees calibrated coordinates.
- A point or outline vertex `x` is drawn at the ImageJ coordinate `x + 0.5` (pixel `i` spans `[i, i+1)`). The image calibration is never applied to a ROI.
- `RegionOf` masks have exactly the size of the exported image.
- On a stack, a result without `t` or `z` lands on the plane that was exported (the window's plane when OK was pressed, or the picked channel), and the Log says so once. Points or outlines that name planes (a `t` or `z` column or property) are not placed on the planes of a stack yet: they are shown as a table or a file.
- An image or labels result with `ApplyTo` takes the pixel size, unit and origin of its source when it has the same width and height. Otherwise it stays uncalibrated at the pixel origin and the Log says `'name' is W x H but 'source' was exported as W2 x H2; shown at the pixel origin, not aligned`.

### Files kept for you

A result Fiji cannot show or place, or a file the tool wrote inside Fiji's temporary job folder, is kept in `<LC_HOME>/results/<YYYYMMDDTHHMMSS>_<6 hex>_<app>_<tool>/`. The Log says where. See [Run records and clean-up](#run-records-and-clean-up).

## Running, cancelling and failures

- A progress bar shows while a tool runs. The tool's progress messages also go to the Log (a changed message at most every 10 s), and after 60 s without one the Log says the tool is still running.
- **Esc** cancels. A tool that ignores the request is killed after 3 s. The status bar then says `cancelled`, or `cancelled (worker stopped)` if the worker had to be killed.
- A worker gets 10 s to exit after its input is closed, then it is killed.
- Every worker (run or lookup) is tracked in one place and closed once; a shutdown hook kills any still alive when Fiji quits. Before a kill, Fiji also stops the processes the worker started (a tool's `subprocess.Popen`), which would otherwise keep the run from ending. This needs Java 9 or later (Fiji ships Java 21); on Java 8 the Log warns.
- Fiji waits for the worker's outputs to arrive before showing a result. If they do not arrive within 10 s of the task ending, the run is reported as failed.
- The worker keeps Fiji's working folder, runs with `PYTHONSAFEPATH=1`, and does not inherit Fiji's Python or Qt settings: `PYTHONHOME`, `VIRTUAL_ENV`, `CONDA_PREFIX` and `QT_PLUGIN_PATH` are set to empty.
- A failure shows the tool's error, the worker's last output and the log location. A worker that dies gets one line with the likely cause (out of memory, a native crash, a missing package). It is called an import failure only when the worker's last words on stderr say `cannot import tool module`.
- All LabConstrictor front-ends write to `<LC_HOME>/logs/labconstrictor.log`. It is rotated at 1 MB with five backups (`.log.1` to `.log.5`). If a rotation fails (on Windows, when another program holds the file), the console says so once and the log keeps growing.

## Run records and clean-up

**Fiji never deletes anything under `<LC_HOME>/results` or `<LC_HOME>/runs`.**

- For every run that starts a worker, Fiji makes `runs/<millisecond time>_<app>_<tool>/` and writes the owner marker `.lc-owner.json` first. When the run ends it writes `run.json`, then, last and atomically, the done marker `.lc-done.json` with `ok`, `failed` or `cancelled`. A results folder gets the same two markers.
- The run status and the content of `run.json` come from the app's Tools (`labconstrictor_tools.runs`). With a Tools older than 0.3.0a1 the run is refused before anything starts, with a message that says so.
- After the run, Fiji starts `<app's Python> -m labconstrictor_tools prune` and does not wait for it. The prune keeps the newest 20 results and 50 run records, and only removes finished folders that carry the markers: your own folders, unmarked folders and unfinished runs are never touched.
- A Tools without the `retention_prune` feature gets no prune; the Log says so, and the folders stay until Tools is updated.
- If the run record cannot be written, the run goes on and the Log says `the run record could not be completed (...)`; the unfinished folder is never removed.

## Compatibility with an app

- When the registry is read, each app's schema is checked with the same rules as `labconstrictor_tools.compat.check_schema` (ported in `lib/LCCompat.groovy`). An app whose `tools_version` is missing or below 0.3.0a1, or whose `features` lack `reserved_inputs_v1` or `app_hosts_v1`, is left out. The Log says why, for example `this app's labconstrictor-tools 0.2.0rc1 is older than the 0.3.0a1 this host needs: update it`; the same text appears in a dialog when no app is usable. A version that cannot be read is only a warning.
- Before a run, Fiji asks the app's interpreter itself which Tools it has (see [Run records and clean-up](#run-records-and-clean-up)).
- **Host requirements** (`host_requirements_v1`, `lib/LCHostRequirements.groovy`): an app can declare the oldest Fiji plugin it needs. If this plugin is older, the app stays listed and the Log says which version to get; if the app marks the requirement `required: true`, the app is left out with that message.

The protocol behind these rules is in LabConstrictor-Tools [`docs/PROTOCOL.md`](https://github.com/CellMigrationLab/LabConstrictor-Tools/blob/main/docs/PROTOCOL.md).

## Running a tool from a macro

With the jar installed, a macro line runs a tool without a dialog:

    run("LabConstrictor Tools...", "app=NucleiSky tool=[Relocalize 2D] reference=ref.tif query=crop.tif reference_pixel_size_um=0.65 query_pixel_size_um=0.325 segmentation=threshold");

- `app` and `tool` are required. Both are matched ignoring case; a tool can be named by its label, `label (id)` or its id.
- Images are given by window title (`name=title`) or by file (`name_file=path`). A macro never guesses the current image. Tables, files and folders are given by path.
- `name_channel=2` picks a channel of a `PickChannel` image; `selection_name=true` uses the selection for a `RegionOf` input.
- An optional value that is not set is left out of the line.
- Numbers follow the same limits as the dialog, with a strict grammar: an optional sign, digits with an optional fraction (or only a fraction) and an optional exponent. No blanks, `1.5f`, `1_0` or hex floats.
- Number choices take the number (`count=2`); a value not in the list is refused with the list of options.
- A pixel size you give is yours; one you leave out follows the image or file the macro names.
- Unknown apps, tools and images are reported with the valid choices.
- A macro call runs once; the form does not come back.

The **Macro Recorder** records runs made through the dialog as such `run(...)` lines, one line per run, with unset optional values left out. The line always names the app (`app=`), also when only one app is installed and its chooser was skipped.

Not available: headless mode, keeping one worker alive across calls in a loop, and a separate menu entry per tool.

## Copy as command

After every run, the Log shows a terminal command and a Python snippet that repeat it outside Fiji. The file behind each open image is used; an image that was never saved gets a placeholder and a note. A selection or a picked channel cannot be repeated exactly: the command names `region.tif` for a selection and sends the whole file for a channel, each with a `# name: ...` note.

**Plugins > LabConstrictor > Copy last run as command** puts the terminal command of the last run on the clipboard. From a macro: `run("Copy last run as command")`, or `run("Copy last run as command", "kind=python")` for the Python snippet. Without a clipboard (headless, or locked by another program), the command is printed in the Log instead.

## When the plugin falls back

In these cases the plugin does something simpler instead of failing, and writes a line to the shared log.

| Situation | What happens |
|---|---|
| A `choices_from` source has nothing to answer with yet, fails or is missing | The parameter stays a text field; a failure is logged and the Log says `could not get the choices of ...` |
| Remembered values (`state/`) cannot be read or written | The dialog starts without them |
| The pixel size of a file cannot be read | The Log says `could not read the pixel size from ...; enter it by hand` |
| The app's Tools has no unit catalog | The catalog built into the plugin is used, and the Log says so |
| A file system without POSIX permissions | The permission part of the registry trust check is skipped for that entry (logged as a warning) |

## Build and test

`mvn package` builds `target/labconstrictor-fiji-<version>.jar` (Java 8 bytecode; needs Java and Maven; the version is the one in `pom.xml`).

The tests drive desktop Fiji on a virtual screen (Linux, `xvfb-run`) and answer the real SciJava dialogs through `tests/test_harness.groovy`, the only place with test hooks. Reports and screenshots go to `evidence/`.

    pip install git+https://github.com/CellMigrationLab/LabConstrictor-Tools numpy pandas tifffile scipy scikit-image
    export LC_FIJI_HOME=/path/to/Fiji.app
    python tests/run_cases.py                      # every case in tests/cases/, through the loose script
    python tests/run_cases.py <case> ...           # some cases
    LC_FIJI_MODE=jar python tests/run_cases.py     # the same cases through the built jar (runs mvn)
    python tests/run_cases.py --real-apps          # tests/cases_real_apps/: needs the apps registered in LC_HOME
                                                   # and LC_REAL_FIXTURES (see the docstring of run_cases.py)
    tests/lint_groovy.sh                           # npm-groovy-lint on the script (needs Node and Java)

A case is a JSON file in `tests/cases/`: the app and tool, images to preload, dialog values, optional cancel timing, and the expected outcome. The shared rules (coordinates, calibration, retention) are read from `labconstrictor_tools/conformance.json` of the Tools in the Python that runs the cases, and a coverage check fails when a shared fixture is neither run nor marked as not applicable to Fiji. A full run takes about an hour.

GitHub Actions run `mvn package` (`ci`), the lint (`lint`), and the cases through the script and through the jar as two parallel jobs (`fiji-gui`). The release workflow (`.github/workflows/release.yml`) builds the jar for a tag, runs GUI cases with it on Linux before attaching the files, and runs a shorter check (menu entry and dialog) on Windows and macOS arm64 that does not hold up the release. Running it by hand with a tag is a dry run that publishes nothing.

The UI audit (`tests/ui_audit.py`) is separate: it presses every control and runs every tool of an installed app. See [UI_AUDIT.md](UI_AUDIT.md).

License: MIT.
