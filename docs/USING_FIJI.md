# Using LabConstrictor in Fiji

This page describes the Fiji-specific workflow. For Python tool declarations and the worker protocol, see [LabConstrictor Tools](https://github.com/CellMigrationLab/LabConstrictor-Tools).

## Scientific applications

For applications you can install, see [Playground](https://github.com/CellMigrationLab/LabConstrictor-Playground), [NucleiSky](https://github.com/CellMigrationLab/NucleiSky), [VLab4Mic desktop](https://github.com/CellMigrationLab/LabConstrictor-VLab4Mic) and [CellTracksColab desktop](https://github.com/CellMigrationLab/CellTracksColab_LabConstrictor) and [Guess the Condition](https://github.com/CellMigrationLab/GuessTheCondition). The corresponding README links to installation instructions and explains which host workflows have been documented. Only tools registered by the installed application appear in the bridge.

## Before you start

Install Fiji, the LabConstrictor Fiji bridge and at least one registered application. The bridge does not contain scientific analysis tools itself. Run `labconstrictor-tools list` to check which applications are registered.

## Run a tool

1. Open the image you want to analyse, or prepare an image file.
2. Open **Plugins > LabConstrictor > LabConstrictor Tools...**.
3. Select an application and tool.
4. Set its inputs and run.
5. Inspect the returned images, ROIs, tables or messages.

A tool determines which inputs are required. The dialog is generated from the application's tool declaration.

## Images and calibration

### Open image or file

An image parameter may use an open Fiji image or a file. When both are supplied, the file takes precedence. Save unsaved images before trying to reproduce the run with a command-line invocation.

### Channels and dimensions

A tool declaring `PickChannel()` can ask for a channel. The Fiji bridge sends the chosen channel at the current Z and T. Check the declared axes before supplying a stack or time series.

### Pixel size

Calibration comes from the image or supported file metadata. Verify pixel size before using outputs in physical units.

## ROIs and regions

A tool declaring `RegionOf("image")` can receive the current selection or selected ROIs as a labelled region. The region is a separate input; it does not automatically crop every image passed to every tool.

The selected image and ROI must refer to the same coordinate frame. If an application needs a cropped region, the tool must handle that region input.

## Results

| Return type | Fiji |
|---|---|
| Image / labels | Image window |
| Points | Point ROI and ROI Manager |
| Shapes | Overlay and, within limits, ROI Manager |
| Table | Results table |
| Affine alignment | Overlay |
| Values / message / file | Text, log or result dialog as appropriate |

The current bridge caps outline display and adds only the first 1,000 outlines to the ROI Manager. Check the application output if the result contains more objects.

## Reusing a run

Fiji can copy the last run as a command-line invocation. A command that references an unsaved image is not directly reproducible until the image is saved.

### Macros

Manually written macro calls are supported experimentally. The Fiji Macro Recorder does not record the LabConstrictor command. Do not assume headless execution or persistent workers between macro calls.

## Troubleshooting

Check the Fiji Log, then the shared LabConstrictor log under `~/.labconstrictor/logs/`. The Toolkit's `doctor` and `support-bundle` commands can help diagnose registration and worker failures.

## Maintainer checks

When the Fiji bridge changes, check image/file precedence, channel and ROI conversion, pixel calibration, each output type, cancellation, and macro behavior. Record which operating systems were tested. The automated Fiji harness lives under `tests/`.
