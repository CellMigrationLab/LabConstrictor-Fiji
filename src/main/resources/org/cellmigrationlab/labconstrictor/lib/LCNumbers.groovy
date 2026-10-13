// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): the numbers a person types or a macro gives (docs/SPEC_WRONG_SCIENCE.md section 6).
// One grammar and one set of limits for the dialog and the macro: a value beyond a limit, declared or the form's own, is REFUSED with the worker's sentence and
// never clamped, rounded or shortened (a whole number with no declared bounds holds +-(2**53 - 1), the largest every double and every JSON number holds exactly;
// a float with none holds +-1e15). The dialog's number fields are text for that reason: a spinner of the toolkit cuts a typed value to its range without a word.
// Every method is typed; nothing here reads the script's globals.

/** The grammar, the limits and the refusal sentences of the numbers of a form (see the comment at the top of this file). */
class LCNumbers {
    static final long FORM_INTEGER_MAX = 9_007_199_254_740_991L      // 2**53 - 1: conformance.json limits.form_integer_max
    static final double UNBOUNDED_FLOAT = 1.0e15d                    // conformance.json limits.unbounded_float
    static final String INTEGER_TEXT = /[+-]?[0-9]+/                  // the number grammar shared by every host (labconstrictor_tools conformance)
    static final String FLOAT_TEXT = /[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?/
    static final List<String> NON_FINITE_WORDS = ['nan', 'inf', 'infinity']   // refused as "not a finite number", like the worker does after parsing them
    static final String FLOAT_LIMIT_TEXT = '1000000000000000.0'      // the form's float limit as Python prints it (the worker's templates print the bound as Python does)

    /** The declared bounds of an integer parameter, kept within +-FORM_INTEGER_MAX (a bound beyond it cannot be held exactly: `limited` says so, the form
     *  states the limit instead of cutting values silently). Null where no bound is declared. */
    static Map<String, Object> integerLimits(Map p) {
        BigInteger limit = FORM_INTEGER_MAX as BigInteger
        Closure<Object> narrowed = { Object bound -> bound == null ? null : ((bound as BigInteger).max(-limit).min(limit)).longValue() }
        Closure<Boolean> beyond = { Object bound -> bound != null && (bound as BigInteger).abs() > limit }
        return [minimum: narrowed(p.minimum), maximum: narrowed(p.maximum), limited: beyond(p.minimum) || beyond(p.maximum)]
    }

    /** A bound as the refusal sentence prints it: what the tool declared (a whole number as it is, a float as the schema has it), or the form's own limit when the
     *  declared bound is beyond it or there is none. */
    static String boundText(Object declared, Object limit, boolean limitIsFloat) {
        boolean within = declared != null && (limitIsFloat ? Math.abs((declared as BigDecimal).doubleValue()) <= UNBOUNDED_FLOAT : (declared as BigInteger).abs() <= (FORM_INTEGER_MAX as BigInteger))
        if (within) return declared.toString()
        return limitIsFloat ? (((limit as double) < 0 ? '-' : '') + FLOAT_LIMIT_TEXT) : limit.toString()
    }

    /** `text` as the number parameter `p` takes it, or an IllegalArgumentException with the worker's sentence (HOST_PARITY I06) followed by `, got 'x'`: not a whole
     *  number, not a number, not finite, too close to zero to be held exactly, or beyond a bound (declared, else the form's limit). A whole number is an Integer when it
     *  fits 32 bits, else a Long; a float is a Double (the one nearest the typed text). No blank is accepted: the dialog trims what was typed (see fromField). */
    static Number parse(Map p, String text) {
        String label = p.label as String
        if (p.type == 'integer') return parseInteger(p, label, text)
        if (text.replaceFirst(/^[+-]/, '').toLowerCase() in NON_FINITE_WORDS) throw new IllegalArgumentException("'" + label + "' must be a finite number, got '" + text + "'")
        if (!(text ==~ FLOAT_TEXT)) throw new IllegalArgumentException("'" + label + "' must be a number, got '" + text + "'")
        double number = Double.parseDouble(text)
        if (!Double.isFinite(number)) throw new IllegalArgumentException("'" + label + "' must be a finite number, got '" + text + "'")
        if (number == 0.0d && text.replaceFirst(/[eE].*$/, '') =~ /[1-9]/)
            throw new IllegalArgumentException("'" + label + "' is too close to zero to be held exactly, got '" + text + "'")
        double minimum = p.minimum != null ? Math.max((p.minimum as BigDecimal).doubleValue(), -UNBOUNDED_FLOAT) : -UNBOUNDED_FLOAT
        double maximum = p.maximum != null ? Math.min((p.maximum as BigDecimal).doubleValue(), UNBOUNDED_FLOAT) : UNBOUNDED_FLOAT
        if (number < minimum) throw new IllegalArgumentException("'" + label + "' must be >= " + boundText(p.minimum, minimum, true) + ", got " + text)
        if (number > maximum) throw new IllegalArgumentException("'" + label + "' must be <= " + boundText(p.maximum, maximum, true) + ", got " + text)
        return number
    }

    /** The whole-number half of parse: exact (BigInteger) up to the comparison with the bounds, so that nothing is rounded on the way. */
    private static Number parseInteger(Map p, String label, String text) {
        if (!(text ==~ INTEGER_TEXT)) throw new IllegalArgumentException("'" + label + "' must be an integer, got '" + text + "'")
        BigInteger number = new BigInteger(text.replaceFirst(/^\+/, ''))
        Map<String, Object> limits = integerLimits(p)
        BigInteger minimum = limits.minimum != null ? (limits.minimum as long) as BigInteger : -(FORM_INTEGER_MAX as BigInteger)
        BigInteger maximum = limits.maximum != null ? (limits.maximum as long) as BigInteger : FORM_INTEGER_MAX as BigInteger
        if (number < minimum) throw new IllegalArgumentException("'" + label + "' must be >= " + boundText(p.minimum, minimum, false) + ", got " + text)
        if (number > maximum) throw new IllegalArgumentException("'" + label + "' must be <= " + boundText(p.maximum, maximum, false) + ", got " + text)
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (Number) number.intValue() : (Number) number.longValue()
    }

    /** What the dialog's text field of number parameter `p` holds, as a number: blanks around the typed text are ignored; an empty field is refused (a required
     *  number has no stand-in, an optional one that was ticked "Set" must not be empty). */
    static Number fromField(Map p, Object raw) {
        String text = raw == null ? '' : raw.toString().trim()
        if (!text) throw new IllegalArgumentException("'" + (p.label as String) + "' " + (p.nullable ? 'is empty: type a number' : 'is required: type a number'))
        return parse(p, text)
    }

    /** The text a number field starts with: the number as it is written (a whole number without a decimal point, a float the way Java prints it, which the grammar accepts). */
    static String startText(Object start) {
        return start instanceof Double || start instanceof Float ? Double.toString(start as double) : (start as BigDecimal).toBigInteger().toString()
    }
}
