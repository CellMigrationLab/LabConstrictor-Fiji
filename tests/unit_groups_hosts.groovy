// Unit-check groups of the per-host discovery rule (docs/PROTOCOL.md "App hosts", conformance.json `app_hosts`, feature app_hosts_v1). NOT part of the product. Loaded by
// tests/unit_checks.groovy, which calls this closure with its toolbox. Returns the groups by name; a case selects them with "unit_checks": [...].
// The vectors are read from the conformance file of the Tools in the case's Python, and a missing `app_hosts` key is a FAILURE (never a skip): a Tools without the rule
// cannot be what this host is tested against.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

{ Map t ->
    def lc = t.lc, check = t.check
    def hosts = lc.lib("LCHosts"), compat = lc.lib("LCCompat")
    String python = System.getenv("LC_PYTHON")

    // the section of the vectors; null (and a failure) when the key is missing
    def section = { Map conformance ->
        def found = conformance.get("app_hosts")
        check("conformance.json has the key app_hosts (a Tools without it is too old for this host)", found instanceof Map, "keys: " + conformance.keySet())
        return found instanceof Map ? found as Map : null
    }

    // the reference: labconstrictor_tools.app_hosts.listed_for of the Tools in the case's Python, asked about every (entry, host) at once
    def reference = { List<Map> questions ->
        def process = new ProcessBuilder([python, "-c", "import json, sys\nfrom labconstrictor_tools import app_hosts\nout = []\nfor q in json.load(sys.stdin):\n" +
            "    try:\n        out.append(list(app_hosts.listed_for(q['entry'], q['host'])))\n    except ValueError as problem:\n        out.append(['error', str(problem)])\nprint(json.dumps(out))"]).redirectErrorStream(true).start()
        process.outputStream.withCloseable { it.write(JsonOutput.toJson(questions).getBytes("UTF-8")) }
        def text = process.inputStream.getText("UTF-8")
        process.waitFor()
        new JsonSlurper().parseText(text.readLines().findAll { it.trim() }.last())
    }

    return [
        app_hosts_vectors: {
            def C = section(t.conformance())
            if (C == null) return
            check("the section names the feature this host requires", C.feature == "app_hosts_v1" && "app_hosts_v1" in compat.REQUIRED_FEATURES, String.valueOf(C.feature))
            check("the known host names are the vectors'", hosts.KNOWN == C.known_hosts, hosts.KNOWN.toString())
            check("the section has cases", C.cases instanceof List && !C.cases.isEmpty())
            ["not_listed", "absent", "invalid"].each { String key ->
                String template = C.messages[key].template
                String ours = key == "not_listed" ? hosts.NOT_LISTED : key == "absent" ? hosts.ABSENT : hosts.INVALID
                check("the " + key + " sentence is the vectors' template", ours == template, ours + " vs " + template)
            }
            C.cases.each { Map c ->
                String label = "app_hosts case '" + c.case + "'"
                if (c.expect == "error") {
                    def refused = null
                    try { hosts.listedFor(c.entry as Map, c.host as String) } catch (IllegalArgumentException problem) { refused = problem.message }
                    check(label + " is the host's own error, not a verdict", refused != null && refused.contains("unknown host"), String.valueOf(refused))
                    return
                }
                def answer = hosts.listedFor(c.entry as Map, c.host as String)
                check(label + " is " + c.expect, answer.listed == (c.expect == "listed"), answer.toString())
                check(label + " says the exact sentence", answer.sentence == c.sentence, answer.sentence + " vs " + c.sentence)
            }
            ["fiji", "napari"].each { String host -> check("the shortcut asks as fiji", hosts.HOST == "fiji" && hosts.listedInFiji([name: "A", hosts: [host]]).listed == (host == "fiji")) }
        },
        app_hosts_reference: {
            def C = section(t.conformance())
            if (C == null) return
            def odd = [[name: "O'Brien", hosts: ["fiji"]], [name: "O'Brien", hosts: ["qupath"]], [name: 'say "x"', hosts: [1]], [name: "b\\s", hosts: ["a'b"]], [name: "two", hosts: ["fiji", "napari", "napari"]],
                       [name: "dup first", hosts: ["fiji", "fiji", "nope"]], [name: "bool", hosts: [true]], [name: "nested", hosts: [["fiji"]]], [name: "empty name", hosts: ["napari"]], [hosts: ["napari"]], [name: "x"]]
            def questions = (C.cases.collect { [entry: it.entry, host: it.host] } +
                             odd.collectMany { Map e -> hosts.KNOWN.collect { String h -> [entry: e, host: h] } }) as List<Map>
            def answers = reference(questions)
            questions.eachWithIndex { Map q, int i ->
                String label = "reference answer " + i + " (" + q.entry + " in " + q.host + ")"
                def theirs = answers[i]
                if (theirs[0] == "error") {
                    def refused = null
                    try { hosts.listedFor(q.entry as Map, q.host as String) } catch (IllegalArgumentException problem) { refused = problem.message }
                    check(label + ": the reference refuses an unknown host, so do we", refused != null, String.valueOf(refused))
                    return
                }
                def ours = hosts.listedFor(q.entry as Map, q.host as String)
                check(label + ": the verdict is the reference's", ours.listed == theirs[0], ours.listed.toString() + " vs " + theirs[0])
                check(label + ": the sentence is the reference's, word for word", ours.sentence == theirs[1], ours.sentence + " vs " + theirs[1])
            }
        },
        app_hosts_compat: {
            def C = section(t.conformance())
            if (C == null) return
            check("the vectors' minimum is the host minimum", C.compat_rule.min_tools_version == compat.MINIMUM_TOOLS, compat.MINIMUM_TOOLS)
            check("the vectors' required features are the host's", C.compat_rule.required_features.every { it in compat.REQUIRED_FEATURES }, compat.REQUIRED_FEATURES.toString())
            C.compat_cases.each { Map c ->
                def verdict = compat.checkForHost(c.schema as Map, [1])
                boolean ok = verdict.status == "ok"
                check("app_hosts compat case '" + c.case + "' is " + c.expect, ok == (c.expect == "ok") && (c.expect == "ok" || verdict.status == "refuse"), verdict.toString())
                if (c.reason_contains) check("app_hosts compat case '" + c.case + "' says '" + c.reason_contains + "'", verdict.reason.contains(c.reason_contains as String), verdict.reason)
            }
        },
        app_hosts_missing_key_fails: {
            // the sabotage check of the vectors test: the same code run on a conformance file WITHOUT the key must fail, not pass quietly
            def before = t.failures.size()
            def found = section([wrong_science: [:]])
            check("a conformance file without app_hosts gives no section", found == null)
            boolean failed = t.failures.size() == before + 1
            t.failures.remove(t.failures.size() - 1)        // the failure above was the point of this check: take it back, then record the outcome
            check("a conformance file without app_hosts makes the test fail", failed)
        },
    ]
}
