// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): the run record and the done status come from the Tools of the app, not from a
// copy of their rules written here. Fiji asks the app's own interpreter (one short Python process) for `labconstrictor_tools.runs.build_record`,
// `record_json` and `done_status` (docs/PROTOCOL.md, "A Python host that writes its own run record"). They exist from Tools 0.3.0a1 (tag v0.3.0-alpha.1);
// a Tools without them, or older than that, is refused with a sentence before a run starts, never worked around.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.TimeUnit

/** The run record and the done status of a run, produced by the Tools of the app (see the comment at the top of this file). */
class LCRunRecord {
    static final String MINIMUM_TOOLS = '0.3.0a1'                // the first version that has the three functions (v0.2.0-rc.2 has them but still says 0.2.0rc1)
    static final String MINIMUM_TAG = 'v0.3.0-alpha.1'
    static final List<String> HELPERS = ['build_record', 'record_json', 'done_status']
    static final int HELPER_TIMEOUT_S = 60                       // the helper imports the standard library and Tools' own modules only
    static final int OUTPUT_TAIL_CHARS = 600                     // what is quoted of a helper that did not answer
    static final Map<String, Map<String, Object>> CHECKED = [:]  // interpreter -> what the check found (once per session and app)

    // What the helper process does. It reads one JSON request on stdin and writes one JSON answer as the last line of stdout. Text goes through the Tools functions.
    static final String SOURCE = '''
import json, sys
request = json.loads(sys.stdin.read())
answer = {"version": None, "missing": [], "old": True}
try:
    import labconstrictor_tools
    from labconstrictor_tools import compat, runs
    version = getattr(labconstrictor_tools, "__version__", None)
    found, wanted = compat.parse_version(version), compat.parse_version(request["minimum"])
    answer["version"] = version
    answer["old"] = found is None or found < wanted
    answer["missing"] = [name for name in request["helpers"] if not hasattr(runs, name)]
except ImportError as problem:
    answer["import_error"] = str(problem)
if answer["old"] or answer["missing"] or "import_error" in answer or request["action"] == "check":
    print(json.dumps(answer))
    sys.exit(0)

class Task:
    pass

task = Task()
for key, value in request["task"].items():
    setattr(task, key, value)
content = runs.build_record(request["app"], request["tool"], request["inputs"], task, request["seconds"], request["stderr"],
                            run_id=request["run_id"], started_utc=request["started_utc"], finished_utc=request["finished_utc"])
content.update(request["extra"])
answer["status"] = runs.done_status(task.status, task.cancel_requested)
answer["record"] = runs.record_json(content)
print(json.dumps(answer))
'''

    /** One request to the helper process of the app's interpreter; the answer is the JSON object it printed last. Throws IOException when there is none. */
    static Map<String, Object> call(Map app, Map<String, Object> request, String home, List<String> scrubbed) {
        Map<String, Object> full = [minimum: MINIMUM_TOOLS, helpers: HELPERS] + request
        ProcessBuilder builder = LCRetention.pythonProcess([app.python as String, '-c', SOURCE], app, home, scrubbed)
        builder.redirectErrorStream(true)
        Process process = builder.start()
        StringBuilder output = new StringBuilder()
        Thread reader = new Thread({ -> output.append(process.inputStream.getText('UTF-8')) } as Runnable, 'lc-run-record')
        reader.daemon = true
        reader.start()
        process.outputStream.withCloseable { OutputStream stdin -> stdin.write(JsonOutput.toJson(full).getBytes('UTF-8')) }
        if (!process.waitFor(HELPER_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw new IOException('the run record helper of ' + app.name + ' did not answer within ' + HELPER_TIMEOUT_S + ' s')
        }
        reader.join(TimeUnit.SECONDS.toMillis(HELPER_TIMEOUT_S))
        String last = output.toString().readLines().findAll { String line -> line.trim() }.with { List<String> lines -> lines ? lines.last() : '' }
        try {
            return new JsonSlurper().parseText(last) as Map<String, Object>
        // groovylint-disable-next-line CatchException
        } catch (Exception problem) {      // anything that is not the helper's JSON line: the interpreter itself failed
            String tail = output.toString().trim()
            throw new IOException('the run record helper of ' + app.name + ' gave no answer (exit code ' + process.exitValue() + '): ' +
                                  tail.substring(Math.max(0, tail.length() - OUTPUT_TAIL_CHARS)), problem)
        }
    }

    /** The sentence for a Tools that cannot write run records: what it is, what is needed, what to do. */
    static String refusal(Map app, Map<String, Object> found) {
        String version = found.version ? 'labconstrictor-tools ' + found.version : 'its labconstrictor-tools (no version)'
        String lacks = found.missing ? ' It also lacks runs.' + (found.missing as List).join(', runs.') + '.' : ''
        String broken = found.import_error ? ' (' + found.import_error + ')' : ''
        return "The Tools of '" + app.name + "' (" + version + ')' + broken + ' cannot write run records: this plugin needs labconstrictor-tools ' + MINIMUM_TOOLS +
               ' or newer (' + MINIMUM_TAG + '); update the app, then run again. Nothing was started.' + lacks
    }

    /** Null when the Tools of `app` have what the run record needs, else the sentence that says it does not. Asked once per interpreter and session; a helper that
     *  cannot be run at all is an IOException (the caller refuses the run with it: the record is part of a run). */
    static String problem(Map app, String home, List<String> scrubbed) {
        String key = (app.python as String) + '|' + (app.runtime_path as String)
        Map<String, Object> found = CHECKED[key]
        if (found == null) {
            found = call(app, [action: 'check'], home, scrubbed)
            CHECKED[key] = found
        }
        boolean fine = !found.old && !found.missing && !found.import_error
        return fine ? null : refusal(app, found)
    }

    /** The content of run.json and the done status for one finished run, both produced by Tools (`build_record`, `record_json`, `done_status`).
     *  `task` carries status, error, outputs and cancel_requested; `extra` is what only the host knows (it is added to the record). Returns [status, record]. */
    static Map<String, Object> compose(Map app, Map<String, Object> request, String home, List<String> scrubbed) {
        Map<String, Object> answer = call(app, [action: 'build'] + request, home, scrubbed)
        if (answer.old || answer.missing || answer.import_error) throw new IOException(refusal(app, answer))
        return [status: answer.status, record: answer.record]
    }
}
