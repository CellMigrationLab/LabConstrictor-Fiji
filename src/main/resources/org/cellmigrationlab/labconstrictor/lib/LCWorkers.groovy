// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): the one place that knows which worker processes of this Fiji are alive (docs/SPEC_WRONG_SCIENCE.md
// section 5.5 and 5.6: one owner for the workers; no worker outlives the close). Every worker the plugin starts, for a run or for lookups, is registered here and is
// closed through here, exactly once: whoever claims a worker first closes it and nobody closes it again. A shutdown hook (Fiji quitting, a Ctrl-C of the launcher) claims
// whatever is still alive and kills it, so that no worker is left behind when Fiji goes away. Every method is typed.

/** The registry of the live workers of this Fiji (see the comment at the top of this file). */
class LCWorkers {
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
                worker.kill()
                killed++
            // groovylint-disable-next-line CatchException
            } catch (Exception problem) {          // the next worker must still be killed
                hookLog('ERROR', 'could not kill a worker at shutdown: ' + problem, problem)
            }
        }
        if (killed > 0) hookLog('WARNING', killed + ' worker(s) were still alive at shutdown and were killed', null)
        return killed
    }
}
