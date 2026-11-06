// Unit-check groups of the form that comes back after a run (issue #58): which values of the run before the next form starts from, and the chooser entry of the tool
// picked last. NOT part of the product. Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox. Returns the groups by name; a case selects them
// with "unit_checks": [...].

{ Map t ->
    def lc = t.lc, check = t.check
    def tool = [label: "T", outputs: [], inputs: [
        [name: "x", label: "X", type: "float", required: true, default: 1.0],
        [name: "mode", label: "Mode", type: "choice", required: true, choices: ["fast", "slow"], default: "fast"],
        [name: "note", label: "Note", type: "string", required: true, default: ""],
        [name: "img", label: "Img", type: "image", required: true],
        [name: "flag", label: "Flag", type: "boolean", required: true, default: false],
        [name: "guess", label: "Guess", type: "string", required: false, nullable: true, clear_after_run: true],
    ]]
    def given = [x: "7", mode: "slow", note: "hi", img_file: new File("a.tif"), flag: true, set_guess: true, guess: "B"]
    def after = { String status, Map values = given -> [values: values, states: [:], status: status] }
    // the value field `name` starts on, in the form built after `previous` (null: the first form of a run)
    def startOf = { Map previous, String name -> lc.buildToolDialog(tool, [], [:], previous)[0].getInput(name)?.getDefaultValue() }

    def linked = [label: "P", outputs: [], inputs: [
        [name: "img", label: "Img", type: "image", required: true],
        [name: "ps", label: "Pixel size", type: "float", required: true, pixel_size_of: "img", unit: "um", default: 0.5],
    ]]
    def linkedForm = { Map state -> lc.buildToolDialog(linked, [], [:], [values: [ps: 0.3d], states: [ps: state], status: "COMPLETE"]) }

    return [
        again_the_next_form_starts_from_the_values_of_the_run_before: {
            check("the first form starts from the defaults", startOf(null, "x") != "7" && startOf(null, "mode") == "fast", startOf(null, "x") + " " + startOf(null, "mode"))
            check("a number keeps the text that was typed", startOf(after("COMPLETE"), "x") == "7", String.valueOf(startOf(after("COMPLETE"), "x")))
            check("a choice keeps the entry that was picked", startOf(after("COMPLETE"), "mode") == "slow", String.valueOf(startOf(after("COMPLETE"), "mode")))
            check("a text keeps what was typed", startOf(after("COMPLETE"), "note") == "hi", String.valueOf(startOf(after("COMPLETE"), "note")))
            check("a check box keeps its tick", startOf(after("COMPLETE"), "flag") == true)
            check("an image's '(or file)' field starts empty: the image follows the open windows again", startOf(after("COMPLETE"), "img_file") == null,
                  String.valueOf(startOf(after("COMPLETE"), "img_file")))
            check("a choice that is no longer offered is not put back", startOf(after("COMPLETE", given + [mode: "gone"]), "mode") == "fast",
                  String.valueOf(startOf(after("COMPLETE", given + [mode: "gone"]), "mode")))
            check("a value of another kind than the field is not put in it", startOf(after("COMPLETE", given + [x: 7.0d]), "x") != 7.0d)
        },
        again_clear_after_run_puts_the_field_back_after_a_run_that_succeeded: {
            check("after a run that succeeded the field is back to its default", startOf(after("COMPLETE"), "guess") == "" && startOf(after("COMPLETE"), "set_guess") == false,
                  startOf(after("COMPLETE"), "guess") + " " + startOf(after("COMPLETE"), "set_guess"))
            ["FAILED", "CANCELED", null].each { String status ->
                check("after a run that did not succeed (" + status + ") the field keeps its value", startOf(after(status), "guess") == "B" && startOf(after(status), "set_guess") == true,
                      startOf(after(status), "guess") + " " + startOf(after(status), "set_guess"))
            }
            check("the other fields are kept after a run that succeeded", startOf(after("COMPLETE"), "note") == "hi")
        },
        again_a_pixel_size_typed_by_the_person_is_kept_one_that_followed_the_image_follows_it_again: {
            def typed = linkedForm([state: "user", value: 0.3d, source: "user"])
            check("a typed pixel size is kept", typed[0].getInput("ps").getDefaultValue() == 0.3d && typed[2].ps.state == "user",
                  typed[0].getInput("ps").getDefaultValue() + " " + typed[2].ps)
            def followed = linkedForm([state: "auto", value: 0.3d, source: "image"])
            check("a pixel size that followed the image follows it again (no image here: unknown, cleared)",
                  followed[0].getInput("ps").getDefaultValue() == 0.0d && followed[2].ps.state == "unset", followed[0].getInput("ps").getDefaultValue() + " " + followed[2].ps)
        },
        again_the_choosers_start_on_the_app_and_tool_picked_last: {
            def choices = lc.toolChoices([schema: [tools: [[id: "one", label: "One"], [id: "two", label: "Same"], [id: "three", label: "Same"]]]])
            check("the entry of a tool", lc.toolChoiceOf(choices, "one") == "One")
            check("the entry of a tool whose label is shared", lc.toolChoiceOf(choices, "three") == "Same (three)", String.valueOf(lc.toolChoiceOf(choices, "three")))
            check("a tool the app no longer has: no entry (the chooser starts on the first)", lc.toolChoiceOf(choices, "gone") == null)
            check("nothing kept: no entry", lc.toolChoiceOf(choices, null) == null)
            def file = lc.lastToolFile()
            def before = file.isFile() ? file.getText("UTF-8") : null
            try {
                file.parentFile.mkdirs()
                file.setText("{not json", "UTF-8")
                check("a damaged file is no choice (logged), never an error", lc.readLastTool() == [:], String.valueOf(lc.readLastTool()))
                lc.rememberLastTool("beta", [id: "two", label: "Same"])
                check("the app and tool picked are kept", lc.readLastTool() == [app: "beta", tool: "two"], String.valueOf(lc.readLastTool()))
            } finally {
                if (before == null) file.delete() else file.setText(before, "UTF-8")      // the home is shared with the other cases of the run
            }
        },
    ]
}
