// Fiji > Plugins > LabConstrictor : run ANY installed LabConstrictor app's tools.
//
//   registry JSON  ->  chooser dialogs  ->  tool schema (cached JSON)  ->  SciJava module items  ->  Fiji's own input harvester
//   -> Appose Java client (bundled with Fiji)  ->  `python -m labconstrictor_tools serve` in the app's interpreter  ->  typed results
//
// There is no application-specific code here. Discovery needs no Python: ~/.labconstrictor/apps/*.json (+ *.schema.json).
// Automated tests plug in through `hooks` (see test_harness.groovy); in normal use every hook is a no-op.
#@ ModuleService moduleService

import groovy.json.JsonSlurper
import groovy.transform.Field
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.io.FileSaver
import ij.measure.ResultsTable
import ij.process.ColorProcessor
import ij.process.FloatProcessor
import org.apposed.appose.Service
import org.scijava.module.DefaultMutableModule
import org.scijava.module.DefaultMutableModuleInfo
import org.scijava.module.DefaultMutableModuleItem
import org.scijava.module.MutableModuleInfo
import java.nio.file.Files

@Field final int CANCEL_GRACE_MS = 3000          // how long a tool gets to honour Cancel before its worker is killed
@Field final String JOB_DIR_KEY = "_job_dir"     // reserved input: host-owned directory for outputs
@Field final String TOOL_PREFIX = "lc:"
@Field final List<Integer> SUPPORTED_PROTOCOLS = [1]
@Field final int EXIT_WAIT_MS = 15000            // heavy interpreters (torch, numba) need a few seconds to exit after stdin closes

/** Module whose callbacks keep a calibration field in sync with the image chosen for it (schema: pixel_size_of). */
class LCModule extends DefaultMutableModule {
    Map<String, List<String>> links = [:]     // image parameter -> [pixel-size parameters]
    LCModule(MutableModuleInfo info) { super(info) }
    void run() {}                              // all work happens after harvesting

    private static final Map<String, Double> MICRONS_PER_UNIT = [um: 1.0d, micron: 1.0d, microns: 1.0d, micrometer: 1.0d, micrometre: 1.0d,
                                                                  nm: 1e-3d, nanometer: 1e-3d, mm: 1e3d, millimeter: 1e3d, cm: 1e4d, m: 1e6d]
    /** Pixel size of an image in micrometres, or null when uncalibrated or in a unit we cannot convert (pixel, inch, ...). */
    static Double micronsPerPixel(ImagePlus imp) {
        def calibration = imp?.getCalibration()
        if (calibration == null || !calibration.scaled()) return null
        def unit = (calibration.getUnit() ?: "").toLowerCase().replace("\u00b5", "u").replace("\u03bc", "u").replace("\ufffd", "u")
        def factor = MICRONS_PER_UNIT[unit]
        return factor == null ? null : calibration.pixelWidth * factor
    }
    /** Pixel size (um) stored in a TIFF file's header (ImageJ description or TIFF resolution tags), without loading the pixels. */
    static Double micronsFromFile(File file) {
        try {
            def info = new ij.io.TiffDecoder(file.parent + File.separator, file.name).getTiffInfo()
            if (!info || info[0].pixelWidth <= 0 || info[0].pixelWidth == 1.0d && !info[0].description) return null
            def unit = (info[0].description =~ /(?m)^unit=(.*)$/).with { it.find() ? it.group(1) : (info[0].unit ?: "") }
            unit = unit.toLowerCase().replace("\u00b5", "u").replace("\u03bc", "u").replace("\ufffd", "u").trim()
            def factor = MICRONS_PER_UNIT[unit] ?: (unit == "cm" ? 1e4d : unit == "inch" ? 25400.0d : null)
            return factor == null ? null : info[0].pixelWidth * factor
        } catch (Throwable ignored) { return null }
    }
    // SciJava resolves callbacks by method name, so each linked image parameter gets a fixed slot (max 8 per tool).
    void syncImage0() { sync(0) }
    void syncFile0() { syncFromFile(0) }
    void syncImage1() { sync(1) }
    void syncFile1() { syncFromFile(1) }
    void syncImage2() { sync(2) }
    void syncFile2() { syncFromFile(2) }
    void syncImage3() { sync(3) }
    void syncFile3() { syncFromFile(3) }
    void syncImage4() { sync(4) }
    void syncFile4() { syncFromFile(4) }
    void syncImage5() { sync(5) }
    void syncFile5() { syncFromFile(5) }
    void syncImage6() { sync(6) }
    void syncFile6() { syncFromFile(6) }
    void syncImage7() { sync(7) }
    void syncFile7() { syncFromFile(7) }
    private void syncFromFile(int slot) {
        def imageName = links.keySet().toList()[slot]
        def file = getInput(imageName + "_file") as File
        def microns = file ? micronsFromFile(file) : null
        if (microns != null) links[imageName].each { setInput(it, microns) }
    }
    private void sync(int slot) {
        def imageName = links.keySet().toList()[slot]
        def imp = getInput(imageName) as ImagePlus
        def microns = micronsPerPixel(imp)
        if (microns != null) links[imageName].each { setInput(it, microns) }
    }
}

