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
import ij.Macro
import ij.plugin.frame.Recorder
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
@Field final int MAX_KEPT_LINES = 500            // worker output and progress events kept per run (the log has the rest)
@Field final int EXIT_WAIT_MS = 15000            // heavy interpreters (torch, numba) need a few seconds to exit after stdin closes

/** Module whose callbacks keep a calibration field in sync with the image chosen for it (schema: pixel_size_of). */
class LCModule extends DefaultMutableModule {
    Map<String, List<String>> links = [:]     // image parameter -> [pixel-size parameters]
    LCModule(MutableModuleInfo info) { super(info) }
    void run() {}                              // all work happens after harvesting

    static Closure logger = { String level, String message, Throwable problem -> }   // set below to lcLog (a class cannot call the script's methods)
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
        if (!file.isFile()) return null            // a path that is still being typed: nothing to read, nothing to complain about
        try {
            def info = new ij.io.TiffDecoder(file.parent + File.separator, file.name).getTiffInfo()
            if (!info || info[0].pixelWidth <= 0 || info[0].pixelWidth == 1.0d && !info[0].description) return null
            def unit = (info[0].description =~ /(?m)^unit=(.*)$/).with { it.find() ? it.group(1) : (info[0].unit ?: "") }
            unit = unit.toLowerCase().replace("\u00b5", "u").replace("\u03bc", "u").replace("\ufffd", "u").trim()
            def factor = MICRONS_PER_UNIT[unit] ?: (unit == "cm" ? 1e4d : unit == "inch" ? 25400.0d : null)
            return factor == null ? null : info[0].pixelWidth * factor
        } catch (Exception problem) {      // (a) not a TIFF / unreadable header (TiffDecoder throws IOException or runtime errors on garbage)
            // not "no calibration" but "could not read it": tell the user instead of silently leaving the field alone
            logger("WARN", "could not read the pixel size from " + file.path + ": " + problem, problem)
            IJ.log("LabConstrictor: could not read the pixel size from " + file.name + " (" + problem + "); enter it by hand")
            return null
        }
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
    /** The image a chooser names: a window title (dialog) or the image itself (macro replay). */
    static ImagePlus imageOf(def value) { return value instanceof ImagePlus ? value : (value ? WindowManager.getImage(value as String) : null) }
    private void syncFromFile(int slot) {
        def imageName = links.keySet().toList()[slot]
        def file = getInput(imageName + "_file") as File
        def microns = file ? micronsFromFile(file) : null
        if (microns != null) links[imageName].each { setInput(it, microns) }
    }
    private void sync(int slot) {
        def imageName = links.keySet().toList()[slot]
        def imp = imageOf(getInput(imageName))
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

LCModule.logger = { String level, String message, Throwable problem -> lcLog(level, message, problem) }

/** Delete a temporary folder; a folder that cannot be removed is logged once (it stays on disk), never ignored. */
void removeFolder(File folder, String what) {
    try {
        if (folder.exists() && !folder.deleteDir()) lcLog("WARN", "could not remove the " + what + " " + folder)
    } catch (SecurityException problem) {
        lcLog("WARN", "could not remove the " + what + " " + folder + ": " + problem, problem)
    }
}

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
    } catch (Exception failure) {
        // the log itself is broken (disk full, read-only home): there is nowhere else to write, so say it once on stderr and carry on
        System.err.println("LabConstrictor: could not write the log file: " + failure)
    }
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

/** Identifiers that become file names (app, tool and parameter names): the same plain-name rule as the Python registry. */
boolean plainName(def text) {
    return text instanceof String && text && text != "." && text != ".." && !text.any { it in ["/", "\\", "\0"] as Set } && text == text.trim()
}
boolean identifier(def text) { return text instanceof String && (text ==~ /[A-Za-z_][A-Za-z0-9_]*/) }
boolean toolId(def text) { return text instanceof String && (text ==~ /[A-Za-z0-9_][A-Za-z0-9_.-]*/) }   // @tool(id=...) may contain - and .
String slug(String text) { return text.replaceAll(/[^A-Za-z0-9_.-]+/, "_").replaceFirst(/^\.+/, "") ?: "x" }

boolean isPosixHost() { return !System.getProperty("os.name").toLowerCase().contains("win") }

/** unix permission bits of `path`, or null where the file system has no such notion (then the POSIX checks cannot apply). */
Integer unixMode(java.nio.file.Path path) {
    try { return java.nio.file.Files.getAttribute(path, "unix:mode") as Integer }
    catch (UnsupportedOperationException | IllegalArgumentException problem) {
        lcLog("WARN", "no unix permission bits for " + path + " (" + problem + "): the permission checks do not apply to it")
        return null
    }
}

/** Writable by everybody (a directory with the sticky bit, like /tmp, only lets owners replace their own files). */
boolean worldWritable(File file, boolean directory) {
    def path = file.toPath()
    def mode = unixMode(path)
    if (mode == null)
        return java.nio.file.Files.getPosixFilePermissions(path).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
    return (mode & 2) != 0 && !(directory && (mode & 01000) != 0)
}

/** Ownership and permission policy for a file hosts read to decide what to start (same as registry._file_reason). */
String fileReason(File file, boolean userDir, String what) {
    def path = file.toPath()
    def owner = java.nio.file.Files.getOwner(path).name
    // per-user entries must be ours; entries in shared folders (LC_APPS_PATH, /etc) may also belong to root (the administrator)
    if (owner != System.getProperty("user.name") && !(owner == "root" && !userDir))
        return what + " is not owned by the current user" + (userDir ? "" : " or root")
    def permissions = java.nio.file.Files.getPosixFilePermissions(path)
    if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
        return what + " is writable by other users"
    return null
}

/** Why an entry must not be used to start a process, or null. Fails closed: a check that cannot be made is a reason. */
String untrustedReason(File entryFile, Map entry, boolean userDir) {
    try {
        // lexical containment (symlinks inside the prefix are normal: a venv's python links to the base interpreter)
        def python = new File(entry.python).absoluteFile.toPath().normalize(), prefix = new File(entry.prefix).absoluteFile.toPath().normalize()
        if (!python.startsWith(prefix))
            return "interpreter " + entry.python + " is not inside the install prefix " + entry.prefix
        if (prefix.parent == null)
            return "the install prefix " + prefix + " is a filesystem root"
        if (!isPosixHost()) return null                                  // Windows: no POSIX ownership/mode to check (known, not an error)
        try { java.nio.file.Files.getPosixFilePermissions(entryFile.toPath()) }
        catch (UnsupportedOperationException problem) {                  // a file system without POSIX permissions (known): the entry cannot be checked
            lcLog("WARN", "the registry entry " + entryFile + " is not checked for permissions: its file system has no POSIX permissions (" + problem + ")")
            return null
        }
        def reason = fileReason(entryFile, userDir, "entry file")
        if (reason) return reason
        def schemaFile = new File(entry.schema_path).absoluteFile
        if (schemaFile.parentFile.toPath().normalize() != entryFile.absoluteFile.parentFile.toPath().normalize())
            return "schema file " + entry.schema_path + " is not in the same folder as the entry"
        if (schemaFile.exists()) {                                       // a missing schema is reported later, with its own message
            reason = fileReason(schemaFile, userDir, "schema file")
            if (reason) return reason
        }
        // anybody who can replace the interpreter (or its folders) can run code as this user the next time an app is started
        for (item in [[python.toFile(), false, "interpreter"], [python.parent.toFile(), true, "interpreter folder"], [prefix.toFile(), true, "install prefix"]]) {
            if (item[0].exists() && worldWritable(item[0] as File, item[1] as boolean))
                return item[2] + " " + item[0] + " is writable by everybody"
        }
    } catch (Exception problem) {
        lcLog("WARNING", "cannot verify the registry entry " + entryFile + ": " + problem, problem)
        return "cannot verify the registry entry permissions (" + problem + ")"
    }
    return null
}

@Field final List<String> PARAMETER_TYPES = ["string", "integer", "float", "boolean", "choice", "image", "labels", "table", "file", "folder"]

/** The registry entry is untrusted JSON: check its shape before any field is used (same rules as registry._validated_entry). */
String entryProblem(def entry) {
    if (!(entry instanceof Map)) return "the entry is not a JSON object"
    for (key in ["name", "python", "prefix", "module", "schema_path"])
        if (!(entry[key] instanceof String) || !entry[key]) return "field '" + key + "' must be a non-empty string"
    if (!plainName(entry.name)) return "invalid app name '" + entry.name + "': it must be a plain name without path separators"
    if (entry.pythonpath != null && !(entry.pythonpath instanceof List && entry.pythonpath.every { it instanceof String }))
        return "field 'pythonpath' must be a list of strings"
    if (entry.runtime_path != null && !(entry.runtime_path instanceof String)) return "field 'runtime_path' must be a string"
    return null
}

/** The cached schema is untrusted JSON too: shape, plus the names that later become file names or SciJava item names. */
String schemaProblem(def schema) {
    if (!(schema instanceof Map)) return "schema is not a JSON object"
    if (!(schema.protocol in SUPPORTED_PROTOCOLS)) return "schema protocol " + schema.protocol + " is not supported"
    if (!(schema.tools instanceof List)) return "schema has no list of tools"
    for (tool in schema.tools) {
        if (!(tool instanceof Map) || !(tool.label instanceof String) || !toolId(tool.id) || !(tool.inputs instanceof List) || !(tool.outputs instanceof List))
            return "a tool has an unexpected structure (needs a plain id, a text label, inputs and outputs lists)"
        for (p in tool.inputs) {
            if (!(p instanceof Map) || !identifier(p.name) || !(p.label instanceof String))
                return "tool '" + tool.id + "' has a parameter without an identifier name and a text label"
            if (!(p.type in PARAMETER_TYPES)) return "tool '" + tool.id + "': parameter '" + p.name + "' has the unsupported type " + p.type
            if (p.type == "choice" && !(p.choices instanceof List && p.choices)) return "tool '" + tool.id + "': choice parameter '" + p.name + "' has no choices"
        }
    }
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
                def malformed = entryProblem(entry)
                if (malformed) { problems << (file.name + ": " + malformed); skip << name; return }
                name = entry.name
                if (apps.containsKey(name) || skip.contains(name)) return          // the file name differs from the app name it claims: priority stays with the earlier directory
                if (!new File(entry.python).exists()) { problems << (name + ": not available on this machine (interpreter " + entry.python + " is missing)"); skip << name; return }
                def reason = untrustedReason(file, entry, index == 0)
                if (reason) { problems << (name + ": ignored: " + reason); skip << name; return }
                def schema = slurper.parseText(new File(entry.schema_path).text)
                def badSchema = schemaProblem(schema)
                if (badSchema) throw new IllegalStateException(badSchema)
                apps[name] = entry + [pythonpath: entry.pythonpath ?: [], runtime_path: entry.runtime_path ?: "", schema: schema]
            } catch (IllegalStateException e) {                    // an invalid schema: expected, reported below with the other problems
                problems << (file.name + ": " + e.message)
            } catch (Exception e) {                                // broad on purpose: one broken entry must not hide the other apps; reported below, the stack goes to the log
                lcLog("WARNING", "app entry " + file.name + " could not be loaded: " + e, e)
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
        def folder = new File(runs, stamp + "_" + slug(app.name as String) + "_" + slug(tool.id as String))
        if (folder.canonicalFile.parentFile != runs.canonicalFile) throw new IOException("run folder " + folder + " is not inside " + runs)
        folder.mkdirs()
        new File(folder, "run.json").text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
            app: app.name, app_version: app.version, tool: tool.id, host: "fiji", status: outcome.status, error: outcome.error,
            seconds: outcome.seconds, inputs: inputs, worker_output_tail: outcome.workerOutput, log_file: new File(lcHome(), "logs/labconstrictor.log").path, progress_events: outcome.progress, interpreter: outcome.outputs?.diagnostics,
            results: (outcome.outputs?.results ?: []).collect { it.findAll { k, v -> k != "matrix_yx" } }]))
        (runs.listFiles({ File f -> f.isDirectory() } as FileFilter) ?: [] as File[]).sort().reverse().drop(50).each { removeFolder(it, "old run record") }
        return folder.path
    } catch (Exception problem) {                       // a record must never break a run, but its absence must be visible
        lcLog("WARNING", "could not write the run record: " + problem, problem)
        return null
    }
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
    item.setPersisted(false)                    // never remember values between runs: a stale '(or file)' would silently beat the open image
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
/** Inputs in the order the dialog shows them: the parameters of a `group` together (where the group first appears), `advanced` ones
 *  after all the others. Without group/advanced hints the order is unchanged. (Same rule as the Napari form.) */
