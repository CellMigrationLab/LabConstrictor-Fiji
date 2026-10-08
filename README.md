# LabConstrictor-Fiji

A Fiji command that runs **any installed LabConstrictor app's tools** from **Plugins > LabConstrictor > LabConstrictor Tools...**.
The dialog for each tool is generated from the tool's declared schema (see
[LabConstrictor-Tools](https://github.com/CellMigrationLab/LabConstrictor-Tools)); the tool runs in the app's own Python
environment through [Appose](https://github.com/apposed/appose), so Fiji never imports the app's packages.

* Parameters become native SciJava dialog fields: numbers with ranges and units, choices, checkboxes, file and folder fields. A value that is optional and has no default (a seed, a time limit) has a **"Set <name>"** checkbox: unticked, the tool receives `None`.
* **Group headings and advanced settings**: parameters of a `group` are shown together under a "— Group —" heading and `advanced` ones last under "— Advanced settings —". `enabled_when` is not applied (SciJava dialogs cannot grey fields out dynamically): all parameters stay editable and the tool must accept them either way. The Napari widget implements all three.
* **Interaction hints**: `choices_from` (a string parameter whose options another tool provides) becomes a dropdown. Fiji builds the dialog before anything is typed, so it asks the source tool with the values the app used in its previous run (kept in `<LC_HOME>/state/<app>.json`) and shows a plain text field until there are some. `replace` on an output closes the previous window of that result (images, tables, and the alignment overlay) before showing the new one. `clear_after_run` needs nothing (every run opens a fresh dialog) and `group_collapsed` is shown as a normal heading: SciJava dialogs cannot fold sections.
* **Message and points outputs**: a `message` result goes to the Log and, when a person runs the tool, into a dialog (markdown emphasis removed). `points` become a multi-point ROI on the image they belong to (the `apply_to` image, else the first image input of the run; a tool with no image input at all: the image its run showed last, else the current one), are kept in the ROI Manager as `<app>:<name>` (replaced by the next run when the output declares `Replace()`), and their properties appear in a results table.
* **Channel selector**: an image input declared with `PickChannel()` gets a "channel" number next to it (1 = first); the tool receives only that channel (at the current Z and T), from an open image or from a file. A channel the image does not have is refused with a clear message. In a macro: `image_channel=2`.
* **Run on the selection** (`RegionOf`): a "Use the selection as ..." box next to the region input sends the ROIs selected in the ROI Manager (else the ROI of the image) as a label image the size of the image (several are labels 1, 2, 3...). Nothing selected, a selection outside the image, or a file chosen for the image are refused with a clear message. In a macro: `selection_region=true`.
* **Outlines** (`ShapesOut`, GeoJSON): drawn as an overlay on the image the outlines belong to, with holes kept (composite ROIs); the first 1000 are also listed in the ROI Manager (it becomes very slow with more); up to 50 000 are shown. Replace() swaps the previous outlines of the same output.
* **Copy as command**: after every run the Log window carries the terminal line and the Python snippet that repeat it outside Fiji (the file behind each open image is used; an image that was never saved gets a placeholder and a note). **Plugins > LabConstrictor > Copy last run as command** puts the terminal line of the last run on the clipboard (macro: `run("Copy last run as command")`, or `run("Copy last run as command", "kind=python")` for the Python snippet).
* **2D tools** (an image declared with `Axes("YX")`): a Z-stack, time series, multi-channel or RGB image is refused with the worker's own sentence, `[wrong_dimensions] 'L' must be a 2D image (YX) but got 3D with shape (3, 32, 32)`; Fiji never picks the plane on screen for you (declare `PickChannel()` to choose a channel, or duplicate the plane you want first).
* **Choices that are numbers** (`Literal[1, 2, 3]`): the dropdown shows the numbers as text and the tool receives the declared number (a macro `count=2` as well; `count=7` is refused with the list of options). Before, the text `"1"` went out and the worker refused it.
* **Paths**: a file, table or folder given as a relative path is resolved against the folder Fiji runs in (the folder the existence check uses) and sent to the worker as an absolute path; the same text could be another file, or none, in the worker's own folder.
* **Images from an open window *or* a file**: each image parameter has the open-image chooser and an "(or file)" field (a file
  wins). With no image open, the file field is the only input. TIFF always; other formats if the app has `imageio`.
* Pixel size follows the chosen image (units converted to micrometres), or the TIFF header for files (the ImageJ unit text, else the TIFF resolution unit: inch or centimetre), until you type a value in the field: a value you typed is never overwritten when you pick another image. Pixels that are not square are warned about in the Log (`pixel size differs: Y .. µm, X .. µm - the tool takes one value and gets X`): the tool receives X.
* Typed results: images/labels open as windows (`<app>:<result name>`), alignments as an overlay window, values in the Log window, tables as a Results window named after the output, files by path in the Log.
* **"No match" is a message, not an error**: a tool that fails with the code `no_match` / `no_result` opens a plain message window instead of the red error dialog.
* Progress bar, Esc to cancel (tools that ignore cancel are killed after 3 s), no worker left behind. A run stopped with Esc says `cancelled` in the status bar, or `cancelled (worker stopped)` when the tool ignored Cancel and its worker had to be killed (never `crashed`: the person asked for the stop).
* Failures show the tool's error, the worker's last output and the log location; unexpected script errors are logged with their
  stack trace. All front-ends share `~/.labconstrictor/logs/labconstrictor.log`.

## Install
1. On the machine, the LabConstrictor apps must be installed and registered (the installer does it; check with
   `labconstrictor-tools list` / `labconstrictor-tools doctor`). Tools repo: `pip install git+https://github.com/CellMigrationLab/LabConstrictor-Tools`.
2. Build and copy the jar (Java 8+ bytecode, runs on any Fiji):

        mvn package
        cp target/labconstrictor-fiji-0.1.0.jar <Fiji.app>/plugins/      # restart Fiji

   (Alternatively copy `src/main/resources/org/cellmigrationlab/labconstrictor/LabConstrictor_Tools.groovy` to
   `<Fiji.app>/scripts/Plugins/LabConstrictor/` to run the script without the jar.)

Fiji update-site publication is not set up yet.

## How it works
`LabConstrictorCommand.java` (the menu entry, reports start-up failures) starts `LabConstrictor_Tools.groovy`, which reads the registry
(`~/.labconstrictor/apps`, `LC_APPS_PATH`, system folder; entries are trust-checked), builds a SciJava `ModuleInfo` from the schema, lets
Fiji's own input harvester show the dialog, exports images as TIFF (or passes file paths), and drives the worker with Appose's Java
client using the restricted `lc:<tool>` protocol. The Groovy script is interpreted at run time; the jar is a packaging shell, not a port.

## Macro replay (prototype; the Macro Recorder does not record this plugin yet)
A macro line typed by hand, e.g.

    run("LabConstrictor Tools...", "app=NucleiSky tool=[Relocalize 2D] reference=ref.tif query=crop.tif reference_pixel_size_um=0.65 query_pixel_size_um=0.325 segmentation=threshold");

runs the tool without any dialog. Images are given by window title (`name=title`) or by file
(`name_file=path`); tables, files and folders by path; an optional value that was not set is simply absent from the line (replay leaves it unset); unknown apps/tools/images are reported with the valid choices. Not yet: headless mode,
keeping the worker alive between several calls in a loop, a menu entry per tool. Needs the jar (the menu command receives the options).

## Fallbacks (intended)
These are deliberate: the host does something simpler instead of failing, writes one line to the shared log
(`<LC_HOME>/logs/labconstrictor.log`) when it happens, and never does it silently.

| Where | What happens | What the person sees |
|---|---|---|
| ChoicesFrom parameter whose source answers nothing yet (a depended-on value is empty, or no earlier run is remembered) | The parameter stays a plain text field | A text field instead of a drop-down |
| ChoicesFrom source tool fails or is missing | The parameter stays a text field; the failure is logged with its stack trace | A text field, and a line in the Log window ("could not get the choices of ...") |
| Remembered values (`state/`) cannot be read or written | The dialog starts without them | Empty fields where a previous value would have been offered |
| Points result and no image is open | The points are shown as a table | The table and a line in the Log window saying to open an image to see them on it |
| Pixel size of a file cannot be read from its header | The field is left for you to fill in | A line in the Log window ("could not read the pixel size from ...; enter it by hand") |
| Points or outlines whose image was given as a file (no window to put them on) | Points are shown as a table, outlines only logged; they are never put on another image | A line in the Log window saying the image is a file, not an open window |
| No clipboard (headless, or locked) for "copy last run" | The command is printed instead of copied | The command in the Log window with the reason |
| File system without POSIX permissions | The permission checks of the trust check cannot apply to that entry | Nothing (logged as a warning) |

## Tests
`tests/run_cases.py` drives desktop Fiji on a virtual screen (Linux: `xvfb-run`), answering the real SciJava dialogs with
`tests/test_harness.groovy` (the only place with test hooks) and writing JSON reports and screenshots to `evidence/`.

    pip install git+https://github.com/CellMigrationLab/LabConstrictor-Tools numpy pandas tifffile
    export LC_FIJI_HOME=/path/to/Fiji.app
    python tests/run_cases.py                      # 21 portable cases (example app and small test apps), loose script
    LC_FIJI_MODE=jar python tests/run_cases.py     # the same 21 through the built jar (needs mvn); about 5 minutes each
    python tests/run_cases.py --real-apps          # cases for NucleiSky, CellTracksColab, VLab4Mic: need them installed/registered
                                                   # (LC_HOME) and LC_REAL_FIXTURES=<folder with nucleisky/ celltracks/ vlab4mic/ data>
                                                   # (4 cases; the blur and VLab4Mic cases fail or time out when those apps are not installed)

Lint: `tests/lint_groovy.sh` runs `npm-groovy-lint` (pinned) on the script with `.groovylintrc.json` (needs node and Java); CI runs it as the `lint` job.

Cases are JSON (`tests/cases/*.json`): the app and tool, images to preload, dialog overrides, optional cancel timing, expectations.

Status: **testing phase**. Tested on Linux only (Fiji with Java 21, Xvfb); to help on Windows or macOS (and on a real desktop) follow the
[human test protocol](https://github.com/CellMigrationLab/LabConstrictor-Tools/blob/main/docs/HUMAN_TEST_PROTOCOL.md). Windows and macOS are untested; SciJava Command generation
(a menu command per tool, headless use) is not implemented. License: MIT.
