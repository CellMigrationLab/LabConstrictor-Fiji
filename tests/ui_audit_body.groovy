// Fiji side of the UI audit (tests/ui_audit.py; NOT part of the product): a test harness in the sense of tests/test_harness.groovy, selected with LC_FIJI_HARNESS=<this file>.
// One Fiji start does ONE job, named by "mode" in the plan file $LC_UI_PLAN (written by ui_audit.py), and writes what it saw as one JSON object per line to $LC_UI_OUT, line by line
// as it goes (a crash or a kill loses nothing already written). It only OBSERVES and ACTS: every judgement is made by ui_audit.py, from the schema, the case and the files on disk.
//   {"kind": "check", "id", "status", "detail", "control", "tool", "seconds"}   one control activated (a row of the matrix)
//   {"kind": "obs", "what": ..., ...}                                           a fact: dialog (title, items), screenshot, activation_plan, chooser_ok, click_failed, settled / settle_timeout (the layout of a dialog)
//   {"kind": "components", "scope", "controls", "unknown", "texts", "geometry", "windows"}   everything one dialog holds, where it is and which windows show
//   {"kind": "finish", "summary": {...}, ...}                                   the plugin's own summary of the run (the product's `finish` hook); always the last line
//   {"kind": "fatal", "detail"}                                                 the audit of this start cannot go on
// Modes: cancel (Cancel on the first chooser), chooser (read the choosers and the tool offered, then Cancel), controls (open the form, press every control, Cancel),
//        run (fill the form from the case, screenshot it, OK, screenshot the result), copy (the "Copy last run as command" entry; no dialog).
// plan.inject (negative controls of tests/ui_audit_negative.py): extra_control | remove_control | offscreen_ok | blank_screens.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import ij.IJ
import ij.WindowManager
import java.awt.Container
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

@Field final int DIALOG_WAIT_MS = 120000        // how long the plugin gets to show the next dialog (discovery, the worker's answers for dropdowns)
@Field final int SETTLE_MS = 700                // a dialog is read only after its position, size and component count have not changed for this long (H-F1: a late layout under CPU load)
@Field final int OVERSIZE_WAIT_MS = 10000       // a dialog bigger than the screen is given this long, unchanged, to become sane (a late layout); after that it is read as it is and the geometry check fails it
@Field final int FILL_SETTLE_MS = 1200          // after the form is filled: the callbacks of SciJava run on a thread of their own
@Field final int AFTER_RUN_SETTLE_MS = 2000     // after the run: result windows get this long to paint before the screen is taken
@Field final int CLOSE_WAIT_MS = 5000           // a real click on OK or Cancel must close its dialog within this
@Field final int POLL_MS = 100
@Field final int SHOT_MIN_PX = 256             // a result image narrower than this is zoomed before the after-run picture
@Field final int CANCEL_WAIT_MS = 6000          // after a Cancel the plugin gets this long to end (its finish hook ends Fiji); then what it does instead is reported and Fiji is ended here
@Field final int MAX_TEXT = 20000               // of the Log kept in the finish line
@Field final List<String> DROPPED = ["interpreter", "frames", "unit_catalog", "calibration_states_initial", "calibration_states", "modifiers"]   // summary keys that are not needed here

def plan = new JsonSlurper().parseText(new File(System.getenv("LC_UI_PLAN")).getText("UTF-8"))
def tree = new GroovyShell(this.class.classLoader).evaluate(new File(System.getenv("LC_UI_TREE")))
File outFile = new File(System.getenv("LC_UI_OUT"))
File shots = new File(plan.shots as String)
shots.mkdirs()
def lock = new Object()
def preloaded = [] as Set

def emit = { Map record ->
    synchronized (lock) { outFile.withWriterAppend("UTF-8") { it << JsonOutput.toJson(record) << "\n" } }
}
// One activation in its own block: a failure or an exception becomes a FAIL row and the audit goes on with the next control. Returns true on PASS.
def guarded = { String id, String control, Closure body ->
    long began = System.nanoTime()
    String status = "PASS", detail
    try {
        detail = body() as String
    // groovylint-disable-next-line CatchThrowable
    } catch (Throwable problem) {          // broad on purpose: whatever went wrong is the result of this control, never the end of the audit
        status = "FAIL"
        detail = problem instanceof AssertionError ? problem.message : problem.class.simpleName + ": " + problem.message
    }
    emit([kind: "check", id: id, status: status, detail: detail, control: control, tool: plan.tool_key, seconds: (System.nanoTime() - began) / 1e9d])
    return status == "PASS"
}