// ---------------------------------------------------------------- hooks (no-ops unless a harness is supplied)
hooks = [
    interactive  : true,          // false: never block on modal error dialogs
    overrides    : [:],           // parameter name -> value used as dialog default
    preferred    : { String kind -> null },
    setup        : { },
    beforeDialog : { String title -> },
    cancelAfterMs: { null },
    finish       : { Map summary -> },
]
def harnessPath = System.getenv("LC_FIJI_HARNESS")
if (harnessPath) hooks += new GroovyShell(this.class.classLoader).evaluate(new File(harnessPath)) as Map

// ---------------------------------------------------------------- discovery (same rules as labconstrictor_tools.registry)
String lcHome() { System.getenv("LC_HOME") ?: (System.getProperty("user.home") + "/.labconstrictor") }

/** Append one line to the log shared with the Python tools (<LC_HOME>/logs/labconstrictor.log). Never throws. */
void lcLog(String level, String message, Throwable problem = null) {
    try {
        def file = new File(lcHome(), "logs/labconstrictor.log")
        file.parentFile.mkdirs()
        if (file.length() > 1_000_000L) file.renameTo(new File(file.parentFile, "labconstrictor.log.1"))
        def stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())
        def text = stamp + " " + level.padRight(7) + " pid=" + ProcessHandle.current().pid() + " fiji: " + message.replace("\r", "") + "\n"
        if (problem) { def w = new StringWriter(); problem.printStackTrace(new PrintWriter(w)); text += w.toString() }
        file.append(text, "UTF-8")
    } catch (Throwable ignored) { }
}

/** Directories searched, highest priority first: per-user, LC_APPS_PATH, system-wide. */
List<File> registryDirs() {
    def dirs = [new File(lcHome(), "apps")]
    (System.getenv("LC_APPS_PATH") ?: "").split(File.pathSeparator).findAll { it }.each { dirs << new File(it) }
    def os = System.getProperty("os.name").toLowerCase()
    dirs << (os.contains("win") ? new File((System.getenv("PROGRAMDATA") ?: "C:\\ProgramData") + "\\LabConstrictor\\apps")
             : os.contains("mac") ? new File("/Library/Application Support/LabConstrictor/apps") : new File("/etc/labconstrictor/apps"))
    return dirs
}

/** Why an entry must not be used to start a process, or null. */
String untrustedReason(File entryFile, Map entry, boolean userDir) {
    try {
        // lexical containment (symlinks inside the prefix are normal: a venv's python links to the base interpreter)
        def python = new File(entry.python).absoluteFile.toPath().normalize(), prefix = new File(entry.prefix).absoluteFile.toPath().normalize()
        if (!python.startsWith(prefix))
            return "interpreter " + entry.python + " is not inside the install prefix " + entry.prefix
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            def path = entryFile.toPath()
            def owner = java.nio.file.Files.getOwner(path).name
            // per-user entries must be ours; entries in shared folders (LC_APPS_PATH, /etc) may also belong to root (the administrator)
            if (owner != System.getProperty("user.name") && !(owner == "root" && !userDir))
                return "entry file is not owned by the current user" + (userDir ? "" : " or root")
            def permissions = java.nio.file.Files.getPosixFilePermissions(path)
            if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
                return "entry file is writable by other users"
        }
    } catch (Exception ignored) { }          // e.g. a file system without POSIX permissions
    return null
}