List presentationOrder(List inputs) {
    if (!inputs.any { it.group || it.advanced }) return inputs
    def first = [:]                                                   // [advanced?, group] -> index where the group first appears
    inputs.eachWithIndex { p, i -> if (p.group) first.putIfAbsent([p.advanced ? 1 : 0, p.group], i) }
    def keyOf = { p, i -> [p.advanced ? 1 : 0, p.group ? first[[p.advanced ? 1 : 0, p.group]] : i, i] }
    def keyed = inputs.withIndex().collect { p, i -> [p, keyOf(p, i)] }
    return keyed.sort(false) { a, b -> a[1][0] <=> b[1][0] ?: a[1][1] <=> b[1][1] ?: a[1][2] <=> b[1][2] }.collect { it[0] }
}

/** The "channel" field of an image parameter declared with PickChannel: a number from 1 (the channels of the chosen image or file are checked when the run starts). */
void addChannelItem(MutableModuleInfo info, Map p, String defaultImageTitle, Map overrides) {
    def imp = defaultImageTitle ? WindowManager.getImage(defaultImageTitle) : null
    def start = overrides.containsKey(p.name + "_channel") ? overrides[p.name + "_channel"] : (imp != null && imp.getNChannels() > 1 ? imp.getChannel() : 1)
    addItem(info, p.name + "_channel", Integer, [label: p.label + " channel", required: false, min: 1, max: 1000, step: 1, default: start as Integer,
                                                description: "Channel of the image to use (1 = first). The tool receives only this channel" + (imp != null && imp.getNChannels() > 1 ? "; the chosen image has " + imp.getNChannels() : "")])
}

/** The dialog's rows in order: [heading: text] for each group and for the advanced block, [parameter: p] for each parameter. */
List dialogRows(List inputs) {
    def rows = [], lastGroup = null, advancedShown = false
    presentationOrder(inputs).each { p ->
        if (p.advanced && !advancedShown) { advancedShown = true; rows << [heading: "Advanced settings"]; lastGroup = null }
        if (p.group && p.group != lastGroup) rows << [heading: p.group as String]
        lastGroup = p.group ?: lastGroup
        rows << [parameter: p]
    }
    return rows
}

/** image parameter -> the pixel-size parameters that follow its calibration (schema: pixel_size_of). */
Map pixelSizeLinks(Map tool) {
    def links = [:]
    tool.inputs.findAll { it.pixel_size_of }.each { links.get(it.pixel_size_of, []) << it.name }
    return links
}

/** The window each image parameter starts on: the i-th image parameter defaults to the i-th open image (the last one repeats). */
Map defaultImageTitles(Map tool, List<String> openImages) {
    def titles = [:]
    tool.inputs.findAll { it.type in ["image", "labels"] }.eachWithIndex { p, i ->
        def given = hooks.overrides[p.name]
        titles[p.name] = given ? given as String : (openImages ? openImages[Math.min(i, openImages.size() - 1)] : null)
    }
    return titles
}

