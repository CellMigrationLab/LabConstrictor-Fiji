// Unit-check groups of the host-requirements rule (docs/PROTOCOL.md "Host requirements", conformance.json `host_requirements`, feature host_requirements_v1, Tools #155).
// NOT part of the product. Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox. Returns the groups by name; a case selects them with "unit_checks": [...].
// The vectors are read from the conformance file of the Tools in the case's Python; a missing `host_requirements` key is a FAILURE (never a skip).
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

{ Map t ->
    def lc = t.lc, check = t.check
    def rule = lc.lib("LCHostRequirements")
    String python = System.getenv("LC_PYTHON")

    def section = { Map conformance ->
        def found = conformance.get("host_requirements")
        check("conformance.json has the key host_requirements (a Tools without it is too old for this check)", found instanceof Map, "keys: " + conformance.keySet())
        return found instanceof Map ? found as Map : null
    }

    // the reference: labconstrictor_tools.host_requirements.check of the Tools in the case's Python, asked every question at once
    def reference = { List<Map> questions ->
        def process = new ProcessBuilder([python, "-c", "import json, sys\nfrom labconstrictor_tools import host_requirements as h\nout = []\nfor q in json.load(sys.stdin):\n" +
            "    try:\n        out.append(list(h.check(q['entry'], q['host'], q['version'], q['features'])))\n    except ValueError as problem:\n        out.append(['error', str(problem)])\n" +
            "print(json.dumps(out))"]).redirectErrorStream(true).start()
        process.outputStream.withCloseable { it.write(JsonOutput.toJson(questions).getBytes("UTF-8")) }
        def text = process.inputStream.getText("UTF-8")
        process.waitFor()
        new JsonSlurper().parseText(text.readLines().findAll { it.trim() }.last())
    }

    return [
        host_requirements_vectors: {
            def C = section(t.conformance())
            if (C == null) return
            check("the section names the feature", C.feature == "host_requirements_v1" && "host_requirements_v1" in rule.HOST_FEATURES, String.valueOf(C.feature))
            check("the hosts are the vectors'", rule.HOSTS == C.hosts, rule.HOSTS.toString())
            [too_old: rule.TOO_OLD, missing: rule.MISSING, unknown_version: rule.UNKNOWN_VERSION, invalid: rule.INVALID].each { String key, String ours ->
                check("the " + key + " sentence is the vectors' template", ours == C.messages[key].template, ours + " vs " + C.messages[key].template)
            }
            check("the update address is the vectors'", rule.UPDATE_URL == C.update_url.template && rule.PLUGIN == C.update_url.plugin, rule.UPDATE_URL)
            List order = C.version_order as List
            for (int i = 1; i < order.size(); i++) check("version " + order[i - 1] + " is older than " + order[i], rule.compare(order[i - 1], order[i]) < 0)
            check("v0.1.0-alpha.6 equals 0.1.0a6", rule.compare("v0.1.0-alpha.6", "0.1.0a6") == 0 && rule.compare("0.1", "0.1.0") == 0)
            C.cases.each { Map c ->
                String label = "host_requirements case '" + c.case + "'"
                if (c.expect == "error") {
                    def refused = null
                    try { rule.check(c.entry as Map, c.host as String, c.host_version as String, c.host_features as List) } catch (IllegalArgumentException problem) { refused = problem.message }
                    check(label + " is the host's own error, not a verdict", refused != null && refused.contains("unknown host"), String.valueOf(refused))
                    return
                }
                def answer = rule.check(c.entry as Map, c.host as String, c.host_version as String, c.host_features as List)
                check(label + " is " + c.expect, answer.status == c.expect, answer.toString())
                check(label + " says the exact sentence", answer.sentence == c.sentence, answer.sentence + " vs " + c.sentence)
            }
        },
        host_requirements_reference: {
            def C = section(t.conformance())
            if (C == null) return
            def entries = [
                [name: "O'Brien", host_requirements: [fiji: [min_version: "1.0", why: "it's \"new\""]]],
                [name: "A", display_name: "Shown", host_requirements: [fiji: [features: ["log_progress_v1", "x_v2"], required: true]]],
                [name: "Bad", host_requirements: [fiji: [min_version: 1.5]]], [name: "Bad", host_requirements: [fiji: [min_versoin: "1"]]],
                [name: "Bad", host_requirements: ["Fiji": [min_version: "1"]]], [name: "Bad", host_requirements: [fiji: [features: ["Bad Id"]]]],
                [name: "Bad", host_requirements: [fiji: [min_version: "1", required: "yes"]]], [name: "Bad", host_requirements: [fiji: [min_version: "1", why: "a\nb"]]],
                [name: "Bad", host_requirements: ["fiji"]], [name: "Bad", host_requirements: [fiji: [why: "only"]]], [name: "Plain"],
                [name: "Post", host_requirements: [qupath: [min_version: "1.0.post1"], napari: [min_version: "2!1.0"]]],
            ]
            def versions = [null, "", "0.1.0a6", "1.0", "1.0.dev0", "1.0rc1", "v1.0.0", "1.0.post1", "SNAPSHOT", "1.0.0-SNAPSHOT", "99"]
            def questions = []
            entries.each { Map e -> ["fiji", "qupath", "napari"].each { String h -> versions.each { v -> questions << [entry: e, host: h, version: v, features: ["log_progress_v1"]] } } }
            def answers = reference(questions as List<Map>)
            questions.eachWithIndex { Map q, int i ->
                String label = "reference answer " + i + " (" + q.entry + " in " + q.host + " " + q.version + ")"
                def ours = rule.check(q.entry as Map, q.host as String, q.version as String, q.features as List)
                check(label + ": the verdict is the reference's", ours.status == answers[i][0], ours.status + " vs " + answers[i][0])
                check(label + ": the sentence is the reference's, word for word", ours.sentence == answers[i][1], ours.sentence + " vs " + answers[i][1])
            }
        },
        host_requirements_missing_key_fails: {
            def before = t.failures.size()
            def found = section([wrong_science: [:]])
            check("a conformance file without host_requirements gives no section", found == null)
            boolean failed = t.failures.size() == before + 1
            t.failures.remove(t.failures.size() - 1)
            check("a conformance file without host_requirements makes the test fail", failed)
        },
    ]
}
