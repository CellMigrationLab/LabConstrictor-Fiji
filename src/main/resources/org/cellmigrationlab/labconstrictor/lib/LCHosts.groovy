// Helper of LabConstrictor_Tools.groovy (loaded by it, see loadLib there): may THIS host (Fiji) list an app? The per-host discovery rule of docs/PROTOCOL.md
// ("App hosts", feature app_hosts_v1), written here in Groovy exactly as labconstrictor_tools.app_hosts.listed_for writes it in Python (same sentences, word for word).
// The test feeds this class the vectors of conformance.json (`app_hosts`) and a case compares its answers with the reference function. Every method is typed;
// nothing here reads the script's globals. The command line and the notebooks are never filtered by this rule: it is about discovery in a host only.

/** The hosts rule: [listed: boolean, sentence: String] for a registry entry and the asking host (see the comment at the top of this file). */
class LCHosts {
    static final String HOST = 'fiji'
    static final List<String> KNOWN = ['notebook', 'napari', 'fiji', 'qupath']
    static final String NOT_LISTED = "'{app}' is not listed in {host}: the app declares hosts: {hosts}"
    static final String ABSENT = "'{app}' declares no hosts, so it is listed in every host; register it with --app-manifest (or --hosts) to choose"
    static final String INVALID = "'{app}' is not listed in {host}: its hosts declaration is not valid ({problem}); register the app again with a valid one"
    static final String NOT_A_LIST = 'hosts must be a list of host names'
    static final String EMPTY = 'hosts is empty; write at least notebook'
    static final String NOT_TEXT = 'hosts must contain only text'
    static final String UNKNOWN = "unknown host name {name} (known: " + KNOWN.join(', ') + ')'
    static final String REPEATED = 'host name {name} appears twice'

    /** How Python's `%r` writes a text: single quotes, or double quotes when the text holds a single quote and no double quote; backslash and line breaks escaped. */
    static String python(String text) {
        String quote = text.contains("'") && !text.contains('"') ? '"' : "'"
        String body = text.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
        if (quote == "'") body = body.replace("'", "\\'")
        return quote + body + quote
    }

    /** The declared list when it is valid, else an IllegalArgumentException whose message is the problem (one of the texts above). */
    static List<String> parseDeclared(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException(NOT_A_LIST)
        List declared = value as List
        if (declared.isEmpty()) throw new IllegalArgumentException(EMPTY)
        if (!declared.every { Object name -> name instanceof String }) throw new IllegalArgumentException(NOT_TEXT)
        declared.each { Object name ->
            if (!(name in KNOWN)) throw new IllegalArgumentException(UNKNOWN.replace('{name}', python(name as String)))
        }
        declared.each { Object name ->
            if (declared.count(name) > 1) throw new IllegalArgumentException(REPEATED.replace('{name}', python(name as String)))
        }
        return new ArrayList<String>(declared as List<String>)
    }

    /** May `host` list this registry entry? [listed, sentence for the person and the log ('' when there is nothing to say)]. An unknown asking host is the host's own
     *  programming error (IllegalArgumentException), never a verdict. */
    static Map listedFor(Map entry, String host) {
        if (!(host in KNOWN)) throw new IllegalArgumentException('unknown host ' + python(host) + ' (a programming error in the host; known: ' + KNOWN.join(', ') + ')')
        String app = entry.get('name') == null ? '' : entry.get('name').toString()
        if (!entry.containsKey('hosts')) return [listed: true, sentence: ABSENT.replace('{app}', app)]
        List<String> declared
        try {
            declared = parseDeclared(entry.get('hosts'))
        } catch (IllegalArgumentException problem) {
            return [listed: false, sentence: INVALID.replace('{app}', app).replace('{host}', host).replace('{problem}', problem.message)]
        }
        if (host in declared) return [listed: true, sentence: '']
        return [listed: false, sentence: NOT_LISTED.replace('{app}', app).replace('{host}', host).replace('{hosts}', declared.join(', '))]
    }

    /** The rule for this host (Fiji). */
    static Map listedInFiji(Map entry) { return listedFor(entry, HOST) }
}
