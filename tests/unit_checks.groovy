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
        check("import failed code 3", hint("exit code 3").contains("failed to import"))
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
        check("integer beyond 32 bits", refusal(integer, "2147483648") == "'L' must be <= 2147483647, got 2147483648", refusal(integer, "2147483648"))
        check("integer below 32 bits", refusal(integer, "-2147483649") == "'L' must be >= -2147483648, got -2147483649", refusal(integer, "-2147483649"))
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
        check("the limit", lc.MAX_FORM_INTEGER == limit)
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
        check("small bounds: a 32-bit field", itemOf(wideInteger([minimum: 1, maximum: 9])).getType() == Integer)
        check("wide bound: a 64-bit field", itemOf(wideInteger([maximum: 5_000_000_000L])).getType() == Long)
        def limited = itemOf(wideInteger([minimum: 0, maximum: 10G ** 20]))
        check("limit beyond: a 64-bit field with the limit in its description", limited.getType() == Long && limited.getDescription() == "Whole numbers up to +-9007199254740991." && limited.getMaximumValue() == limit,
              limited.getType().toString() + " " + limited.getDescription() + " " + limited.getMaximumValue())
        check("a wide default is kept", itemOf(wideInteger([default: 4_000_000_000L])).getDefaultValue() == 4_000_000_000L)
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
            (ids ?: []).each { String id -> named << id; check(f.name + " names " + id + ", which is in conformance.json", id in known) }
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
]

cfg.unit_checks.each { String group ->
    if (!groups.containsKey(group)) failures << ("unknown group " + group)
    else try { groups[group]() } catch (Exception problem) { failures << (group + " raised " + problem) }   // the report is written whatever happens
}
new File(cfg.report as String).text = JsonOutput.prettyPrint(JsonOutput.toJson([failures: failures, checked: checked, groups: cfg.unit_checks, unsupported: unsupported]))
System.exit(0)
