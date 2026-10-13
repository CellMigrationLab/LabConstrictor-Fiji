// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): the folders under <LC_HOME>/results and <LC_HOME>/runs and the
// markers that make them removable by the Tools CLI (docs/SPEC_WRONG_SCIENCE.md section 3). Fiji NEVER deletes anything under those two
// folders: it makes a folder, writes `.lc-owner.json` first and `.lc-done.json` last, and then starts `labconstrictor-tools prune` in a
// subprocess (3.1). Only Tools prunes. Every method is typed; nothing here reads the script's globals.
import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** The folders under <LC_HOME>/results and <LC_HOME>/runs, their markers and the prune subprocess (see the comment at the top of this file). */
class LCRetention {
    static final String OWNER_FILE = '.lc-owner.json'
    static final String DONE_FILE = '.lc-done.json'
    static final String HOST = 'fiji'                         // the `host` of the owner marker (conformance.json wrong_science.retention.markers.hosts)
    static final int MARKER_VERSION = 1                       // `lc_marker`: the integer 1, not true
    static final String KIND_RESULT = 'result'
    static final String KIND_RUN = 'run'
    static final List<String> STATUSES = ['ok', 'failed', 'cancelled']
    static final String PRUNE_FEATURE = 'retention_prune'     // the `features` id of a Tools that has the `prune` command (compatibility rule 6)
    static final int RESULT_SUFFIX_DIGITS = 6                 // <YYYYMMDDTHHMMSS>_<6 hex>_<app>_<tool>
    static final int PRUNE_TIMEOUT_S = 600                    // a prune that has not ended after this is stopped (it deletes nothing half way: it renames, then removes)
    static final int PRUNE_LOG_LINES = 20                     // lines of a failing prune's output copied into the log
    static final List<String> RETENTION_ROOTS = ['results', 'runs']
    static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    static final DateTimeFormatter RESULT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)
    static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS", Locale.ROOT).withZone(ZoneOffset.UTC)
    static final SecureRandom RANDOM = new SecureRandom()

    /** Now as `YYYY-MM-DDTHH:MM:SS.mmmZ` (UTC): the `created` and `finished` of the markers, and the sort key of the quota. */
    static String nowUtc() { return UTC_MILLIS.format(java.time.Instant.now()) }

    /** `text` made safe for a folder name: runs of characters other than `A-Za-z0-9_.-` become `_`, no leading dots. */
    static String slug(String text) { return text.replaceAll(/[^A-Za-z0-9_.-]+/, '_').replaceFirst(/^\.+/, '') ?: 'x' }

    /** The name of a result folder: `<YYYYMMDDTHHMMSS>_<6 hex>_<app>_<tool>` (3.2, the CLI's naming). */
    static String resultFolderName(String app, String tool) {
        String suffix = String.format(Locale.ROOT, '%0' + RESULT_SUFFIX_DIGITS + 'x', RANDOM.nextInt(1 << (RESULT_SUFFIX_DIGITS * 4)))
        return RESULT_STAMP.format(java.time.Instant.now()) + '_' + suffix + '_' + slug(app) + '_' + slug(tool)
    }

    /** The name of a run record folder: the millisecond stamp Fiji has always used, then the app and the tool (any slug is allowed for run records). */
    static String runFolderName(String app, String tool) {
        return RUN_STAMP.format(java.time.Instant.now()) + '_' + slug(app) + '_' + slug(tool)
    }

    /** Write `.lc-owner.json` into a folder that was just made (3.2): the first thing in it. Returns the marker. */
    static Map<String, Object> writeOwner(File folder, String kind) {
        Map<String, Object> marker = [lc_marker: MARKER_VERSION, kind: kind, name: folder.name, host: HOST, pid: ProcessHandle.current().pid(),
                                      created: nowUtc(), token: UUID.randomUUID().toString()]
        new File(folder, OWNER_FILE).setText(JsonOutput.toJson(marker), 'UTF-8')
        return marker
    }

    /** Write `.lc-done.json` atomically (a temporary file in the folder, then a rename): the last thing in the folder. A status that is not ok, failed or
     *  cancelled is refused. Where the file system cannot rename atomically the exception stands: the folder then stays unfinished and is never removed. */
    static void writeDone(File folder, String status) {
        if (!(status in STATUSES)) throw new IllegalArgumentException('the done status must be one of ' + STATUSES.join(', ') + ', got ' + status)
        File temporary = new File(folder, DONE_FILE + '.tmp-' + ProcessHandle.current().pid())
        temporary.setText(JsonOutput.toJson([finished: nowUtc(), status: status]), 'UTF-8')
        Files.move(temporary.toPath(), new File(folder, DONE_FILE).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Make the folder `name` directly under `root` and write its owner marker. The folder must not exist (an existing one would be marked as ours). */
    static File makeOwnedFolder(File root, String name, String kind) {
        root.mkdirs()
        File folder = new File(root, name)
        if (folder.canonicalFile.parentFile != root.canonicalFile) throw new IOException('the folder ' + folder + ' is not directly inside ' + root)
        if (!folder.mkdir()) throw new IOException('could not make ' + folder + ' (it exists, or the folder above is not writable)')
        writeOwner(folder, kind)
        return folder
    }

    /** A result folder of one run: `<home>/results/<YYYYMMDDTHHMMSS>_<6 hex>_<app>_<tool>`, owned. */
    static File makeResultFolder(File home, String app, String tool) {
        return makeOwnedFolder(new File(home, 'results'), resultFolderName(app, tool), KIND_RESULT)
    }

    /** The run record folder of one run: `<home>/runs/<stamp>_<app>_<tool>`, owned. */
    static File makeRunFolder(File home, String app, String tool) {
        return makeOwnedFolder(new File(home, 'runs'), runFolderName(app, tool), KIND_RUN)
    }

    /** Whether `folder` is `<home>/results` or `<home>/runs`, lies inside one of them, or contains one of them (real paths: a link does not hide it). Nothing of
     *  the host's may delete such a folder (3.1.1): the host's own temporary folders live outside <home>. */
    static boolean touchesRetentionRoots(File home, File folder) {
        Path target = folder.canonicalFile.toPath()
        return RETENTION_ROOTS.any { String name ->
            Path root = new File(home, name).canonicalFile.toPath()
            return target.startsWith(root) || root.startsWith(target)
        }
    }

    /** Why `prune` cannot be started for an app whose schema says `schema` (compatibility rule 6 of docs/PROTOCOL.md): its Tools has no `retention_prune`
     *  feature. Null when it can. The host never deletes the folders itself instead: they stay until Tools is updated. */
    static String pruneUnavailable(Map schema) {
        Object features = schema?.get('features')
        if (features instanceof List && (features as List).contains(PRUNE_FEATURE)) return null
        return "the Tools of this app have no 'prune' command (they list no '" + PRUNE_FEATURE + "' feature); the folders under results/ and runs/ are not cleaned up until labconstrictor-tools is updated"
    }

    /** The command that starts the Tools CLI's prune with the app's own interpreter (the environment that runs its tools). */
    static List<String> pruneCommand(Map app) {
        return [app.python as String, '-m', 'labconstrictor_tools', 'prune']
    }

    /** A process of the app's interpreter with the environment of its worker (never the host's Python or Qt variables), LC_HOME set to the home the host uses. */
    static ProcessBuilder pythonProcess(List<String> command, Map app, String home, List<String> scrubbed) {
        ProcessBuilder builder = new ProcessBuilder(command)
        Map<String, String> environment = builder.environment()
        environment.putAll([PYTHONPATH: app.runtime_path as String, PYTHONNOUSERSITE: '1', PYTHONSAFEPATH: '1', PYTHONIOENCODING: 'utf-8', LC_HOME: home])
        scrubbed.each { String name -> environment.remove(name) }
        builder.directory(new File(System.getProperty('user.dir')))
        return builder
    }

    /** Start `command` (the prune) and do not wait for it: a daemon thread watches it, ignores the exit code and writes a failure to the log through `log`
     *  (level, message, problem). Returns the watching thread, or null when the process could not be started (said in the log). Never throws. */
    static Thread startPrune(List<String> command, Map<String, String> environment, File directory, Closure log) {
        File output = Files.createTempFile('lcprune_', '.txt').toFile()
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
            builder.environment().putAll(environment)
            builder.directory(directory)
            builder.redirectErrorStream(true)
            builder.redirectOutput(output)
            Process process = builder.start()
            Thread watcher = new Thread({ -> watchPrune(process, output, command, log) } as Runnable, 'lc-prune')
            watcher.daemon = true
            watcher.start()
            return watcher
        } catch (IOException | SecurityException problem) {
            output.delete()
            log('WARNING', 'could not start prune (' + command.join(' ') + '): ' + problem, problem)
            return null
        }
    }

    /** The watching thread of a prune: waits (at most PRUNE_TIMEOUT_S), reads what it printed, logs a failure, removes its own temporary output file. */
    private static void watchPrune(Process process, File output, List<String> command, Closure log) {
        try {
            if (!process.waitFor(PRUNE_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                log('WARNING', 'prune did not end within ' + PRUNE_TIMEOUT_S + ' s and was stopped', null)
                return
            }
            if (process.exitValue() != 0) {
                List<String> lines = output.readLines('UTF-8')
                log('WARNING', 'prune failed (exit code ' + process.exitValue() + '): ' + command.join(' ') + '\n' + lines.takeRight(PRUNE_LOG_LINES).join('\n'), null)
            }
        } catch (InterruptedException | IOException problem) {
            log('WARNING', 'could not watch prune: ' + problem, problem)
        } finally {
            output.delete()
        }
    }
}
