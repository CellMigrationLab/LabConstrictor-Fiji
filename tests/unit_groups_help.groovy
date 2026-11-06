// Unit-check groups of the input help marker (Tools #147): an ⓘ after the label of each input with a description. SciJava shows the description on hover; its dialog
// allows no click or keyboard help, so the marker is all this host adds. NOT part of the product. Loaded by tests/unit_checks.groovy, which calls this closure with its
// toolbox. Returns the groups by name; a case selects them with "unit_checks": [...].

{ Map t ->
    def lc = t.lc, check = t.check
    def marker = " \u24D8"
    def tool = [label: "T", outputs: [], inputs: [
        [name: "x", label: "X", type: "float", required: true, default: 1.0, description: "How far to go"],
        [name: "n", label: "N", type: "integer", required: true, default: 2, minimum: 0, maximum: 9],
        [name: "mode", label: "Mode", type: "choice", required: true, choices: ["fast", "slow"], default: "fast", description: "   "],
        [name: "note", label: "Note", type: "string", required: true, default: "", description: "Shown in the log"],
        [name: "flag", label: "Flag", type: "boolean", required: true, default: false, description: "Also count the edges"],
        [name: "size", label: "Size", type: "float", required: true, default: 1.0, unit: "um", description: "Spot size"],
        [name: "seed", label: "Seed", type: "integer", required: false, nullable: true, description: "Random seed"],
        [name: "img", label: "Img", type: "image", required: true, description: "The picture"],
    ]]
    def form = { lc.buildToolDialog(tool, [], [:], null)[0] }
    def labelOf = { String name -> form().getInput(name)?.getLabel() }

    return [
        help_marker_after_the_label_of_each_described_input: {
            check("a described number", labelOf("x") == "X" + marker, labelOf("x"))
            check("a described text", labelOf("note") == "Note" + marker, labelOf("note"))
            check("a described check box", labelOf("flag") == "Flag" + marker, labelOf("flag"))
            check("after the unit", labelOf("size") == "Size (um)" + marker, labelOf("size"))
            check("an optional input: on its own row, not on its Set box", labelOf("seed") == "Seed" + marker && labelOf("set_seed") == "Set seed", labelOf("seed") + " / " + labelOf("set_seed"))
            check("the hover text is the description", form().getInput("x").getDescription().startsWith("How far to go") && form().getInput("flag").getDescription() == "Also count the edges",
                  form().getInput("x").getDescription())
        },
        help_marker_absent_without_a_description: {
            check("no description", labelOf("n") == "N", labelOf("n"))
            check("a description of spaces counts as none", labelOf("mode") == "Mode", labelOf("mode"))
            check("the helper", lc.helpLabel("A", null) == "A" && lc.helpLabel("A", "") == "A" && lc.helpLabel("A", " ") == "A" && lc.helpLabel("A", "b") == "A" + marker)
        },
    ]
}
