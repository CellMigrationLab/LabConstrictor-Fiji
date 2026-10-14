// Swing side of the Fiji UI audit (tests/ui_audit.py; NOT part of the product). Evaluated by tests/ui_audit_body.groovy, it returns a map of closures that
//   enumerate(window, scope, dialogKind, openImages)  every component of a dialog, classified by the control table ($LC_UI_CONTROLS = tests/ui_audit_controls.json)
//   activate(control)                                  does what the table says for the control and asserts the effect (an AssertionError says what was not so)
//   fill(controls, label, value)                       puts a value into the component of the row `label` (what a person would type or pick)
//   realClick(component)                               a real mouse click (java.awt.Robot) at the centre of the component, refused when that point is not on the screen
//   closeWindowsExcept(known)                          closes the windows a browse button opened
// It needs only the JDK's Swing, so tests/ui_audit_tree_check.groovy runs it on a fake dialog without Fiji. Every Swing access goes through the event thread (onEdt).
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.lang.reflect.InvocationTargetException
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JRadioButton
import javax.swing.JScrollBar
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

@Field final Map<String, Class> KINDS = [JButton: JButton, JCheckBox: JCheckBox, JRadioButton: JRadioButton, JComboBox: JComboBox, JSpinner: JSpinner, JSlider: JSlider,
                                  JTextComponent: JTextComponent, JScrollBar: JScrollBar]
@Field final List<Class> INTERACTIVE = [AbstractButton, JComboBox, JSpinner, JSlider, JTextComponent, JList, JTable, JTree, JScrollBar]
@Field final int SETTLE_MS = 300                  // the callbacks of SciJava run on a thread of their own: they get this long before a value is read back
@Field final int ITEM_SETTLE_MS = 150
@Field final int WINDOW_WAIT_MS = 15000           // how long a browse button gets to open its chooser
@Field final int POLL_MS = 100
@Field final int CLICK_DELAY_MS = 80
@Field final String PROBE_TEXT = "lcaudit"
final Map table = new JsonSlurper().parseText(new File(System.getenv("LC_UI_CONTROLS")).getText("UTF-8")) as Map

def onEdt = { Closure body ->
    if (SwingUtilities.isEventDispatchThread()) return body()
    def box = [:]
    try {
        SwingUtilities.invokeAndWait { box.value = body() }
    } catch (InvocationTargetException problem) {
        throw problem.cause ?: problem
    }
    return box.value
}
def isInteractive = { Component c -> INTERACTIVE.any { it.isInstance(c) } }
def collect
collect = { Component c, List found ->
    found << c
    if (!isInteractive(c) && c instanceof Container) c.components.each { collect(it, found) }       // the insides of a spinner or a combo box are not controls of the form
}
def hasInteractive = { Component c -> def all = []; collect(c, all); all.any { isInteractive(it) } }
def normalise = { String text -> (text ?: "").replaceAll(/<[^>]*>/, " ").replaceAll(/\s+/, " ").trim().toLowerCase().replaceAll(/:$/, "").trim() }

// The label of the row a component sits in: the nearest JLabel before it (or before the widget panel that holds it) with nothing interactive in between.
def rowLabel = { Component c ->
    Component node = c
    while (node.parent != null && !(node.parent instanceof Window)) {
        Container parent = node.parent
        List siblings = parent.components.toList()
        for (int j = siblings.indexOf(node) - 1; j >= 0; j--) {
            Component before = siblings[j]
            if (before instanceof JLabel && before.text?.trim()) return normalise(before.text)
            if (hasInteractive(before)) break
        }
        node = parent
    }
    return ""
}
def labelOf = { Component c ->
    if ((c instanceof JCheckBox || c instanceof JRadioButton) && normalise(c.text)) return normalise(c.text)
    return rowLabel(c)
}
def itemsOf = { JComboBox box -> onEdt { (0..<box.itemCount).collect { box.getItemAt(it).toString() } } }

