// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): may this host use a schema written by this version of Tools? The rules of
// docs/PROTOCOL.md ("Compatibility rules for hosts"), written here in Groovy exactly as labconstrictor_tools.compat.check_schema writes them in Python; the test
// feeds this class the vectors of conformance.json (`compat.cases`, `compat.version_order`) and a case compares its sentences with the reference function.
// Every method is typed; nothing here reads the script's globals.
import java.util.regex.Matcher
import java.util.regex.Pattern

/** The compatibility rules of PROTOCOL.md for a host: ok, warn or refuse, with one sentence for the person (see the comment at the top of this file). */
class LCCompat {
    static final String OK = 'ok'
    static final String WARN = 'warn'
    static final String REFUSE = 'refuse'
    static final Pattern VERSION = Pattern.compile(/^v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-.]?(a|alpha|b|beta|rc|c)[-.]?(\d+))?$/, Pattern.CASE_INSENSITIVE)
    static final Map<String, Integer> PRE_ORDER = [a: 0, alpha: 0, b: 1, beta: 1, rc: 2, c: 2]
    static final int RELEASE_RANK = 3              // a release sorts after its pre-releases
    static final String MINIMUM_TOOLS = '0.3.0a1'  // what this host needs: the run-record helpers and the prune command (tag v0.3.0-alpha.1)
    // reserved_inputs_v1: the worker ignores reserved underscore inputs (_job_dir, ...); app_hosts_v1: the registry entry can declare the hosts (LCHosts)
    static final List<String> REQUIRED_FEATURES = ['reserved_inputs_v1', 'app_hosts_v1']

    /** `0.2.0rc1` / `v0.2.0-rc.1` / `0.2` as a sortable list (a release sorts after its pre-releases); null when it is not a version (or not text). */
    static List<Integer> parseVersion(Object text) {
        if (!(text instanceof String)) return null
        Matcher match = VERSION.matcher((text as String).trim())
        if (!match.matches()) return null
        String pre = match.group(4)
        int rank = pre != null ? PRE_ORDER[pre.toLowerCase(Locale.ROOT)] : RELEASE_RANK
        int number = pre != null ? Integer.parseInt(match.group(5)) : 0
        return [Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), match.group(3) != null ? Integer.parseInt(match.group(3)) : 0, rank, number]
    }

    /** -1, 0 or 1: the order of two parsed versions (the lists compare like Python tuples). */
    static int compareVersions(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < a.size(); i++) {
            if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1
        }
        return 0
    }

    /** How Python's `%r` writes a value in the sentences of the reference: text in quotes, a number or None as it is. */
    private static String python(Object value) {
        return value == null ? 'None' : value instanceof String ? "'" + value + "'" : value.toString()
    }

    private static Map<String, String> verdict(String status, String reason) { return [status: status, reason: reason] }

    /** Apply the host rules to one cached schema: [status: ok|warn|refuse, reason: the sentence ('' when ok)]. A missing field means unknown or old, never an error;
     *  a minimum or a `warn below` that is not a version is the host's own programming error (IllegalArgumentException). */
    static Map<String, String> checkSchema(Map schema, String minimum, Collection<String> required, String warnBelow, List<Integer> protocols) {
        [['min_tools_version', minimum], ['warn_below_tools_version', warnBelow]].each { List pair ->
            if (pair[1] && parseVersion(pair[1]) == null) throw new IllegalArgumentException(pair[0] + ' ' + python(pair[1]) + ' is not a version (programming error in the host)')
        }
        List<String> needed = required.toSet().toList().sort()
        Object protocol = schema.get('protocol')
        if (!(protocol in protocols)) return verdict(REFUSE, 'this app speaks protocol ' + python(protocol) + ', which this host does not support')
        Object rawVersion = schema.get('tools_version'), rawFeatures = schema.get('features')
        List<Integer> version = parseVersion(rawVersion)
        Set<String> features = rawFeatures instanceof List ? (rawFeatures as List).collect { it.toString() }.toSet() : null
        if (features == null && needed) return verdict(REFUSE, "this app's labconstrictor-tools is too old (it lists no features, this host needs " + needed.join(', ') + '): update it')
        List<String> missing = needed.findAll { String f -> features != null && !(f in features) }
        if (missing) return verdict(REFUSE, "this app's labconstrictor-tools lacks " + missing.join(', ') + ': update it')
        if (minimum) {
            if (rawVersion == null || rawVersion == '')
                return verdict(REFUSE, "this app's labconstrictor-tools is too old (no version recorded, this host needs " + minimum + ' or newer): update it')
            List<Integer> wanted = parseVersion(minimum)
            if (version != null && wanted != null && compareVersions(version, wanted) < 0)
                return verdict(REFUSE, "this app's labconstrictor-tools " + rawVersion + ' is older than the ' + minimum + ' this host needs: update it')
        }
        if (rawVersion == null || rawVersion == '') return verdict(WARN, "the version of this app's labconstrictor-tools is unknown (it predates version 0.2)")
        if (version == null) return verdict(WARN, 'the labconstrictor-tools version ' + python(rawVersion) + ' of this app is not understood')
        List<Integer> soft = parseVersion(warnBelow)
        if (soft != null && compareVersions(version, soft) < 0)
            return verdict(WARN, "this app's labconstrictor-tools " + rawVersion + ' is older than the recommended ' + warnBelow + ': update it')
        return verdict(OK, '')
    }

    /** The rules with what this host needs: Tools MINIMUM_TOOLS or newer with the REQUIRED_FEATURES (the plugin's own minimum, not the reference host's). */
    static Map<String, String> checkForHost(Map schema, List<Integer> protocols) {
        return checkSchema(schema, MINIMUM_TOOLS, REQUIRED_FEATURES, null, protocols)
    }
}