def distinctColours = { BufferedImage image ->        // true when the picture is not one flat colour
    int first = image.getRGB(0, 0)
    for (int y = 0; y < image.height; y++) for (int x = 0; x < image.width; x++) if (image.getRGB(x, y) != first) return true
    return false
}
def screenshot = { String which, java.awt.Rectangle area ->
    File target = new File(shots, plan.tool_id + "_" + which + ".png")
    target.delete()                                    // a file from an earlier run can never pass for this one
    def record = [kind: "obs", what: "screenshot", which: which, path: target.path]
    try {
        BufferedImage image = new Robot().createScreenCapture(area)
        if (plan.inject == "blank_screens") {
            image = new BufferedImage(area.width as int, area.height as int, BufferedImage.TYPE_INT_RGB)
            image.graphics.fillRect(0, 0, image.width, image.height)
        }
        ImageIO.write(image, "png", target)
        record << [exists: target.isFile(), bytes: target.length(), width: image.width, height: image.height, not_flat: distinctColours(image)]
    // groovylint-disable-next-line CatchThrowable
    } catch (Throwable problem) {          // the screenshot is evidence: a failure is reported with its reason and the check on it fails
        record << [exists: target.isFile(), error: problem.class.simpleName + ": " + problem.message]
    }
    emit(record)
}
def screenSize = { Toolkit.getDefaultToolkit().getScreenSize() }
def textsOf
textsOf = { java.awt.Component c ->
    def found = []
    if (c instanceof JLabel && c.text) found << tree.normalise(c.text)
    if (c instanceof JTextComponent && c.text) found << tree.normalise(c.text)
    if (c instanceof Container) c.components.each { found.addAll(textsOf(it)) }
    return found
}
def rectOf = { java.awt.Component c ->
    try {
        return tree.onEdt { def at = c.locationOnScreen; [at.x, at.y, c.width, c.height] }
    // groovylint-disable-next-line CatchException
    } catch (Exception ignored) {          // a component that is not showing has no place on the screen: [0, 0, 0, 0] says so and the geometry check fails on it
        return [0, 0, 0, 0]
    }
}
def describe = { List controls -> controls.collect { [id: it.id, kind: it.kind, label: it.label, text: it.text, rect: rectOf(it.comp)] } }
def windowsNow = {
    tree.onEdt {
        Window.getWindows().findAll { it.isShowing() }.collect {
            [cls: it.class.name, title: (it instanceof Dialog || it instanceof java.awt.Frame ? it.title : "") ?: "", dialog: it instanceof Dialog, x: it.x, y: it.y, w: it.width, h: it.height]
        }
    }
}
def openImages = { WindowManager.getImageTitles() as Set }