Map discoverApps() {
    def slurper = new JsonSlurper()
    def apps = [:], problems = [], skip = [] as Set
    registryDirs().eachWithIndex { File dir, int index ->
        def files = dir.listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter)
        (files ?: [] as File[]).sort().each { File file ->
            def name = file.name.replaceAll(/\.json$/, "")
            if (apps.containsKey(name) || skip.contains(name)) return          // a higher-priority directory already provided it
            try {                                                            // one broken app must not hide the others
                def entry = slurper.parseText(file.text)
                name = entry.name
                if (apps.containsKey(name) || skip.contains(name)) return          // the file name differs from the app name it claims: priority stays with the earlier directory
                if (!new File(entry.python).exists()) { problems << (name + ": not available on this machine (interpreter " + entry.python + " is missing)"); skip << name; return }
                def reason = untrustedReason(file, entry, index == 0)
                if (reason) { problems << (name + ": ignored: " + reason); skip << name; return }
                def schema = slurper.parseText(new File(entry.schema_path).text)
                if (!(schema.protocol in SUPPORTED_PROTOCOLS)) throw new IllegalStateException("schema protocol " + schema.protocol + " is not supported")
                apps[name] = entry + [schema: schema]
            } catch (Exception e) {
                problems << (file.name + ": " + e.message)
            }
        }
    }
    if (problems) { IJ.log("LabConstrictor: skipped " + problems.join("; ")); problems.each { lcLog("WARNING", "app skipped: " + it) } }
    return [home: lcHome(), apps: apps, problems: problems]
}

/** One folder per run under <home>/runs (newest 50 kept): what was run, by which interpreter, what came back. */
String writeRunRecord(Map app, Map tool, Map inputs, Map outcome, Map summary) {
    try {
        def runs = new File(lcHome(), "runs")
        def stamp = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmssSSS").format(new Date())
        def folder = new File(runs, stamp + "_" + app.name + "_" + tool.id)
        folder.mkdirs()
        new File(folder, "run.json").text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
            app: app.name, app_version: app.version, tool: tool.id, host: "fiji", status: outcome.status, error: outcome.error,
            seconds: outcome.seconds, inputs: inputs, worker_output_tail: outcome.workerOutput, log_file: new File(lcHome(), "logs/labconstrictor.log").path, progress_events: outcome.progress, interpreter: outcome.outputs?.diagnostics,
            results: (outcome.outputs?.results ?: []).collect { it.findAll { k, v -> k != "matrix_yx" } }]))
        (runs.listFiles({ File f -> f.isDirectory() } as FileFilter) ?: [] as File[]).sort().reverse().drop(50).each { it.deleteDir() }
        return folder.path
    } catch (Exception ignored) { return null }       // a log must never break a run
}

// ---------------------------------------------------------------- SciJava helpers
MutableModuleInfo newInfo(String title) {
    def info = new DefaultMutableModuleInfo()
    info.setModuleClass(LCModule)
    info.setLabel(title)
    info.setName(title)
    return info
}

def addItem(MutableModuleInfo info, String name, Class type, Map o) {
    def item = new DefaultMutableModuleItem(info, name, type)
    item.setLabel(o.label ?: name)
    if (o.description) item.setDescription(o.description)
    item.setRequired(o.required != false)
    if (o.default != null) item.setDefaultValue(o.default)
    if (o.min != null) item.setMinimumValue(o.min)
    if (o.max != null) item.setMaximumValue(o.max)
    if (o.step != null) item.setStepSize(o.step)
    if (o.choices) item.setChoices(o.choices)
    if (o.callback) item.setCallback(o.callback)
    if (o.message) item.setVisibility(org.scijava.ItemVisibility.MESSAGE)
    info.addInput(item)
    return item
}

/** Show Fiji's native input dialog for `info`; returns the harvested module, or null if cancelled. */
def harvest(MutableModuleInfo info, String title, Map links = [:]) {
    hooks.beforeDialog(title)
    def module = new LCModule(info)
    module.links = links
    return moduleService.run(module, true).get()
}

