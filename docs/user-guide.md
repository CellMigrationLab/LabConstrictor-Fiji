# Install the Fiji plugin

One Fiji command runs the tools of every installed LabConstrictor app, with a dialog generated from each tool's declaration.

> [!WARNING]
> **Under heavy construction.** This plugin is untested and unlikely to be stable. Expect bugs, missing features and changes without notice. Do not rely on it for work you cannot redo.
>
> There is no ready-made file and no Fiji update site yet. Today the plugin is built from its repository. A ready-made jar and an update site are planned.

## What you need

- [Fiji](https://fiji.sc).
- At least one LabConstrictor app installed on your computer. The app's installer registers it, which is how the plugin finds it.
- To build the jar: Java and Maven.

## Install

1. Get the [LabConstrictor-Fiji](https://github.com/CellMigrationLab/LabConstrictor-Fiji) repository and build the jar:

        mvn package

2. Copy `target/labconstrictor-fiji-0.1.0.jar` into the `plugins` folder of your Fiji installation, and restart Fiji.

Without building: copy the script `src/main/resources/org/cellmigrationlab/labconstrictor/LabConstrictor_Tools.groovy` into `Fiji.app/scripts/Plugins/LabConstrictor/` instead. Both routes give the same menu entry; macro replay needs the jar.

## Open it

Choose **Plugins > LabConstrictor > LabConstrictor Tools...**. Pick an app and a tool, fill in the dialog, and press OK.

- Each image input has a chooser for an open image and an "(or file)" field. A file wins.
- Results open as image windows, a Results table, ROIs or overlays on the image, or lines in the Log window.
- Press Esc to cancel. A progress bar shows while a tool runs.
- After every run, the Log window shows the terminal line and the Python snippet that repeat it outside Fiji.

## If something does not work

- A failure dialog shows the tool's error, the worker's last output and where the log is: `~/.labconstrictor/logs/labconstrictor.log`.
- `labconstrictor-tools doctor` shows which apps are registered and why one might be skipped.

Source, macro replay and issues: [LabConstrictor-Fiji](https://github.com/CellMigrationLab/LabConstrictor-Fiji).
