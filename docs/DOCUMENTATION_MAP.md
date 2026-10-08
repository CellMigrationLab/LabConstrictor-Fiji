# Documentation map

Using a registered tool on a Fiji image.

The [README](../README.md) is the entry point. Detailed material belongs in the sections below. This map records the intended structure; a named page may still need to be created or updated by the implementation maintainers.

| Section | Intended file | Scope |
|---|---|---|
| Overview and first run | `README.md` | What is installed, how to open the command, one reproducible example |
| Inputs | `docs/INPUTS.md` | Open images versus files, channel choice, ROIs, ROI Manager and calibration |
| Results | `docs/RESULTS.md` | Image windows, tables, points, shapes, alignments, replacement and display limits |
| Automation | `docs/AUTOMATION.md` | Macro invocation, replay restrictions, copied CLI commands |
| Install and troubleshoot | `docs/INSTALL.md` | Jar/script installation, dependencies, logs, supported versions |
| Testing | `docs/TESTING.md` | Test harness, real-app prerequisites, platform matrix and known gaps |

## Maintenance

Do not reproduce the full Toolkit protocol. Keep Fiji-specific behavior and screenshots here. Code owners should validate menu names, macro syntax and test commands.

When changing a tool or host behavior, update the relevant section in the same PR. Prefer one tested example to several unverified ones. Mark unsupported behavior explicitly; do not turn planned features into present-tense claims.