/** Ask the user to pick one of `choices`; a single choice needs no dialog (and avoids SciJava's single-input prompt). */
String pickOne(String title, String label, List<String> choices, String preferred) {
    if (choices.size() == 1) return choices[0]
    def info = newInfo(title)
    addItem(info, "choice", String, [label: label, choices: choices, default: choices.find { it.equalsIgnoreCase(preferred ?: "") } ?: choices[0]])
    return harvest(info, title)?.getInput("choice")
}

// ---------------------------------------------------------------- schema -> dialog
/** Build the tool dialog from the schema. Returns [info, links]. */
List buildToolDialog(Map tool, List<String> openImages) {
    def info = newInfo(tool.label)
    def links = [:]                                          // image param -> [pixel-size params]
    tool.inputs.findAll { it.pixel_size_of }.each { links.get(it.pixel_size_of, []) << it.name }
    def linkedImages = links.keySet().toList()
    def imageIndex = 0                                       // i-th image parameter defaults to the i-th open image
    if (openImages && tool.inputs.any { it.type in ["image", "labels"] }) {
        // also keeps SciJava from replacing a one-image dialog by a bare file chooser (the open image is auto-resolved)
        addItem(info, "image_source_note", String, [label: "Image source", message: true, required: false,
                                                    default: "Images are taken from the open windows. To use a file instead, choose it in the matching '(or file)' field."])
    }
    tool.inputs.each { p ->
        def base = [label: p.label + (p.unit ? " (" + p.unit + ")" : ""), description: p.description, required: p.required]
        if (p.nullable && p.type in ["string", "integer", "float", "choice"]) {   // optional with no default: "unset" must stay possible
            addItem(info, "set_" + p.name, Boolean, [label: "Set " + p.label.toLowerCase(), required: false,
                                                     default: hooks.overrides.containsKey("set_" + p.name) ? hooks.overrides["set_" + p.name] : false])
        }
        def overridden = hooks.overrides.containsKey(p.name)
        def override = overridden ? hooks.overrides[p.name] : null
        switch (p.type) {
            case ["image", "labels"]:
                def slot = linkedImages.indexOf(p.name)
                def fileOverride = hooks.overrides[p.name + "_file"]
                if (openImages) {
                    def imp = WindowManager.getImage(override ?: openImages[Math.min(imageIndex++, openImages.size() - 1)])
                    if (!p.required) {                       // SciJava image choosers cannot be empty
                        addItem(info, "use_" + p.name, Boolean, [label: "Use " + p.label.toLowerCase(), required: false,
                                                                 default: hooks.overrides.containsKey("use_" + p.name) ? hooks.overrides["use_" + p.name] : false])
                    }
                    addItem(info, p.name, ImagePlus, base + [default: imp, callback: slot >= 0 ? "syncImage" + slot : null])
                    addItem(info, p.name + "_file", File, [label: p.label + " (or file)", required: false,
                                                           description: "Read the image from a file instead of the open image above (leave empty to use the open image)",
                                                           default: fileOverride ? new File(fileOverride as String) : null,
                                                           callback: slot >= 0 ? "syncFile" + slot : null])
                } else {                                     // nothing open: the file is the only way to give an image
                    addItem(info, p.name + "_file", File, base + [description: p.description ?: "No image is open - choose a file",
                                                                  default: fileOverride ? new File(fileOverride as String) : null,
                                                                  callback: slot >= 0 ? "syncFile" + slot : null])
                }
                break
            case ["table", "file"]:
                addItem(info, p.name, File, base + [default: override ? new File(override as String) : null]); break
            case "folder":
                addItem(info, p.name, File, base + [default: override ? new File(override as String) : null]).setWidgetStyle("directory"); break
            case "string":
                addItem(info, p.name, String, base + [default: override ?: p.default ?: ""]); break
            case "boolean":
                addItem(info, p.name, Boolean, base + [default: overridden ? override : (p.default ?: false)]); break
            case "choice":
                addItem(info, p.name, String, base + [choices: p.choices, default: override ?: p.default ?: p.choices[0]]); break
            case "integer":
                addItem(info, p.name, Integer, base + [default: (overridden ? override : (p.default ?: 0)) as Integer,
                                                      min: p.minimum as Integer, max: p.maximum as Integer, step: 1]); break
            case "float":
                def value = overridden ? override : p.default
                def source = p.pixel_size_of && openImages ? WindowManager.getImage(hooks.overrides[p.pixel_size_of] ?: openImages[0]) : null
                def microns = LCModule.micronsPerPixel(source)
                def fileSource = p.pixel_size_of ? hooks.overrides[p.pixel_size_of + "_file"] : null
                if (microns == null && fileSource) microns = LCModule.micronsFromFile(new File(fileSource as String))
                if (microns != null) value = microns                                                      // calibration prefill (unit-aware)
                addItem(info, p.name, Double, base + [default: (value ?: 0) as Double, min: p.minimum as Double, max: p.maximum as Double, step: 0.0001d])
                break
        }
    }
    return [info, links]
}

