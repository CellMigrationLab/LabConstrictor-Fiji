// Body of tests/sandbox_listing.py (NOT part of the product): runs the discovery of the plugin script on the registry $LC_HOME and writes what Fiji lists to the report
// file named by $LC_LISTING_REPORT: {apps: {name: [tool ids]}, problems: [...]}. The product script is parsed without its last line (the call of labConstrictorMain), as
// tests/unit_checks.groovy does, so discoverApps() runs exactly as the plugin runs it. A failure is written to the report too (the driver then says FAIL with it).
import groovy.json.JsonOutput

File report = new File(System.getenv("LC_LISTING_REPORT"))
try {
    def text = new File(System.getenv("LC_FIJI_SCRIPT")).text.replaceFirst(/(?m)^#@/, "//#@").replaceFirst(/(?m)^labConstrictorMain\(\)\s*$/, "")
    def lc = new GroovyShell(this.class.classLoader).parse(text)
    lc.run()
    def found = lc.discoverApps()
    report.text = JsonOutput.toJson([apps: found.apps.collectEntries { String name, Map app -> [(name): app.schema.tools*.id] }, problems: found.problems])
// groovylint-disable-next-line CatchException
} catch (Exception problem) {
    report.text = JsonOutput.toJson([error: problem.toString()])
}
System.exit(0)
