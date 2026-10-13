// Smoke run inside Fiji (started by os_smoke.py with --run): is the menu entry Plugins > LabConstrictor > LabConstrictor Tools... there,
// and does it open a dialog that lists the registered app? Writes a JSON report to $LC_SMOKE_REPORT; exit code 0 pass, 1 fail, 2 no display.
import groovy.json.JsonOutput
import ij.IJ
import java.awt.Choice
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Label
import java.awt.Menu
import java.awt.MenuBar
import java.awt.MenuItem
import java.awt.Window
import javax.swing.JComboBox
import javax.swing.JLabel

def out = new File(System.getenv("LC_SMOKE_REPORT"))
def wanted = System.getenv("LC_SMOKE_APP") ?: "synthetic"
def report = [os: System.getProperty("os.name"), arch: System.getProperty("os.arch"), java: System.getProperty("java.version"),
              imagej: IJ.getFullVersion(), headless: GraphicsEnvironment.isHeadless(), stage: "started"]
def write = { out.text = JsonOutput.prettyPrint(JsonOutput.toJson(report)) }
def finish = { int code -> write(); Runtime.getRuntime().halt(code) }
write()
if (report.headless) { report.problem = "no display: the Java runtime is headless"; finish(2) }

def walkMenu
walkMenu = { MenuItem item, String path, List found ->
    String here = path ? path + ">" + item.getLabel() : item.getLabel()
    found << here
    if (item instanceof Menu) for (int i = 0; i < ((Menu) item).getItemCount(); i++) walkMenu(((Menu) item).getItem(i), here, found)
}
MenuBar bar = ij.Menus.getMenuBar()
def labels = []
for (int i = 0; i < bar.getMenuCount(); i++) walkMenu(bar.getMenu(i), "", labels)
report.menu_entry = labels.contains("Plugins>LabConstrictor>LabConstrictor Tools...")
report.menu_entries_labconstrictor = labels.findAll { it.contains("LabConstrictor") }
report.stage = "menu_checked"
write()

def texts = { Container c, List into ->
    def self
    self = { Component x ->
        if (x instanceof JLabel && x.text) into << x.text
        if (x instanceof Label && x.text) into << x.text
        if (x instanceof JComboBox) for (int i = 0; i < x.itemCount; i++) into << String.valueOf(x.getItemAt(i))
        if (x instanceof Choice) for (int i = 0; i < x.itemCount; i++) into << x.getItem(i)
        if (x instanceof Container) x.components.each { self(it) }
    }
    self(c)
}

Thread.start {
    def deadline = System.currentTimeMillis() + 120000
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(1000)
        def into = [], titles = []
        Window.getWindows().findAll { it.isShowing() && !(it.class.name.startsWith("ij.ImageJ")) }.each { w ->
            titles << (w.hasProperty("title") ? String.valueOf(w.title) : w.class.name)
            texts(w, into)
        }
        if (into.any { it.toLowerCase().contains(wanted.toLowerCase()) }) {
            report.dialog_titles = titles
            report.dialog_texts = into.take(60)
            report.app_listed = true
            report.dialog_opened = true
            report.stage = "done"
            finish(report.menu_entry ? 0 : 1)
        }
        report.dialog_titles = titles
        report.dialog_texts = into.take(60)
    }
    report.problem = "no dialog listing '" + wanted + "' within 120 s"
    report.dialog_opened = !report.dialog_titles?.isEmpty()
    report.app_listed = false
    finish(1)
}
try { IJ.run("LabConstrictor Tools...") } catch (Throwable t) { report.problem = "IJ.run failed: " + t; finish(1) }
Thread.sleep(150000)
