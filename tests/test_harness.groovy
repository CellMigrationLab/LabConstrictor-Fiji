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
def report = [dialogs: [:]]
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

def answerDialog = { Window dialog, String title ->
    Thread.sleep(700)
    screenshot(dialog, "dialog_" + safe(title))
    report.dialogs[title] = components(dialog, JLabel).findAll { it.text }.collect { it.text }
    if (title == cfg.tool) {      // which open image each image chooser shows before anybody touches it (top to bottom)
        def open = (WindowManager.getImageTitles() as List)
        report.chooser_initial = components(dialog, JComboBox).sort { it.getLocationOnScreen().y }
            .findAll { box -> box.getItemCount() > 0 && (0..<box.getItemCount()).every { open.contains(box.getItemAt(it).toString()) } }
            .collect { it.getSelectedItem()?.toString() }
    }
    if (title == cfg.tool) selectImages(dialog)
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
    if (cfg.record) ij.plugin.frame.Recorder.record = true          // as if the Macro Recorder window were open
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
    }
}

def finish = { Map summary ->
    if (cfg.record) summary.recorded_options = ij.plugin.frame.Recorder.getCommandOptions()
    summary.dialogs = report.dialogs
    summary.spinner_values_after_selection = report.spinner_values_after_selection
    summary.chooser_initial = report.chooser_initial
    summary.single_input_prompt = report.single_input_prompt
    summary.open_windows = WindowManager.getImageTitles() as List
    def overlay = summary.overlay_title ? WindowManager.getImage(summary.overlay_title as String) : null
    if (overlay) {
        IJ.saveAs(overlay, "PNG", new File(shots, "overlay.png").path)
        if (cfg.overlay_zoom) {
            overlay.setRoi(*cfg.overlay_zoom)
            IJ.saveAs(overlay.crop(), "PNG", new File(shots, "overlay_zoom.png").path)
        }
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
    finish       : finish,
]