def matches = { Map entry, Component c, String scope, String dialogKind, Set openImages ->
    if (entry.scope != "any" && entry.scope != scope) return false
    if (!KINDS[entry.kind].isInstance(c)) return false
    if (entry.text && !(c.text in entry.text)) return false
    if (entry.dialog && entry.dialog != dialogKind) return false
    String label = labelOf(c)
    if (entry.label_prefix && !label.startsWith(entry.label_prefix as String)) return false
    if (entry.label_suffix && !label.endsWith(entry.label_suffix as String)) return false
    if (entry.containsKey("items_open_images")) {
        def items = itemsOf(c as JComboBox)
        if ((items && items.every { openImages.contains(it) }) != entry.items_open_images) return false
    }
    if (entry.containsKey("editable") && c instanceof JTextComponent && (onEdt { c.editable }) != entry.editable) return false
    return true
}

def enumerate = { Window window, String scope, String dialogKind, Set openImages ->
    def all = []
    onEdt { collect(window, all) }
    def controls = [], unknown = []
    all.findAll { isInteractive(it) }.each { Component c ->
        def entry = table.controls.find { matches(it as Map, c, scope, dialogKind, openImages) }
        String label = onEdt { labelOf(c) }
        String text = c instanceof AbstractButton ? onEdt { c.text } : null
        if (entry == null) unknown << (c.class.simpleName + ": '" + (text ?: label) + "'" + (text && label ? " in the row '" + label + "'" : ""))
        else controls << [id: entry.id, activation: entry.activation, kind: c.class.simpleName, label: label, text: text, comp: c]
    }
    return [controls: controls, unknown: unknown]
}

def settle = { int ms -> Thread.sleep(ms) }
def fail = { String message -> throw new AssertionError(message) }

