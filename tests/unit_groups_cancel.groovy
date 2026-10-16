// Unit-check groups of the kill-after-Cancel path of the product script (the root cause of the CI failure of cancel_after_a_kill_says_cancelled_not_crashed). NOT part of the product.
// Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox.
import org.apposed.appose.Service
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

// A task and a service that behave like the client of Appose: kill() ends the process at once, but the task learns of it on another thread, `lagMs` later.
class LaggingTask {
    volatile Service.TaskStatus status = Service.TaskStatus.RUNNING
    final List<Closure> listeners = new CopyOnWriteArrayList<Closure>()
    int cancels = 0
    void cancel() { cancels++ }
    LaggingTask listen(Closure listener) { listeners.add(listener); return this }
    /** What Appose 0.12.0 does on the end of a task: status first, the outputs, then the listeners are told (Service.Task.handle). */
    void end(Service.TaskStatus finalStatus, Service.ResponseType event) {
        status = finalStatus
        listeners.each { Closure listener -> listener(new FakeEvent(responseType: event)) }
    }
}
class FakeEvent { Service.ResponseType responseType }
/** A task whose client publishes like Appose 0.12.0: the status says COMPLETE at once, the outputs follow `publishMs` later one by one, then the listeners hear of the end. */
class SlowPublishTask extends LaggingTask {
    final Map<String, Object> outputs = new HashMap<String, Object>()      // not synchronized, like Appose's: only the end event makes the entries visible
    void publishLater(int publishMs, int entries) {
        status = Service.TaskStatus.COMPLETE
        Thread.start {
            Thread.sleep(publishMs)
            (1..entries).each { int n -> outputs.put("key" + n, n); if (n % 10 == 0) Thread.sleep(1) }
            listeners.each { Closure listener -> listener(new FakeEvent(responseType: Service.ResponseType.COMPLETION)) }
        }
    }
}
/** Starts a real process (the stand-in for a worker) that starts a child which inherits its stdout and stderr, as a tool that runs an external executable does. */
class TreeWorker {
    static final String SCRIPT = "import subprocess, sys, time\nchild = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(600)'])\n" +
        "sys.stderr.write('child=%d\\n' % child.pid); sys.stderr.flush()\nsys.stdin.read() if len(sys.argv) > 1 else time.sleep(600)\n"
    final Service service
    final List<String> output = new CopyOnWriteArrayList<String>()
    TreeWorker(String python, File script, boolean exitOnClose) {
        script.text = SCRIPT
        service = new Service(script.parentFile, [:], ([python, script.path] + (exitOnClose ? ["stdin"] : [])) as String[])
        service.debug { String line -> output.add(line) }
        service.start()
    }
    long childPid() {
        long deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            def line = output.find { String text -> text.contains("child=") }
            if (line) return (line =~ /child=(\d+)/)[0][1] as long
            Thread.sleep(50)
        }
        throw new IllegalStateException("the stand-in worker never named its child: " + output)
    }
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
        Thread.start { Thread.sleep(lagMs); lagging.end(Service.TaskStatus.CRASHED, Service.ResponseType.CRASH) }
    }
}


{ Map t ->
    def lc = t.lc, check = t.check

    return [
        kill_settles: {
            lc.hooks.cancelAfterMs = { 0L }                          // Cancel at once; the tool ignores it
            [1, 400, 1500].each { int lag ->
                def task = new LaggingTask(), service = new LaggingService(task, lag)
                Long cancelSent = lc.waitForTask(service, task, System.currentTimeMillis(), lc.watchTaskEnd(task))
                check("cancel was sent (" + lag + " ms lag)", cancelSent != null && task.cancels == 1)
                check("the worker was killed after the grace period (" + lag + " ms lag)", service.killed)
                check("the task has said it ended when the wait returns, whatever the lag (" + lag + " ms)", task.status == Service.TaskStatus.CRASHED, task.status.toString())
            }
        },
        outputs_before_the_end: {
            // Appose 0.12.0 sets COMPLETE before it fills `outputs`: the wait must not return on the status (GitHub issue 33)
            lc.hooks.cancelAfterMs = { null }
            [[300, 200], [50, 500]].each { List<Integer> shape ->
                def task = new SlowPublishTask()
                def ended = lc.watchTaskEnd(task)
                task.publishLater(shape[0], shape[1])
                lc.waitForTask(new LaggingService(task, 1), task, System.currentTimeMillis(), ended)
                check("the status read COMPLETE and the outputs are all there (" + shape + ")", task.status == Service.TaskStatus.COMPLETE && task.outputs.size() == shape[1] && ended.count == 0, task.outputs.size().toString())
            }
            // the same, many times over, with a publication a hair after the status
            int incomplete = 0
            100.times { int round ->
                def task = new SlowPublishTask()
                def ended = lc.watchTaskEnd(task)
                task.publishLater(round % 7, 100 + round)
                lc.waitForTask(new LaggingService(task, 1), task, System.currentTimeMillis(), ended)
                if (task.outputs.size() != 100 + round) incomplete++
            }
            check("100 rapid completions: every output is there when the wait returns", incomplete == 0, incomplete + " incomplete")
            // a status that says finished while the end is never signalled does not wait for ever, and is not a success
            def lost = new LaggingTask()
            lost.status = Service.TaskStatus.COMPLETE
            def lostEnded = lc.watchTaskEnd(lost)
            long begun = System.currentTimeMillis()
            lc.waitForTask(new LaggingService(lost, 1), lost, begun, lostEnded)
            long waited = System.currentTimeMillis() - begun
            check("an end that is never signalled is given up after OUTPUTS_WAIT_MS", lostEnded.count == 1 && waited >= lc.OUTPUTS_WAIT_MS && waited < lc.OUTPUTS_WAIT_MS + 3000, waited.toString())
        },
        worker_tree_is_killed: {
            // Appose 0.12.0 kill() destroys only the worker's process, and a child holding its stderr kept the task from ever ending (GitHub issue 32): run against the real Service
            def python = System.getenv("LC_PYTHON") ?: "python3"
            def folder = java.nio.file.Files.createTempDirectory("lc_tree").toFile()
            try {
                def worker = new TreeWorker(python, new File(folder, "tree_worker.py"), false)
                long child = worker.childPid()
                def task = worker.service.task("never answered")
                def ended = lc.watchTaskEnd(task)
                task.start()
                check("before the kill the child lives", ProcessHandle.of(child).map { it.isAlive() }.orElse(false))
                lc.lib("LCWorkers").killTree(worker.service)
                check("the worker is gone", !worker.service.isAlive())
                check("the child is gone with it", !ProcessHandle.of(child).map { it.isAlive() }.orElse(false))
                check("the task has said it ended, it is not left pending behind the child's open pipes", ended.await(10, java.util.concurrent.TimeUnit.SECONDS) && task.status == Service.TaskStatus.CRASHED, task.status.toString())
            } finally { folder.deleteDir() }
        },
        worker_children_do_not_outlive_a_clean_close: {
            def python = System.getenv("LC_PYTHON") ?: "python3"
            def folder = java.nio.file.Files.createTempDirectory("lc_tree").toFile()
            try {
                def worker = new TreeWorker(python, new File(folder, "tree_worker.py"), true)
                long child = worker.childPid()
                lc.lib("LCWorkers").register(worker.service)
                lc.closeWorker(worker.service)               // the worker exits on its own when its input is closed; its child does not
                check("the worker exited", !worker.service.isAlive())
                check("the child it left running is stopped too", !ProcessHandle.of(child).map { it.isAlive() }.orElse(false))
            } finally { folder.deleteDir() }
        },
    ]
}
