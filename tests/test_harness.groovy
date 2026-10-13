// Test harness for fiji/LabConstrictor_Tools.groovy. NOT part of the product: it automates the real SciJava dialogs on a virtual
// screen (answers them, clicks OK, takes screenshots) and writes a JSON report. Selected with LC_FIJI_HARNESS=<this file>;
// the case to run comes from LC_FIJI_CASE=<case.json>. Returns the hook map the product script merges over its no-op defaults.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import ij.IJ
import ij.WindowManager
import java.awt.Container
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Robot
import java.awt.Window
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SwingUtilities

def cfg = new JsonSlurper().parseText(new File(System.getenv("LC_FIJI_CASE")).text)
def report = [dialogs: [:], combo_items: [:]]
def conformance = System.getenv("LC_CONFORMANCE") ? new JsonSlurper().parseText(new File(System.getenv("LC_CONFORMANCE")).getText("UTF-8")) : null   // labconstrictor_tools/conformance.json (run_cases.py sets it)
def shots = new File(cfg.shots as String)
shots.mkdirs()

def walk
walk = { java.awt.Component c, Closure visit ->
    visit(c)
    if (c instanceof Container) c.components.each { walk(it, visit) }
}
def components = { Window w, Class type -> def found = []; walk(w) { if (type.isInstance(it)) found << it }; found }
def screenshot = { Window w, String name ->
    try { ImageIO.write(new Robot().createScreenCapture(w.getBounds()), "png", new File(shots, name + ".png")) } catch (Throwable ignored) { }
}
def safe = { String s -> s.replaceAll("[^A-Za-z0-9]+", "_") }

// Pick the requested image in each image chooser (top to bottom), in the order given by cfg.select.
def selectImages = { Window dialog ->
    if (!cfg.select) return
    def wanted = cfg.select.collect()
    components(dialog, JComboBox).sort { it.getLocationOnScreen().y }.each { JComboBox box ->
        if (!wanted) return
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).toString() == wanted[0]) {
                int index = i
                SwingUtilities.invokeAndWait { box.setSelectedIndex(index) }
                wanted.remove(0)
                break
            }
        }
    }
    Thread.sleep(500)
    screenshot(dialog, "dialog_after_selection")
    report.spinner_values_after_selection = components(dialog, JSpinner).collect { it.getValue().toString() }
}

// Type values into the number fields (top to bottom), as a person would before changing something else: cfg.type_into_spinners = [{index, value}].
def typeIntoSpinners = { Window dialog ->
    if (!cfg.type_into_spinners) return
    def spinners = components(dialog, JSpinner).sort { it.getLocationOnScreen().y }
    cfg.type_into_spinners.each { entry ->
        JSpinner spinner = spinners[entry.index as int]
        SwingUtilities.invokeAndWait { spinner.setValue(entry.value as double) }
    }
    Thread.sleep(500)
}

// A sequence of things a person does in the dialog, one after the other, with the number fields as they stand after each (report.steps): cfg.dialog_steps =
//   {select: "b.tif"}   pick that open image in the chooser that lists it       {type: 0.3, field: 0}  type a value in number field 0 (top to bottom)
//   {file: "/path"}     put that path in the "(or file)" text field (""  clears it)     {use: true}  tick or untick the first check box named "Use ..."
//   {text: "9007199254740991", field: 0}  type that text into text field number `field` (top to bottom; a number field is a text field)
def runSteps = { Window dialog ->
    report.steps = []
    cfg.dialog_steps.each { step ->
        if (step.select != null) {
            def box = components(dialog, JComboBox).find { b -> (0..<b.getItemCount()).any { b.getItemAt(it).toString() == step.select } }
            def index = (0..<box.getItemCount()).find { box.getItemAt(it).toString() == step.select }
            SwingUtilities.invokeAndWait { box.setSelectedIndex(index as int) }
        }
        if (step.type != null) {
            def spinner = components(dialog, JSpinner).sort { it.getLocationOnScreen().y }[(step.field ?: 0) as int]
            SwingUtilities.invokeAndWait { spinner.setValue(step.type as double) }
        }
        if (step.file != null || step.text != null) {          // `text`: what a person types into text field number `field` (top to bottom): a number field is text too
            def field = components(dialog, javax.swing.JTextField).sort { it.getLocationOnScreen().y }[(step.field ?: 0) as int]
            SwingUtilities.invokeAndWait { field.setText((step.file != null ? step.file : step.text) as String) }
        }
        if (step.use != null) {
            def box = components(dialog, javax.swing.JCheckBox).find { it.text?.startsWith("Use ") }
            SwingUtilities.invokeAndWait { box.setSelected(step.use as boolean) }
        }
        Thread.sleep(1200)                                   // the callbacks of SciJava run on their own thread: let them finish before the next thing is done
        report.steps << components(dialog, JSpinner).sort { it.getLocationOnScreen().y }.collect { it.getValue() as double }
    }
    screenshot(dialog, "dialog_after_steps")
}

