# Notes for agents and contributors

## Documentation

Each topic of the LabConstrictor ecosystem has exactly one owner page; every other page links to it. The table of owners is "Sources of truth" in the [`AGENTS.md` of LabConstrictor-Site](https://github.com/CellMigrationLab/LabConstrictor-Site/blob/main/AGENTS.md#sources-of-truth).

- This repository owns the **Fiji guide**: [`docs/user-guide.md`](docs/user-guide.md), published on the website as the [Fiji guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/fiji/). It is the only page with the install steps, the menu path, the troubleshooting list and the current version number. Update the version there when a release is made.
- Every other page (`README.md`, `docs/REFERENCE.md`, `docs/UI_AUDIT.md`, release notes) links to the guide instead of repeating its steps or the version.
- The website downloads `docs/user-guide.md` at build time, so its links and pictures must be absolute (`https://...`).
- Writing rules (sentence case, link text that says where it goes, no "click here", plain words) are in the Site's [`DESIGN.md`](https://github.com/CellMigrationLab/LabConstrictor-Site/blob/main/DESIGN.md).