// ---------------------------------------------------------------- dialog -> request
/** Harvested values -> worker inputs. Images are saved as TIFF (calibration travels as explicit parameters). */
List exportInputs(Map tool, def module, File jobDir) {
    def inputs = [:], images = [:]
    tool.inputs.each { p ->
        def value = module.getInput(p.name)
        switch (p.type) {
            case ["image", "labels"]:
                def chosen = module.getInput(p.name + "_file") as File
                if (chosen) {                                                // a file wins over the open image: the worker reads it directly
                    if (!chosen.isFile()) throw new IllegalArgumentException("'" + p.label + "': file not found: " + chosen.path)
                    inputs[p.name] = chosen.path
                    images[p.name] = chosen.path                              // opened later only if a result needs it (see asImage)
                    break
                }
                if (!p.required && !module.getInput("use_" + p.name)) break
                if (!(value instanceof ImagePlus)) throw new IllegalArgumentException("'" + p.label + "' is required: open an image or choose a file")
                def file = new File(jobDir, p.name + ".tif")
                def imp = value as ImagePlus
                if (p.axes == "YX" && imp.getStackSize() > 1) {          // tool wants one plane: send the one on screen
                    IJ.log("LabConstrictor: '" + p.label + "' needs a single 2D plane - using the current plane (" + imp.getCurrentSlice() + " of " + imp.getStackSize() + ")")
                    imp = new ImagePlus(imp.getTitle(), imp.getProcessor().duplicate())
                    imp.setCalibration(value.getCalibration())
                }
                new FileSaver(imp).saveAsTiff(file.path)
                inputs[p.name] = file.path
                images[p.name] = value as ImagePlus
                break
            case ["table", "file"]:
                if (value) inputs[p.name] = (value as File).path
                break
            case "folder":
                if (value) {
                    if (!(value as File).isDirectory()) throw new IllegalArgumentException("'" + p.label + "': folder not found: " + (value as File).path)
                    inputs[p.name] = (value as File).path
                }
                break
            default:
                if (p.nullable && !module.getInput("set_" + p.name)) break      // unset: the tool receives None
                if (value != null) inputs[p.name] = value
        }
    }
    inputs[JOB_DIR_KEY] = jobDir.path
    return [inputs, images]
}

