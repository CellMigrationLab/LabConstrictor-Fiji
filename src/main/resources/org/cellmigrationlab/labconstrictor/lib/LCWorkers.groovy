// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): the one place that knows which worker processes of this Fiji are alive (docs/SPEC_WRONG_SCIENCE.md
// section 5.5 and 5.6: one owner for the workers; no worker outlives the close). Every worker the plugin starts, for a run or for lookups, is registered here and is
// closed through here, exactly once: whoever claims a worker first closes it and nobody closes it again. A shutdown hook (Fiji quitting, a Ctrl-C of the launcher) claims
// whatever is still alive and kills it, so that no worker is left behind when Fiji goes away. A kill takes the processes the worker started with it: Appose 0.12.0 (the
// version Fiji declares) only destroys the worker's own process, so a tool's child (an external executable, a subprocess.Popen) would live on, and, holding the worker's
// stdout/stderr, would keep the client from ever reporting the task as ended. Every method is typed.

/** The registry of the live workers of this Fiji (see the comment at the top of this file). */
class LCWorkers {
    static final int REAP_WAIT_MS = 5000       // how long the processes a worker started get to die after destroyForcibly (a kill is not instant); then the ones left are said in the log
    static final int REAP_POLL_MS = 25
    static final Object LOCK = new Object()
    static final Set<Object> LIVE = new LinkedHashSet<Object>()    // workers started and not yet claimed (an Appose Service, or a stand-in in a test)
    static boolean hookInstalled = false
    static Closure hookLog = { String level, String message, Throwable problem -> }   // where the shutdown hook says what it did (set by the script once)

    /** Remember a worker that was just made, and make sure a shutdown hook is there to kill it if Fiji goes away first. */
    static void register(Object worker) {
        synchronized (LOCK) {
            LIVE.add(worker)
            if (!hookInstalled) {
                hookInstalled = true
                Runtime.runtime.addShutdownHook(new Thread({ -> killAll() } as Runnable, 'lc-workers-shutdown'))
            }
        }
    }

    /** Take the worker out of the registry. True for the one caller that took it (that caller closes it); false when somebody else already did (nothing to close). */
    static boolean claim(Object worker) {
        synchronized (LOCK) { return LIVE.remove(worker) }
    }

    /** Close `worker` with `closer` if this call is the one that claims it: the closer runs at most once for a worker, whoever asks and however many threads do. The
     *  closer runs outside the lock (closing a process takes time). Returns whether it ran. */
    static boolean release(Object worker, Closure closer) {
        if (!claim(worker)) return false
        closer(worker)
        return true
    }

    /** How many workers are alive and registered. */
    static int liveCount() {
        synchronized (LOCK) { return LIVE.size() }
    }

    /** Kill every worker still registered (the shutdown hook; a test). Each is claimed first, so a worker that a running thread is closing at that moment is not closed twice.
     *  Returns how many were killed. Never throws: one worker that cannot be killed must not keep the others alive. */
    static int killAll() {
        List<Object> mine
        synchronized (LOCK) {
            mine = new ArrayList<Object>(LIVE)
            LIVE.clear()
        }
        int killed = 0
        mine.each { Object worker ->
            try {
                killTree(worker)
                killed++
            // groovylint-disable-next-line CatchException
            } catch (Exception problem) {          // the next worker must still be killed
                hookLog('ERROR', 'could not kill a worker at shutdown: ' + problem, problem)
            }
        }
        if (killed > 0) hookLog('WARNING', killed + ' worker(s) were still alive at shutdown and were killed', null)
        return killed
    }

    /** The operating-system process of an Appose Service, or null for a stand-in that has none (a test double; a service that was never started). Appose 0.12.0 offers no accessor,
     *  so the private field is read; if that is refused the log says so (the worker itself is still killed, only what it started cannot be found). */
    static Process processOf(Object worker) {
        for (Class<?> type = worker.getClass(); type != null; type = type.superclass) {
            if (type.name != 'org.apposed.appose.Service') continue
            try {
                java.lang.reflect.Field field = type.getDeclaredField('process')
                field.accessible = true
                return (Process) field.get(worker)
            // groovylint-disable-next-line CatchException
            } catch (Exception problem) {          // a different Appose, or a security manager: said, not hidden
                hookLog('ERROR', 'cannot read the worker process from ' + type.name + ', so what the worker started cannot be found: ' + problem, problem)
                return null
            }
        }
        return null
    }

    /** The processes the worker has started, directly or not, right now (ProcessHandle objects). Needs Java 9+: on Java 8 there is no way to list them, which is said in the log
     *  and the list is empty. */
    static List<Object> descendantsOf(Object worker) {
        Process process = processOf(worker)
        if (process == null || !process.isAlive()) return []
        if (!process.metaClass.respondsTo(process, 'toHandle')) {
            hookLog('WARNING', 'this Java (' + System.getProperty('java.version') + ') cannot list the processes a worker started: they are not stopped with it (Java 9 or later does)', null)
            return []
        }
        List<Object> found = []
        process.toHandle().descendants().forEach { Object handle -> found.add(handle) }
        return found
    }

    /** Kill the worker and every process it started (listed before the kill: afterwards they would have been handed to init). */
    static void killTree(Object worker) {
        List<Object> started = descendantsOf(worker)
        worker.kill()
        reap(started)
    }

    /** Stop the processes of `handles` (ProcessHandle objects from `descendantsOf`) that are still alive, wait up to REAP_WAIT_MS for them to be gone and say in the log what was
     *  found: a tool that left a process running when the worker ended, and any that would not die. */
    static void reap(List<Object> handles) {
        List<Object> alive = handles.findAll { Object handle -> handle.isAlive() }
        if (alive.isEmpty()) return
        hookLog('WARNING', alive.size() + ' process(es) started by the worker were still running and are stopped: ' + alive.collect { Object handle -> handle.pid() + ' ' + handle.info().command().orElse('?') }.join(', '), null)
        alive.each { Object handle -> handle.destroyForcibly() }
        long deadline = System.currentTimeMillis() + REAP_WAIT_MS
        while (alive.any { Object handle -> handle.isAlive() } && System.currentTimeMillis() < deadline) Thread.sleep(REAP_POLL_MS)
        List<Object> left = alive.findAll { Object handle -> handle.isAlive() }
        if (!left.isEmpty()) hookLog('ERROR', left.size() + ' process(es) started by the worker are still running ' + REAP_WAIT_MS + ' ms after they were killed: ' + left.collect { Object handle -> handle.pid() }.join(', '), null)
    }
}