/** The harness's value for a dialog key, or `fallback` when it did not set one. */
def overrideOr(String key, def fallback) { return hooks.overrides.containsKey(key) ? hooks.overrides[key] : fallback }

/** Build the tool dialog from the schema. Returns [info, links]. */
List buildToolDialog(Map tool, List<String> openImages, Map choiceLists = [:]) {
    def info = newInfo(tool.label)
    def links = pixelSizeLinks(tool)                         // image param -> [pixel-size params]
    def context = [openImages: openImages, choiceLists: choiceLists, linkedImages: links.keySet().toList(), defaultTitle: defaultImageTitles(tool, openImages)]
    if (openImages && tool.inputs.any { it.type in ["image", "labels"] }) {
        // also keeps SciJava from replacing a one-image dialog by a bare file chooser (the open image is auto-resolved)
        addItem(info, "image_source_note", String, [label: "Image source", message: true, required: false,
                                                    default: "Images are taken from the open windows. To use a file instead, choose it in the matching '(or file)' field."])
    }
    int headings = 0
    dialogRows(tool.inputs).each { row ->
        if (row.heading) addItem(info, "heading_" + (headings++), String, [label: row.heading, message: true, required: false, default: "— " + row.heading + " —"])
        else addParameterItems(info, row.parameter, context)
    }
    return [info, links]
}

/** The dialog items of one parameter: an optional "Set" box for nullable ones, then the field(s) its type needs. */
void addParameterItems(MutableModuleInfo info, Map p, Map context) {
    def base = [label: p.label + (p.unit ? " (" + p.unit + ")" : ""), description: p.description, required: p.required]
    if (p.nullable && p.type in ["string", "integer", "float", "choice", "boolean"]) {   // optional with no default: "unset" must stay possible
        addItem(info, "set_" + p.name, Boolean, [label: "Set " + p.label.toLowerCase(), required: false, default: overrideOr("set_" + p.name, false)])
    }
    def overridden = hooks.overrides.containsKey(p.name)
    def override = overridden ? hooks.overrides[p.name] : null
    switch (p.type) {
        case ["image", "labels"]: addImageItems(info, p, base, context); break
        case ["table", "file"]:
            addItem(info, p.name, File, base + [default: override ? new File(override as String) : null]); break
        case "folder":
            addItem(info, p.name, File, base + [default: override ? new File(override as String) : null]).setWidgetStyle("directory"); break
        case "string": addStringItem(info, p, base, override, context.choiceLists); break
        case "boolean":
            addItem(info, p.name, Boolean, base + [default: overridden ? override : (p.default ?: false)]); break
        case "choice":
            def choiceItem = addItem(info, p.name, String, base + [choices: p.choices, default: override ?: p.default ?: p.choices[0]])
            if (p.widget == "radio" && !p.nullable) choiceItem.setWidgetStyle("radioButtonHorizontal")   // Widget("radio")
            break
        case "integer":
            def intItem = addItem(info, p.name, Integer, base + [default: (overridden ? override : (p.default ?: 0)) as Integer,
                                                                min: p.minimum as Integer, max: p.maximum as Integer, step: 1])
            if (p.widget == "slider" && !p.nullable && p.minimum != null && p.maximum != null) intItem.setWidgetStyle("slider")   // Widget("slider")
            break
        case "float": addFloatItem(info, p, base, overridden, override, context); break
        default:
            throw new IllegalArgumentException("parameter '" + p.name + "' has the unsupported type '" + p.type + "'")
    }
}

/** Image / labels parameter: the chooser of open windows, the "(or file)" field, and the extras (selection, "use" box, channel). */
void addImageItems(MutableModuleInfo info, Map p, Map base, Map context) {
    def openImages = context.openImages
    def slot = context.linkedImages.indexOf(p.name)
    def fileOverride = hooks.overrides[p.name + "_file"]
    def defaultTitle = context.defaultTitle[p.name]
    if (p.region_of) {                           // RegionOf: the ROI / ROI Manager selection of the named image can be the value
        addItem(info, "selection_" + p.name, Boolean, [label: "Use the selection as " + p.label.toLowerCase(), required: false,
                                                       description: "Send the ROI of the image (or the ROIs selected in the ROI Manager: several are labels 1, 2, 3...) as the region",
                                                       default: overrideOr("selection_" + p.name, false)])
    }
    if (openImages) {
        if (!p.required) {                       // SciJava image choosers cannot be empty
            addItem(info, "use_" + p.name, Boolean, [label: "Use " + p.label.toLowerCase(), required: false, default: overrideOr("use_" + p.name, false)])
        }
        addItem(info, p.name, String, base + [choices: openImages, default: defaultTitle, callback: slot >= 0 ? "syncImage" + slot : null])
        addItem(info, p.name + "_file", File, [label: p.label + " (or file)", required: false,
                                               description: "Read the image from a file instead of the open image above (leave empty to use the open image)",
                                               default: fileOverride ? new File(fileOverride as String) : null,
                                               callback: slot >= 0 ? "syncFile" + slot : null])
        if (p.pick_channel) addChannelItem(info, p, defaultTitle, hooks.overrides)
    } else {                                     // nothing open: the file is the only way to give an image
        addItem(info, p.name + "_file", File, base + [description: p.description ?: "No image is open - choose a file",
                                                      default: fileOverride ? new File(fileOverride as String) : null,
                                                      callback: slot >= 0 ? "syncFile" + slot : null])
        if (p.pick_channel) addChannelItem(info, p, null, hooks.overrides)
    }
}

/** String parameter: a dropdown when ChoicesFrom answered, otherwise the plain text field. */
void addStringItem(MutableModuleInfo info, Map p, Map base, def override, Map choiceLists) {
    def options = choiceLists[p.name]
    def held = (override ?: p.default) as String                // a value the parameter already holds (its default, or a remembered one) stays selectable even when the source does not list it: never silently replaced
    if (options && held && !options.contains(held)) options = [held] + options
    if (options && p.nullable) options = [""] + options    // no answer pre-selected: ticking "Set" with the blank entry is refused by the tool, not silently answered
    if (options) addItem(info, p.name, String, base + [choices: options, default: options.contains(override) ? override : options.contains(p.default) ? p.default : options[0]])
    else addItem(info, p.name, String, base + [default: override ?: p.default ?: ""])
}

/** Float parameter, prefilled from the calibration of the image (or file) it is linked to. */
void addFloatItem(MutableModuleInfo info, Map p, Map base, boolean overridden, def override, Map context) {
    def openImages = context.openImages
    def value = overridden ? override : p.default
    def source = p.pixel_size_of && openImages ? WindowManager.getImage(context.defaultTitle[p.pixel_size_of] ?: openImages[0]) : null
    def microns = LCModule.micronsPerPixel(source)
    def fileSource = p.pixel_size_of ? hooks.overrides[p.pixel_size_of + "_file"] : null
    if (microns == null && fileSource) microns = LCModule.micronsFromFile(new File(fileSource as String))
    if (microns != null) value = microns                                                      // calibration prefill (unit-aware)
    if (p.pixel_size_of && value == null)
        IJ.log("LabConstrictor: no pixel size found for '" + p.label + "' (the image has no usable calibration): enter it by hand")
    def floatItem = addItem(info, p.name, Double, base + [default: (value ?: 0) as Double, min: p.minimum as Double, max: p.maximum as Double, step: 0.0001d])
    if (p.widget == "slider" && !p.nullable && p.minimum != null && p.maximum != null) floatItem.setWidgetStyle("slider")   // Widget("slider")
}

