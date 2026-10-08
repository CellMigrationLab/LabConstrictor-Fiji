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
    if (title == cfg.tool) { typeIntoSpinners(dialog); selectImages(dialog) }
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
        imp.show()
        if (item.roi) imp.setRoi(new java.awt.Rectangle(item.roi[0] as int, item.roi[1] as int, item.roi[2] as int, item.roi[3] as int))      // "the person drew a region"
        if (item.manager_rois) {                                // "the person listed regions in the ROI Manager and selected some"
            def manager = ij.plugin.frame.RoiManager.getRoiManager()
            item.manager_rois.each { r -> manager.addRoi(new ij.gui.Roi(r[0] as int, r[1] as int, r[2] as int, r[3] as int)) }
            manager.setSelectedIndexes(item.manager_selected as int[])
        }
    }
}

def finish = { Map summary ->
    if (cfg.record) summary.recorded_options = ij.plugin.frame.Recorder.getCommandOptions()
    summary.dialogs = report.dialogs
    summary.combo_items = report.combo_items
    summary.spinner_values_after_selection = report.spinner_values_after_selection
    summary.chooser_initial = report.chooser_initial
    summary.single_input_prompt = report.single_input_prompt
    summary.open_windows = WindowManager.getImageTitles() as List
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
    finish       : finish,
]
