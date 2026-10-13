// Unit-check groups of the number-limits theme (docs/SPEC_WRONG_SCIENCE.md section 6, conformance.json limits, messages and wrong_science.number_limits) and of the
// worker exit-code rule. NOT part of the product. Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox.
import groovy.json.JsonSlurper

{ Map t ->
    def lc = t.lc, check = t.check, unsupported = t.unsupported
    def numbers = lc.lib("LCNumbers")
    def render = { String template, Map values -> values.inject(template) { String text, String name, Object value -> text.replace("{" + name + "}", value.toString()) } }
    // the parameter a NUM fixture describes, named by the words of its `field`
    def parameterOf = { String field ->
        field.startsWith("integer with declared Max 10") ? [type: "integer", label: "L", name: "l", required: true, maximum: 10]
            : field.startsWith("float") ? [type: "float", label: "L", name: "l", required: true]
            : [type: "integer", label: "L", name: "l", required: true]
    }
    // what the dialog does with a text: the check of its field, and what a macro does: the same grammar
    def viaField = { Map p, String text -> t.refuses { numbers.fromField(p, text) } }
    def viaMacro = { Map p, String text -> t.refuses { lc.macroNumber(p, text) } }

    return [
        conformance_numbers: {
            def data = t.conformance(), L = data.limits, N = data.wrong_science.number_limits
            check("the whole-number limit is limits.form_integer_max", numbers.FORM_INTEGER_MAX == (L.form_integer_max.value as long), numbers.FORM_INTEGER_MAX.toString())
            check("the float limit is limits.unbounded_float", numbers.UNBOUNDED_FLOAT == (L.unbounded_float.value as double), numbers.UNBOUNDED_FLOAT.toString())
            check("the float limit is printed the way Python prints it", Double.parseDouble(numbers.FLOAT_LIMIT_TEXT) == numbers.UNBOUNDED_FLOAT && numbers.FLOAT_LIMIT_TEXT == "1000000000000000.0")
            check("the fixtures name exactly the two limits", N.limits == ["form_integer_max", "unbounded_float"], N.limits.toString())
            def above = data.messages.number_above_maximum.template as String, below = data.messages.number_below_minimum.template as String
            // each NUM fixture: through the dialog's field check AND through the macro check; the refusal is the conformance template with the label and the limit
            N.cases.each { fixture ->
                def p = parameterOf(fixture.field as String)
                def typedList = fixture.typed instanceof List ? fixture.typed : [fixture.typed]
                def expectList = fixture.expect instanceof List ? fixture.expect : [fixture.expect]
                typedList.eachWithIndex { String text, int i ->
                    def wantsRefusal = (expectList[i] as String).startsWith("refused")
                    ["dialog": viaField, "macro": viaMacro].each { String path, Closure how ->
                        def refusal = how(p, text)
                        check(fixture.id + " " + path + " '" + text + "': " + (wantsRefusal ? "refused" : "accepted"), wantsRefusal == (refusal != null), refusal)
                        if (wantsRefusal) {
                            def sentence = (expectList[i] as String).replaceFirst(/^refused: /, "").split(";")[0].replaceFirst(/ \(unchanged\)$/, "")
                            if (sentence.startsWith("'L'")) check(fixture.id + " " + path + " says the conformance sentence", refusal != null && refusal.startsWith(sentence), refusal + " vs " + sentence)
                        }
                    }
                }
            }
            check("the conformance template for a maximum is ours", (above.replace("{label}", "L").replace("{maximum}", "9007199254740991")) == "'L' must be <= 9007199254740991" &&
                  viaField([type: "integer", label: "L", name: "l", required: true], "9007199254740992").startsWith(above.replace("{label}", "L").replace("{maximum}", "9007199254740991")))
            check("the conformance template for a minimum is ours", viaField([type: "integer", label: "L", name: "l", required: true], "-9007199254740992").startsWith(below.replace("{label}", "L").replace("{minimum}", "-9007199254740991")))
            // never clamped: what is refused is not changed into the limit, and what is accepted is exactly what was typed
            check("the largest whole number is held exactly", numbers.fromField([type: "integer", label: "L", name: "l"], "9007199254740991") == 9007199254740991L)
            check("a whole number typed with a plus sign and zeros is the same number", numbers.fromField([type: "integer", label: "L", name: "l"], "+0042") == 42)
            check("a float is the double nearest the text", numbers.fromField([type: "float", label: "L", name: "l"], "0.1") == 0.1d)
            check("a declared bound beyond the limit is cut to it and the sentence names the limit that applies",
                  viaField([type: "integer", label: "L", name: "l", maximum: 10G ** 20], "9007199254740992") == "'L' must be <= 9007199254740991, got 9007199254740992")
            check("a declared float bound beyond the limit is cut to it", viaField([type: "float", label: "L", name: "l", maximum: 1.0E20], "1.5e15") == "'L' must be <= 1000000000000000.0, got 1.5e15")
            check("an empty required field is refused with no stand-in value", viaField([type: "integer", label: "L", name: "l", required: true], "  ") == "'L' is required: type a number")
            check("an empty optional field that was ticked is refused", viaField([type: "integer", label: "L", name: "l", nullable: true], "") == "'L' is empty: type a number")
            check("a value too close to zero is refused, not turned into 0", viaField([type: "float", label: "L", name: "l"], "1e-400") == "'L' is too close to zero to be held exactly, got '1e-400'")
            check("zero in any spelling is zero", numbers.fromField([type: "float", label: "L", name: "l"], "0e5") == 0.0d && numbers.fromField([type: "float", label: "L", name: "l"], "-0.0") == -0.0d)
        },
        conformance_numbers_coverage: {
            // no silent skip: every NUM id of the conformance file is run through the dialog and through a macro by a case file ("fixtures": [ids] with dialog_steps or script_macro)
            def N = t.conformance().wrong_science.number_limits, dialog = [] as Set, macro = [] as Set
            new File(t.cfg.cases_dir as String).listFiles({ File f -> f.name.endsWith(".json") } as FileFilter).sort().each { File f ->
                def parsed = new JsonSlurper().parseText(f.getText("UTF-8"))
                (parsed.fixtures ?: []).findAll { it.startsWith("NUM-") }.each { String id -> if (parsed.dialog_steps) dialog << id; if (parsed.script_macro) macro << id }
            }
            N.cases*.id.each { String id ->
                check(id + " is typed into the dialog by a case file", id in dialog)
                check(id + " is given to a macro by a case file", id in macro)
            }
            check("the fixtures are the five of the spec", N.cases*.id == ["NUM-01", "NUM-02", "NUM-03", "NUM-04", "NUM-05"], N.cases*.id.toString())
        },
        exit_code_rule: {
            // the reference is the Tools function the other hosts read the same rule from: labconstrictor_tools.log.hint_for_exit (Tools PR #124): code 3 is an import failure only with the
            // marker `cannot import tool module` on the worker's stderr. The oracle is asked in the app's own interpreter.
            String python = System.getenv("LC_PYTHON")
            def oracle = { def code, String stderr ->
                def process = new ProcessBuilder([python, "-c", "import sys; from labconstrictor_tools import log; print(log.hint_for_exit(int(sys.argv[1]), sys.argv[2]))", code.toString(), stderr]).redirectErrorStream(true).start()
                def out = process.inputStream.getText("UTF-8").trim(); process.waitFor(); out
            }
            def kind = { String text ->
                text.contains("package is missing") ? "package" : text.contains("killed") ? "killed" : text.contains("crashed natively") ? "segfault" : text.contains("failed to import") ? "import" : "none"
            }
            def tools = new ProcessBuilder([python, "-c", "from labconstrictor_tools import log; print(log.IMPORT_FAILED_MARKER); print(log.EXIT_TOOL_IMPORT_FAILED)"]).start()
            def lines = tools.inputStream.getText("UTF-8").readLines(); tools.waitFor()
            check("our marker is the marker of Tools", lines[0] == lc.IMPORT_FAILED_MARKER, lines[0] + " vs " + lc.IMPORT_FAILED_MARKER)
            check("our import-failure code is the code of Tools", lines[1] as long == lc.EXIT_IMPORT_FAILED, lines[1])
            [[3, "LabConstrictor worker: cannot import tool module 'x' (details above)"], [3, ""], [3, "Fatal Python error: abort"], [1, "cannot import tool module 'x'"], [-9, ""], [137, ""], [139, ""], [-11, ""],
             [3221225477L, ""], [3, "ModuleNotFoundError: No module named 'q'"], [1, "ImportError: boom"], [42, ""], [0, ""]].each { List item ->
                def ours = lc.crashHint([error: "worker exited, exit code " + item[0], workerOutput: [item[1]], cancelRequested: false])
                def theirs = oracle(item[0], item[1] as String)
                def expectKind = kind(theirs)
                def gotKind = kind(ours)
                check("exit code " + item[0] + " with '" + item[1] + "': the same cause as Tools says (" + expectKind + ")", gotKind == expectKind, ours + " vs " + theirs)
            }
        },
    ]
}
