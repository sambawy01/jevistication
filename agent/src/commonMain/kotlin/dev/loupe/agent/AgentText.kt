package dev.loupe.agent

/**
 * Text handling for everything that crosses to a provider, ported from the iPhone's `AssistText`
 * (Station's `mail/provider.py` `safe_text` and `mail/drafts.py` `clean_body`).
 *
 * Three jobs, all of them about minimisation and about not letting an item's text act:
 *
 * - [safe] and [cleanBody] strip control, format and bidi-override characters. A right-to-left
 *   override in a subject line is how a `.exe` is made to read as a `.pdf`; it has no business in
 *   a prompt or in a draft.
 * - [ownText] cuts the quoted thread, so replying to one message does not send the six before it.
 *   This is stricter than Station, which reads one message already.
 * - [neutral] makes it impossible for the item's text to close or open one of our own tags, which
 *   is the cheapest prompt injection there is.
 */
object AgentText {
    /** Printable single-line text: control and bidi/format characters become spaces, runs collapsed. */
    fun safe(s: String, limit: Int? = null): String {
        val mapped = buildString(s.length) {
            for (ch in s) append(if (isStripped(ch)) ' ' else ch)
        }
        var out = mapped.split(' ', '\t', '\n', '\r', '\u000B', '\u000C')
            .filter { it.isNotEmpty() }.joinToString(" ")
        if (limit != null && out.length > limit) out = out.take(limit - 1).trimEnd() + "…"
        return out
    }

    /** Plain multi-line text: normalised newlines, no control or bidi-override characters. */
    fun cleanBody(s: String): String {
        val t = s.replace("\r\n", "\n").replace('\r', '\n')
        return buildString(t.length) {
            for (ch in t) {
                if (ch == '\n' || ch == '\t') {
                    append(ch)
                    continue
                }
                if (ch.code < 0x20 || ch.code == 0x7f) continue
                if (ch.code in 0x202A..0x202E || ch.code in 0x2066..0x2069) continue
                append(ch)
            }
        }.trim()
    }

    /**
     * Only the target message's own text: the quoted thread is cut, so earlier mail is never sent.
     */
    fun ownText(body: String): String {
        val out = mutableListOf<String>()
        for (line in body.replace("\r\n", "\n").split('\n')) {
            val t = line.trim()
            if (t.startsWith(">")) continue
            val lower = t.lowercase()
            if (lower.startsWith("-----original message-----")) break
            if (lower.startsWith("---------- forwarded message")) break
            if (lower.startsWith("begin forwarded message")) break
            if (lower.startsWith("on ") && lower.endsWith("wrote:")) break
            if (lower.startsWith("from:") && out.isNotEmpty() && out.last().isBlank() &&
                out.any { it.isNotBlank() }
            ) {
                break
            }
            out += line
        }
        return out.joinToString("\n").trim()
    }

    private val TAGS = Regex("""(?i)<\s*/?\s*(item|email|text|facts|actions|never_promise)\s*>""")

    /** The item's text can never close or open one of our tags. */
    fun neutral(s: String): String = TAGS.replace(s) { "[" + it.groupValues[1] + "]" }

    private fun isStripped(ch: Char): Boolean {
        val c = ch.code
        if (c < 0x20 || c == 0x7f) return true
        // Bidi overrides and isolates, the zero-width marks, and the line/paragraph separators.
        if (c in 0x200B..0x200F) return true
        if (c in 0x202A..0x202E) return true
        if (c in 0x2060..0x2064) return true
        if (c in 0x2066..0x2069) return true
        if (c == 0x2028 || c == 0x2029 || c == 0xFEFF) return true
        return false
    }
}
