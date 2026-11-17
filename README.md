# LabConstrictor for Fiji

Run the analysis tools of your LabConstrictor apps from inside [Fiji](https://fiji.sc). One menu entry lists every installed app; you pick a tool, fill in a normal Fiji dialog and press **OK**. Results come back as image windows, outlines on your image, Results tables and lines in the Log window. The analysis runs in the app's own Python environment, so Fiji needs no Python setup.

![Fiji after running the StarDist2D app: nuclei outlined on the open image, a label image and a table of the objects](docs/img/fiji-stardist.png)

*The StarDist2D app's "Segment nuclei" tool, run from Fiji on an open image: outlines on the image, a label image and a table.*

It has been tested on Linux, Windows and macOS. Please [tell us](https://github.com/CellMigrationLab/LabConstrictor-Fiji/issues) what you find.

## Install

Download the jar from the [Releases page](https://github.com/CellMigrationLab/LabConstrictor-Fiji/releases) into `Fiji.app/plugins` and restart Fiji. The steps, how to open it and what to do when something goes wrong are in the [Fiji guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/fiji/).

## Apps that work in Fiji

| App | What it does |
|---|---|
| [StarDist2D](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/stardist2d/) | Segment nuclei and cells in 2D images with StarDist |
| [NucleiSky](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/nucleisky/) | Align microscopy images by matching the pattern of their nuclei |
| [Guess the Condition](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/guessthecondition/) | A blinded test of whether conditions can be told apart by eye |
| [VLab4Mic](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/labconstrictor-vlab4mic/) | Simulate fluorescence microscopy images of virtual samples |
| [Playground](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/labconstrictor-playground/) | Check your computer and try every feature of the plugin |

Any LabConstrictor app that declares Fiji as one of its hosts appears in the menu; no extra plugin is needed per app. The [App Centre](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/?host=fiji) has the current list.

## Getting help

- **Something does not work?** See [If something does not work](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/fiji/#if-something-does-not-work) in the Fiji guide.
- **Other guides:** the [LabConstrictor guides](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/).
- **Bugs and questions:** the [issue tracker](https://github.com/CellMigrationLab/LabConstrictor-Fiji/issues).

## More detail

- [Technical reference](docs/REFERENCE.md): every rule the plugin follows (channels, selections, pixel size, coordinates, number limits), running a tool from a macro, and how the release is built and checked.
- The same apps also run in [napari](https://github.com/CellMigrationLab/LabConstrictor-napari) and [QuPath](https://github.com/CellMigrationLab/LabConstrictor-QuPath). To make your own app, start at the [LabConstrictor website](https://cellmigrationlab.github.io/LabConstrictor-Site/).

## For developers

`mvn package` builds `target/labconstrictor-fiji-<version>.jar` (needs Java and Maven). The tests drive a real Fiji on a virtual screen (Linux):

```bash
pip install git+https://github.com/CellMigrationLab/LabConstrictor-Tools numpy pandas tifffile scipy scikit-image
export LC_FIJI_HOME=/path/to/Fiji.app
python tests/run_cases.py                    # the plugin script
LC_FIJI_MODE=jar python tests/run_cases.py   # the same cases through the built jar
tests/lint_groovy.sh                         # lint (needs Node and Java)
```

How the plugin works inside and what the tests cover: [technical reference](docs/REFERENCE.md#how-it-works).

License: [MIT](LICENSE).