def answerDialog = { Window dialog, String title ->
    Thread.sleep(700)
    screenshot(dialog, "dialog_" + safe(title))
    report.dialogs[title] = components(dialog, JLabel).findAll { it.text }.collect { it.text }
    report.combo_items[title] = components(dialog, JComboBox).sort { it.getLocationOnScreen().y }.collect { box -> (0..<box.getItemCount()).collect { box.getItemAt(it).toString() } }
    if (title == cfg.tool) {      // which open image each image chooser shows before anybody touches it (top to bottom)
        def open = (WindowManager.getImageTitles() as List)
        report.chooser_initial = components(dialog, JComboBox).sort { it.getLocationOnScreen().y }
            .findAll { box -> box.getItemCount() > 0 && (0..<box.getItemCount()).every { open.contains(box.getItemAt(it).toString()) } }
            .collect { it.getSelectedItem()?.toString() }
    }
    if (title == cfg.tool) { if (cfg.dialog_steps) runSteps(dialog) else { typeIntoSpinners(dialog); selectImages(dialog) } }
    def ok = components(dialog, JButton).find { it.text == "OK" }
    if (ok) SwingUtilities.invokeLater { ok.doClick() }
}

// SciJava shows a native file chooser instead of a dialog for tools with exactly one input.
def answerFileChooser = { FileDialog chooser ->
    def path = cfg.overrides.values().find { it instanceof String && it.startsWith("/") }
    screenshot(chooser, "single_input_file_prompt")
    report.single_input_prompt = "native file chooser (SciJava single-input prompt)"
    java.awt.EventQueue.invokeLater { chooser.setDirectory(new File(path).parent); chooser.setFile(new File(path).name); chooser.setVisible(false) }
}

def startClicker = { String title ->
    Thread.start {
        def deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) {
            def windows = Window.getWindows().findAll { it.isShowing() }
            def chooser = windows.find { it instanceof FileDialog }
            if (chooser && cfg.overrides) return answerFileChooser(chooser)
            def dialog = windows.find { it instanceof Dialog && it.title?.contains(title) }
            if (dialog) return answerDialog(dialog, title)
            Thread.sleep(100)
        }
    }
}

def preload = {
    if (cfg.script_macro) System.setProperty("lc.macro.options", cfg.script_macro as String)   // a macro call without the menu command (the script reads this property first)
    if (cfg.record) ij.plugin.frame.Recorder.record = true          // as if the Macro Recorder window were open
    cfg.preload_tables?.each { item ->                       // stands in for "a results window the person already has open"
        def table = new ij.measure.ResultsTable()
        table.addRow()
        table.addValue("a", 1)
        table.show(item.title as String)
    }
    cfg.preload?.each { item ->                              // stands in for "images the user already has open"
        def imp = IJ.openImage(item.path as String)
        imp.setTitle(item.title as String)
        if (item.pixel_size) {
            def cal = imp.getCalibration()
            cal.pixelWidth = item.pixel_size
            cal.pixelHeight = item.pixel_size
            cal.setUnit(item.unit ?: "um")
        }
        if (item.pixel_width != null || item.x_origin != null) {      // a calibration exactly as given: Y and X apart, an origin, the unit text as it is
            def cal = imp.getCalibration()
            cal.pixelWidth = item.pixel_width ?: 1.0
            cal.pixelHeight = item.pixel_height ?: item.pixel_width ?: 1.0
            cal.xOrigin = item.x_origin ?: 0.0
            cal.yOrigin = item.y_origin ?: 0.0
            cal.setUnit(item.unit ?: "pixel")
        }
        imp.show()
        if (item.slice) imp.setSlice(item.slice as int)
        if (item.polygon_roi_from_conformance) {                 // "the person drew a polygon around the pixels of the fixture's rectangle" (vertices [y, x] at pixel centres: +0.5 in ImageJ)
            def rect = conformance.wrong_science.coordinate[item.polygon_roi_from_conformance as String]
            imp.setRoi(new ij.gui.PolygonRoi(rect.collect { (it[1] + 0.5) as float } as float[], rect.collect { (it[0] + 0.5) as float } as float[], rect.size(), ij.gui.Roi.POLYGON))
        }
        if (item.roi) imp.setRoi(new java.awt.Rectangle(item.roi[0] as int, item.roi[1] as int, item.roi[2] as int, item.roi[3] as int))      // "the person drew a region"
        if (item.manager_rois) {                                // "the person listed regions in the ROI Manager and selected some"
            def manager = ij.plugin.frame.RoiManager.getRoiManager()
            item.manager_rois.eachWithIndex { r, i ->
                def roi = new ij.gui.Roi(r[0] as int, r[1] as int, r[2] as int, r[3] as int)
                if (item.manager_positions) roi.setPosition(item.manager_positions[i] as int)      // "each region was drawn on another plane of the stack"
                manager.addRoi(roi)
            }
            manager.setSelectedIndexes(item.manager_selected as int[])
        }
    }
}

