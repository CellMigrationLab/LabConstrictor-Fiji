// Unit-check groups of the kill-after-Cancel path of the product script (the root cause of the CI failure of cancel_after_a_kill_says_cancelled_not_crashed). NOT part of the product.
// Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox.
import org.apposed.appose.Service

// A task and a service that behave like the client of Appose: kill() ends the process at once, but the task learns of it on another thread, `lagMs` later.
class LaggingTask {
    volatile Service.TaskStatus status = Service.TaskStatus.RUNNING
    int cancels = 0
    void cancel() { cancels++ }
}
class LaggingService extends Service {
    LaggingTask lagging
    int lagMs
    volatile boolean killed = false
    LaggingService(LaggingTask task, int lagMs) {
        super(new File(System.getProperty("user.dir")), [:], ["unused"] as String[])      // never started: nothing is run
        this.lagging = task
        this.lagMs = lagMs
    }
    @Override
    void kill() {
        killed = true
        Thread.start { Thread.sleep(lagMs); lagging.status = Service.TaskStatus.CRASHED }
    }
}


{ Map t ->
    def lc = t.lc, check = t.check

    return [
        kill_settles: {
            lc.hooks.cancelAfterMs = { 0L }                          // Cancel at once; the tool ignores it
            [1, 400, 1500].each { int lag ->
                def task = new LaggingTask(), service = new LaggingService(task, lag)
                Long cancelSent = lc.waitForTask(service, task, System.currentTimeMillis())
                check("cancel was sent (" + lag + " ms lag)", cancelSent != null && task.cancels == 1)
                check("the worker was killed after the grace period (" + lag + " ms lag)", service.killed)
                check("the task has said it ended when the wait returns, whatever the lag (" + lag + " ms)", task.status == Service.TaskStatus.CRASHED, task.status.toString())
            }
        },
    ]
}