// A real mouse click; when it cannot be done (off screen) or does not close the dialog, that is reported (obs click_failed: ui_audit.py fails the check) and the dialog is closed
// by the component's own click so that the audit can go on.
def press = { Map control, Dialog dialog, String what ->
    String problem = null
    try {
        tree.realClick(control.comp)
        long deadline = System.currentTimeMillis() + CLOSE_WAIT_MS
        while (dialog.isShowing() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        if (dialog.isShowing()) problem = "a real click on " + what + " did not close the dialog within " + CLOSE_WAIT_MS + " ms"
    } catch (AssertionError refused) {
        problem = what + ": " + refused.message
    }
    if (problem) {
        emit([kind: "obs", what: "click_failed", id: what, detail: problem])
        SwingUtilities.invokeLater { control.comp.doClick() }
    }
}

// Cancel, then leave: `observe` waits for the plugin's own end (the finish hook exits Fiji and writes the summary); when it goes on instead (a Cancel that does not cancel) the windows it shows
// are reported (obs cancel_ignored) and Fiji is ended here. Without `observe` Fiji is ended at once, so that a plugin that goes on cannot run a tool just because the audit pressed Cancel.
def cancelAndLeave = { Map control, Dialog dialog, String what, boolean observe ->
    press(control, dialog, what)
    if (observe) {
        Thread.sleep(CANCEL_WAIT_MS)
        def shown = windowsNow().findAll { it.dialog }.collect { it.title }
        emit([kind: "obs", what: "cancel_ignored", id: what, detail: "the plugin did not end within " + CANCEL_WAIT_MS + " ms of the click on Cancel; dialogs showing now: " + shown])
    }
    Thread.sleep(POLL_MS)
    // groovylint-disable-next-line SystemExit
    System.exit(observe ? 3 : 0)
}

def inject = { Dialog dialog, Closure enumerateAgain ->
    if (plan.inject == "extra_control") tree.onEdt { dialog.contentPane.add(new JButton("Injected"), java.awt.BorderLayout.NORTH); dialog.pack() }
    if (plan.inject == "remove_control") {
        def victim = enumerateAgain().controls.find { it.id == "text_field" }
        tree.onEdt { victim.comp.parent.remove(victim.comp); dialog.pack() }
    }
    if (plan.inject == "offscreen_ok") tree.onEdt { dialog.setLocation(screenSize().width as int - 60, 20) }
}

// ---- the dialogs
def record = { Dialog dialog, String scope, Map found ->
    def size = screenSize()
    emit([kind: "components", scope: scope, controls: describe(found.controls), unknown: found.unknown, texts: textsOf(dialog),
          geometry: [screen: [size.width, size.height], dialog: tree.onEdt { [dialog.x, dialog.y, dialog.width, dialog.height] }], windows: windowsNow()])
}
def pressEvery = { Map found ->
    def doable = found.controls.findAll { !(it.activation in ["press", "none"]) }
    emit([kind: "obs", what: "activation_plan", n: doable.size(), ids: doable.collect { it.id }])
    doable.each { control -> guarded("ui_fiji_form_" + plan.tool_key + "_controls", control.id + " '" + (control.label ?: control.text) + "'") { tree.activate(control) } }
}
def fillForm = { Map found ->
    def failed = []
    plan.fills.each { fill ->
        try {
            tree.fill(found.controls, fill.label as String, fill.value)
        // groovylint-disable-next-line CatchThrowable
        } catch (Throwable problem) {
            failed << (fill.label + ": " + (problem instanceof AssertionError ? problem.message : problem.class.simpleName + ": " + problem.message))
        }
    }
    return failed
}

// Waits until the dialog has stopped changing: the same place, size and number of components (all levels) in every poll for SETTLE_MS; a layout that comes late under CPU load (H-F1: the
// simulate_sample form seen at 1066x3024 on a 1600x1000 screen) is then read in its final state, never in a half-built one. True when it settled (obs settled: how long it took); false after
// the plan's settle_timeout_ms (obs settle_timeout: the states seen), and the caller must not read the dialog: ui_audit.py turns that into a FAIL row that says so, never into a pass. A dialog
// that is bigger than the screen has to stay unchanged for OVERSIZE_WAIT_MS instead (the late layout may still shrink it); if it is still too big then, it is a real oversize and is read.
def settle = { Dialog dialog, String what ->
    def countAll
    countAll = { java.awt.Component c -> 1 + (c instanceof Container ? c.components.sum(0) { countAll(it) } : 0) }
    int timeout = plan.settle_timeout_ms as int
    long began = System.currentTimeMillis()
    long changed = began
    def last = null
    def states = [] as LinkedHashSet
    while (System.currentTimeMillis() - began < timeout) {
        def now = tree.onEdt { [dialog.x, dialog.y, dialog.width, dialog.height, countAll(dialog), dialog.isShowing()] }
        if (now != last) {
            last = now
            changed = System.currentTimeMillis()
            states << String.format("%dx%d at (%d,%d), %d components", now[2], now[3], now[0], now[1], now[4])
        }
        boolean oversized = last[2] > screenSize().width || last[3] > screenSize().height
        if (last[5] && System.currentTimeMillis() - changed >= (oversized ? OVERSIZE_WAIT_MS : SETTLE_MS)) {
            emit([kind: "obs", what: "settled", dialog: what, waited_ms: changed - began, states: states.size(), oversized: oversized])
            return true
        }
        Thread.sleep(POLL_MS)
    }
    emit([kind: "obs", what: "settle_timeout", dialog: what, detail: "the dialog '" + dialog.title + "' was still changing after " + timeout + " ms (states seen: " + states.take(8).join("; ") + ")"])
    return false
}

// A dialog that never settled is not read and not clicked (a click on a half-built dialog proves nothing, and the Cancel of a form can run the tool): Fiji is ended here.
def leaveUnsettled = {
    Thread.sleep(POLL_MS)
    // groovylint-disable-next-line SystemExit
    System.exit(0)
}

def handleChooser = { Dialog dialog, String which, Map state ->
    if (!settle(dialog, "chooser_" + which)) return leaveUnsettled()
    def found = tree.enumerate(dialog, "chooser", which, openImages())
    record(dialog, "chooser_" + which, found)
    def combo = found.controls.find { it.id == "chooser_" + which }
    def items = combo ? tree.itemsOf(combo.comp) : []
    emit([kind: "obs", what: "dialog", dialog: which, title: dialog.title, items: items])
    def button = { String id -> found.controls.find { it.id == id } }
    if (plan.mode == "cancel") return cancelAndLeave(button("chooser_cancel"), dialog, "chooser_cancel", true)
    if (plan.emit_panel && combo) guarded("ui_fiji_panel_chooser_" + which, "chooser_" + which) { tree.activate(combo) + "; lists " + items }
    def target = which == "app" ? plan.app : plan.tool_choice
    int index = items.indexOf(target)
    if (index < 0) {
        emit([kind: "obs", what: "dialog_problem", dialog: which, detail: "'" + target + "' is not in the chooser (items: " + items + ")"])
        return cancelAndLeave(button("chooser_cancel"), dialog, "chooser_cancel", false)
    }
    if (plan.mode == "chooser" && which == "app") {            // switching: every other app first, the wanted one last
        items.eachWithIndex { item, i -> if (i != index) tree.onEdt { combo.comp.setSelectedIndex(i) } }
    }
    tree.onEdt { combo.comp.setSelectedIndex(index) }
    Thread.sleep(POLL_MS)
    if (plan.mode == "chooser" && which == "tool") return cancelAndLeave(button("chooser_cancel"), dialog, "chooser_cancel", false)
    state.okPending = which
    press(button("chooser_ok"), dialog, "chooser_ok")
}

// Seeded late layouts (negative controls): `late_layout` makes the form too big for the screen for 3 s and then right again (a correct audit waits, reads it right and passes); `never_settles`
// resizes it every 200 ms for as long as the audit looks (a correct audit says so and does not read it).
def injectLayout = { Dialog dialog ->
    if (plan.inject == "late_layout") {
        Thread.start {
            tree.onEdt { dialog.setSize(1066, 3024) }
            Thread.sleep(3000)
            tree.onEdt { dialog.pack() }
        }
    }
    if (plan.inject == "never_settles") {
        Thread.start {
            for (int n = 1; n < 1000 && dialog.isShowing(); n++) {
                tree.onEdt { dialog.setSize(400 + n, 300 + n) }
                Thread.sleep(200)
            }
        }
    }
}

def handleForm = { Dialog dialog, Map state ->
    injectLayout(dialog)
    if (!settle(dialog, "form")) return leaveUnsettled()
    emit([kind: "obs", what: "dialog", dialog: "form", title: dialog.title, items: []])
    inject(dialog) { tree.enumerate(dialog, "form", "form", openImages()) }
    def found = tree.enumerate(dialog, "form", "form", openImages())
    record(dialog, "form_" + plan.tool_key, found)
    def cancel = found.controls.find { it.id == "form_cancel" }
    def ok = found.controls.find { it.id == "form_ok" }
    if (plan.mode == "chooser") return cancelAndLeave(cancel, dialog, "form_cancel", false)
    if (plan.mode == "cancel_form") return cancelAndLeave(cancel, dialog, "form_cancel", true)   // alpha.3 FJ-1b: a plain Cancel on the untouched form, the plugin must end
    if (plan.mode == "ok_empty") {                                                                // alpha.3 FJ-1b: OK on the untouched form (a required input empty): the plugin must refuse
        screenshot("form", tree.onEdt { dialog.bounds })
        return press(ok, dialog, "form_ok")
    }
    if (plan.mode == "controls") {
        screenshot("form", tree.onEdt { dialog.bounds })
        pressEvery(found)
        return cancelAndLeave(cancel, dialog, "form_cancel", plan.emit_panel as boolean)
    }
    def failed = fillForm(found)
    if (failed) {
        emit([kind: "obs", what: "fill_failed", detail: "the form could not be filled from the case: " + failed.join("; ")])
        // groovylint-disable-next-line SystemExit
        System.exit(0)                                          // no click: the Cancel of this plugin runs the tool (see ui_fiji_panel_form_cancel), and nothing may run on a form that is half filled
    }
    Thread.sleep(FILL_SETTLE_MS)
    screenshot("form", tree.onEdt { dialog.bounds })
    press(ok, dialog, "form_ok")
}

// A tool with exactly one input that is a file gets SciJava's native file prompt instead of a form (one parameter, one prompt). The audit reports it (obs single_input_prompt); in a run
// the case's file is put into it and it is closed as a person's OK would, and the driver goes on; otherwise the prompt is closed and this start ends (no click: see cancelAndLeave).
def handleFileChooser = { FileDialog chooser ->
    emit([kind: "obs", what: "single_input_prompt", title: chooser.title, open: chooser.mode == FileDialog.LOAD])
    if (plan.mode == "run" && plan.fills && plan.fills[0].value) {
        File target = new File(plan.fills[0].value as String)
        Thread.sleep(SETTLE_MS)
        screenshot("form", new java.awt.Rectangle(screenSize()))
        tree.onEdt { chooser.setDirectory(target.parent); chooser.setFile(target.name) }
        SwingUtilities.invokeLater { chooser.setVisible(false) }
        return true
    }
    SwingUtilities.invokeLater { chooser.dispose() }
    Thread.sleep(POLL_MS * 3)
    // groovylint-disable-next-line SystemExit
    System.exit(0)
}

def drive = {
    def handled = [] as Set
    def state = [okPending: null]
    long deadline = System.currentTimeMillis() + DIALOG_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
        def windows = tree.showingWindows()
        def file = windows.find { it instanceof FileDialog && !handled.contains(it) }
        Dialog dialog = windows.find { it instanceof Dialog && !(it instanceof FileDialog) && !handled.contains(it) && it.title }
        if (file) {
            handled << file
            if (handleFileChooser(file)) continue
            return null
        }
        if (dialog == null) {
            Thread.sleep(POLL_MS)
            continue
        }
        handled << dialog
        if (state.okPending) {                                  // the OK of a chooser opened this dialog
            emit([kind: "obs", what: "chooser_ok", which: state.okPending, opened: dialog.title])
            state.okPending = null
        }
        String title = dialog.title
        if (title == "LabConstrictor") handleChooser(dialog, "app", state)
        else if (title.startsWith("LabConstrictor: ")) handleChooser(dialog, "tool", state)
        else if (plan.tool_label && title.contains(plan.tool_label as String)) return handleForm(dialog, state)
        else { emit([kind: "obs", what: "dialog", dialog: "other", title: title, items: []]); continue }
        deadline = System.currentTimeMillis() + DIALOG_WAIT_MS
        if (plan.mode == "cancel") return null
    }
    emit([kind: "fatal", detail: "no dialog the audit expected appeared within " + DIALOG_WAIT_MS + " ms (the plan: " + plan.mode + " for " + plan.app + "/" + plan.tool_id + ")"])
    // groovylint-disable-next-line SystemExit
    System.exit(2)
}
def driver = {
    try {
        drive()
    // groovylint-disable-next-line CatchThrowable
    } catch (Throwable problem) {          // the driver thread must never die silently: say why, and end Fiji (ui_audit.py reports every check that was not reached)
        emit([kind: "fatal", detail: "the driver stopped: " + problem.class.simpleName + ": " + problem.message])
        // groovylint-disable-next-line SystemExit
        System.exit(2)
    }
}