// ---------------------------------------------------------------- run
/** Run one task through Appose; Esc (or the harness) requests cancel, a tool that ignores it is killed. */
Map runTool(Map app, Map tool, Map inputs) {
    def command = [app.python, "-m", "labconstrictor_tools", "serve", "--module", app.module] + app.pythonpath.collectMany { ["--pythonpath", it] }
    def env = [PYTHONPATH: app.runtime_path, PYTHONNOUSERSITE: "1", PYTHONIOENCODING: "utf-8", PYTHONUNBUFFERED: "1"]
    def service = new Service(new File(app.prefix), env, command as String[])
    def workerOutput = []                                    // the worker's stderr: tracebacks, import errors, native crashes
    service.debug { String line -> workerOutput << line; lcLog("DEBUG", "worker " + line) }
    lcLog("INFO", "starting worker for " + app.name + " python=" + app.python + " module=" + app.module + " tool=" + tool.id)
    def progress = []
    def task = service.task(TOOL_PREFIX + tool.id, inputs)
    task.listen { event ->
        if (event.responseType == Service.ResponseType.UPDATE) {
            progress << [event.message, event.current, event.maximum]
            IJ.showStatus("LabConstrictor: " + (event.message ?: ""))
            if (event.maximum > 0) IJ.showProgress(event.current / (double) event.maximum)
        }
    }
    task.start()
    IJ.resetEscape()
    def started = System.currentTimeMillis()
    def cancelAfter = hooks.cancelAfterMs()
    Long cancelSent = null
    while (!task.status.isFinished()) {
        if (cancelSent == null && (IJ.escapePressed() || (cancelAfter != null && System.currentTimeMillis() - started > cancelAfter))) {
            task.cancel()
            cancelSent = System.currentTimeMillis()
            IJ.showStatus("LabConstrictor: cancelling...")
        }
        if (cancelSent != null && System.currentTimeMillis() - cancelSent > CANCEL_GRACE_MS && !task.status.isFinished()) {
            service.kill()                                   // the tool ignored Cancel
            break
        }
        Thread.sleep(50)
    }
    IJ.showProgress(1.0)
    service.close()
    waitForExit(service, EXIT_WAIT_MS)
    def complete = task.status == Service.TaskStatus.COMPLETE
    if (complete) lcLog("INFO", "task COMPLETE tool=" + tool.id + " timings=" + task.outputs?.timings)
    else lcLog("ERROR", "task " + task.status + " tool=" + tool.id + " error=" + (task.error ?: "") + (workerOutput ? "\n--- worker output ---\n" + workerOutput.takeRight(40).join("\n") : ""))
    return [status: task.status.toString(), complete: complete, error: task.error, workerOutput: workerOutput.takeRight(60),
            outputs: task.outputs, progress: progress, workerAlive: service.isAlive(), cancelRequested: cancelSent != null,
            seconds: (System.currentTimeMillis() - started) / 1000.0]
}

/** One line saying the likely cause of a worker that died (same wording as labconstrictor_tools.log.hint_for_exit). */
String crashHint(Map outcome) {
    if (outcome.cancelRequested) return "The tool did not stop when Cancel was pressed, so its worker was stopped."
    def exit = (outcome.error =~ /exit code (-?\d+)/).with { it.find() ? it.group(1) as Long : null }
    def text = (outcome.error ?: "") + "\n" + (outcome.workerOutput ?: []).join("\n")
    if (text.contains("ModuleNotFoundError") || text.contains("ImportError"))
        return "A Python package is missing or broken in the app's environment (see the traceback below)."
    if (exit in [-9L, 137L]) return "The worker was killed (out of memory? the OS OOM killer ends big image jobs this way)."
    if (exit in [-11L, 139L, 3221225477L]) return "The worker crashed natively (segmentation fault in a compiled library)."
    if (exit == 3L) return "The app's tool module failed to import (see the traceback below)."
    return "The worker process stopped unexpectedly."
}

/** Text of the error dialog for a failed or crashed run: what happened, the worker's own output, where the details are. */
String failureMessage(Map outcome, Map summary) {
    def worker = (outcome.workerOutput ?: []).findAll { !it.startsWith("[SERVICE-0]") }       // protocol chatter is in the log, not for people
    return (outcome.status == "CRASHED" ? crashHint(outcome) + "\n\n" : "") + (outcome.error ?: "failed") +
           (worker ? "\n\nWorker output (last lines):\n" + worker.takeRight(8).join("\n") : "") +
           "\n\nRun record: " + (summary.run_record ?: "(none)") + "\nLog file: " + new File(lcHome(), "logs/labconstrictor.log").path
}

