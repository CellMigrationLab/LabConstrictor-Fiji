// Unit checks of functions of LabConstrictor_Tools.groovy that no single run can reach (every exit code, every number text, every literal).
// NOT part of the product. Run by tests/run_cases.py for the cases that carry "unit_checks": the product script is parsed WITHOUT its last
// line (the call of labConstrictorMain), so its functions and constants can be called directly. The case names the groups to run.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def cfg = new JsonSlurper().parseText(new File(System.getenv("LC_FIJI_CASE")).text)
def text = new File(cfg.script as String).text.replaceFirst(/(?m)^#@/, "//#@").replaceFirst(/(?m)^labConstrictorMain\(\)\s*$/, "")
def lc = new GroovyShell(this.class.classLoader).parse(text)
lc.run()                                   // the top-level definitions only: hooks, constants, helper classes

def failures = [], checked = 0
def check = { String name, boolean ok, String detail = "" ->
    checked++
    if (!ok) failures << (name + (detail ? ": " + detail : ""))
}
// the refusal a call raises (the message of its IllegalArgumentException), or null when it accepted; any other exception is a failure of the code under test
def refuses = { Closure call ->
    try { call(); return null }
    catch (IllegalArgumentException problem) { return problem.message }
    catch (Exception problem) { failures << ("unexpected " + problem); return "unexpected " + problem }
}

def groups = [
    crash_hints: {
        def hint = { String error, List output = [] -> lc.crashHint([error: error, workerOutput: output, cancelRequested: false]) }
        [-1073741819L, 3221225477L, -11L, 139L].each { code ->
            check("segfault code " + code, hint("worker exited, exit code " + code).contains("crashed natively"), hint("exit code " + code))
        }
        [-9L, 137L].each { code -> check("killed code " + code, hint("exit code " + code).contains("killed")) }
        check("import failed code 3", hint("exit code 3").contains("failed to import"))
        check("missing package wins over the exit code", hint("exit code 137", ["ModuleNotFoundError: No module named 'x'"]).contains("package is missing"))
        check("unknown code", hint("exit code 42").contains("stopped unexpectedly"))
    },
    python_literals: {
        def literal = { def value -> lc.pythonLiteral(value) }
        check("plain", literal("plain") == "'plain'", literal("plain"))
        check("newline", literal("a\nb") == "'a\\nb'", literal("a\nb"))
        check("carriage return", literal("a\rb") == "'a\\rb'", literal("a\rb"))
        check("tab", literal("tab\t") == "'tab\\t'", literal("tab\t"))
        check("backslash", literal("back\\slash") == "'back\\\\slash'", literal("back\\slash"))
        check("quote", literal("it's") == "'it\\'s'", literal("it's"))
        check("control character", literal("a\u0001b") == "'a\\x01b'", literal("a\u0001b"))
        check("delete", literal("a\u007fb") == "'a\\x7fb'", literal("a\u007fb"))
        check("zero width space", literal("a​b") == "'a\\u200bb'", literal("a​b"))
        check("line separator", literal("a b") == "'a\\u2028b'", literal("a b"))
        check("printable unicode stays", literal("naïve µm") == "'naïve µm'", literal("naïve µm"))
        check("emoji stays", literal("a😀b") == "'a😀b'", literal("a😀b"))
        check("true", literal(true) == "True")
        check("false", literal(false) == "False")
        check("float", literal(0.325) == "0.325", literal(0.325))
        check("integer", literal(3) == "3")
    },
    number_grammar: {
        def integer = [type: "integer", label: "L"], decimal = [type: "float", label: "L"]
        def refusal = { Map p, String given -> refuses { lc.macroNumber(p, given) } }
        ["5", "-5", "+5", "007"].each { String t -> check("integer accepts " + t, refusal(integer, t) == null, refusal(integer, t)) }
        ["", " 5", "5 ", "5.0", "1e3", "1_000", "0x10", "\u0665", "nan", "5,0"].each { String t -> check("integer rejects '" + t + "'", refusal(integer, t) != null) }
        ["5", "-5.5", ".5", "5.", "1e3", "1E-5", "0.325", "+2"].each { String t -> check("float accepts " + t, refusal(decimal, t) == null, refusal(decimal, t)) }
        ["", " 0.5", "0.5 ", "1,5", "1_0", "1.5f", "2d", "0x1p3", "1e", "--1", "\u0665"].each { String t -> check("float rejects '" + t + "'", refusal(decimal, t) != null) }
        ["nan", "NaN", "inf", "-inf", "Infinity", "-Infinity", "1e999"].each { String t ->
            check("float refuses '" + t + "' as not finite", (refusal(decimal, t) ?: "").contains("must be a finite number, got '" + t + "'"), refusal(decimal, t))
        }
        check("integer beyond 32 bits", refusal(integer, "2147483648") == "'L' must be <= 2147483647, got 2147483648", refusal(integer, "2147483648"))
        check("integer below 32 bits", refusal(integer, "-2147483649") == "'L' must be >= -2147483648, got -2147483649", refusal(integer, "-2147483649"))
        check("minimum template", refusal([type: "integer", label: "L", minimum: 3], "2") == "'L' must be >= 3, got 2")
        check("exponent value", lc.macroNumber(decimal, "1E-2") == 0.01d)
        check("integer is an int", lc.macroNumber(integer, "+7") instanceof Integer)
        check("integer template", refusal(integer, "2.5") == "'L' must be an integer, got '2.5'", refusal(integer, "2.5"))
        check("float template", refusal(decimal, "abc") == "'L' must be a number, got 'abc'", refusal(decimal, "abc"))
        def words = [name: "mode", label: "Mode", choices: ["fast", "it's"]]
        def numbers = [name: "n", label: "N", choices: [1, 2, 3]]
        check("choices of text as Python writes them", lc.choicesText(words) == "['fast', 'it\\'s']", lc.choicesText(words))
        check("choices of numbers", lc.choicesText(numbers) == "[1, 2, 3]", lc.choicesText(numbers))
        check("choice template uses the name", refuses { lc.typedChoice(words, "slow") } == "'mode' must be one of ['fast', 'it\\'s']", refuses { lc.typedChoice(words, "slow") })
    },
    affine_warp: {
        def plane = new ij.process.FloatProcessor(3, 3, [1, 2, 3, 4, 5, 6, 7, 8, 9] as float[])
        def image = new ij.ImagePlus("plane", plane)
        def pixels = { List matrix -> (0..<9).collect { lc.resample(image, 3, 3, matrix).getf(it) as double } }
        check("identity keeps the pixels", pixels([[1, 0, 0], [0, 1, 0]]) == [1, 2, 3, 4, 5, 6, 7, 8, 9] as List<Double>, pixels([[1, 0, 0], [0, 1, 0]]).toString())
        // [y', x'] = M [y, x]: x' = 0 for every pixel, so each row collapses onto its first column (the mean of the row)
        def collapsed = pixels([[1, 0, 0], [0, 0, 0]])
        check("singular matrix still applied", collapsed == [2, 0, 0, 5, 0, 0, 8, 0, 0] as List<Double>, collapsed.toString())
        check("singular is detected", lc.determinantOf([[1, 0, 0], [0, 0, 0]]).singular)
        check("regular is not singular", !lc.determinantOf([[1, 0, 0], [0, 2, 0]]).singular)
        check("determinant", lc.determinantOf([[1, 0, 0], [0, 2, 0]]).det == 2d)
        def failure = null
        try { lc.resample(image, 3, 3, [[1, 0, 0], [0, Double.NaN, 0]]) } catch (IllegalStateException problem) { failure = problem.message }
        check("non-finite matrix refused", failure == "the alignment matrix contains values that are not finite numbers", failure)
    },
    usable_tools: {
        def schema = [protocol: 1, tools: [
            [id: "fine", label: "Fine", inputs: [[name: "a", label: "A", type: "float"]], outputs: []],
            [id: "odd", label: "Odd", inputs: [[name: "x", label: "X", type: "float"], [name: "m", label: "M", type: "matrix"]], outputs: []],
        ]]
        check("schema with an unknown type is still well formed", lc.schemaProblem(schema) == null, lc.schemaProblem(schema))
        def usable = lc.usableTools(schema)
        check("the known tool stays", usable.tools*.id == ["fine"], usable.tools*.id.toString())
        check("the other is named", usable.skipped == ["tool 'odd': this tool cannot be shown: parameter 'm' has the unknown type 'matrix'"], usable.skipped.toString())
        check("nothing skipped when all are known", lc.usableTools([tools: [schema.tools[0]]]).skipped == [])
    },
    quote_vectors: {
        // tests/fixtures/quote_vectors.json: a copy of LabConstrictor-Tools tests/quote_vectors.json (the shared vectors every host quotes by)
        def vectors = new JsonSlurper().parseText(new File(cfg.vectors as String).text)
        vectors.each { v ->
            check("posix " + v.label, lc.shellQuote(v.text as String, false) == v.posix, lc.shellQuote(v.text as String, false))
            check("windows " + v.label, lc.shellQuote(v.text as String, true) == v.windows, lc.shellQuote(v.text as String, true))
        }
    },
    form_integers: {
        def limit = 9007199254740991L                                  // 2**53 - 1
        def wideInteger = { Map extra -> [type: "integer", name: "big", label: "Big", required: true] + extra }
        def refusal = { Map p, String given -> refuses { lc.macroNumber(p, given) } }
        check("the limit", lc.MAX_FORM_INTEGER == limit)
        check("bounds within the limit are kept", lc.integerLimits(wideInteger([minimum: -5, maximum: 5_000_000_000L])) == [minimum: -5L, maximum: 5_000_000_000L, limited: false])
        def beyond = lc.integerLimits(wideInteger([minimum: -(10G ** 20), maximum: 10G ** 20]))
        check("bounds beyond the limit are narrowed and said", beyond == [minimum: -limit, maximum: limit, limited: true], beyond.toString())
        def wide = wideInteger([maximum: 5_000_000_000L])
        check("a wide bound is not cut to 32 bits", lc.macroNumber(wide, "3000000000") == 3_000_000_000L, lc.macroNumber(wide, "3000000000").toString())
        check("a small value stays an int", lc.macroNumber(wide, "7") instanceof Integer)
        check("beyond a wide bound", refusal(wide, "5000000001") == "'Big' must be <= 5000000000, got 5000000001", refusal(wide, "5000000001"))
        def huge = wideInteger([maximum: 10G ** 20])
        check("the limit itself is accepted", lc.macroNumber(huge, "9007199254740991") == limit)
        check("beyond the limit is refused", refusal(huge, "9007199254740992") == "'Big' must be <= 9007199254740991, got 9007199254740992", refusal(huge, "9007199254740992"))
        check("below the limit is refused", refusal(huge, "-9007199254740992") == "'Big' must be >= -9007199254740991, got -9007199254740992", refusal(huge, "-9007199254740992"))
        check("32 bits", lc.fitsInt(2147483647) && !lc.fitsInt(2147483648L) && lc.fitsInt(-2147483648) && !lc.fitsInt(-2147483649L))
        def itemOf = { Map p -> lc.buildToolDialog([label: "T", inputs: [p], outputs: []], [])[0].getInput("big") }
        check("small bounds: a 32-bit field", itemOf(wideInteger([minimum: 1, maximum: 9])).getType() == Integer)
        check("wide bound: a 64-bit field", itemOf(wideInteger([maximum: 5_000_000_000L])).getType() == Long)
        def limited = itemOf(wideInteger([minimum: 0, maximum: 10G ** 20]))
        check("limit beyond: a 64-bit field with the limit in its description", limited.getType() == Long && limited.getDescription() == "Whole numbers up to +-9007199254740991." && limited.getMaximumValue() == limit,
              limited.getType().toString() + " " + limited.getDescription() + " " + limited.getMaximumValue())
        check("a wide default is kept", itemOf(wideInteger([default: 4_000_000_000L])).getDefaultValue() == 4_000_000_000L)
    },
]

cfg.unit_checks.each { String group ->
    if (!groups.containsKey(group)) failures << ("unknown group " + group)
    else try { groups[group]() } catch (Exception problem) { failures << (group + " raised " + problem) }   // the report is written whatever happens
}
new File(cfg.report as String).text = JsonOutput.prettyPrint(JsonOutput.toJson([failures: failures, checked: checked, groups: cfg.unit_checks]))
System.exit(0)