// ---- hooks of the product script (see the top of LabConstrictor_Tools.groovy)
def plain
plain = { def value, int depth ->
    if (value == null || value instanceof Number || value instanceof Boolean) return value
    if (value instanceof Map && depth < 5) return value.collectEntries { k, v -> [(k.toString()): plain(v, depth + 1)] }
    if (value instanceof Collection && depth < 5) return value.take(200).collect { plain(it, depth + 1) }
    return value.toString().take(2000)
}
// The labels of the entries of Plugins > LabConstrictor in Fiji's own menu bar (null when there is no such menu).
def menuEntries = {
    def bar = IJ.getInstance()?.getMenuBar()
    def plugins = bar == null ? null : (0..<bar.menuCount).collect { bar.getMenu(it) }.find { it.label == "Plugins" }
    def ours = plugins == null ? null : (0..<plugins.itemCount).collect { plugins.getItem(it) }.find { it.label == "LabConstrictor" }
    return ours == null ? null : (0..<ours.itemCount).collect { ours.getItem(it).label }
}
// The text of ImageJ's status line (the product says 'LabConstrictor: cancelled' there); null when it cannot be read.
def statusText = {
    try {
        def found = []
        def walk
        walk = { java.awt.Component c ->
            if ((c instanceof java.awt.Label || c instanceof JLabel) && c.text) found << c.text
            if (c instanceof Container) c.components.each { walk(it) }
        }
        tree.onEdt { walk(IJ.getInstance().getStatusBar()) }
        return found.join(" | ")
    // groovylint-disable-next-line CatchThrowable
    } catch (Throwable problem) {
        return "unreadable: " + problem
    }
}
def finish = { Map summary ->
    Thread.sleep(plan.mode == "run" ? AFTER_RUN_SETTLE_MS : POLL_MS)
    def out = [kind: "finish", mode: plan.mode, summary: plain(summary.findAll { !(it.key in DROPPED) }, 0)]
    if (plan.mode == "run") {
        // the picture is evidence for a person: bring every result image to the front, side by side, and zoom the small ones (without a window manager they would sit under other windows)
        WindowManager.getImageTitles().findAll { !preloaded.contains(it) }.eachWithIndex { String title, int n ->
            def imp = WindowManager.getImage(title)
            tree.onEdt {
                def canvas = imp.getCanvas()
                while (canvas != null && imp.getWidth() * canvas.getMagnification() < SHOT_MIN_PX && canvas.getMagnification() < 16) canvas.zoomIn(0, 0)
                imp.getWindow().pack()
                imp.getWindow().setLocation(40 + n * 40, 120 + n * 40)
                imp.getWindow().toFront()
            }
        }
        Thread.sleep(POLL_MS * 5)
        screenshot("after_run", new java.awt.Rectangle(screenSize()))
        out.windows = WindowManager.getImageTitles() as List
        out.tables = WindowManager.getNonImageTitles() as List
        out.images = WindowManager.getImageTitles().collect { String title ->        // what every open image holds now, read from the ImagePlus itself (the result placement checks of ui_audit_placement.py)
            def imp = WindowManager.getImage(title)
            def cal = imp.getCalibration(), roi = imp.getRoi(), overlay = imp.getOverlay()
            [title: title, width: imp.getWidth(), height: imp.getHeight(), channels: imp.getNChannels(), slices: imp.getNSlices(), frames: imp.getNFrames(), bits: imp.getBitDepth(),
             pixel_width: cal.pixelWidth, pixel_height: cal.pixelHeight, unit: cal.getUnit(), x_origin: cal.xOrigin, y_origin: cal.yOrigin,
             roi: roi == null ? null : [type: roi.getTypeAsString().toLowerCase(), name: roi.getName(), points: roi.getFloatPolygon().npoints], overlay: overlay == null ? 0 : overlay.size()]
        }
        out.shown = windowsNow()
        out.screen = [screenSize().width, screenSize().height]
        out.windows.findAll { !preloaded.contains(it) }.eachWithIndex { String title, int n ->
            IJ.saveAs(WindowManager.getImage(title), "PNG", new File(shots, plan.tool_id + "_result_" + n + ".png").path)
        }
        out.log = (IJ.getLog() ?: "").take(MAX_TEXT)
    }
    if (plan.mode == "copy") {
        out.menu = menuEntries()
        try {
            out.clipboard = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor) as String
        // groovylint-disable-next-line CatchThrowable
        } catch (Throwable problem) {          // the clipboard text is the thing under test: a clipboard that cannot be read is said, and ui_audit.py fails the check
            out.clipboard_error = problem.toString()
        }
    }
    out.status_text = statusText()
    emit(out)
    // groovylint-disable-next-line SystemExit
    System.exit(0)
}
def setup = {
    plan.preload.each { item ->                              // "images the person already has open": opened by Fiji itself, so the calibration is the one Fiji reads from the file
        def imp = IJ.openImage(item.path as String)
        if (imp == null) {
            emit([kind: "obs", what: "fill_failed", detail: "Fiji cannot open the case's image " + item.path])
            return
        }
        imp.setTitle(item.title as String)
        imp.show()
        preloaded << item.title
    }
    if (plan.script_macro) System.setProperty("lc.macro.options", plan.script_macro as String)   // the "copy_last" call without the menu command (the script reads this property first)
    if (plan.mode != "copy") Thread.start(driver)
}

return [
    interactive : false,                                     // never block on a modal error dialog: the failure is in the summary
    preferred   : { String kind -> null },
    setup       : setup,
    awaitPrune  : { true },
    finish      : finish,
]
