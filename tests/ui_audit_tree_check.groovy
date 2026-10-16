// Check of tests/ui_audit_tree.groovy WITHOUT Fiji (needs only a JDK, the Groovy jars and a display, e.g. xvfb-run): a fake SciJava-like dialog with one component of each
// kind is enumerated, classified, activated and filled, and an unknown component and an unknown button must be reported. Prints `TREECHECK <name> PASS|FAIL <detail>`; exit 1 on FAIL.
//   LC_UI_TREE=tests/ui_audit_tree.groovy LC_UI_CONTROLS=tests/ui_audit_controls.json xvfb-run -a java -cp groovy.jar:groovy-json.jar groovy.ui.GroovyMain tests/ui_audit_tree_check.groovy
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

def tree = new GroovyShell(this.class.classLoader).evaluate(new File(System.getenv("LC_UI_TREE")))
def failures = 0
def check = { String name, boolean ok, String detail ->
    println "TREECHECK " + name + " " + (ok ? "PASS" : "FAIL") + " " + detail
    if (!ok) failures++
}

JDialog dialog
JTextField count = new JTextField("3")
JComboBox mode = new JComboBox(["alpha", "beta", "gamma"] as String[])
JComboBox image = new JComboBox(["a.tif", "b.tif"] as String[])
JCheckBox flag = new JCheckBox()
JCheckBox useImage = new JCheckBox("Use optional image")
JSpinner scale = new JSpinner(new SpinnerNumberModel(0.5d, 0.0d, 100.0d, 0.1d))
JSpinner channel = new JSpinner(new SpinnerNumberModel(1, 1, 9, 1))
JTextField fileField = new JTextField("")
JButton browse = new JButton("...")
JButton ok = new JButton("OK")
JButton cancel = new JButton("Cancel")
JButton mystery = new JButton("Mystery")
SwingUtilities.invokeAndWait {
    dialog = new JDialog((java.awt.Frame) null, "Fake form", false)
    JPanel rows = new JPanel(new GridLayout(0, 2))
    JPanel fileRow = new JPanel(new FlowLayout())
    fileRow.add(fileField)
    fileRow.add(browse)
    [["Count", count], ["Mode", mode], ["Image", image], ["Flag", flag], ["Use optional image", null], ["Scale (um/px)", scale], ["Image channel", channel],
     ["Image (or file)", fileRow], ["Note", new JTextArea("a description").with { editable = false; it }]].each { row ->
        rows.add(new JLabel(row[0] + ":"))
        rows.add(row[1] ?: useImage)
    }
    dialog.contentPane.add(rows, java.awt.BorderLayout.CENTER)
    JPanel buttons = new JPanel()
    [ok, cancel].each { buttons.add(it) }
    dialog.contentPane.add(buttons, java.awt.BorderLayout.SOUTH)
    dialog.pack()
    dialog.setVisible(true)
}

def found = tree.enumerate(dialog, "form", "form", ["a.tif", "b.tif"] as Set)
def ids = found.controls.collect { it.id }
check("all_known", found.unknown.isEmpty(), "unknown: " + found.unknown)
def expected = ["text_field", "choice_combo", "image_choice", "flag_checkbox", "use_image_checkbox", "number_spinner", "channel_spinner", "text_field", "file_browse", "message_text", "form_ok", "form_cancel"]
check("classified", ids.sort(false) == expected.sort(false), ids.toString())
check("labels", found.controls.find { it.id == "image_choice" }.label == "image" && found.controls.find { it.id == "file_browse" }.label == "image (or file)" && found.controls.find { it.id == "flag_checkbox" }.label == "flag",
      found.controls.collect { it.id + "=" + it.label }.toString())

SwingUtilities.invokeAndWait { dialog.contentPane.add(mystery, java.awt.BorderLayout.NORTH); dialog.contentPane.add(new JList(["x"] as String[]), java.awt.BorderLayout.WEST); dialog.pack() }
def withUnknown = tree.enumerate(dialog, "form", "form", ["a.tif", "b.tif"] as Set)
check("unknown_found", withUnknown.unknown.size() == 2 && withUnknown.unknown.any { it.contains("Mystery") } && withUnknown.unknown.any { it.startsWith("JList") }, withUnknown.unknown.toString())

found.controls.findAll { !(it.activation in ["press", "none"]) && it.id != "file_browse" }.each { control ->
    try {
        check("activate_" + control.id + "_" + control.label, true, tree.activate(control) as String)
    } catch (AssertionError problem) {
        check("activate_" + control.id + "_" + control.label, false, problem.message)
    }
}

// the browse button of the fake form opens a JFileChooser dialog: it must be seen and closed
browse.addActionListener { new JFileChooser().showOpenDialog(dialog) }
try {
    check("browse", true, tree.activate(found.controls.find { it.id == "file_browse" }) as String)
} catch (AssertionError problem) {
    check("browse", false, problem.message)
}
// a browse button that opens nothing is a failure, not a silent pass
JButton idle = new JButton("...")
try {
    tree.activate([id: "file_browse", activation: "browse", comp: idle])
    check("browse_nothing_opens", false, "no failure")
} catch (AssertionError problem) {
    check("browse_nothing_opens", problem.message.contains("no file chooser"), problem.message)
}

tree.fill(found.controls, "Count", 7)
tree.fill(found.controls, "Mode", "gamma")
tree.fill(found.controls, "Use optional image", true)
tree.fill(found.controls, "Scale (um/px)", 0.25)
check("fill", count.text == "7" && mode.selectedItem == "gamma" && useImage.selected && scale.value == 0.25d, [count.text, mode.selectedItem, useImage.selected, scale.value].toString())
try {
    tree.fill(found.controls, "No such row", 1)
    check("fill_unknown_row", false, "no failure")
} catch (AssertionError problem) {
    check("fill_unknown_row", true, problem.message)
}
SwingUtilities.invokeAndWait { dialog.dispose() }
// groovylint-disable-next-line SystemExit
System.exit(failures == 0 ? 0 : 1)
