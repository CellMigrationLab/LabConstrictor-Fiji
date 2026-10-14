# Install the Fiji plugin

One Fiji (ImageJ) command runs the tools of every installed LabConstrictor app, with a dialog generated from each tool's declaration.

> [!WARNING]
> **Under heavy construction.** This plugin is untested by people on real machines and unlikely to be stable. Expect bugs, missing features and changes without notice. Do not rely on it for work you cannot redo.
>
> The plugin is installed from a GitHub release (a jar, or a script with its helper files); there is no Fiji update site yet. The first release had not been created when this was written (14 Oct 2026): until it exists, the files below cannot be downloaded.

## What you need

- [Fiji](https://fiji.sc).
- At least one LabConstrictor app installed on your computer. The app's installer registers it, which is how the plugin finds it. The app needs **labconstrictor-tools 0.3.0a1 or newer** (tag `v0.3.0-alpha.1`): an app with an older one is left out of the list, with a sentence in the Log window.
- Apps declare which hosts list them (`hosts:` in their `labconstrictor-app.yaml`); the rule is a binding rule in Tools 0.3.0a1 (feature `app_hosts_v1`; PROTOCOL.md section App hosts); Napari, Fiji and QuPath all honour it (this host since PR #47). Apps without a declaration, i.e. installed with an installer from before template 0.1.18, are listed in every host and the Log says so ("declares no hosts"); installers pass the hosts from template 0.1.18 (template PR #33, not merged yet). Intended for Fiji: Playground, NucleiSky, Guess the Condition and VLab4Mic. CellTracksColab is not listed in any host.
- Nothing to build: no Java or Maven is needed to install.

## Install

The full install page for the apps and all three hosts is [Tools `docs/INSTALL.md`](https://github.com/CellMigrationLab/LabConstrictor-Tools/blob/main/docs/INSTALL.md). For Fiji:

1. Download `labconstrictor-fiji-<tag>.jar` from the [Releases page](https://github.com/CellMigrationLab/LabConstrictor-Fiji/releases) (`<tag>` is the release tag; take the latest release).
2. Put it into `Fiji.app/plugins/` (not a subfolder; remove an older `labconstrictor-fiji-*.jar`) and restart Fiji.
3. Run **Plugins > LabConstrictor > LabConstrictor Tools...**.

No-jar alternative: the same release has `labconstrictor-fiji-script-<tag>.zip`. It holds the script `LabConstrictor_Tools.groovy` **and the `lib` folder it needs** (the script alone stops with a message). Unzip it so both sit in `Fiji.app/scripts/Plugins/LabConstrictor/`; the menu entry is then **Plugins > LabConstrictor > LabConstrictor Tools** (no dots). **Macro calls need the jar.** Install one of the two, not both (with both, the menu lists the tool twice).

`SHA256SUMS` in the release lists the checksums of the jar and the zip. Every release jar is installed in Fiji and run on GUI cases before it is attached. The release gate is Linux; Windows and macOS (Apple silicon) get a best-effort check in the release run that can fail without blocking a release, and nobody has used the plugin on those systems by hand yet.

Testing the plugin and reporting back: the [human test protocol](https://github.com/CellMigrationLab/LabConstrictor-Tools/blob/main/docs/HUMAN_TEST_PROTOCOL.md) and its [record sheet](https://github.com/CellMigrationLab/LabConstrictor-Tools/blob/main/docs/HUMAN_TEST_PROTOCOL.md#0-record-sheet-the-test-set-fill-in-before-you-start) (same PR).

## Open it

Choose **Plugins > LabConstrictor > LabConstrictor Tools...** (without the dots if you installed the script). Pick an app and a tool, fill in the dialog, and press OK.

A first run needs an installed LabConstrictor app that declares tools. If none is registered, Fiji shows the message "No LabConstrictor apps are registered" and stops.

- Each image input has a chooser for an open image and an "(or file)" field. A file wins.
- Results open as image windows, a Results table, ROIs or overlays on the image, or lines in the Log window.
- Press Esc to cancel. A progress bar shows while a tool runs.
- After every run, the Log window shows the terminal line and the Python snippet that repeat it outside Fiji.

## If something does not work

- A failure dialog shows the tool's error, the worker's last output and where the log is: `~/.labconstrictor/logs/labconstrictor.log`.
- `labconstrictor-tools doctor` shows which apps are registered and why one might be skipped.
- No menu entry: the jar is not directly in `Fiji.app/plugins/`, or Fiji was not restarted.
- Pixel-size field: there is no "use the image's calibration" button; the field follows the image by itself until you type a value. Typing the value the box already shows cannot be detected in a Fiji dialog, so the field is cleared and the run is blocked with a message; a wrong value is never sent.

Source, macro replay (needs the jar) and issues: [LabConstrictor-Fiji](https://github.com/CellMigrationLab/LabConstrictor-Fiji).
