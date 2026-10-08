#!/bin/sh
# Lints LabConstrictor_Tools.groovy with npm-groovy-lint (CodeNarc) using .groovylintrc.json; fails on any warning or error.
# Needs node/npx and a Java runtime. The "#@ ModuleService ..." line is a SciJava script parameter that the Groovy parser
# cannot read, so it is commented out in a temporary copy (line numbers stay the same).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
sed 's|^#@|//#@|' "$root/src/main/resources/org/cellmigrationlab/labconstrictor/LabConstrictor_Tools.groovy" > "$work/LabConstrictor_Tools.groovy"
cd "$work"
npx --yes npm-groovy-lint@17.0.5 --noserver --config "$root/.groovylintrc.json" --loglevel warning --failon warning LabConstrictor_Tools.groovy
