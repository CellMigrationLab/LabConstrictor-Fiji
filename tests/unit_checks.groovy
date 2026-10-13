// Unit checks of functions of LabConstrictor_Tools.groovy that no single run can reach (every exit code, every number text, every literal).
// NOT part of the product. Run by tests/run_cases.py for the cases that carry "unit_checks": the product script is parsed WITHOUT its last
// line (the call of labConstrictorMain), so its functions and constants can be called directly. The case names the groups to run.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def cfg = new JsonSlurper().parseText(new File(System.getenv("LC_FIJI_CASE")).text)
def text = new File(cfg.script as String).text.replaceFirst(/(?m)^#@/, "//#@").replaceFirst(/(?m)^labConstrictorMain\(\)\s*$/, "")
def lc = new GroovyShell(this.class.classLoader).parse(text)
lc.run()                                   // the top-level definitions only: hooks, constants, helper classes

def failures = [], checked = 0, unsupported = []     // `unsupported`: conformance cases this host cannot run end to end (said, never counted as passed)
def conformance = { new JsonSlurper().parseText(new File(System.getenv("LC_CONFORMANCE")).getText("UTF-8")) }    // the shared rules as data (run_cases.py sets LC_CONFORMANCE)
def check = { String name, boolean ok, String detail = "" ->
    checked++
    if (!ok) failures << (name + (detail ? ": " + detail : ""))
}
// the refusal a call raises (the message of its IllegalArgumentException), or null when it accepted; any other exception is a failure of the code under test
def refuses = { Closure call ->
    try { call(); return null }
    catch (IllegalArgumentException problem) { return problem.message }
    catch (Exception problem) { failures << ("unexpected " + problem); return "unexpected " + problem }
}

def groups = [
    crash_hints: {
        def hint = { String error, List output = [] -> lc.crashHint([error: error, workerOutput: output, cancelRequested: false]) }
        [-1073741819L, 3221225477L, -11L, 139L].each { code ->
            check("segfault code " + code, hint("worker exited, exit code " + code).contains("crashed natively"), hint("exit code " + code))
        }
        [-9L, 137L].each { code -> check("killed code " + code, hint("exit code " + code).contains("killed")) }
        // code 3 is an import failure only with the worker's own last words (labconstrictor_tools.log.IMPORT_FAILED_MARKER); alone it is what the Windows C runtime gives abort()
        check("import failed: code 3 with the marker", hint("exit code 3", ["LabConstrictor worker: cannot import tool module 'tools' (details above)"]).contains("failed to import"))
        check("code 3 without the marker is not an import failure", !hint("exit code 3").contains("failed to import") && hint("exit code 3").contains("stopped unexpectedly"), hint("exit code 3"))
        check("the marker without code 3 is not an import failure either", !hint("exit code 1", ["cannot import tool module 'tools'"]).contains("failed to import"), hint("exit code 1", ["cannot import tool module 'tools'"]))
        check("missing package wins over the exit code", hint("exit code 137", ["ModuleNotFoundError: No module named 'x'"]).contains("package is missing"))
        check("unknown code", hint("exit code 42").contains("stopped unexpectedly"))
    },
    python_literals: {
        def literal = { def value -> lc.pythonLiteral(value) }
        check("plain", literal("plain") == "'plain'", literal("plain"))
        check("newline", literal("a\nb") == "'a\\nb'", literal("a\nb"))
        check("carriage return", literal("a\rb") == "'a\\rb'", literal("a\rb"))
        check("tab", literal("tab\t") == "'tab\\t'", literal("tab\t"))
        check("backslash", literal("back\\slash") == "'back\\\\slash'", literal("back\\slash"))
        check("quote", literal("it's") == "'it\\'s'", literal("it's"))
        check("control character", literal("a\u0001b") == "'a\\x01b'", literal("a\u0001b"))
        check("delete", literal("a\u007fb") == "'a\\x7fb'", literal("a\u007fb"))
        check("zero width space", literal("a​b") == "'a\\u200bb'", literal("a​b"))
        check("line separator", literal("a b") == "'a\\u2028b'", literal("a b"))
        check("printable unicode stays", literal("naïve µm") == "'naïve µm'", literal("naïve µm"))
        check("emoji stays", literal("a😀b") == "'a😀b'", literal("a😀b"))
        check("true", literal(true) == "True")
        check("false", literal(false) == "False")
        check("float", literal(0.325) == "0.325", literal(0.325))
        check("integer", literal(3) == "3")
    },
    number_grammar: {
        def integer = [type: "integer", label: "L"], decimal = [type: "float", label: "L"]
        def refusal = { Map p, String given -> refuses { lc.macroNumber(p, given) } }
        ["5", "-5", "+5", "007"].each { String t -> check("integer accepts " + t, refusal(integer, t) == null, refusal(integer, t)) }
        ["", " 5", "5 ", "5.0", "1e3", "1_000", "0x10", "\u0665", "nan", "5,0"].each { String t -> check("integer rejects '" + t + "'", refusal(integer, t) != null) }
        ["5", "-5.5", ".5", "5.", "1e3", "1E-5", "0.325", "+2"].each { String t -> check("float accepts " + t, refusal(decimal, t) == null, refusal(decimal, t)) }
        ["", " 0.5", "0.5 ", "1,5", "1_0", "1.5f", "2d", "0x1p3", "1e", "--1", "\u0665"].each { String t -> check("float rejects '" + t + "'", refusal(decimal, t) != null) }
        ["nan", "NaN", "inf", "-inf", "Infinity", "-Infinity", "1e999"].each { String t ->
            check("float refuses '" + t + "' as not finite", (refusal(decimal, t) ?: "").contains("must be a finite number, got '" + t + "'"), refusal(decimal, t))
        }
        check("integer beyond 32 bits is accepted (the limit is 2**53 - 1, section 6)", refusal(integer, "2147483648") == null && lc.macroNumber(integer, "2147483648") == 2147483648L, refusal(integer, "2147483648"))
        check("integer beyond 2**53 - 1", refusal(integer, "9007199254740992") == "'L' must be <= 9007199254740991, got 9007199254740992", refusal(integer, "9007199254740992"))
        check("integer below -(2**53 - 1)", refusal(integer, "-9007199254740992") == "'L' must be >= -9007199254740991, got -9007199254740992", refusal(integer, "-9007199254740992"))
        check("minimum template", refusal([type: "integer", label: "L", minimum: 3], "2") == "'L' must be >= 3, got 2")
        check("exponent value", lc.macroNumber(decimal, "1E-2") == 0.01d)
        check("integer is an int", lc.macroNumber(integer, "+7") instanceof Integer)
        check("integer template", refusal(integer, "2.5") == "'L' must be an integer, got '2.5'", refusal(integer, "2.5"))
        check("float template", refusal(decimal, "abc") == "'L' must be a number, got 'abc'", refusal(decimal, "abc"))
        def words = [name: "mode", label: "Mode", choices: ["fast", "it's"]]
        def numbers = [name: "n", label: "N", choices: [1, 2, 3]]
        check("choices of text as Python writes them", lc.choicesText(words) == "['fast', 'it\\'s']", lc.choicesText(words))
        check("choices of numbers", lc.choicesText(numbers) == "[1, 2, 3]", lc.choicesText(numbers))
        check("choice template uses the name", refuses { lc.typedChoice(words, "slow") } == "'mode' must be one of ['fast', 'it\\'s']", refuses { lc.typedChoice(words, "slow") })
    },
    affine_warp: {
        def plane = new ij.process.FloatProcessor(3, 3, [1, 2, 3, 4, 5, 6, 7, 8, 9] as float[])
        def image = new ij.ImagePlus("plane", plane)
        def pixels = { List matrix -> (0..<9).collect { lc.resample(image, 3, 3, matrix).getf(it) as double } }
        check("identity keeps the pixels", pixels([[1, 0, 0], [0, 1, 0]]) == [1, 2, 3, 4, 5, 6, 7, 8, 9] as List<Double>, pixels([[1, 0, 0], [0, 1, 0]]).toString())
        // [y', x'] = M [y, x]: x' = 0 for every pixel, so each row collapses onto its first column (the mean of the row)
        def collapsed = pixels([[1, 0, 0], [0, 0, 0]])
        check("singular matrix still applied", collapsed == [2, 0, 0, 5, 0, 0, 8, 0, 0] as List<Double>, collapsed.toString())
        check("singular is detected", lc.determinantOf([[1, 0, 0], [0, 0, 0]]).singular)
        check("regular is not singular", !lc.determinantOf([[1, 0, 0], [0, 2, 0]]).singular)
        check("determinant", lc.determinantOf([[1, 0, 0], [0, 2, 0]]).det == 2d)
        def failure = null
        try { lc.resample(image, 3, 3, [[1, 0, 0], [0, Double.NaN, 0]]) } catch (IllegalStateException problem) { failure = problem.message }
        check("non-finite matrix refused", failure == "the alignment matrix contains values that are not finite numbers", failure)
    },
    usable_tools: {
        def schema = [protocol: 1, tools: [
            [id: "fine", label: "Fine", inputs: [[name: "a", label: "A", type: "float"]], outputs: []],
            [id: "odd", label: "Odd", inputs: [[name: "x", label: "X", type: "float"], [name: "m", label: "M", type: "matrix"]], outputs: []],
        ]]
        check("schema with an unknown type is still well formed", lc.schemaProblem(schema) == null, lc.schemaProblem(schema))
        def usable = lc.usableTools(schema)
        check("the known tool stays", usable.tools*.id == ["fine"], usable.tools*.id.toString())
        check("the other is named", usable.skipped == ["tool 'odd': this tool cannot be shown: parameter 'm' has the unknown type 'matrix'"], usable.skipped.toString())
        check("nothing skipped when all are known", lc.usableTools([tools: [schema.tools[0]]]).skipped == [])
    },
    quote_vectors: {
        // tests/fixtures/quote_vectors.json: a copy of LabConstrictor-Tools tests/quote_vectors.json (the shared vectors every host quotes by)
        def vectors = new JsonSlurper().parseText(new File(cfg.vectors as String).text)
        vectors.each { v ->
            check("posix " + v.label, lc.shellQuote(v.text as String, false) == v.posix, lc.shellQuote(v.text as String, false))
            check("windows " + v.label, lc.shellQuote(v.text as String, true) == v.windows, lc.shellQuote(v.text as String, true))
        }
    },
    form_integers: {
        def limit = 9007199254740991L                                  // 2**53 - 1
        def wideInteger = { Map extra -> [type: "integer", name: "big", label: "Big", required: true] + extra }
        def refusal = { Map p, String given -> refuses { lc.macroNumber(p, given) } }
        check("the limit", lc.lib("LCNumbers").FORM_INTEGER_MAX == limit)
        check("bounds within the limit are kept", lc.integerLimits(wideInteger([minimum: -5, maximum: 5_000_000_000L])) == [minimum: -5L, maximum: 5_000_000_000L, limited: false])
        def beyond = lc.integerLimits(wideInteger([minimum: -(10G ** 20), maximum: 10G ** 20]))
        check("bounds beyond the limit are narrowed and said", beyond == [minimum: -limit, maximum: limit, limited: true], beyond.toString())
        def wide = wideInteger([maximum: 5_000_000_000L])
        check("a wide bound is not cut to 32 bits", lc.macroNumber(wide, "3000000000") == 3_000_000_000L, lc.macroNumber(wide, "3000000000").toString())
        check("a small value stays an int", lc.macroNumber(wide, "7") instanceof Integer)
        check("beyond a wide bound", refusal(wide, "5000000001") == "'Big' must be <= 5000000000, got 5000000001", refusal(wide, "5000000001"))
        def huge = wideInteger([maximum: 10G ** 20])
        check("the limit itself is accepted", lc.macroNumber(huge, "9007199254740991") == limit)
        check("beyond the limit is refused", refusal(huge, "9007199254740992") == "'Big' must be <= 9007199254740991, got 9007199254740992", refusal(huge, "9007199254740992"))
        check("below the limit is refused", refusal(huge, "-9007199254740992") == "'Big' must be >= -9007199254740991, got -9007199254740992", refusal(huge, "-9007199254740992"))
        check("32 bits", lc.fitsInt(2147483647) && !lc.fitsInt(2147483648L) && lc.fitsInt(-2147483648) && !lc.fitsInt(-2147483649L))
        def itemOf = { Map p -> lc.buildToolDialog([label: "T", inputs: [p], outputs: []], [])[0].getInput("big") }
        // a number field is text (section 6): the person's text reaches the check unchanged, and the description says what is taken
        check("small bounds: a text field", itemOf(wideInteger([minimum: 1, maximum: 9])).getType() == String)
        check("wide bound: a text field too (nothing is cut to 32 bits)", itemOf(wideInteger([maximum: 5_000_000_000L])).getType() == String)
        def limited = itemOf(wideInteger([minimum: 0, maximum: 10G ** 20]))
        check("limit beyond: a text field that says the limit", limited.getType() == String && limited.getDescription().contains("Whole numbers up to +-9007199254740991.") && limited.getDescription().contains("refused, never changed"), limited.getType().toString() + " " + limited.getDescription())
        check("a wide default is kept as typed", itemOf(wideInteger([default: 4_000_000_000L])).getDefaultValue() == "4000000000")
    },
    conformance_frame: {
        // docs/SPEC_WRONG_SCIENCE.md section 1, conformance.json wrong_science.coordinate (CF-01..CF-14), at the level of the functions that place results
        def C = conformance(), WS = C.wrong_science.coordinate
        def cases = WS.cases.collectEntries { [(it.id): it] }
        (1..14).each { n -> check(String.format("CF-%02d is in conformance.json", n), cases.containsKey(String.format("CF-%02d", n))) }
        def rows = WS.image.shape[0] as int, columns = WS.image.shape[1] as int
        check("the fixture image is ASYM = 100*y + x", WS.image.id == "ASYM" && WS.image.value == "100*y + x" && WS.image.dtype == "uint16")
        def asym = new ij.process.ShortProcessor(columns, rows)
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) asym.set(x, y, 100 * y + x)
        def fixture = ij.IJ.openImage(cfg.asym as String)
        check("tests/fixtures/ASYM.tif is the fixture image", fixture != null && fixture.getWidth() == columns && fixture.getHeight() == rows &&
              (0..<rows).every { y -> (0..<columns).every { x -> fixture.getProcessor().get(x, y) == asym.get(x, y) } })
        def source = new ij.ImagePlus("ASYM", asym)
        // messages and codes
        def template = C.messages.size_mismatch.template as String
        def expected = template.replace("{name}", "lab").replace("{w}", "6").replace("{h}", "4").replace("{source}", "ASYM.tif").replace("{w2}", "12").replace("{h2}", "8")
        check("the size mismatch sentence is the conformance text", lc.sizeMismatchText("lab", [6, 4], "ASYM.tif", [12, 8]) == expected, lc.sizeMismatchText("lab", [6, 4], "ASYM.tif", [12, 8]))
        check("the size mismatch template constant is the conformance template", lc.SIZE_MISMATCH_TEXT == template.replaceAll(/\{(name|source)\}/, "%s").replaceAll(/\{(w|h|w2|h2)\}/, "%d"), lc.SIZE_MISMATCH_TEXT)
        check("selection_mixed_planes is a host-internal code", lc.REFUSAL_SELECTION_PLANES in C.host_internal_codes)
        // the frame: no transform (ImageJ images have none), exported area = the whole image at downsample 1, plane of the window at the click
        def frame = lc.frameOf("image", source, null)
        check("frame: identity by construction, no transform", !frame.containsKey("transform") && frame.downsample == 1.0d && frame.axes_offset == [t: 0, z: 0, y: 0.0d, x: 0.0d], frame.toString())
        check("frame: the exported size and the source object", frame.width == columns && frame.height == rows && frame.image.is(source) && frame.label == "ASYM")
        // CF-01..05 point: the point lands at target_px + 0.5 whatever the source's transform (Fiji has none; its calibration is never applied to a ROI)
        def roiOf = { double y, double x, Map f ->
            def table = new ij.measure.ResultsTable()
            table.addRow(); table.addValue("y", y); table.addValue("x", x)
            def polygon = lc.pointRoiOf(table, f).getFloatPolygon()
            [polygon.xpoints[0] as double, polygon.ypoints[0] as double]
        }
        cases.values().findAll { it.point && it.expect?.target_px }.each { c ->
            def tx = c.expect.target_px[1] as double, ty = c.expect.target_px[0] as double
            check(c.id + " point " + c.point + " is drawn at target_px + 0.5 = " + [tx + 0.5, ty + 0.5], roiOf(c.point[0] as double, c.point[1] as double, frame) == [tx + 0.5, ty + 0.5], roiOf(c.point[0] as double, c.point[1] as double, frame).toString())
        }
        // a calibration (CF-03 scale and translate, as Fiji has them) is not applied to ROIs
        def calibrated = new ij.ImagePlus("calibrated", asym.duplicate())
        calibrated.getCalibration().pixelWidth = 0.25d; calibrated.getCalibration().pixelHeight = 0.5d; calibrated.getCalibration().xOrigin = -80d; calibrated.getCalibration().yOrigin = -20d
        calibrated.getCalibration().setUnit("um")
        def calibratedFrame = lc.frameOf("image", calibrated, null)
        check("CF-03 a calibrated image places the point exactly like an uncalibrated one", roiOf(2d, 3d, calibratedFrame) == [3.5d, 2.5d], roiOf(2d, 3d, calibratedFrame).toString())
        // CF-01 outline: every vertex of the rectangle (given [y, x] in the fixture, [x, y] in the protocol) at vertex + 0.5
        def ring = WS.rect.collect { [it[1], it[0]] }; ring << ring[0]
        def outline = lc.polygonRoi([ring], frame).getFloatPolygon()
        def drawn = (0..<outline.npoints).collect { [outline.xpoints[it] as double, outline.ypoints[it] as double] } as Set
        check("CF-01 the outline is drawn at the rectangle's vertices + 0.5", drawn == WS.rect.collect { [(it[1] + 0.5) as double, (it[0] + 0.5) as double] } as Set, drawn.toString())
        // CF-09 and CF-14: c = x0 + (x + 0.5) * downsample, in ImageJ coordinates (target_px + 0.5 is c)
        def framed = { double y0, double x0, double d -> frame + [axes_offset: [t: 0, z: 0, y: y0, x: x0], downsample: d] }
        def crop = cases["CF-09"]
        check("CF-09 a crop offset moves the result by it", roiOf(crop.point[0] as double, crop.point[1] as double, framed(crop.expect.export_offset_yx[0] as double, crop.expect.export_offset_yx[1] as double, 1d)) ==
              [crop.result_expect.target_px[1] + 0.5d, crop.result_expect.target_px[0] + 0.5d], roiOf(crop.point[0] as double, crop.point[1] as double, framed(2d, 4d, 1d)).toString())
        cases["CF-14"].variants.each { v ->
            def at = roiOf(v.point[0] as double, v.point[1] as double, framed((v.offset_yx[0] as double), (v.offset_yx[1] as double), (v.downsample as double)))
            check("CF-14 downsample " + v.downsample + " offset " + v.offset_yx + ": c_yx " + v.expect.c_yx, at == [v.expect.c_yx[1] as double, v.expect.c_yx[0] as double], at.toString())
            if (v.downsample != 1) unsupported << ("CF-14 downsample " + v.downsample + ": only the arithmetic is checked; Fiji exports at downsample 1")
        }
        // RegionOf (1.2): the mask has the exported size and holds the pixels whose centre is inside the polygon, ROIs being pixel based
        def polygon = { List rect, double shift ->
            new ij.gui.PolygonRoi(rect.collect { (it[1] + shift) as float } as float[], rect.collect { (it[0] + shift) as float } as float[], rect.size(), ij.gui.Roi.POLYGON)
        }
        def pixelsOf = { ij.process.ShortProcessor mask -> (0..<rows).collectMany { y -> (0..<columns).findAll { x -> mask.get(x, y) > 0 }.collect { x -> [y, x] } } as Set }
        def mask = lc.regionMask([polygon(WS.rect, 0.5d)], columns, rows)
        check("the mask has the exported size", mask.getWidth() == columns && mask.getHeight() == rows)
        check("the mask holds exactly rect_mask_pixels", pixelsOf(mask) == WS.rect_mask_pixels as Set, pixelsOf(mask).toString())
        check("the pixels under the mask are rect_mask_values", WS.rect_mask_pixels.collect { asym.get(it[1] as int, it[0] as int) } == WS.rect_mask_values, WS.rect_mask_pixels.collect { asym.get(it[1] as int, it[0] as int) }.toString())
        // 1.2.5: one selection lies on one plane
        def onPlane = { Map position -> def r = new ij.gui.Roi(0, 0, 4, 4); r.setPosition(position.c ?: 0, position.z ?: 0, position.t ?: 0); r }
        check("rois with no plane count for none", lc.selectionPlanes([new ij.gui.Roi(0, 0, 4, 4), new ij.gui.Roi(1, 1, 2, 2)]).isEmpty())
        check("two rois on one plane are one plane", lc.selectionPlanes([onPlane([z: 2]), onPlane([z: 2])]).size() == 1)
        check("rois on two z planes are two planes", lc.selectionPlanes([onPlane([z: 1]), onPlane([z: 2])]).size() == 2)
        check("rois on two time points are two planes", lc.selectionPlanes([onPlane([z: 1, t: 1]), onPlane([z: 1, t: 2])]).size() == 2)
        def stack = new ij.ImagePlus("stack", new ij.ImageStack(columns, rows).with { s -> 3.times { s.addSlice(new ij.process.ShortProcessor(columns, rows)) }; s })
        stack.setDimensions(1, 3, 1)
        stack.setPosition(1, 2, 1)
        check("the exported plane is the window's plane at the click", lc.frameOf("image", stack, null).plane == [c: 0, z: 2, t: 1], lc.frameOf("image", stack, [channel: 2]).plane.toString())
        check("a picked channel is part of the plane", lc.frameOf("image", stack, [channel: 2]).plane.c == 2)
        check("results that name planes are not put on one plane of a stack", lc.planeProblem(true, stack) != null && lc.planeProblem(false, stack) == null && lc.planeProblem(true, source) == null)
        // what Fiji cannot be asked: images have no transform (1.3), no multiscale levels, no mixed QuPath planes
        unsupported << "CF-04 rotation, CF-05 shear, CF-13 non-invertible: a Fiji image has no transform (identity by construction, 1.3)"
        unsupported << "CF-06 multiscale: no levels in an ImagePlus"
        unsupported << "CF-09 crop export, CF-10 QuPath planes: QuPath only (the offset arithmetic of CF-09 is checked above)"
    },
    conformance_coverage: {
        // no silent skip (docs/CONFORMANCE.md): every case of the coordinate-frame fixtures is run by a case file ("conformance": [ids]), checked at the level of the functions
        // (conformance_frame), or named here as not applicable to Fiji with its reason
        def C = conformance(), WS = C.wrong_science.coordinate
        def known = WS.cases*.id as Set
        def named = [] as Set
        new File(cfg.cases_dir as String).listFiles({ File f -> f.name.endsWith(".json") } as FileFilter).sort().each { File f ->
            def ids = new JsonSlurper().parseText(f.getText("UTF-8")).conformance
            (ids ?: []).each { String id -> named << id; check(f.name + " names " + id + ", which is in conformance.json", id in known || id.startsWith("CAL-")) }       // the CAL ids are checked by conformance_calibration_coverage
        }
        def atFunctionLevel = ["CF-01", "CF-02", "CF-03", "CF-09", "CF-14"] as Set       // the arithmetic of placement, the mask pixels and the offsets (conformance_frame)
        def notApplicable = [
            "CF-04": "rotation: a Fiji image has no transform (identity by construction, 1.3)", "CF-05": "shear: no transform", "CF-06": "multiscale: no levels in an ImagePlus",
            "CF-13": "non-invertible transform: no transform",
        ]
        known.each { String id ->
            check(id + " is run by a case, checked at function level, or not applicable", id in named || id in atFunctionLevel || notApplicable.containsKey(id))
            if (notApplicable.containsKey(id)) unsupported << (id + " " + notApplicable[id])
        }
        check("the not-applicable cases are not also claimed as run", notApplicable.keySet().every { !(it in named) && !(it in atFunctionLevel) })
    },
    conformance_calibration: {
        // docs/SPEC_WRONG_SCIENCE.md section 2, conformance.json wrong_science.calibration: CAL-01..CAL-20 at the level of the unit conversion, with an open image's Calibration
        // (what Fiji reads) and with TIFF files (tests/run_cases.py writes them from the same cases); the request the host would send is checked in conformance_calibration_request
        def C = conformance(), CAL = C.wrong_science.calibration
        def LC = lc.getClass().getClassLoader().loadClass("LCCalibration")
        LC.catalog = LC.catalogFrom(C)                           // the catalog from the conformance file itself, never from a copy
        def cases = CAL.cases.collectEntries { [(it.id): it] }
        (1..20).each { n -> check(String.format("CAL-%02d is in conformance.json", n), cases.containsKey(String.format("CAL-%02d", n))) }
        def near = { def found, def wanted -> found != null && Math.abs((found as double) - (wanted as double)) <= 1e-9 * Math.max(1.0d, Math.abs(wanted as double)) }
        def imageWith = { double width, double height, String unit ->
            def imp = new ij.ImagePlus("c", new ij.process.ByteProcessor(2, 2))
            imp.getCalibration().pixelWidth = width; imp.getCalibration().pixelHeight = height; imp.getCalibration().setUnit(unit)
            imp
        }
        def check1 = { String name, Map found, def expected, String warning = null ->
            if (expected == "unknown") check(name + " is unknown", !found.known && found.value == null, found.toString())
            else check(name + " is " + expected, found.known && near(found.value, expected), found.value.toString())
            if (warning != null) check(name + " warns '" + warning + "'", found.warning == warning, String.valueOf(found.warning))
        }
        // the sentences are the conformance ones
        check("pixels are not square is the conformance text", LC.message("pixels_not_square_warning") == C.messages.pixels_not_square_warning.template)
        check("unit 's' is not a length", LC.message("unit_not_length_warning", [unit: "s"]) == C.messages.unit_not_length_warning.template.replace("{unit}", "s"))
        // CAL-01..04: a layer's scale and units in Napari are an ImageJ image's pixelWidth, pixelHeight and unit here (a bare scale with the default unit is unknown)
        [["CAL-01", [1d, 1d], "micrometer", 1.0], ["CAL-02", [1d, 1d], "pixel", "unknown"], ["CAL-03", [1d, 1d], "millimeter", 1000.0]].each { id, scale, unit, wanted ->
            check1(id, LC.ofImage(imageWith(scale[1], scale[0], unit)), wanted)
        }
        cases["CAL-04"].input.units.each { units -> check1("CAL-04 scale 0.25 units " + units, LC.ofImage(imageWith(0.25d, 0.25d, units[0] as String)), "unknown") }
        cases["CAL-05"].input.variants.each { v -> check1("CAL-05 " + v.scale + " " + v.unit, LC.ofImage(imageWith(v.scale as double, v.scale as double, v.unit as String)), cases["CAL-05"].expect.um_per_px) }
        check1("CAL-06", LC.ofImage(imageWith(0.25d, 0.5d, "um")), "unknown", "pixels are not square")                  // scale (y 0.5, x 0.25)
        check1("CAL-07", LC.ofImage(imageWith(1d, 1d, "second")), "unknown", "unit 'second' is not a length")
        cases["CAL-08"].input.variants.eachWithIndex { v, i -> check1("CAL-08 (ImageJ) unit '" + v.unit + "' width " + v.pixelWidth, LC.ofImage(imageWith(v.pixelWidth as double, v.pixelWidth as double, v.unit as String)), cases["CAL-08"].expect.um_per_px_each[i]) }
        unsupported << "CAL-09 QuPath PixelCalibration: QuPath only"
        cases["CAL-15"].input.variants.each { v -> check1("CAL-15 '" + v.unit + "'", LC.ofImage(imageWith(v.scale as double, v.scale as double, v.unit as String)), 0.5) }
        check1("CAL-16", LC.ofImage(imageWith(0.5d, 0.5d, cases["CAL-16"].input.unit as String)), "unknown", cases["CAL-16"].expect.warning as String)
        cases["CAL-17"].input.variants.each { v -> check1("CAL-17 '" + v.unit + "'", LC.ofImage(imageWith(1d, 1d, v.unit as String)), 0.0001) }
        cases["CAL-18"].input.variants.eachWithIndex { v, i ->
            check1("CAL-18 scale " + v.scale, LC.ofImage(imageWith(v.scale[1] as double, v.scale[0] as double, v.unit as String)), cases["CAL-18"].expect.um_per_px_each[i], i == 1 ? "pixels are not square" : null)
        }
        CAL.unit_match_refused.each { name -> check1("CAL-19 unit '" + name + "'", LC.ofImage(imageWith(1d, 1d, name as String)), "unknown") }
        check("CAL-19 names " + CAL.unit_match_refused.size() + " units", CAL.unit_match_refused.size() == cases["CAL-19"].expect.um_per_px_each.size())
        cases["CAL-20"].input.variants.eachWithIndex { v, i -> check1("CAL-20 '" + v.unit + "'", LC.ofImage(imageWith(1d, 1d, v.unit as String)), cases["CAL-20"].expect.um_per_px_each[i]) }
        // the catalog itself: every name of it converts, symbols exactly, words in any case with spaces around, never a symbol in another case
        CAL.length_units.each { name, unit ->
            unit.symbols.each { check1("catalog symbol '" + it + "'", LC.ofImage(imageWith(1d, 1d, it as String)), unit.um_per_unit) }
            unit.words.each { word ->
                check1("catalog word '" + word + "'", LC.ofImage(imageWith(1d, 1d, " " + (word as String).toUpperCase() + " ")), unit.um_per_unit)
                check1("catalog word '" + word + "' with a space inside", LC.ofImage(imageWith(1d, 1d, (word as String).substring(0, 3) + " " + (word as String).substring(3))), "unknown")
            }
        }
        // ImageJ's own spellings: the blank default unit of an uncalibrated image, "pixel", "micron" and "microns", the micro sign it writes for "um"
        check("ImageJ's blank default unit is unknown and says nothing", !LC.ofImage(imageWith(0.5d, 0.5d, " ")).known && LC.ofImage(imageWith(0.5d, 0.5d, " ")).warning == null && LC.ofImage(new ij.ImagePlus("u", new ij.process.ByteProcessor(2, 2))).warning == null)
        check1("ImageJ's microns", LC.ofImage(imageWith(0.5d, 0.5d, "microns")), 0.5d)
        check("ImageJ keeps 'um' typed into a Calibration as the micro sign, which is a symbol of the catalog", imageWith(0.5d, 0.5d, "um").getCalibration().getUnit() == "\u00b5m" && LC.ofImage(imageWith(0.5d, 0.5d, "um")).value == 0.5d)
        // the unit of X and of Y are read apart (ImageJ keeps one per axis)
        def mixed = imageWith(0.5d, 0.5d, "um"); mixed.getCalibration().setYUnit("mm")
        check("X in um and Y in mm are not one pixel size", !LC.ofImage(mixed).known)
        check("a size of 0 or below is unknown", !LC.ofImage(imageWith(0d, 0d, "um")).known && !LC.ofImage(imageWith(-1d, -1d, "um")).known)
        // TIFF files (CAL-10..CAL-14 and a few more of the same kind), written by run_cases.py with tifffile from the same cases
        def file = { String name -> new File(cfg.files_dir as String, name + ".tif") }
        def ofFile = { String name -> LC.ofFile(file(name)) }
        cases["CAL-10"].expect.um_per_px_each.eachWithIndex { wanted, i -> check1("CAL-10 file " + i, ofFile("CAL-10_" + i), wanted) }
        check("CAL-10 ImageJ unit names the file as the source", ofFile("CAL-10_0").source == "file" && ofFile("CAL-10_0").x.raw == [scale: 1.0d, unit: "micron"], ofFile("CAL-10_0").toString())
        check1("CAL-11 72 dpi", ofFile("CAL-11"), "unknown")
        check1("CAL-12 96 dpi", ofFile("CAL-12"), "unknown")
        cases["CAL-13"].input.variants.eachWithIndex { v, i -> check1("CAL-13 " + v.resolution, ofFile("CAL-13_" + i), "unknown") }
        check1("CAL-14 25400 dpi", ofFile("CAL-14"), cases["CAL-14"].expect.um_per_px)
        check("CAL-14 the source is the file", ofFile("CAL-14").source == cases["CAL-14"].expect.source)
        check1("72.3 dpi is within 0.5 % of 72", ofFile("INCH_72.3"), "unknown")
        check1("73 dpi is not", ofFile("INCH_73"), 25400.0d / 73.0d)
        check1("one axis that looks like a default makes the file unknown", ofFile("INCH_72_96"), "unknown")
        check1("37.7 per cm is within 0.5 % of 37.8", ofFile("CM_37.7"), "unknown")
        check1("40000 per cm is 0.25 um per pixel", ofFile("CM_40000"), 0.25d)
        check1("OME 325 nm", ofFile("OME_nm"), 0.325d)
        check("OME wins over the tags", ofFile("OME_nm").x.raw.unit == "nm", ofFile("OME_nm").toString())
        check1("OME with different X and Y sizes", ofFile("OME_not_square"), "unknown", "pixels are not square")
        check1("an OME size with no unit is not a calibration", ofFile("OME_no_unit"), "unknown")
        check1("an ImageJ unit of nm", ofFile("IMAGEJ_nm"), 0.0005d)
        check1("the ImageJ unit text 'inch' is not in the catalog", ofFile("IMAGEJ_inch_text"), "unknown")
        check1("a file that is not there", LC.ofFile(new File(cfg.files_dir as String, "missing.tif")), "unknown")
        // the built-in copy of the catalog (used when the app's Tools has none) is the conformance data
        def builtIn = LC.catalogFrom(lc.UNIT_CATALOG_FALLBACK)
        def fromFile = LC.catalogFrom(C)
        check("the built-in catalog is the conformance catalog", builtIn == fromFile, "built-in " + builtIn.keySet() + " differs from the file")
        check("the built-in length units are the conformance length units", lc.UNIT_CATALOG_FALLBACK.wrong_science.calibration.length_units == CAL.length_units.collectEntries { k, v -> [(k): [um_per_unit: v.um_per_unit, symbols: v.symbols, words: v.words]] })
        // a catalog that is not one is refused, never half used
        ["{}", '{"wrong_science": {"calibration": {"length_units": {"um": {"um_per_unit": -1, "symbols": [], "words": []}}}}}'].each { json ->
            def refused = false
            try { LC.catalogFrom(new JsonSlurper().parseText(json) as Map) } catch (IllegalArgumentException problem) { refused = true }
            check("a catalog that is not usable is refused: " + json.take(30), refused)
        }
        // the app's own catalog: found through the app's interpreter, else the built-in copy is used and said
        def app = [python: cfg.python, runtime_path: "", name: "catalog-test"]
        LC.catalog = null
        check("the app's interpreter names the catalog", lc.useUnitCatalog(app) == "app" && LC.catalog == fromFile, "")
        LC.catalog = null
        check("an interpreter that cannot be run falls back to the built-in catalog", lc.useUnitCatalog([python: "/nonexistent/python", runtime_path: "", name: "catalog-test"]) == "built-in" && LC.catalog == builtIn, "")
        LC.catalog = fromFile
    },
    conformance_calibration_request: {
        // what the host would SEND (inputs of the worker, and `_calibration`) for the sources of the CAL cases and the situations of CAL-T1..CAL-T9 that need no dialog event
        def C = conformance(), CAL = C.wrong_science.calibration
        def LC = lc.getClass().getClassLoader().loadClass("LCCalibration")
        LC.catalog = LC.catalogFrom(C)
        def Macro = lc.getClass().getClassLoader().loadClass("MacroModule")
        def sourceOf = { double width, double height, String unit -> def imp = new ij.ImagePlus("a.tif", new ij.process.ByteProcessor(4, 4)); imp.getCalibration().pixelWidth = width; imp.getCalibration().pixelHeight = height; imp.getCalibration().setUnit(unit); imp }
        def toolWith = { boolean optional ->
            [id: "t", label: "T", outputs: [], inputs: [
                [name: "image", label: "Image", type: "image", required: true],
                [name: "pixel_size", label: "Pixel size", type: "float", required: !optional, nullable: optional, default: optional ? null : 1.0d, minimum: 0, pixel_size_of: "image"]]]
        }
        def run = { Map tool, Map values, Map states = [:] ->
            def module = Macro.newInstance(); module.values.putAll(values); module.states.putAll(states)
            def jobDir = java.nio.file.Files.createTempDirectory("lccal_unit_").toFile()
            try { return lc.exportInputs(tool, module, jobDir) }
            finally { jobDir.deleteDir() }
        }
        def refusal = { Closure call -> try { call(); return null } catch (IllegalArgumentException problem) { return problem.message } }
        def required = C.messages.calibration_required.template.replace("{param}", "pixel_size").replace("{of}", "image")
        check("the refusal names the parameter and the image", required == "'pixel_size' needs a value: 'image' has no pixel size. Enter it in um.", required)
        // CAL-01: a calibrated open image: the value, and the per-axis entry with its provenance
        def sent = run(toolWith(false), [image: sourceOf(1d, 1d, "micrometer")])[0]
        check("CAL-01 the request carries 1.0", sent.pixel_size == 1.0d, sent.toString())
        check("CAL-01 and the per-axis entry of the image, with the raw scale and unit",
              sent._calibration == [pixel_size: [of: "image", source: "image", axes: [x: [value: 1.0d, unit: "um", source: "image", raw: [scale: 1.0d, unit: "micrometer"]], y: [value: 1.0d, unit: "um", source: "image", raw: [scale: 1.0d, unit: "micrometer"]]]]], sent._calibration.toString())
        check("CAL-03 a millimetre is 1000 um", run(toolWith(false), [image: sourceOf(1d, 1d, "millimeter")])[0].pixel_size == 1000.0d)
        check("CAL-05 325 nm", Math.abs(run(toolWith(false), [image: sourceOf(325d, 325d, "nm")])[0].pixel_size - 0.325d) < 1e-12)
        def mean = run(toolWith(false), [image: sourceOf(0.5d, 0.5004d, "um")])[0]
        check("CAL-18 within the tolerance the field is the mean and each axis keeps its own value", Math.abs(mean.pixel_size - 0.5002d) < 1e-12 && mean._calibration.pixel_size.axes.x.value == 0.5d && mean._calibration.pixel_size.axes.y.value == 0.5004d, mean._calibration.toString())
        // CAL-02, 04, 06, 07, 16: unknown: a required field is refused with the shared sentence, whatever the declared default (CAL-T8); an Optional one is null and has no entry (CAL-T2)
        [[1d, 1d, "pixel"], [0.25d, 0.25d, "pixel"], [0.5d, 0.25d, "um"], [1d, 1d, "second"], [0.5d, 0.5d, "�m"]].each { w, h, unit ->
            def image = sourceOf(w, h, unit)
            check("unknown (" + w + ", " + h + ", '" + unit + "'): the run is refused with the shared sentence, the default 1.0 is not sent", refusal { run(toolWith(false), [image: image]) } == required)
            def optional = run(toolWith(true), [image: image])[0]
            check("unknown (" + w + ", " + h + ", '" + unit + "'): an Optional field is not sent, and has no _calibration entry", !optional.containsKey("pixel_size") && !optional.containsKey("_calibration"), optional.toString())
        }
        // CAL-T1, CAL-T2: the request follows the image it is made for, and never carries another one's value
        check("CAL-T1 B (0.5) after A (0.25): 0.5, source image", run(toolWith(false), [image: sourceOf(0.5d, 0.5d, "um")])[0].pixel_size == 0.5d)
        check("CAL-T2 uncalibrated B after A (0.25): refused", refusal { run(toolWith(false), [image: sourceOf(1d, 1d, "pixel")]) } == required)
        // CAL-T3, CAL-T9: a value the person entered is theirs, whatever the image says, and says so
        def entered = run(toolWith(false), [image: sourceOf(0.5d, 0.5d, "um"), pixel_size: 0.3d], [pixel_size: [state: "user", value: 0.3d]])[0]
        check("CAL-T3 user 0.3 with B (0.5): 0.3, source user, per axis", entered.pixel_size == 0.3d && entered._calibration.pixel_size.source == "user" && entered._calibration.pixel_size.axes.x == [value: 0.3d, unit: "um", source: "user"], entered._calibration.toString())
        check("CAL-T9 (request) user 0.25 with an uncalibrated image: 0.25, source user", run(toolWith(false), [image: sourceOf(1d, 1d, "pixel"), pixel_size: 0.25d], [pixel_size: [state: "user", value: 0.25d]])[0].pixel_size == 0.25d)
        check("an entered value that is not above 0 is refused", refusal { run(toolWith(false), [image: sourceOf(0.5d, 0.5d, "um"), pixel_size: -1d], [pixel_size: [state: "user", value: -1d]]) } != null)
        check("an entered value that is not finite is refused", refusal { run(toolWith(false), [image: sourceOf(0.5d, 0.5d, "um"), pixel_size: Double.NaN], [pixel_size: [state: "user", value: Double.NaN]]) } != null)
        // CAL-T5, CAL-T6: the file is the exported source and wins over the open image; an uncalibrated file is unknown, never the open image's value
        def calibratedFile = new File(cfg.files_dir as String, "CM_40000.tif"), plainFile = new File(cfg.files_dir as String, "CAL-10_1.tif")
        def open = sourceOf(0.25d, 0.25d, "um")
        check("CAL-T5 open image A (0.25) and an uncalibrated file B: refused, A's value is not used", refusal { run(toolWith(false), [image: open, image_file: plainFile]) } == required)
        check("CAL-T5 (Optional) the request holds neither", !run(toolWith(true), [image: open, image_file: plainFile])[0].containsKey("pixel_size"))
        check("CAL-T6 the file cleared: A's 0.25 again", run(toolWith(false), [image: open])[0].pixel_size == 0.25d)
        def fromFile = run(toolWith(false), [image: open, image_file: calibratedFile])
        check("a calibrated file wins over a calibrated image: its value, source file, raw in cm", fromFile[0].pixel_size == 0.25d && fromFile[0]._calibration.pixel_size.source == "file" && fromFile[0]._calibration.pixel_size.axes.x.raw.unit == "cm", fromFile[0]._calibration.toString())
        // CAL-T8: a required linked field with the default 1.0 and no calibration: blocked, the default is not sent (above); here the tool's own default is 1.0
        check("CAL-T8 the declared default is not a stand-in", toolWith(false).inputs[1].default == 1.0d && refusal { run(toolWith(false), [image: sourceOf(1d, 1d, "")]) } == required)
        // the CAL and CAL-T ids are all accounted for
        (1..9).each { n -> check(String.format("CAL-T%d is in conformance.json", n), CAL.transitions.any { it.id == "CAL-T" + n }) }
        unsupported << "CAL-T7 QuPath project image: QuPath only"
    },
    conformance_calibration_coverage: {
        // no silent skip: every CAL and CAL-T id is checked at the level of the conversion (conformance_calibration), of the request (conformance_calibration_request), run through the
        // dialog by a case file ("conformance": [ids]), or named here with the reason it does not apply to Fiji
        def C = conformance(), CAL = C.wrong_science.calibration
        def known = (CAL.cases*.id + CAL.transitions*.id) as Set
        def named = [] as Set, gaps = [] as Set
        new File(cfg.cases_dir as String).listFiles({ File f -> f.name.endsWith(".json") } as FileFilter).sort().each { File f ->
            def parsed = new JsonSlurper().parseText(f.getText("UTF-8"))
            (parsed.conformance ?: []).each { String id -> named << id; check(f.name + " names " + id + ", which is in conformance.json", id in known || id.startsWith("CF-")) }
            (parsed.conformance_gaps ?: []).each { String id -> gaps << id; check(f.name + " documents the gap " + id + ", which is in conformance.json", id in known) }
        }
        def atFunctionLevel = (1..20).collect { String.format("CAL-%02d", it) }.findAll { it != "CAL-09" } as Set
        atFunctionLevel += ["CAL-T1", "CAL-T2", "CAL-T3", "CAL-T5", "CAL-T6", "CAL-T8"]       // their request (conformance_calibration_request) and, with the dialog events, a case file
        def notApplicable = [
            "CAL-09": "QuPath PixelCalibration", "CAL-T7": "QuPath project image",
            "CAL-T9": "a SciJava dialog reports a number field only when its value changes: typing the value the box already shows is not an event, so the flag cannot be set (the field is then cleared, never kept wrongly)",
        ]
        known.each { String id ->
            check(id + " is run by a case, checked at function level, or not applicable", id in named || id in atFunctionLevel || notApplicable.containsKey(id))
            if (notApplicable.containsKey(id)) unsupported << (id + " " + notApplicable[id])
        }
        ["CAL-T1", "CAL-T2", "CAL-T3", "CAL-T4", "CAL-T5", "CAL-T6"].each { id -> check(id + " is run through the dialog by a case file", id in named) }
        check("CAL-T9, which this dialog cannot report, has a case that pins what happens instead", "CAL-T9" in gaps)
    },
]

// groups kept in files of their own (tests/unit_groups_*.groovy, each a closure that takes the toolbox and returns its groups by name)
def toolbox = [lc: lc, check: check, refuses: refuses, conformance: conformance, cfg: cfg, failures: failures, unsupported: unsupported]
new File(System.getenv("LC_UNIT_GROUPS")).listFiles({ File f -> f.name.startsWith("unit_groups_") && f.name.endsWith(".groovy") } as FileFilter).sort().each { File file ->
    groups += (new GroovyShell(this.class.classLoader).evaluate(file) as Closure).call(toolbox) as Map
}

cfg.unit_checks.each { String group ->
    if (!groups.containsKey(group)) failures << ("unknown group " + group)
    else try { groups[group]() } catch (Exception problem) { failures << (group + " raised " + problem) }   // the report is written whatever happens
}
new File(cfg.report as String).text = JsonOutput.prettyPrint(JsonOutput.toJson([failures: failures, checked: checked, groups: cfg.unit_checks, unsupported: unsupported]))
System.exit(0)
