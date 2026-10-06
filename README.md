# LabConstrictor-Fiji

A Fiji command that runs **any installed LabConstrictor app's tools** from **Plugins > LabConstrictor > LabConstrictor Tools...**.
The dialog for each tool is generated from the tool's declared schema (see
[LabConstrictor-Tools](https://github.com/CellMigrationLab/LabConstrictor-Tools)); the tool runs in the app's own Python
environment through [Appose](https://github.com/apposed/appose), so Fiji never imports the app's packages.

* Parameters become native SciJava dialog fields: numbers with ranges and units, choices, checkboxes, optional parameters.
* **Images from an open window *or* a file**: each image parameter has the open-image chooser and an "(or file)" field (a file
  wins). With no image open, the file field is the only input. TIFF always; other formats if the app has `imageio`.
* Pixel size follows the chosen image (units converted to micrometres), or the TIFF header for files.
* Typed results: images/labels open as windows, alignments as an overlay, values in the log, tables as files.
* Progress bar, Esc to cancel (tools that ignore cancel are killed after 3 s), no worker left behind.
* Failures show the tool's error, the worker's last output and the log location; unexpected script errors are logged with their
  stack trace. All front-ends share `~/.labconstrictor/logs/labconstrictor.log`.

## Install
1. On the machine, the LabConstrictor apps must be installed and registered (the installer does it; check with
   `labconstrictor-tools list` / `labconstrictor-tools doctor`). Tools repo: `pip install git+https://github.com/CellMigrationLab/LabConstrictor-Tools`.
2. Build and copy the jar (Java 8+ bytecode, runs on any Fiji):

        mvn package
        cp target/labconstrictor-fiji-0.1.0.jar <Fiji.app>/plugins/      # restart Fiji

   (Alternatively copy `src/main/resources/org/cellmigrationlab/labconstrictor/LabConstrictor.groovy` to
   `<Fiji.app>/scripts/Plugins/LabConstrictor/` to run the script without the jar.)

Fiji update-site publication is not set up yet.

## How it works
`LabConstrictorCommand.java` (the menu entry, reports start-up failures) starts `LabConstrictor.groovy`, which reads the registry
(`~/.labconstrictor/apps`, `LC_APPS_PATH`, system folder; entries are trust-checked), builds a SciJava `ModuleInfo` from the schema, lets
Fiji's own input harvester show the dialog, exports images as TIFF (or passes file paths), and drives the worker with Appose's Java
client using the restricted `lc:<tool>` protocol. The Groovy script is interpreted at run time; the jar is a packaging shell, not a port.

## Macro recording and replay (prototype)
With the Macro Recorder open, a run through the dialog records one line, e.g.

    run("LabConstrictor Tools...", "app=NucleiSky tool=[Relocalize 2D] reference=ref.tif query=crop.tif reference_pixel_size_um=0.65 query_pixel_size_um=0.325 segmentation=threshold");

Playing that line (or typing it) runs the tool without any dialog. Images are given by window title (`name=title`) or by file
(`name_file=path`); tables and files by path; unknown apps/tools/images are reported with the valid choices. Not yet: headless mode,
keeping the worker alive between several calls in a loop, a menu entry per tool. Needs the jar (the menu command receives the options).

## Tests
`tests/run_cases.py` drives desktop Fiji on a virtual screen (Linux: `xvfb-run`), answering the real SciJava dialogs with
`tests/test_harness.groovy` (the only place with test hooks) and writing JSON reports and screenshots to `evidence/`.

    pip install git+https://github.com/CellMigrationLab/LabConstrictor-Tools numpy pandas tifffile
    export LC_FIJI_HOME=/path/to/Fiji.app
    python tests/run_cases.py                      # 10 portable cases with the example app, loose script
    LC_FIJI_MODE=jar python tests/run_cases.py     # same through the built jar (needs mvn)
    python tests/run_cases.py --real-apps          # cases for NucleiSky, CellTracksColab, VLab4Mic: need them installed/registered
                                                   # (LC_HOME) and LC_REAL_FIXTURES=<folder with nucleisky/ celltracks/ vlab4mic/ data>

Cases are JSON (`tests/cases/*.json`): the app and tool, images to preload, dialog overrides, optional cancel timing, expectations.

Manifest hints: parameters of a `group` are shown together under a heading and `advanced` ones last under "Advanced settings"; `enabled_when` is not applied (SciJava dialogs cannot grey fields out dynamically), so all parameters stay editable and the tool must accept them either way. The Napari widget implements all three.

Status: **testing phase**. Tested on Linux only (Fiji with Java 21, Xvfb). Windows and macOS are untested; SciJava Command generation
(a menu command per tool, headless use) is not implemented. License: MIT.