// What every open image window holds after the run: its size, calibration, selection and overlay with the coordinates actually drawn (so that a case can say
// "nothing was drawn on this image"). Keyed by the title with every character that is not a letter or digit as "_" (a title can hold dots).
def describeRoi = { ij.gui.Roi roi ->
    def polygon = roi.getFloatPolygon()
    [type: roi.getTypeAsString(), name: roi.getName(), c: roi.getCPosition(), z: roi.getZPosition(), t: roi.getTPosition(), position: roi.getPosition(),
     points: (0..<polygon.npoints).collect { [polygon.xpoints[it] as double, polygon.ypoints[it] as double] }]
}
def describeWindows = {
    WindowManager.getImageTitles().collectEntries { String title ->
        def imp = WindowManager.getImage(title), cal = imp.getCalibration()
        [(safe(title)): [title: title, width: imp.getWidth(), height: imp.getHeight(), stack: imp.getStackSize(),
                         calibration: [pixel_width: cal.pixelWidth, pixel_height: cal.pixelHeight, unit: cal.getUnit(), x_origin: cal.xOrigin, y_origin: cal.yOrigin],
                         roi: imp.getRoi() == null ? null : describeRoi(imp.getRoi()),
                         overlay: (imp.getOverlay()?.toArray() ?: [] as ij.gui.Roi[]).collect { describeRoi(it) }]]
    }
}

def finish = { Map summary ->
    if (cfg.record) summary.recorded_options = ij.plugin.frame.Recorder.getCommandOptions()
    summary.dialogs = report.dialogs
    summary.combo_items = report.combo_items
    summary.spinner_values_after_selection = report.spinner_values_after_selection
    summary.steps = report.steps
    summary.chooser_initial = report.chooser_initial
    summary.single_input_prompt = report.single_input_prompt
    summary.open_windows = WindowManager.getImageTitles() as List
    summary.windows = describeWindows()
    summary.open_tables = WindowManager.getNonImageTitles() as List
    def overlay = summary.overlay_title ? WindowManager.getImage(summary.overlay_title as String) : null
    if (overlay) {
        IJ.saveAs(overlay, "PNG", new File(shots, "overlay.png").path)
        if (cfg.overlay_zoom) {
            overlay.setRoi(*cfg.overlay_zoom)
            IJ.saveAs(overlay.crop(), "PNG", new File(shots, "overlay_zoom.png").path)
        }
    }
    if (cfg.final_screenshot) {                              // the whole screen after the run: result windows, log, table
        try { ImageIO.write(new Robot().createScreenCapture(new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize())), "png", new File(shots, "final_screen.png")) } catch (Throwable ignored) { }
    }
    new File(cfg.report as String).text = JsonOutput.prettyPrint(JsonOutput.toJson(summary))
    System.exit(0)
}

return [
    interactive  : false,
    overrides    : cfg.overrides ?: [:],
    preferred    : { String kind -> cfg[kind] },
    setup        : preload,
    beforeDialog : { String title -> startClicker(title) },
    cancelAfterMs: { cfg.cancel_after_s ? (long) (cfg.cancel_after_s * 1000) : null },
    maxShapes     : { cfg.max_shapes ? (int) cfg.max_shapes : null },
    maxExportBytes: { cfg.max_export_bytes ? (long) cfg.max_export_bytes : null },       // a small limit instead of a 4 GB image
    // the prune the host starts after a run: the real one (cfg.prune absent), a recorder ("recorder") or a command that fails ("failing"); tests/prune_recorder.py
    pruneCommand : { List<String> command ->
        cfg.prune ? [System.getenv("LC_PYTHON"), System.getenv("LC_PRUNE_RECORDER"), cfg.prune as String, new File(cfg.shots as String, "prune_calls.json").path] + command : command
    },
    awaitPrune   : { true },                                 // the report is read after the run: the prune it started has ended by then
    finish       : finish,
]
