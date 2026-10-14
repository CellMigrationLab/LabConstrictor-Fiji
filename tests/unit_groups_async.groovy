// Unit-check groups of the compatibility, worker-owner and lookup themes (docs/PROTOCOL.md "Compatibility rules for hosts", docs/SPEC_WRONG_SCIENCE.md section 5, conformance.json
// `compat`, `limits`, `messages`, `wrong_science.async_lookups`, `wrong_science.run_identity`). NOT part of the product. Loaded by tests/unit_checks.groovy, which calls this closure
// with its toolbox. Returns the groups by name; a case selects them with "unit_checks": [...].
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

// A worker as the registry sees it: something that can be closed and killed. The counters say how many times.
class FakeWorker {
    final AtomicInteger closes = new AtomicInteger()
    final AtomicInteger kills = new AtomicInteger()
    boolean failsToDie = false
    void close() { closes.incrementAndGet() }
    void kill() {
        if (failsToDie) throw new IllegalStateException("cannot kill")
        kills.incrementAndGet()
    }
}

{ Map t ->
    def lc = t.lc, check = t.check, unsupported = t.unsupported
    def compat = lc.lib("LCCompat"), workers = lc.lib("LCWorkers")
    String python = System.getenv("LC_PYTHON")
    // the reference: labconstrictor_tools.compat of the Tools in the case's Python, asked about every vector at once
    def reference = { Map input ->
        def process = new ProcessBuilder([python, "-c", "import json, sys\nfrom labconstrictor_tools import compat\nd = json.load(sys.stdin)\n" +
            "out = {'checks': [list(compat.check_schema(c['schema'], c.get('minimum'), c.get('required', []))) for c in d['cases']], " +
            "'versions': [(list(compat.parse_version(v)) if compat.parse_version(v) else None) for v in d['versions']]}\nprint(json.dumps(out))"]).redirectErrorStream(true).start()
        process.outputStream.withCloseable { it.write(JsonOutput.toJson(input).getBytes("UTF-8")) }
        def text = process.inputStream.getText("UTF-8")
        process.waitFor()
        new JsonSlurper().parseText(text.readLines().findAll { it.trim() }.last())
    }

    return [
        conformance_compat: {
            def C = t.conformance().compat
            def cases = C.cases.collect { [schema: it.schema, minimum: C.min_tools_version, required: C.required_features] }
            def own = [
                [schema: [protocol: 1, tools_version: "0.3.0a1", features: ["reserved_inputs_v1", "retention_prune"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "0.3.0", features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "0.2.0rc1", features: ["reserved_inputs_v1", "retention_prune"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "0.3.0a1", features: []], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "0.3.0a1"], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "", features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "banana", features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "v0.3.0-alpha.1", features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: "x", tools_version: "0.3.0a1", features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [tools_version: "0.3.0a1", features: ["reserved_inputs_v1"]], minimum: compat.MINIMUM_TOOLS, required: compat.REQUIRED_FEATURES],
                [schema: [protocol: 1, tools_version: "0.2.9", features: ["a", "b"]], minimum: "0.3.0a1", required: ["b", "a", "c", "a"]],
                [schema: [protocol: 1, tools_version: "0.3.0a1", features: ["a"]], minimum: null, required: []],
            ]
            def versions = C.version_order + ["0.2", "v1.0", "1", "0.2.0-rc.1", "0.2.0 rc1", " 0.3.0a1 ", "0.3.0b2", "0.3.0.1", "", "banana"]
            def answers = reference([cases: cases + own, versions: versions])
            (cases + own).eachWithIndex { Map c, int i ->
                def ours = compat.checkSchema(c.schema as Map, c.minimum as String, c.required as Collection<String>, null, [1])
                def theirs = answers.checks[i]
                check("compat vector " + i + ": the status is the reference's (" + theirs[0] + ")", ours.status == theirs[0], ours.status + " vs " + theirs[0])
                check("compat vector " + i + ": the sentence is the reference's, word for word", ours.reason == theirs[1], ours.reason + " vs " + theirs[1])
            }
            C.cases.eachWithIndex { c, int i ->        // the conformance file's own expectations, not only the reference function's
                def ours = compat.checkSchema(c.schema as Map, C.min_tools_version as String, C.required_features as Collection<String>, null, [1])
                check("conformance case '" + c.case + "' is " + c.expect, ours.status == c.expect, ours.status)
                if (c.reason_contains) check("conformance case '" + c.case + "' says '" + c.reason_contains + "'", ours.reason.contains(c.reason_contains as String), ours.reason)
            }
            versions.eachWithIndex { String v, int i ->
                def ours = compat.parseVersion(v)
                check("parse_version('" + v + "') is the reference's", (ours == null && answers.versions[i] == null) || ours == answers.versions[i], ours.toString() + " vs " + answers.versions[i])
            }
            def order = C.version_order.collect { compat.parseVersion(it) }
            (0..<(order.size() - 1)).each { int i -> check("version order: " + C.version_order[i] + " < " + C.version_order[i + 1], compat.compareVersions(order[i], order[i + 1]) < 0) }
            check("a minimum that is not a version is the host's programming error", { try { compat.checkSchema([protocol: 1], "banana", [], null, [1]); false } catch (IllegalArgumentException problem) { problem.message.contains("is not a version") } }())
            // what this host needs
            def host = { Map schema -> compat.checkForHost(schema, [1]) }
            check("the host minimum is the one the run record needs", compat.MINIMUM_TOOLS == lc.lib("LCRunRecord").MINIMUM_TOOLS, compat.MINIMUM_TOOLS)
            check("Tools 0.3.0a1 with the features is ok", host([protocol: 1, tools_version: "0.3.0a1", features: ["reserved_inputs_v1", "app_hosts_v1"]]).status == "ok")
            check("Tools 0.2.0rc1 is refused", host([protocol: 1, tools_version: "0.2.0rc1", features: ["reserved_inputs_v1"]]).status == "refuse")
            def known = new ProcessBuilder([python, "-c", "from labconstrictor_tools import features; print(','.join(features.FEATURES))"]).start().inputStream.getText("UTF-8").trim().split(",") as Set
            check("the features this host requires exist in Tools", compat.REQUIRED_FEATURES.every { it in known }, known.toString())
        },
        workers_registry: {
            // ASY-08 at the level of the owner of the workers (a worker is closed once, whoever asks and from however many threads): forced interleavings with barriers
            int rounds = 60, size = 12
            rounds.times { int round ->
                def fleet = (1..size).collect { new FakeWorker() }
                fleet.each { workers.register(it) }
                def barrier = new CyclicBarrier(3)
                def threads = (0..<3).collect { int who ->
                    Thread.start {
                        barrier.await()
                        if (who == 0) workers.killAll()
                        else fleet.each { FakeWorker w -> workers.release(w) { FakeWorker closing -> closing.close() } }       // two threads close, one kills the lot
                    }
                }
                threads*.join()
                if (round == 0) check("after the race no worker is registered any more", workers.liveCount() == 0, workers.liveCount().toString())
                def bad = fleet.findAll { it.closes.get() + it.kills.get() != 1 }
                if (bad) check("round " + round + ": every worker is closed or killed exactly once", false, bad.collect { it.closes.get() + "+" + it.kills.get() }.toString())
            }
            check("60 rounds of three threads closed or killed every worker exactly once", true)
            def one = new FakeWorker()
            check("a worker nobody registered is not closed by a release", !workers.release(one) { it.close() } && one.closes.get() == 0)
            workers.register(one)
            check("the first release closes it", workers.release(one) { it.close() } && one.closes.get() == 1)
            check("the second release does nothing: no double close", !workers.release(one) { it.close() } && one.closes.get() == 1)
            def again = new FakeWorker()
            workers.register(again)
            check("the shutdown kill reaches a worker that is still registered", workers.killAll() == 1 && again.kills.get() == 1)
            check("and a second one finds nothing: no double kill", workers.killAll() == 0 && again.kills.get() == 1)
            def stubborn = new FakeWorker(failsToDie: true), others = (1..3).collect { new FakeWorker() }
            workers.register(stubborn); others.each { workers.register(it) }
            def logged = []
            workers.hookLog = { String level, String message, Throwable problem -> logged << (level + ": " + message) }
            def killed = workers.killAll()
            check("a worker that cannot be killed does not keep the others alive", killed == 3 && others.every { it.kills.get() == 1 }, killed.toString())
            check("and is said", logged.any { it.startsWith("ERROR: could not kill a worker at shutdown") }, logged.toString())
        },
        conformance_async_coverage: {
            // no silent skip: every ASY fixture of the conformance file is run by a case ("fixtures": [ids]), by a unit group, or named here as not applicable to Fiji with the reason
            def A = t.conformance().wrong_science.async_lookups, named = [] as Set
            new File(t.cfg.cases_dir as String).listFiles({ File f -> f.name.endsWith(".json") } as FileFilter).sort().each { File f ->
                (new JsonSlurper().parseText(f.getText("UTF-8")).fixtures ?: []).each { String id -> named << id }
            }
            def groups = ["ASY-08": "unit group workers_registry (the registry that owns every worker, three threads with barriers)"]
            def notApplicable = [
                "ASY-01": "Fiji builds a dialog once, after the lookups: there is no switch of tool or app while a lookup is out, so no stale reply can arrive for another form",
                "ASY-02": "no dependency is edited in the dialog while it is shown: a ChoicesFrom is asked with the values of the previous run before the dialog opens and never again",
                "ASY-03": "no lookup outlives the form it was asked for (see ASY-01), so there is no field of another tool to put its error on",
                "ASY-06": "no worker is shared between jobs: a run and a lookup session each make their own worker, so a lookup can never use or replace the worker of a run",
            ]
            A.fixtures*.id.each { String id ->
                check(id + " is run by a case, by a unit group, or not applicable to Fiji", id in named || groups.containsKey(id) || notApplicable.containsKey(id))
                if (notApplicable.containsKey(id)) unsupported << (id + " not applicable to Fiji: " + notApplicable[id])
            }
            ["ASY-04", "ASY-05", "ASY-07"].each { String id -> check(id + " has a case file that names it", id in named) }
            check("the fixtures are the eight of the spec", A.fixtures*.id == (1..8).collect { String.format("ASY-%02d", it) }, A.fixtures*.id.toString())
        },
        conformance_limits_and_messages: {
            def data = t.conformance(), L = data.limits, M = data.messages, WS = data.wrong_science
            // each limit: the constant of the product that must equal it, or the reason it does not apply to this host (no silent skip)
            def equal = [
                worker_close_timeout_s: { lc.EXIT_WAIT_MS == (L.worker_close_timeout_s.value as long) * 1000 },
                lookup_timeout_s      : { lc.LOOKUP_TIMEOUT_MS == (L.lookup_timeout_s.value as long) * 1000 },
                form_integer_max      : { lc.lib("LCNumbers").FORM_INTEGER_MAX == (L.form_integer_max.value as long) },
                unbounded_float       : { lc.lib("LCNumbers").UNBOUNDED_FLOAT == (L.unbounded_float.value as double) },
                lookup_in_flight_max  : { (L.lookup_in_flight_max.value as int) == 1 },     // the lookups of a dialog are asked one at a time (case asy04_asy05)
            ]
            def notApplicable = [
                results_kept         : "hosts never prune: only the Tools prune applies the quota of 20",
                run_records_kept     : "hosts never prune: only the Tools prune applies the quota of 50",
                choices_debounce_ms  : "no dependency is edited while the dialog is shown, so nothing is debounced",
                choices_busy_retry_ms: "a lookup never waits for a run: it has a worker of its own",
                lookup_pending_max   : "there is no queue: the questions of a dialog are asked in order, one at a time, and none is dropped (so a form with more than 8 of them waits for all)",
            ]
            L.keySet().each { String key ->
                check("limit " + key + " is checked against the product or not applicable", equal.containsKey(key) || notApplicable.containsKey(key))
                if (equal.containsKey(key)) check("limit " + key + " equals the constant of the product (" + L[key].value + ")", equal[key]())
                if (notApplicable.containsKey(key)) unsupported << ("limit " + key + " not applicable to Fiji: " + notApplicable[key])
            }
            // each message template: compared with what the product writes, or not produced by this version (run identity is not part of this round)
            def calibration = lc.getClass().getClassLoader().loadClass("LCCalibration")
            calibration.catalog = calibration.catalogFrom(lc.UNIT_CATALOG_FALLBACK)           // the built-in copy: the unit checks do not choose an app
            def say = { String key, Map values -> calibration.message(key, values) }
            def produced = [
                size_mismatch              : { lc.sizeMismatchText("n", [3, 4], "s", [5, 6]) == M.size_mismatch.template.replace("{name}", "n").replace("{w}", "3").replace("{h}", "4").replace("{source}", "s").replace("{w2}", "5").replace("{h2}", "6") },
                calibration_required       : { say("calibration_required", [param: "p", of: "i"]) == M.calibration_required.template.replace("{param}", "p").replace("{of}", "i") },
                calibration_unknown_tooltip: { say("calibration_unknown_tooltip", [of: "i"]) == M.calibration_unknown_tooltip.template.replace("{of}", "i") },
                unit_damaged_warning       : { say("unit_damaged_warning", [unit: "u"]) == M.unit_damaged_warning.template.replace("{unit}", "u") },
                pixels_not_square_warning  : { say("pixels_not_square_warning", [:]) == M.pixels_not_square_warning.template },
                unit_not_length_warning    : { say("unit_not_length_warning", [unit: "u"]) == M.unit_not_length_warning.template.replace("{unit}", "u") },
            ]
            def sentences = []
            [[[type: "integer", label: "L", maximum: 10], "11"], [[type: "integer", label: "L", minimum: -10], "-11"]].each { List item ->
                try { lc.lib("LCNumbers").parse(item[0] as Map, item[1] as String) } catch (IllegalArgumentException problem) { sentences << problem.message }
            }
            check("number_above_maximum: our sentence starts with the template", sentences[0].startsWith(M.number_above_maximum.template.replace("{label}", "L").replace("{maximum}", "10")), sentences[0])
            check("number_below_minimum: our sentence starts with the template", sentences[1].startsWith(M.number_below_minimum.template.replace("{label}", "L").replace("{minimum}", "-10")), sentences[1])
            def notProduced = [
                target_unavailable : "run identity (SPEC_WRONG_SCIENCE section 4) is not part of this version of the Fiji host",
                target_changed_size: "run identity (SPEC_WRONG_SCIENCE section 4) is not part of this version of the Fiji host",
                refusal_suffix     : "the refusal suffix of coordinate refusals: Fiji's selection refusals do not carry it yet",
                file_input_note    : "QuPath's status line only",
            ]
            M.keySet().each { String key ->
                boolean handled = produced.containsKey(key) || key in ["number_above_maximum", "number_below_minimum"] || notProduced.containsKey(key)
                check("message " + key + " is compared with the product or named as not produced", handled)
                if (produced.containsKey(key)) check("message " + key + " is the product's sentence", produced[key]())
                if (notProduced.containsKey(key)) unsupported << ("message " + key + " not produced by Fiji: " + notProduced[key])
            }
            // run identity fixtures: all unsupported (named, never counted as passed)
            WS.run_identity.fixtures.each { fixture -> unsupported << (fixture.id + " run identity is not part of this version of the Fiji host (SPEC_WRONG_SCIENCE section 4)") }
            check("the host-internal codes are the four of the spec, none of them produced here except selection_mixed_planes", data.host_internal_codes.contains(lc.REFUSAL_SELECTION_PLANES))
        },
    ]
}
