# LabConstrictor for Fiji

**Run registered Python analysis tools from Fiji.**

LabConstrictor adds a Fiji command that discovers tools from installed LabConstrictor applications. Choose an application, choose a tool and fill in the form. Fiji sends the images and parameters to the application's own Python environment, then brings the results back into Fiji.

You do not have to install each application's Python dependencies into Fiji or write a Fiji plugin for every analysis.

This bridge is **in testing**. It has been exercised on Linux; native Windows and macOS testing is still needed.

## Run a tool

1. Open your image in Fiji, or have a TIFF file ready.
2. Choose **Plugins > LabConstrictor > LabConstrictor Tools...**.
3. Select an installed application and one of its tools.
4. Pick the image or file, set the parameters and run.
5. Inspect the returned images, tables, ROIs, alignment overlay or messages in Fiji.

The form is built from the tool's Python declaration. A tool can ask for a number, a choice, a file, an image or a region. It can also report progress, and you can press **Esc** to cancel a running tool.

### Example: image relocalisation

The repository's real-application tests include NucleiSky's **Relocalize 2D** tool. Give it a reference image and a query image, along with the calibration and other parameters the tool requests. The returned alignment can be inspected in Fiji.

This is an example of how an *installed* scientific application becomes available through the bridge; NucleiSky is not bundled with this repository.

### Images, channels and selections

- **Open image or file:** an image parameter accepts a Fiji window or a file path. When both are supplied, the file takes precedence.
- **Channels:** tools declaring `PickChannel()` offer a numbered channel choice. The selected channel, at the current Z and T, is sent to the tool.
- **Selection as region:** tools declaring `RegionOf(...)` can use the current ROI or selected ROIs in the ROI Manager. The selection is passed as a labelled region image; it is not a general-purpose crop option for every tool.
- **Calibration:** pixel size is taken from the selected image or, when possible, the TIFF metadata. Check the value before running measurements that depend on physical units.

## What comes back to Fiji

| Tool result | Fiji presentation |
|---|---|
| Image or label image | A new image window |
| Points | Multi-point ROI and ROI Manager entry, with properties in a table |
| Outlines | Image overlay; up to 1,000 are also placed in the ROI Manager |
| Table | Results window |
| Alignment | Overlay window |
| File, values or message | File path or text in the Log; messages may also appear in a dialog |

A tool can mark an output for replacement so that repeated runs update the previous result instead of accumulating windows. There are limits to how many outlines can be displayed; the bridge reports them rather than pretending all objects were added.

## Install

First install and register the LabConstrictor application you want to use. The application's installer normally handles registration. The toolkit provides commands to inspect it:

```bash
labconstrictor-tools list
labconstrictor-tools doctor
```

If you are installing the toolkit manually:

```bash
python -m pip install https://github.com/CellMigrationLab/LabConstrictor-Tools/archive/refs/heads/main.zip
```

Build the Fiji jar from this repository:

```bash
mvn package
```

Copy `target/labconstrictor-fiji-0.1.0.jar` into `Fiji.app/plugins/` and restart Fiji. The alternative is to place `src/main/resources/org/cellmigrationlab/labconstrictor/LabConstrictor_Tools.groovy` in `Fiji.app/scripts/Plugins/LabConstrictor/`.

There is **no Fiji update site yet**. The jar is a small Java command that launches the bundled Groovy bridge; the scientific analysis stays in the registered application's Python environment.

## Reuse a run outside Fiji

After a run, the Log includes a command-line version and a Python snippet. Choose **Plugins > LabConstrictor > Copy last run as command** to copy the terminal command.

An unsaved Fiji image cannot be represented as a reusable file path; the generated command will say so and include a placeholder. Save the input first if you want to reproduce the run outside Fiji.

### Macros

You can write a macro call manually, for example:

```javascript
run("LabConstrictor Tools...", "app=NucleiSky tool=[Relocalize 2D] reference=ref.tif query=crop.tif reference_pixel_size_um=0.65 query_pixel_size_um=0.325 segmentation=threshold");
```

The **Macro Recorder does not record this plugin**. Headless execution and persistent workers across macro calls are not implemented. Use the Toolkit's command-line interface for batch runs that do not require Fiji.