def selectItems = { Map control ->
    JComboBox box = control.comp
    int count = onEdt { box.itemCount }, original = onEdt { box.selectedIndex }
    if (count == 0) fail("the list has no items")
    for (int i = 0; i < count; i++) {
        onEdt { box.setSelectedIndex(i) }
        settle(ITEM_SETTLE_MS)
        int now = onEdt { box.selectedIndex }
        if (now != i) fail("item " + i + " was selected but the box reads " + now)
    }
    onEdt { box.setSelectedIndex(original) }
    return count + " item(s) selected and read back"
}
def realClick = { Component c ->
    def where = onEdt { [c.locationOnScreen, c.size] }
    Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
    int x = (where[0].x as int) + (int) (where[1].width / 2), y = (where[0].y as int) + (int) (where[1].height / 2)
    if (!screen.contains(x, y)) fail("the control is at (" + x + "," + y + "), outside the " + screen.width + "x" + screen.height + " screen: a real click cannot reach it")
    Robot robot = new Robot()
    robot.mouseMove(x, y)
    robot.delay(CLICK_DELAY_MS)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.delay(CLICK_DELAY_MS)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    robot.waitForIdle()
}
def toggle = { Map control ->
    AbstractButton box = control.comp
    boolean original = onEdt { box.selected }
    for (boolean wanted : [!original, original]) {
        realClick(box)
        settle(SETTLE_MS)
        if ((onEdt { box.selected }) != wanted) fail("a click should leave it " + (wanted ? "ticked" : "unticked") + " (enabled: " + onEdt { box.enabled } + ")")
    }
    return "ticked and unticked"
}
def typeText = { Map control ->
    JTextComponent field = control.comp
    String original = onEdt { field.text }
    onEdt { field.setText(PROBE_TEXT) }
    settle(SETTLE_MS)
    String now = onEdt { field.text }
    onEdt { field.setText(original) }
    settle(SETTLE_MS)
    if (now != PROBE_TEXT) fail("typed '" + PROBE_TEXT + "' and the field reads '" + now + "'")
    return "typed text read back"
}
def step = { Map control ->
    JSpinner spinner = control.comp
    def original = onEdt { spinner.value }
    def next = onEdt { spinner.nextValue ?: spinner.previousValue }
    if (next == null) fail("the number cannot be stepped")
    onEdt { spinner.setValue(next) }
    settle(SETTLE_MS)
    def now = onEdt { spinner.value }
    onEdt { spinner.setValue(original) }
    settle(SETTLE_MS)
    if (now.toString() != next.toString()) fail("set " + next + " and the spinner reads " + now)
    return "stepped " + original + " -> " + next
}
def selectRadio = { Map control ->
    AbstractButton radio = control.comp
    realClick(radio)
    settle(SETTLE_MS)
    if (!(onEdt { radio.selected })) fail("a click did not select the radio button '" + control.text + "'")
    return "selected"
}
def slide = { Map control ->
    JSlider slider = control.comp
    int original = onEdt { slider.value }, target = original == onEdt { slider.maximum } ? onEdt { slider.minimum } : onEdt { slider.maximum }
    onEdt { slider.setValue(target) }
    settle(SETTLE_MS)
    int now = onEdt { slider.value }
    onEdt { slider.setValue(original) }
    if (now != target) fail("set " + target + " and the slider reads " + now)
    return "moved " + original + " -> " + target
}
def showingWindows = { onEdt { Window.getWindows().findAll { it.isShowing() } } as List }
def closeWindowsExcept = { List known ->
    showingWindows().findAll { !known.contains(it) }.each { Window w -> SwingUtilities.invokeLater { w.dispose() } }
    long deadline = System.currentTimeMillis() + WINDOW_WAIT_MS
    while (showingWindows().any { !known.contains(it) } && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
}
def browse = { Map control ->
    List before = showingWindows()
    AbstractButton button = control.comp
    SwingUtilities.invokeLater { button.doClick() }
    Window opened = null
    long deadline = System.currentTimeMillis() + WINDOW_WAIT_MS
    while (opened == null && System.currentTimeMillis() < deadline) {
        opened = showingWindows().find { !before.contains(it) }
        if (opened == null) Thread.sleep(POLL_MS)
    }
    if (opened == null) fail("no file chooser opened within " + WINDOW_WAIT_MS + " ms")
    String what = opened.class.simpleName + " '" + (opened instanceof java.awt.Dialog ? opened.title : "") + "'"
    closeWindowsExcept(before)
    if (showingWindows().any { !before.contains(it) }) fail("the file chooser " + what + " did not close")
    if (!before.every { it.isShowing() }) fail("the form closed together with the file chooser")
    return "opened " + what + " and closed it; the form is still open"
}

final Map activations = [select_items: selectItems, toggle: toggle, type: typeText, step: step, select_radio: selectRadio, slide: slide, browse: browse]
def activate = { Map control ->
    def action = activations[control.activation]
    if (action == null) fail("the table gives the activation '" + control.activation + "' that this driver cannot do")
    return action(control)
}

// Put `value` where a person would: the component of the row `label` (a radio group has the choice's text as its own label).
def fill = { List controls, String label, def value ->
    String wanted = normalise(label)
    def control = controls.find { it.label == wanted && !(it.id in ["file_browse", "radio", "form_ok", "form_cancel", "message_text"]) }
    if (control == null) control = controls.find { it.id == "radio" && normalise(it.text) == normalise(value.toString()) }
    if (control == null) fail("no component for '" + label + "' to put " + value + " into")
    def comp = control.comp
    if (comp instanceof JTextComponent) {
        onEdt { comp.setText(value.toString()) }
    } else if (comp instanceof JComboBox) {
        int index = (itemsOf(comp)).indexOf(value.toString())
        if (index < 0) fail("'" + label + "' has no item '" + value + "' (items: " + itemsOf(comp) + ")")
        onEdt { comp.setSelectedIndex(index) }
    } else if (comp instanceof JCheckBox) {
        if ((onEdt { comp.selected }) != (value as boolean)) onEdt { comp.doClick() }
    } else if (comp instanceof JRadioButton) {
        onEdt { comp.doClick() }
    } else if (comp instanceof JSpinner) {
        def current = onEdt { comp.value }
        onEdt { comp.setValue(current instanceof Integer ? (value as Number).intValue() : (value as Number).doubleValue()) }
    } else if (comp instanceof JSlider) {
        onEdt { comp.setValue((value as Number).intValue()) }
    } else {
        fail("the component of '" + label + "' is a " + comp.class.simpleName + ": there is no way to fill it")
    }
    settle(SETTLE_MS)
    return control.id
}

return [enumerate: enumerate, activate: activate, fill: fill, realClick: realClick, onEdt: onEdt, itemsOf: itemsOf, closeWindowsExcept: closeWindowsExcept, normalise: normalise, showingWindows: showingWindows]
