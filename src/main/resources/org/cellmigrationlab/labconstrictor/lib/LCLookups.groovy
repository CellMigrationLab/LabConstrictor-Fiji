// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): the ChoicesFrom lookups of one dialog (docs/SPEC_WRONG_SCIENCE.md section 5). Fiji builds a dialog
// once, before anything is typed, so a lookup has no dependency that can change under it and no reply can arrive for a form that is gone; what applies is the bounded-work and
// the owner rules: the questions of one dialog are asked ONE AT A TIME, in the order of the form, by ONE worker (5.4, 5.5: at most one in flight, one process for all of them),
// a lookup that has not answered within the time limit is cancelled and its worker is thrown away (5.4), the person's Esc cancels the lookup in flight, and the worker is closed
// (and killed when it does not leave) as soon as the last question is answered (5.6: no worker outlives the dialog). Every method is typed.
import org.apposed.appose.Service

/** The lookups of one dialog: one worker, one question at a time, bounded (see the comment at the top of this file). */
class LCLookups {
    static final int POLL_MS = 50                 // how often a lookup in flight is looked at (Esc, the time limit, the task status)
    static final int SETTLE_MS = 10000            // after a worker was killed: how long its task gets to report the end
    static final String VALUES_TYPE = 'values'    // the result type that carries the options

    /** Ask every question of `questions` (each [param, script: 'lc:<tool>', inputs, field]) of the source tools of `app`, one at a time, with one worker made by
     *  `newService` (a Closure returning a registered, not yet started Service) and closed by `finish` (a Closure that closes or kills it, once). `options`:
     *  timeoutMs, graceMs (a cancelled tool gets this long before its worker is killed), escape (Closure: true when the person pressed Esc), whileWaiting (Closure called with the
     *  milliseconds a lookup has run, at every look: a test hook), log (Closure: level, message, Throwable). Returns param -> [options: List or null, error: text or null,
     *  status: the task status, seconds, worker_started: how many workers were made so far]. A question that fails does not stop the others. */
    static Map<String, Map<String, Object>> ask(List<Map<String, Object>> questions, Map<String, Object> options, Closure newService, Closure finish) {
        Map<String, Map<String, Object>> answers = [:]
        Service service = null
        int made = 0
        try {
            for (Map<String, Object> question : questions) {
                if (service == null) {
                    service = newService() as Service
                    made++
                }
                Map<String, Object> answer = askOne(service, question, options)
                answer.worker_started = made
                answers[question.param as String] = answer
                if (answer.discard) {                      // timed out, killed or crashed: that worker is not asked again
                    finish(service)
                    service = null
                }
            }
        } finally {
            if (service != null) finish(service)
        }
        return answers
    }

    /** One question, bounded: Esc or the time limit cancels it, a tool that ignores the cancel for `graceMs` is killed with its worker (`discard`). */
    private static Map<String, Object> askOne(Service service, Map<String, Object> question, Map<String, Object> options) {
        Closure log = options.log as Closure
        Service.Task task = service.task(question.script as String, question.inputs as Map)
        long started = System.currentTimeMillis()
        String why = null
        Long cancelSent = null
        boolean killed = false
        task.start()
        while (!task.status.isFinished()) {
            long elapsed = System.currentTimeMillis() - started
            (options.whileWaiting as Closure)(elapsed)
            if (cancelSent == null && ((options.escape as Closure)() as boolean || elapsed > (options.timeoutMs as long))) {
                why = elapsed > (options.timeoutMs as long) ? 'timeout' : 'escape'
                task.cancel()
                cancelSent = System.currentTimeMillis()
            }
            if (cancelSent != null && System.currentTimeMillis() - cancelSent > (options.graceMs as long) && !task.status.isFinished()) {
                service.kill()
                killed = true
                long deadline = System.currentTimeMillis() + SETTLE_MS
                while (!task.status.isFinished() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
                break
            }
            Thread.sleep(POLL_MS)
        }
        double seconds = (System.currentTimeMillis() - started) / 1000.0d
        Service.TaskStatus status = task.status
        Map<String, Object> answer = [options: null, error: task.error, status: status.toString(), seconds: seconds, why: why, discard: killed || status == Service.TaskStatus.CRASHED]
        if (status == Service.TaskStatus.COMPLETE) answer.options = optionsOf(task.outputs, question.field as String)
        else if (why == 'timeout') log('WARNING', "the lookup of '" + question.param + "' did not answer within " + ((options.timeoutMs as long) / 1000.0d) + ' s: cancelled' + (killed ? ' and its worker was killed' : ''), null)
        return answer
    }

    /** The options a finished source tool answered (`values` result with a list under `field`), as text; null when it answered none. */
    static List<String> optionsOf(Map outputs, String field) {
        List results = (outputs?.get('results') ?: []) as List
        Map found = results.find { Object r -> r instanceof Map && (r as Map).type == VALUES_TYPE && ((r as Map).values as Map)?.get(field) instanceof List } as Map
        return found == null ? null : (((found.values as Map).get(field)) as List).collect { Object o -> o.toString() }
    }
}
