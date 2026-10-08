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
    },
]

cfg.unit_checks.each { String group ->
    if (!groups.containsKey(group)) failures << ("unknown group " + group)
    else try { groups[group]() } catch (Exception problem) { failures << (group + " raised " + problem) }   // the report is written whatever happens
}
new File(cfg.report as String).text = JsonOutput.prettyPrint(JsonOutput.toJson([failures: failures, checked: checked, groups: cfg.unit_checks]))
System.exit(0)