## Current limitations

Fiji uses SciJava dialogs, so some presentation hints work differently from Napari:

- Advanced and grouped parameters appear under headings, not collapsible sections.
- Fields cannot be dynamically disabled using `enabled_when`; the tool must still validate its inputs.
- Dynamic choices can depend on values remembered from a previous run. If the choices are not available, Fiji shows a text field and reports a source-tool failure in the Log.
- Some results require an open image to be placed on it; otherwise they are reported as tables or messages.

For errors, inspect Fiji's Log and the shared log at `~/.labconstrictor/logs/labconstrictor.log`. The Toolkit also provides `labconstrictor-tools support-bundle`.

For more detail, see [Fiji workflow and results](docs/USING_FIJI.md).

## Applications you can try

These are separate scientific applications, not tools bundled with this bridge. Install an application and its LabConstrictor tool registration before expecting it to appear in Fiji, Napari or QuPath.

- [LabConstrictor Playground](https://github.com/CellMigrationLab/LabConstrictor-Playground) — synthetic images, segmentation outputs, installation checks and host integration tests. [Installers](https://github.com/CellMigrationLab/LabConstrictor-Playground/releases).
- [NucleiSky](https://github.com/CellMigrationLab/NucleiSky) — registration of microscopy images using nuclei positions. [Desktop installation](https://github.com/CellMigrationLab/NucleiSky/blob/main/.tools/docs/download_executable.md). Its repository documents Fiji integration; verify the installed version exposes the required tools.
- [VLab4Mic desktop application](https://github.com/CellMigrationLab/LabConstrictor-VLab4Mic) — fluorescence microscopy simulations and image comparison. [Installation guide](https://github.com/CellMigrationLab/LabConstrictor-VLab4Mic/blob/main/.tools/docs/download_executable.md). Its repository documents Napari and Fiji bridge workflows.
- [CellTracksColab desktop application](https://github.com/CellMigrationLab/CellTracksColab_LabConstrictor) — cell-track analysis. [Desktop installation](https://github.com/CellMigrationLab/CellTracksColab_LabConstrictor/blob/main/.tools/docs/download_executable.md). Check the installed application's declared tools before assuming a particular host workflow is available.
- [Guess the Condition](https://github.com/CellMigrationLab/GuessTheCondition) — blinded classification of microscopy images to test whether experimental conditions can be distinguished across biological repeats. [Desktop installers](https://github.com/CellMigrationLab/GuessTheCondition/releases) and [Colab notebook](https://colab.research.google.com/github/CellMigrationLab/GuessTheCondition/blob/main/notebooks/GuessTheCondition/GuessTheCondition.ipynb). Its repository documents five bridge tools for Napari and Fiji.

**Compatibility is tool- and host-specific.** An application having a desktop installer does not by itself establish that every analysis function is exposed through the bridge. Use `labconstrictor-tools list` to inspect the installed tools.

## For developers and testers

The main bridge lives in `src/main/resources/org/cellmigrationlab/labconstrictor/LabConstrictor_Tools.groovy`; `LabConstrictorCommand.java` provides the Fiji menu entry. The Java side uses Fiji's Appose integration to communicate with the restricted LabConstrictor worker.

The real-dialog test harness is in `tests/run_cases.py`. On a Linux machine with Fiji installed and a virtual display:

```bash
export LC_FIJI_HOME=/path/to/Fiji.app
python tests/run_cases.py
LC_FIJI_MODE=jar python tests/run_cases.py
```

Real-application tests require their applications and fixtures to be installed separately. See the [human testing protocol](https://github.com/CellMigrationLab/LabConstrictor-Tools/blob/main/docs/HUMAN_TEST_PROTOCOL.md) for Windows and macOS checks.

Related projects: [Toolkit](https://github.com/CellMigrationLab/LabConstrictor-Tools) · [Napari](https://github.com/CellMigrationLab/napari-labconstrictor) · [QuPath](https://github.com/CellMigrationLab/LabConstrictor-QuPath) · [Playground](https://github.com/CellMigrationLab/LabConstrictor-Playground)

License: MIT.
