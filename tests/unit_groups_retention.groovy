// Unit-check groups of the retention theme (docs/SPEC_WRONG_SCIENCE.md section 3, conformance.json wrong_science.retention). NOT part of the product.
// Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox: lc (the parsed product script), check, refuses, conformance, cfg, failures, unsupported.
// Returns the groups by name; a case selects them with "unit_checks": [...].
import groovy.json.JsonSlurper

{ Map t ->
    def lc = t.lc, check = t.check, conformance = t.conformance, unsupported = t.unsupported
    def retention = lc.lib("LCRetention")
    String python = System.getenv("LC_PYTHON")
    def home = { new File(lc.lcHome()) }
    def app = { lc.discoverApps().apps.values().first() as Map }          // the one app the case registered (a private home)
    def freshFolder = { String name -> File f = File.createTempFile("lcret_", ""); f.delete(); f.mkdirs(); new File(f, name) }
    def recorderCommand = { String out -> { List<String> command -> [python, System.getenv("LC_PRUNE_RECORDER"), "recorder", out] + command } }
    // what the Tools helper says for a finished task (the real run-record helpers of the app's interpreter)
    def statusOf = { Map theApp, String status, boolean cancelled ->
        def request = [app: "x", tool: "t", inputs: [:], seconds: 0.5, stderr: "", run_id: "r", started_utc: "2026-01-01T00:00:00.000Z", finished_utc: "2026-01-01T00:00:01.000Z", extra: [:],
                       task: [status: status, error: null, code: null, traceback: null, outputs: [:], cancel_requested: cancelled]]
        lc.lib("LCRunRecord").compose(theApp, request, lc.lcHome(), lc.SCRUBBED_ENV).status
    }

    return [
        conformance_retention: {
            def data = t.conformance(), R = data.wrong_science.retention, M = R.markers
            def folder = freshFolder("20261013T120000_abcdef_x_y")
            retention.makeOwnedFolder(folder.parentFile, folder.name, "result")
            def owner = new JsonSlurper().parseText(new File(folder, M.owner_file as String).text) as Map
            check("the owner file name is the conformance one", retention.OWNER_FILE == M.owner_file, retention.OWNER_FILE)
            check("the done file name is the conformance one", retention.DONE_FILE == M.done_file, retention.DONE_FILE)
            check("the owner marker has exactly the fields of the conformance file", owner.keySet() == (M.owner_fields as Set), owner.keySet().toString())
            check("lc_marker is the integer 1", owner.lc_marker == 1 && !(owner.lc_marker instanceof Boolean), owner.lc_marker.toString())
            check("kind is one of the conformance kinds", owner.kind in M.owner_kinds, owner.kind)
            check("fiji is a host of the conformance file", retention.HOST in M.hosts && owner.host == "fiji", owner.host)
            check("name is the folder name", owner.name == folder.name)
            check("the token is long enough and made of the allowed characters", (owner.token as String) ==~ /[A-Za-z0-9_-]{8,64}/, owner.token)
            check("created is UTC with a Z", (owner.created as String) ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z/, owner.created)
            check("the pid is this process", owner.pid == ProcessHandle.current().pid())
            check("a done file is not there before the run ends", !new File(folder, M.done_file as String).exists())
            retention.writeDone(folder, "cancelled")
            def done = new JsonSlurper().parseText(new File(folder, M.done_file as String).text) as Map
            check("the done marker has exactly the fields of the conformance file", done.keySet() == (M.done_fields as Set), done.keySet().toString())
            check("the done status is the one given", done.status == "cancelled")
            check("the done statuses are the conformance ones", retention.STATUSES == M.done_statuses, retention.STATUSES.toString())
            check("no temporary file is left behind", folder.list().every { !it.contains(".tmp-") }, folder.list().toString())
            check("a status that is not in the list is refused", t.refuses { retention.writeDone(folder, "finished") } != null)
            def again = null
            try { retention.makeOwnedFolder(folder.parentFile, folder.name, "result") } catch (IOException problem) { again = problem.message }
            check("an existing folder is refused, not marked", again != null && again.contains("could not make"), again)
            def outside = null
            try { retention.makeOwnedFolder(folder.parentFile, "../escape", "result") } catch (IOException problem) { outside = problem.message }
            check("a name that leaves the root is refused", outside != null && outside.contains("not directly inside"), outside)
            def result = retention.resultFolderName("my app", "a/b")
            check("a result folder name is <time>_<6 hex>_<app>_<tool> of allowed characters", result ==~ /\d{8}T\d{6}_[0-9a-f]{6}_my_app_a_b/, result)
            check("a run folder name is the millisecond stamp, the app and the tool", retention.runFolderName("p", "q") ==~ /\d{8}T\d{9}_p_q/, retention.runFolderName("p", "q"))
        },
        conformance_done_status: {
            def D = t.conformance().wrong_science.retention.done_status, theApp = app()
            D.by_task_status.each { String taskStatus, String expected ->
                check(taskStatus + " without Cancel is " + expected + " (conformance by_task_status)", statusOf(theApp, taskStatus, false) == expected, statusOf(theApp, taskStatus, false))
            }
            check("CRASHED after Cancel is " + D.crashed_after_cancel, statusOf(theApp, "CRASHED", true) == D.crashed_after_cancel, statusOf(theApp, "CRASHED", true))
            check("FAILED after Cancel is still failed (a tool that reports its own failure has failed)", statusOf(theApp, "FAILED", true) == "failed", statusOf(theApp, "FAILED", true))
            check("COMPLETE after Cancel is still ok", statusOf(theApp, "COMPLETE", true) == "ok")
            check("CANCELED is cancelled", statusOf(theApp, "CANCELED", true) == "cancelled")
            // the host fixtures that carry the data: HRET-05 and HRET-06
            t.conformance().wrong_science.retention.fixtures_hosts.findAll { it.done_status }.each { fixture ->
                check(fixture.id + " (" + fixture.task_status + ", Cancel " + fixture.cancel_requested + ") is " + fixture.done_status,
                      statusOf(theApp, fixture.task_status as String, fixture.cancel_requested as boolean) == fixture.done_status)
            }
        },
        conformance_retention_coverage: {
            // no silent skip: every host fixture of the conformance file is run by a case file ("fixtures": [ids]), by a group of this file, or named here as not applicable
            def named = [] as Set
            new File(t.cfg.cases_dir as String).listFiles({ File f -> f.name.endsWith(".json") } as FileFilter).sort().each { File f ->
                (new JsonSlurper().parseText(f.getText("UTF-8")).fixtures ?: []).each { String id -> named << id }
            }
            def groups = ["HRET-01": "unit group retention_sixty_runs (60 runs through the host's own finishing, with the prune replaced by a recorder)"]
            def notApplicable = [:]
            t.conformance().wrong_science.retention.fixtures_hosts*.id.each { String id ->
                check(id + " is run by a case, by a unit group, or not applicable", id in named || groups.containsKey(id) || notApplicable.containsKey(id))
                if (notApplicable.containsKey(id)) unsupported << (id + " " + notApplicable[id])
            }
            ["HRET-02", "HRET-03", "HRET-04", "HRET-05", "HRET-06", "RET-10", "RET-11", "RET-14"].each { id -> check(id + " has a case file that names it", id in named) }
        },
        retention_sixty_runs: {
            // HRET-01 at the level of the host's own finishing: 60 runs, a person's own folder in each root, the prune replaced by a recorder. Nothing is deleted, every owned folder
            // is finished before the prune was started, and the number of owned folders only grows.
            def theApp = app(), out = File.createTempFile("lcprune_calls_", ".json"), recorder = recorderCommand(out.path)
            out.delete()
            lc.hooks.pruneCommand = recorder
            def before = ["results", "runs"].collectEntries { [(it): (new File(home(), it).list() ?: []).length] }
            60.times { int n ->
                def summary = [:], state = lc.newRetentionState(theApp, [id: "typed_choices"], summary)
                def run = [app: theApp, tool: [id: "typed_choices"], kept: state]
                lc.keptFolder(run)                                                      // a results folder too (a file that had to be kept)
                lc.finishRetention([:], [status: "COMPLETE", complete: true, error: null, workerOutput: [], outputs: [results: []], progress: [], cancelRequested: false, seconds: 0.1d], run, summary)
                check("run " + n + " is finished", summary.done_status == "ok" && summary.prune?.started == true, summary.toString())
            }
            ["results", "runs"].each { String root ->
                def now = (new File(home(), root).list() ?: []).length
                check(root + ": 60 more folders and none fewer", now == before[root] + 60, before[root] + " -> " + now)
                check(root + ": the person's own folders are intact", new File(home(), root).listFiles().findAll { it.name in ["20261008T120000_reanalysis_backup", "0000_keep_this_analysis"] }.every { new File(it, "x.txt").text == "keep" })
            }
            def calls = new JsonSlurper().parse(out) as List
            check("the prune was started once per run", calls.size() == 60, calls.size().toString())
            check("every owned folder was finished when each prune started", calls.every { it.unfinished == [] }, calls.find { it.unfinished }?.unfinished?.toString())
            check("the prune command is the Tools CLI of the app", calls.every { it.command[-3..-1] == ["-m", "labconstrictor_tools", "prune"] })
        },
        retention_no_host_deletes: {
            def theApp = app(), results = new File(home(), "results"), runs = new File(home(), "runs")
            [results, runs].each { File root ->
                def inside = new File(root, "20261008T120000_x"); inside.mkdirs(); new File(inside, "keep.txt").text = "keep"
                lc.removeFolder(inside, "test folder")
                check("removeFolder refuses a folder under " + root.name, new File(inside, "keep.txt").text == "keep")
                lc.removeFolder(root, "test folder")
                check("removeFolder refuses " + root.name + " itself", root.isDirectory())
            }
            lc.removeFolder(home(), "test folder")
            check("removeFolder refuses a folder that contains both roots", results.isDirectory() && runs.isDirectory())
            check("removeFolder still removes a temporary folder of its own", { File tmp = java.nio.file.Files.createTempDirectory("lcret_").toFile(); lc.removeFolder(tmp, "test folder"); !tmp.exists() }())
            // nothing else of the product deletes: every removal call of the script and of the helpers is in the list below
            def sources = [new File(t.cfg.script as String)] + new File(t.cfg.script as String).parentFile.listFiles({ File f -> f.name.endsWith(".groovy") } as FileFilter).toList() +
                          new File(new File(t.cfg.script as String).parentFile, "lib").listFiles({ File f -> f.name.endsWith(".groovy") } as FileFilter).toList()
            def removals = []
            sources.unique().each { File source ->
                source.readLines("UTF-8").eachWithIndex { String line, int i ->
                    if (line =~ /\.deleteDir\(|deleteRecursively|\.delete\(\)|Files\.delete|FileUtils|deleteOnExit|\.deleteDir/ && !line.trim().startsWith("//") && !line.trim().startsWith("*")) removals << (source.name + ": " + line.trim())
                }
            }
            def allowed = ["if (folder.exists() && !folder.deleteDir()) lcLog(", "output.delete()"]
            removals.each { String removal -> check("a removal in the product is one of the allowed ones: " + removal, allowed.any { removal.contains(it) }) }
            check("the product removes in exactly two places: its own temporary folders and the temporary output of a prune", removals.size() == 3, removals.toString())
        },
        retention_unshown_result: {
            // RET-10 / RET-14 at the level of the host's own result handling: a result whose file Fiji cannot show, and a FileOut, both inside the job folder that is removed, are
            // moved to the owned results folder and that path is reported; a file anywhere else is reported where it is and never moved.
            def theApp = app(), job = java.nio.file.Files.createTempDirectory("lcjob_fiji_test_").toFile(), elsewhere = java.nio.file.Files.createTempDirectory("lcelsewhere_").toFile()
            new File(job, "bad.tif").bytes = "this is not an image".bytes
            new File(job, "report.txt").text = "data"
            new File(elsewhere, "mine.txt").text = "mine"
            def summary = [:], state = lc.newRetentionState(theApp, [id: "t"], summary)
            def run = [app: theApp, tool: [id: "t", inputs: [], outputs: []], images: [:], frames: [:], kept: state, jobDir: job]
            def shown = lc.showResults(theApp, [[type: "image", name: "bad", path: new File(job, "bad.tif").path], [type: "file", name: "report", path: new File(job, "report.txt").path],
                                                [type: "file", name: "mine", path: new File(elsewhere, "mine.txt").path]], run)
            check("the image Fiji cannot open is said, not thrown", (shown.display_errors ?: []).any { it.contains("could not show the result 'bad'") && it.contains("the file is kept at ") }, shown.display_errors?.toString())
            def kept = lc.keptFolder(run)
            check("the results folder is owned", new File(kept, ".lc-owner.json").isFile() && !new File(kept, ".lc-done.json").exists(), kept.list().toString())
            check("the image file is there with its bytes", new File(kept, "bad.tif").text == "this is not an image")
            check("the FileOut is moved with its contents and reported at the new path", new File(kept, "report.txt").text == "data" && shown.file_report == new File(kept, "report.txt").path, shown.file_report?.toString())
            check("a file outside the job folder is reported where it is and stays", shown.file_mine == new File(elsewhere, "mine.txt").path && new File(elsewhere, "mine.txt").text == "mine" && !new File(kept, "mine.txt").exists())
            check("the paths are kept for the run record", run.kept.paths.keySet() == ["bad", "report"] as Set, run.kept.paths.toString())
            check("the reported path of the image is under the owned folder", (run.kept.paths.bad as String).startsWith(kept.path + File.separator))
            lc.removeFolder(job, "test job folder")
            check("the job folder is removed and the kept files survive", !job.exists() && new File(kept, "report.txt").isFile())
        },
        retention_lib_loading: {
            def missing = null
            try { lc.libSource("NoSuchHelper") } catch (IllegalStateException problem) { missing = problem.message }
            check("a helper that is nowhere is an error that says where it looked", missing != null && missing.contains("could not find its helper file NoSuchHelper.groovy") && missing.contains("looked in"), missing)
            check("the helpers compile and are typed classes", lc.lib("LCRetention").name == "LCRetention" && lc.lib("LCRunRecord").name == "LCRunRecord")
            def theApp = app()
            check("the Tools of the case have what the run record needs", lc.runRecordProblem(theApp) == null, lc.runRecordProblem(theApp))
            def old = lc.lib("LCRunRecord").refusal([name: "demo"], [version: "0.2.0rc1", missing: [], old: true])
            check("the refusal names the version, the minimum and the tag", old.contains("'demo' (labconstrictor-tools 0.2.0rc1)") && old.contains("0.3.0a1 or newer (v0.3.0-alpha.1)") && old.endsWith("Nothing was started."), old)
            check("a pruneless Tools is said", lc.lib("LCRetention").pruneUnavailable([features: ["reserved_inputs_v1"]]).contains("no 'prune' command") && lc.lib("LCRetention").pruneUnavailable([features: ["retention_prune"]]) == null && lc.lib("LCRetention").pruneUnavailable([:]) != null)
        },
    ]
}