/** After close(): wait for the worker to exit on its own, then kill it so no process is ever left behind. */
void waitForExit(Service service, int timeoutMs) {
    def deadline = System.currentTimeMillis() + timeoutMs
    while (service.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    if (service.isAlive()) service.kill()
}

// ---------------------------------------------------------------- results (switch on result type only)
Map showResults(Map app, List results, Map images) {
    def summary = [:]
    results.each { r ->
        switch (r.type) {
            case ["image", "labels"]: showImage(app, r, summary); break
            case "table": showTable(r, summary); break
            case "values": IJ.log(app.display_name + " " + r.name + ": " + r.values); summary["values_" + r.name] = r.values; break
            case "file": IJ.log("Output file: " + r.path); break
            case "affine": showAffine(r, images, results, summary); break
        }
    }
    return summary
}

void showImage(Map app, Map r, Map summary) {
    def imp = IJ.openImage(r.path)
    if (imp == null) { IJ.error("LabConstrictor", "Fiji cannot open result image " + r.name); return }
    def title = app.name + ":" + r.name
    for (int n = 1; WindowManager.getImage(title) != null; n++) title = app.name + ":" + r.name + " [" + n + "]"   // a chain must be able to tell results apart
    imp.setTitle(title)
    imp.show()
    summary["image_" + r.name] = [imp.getWidth(), imp.getHeight(), imp.getNSlices() * imp.getNFrames(), imp.getBitDepth()]
}

void showTable(Map r, Map summary) {
    def table = ResultsTable.open(r.path)
    table.show(r.name)
    summary["table_" + r.name] = [rows: table.size(), cols: table.getHeadings() as List, first: table.getRowAsString(0)]
}

/** Resample `source` into the `target` frame with the returned matrix (maps source px -> target px) and show an overlay. */
ImagePlus asImage(def image) {                          // inputs given as files are opened only when a result needs them
    if (image instanceof ImagePlus) return image
    def imp = IJ.openImage(image as String)
    if (imp == null) throw new IllegalStateException("Fiji cannot open " + image)
    return imp
}

void showAffine(Map r, Map images, List results, Map summary) {
    def source = asImage(images[r.apply_to]), target = asImage(images[r.relative_to ?: r.apply_to])
    def warped = resample(source, target.getWidth(), target.getHeight(), r.matrix_yx)
    summary.affine_matrix = r.matrix_yx
    def workerWarp = results.find { it.type == "image" }      // only used to cross-check Fiji's own application
    if (workerWarp) summary.fiji_warp_vs_worker_warp_rel_diff = relativeDifference(warped, IJ.openImage(workerWarp.path).getProcessor().convertToFloat())
    def gray = { ImagePlus imp -> imp.getProcessor().convertToByteProcessor(true) }
    def green = gray(target), magenta = gray(new ImagePlus("warped", warped))
    def rgb = new ColorProcessor(target.getWidth(), target.getHeight())
    for (int i = 0; i < target.getWidth() * target.getHeight(); i++) {
        int g = green.get(i) & 0xff, m = magenta.get(i) & 0xff
        rgb.set(i, (m << 16) | (g << 8) | m)                  // aligned structures turn white
    }
    def overlay = new ImagePlus("Alignment overlay (green = " + (r.relative_to ?: "") + ", magenta = " + r.apply_to + ")", rgb)
    overlay.show()
    summary.overlay_title = overlay.getTitle()
}

FloatProcessor resample(ImagePlus source, int width, int height, List matrix) {
    def (a, b, ty) = matrix[0]
    def (c, d, tx) = matrix[1]
    double det = a * d - b * c
    def input = source.getProcessor().convertToFloat()
    def output = new FloatProcessor(width, height)
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            double ry = y - ty, rx = x - tx                   // invert  [y', x'] = M [y, x] + t
            double sy = (d * ry - b * rx) / det, sx = (-c * ry + a * rx) / det
            if (sy >= 0 && sx >= 0 && sy <= source.getHeight() - 1 && sx <= source.getWidth() - 1)
                output.setf(x, y, (float) input.getInterpolatedValue(sx, sy))
        }
    }
    return output
}

double relativeDifference(FloatProcessor a, FloatProcessor b) {
    double sumDiff = 0, sumRef = 0
    for (int i = 0; i < a.getWidth() * a.getHeight(); i++) {
        double x = a.getf(i), y = b.getf(i)
        if (x > 0 || y > 0) { sumDiff += Math.abs(x - y); sumRef += Math.abs(y) }
    }
    return sumDiff / Math.max(sumRef, 1e-9)
}