// ---------------------------------------------------------------- dialog -> request
/** Harvested values -> worker inputs. Images are saved as TIFF (calibration travels as explicit parameters). */
/** One channel of a (hyper)stack as a plain image, at the current Z and T: what a tool declared with PickChannel receives. */
ImagePlus channelOf(ImagePlus imp, int channel, String label) {
    if (channel < 1 || channel > imp.getNChannels())
        throw new IllegalArgumentException("'" + label + "': channel " + channel + " was asked for, but the image has " + imp.getNChannels() + " channel" + (imp.getNChannels() == 1 ? "" : "s"))
    def plane = imp.getNChannels() == 1 ? imp.getProcessor() : imp.getStack().getProcessor(imp.getStackIndex(channel, imp.getSlice(), imp.getFrame()))
    def single = new ImagePlus(imp.getTitle(), plane.duplicate())
    single.setCalibration(imp.getCalibration())
    return single
}

@groovy.transform.Field final int MAX_REGION_OBJECTS = 65535      // labels of the region image (16 bit)

/** RegionOf: the ROIs selected in the ROI Manager (else the ROI of the image) as a label image the size of the image the region
 *  belongs to: labels 1..N, 0 outside. Whatever makes that impossible is said to the person, never guessed around. */
File selectionMask(Map p, Map tool, def module, File jobDir) {
    def label = p.label as String, owner = tool.inputs.find { it.name == p.region_of }
    if (module.getInput(p.region_of + "_file"))
        throw new IllegalArgumentException("'" + label + "': the selection belongs to an open image, but a file was chosen for '" + owner.label + "': open the image, or untick the selection")
    def imp = LCModule.imageOf(module.getInput(p.region_of))
    if (imp == null) throw new IllegalArgumentException("'" + label + "': choose the open image it belongs to ('" + owner.label + "')")
    def manager = ij.plugin.frame.RoiManager.getInstance2()
    def rois = (manager?.getSelectedIndexes() ?: [] as int[]).collect { manager.getRoi(it) }.findAll { it != null }
    if (!rois && imp.getRoi() != null) rois = [imp.getRoi()]
    if (!rois) throw new IllegalArgumentException("'" + label + "': nothing is selected: draw a region on '" + imp.getTitle() + "' or select entries in the ROI Manager, or untick the selection")
    if (rois.size() > MAX_REGION_OBJECTS) throw new IllegalArgumentException("'" + label + "': " + rois.size() + " objects are selected; at most " + MAX_REGION_OBJECTS + " are supported")
    def mask = new ij.process.ShortProcessor(imp.getWidth(), imp.getHeight())
    rois.eachWithIndex { roi, i -> mask.setValue(i + 1); mask.fill(roi) }
    if (mask.getStatistics().max == 0) throw new IllegalArgumentException("'" + label + "': the selection lies outside the image")
    def file = new File(jobDir, p.name + ".tif")
    new FileSaver(new ImagePlus(p.name, mask)).saveAsTiff(file.path)
    return file
}

