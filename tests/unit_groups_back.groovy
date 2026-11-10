// Unit-check groups of the Back button (the form -> the tool chooser -> the app chooser): which window Back goes to, the button beside OK and Cancel, and what a form
// picked again after Back starts from. NOT part of the product. Loaded by tests/unit_checks.groovy, which calls this closure with its toolbox. Returns the groups by
// name; a case selects them with "unit_checks": [...].

import javax.swing.JButton
import javax.swing.JOptionPane

{ Map t ->
    def lc = t.lc, check = t.check
    def pane = { -> new JOptionPane("message", JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION) }
    def buttonsOf = { java.awt.Container row -> row.getComponents().findAll { it instanceof JButton }.collect { (it as JButton).getText() } }

    def tool = [label: "T", outputs: [], inputs: [
        [name: "x", label: "X", type: "float", required: true, default: 1.0],
        [name: "img", label: "Img", type: "image", required: true],
        [name: "guess", label: "Guess", type: "string", required: false, nullable: true, clear_after_run: true],
    ]]
    def typed = [values: [x: "7", img_file: new File("a.tif"), set_guess: true, guess: "B"], states: [:], status: null]   // what askTool keeps after Back
    def startOf = { String name -> lc.buildToolDialog(tool, [], [:], typed)[0].getInput(name)?.getDefaultValue() }

    return [
        back_goes_one_window_back_and_skips_a_chooser_that_is_never_shown: {
            [["form", 2, 3, "tool"], ["form", 1, 3, "tool"], ["form", 2, 1, "app"], ["form", 1, 1, null],
             ["tool", 2, 3, "app"], ["tool", 1, 3, null], ["app", 2, 3, null]].each { step, apps, tools, wanted ->
                def got = lc.windowBefore(step as String, apps as int, tools as int)
                check("from the " + step + " with " + apps + " app(s) and " + tools + " tool(s) Back goes to " + wanted, got == wanted, String.valueOf(got))
            }
        },
        back_the_button_sits_first_in_the_row_of_ok_and_cancel_and_closes_the_dialog_as_cancel: {
            def p = pane()
            def row = lc.namedIn(p, lc.BUTTON_ROW) as java.awt.Container
            check("Swing names the row of OK and Cancel", row != null && buttonsOf(row).size() == 2, row == null ? "no row" : buttonsOf(row).toString())
            boolean pressed = false
            check("the button is added", lc.addBackButton(p, { pressed = true }))
            def texts = buttonsOf(row)
            check("Back comes first, then OK and Cancel", texts.size() == 3 && texts[0] == lc.BACK_TEXT, texts.toString())
            (row.getComponent(0) as JButton).doClick()
            check("pressing it says so to the caller", pressed)
            check("and closes the dialog as Cancel does (SciJava then cancels the module)", p.getValue() == JOptionPane.CANCEL_OPTION, String.valueOf(p.getValue()))
            def bare = pane()
            def bareRow = lc.namedIn(bare, lc.BUTTON_ROW)
            bareRow.getParent().remove(bareRow)
            check("a pane without that row gets no button, and says so (the caller logs it)", !lc.addBackButton(bare, { }))
        },
        back_a_form_picked_again_after_back_starts_from_what_was_typed: {
            check("a number keeps what was typed", startOf("x") == "7", String.valueOf(startOf("x")))
            check("a clear_after_run field keeps its value: nothing ran", startOf("guess") == "B" && startOf("set_guess") == true, startOf("guess") + " " + startOf("set_guess"))
            check("an image's '(or file)' field starts empty, as in the form that comes back after a run", startOf("img_file") == null, String.valueOf(startOf("img_file")))
        },
    ]
}