// ---------------------------------------------------------------- main
def labConstrictorRun() {
    hooks.setup()
    def started = System.nanoTime()
    def summary = [timings: [:]]
    def found = discoverApps()
    summary.timings.discovery_s = (System.nanoTime() - started) / 1e9
    if (!found.apps) {
        def why = found.problems ? "\n\nSkipped:\n" + found.problems.join("\n") : ""
        lcLog("ERROR", "no usable apps in " + found.home + "/apps" + why.replace("\n", " | "))
        if (hooks.interactive) IJ.error("LabConstrictor", "No LabConstrictor apps are registered in " + found.home + "/apps." + why +
                                        "\n\nCheck with: labconstrictor-tools doctor   (log: " + new File(lcHome(), "logs/labconstrictor.log").path + ")")
        return hooks.finish(summary + [error: "no apps registered"])
    }

    def appName = pickOne("LabConstrictor", "Application", found.apps.keySet().toList(), hooks.preferred("app"))
    if (appName == null) return hooks.finish(summary + [cancelled: true])
    def app = found.apps[appName]
    def toolLabel = pickOne("LabConstrictor: " + app.display_name, "Tool", app.schema.tools.collect { it.label }, hooks.preferred("tool"))
    if (toolLabel == null) return hooks.finish(summary + [cancelled: true])
    def tool = app.schema.tools.find { it.label == toolLabel }

    def openImages = WindowManager.getImageTitles() as List<String>
    def dialogStarted = System.nanoTime()
    def (info, links) = buildToolDialog(tool, openImages)
    summary.timings.dialog_construction_s = (System.nanoTime() - dialogStarted) / 1e9
    def module = harvest(info, tool.label, links)
    if (module == null) return hooks.finish(summary + [cancelled: true])

    def jobDir = Files.createTempDirectory("lcjob_fiji_").toFile()
    List exported
    try {
        exported = exportInputs(tool, module, jobDir)
    } catch (IllegalArgumentException problem) {               // missing file, nothing chosen for a required image
        if (hooks.interactive) IJ.error("LabConstrictor", problem.message)
        jobDir.deleteDir()
        return hooks.finish(summary + [error: problem.message])
    }
    def (inputs, images) = exported
    try {
        runAndShow(app, tool, inputs, images, summary)
    } finally {
        jobDir.deleteDir()                                       // also when running or showing the results throws
    }
    hooks.finish(summary)
}

void runAndShow(Map app, Map tool, Map inputs, Map images, Map summary) {
    def outcome = runTool(app, tool, inputs)
    summary << [request: inputs, status: outcome.status, error: outcome.error, progress_events: outcome.progress,
                worker_alive_after: outcome.workerAlive, cancel_requested: outcome.cancelRequested]
    summary.timings.worker_run_s = outcome.seconds
    summary.run_record = writeRunRecord(app, tool, inputs, outcome, summary)
    if (outcome.complete) {
        summary.interpreter = outcome.outputs.diagnostics
        summary << showResults(app, outcome.outputs.results, images)
    } else if (outcome.status == "FAILED" && !outcome.cancelRequested && (outcome.error ?: "") =~ /^\[(no_match|no_result)\] /) {
        // an outcome ("nothing found"), not a fault: a plain message, not an error dialog
        summary.no_match_message = outcome.error.replaceFirst(/^\[[a-z_]+\] /, "")
        if (hooks.interactive) IJ.showMessage("LabConstrictor: " + app.display_name, summary.no_match_message)
        IJ.showStatus("LabConstrictor: " + summary.no_match_message)
    } else if (outcome.status in ["FAILED", "CRASHED"] && !outcome.cancelRequested) {
        summary.failure_message = failureMessage(outcome, summary)
        if (hooks.interactive) IJ.error("LabConstrictor: " + app.display_name, summary.failure_message)
    } else {
        IJ.showStatus("LabConstrictor: " + outcome.status.toLowerCase())
    }
}
/** Entry point: anything unexpected is logged with its stack trace and shown with the log's location. */
def labConstrictorMain() {
    lcLog("INFO", "---- session start: " + IJ.getFullVersion() + " java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + " LC_HOME=" + lcHome())
    try {
        return labConstrictorRun()
    } catch (Throwable problem) {
        lcLog("ERROR", "unexpected failure: " + problem, problem)
        if (hooks.interactive) IJ.error("LabConstrictor", "Unexpected error: " + problem + "\n\nThe full report is in the log file:\n" + new File(lcHome(), "logs/labconstrictor.log").path)
        return hooks.finish([error: problem.toString()])
    }
}
labConstrictorMain()