List exportInputs(Map tool, def module, File jobDir) {
    def inputs = [:], images = [:]
    tool.inputs.each { p ->
        def value = module.getInput(p.name)
        switch (p.type) {
            case ["image", "labels"]:
                if (p.region_of && module.getInput("selection_" + p.name)) {   // RegionOf: the selection is the value
                    def maskFile = selectionMask(p, tool, module, jobDir)
                    inputs[p.name] = maskFile.path
                    images[p.name] = maskFile.path
                    break
                }
                def chosen = module.getInput(p.name + "_file") as File
                if (chosen) {                                                // a file wins over the open image: the worker reads it directly
                    if (!chosen.isFile()) throw new IllegalArgumentException("'" + p.label + "': file not found: " + chosen.path)
                    def opened = p.pick_channel ? IJ.openImage(chosen.path) : null
                    if (opened != null) {                                     // PickChannel: the file's chosen channel is written for the worker
                        def picked = channelOf(opened, (module.getInput(p.name + "_channel") ?: 1) as int, p.label as String)
                        def channelFile = new File(jobDir, p.name + ".tif")
                        new FileSaver(picked).saveAsTiff(channelFile.path)
                        inputs[p.name] = channelFile.path
                        images[p.name] = chosen.path
                        break
                    }
                    inputs[p.name] = chosen.path
                    images[p.name] = chosen.path                              // opened later only if a result needs it (see asImage)
                    break
                }
                if (!p.required && !module.getInput("use_" + p.name)) break
                def chosenImage = LCModule.imageOf(value)
                if (chosenImage == null) throw new IllegalArgumentException("'" + p.label + "' is required: open an image or choose a file")
                value = chosenImage
                def file = new File(jobDir, p.name + ".tif")
                if (file.canonicalFile.parentFile != jobDir.canonicalFile) throw new IllegalArgumentException("'" + p.label + "': refusing to write outside " + jobDir)
                def imp = value as ImagePlus
                if (p.pick_channel) {                                   // PickChannel: exactly the channel that was chosen, at the current Z and T
                    imp = channelOf(imp, (module.getInput(p.name + "_channel") ?: 1) as int, p.label as String)
                } else if (p.axes == "YX" && imp.getStackSize() > 1) {          // tool wants one plane: send the one on screen
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

// ---------------------------------------------------------------- remembered values and dynamic choices (ChoicesFrom)
/** Names of the parameters that some ChoicesFrom of the app depends on: the only values kept between runs. */
Set<String> dependsNames(Map app) {
    def names = [] as Set
    app.schema.tools.each { tool -> tool.inputs.each { p -> if (p.choices_from?.depends instanceof List) names.addAll(p.choices_from.depends) } }
    return names
}

File stateFile(Map app) { return new File(new File(lcHome(), "state"), slug(app.name as String) + ".json") }

Map readState(Map app) {
    try {
        def file = stateFile(app)
        def data = file.isFile() ? new JsonSlurper().parseText(file.text) : [:]
        return data instanceof Map ? data : [:]
    } catch (Exception problem) {
        // (d) intended fallback: no remembered values (ChoicesFrom then shows a text field); broad because the file is whatever was left on disk
        lcLog("WARNING", "could not read the remembered values (starting without them): " + problem, problem)
        return [:]
    }
}

/** Keep the values of the parameters ChoicesFrom depends on, so the next dialog can ask the source tool (Fiji builds the dialog before anything is typed). */
void rememberDepends(Map app, Map tool, Map inputs) {
    try {
        def keep = dependsNames(app)
        def now = tool.inputs.findAll { it.name in keep && inputs[it.name] instanceof String }.collectEntries { [(it.name): inputs[it.name]] }
        if (!now) return
        def state = readState(app) + now
        def file = stateFile(app)
        file.parentFile.mkdirs()
        file.text = groovy.json.JsonOutput.toJson(state)
    } catch (Exception problem) { lcLog("WARNING", "could not remember the values (the next dialog will not know them): " + problem, problem) }
}

/** ChoicesFrom parameters of `tool` -> their options, asked of the source tool with the values of the previous run; unanswered ones are left out (text field). */
Map choiceListsFor(Map app, Map tool) {
    def lists = [:]
    def state = null
    tool.inputs.findAll { it.choices_from instanceof Map }.each { p ->
        state = state ?: readState(app)
        def source = app.schema.tools.find { it.id == p.choices_from.tool }
        def given = hooks.overrides.containsKey("_choices_" + p.name) ? hooks.overrides["_choices_" + p.name] : null
        try {
            if (given instanceof List) { lists[p.name] = given; return }
            if (source == null || !(p.choices_from.depends instanceof List)) {
                lcLog("WARNING", "choices of '" + p.name + "' cannot be asked: the source tool '" + p.choices_from.tool + "' is not in the app or 'depends' is not a list (text field)")
                return
            }
            def request = [:]
            for (name in p.choices_from.depends) {
                def value = hooks.overrides.containsKey(name) ? hooks.overrides[name] : state[name]
                def type = tool.inputs.find { it.name == name }?.type
                if (!(value instanceof String) || !value || (type == "folder" && !new File(value).isDirectory())) {   // (d) intended fallback: not known yet, so a text field
                    lcLog("INFO", "choices of '" + p.name + "' not asked yet: '" + name + "' has no usable value (text field)")
                    return
                }
                request[name] = value
            }
            def jobDir = Files.createTempDirectory("lcchoices_fiji_").toFile()
            try {
                request[JOB_DIR_KEY] = jobDir.path
                def outcome = runTool(app, source, request)
                if (!outcome.complete) { lcLog("WARNING", "choices of '" + p.name + "' not available: " + outcome.error); return }
                def found = outcome.outputs.results.find { it.type == "values" && it.values?.get(p.choices_from.field ?: "choices") instanceof List }
                def options = found?.values?.get(p.choices_from.field ?: "choices")?.collect { it.toString() }
                if (options) lists[p.name] = options
            } finally { removeFolder(jobDir, "job folder of the choices request") }
        } catch (Exception problem) {          // broad on purpose: one failing source tool must not break the dialog; the field stays a text field
            lcLog("WARNING", "could not get the choices of '" + p.name + "': " + problem, problem)
            IJ.log("LabConstrictor: could not get the choices of '" + p.label + "' (" + problem + "); type the value instead")
        }
    }
    return lists
}

// ---------------------------------------------------------------- run
/** Run one task through Appose; Esc (or the harness) requests cancel, a tool that ignores it is killed. */
Map runTool(Map app, Map tool, Map inputs) {
    def command = [app.python, "-m", "labconstrictor_tools", "serve", "--module", app.module] + app.pythonpath.collectMany { ["--pythonpath", it] }
    def env = [PYTHONPATH: app.runtime_path, PYTHONNOUSERSITE: "1", PYTHONIOENCODING: "utf-8", PYTHONUNBUFFERED: "1"]
    def service = new Service(new File(app.prefix), env, command as String[])
    def workerOutput = new LinkedList<String>()              // the worker's stderr tail: tracebacks, import errors, native crashes
    service.debug { String line -> synchronized (workerOutput) { workerOutput << line; if (workerOutput.size() > MAX_KEPT_LINES) workerOutput.removeFirst() }; lcLog("DEBUG", "worker " + line) }
    lcLog("INFO", "starting worker for " + app.name + " python=" + app.python + " module=" + app.module + " tool=" + tool.id)
    def progress = []
    def task = service.task(TOOL_PREFIX + tool.id, inputs)
    task.listen { event ->
        if (event.responseType == Service.ResponseType.UPDATE) {
            synchronized (progress) { progress << [event.message, event.current, event.maximum]; if (progress.size() > MAX_KEPT_LINES) progress.remove(0) }
            IJ.showStatus("LabConstrictor: " + (event.message ?: ""))
            if (event.maximum > 0) IJ.showProgress(event.current / (double) event.maximum)
        }
    }
    def started = System.currentTimeMillis()
    Long cancelSent = null
    try {
    task.start()
    IJ.resetEscape()
    def cancelAfter = hooks.cancelAfterMs()
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
    } finally {                                              // also when interrupted or when anything above throws: no worker left behind
        IJ.showProgress(1.0)
        try { service.close(); waitForExit(service, EXIT_WAIT_MS) }
        catch (Throwable problem) {                          // cleanup: broad on purpose, whatever close() throws the worker must still be killed
            lcLog("ERROR", "could not close the worker cleanly: " + problem, problem); service.kill()
        }
    }
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
    // protocol chatter ("[SERVICE-n] {...}", raw JSON lines) is in the log, not for people; a long line would make the dialog wider than the screen
    def worker = (outcome.workerOutput ?: []).findAll { !(it =~ /^\[SERVICE-\d+\]/) && !it.trim().startsWith("{") }
            .collect { it.length() > 300 ? it.substring(0, 300) + " …" : it }
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
@groovy.transform.Field def lastShownImage = null          // the image window this run showed last (points without an apply_to go on it)
Map showResults(Map app, List results, Map images, Map tool = null) {
    def summary = [:]
    def replaced = (tool?.outputs ?: []).findAll { it.replace }.collect { it.name } as Set
    results.each { r ->
        try {
            switch (r.type) {
                case ["image", "labels"]: showImage(app, r, summary, r.name in replaced); break
                case "table": showTable(r, summary); break
                case "values": IJ.log(app.display_name + " " + r.name + ": " + r.values); summary["values_" + r.name] = r.values; break
                case "message": showMessage(app, r, summary); break
                case "points": showPoints(app, r, images, summary, r.name in replaced); break
                case "shapes": showShapes(app, r, images, summary, r.name in replaced); break
                case "file":
                    if (!new File(r.path as String).isFile()) throw new IllegalStateException("the tool reported the file " + r.path + " but it does not exist")
                    IJ.log("Output file: " + r.path); break
                case "affine": showAffine(r, images, results, summary, r.name in replaced); break
                default: throw new IllegalStateException("the tool returned a result of the type '" + r.type + "', which this version of Fiji LabConstrictor cannot show")
            }
        } catch (Exception problem) {          // broad on purpose: one result that cannot be shown must not hide the others
            def text = "could not show the result '" + r.name + "': " + problem.message
            lcLog("ERROR", text, problem)
            IJ.log("LabConstrictor: " + text)
            summary.display_errors = (summary.display_errors ?: []) + [text]
        }
    }
    return summary
}

void showImage(Map app, Map r, Map summary, boolean replace = false) {
    def imp = IJ.openImage(r.path)
    if (imp == null) { IJ.error("LabConstrictor", "Fiji cannot open result image " + r.name); return }
    def title = app.name + ":" + r.name
    if (replace) {                                           // Replace(): the previous result of this output makes way (no "name [1]" pile)
        def previous = WindowManager.getImage(title)
        if (previous != null) { previous.changes = false; previous.close() }
    }
    for (int n = 1; WindowManager.getImage(title) != null; n++) title = app.name + ":" + r.name + " [" + n + "]"   // a chain must be able to tell results apart
    imp.setTitle(title)
    imp.show()
    lastShownImage = imp
    summary["image_" + r.name] = [imp.getWidth(), imp.getHeight(), imp.getNSlices() * imp.getNFrames(), imp.getBitDepth()]
}

/** A message result: in the log, and in a dialog when a person is running the tool (markdown emphasis removed: a dialog shows plain text). */
void showMessage(Map app, Map r, Map summary) {
    def text = (r.text as String).replaceAll(/\*\*(.+?)\*\*/, '$1').replaceAll(/(?m)^\s*[-*] /, "\u2022 ")
    IJ.log(app.display_name + ": " + text)
    summary["message_" + r.name] = r.text
    if (hooks.interactive) IJ.showMessage("LabConstrictor: " + app.display_name, text)
}

/** Points (columns y, x in pixels of the image named by apply_to, else of the first image of this run, else the current image):
 *  a multi-point ROI on that image, also kept in the ROI Manager under the output's name, and a table with the properties. */
void showPoints(Map app, Map r, Map images, Map summary, boolean replace) {
    def table = ResultsTable.open(r.path)
    def target = images[r.apply_to]
    if (!(target instanceof ImagePlus)) target = lastShownImage ?: WindowManager.getCurrentImage()
    if (!(target instanceof ImagePlus)) {                    // nothing to place them on: the table is still the result
        table.show(app.name + ":" + r.name)
        lcLog("INFO", "points '" + r.name + "' shown as a table: no image is open to place " + table.size() + " point(s) on")
        IJ.log("LabConstrictor: '" + r.name + "' has " + table.size() + " point(s) but no image is open to place them on: shown as a table (open an image and run again to see them on it)")
        summary["points_" + r.name] = [count: table.size(), image: null, columns: table.getHeadings() as List]
        return
    }
    def xs = new float[table.size()], ys = new float[table.size()]
    for (int i = 0; i < table.size(); i++) { xs[i] = (float) (table.getValue("x", i) + 0.5d); ys[i] = (float) (table.getValue("y", i) + 0.5d) }   // pixel centres
    def roi = new ij.gui.PointRoi(xs, ys, xs.length)
    roi.setName(app.name + ":" + r.name)
    def manager = ij.plugin.frame.RoiManager.getRoiManager()
    if (replace) {                                           // Replace(): the previous points of this output make way
        def indexes = (0..<manager.getCount()).findAll { manager.getRoi(it).getName() == roi.getName() } as int[]
        if (indexes) manager.setSelectedIndexes(indexes).with { manager.runCommand("Delete") }
    }
    target.setRoi(roi)
    manager.addRoi(roi)
    table.show(app.name + ":" + r.name)
    summary["points_" + r.name] = [count: table.size(), image: target.getTitle(), columns: table.getHeadings() as List]
}

@groovy.transform.Field final int MAX_SHAPES = 50000       // outlines shown on the image
@groovy.transform.Field final int MAX_MANAGER_SHAPES = 1000  // outlines also listed in the ROI Manager (it gets very slow with more)

/** Outlines (GeoJSON Polygon / MultiPolygon, [x, y] with pixel centres at integers) in the frame of the image named by apply_to
 *  (else the first image of this run, else the current image): an overlay on that image with holes kept (composite ROIs), and the
 *  first MAX_MANAGER_SHAPES also in the ROI Manager. Replace() swaps this output's previous outlines. */
void showShapes(Map app, Map r, Map images, Map summary, boolean replace) {
    def collection = new JsonSlurper().parse(new File(r.path as String), "UTF-8")
    def target = images[r.apply_to]
    if (!(target instanceof ImagePlus)) target = lastShownImage ?: WindowManager.getCurrentImage()
    def prefix = app.name + ":" + r.name
    def rois = [], holes = 0, total = 0
    collection.features.each { feature ->
        def geometry = feature.geometry
        def parts = geometry.type == "Polygon" ? [geometry.coordinates] : geometry.coordinates
        parts.each { part ->
            total++
            if (rois.size() >= MAX_SHAPES) return
            def path = new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD)
            part.each { ring ->
                ring.eachWithIndex { point, i ->
                    double x = (point[0] as double) + 0.5d, y = (point[1] as double) + 0.5d       // pixel centres
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.closePath()
            }
            if (part.size() > 1) holes++
            def roi = new ij.gui.ShapeRoi(path)
            def label = feature.properties?.label
            roi.setName(prefix + " " + (label != null ? label : rois.size() + 1) + (parts.size() > 1 ? "." + (rois.size() + 1) : ""))
            rois << roi
        }
    }
    if (!(target instanceof ImagePlus)) {
        IJ.log("LabConstrictor: '" + r.name + "' has " + total + " outline(s) but no image is open to place them on (open an image and run again)")
        summary["shapes_" + r.name] = [count: total, shown: 0, image: null]
        return
    }
    def overlay = target.getOverlay() ?: new ij.gui.Overlay()
    def manager = ij.plugin.frame.RoiManager.getRoiManager()
    if (replace) {                                           // Replace(): the previous outlines of this output make way
        for (int i = overlay.size() - 1; i >= 0; i--) if (overlay.get(i).getName()?.startsWith(prefix + " ")) overlay.remove(i)
        def indexes = (0..<manager.getCount()).findAll { manager.getRoi(it).getName()?.startsWith(prefix + " ") } as int[]
        if (indexes) manager.setSelectedIndexes(indexes).with { manager.runCommand("Delete") }
    }
    rois.each { overlay.add(it) }
    target.setOverlay(overlay)
    overlay.setStrokeColor(java.awt.Color.YELLOW)
    target.updateAndDraw()
    rois.take(MAX_MANAGER_SHAPES).each { manager.addRoi(it.clone() as ij.gui.Roi) }
    if (total > MAX_SHAPES) IJ.log("LabConstrictor: '" + r.name + "': showing the first " + MAX_SHAPES + " of " + total + " outlines")
    if (rois.size() > MAX_MANAGER_SHAPES) IJ.log("LabConstrictor: '" + r.name + "': the ROI Manager lists the first " + MAX_MANAGER_SHAPES + " of " + rois.size() + " outlines (all are in the overlay)")
    if (holes) IJ.log("LabConstrictor: '" + r.name + "': " + holes + " outline(s) have holes (kept in the overlay and the ROI Manager)")
    summary["shapes_" + r.name] = [count: total, shown: rois.size(), holes: holes, image: target.getTitle(), overlay_size: overlay.size(), manager_count: manager.getCount()]
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

void showAffine(Map r, Map images, List results, Map summary, boolean replace = false) {
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
    def overlayTitle = "Alignment overlay (green = " + (r.relative_to ?: "") + ", magenta = " + r.apply_to + ")"
    if (replace) {                                           // Replace(): the previous overlay of this alignment makes way
        def previous = WindowManager.getImage(overlayTitle)
        if (previous != null) { previous.changes = false; previous.close() }
    }
    def overlay = new ImagePlus(overlayTitle, rgb)
    overlay.show()
    summary.overlay_title = overlay.getTitle()
}

FloatProcessor resample(ImagePlus source, int width, int height, List matrix) {
    def (a, b, ty) = matrix[0]
    def (c, d, tx) = matrix[1]
    double det = a * d - b * c
    if (!matrix.flatten().every { it instanceof Number && Double.isFinite(it as double) })
        throw new IllegalStateException("the alignment matrix contains values that are not finite numbers")
    if (!(Math.abs(det) > 1e-9 * Math.max(1.0d, Math.abs(a * d) + Math.abs(b * c))))
        throw new IllegalStateException("the alignment matrix is singular (determinant " + det + "): it cannot be applied")
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


// ---------------------------------------------------------------- macro recording / replay
/** Module stand-in for a macro call: same getInput(name) contract as the harvested dialog module, values from the options string. */
class MacroModule {
    Map values = [:]
    def getInput(String name) { values[name] }
}

/** Options string of a macro call (run("LabConstrictor Tools...", "app=[X] tool=[Y] image=a.tif ...")), or null for an interactive run. */
String macroOptions() {
    def given = System.clearProperty("lc.macro.options") ?: Macro.getOptions()   // consumed here: the menu command waits for this (see its hand-off)
    return given && given.trim() ? given : null
}

/** The dialog's choices as macro options (key -> text). Images by window title, or `<name>_file` for a file. */
Map<String, String> toMacroOptions(String appName, Map tool, def module) {
    def o = [app: appName, tool: tool.label] as LinkedHashMap
    tool.inputs.each { p ->
        switch (p.type) {
            case ["image", "labels"]:
                def file = module.getInput(p.name + "_file") as File
                def imp = module.getInput(p.name)
                if (p.region_of && module.getInput("selection_" + p.name)) o["selection_" + p.name] = "true"
                if (file) o[p.name + "_file"] = file.path
                else if (imp && (p.required || module.getInput("use_" + p.name))) o[p.name] = imp instanceof ImagePlus ? imp.getTitle() : imp as String
                if (p.pick_channel && module.getInput(p.name + "_channel") != null) o[p.name + "_channel"] = module.getInput(p.name + "_channel").toString()
                break
            case ["table", "file", "folder"]:
                def f = module.getInput(p.name) as File
                if (f) o[p.name] = f.path
                break
            default:
                if (p.nullable && !module.getInput("set_" + p.name)) break      // unset stays out of the macro
                def v = module.getInput(p.name)
                if (v != null) o[p.name] = v.toString()
        }
    }
    return o
}

/** Tell the macro recorder what this run was (only when the recorder is open); the command line becomes a replayable run(...). */
void recordRun(String appName, Map tool, def module) {
    try {
        if (!Recorder.record) return
        toMacroOptions(appName, tool, module).each { k, v -> Recorder.recordOption(k, v) }
    } catch (Exception problem) {            // broad on purpose: recording must never break a run, but the failure is not hidden
        lcLog("WARN", "could not record the run for the macro recorder: " + problem, problem)
        IJ.log("LabConstrictor: this run could not be recorded for the macro recorder (" + problem + ")")
    }
}

/** The keys of a macro options string (`key=value key=[value with spaces] flag`). */
List<String> macroKeys(String options) {
    def keys = []
    def matcher = options =~ /(\w+)(?:=(?:\[[^\]]*\]|\S*))?/
    while (matcher.find()) keys << matcher.group(1)
    return keys
}

/** Strict number parsing for macro values: no truncation, no NaN/Infinity, within the declared bounds. */
def macroNumber(Map p, String text) {
    def number
    if (p.type == "integer") {
        if (!(text.trim() ==~ /[+-]?\d+/)) throw new IllegalArgumentException("'" + p.label + "' must be a whole number, got '" + text + "'")
        number = new BigInteger(text.trim().replaceFirst(/^\+/, ""))
        if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) throw new IllegalArgumentException("'" + p.label + "' is out of range: " + text)
        number = number.intValue()
    } else {
        try { number = Double.parseDouble(text.trim()) } catch (NumberFormatException ignored) { throw new IllegalArgumentException("'" + p.label + "' must be a number, got '" + text + "'") }
        if (!Double.isFinite(number)) throw new IllegalArgumentException("'" + p.label + "' must be a finite number, got '" + text + "'")
    }
    if (p.minimum != null && number < p.minimum) throw new IllegalArgumentException("'" + p.label + "' must be >= " + p.minimum + ", got " + text)
    if (p.maximum != null && number > p.maximum) throw new IllegalArgumentException("'" + p.label + "' must be <= " + p.maximum + ", got " + text)
    return number
}

/** Build the module values for a replayed call; unknown options, tools or parameters, missing images and invalid values raise IllegalArgumentException (shown, logged). */
MacroModule moduleFromMacro(Map tool, String options) {
    def allowed = ["app", "tool"] + tool.inputs.collectMany { p -> [p.name] + (p.type in ["image", "labels"] ? [p.name + "_file"] : []) + (p.pick_channel ? [p.name + "_channel"] : []) + (p.region_of ? ["selection_" + p.name] : []) }
    def unknown = macroKeys(options).findAll { !(it in allowed) }
    if (unknown) throw new IllegalArgumentException("unknown option" + (unknown.size() > 1 ? "s " : " ") + unknown.collect { "'" + it + "'" }.join(", ") +
                                                    " (the tool accepts: " + allowed.findAll { it != "app" && it != "tool" }.join(", ") + ")")
    def module = new MacroModule()
    tool.inputs.each { p ->
        def text = Macro.getValue(options, p.name, null)
        def fileText = Macro.getValue(options, p.name + "_file", null)
        if (p.pick_channel) {                                                  // PickChannel: the channel to hand the tool (1 = first)
            def channelText = Macro.getValue(options, p.name + "_channel", null)
            module.values[p.name + "_channel"] = channelText == null ? 1 : macroNumber([type: "integer", label: p.label + " channel", minimum: 1], channelText)
        }
        if (p.nullable && p.type in ["string", "integer", "float", "choice", "boolean"]) {   // optional, no default: not in the macro = unset
            module.values["set_" + p.name] = text != null
            if (text == null) return
        }
        if (p.region_of) module.values["selection_" + p.name] = (Macro.getValue(options, "selection_" + p.name, "false") ?: "true").toLowerCase() in ["true", "1", "yes"]   // a bare flag counts as true
        switch (p.type) {
            case ["image", "labels"]:
                if (p.region_of && module.values["selection_" + p.name]) break          // the selection is the value (taken when the run starts)
                if (fileText) { module.values[p.name + "_file"] = new File(fileText); module.values["use_" + p.name] = true; break }
                if (text == null && !p.required) break
                if (text == null) throw new IllegalArgumentException("'" + p.label + "' is required: give " + p.name + "=<window title> or " + p.name + "_file=<path> (a macro never guesses the current image)")
                def imp = WindowManager.getImage(text)
                if (imp == null) throw new IllegalArgumentException("'" + p.label + "': no open image" + (text ? " called '" + text + "'" : "") + " (use " + p.name + "=<window title> or " + p.name + "_file=<path>)")
                module.values[p.name] = imp; module.values["use_" + p.name] = true
                break
            case ["table", "file", "folder"]:
                if (text) module.values[p.name] = new File(text)
                break
            case "boolean":
                if (text == null) module.values[p.name] = p.default ?: false
                else if (text.toLowerCase() in ["", "true", "1", "yes"]) module.values[p.name] = true       // a bare flag counts as true (macro convention)
                else if (text.toLowerCase() in ["false", "0", "no"]) module.values[p.name] = false
                else throw new IllegalArgumentException("'" + p.label + "' must be true or false, got '" + text + "'")
                break
            case "integer":
                module.values[p.name] = text == null ? (p.default ?: 0) as Integer : macroNumber(p, text)
                break
            case "float":
                module.values[p.name] = text == null ? (p.default ?: 0) as Double : macroNumber(p, text)
                break
            case "choice":
                if (text != null && !(text in p.choices)) throw new IllegalArgumentException("'" + p.label + "' must be one of " + p.choices + ", got '" + text + "'")
                module.values[p.name] = text == null ? (p.default ?: p.choices[0]) : text
                break
            default:
                module.values[p.name] = text == null ? (p.default ?: "") : text
        }
    }
    return module
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

    def macro = macroOptions()
    if (macro && macro =~ /(^|\s)copy_last(=|\s|$)/) {         // menu entry "Copy last run as command": no dialog, nothing is run
        def file = new File(new File(lcHome(), "state"), "last_command.json")
        if (!file.isFile()) return failEarly(summary, "nothing to copy yet: run a tool first")
        def last = new JsonSlurper().parseText(file.getText("UTF-8"))
        def kind = Macro.getValue(macro, "kind", "terminal")
        def text = kind == "python" ? last.python : last.terminal
        boolean copied = true
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null)
        } catch (java.awt.HeadlessException | IllegalStateException | SecurityException problem) {    // no clipboard here (headless, locked by another program)
            copied = false
            lcLog("WARN", "clipboard not available: " + problem, problem)
            summary.clipboard_error = problem.toString()
        }
        if (copied) {
            IJ.log("LabConstrictor: copied the " + kind + " command of the last run (" + last.app + ": " + last.tool + "):\n" + text)
            IJ.showStatus("LabConstrictor: copied the last run as a " + kind + " command")
        } else {
            IJ.log("LabConstrictor: could not copy to the clipboard (" + summary.clipboard_error + "); the " + kind + " command of the last run (" + last.app + ": " + last.tool + ") is:\n" + text)
        }
        return hooks.finish(summary + [copied_last: text, copied_kind: kind])
    }
    def appName, toolLabel, app, tool, module
    if (macro) {                                               // replay of a recorded macro: no choosers, no dialog
        appName = found.apps.keySet().find { it.equalsIgnoreCase(Macro.getValue(macro, "app", "") ?: "") }
        if (appName == null) return failEarly(summary, "unknown app '" + Macro.getValue(macro, "app", "") + "' (registered: " + found.apps.keySet().join(", ") + ")")
        app = found.apps[appName]
        tool = app.schema.tools.find { it.label.equalsIgnoreCase(Macro.getValue(macro, "tool", "") ?: "") || it.id == Macro.getValue(macro, "tool", "") }
        if (tool == null) return failEarly(summary, "unknown tool '" + Macro.getValue(macro, "tool", "") + "' in " + appName + " (tools: " + app.schema.tools.collect { it.label }.join(", ") + ")")
        try { module = moduleFromMacro(tool, macro) } catch (IllegalArgumentException | NumberFormatException problem) { return failEarly(summary, problem.message ?: problem.toString()) }
        summary.replayed_from_macro = true
    } else {
        appName = pickOne("LabConstrictor", "Application", found.apps.keySet().toList(), hooks.preferred("app"))
        if (appName == null) return hooks.finish(summary + [cancelled: true])
        app = found.apps[appName]
        toolLabel = pickOne("LabConstrictor: " + app.display_name, "Tool", app.schema.tools.collect { it.label }, hooks.preferred("tool"))
        if (toolLabel == null) return hooks.finish(summary + [cancelled: true])
        tool = app.schema.tools.find { it.label == toolLabel }

        def openImages = WindowManager.getImageTitles() as List<String>
        def dialogStarted = System.nanoTime()
        def (info, links) = buildToolDialog(tool, openImages, choiceListsFor(app, tool))
        summary.timings.dialog_construction_s = (System.nanoTime() - dialogStarted) / 1e9
        summary.dialog_inputs = info.inputs().collect { it.getName() }
        summary.dialog_choices = info.inputs().findAll { it.getChoices() }.collectEntries { [(it.getName()): it.getChoices() as List] }
        summary.dialog_styles = info.inputs().findAll { it.getWidgetStyle() }.collectEntries { [(it.getName()): it.getWidgetStyle()] }
        summary.dialog_defaults = info.inputs().findAll { it.getChoices() }.collectEntries { [(it.getName()): it.getDefaultValue()] }
        module = harvest(info, tool.label, links)
        if (module == null) return hooks.finish(summary + [cancelled: true])
        recordRun(appName, tool, module)
    }

    def jobDir = Files.createTempDirectory("lcjob_fiji_").toFile()
    try {                                                        // the folder goes whatever happens from here on
        List exported
        try {
            exported = exportInputs(tool, module, jobDir)
        } catch (IllegalArgumentException problem) {           // missing file, nothing chosen for a required image
            if (hooks.interactive) IJ.error("LabConstrictor", problem.message)
            return hooks.finish(summary + [error: problem.message])
        }
        def (inputs, images) = exported
        runAndShow(app, tool, inputs, images, summary)
        if (summary.status == "COMPLETE") rememberDepends(app, tool, inputs)
    } finally {
        removeFolder(jobDir, "job folder of the run")
    }
    hooks.finish(summary)
}

// ---------------------------------------------------------------- Copy as command (same text as labconstrictor_tools.command)
@Field final Map<String, String> FILE_PLACEHOLDERS = [image: "image.tif", labels: "labels.tif", table: "table.csv", file: "file", folder: "folder"]

String shellQuote(String text, boolean windows) {
    if (windows) return (text && text ==~ /[A-Za-z0-9_.:\/\\=+,-]+/) ? text : '"' + text.replace('"', '\\"') + '"'
    return (text && text ==~ /[A-Za-z0-9_@%+=:,.\/-]+/) ? text : "'" + text.replace("'", "'\"'\"'") + "'"
}

/** The values a command line needs: the file behind each image (its own file, or the file the open image came from), else a placeholder. */
Map commandValues(Map tool, Map inputs, Map images) {
    def values = [:], missing = []
    tool.inputs.each { p ->
        def value = inputs[p.name]
        if (p.type in ["image", "labels"]) {
            def source = images[p.name]
            def path = source instanceof ImagePlus ? ((source.getOriginalFileInfo()?.directory && source.getOriginalFileInfo()?.fileName) ? new File(source.getOriginalFileInfo().directory, source.getOriginalFileInfo().fileName).path : null)
                     : source instanceof String ? source : null
            if (path != null) values[p.name] = path
        } else if (value != null) values[p.name] = value
        if (p.type in FILE_PLACEHOLDERS.keySet() && p.required && values[p.name] == null) { values[p.name] = FILE_PLACEHOLDERS[p.type]; missing << p.name }
    }
    return [values: values, missing: missing]
}

String commandText(String kind, Map app, Map tool, Map inputs, Map images) {
    def built = commandValues(tool, inputs, images)
    def note = built.missing ? "# replace the file for: " + built.missing.join(", ") + "\n" : ""
    def given = tool.inputs.findAll { built.values.containsKey(it.name) }
    if (kind == "python") {
        def literal = { v -> v instanceof Boolean ? (v ? "True" : "False") : v instanceof Number ? v.toString() : "'" + v.toString().replace("\\", "\\\\").replace("'", "\\'") + "'" }
        def body = given ? "{\n" + given.collect { "    '" + it.name + "': " + literal(built.values[it.name]) + "," }.join("\n") + "\n}" : "{}"
        return note + "from labconstrictor_tools import client\n\ntask = client.run_once('" + app.name + "', '" + tool.id + "', " + body + ")\n" +
               'print(task.status, task.outputs if task.status == "COMPLETE" else task.error)'
    }
    boolean windows = System.getProperty("os.name").toLowerCase().contains("win")
    def parts = [app.python as String, "-m", "labconstrictor_tools", "run", app.name as String, tool.id as String].collect { shellQuote(it, windows) }
    given.each { p ->
        def v = built.values[p.name]
        parts << shellQuote(p.name + "=" + (v instanceof Boolean ? (v ? "true" : "false") : v.toString()), windows)
    }
    return note + parts.join(" ")
}

void runAndShow(Map app, Map tool, Map inputs, Map images, Map summary) {
    def outcome = runTool(app, tool, inputs)
    summary << [request: inputs, status: outcome.status, error: outcome.error, progress_events: outcome.progress,
                worker_alive_after: outcome.workerAlive, cancel_requested: outcome.cancelRequested]
    summary.timings.worker_run_s = outcome.seconds
    try {                                                    // Copy as command: the Log carries what repeats this run outside Fiji
        summary.command_line = commandText("terminal", app, tool, inputs, images)
        summary.python_snippet = commandText("python", app, tool, inputs, images)
        IJ.log("LabConstrictor: to repeat this run outside Fiji, copy from here:\n" + summary.command_line + "\n--- or in Python:\n" + summary.python_snippet)
        def stateDir = new File(lcHome(), "state"); stateDir.mkdirs()
        new File(stateDir, "last_command.json").setText(groovy.json.JsonOutput.toJson([app: app.name, tool: tool.id, terminal: summary.command_line, python: summary.python_snippet]), "UTF-8")
    } catch (Exception problem) {          // broad on purpose: the copyable command is a convenience and must never break a run; the failure is shown, not hidden
        lcLog("WARN", "could not build the command text: " + problem, problem)
        IJ.log("LabConstrictor: could not build the command to repeat this run (" + problem + ")")
        summary.command_error = problem.toString()
    }
    summary.run_record = writeRunRecord(app, tool, inputs, outcome, summary)
    if (outcome.complete) {
        summary.interpreter = outcome.outputs.diagnostics
        summary << showResults(app, outcome.outputs.results, images, tool)
        if (summary.display_errors && hooks.interactive)
            IJ.error("LabConstrictor: " + app.display_name, "The tool finished, but not everything could be shown:\n" + summary.display_errors.join("\n"))
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
        IJ.log("LabConstrictor: the run was " + (outcome.cancelRequested ? "cancelled" : outcome.status.toLowerCase()))
    }
}
def failEarly(Map summary, String message) {
    lcLog("ERROR", "macro call rejected: " + message)
    if (hooks.interactive) IJ.error("LabConstrictor", message)
    IJ.log("LabConstrictor: " + message)
    return hooks.finish(summary + [error: message])
}
/** Entry point: anything unexpected is logged with its stack trace and shown with the log's location. */
def labConstrictorMain() {
    lcLog("INFO", "---- session start: " + IJ.getFullVersion() + " java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + " LC_HOME=" + lcHome())
    try {
        return labConstrictorRun()
    } catch (Throwable problem) {          // the entry point: broad on purpose (anything, even an Error, must end in the log and a dialog, never a silent script)
        lcLog("ERROR", "unexpected failure: " + problem, problem)
        if (hooks.interactive) IJ.error("LabConstrictor", "Unexpected error: " + problem + "\n\nThe full report is in the log file:\n" + new File(lcHome(), "logs/labconstrictor.log").path)
        return hooks.finish([error: problem.toString()])
    }
}
labConstrictorMain()
