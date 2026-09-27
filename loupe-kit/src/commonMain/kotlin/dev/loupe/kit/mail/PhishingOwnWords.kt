package dev.loupe.kit.mail

import dev.loupe.engine.PortableText

/*
 * Formula v1.3 (self-vouching): the sender's own words of an email, and the first of their sentences
 * that vouches for the message.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`, commit
 * 0ff886d65958077adc4269fc8d98c58795612e83): `VOUCHING_RE`, `_SENTENCE_SPLIT`, `MAX_PHRASE_CHARS` and
 * `self_vouching()` of `laya_studio/mail/phishing.py`; `_HEADER_RE`, `_QUOTE_CUT_RE`, `_FOOTER_RE`,
 * `_URL_RE`, `_split_email()` and `own_text()` of `laya_studio/baselines.py`. The keyword rule is the
 * lab's (`tools/lab/authority_injection.py` `KEYWORD_RE`). Patterns and order are verbatim; only the
 * language changed. Python's Unicode semantics are spelled out so the JVM and Kotlin/Native agree
 * with Station: `\b` is a boundary against Python's `\w` (letters, numbers, `_`), `\s` / `strip()` /
 * `splitlines()` use Python's whitespace and line breaks, and the 80-character cut counts code points.
 */
object PhishingOwnWords {
    /** A vouching phrase is cut to this many characters (79 + "…"), as Station's `MAX_PHRASE_CHARS`. */
    const val MAX_PHRASE_CHARS = 80

    /** Python's `str.isspace()` characters, as a regex class body. */
    private const val WS = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
    private const val S = "[$WS]"
    private const val NOT_S = "[^$WS]"
    /**
     * Station's `VOUCHING_RE` (English, Modern Standard Arabic, Egyptian Arabic), case-insensitive,
     * spelled portably (fix loop 7): the English words, each between Python's Unicode `\b` (a `\w` is
     * a letter, a number or `_`, from the pinned Unicode data), found on [PortableText.fold]ed text;
     * the marks and the Arabic phrases anywhere, as in Station's pattern (no boundary there).
     */
    private val VOUCHING_WORDS = listOf(
        "verified", "approved", "authorised", "authorized", "whitelisted", "trusted", "genuine", "legitimate", "confirmed safe",
        "safe to open", "signed off", "not phishing", "security check passed",
    )
    private val VOUCHING_ANYWHERE = listOf("✓", "✔", "تم التحقق", "موثق", "موثّق", "موثوق", "معتمد", "اعتماد", "وافق", "متأكد منها", "آمن", "اطمن", "متراجع")

    private fun pyWord(cp: Int): Boolean = cp == '_'.code || PortableText.isLetter(cp) || PortableText.isNumber(cp)

    /** Whether [sentence] vouches for itself (Station's `VOUCHING_RE.search`). */
    fun vouches(sentence: String): Boolean {
        if (VOUCHING_ANYWHERE.any { it in sentence }) return true
        val f = PortableText.fold(sentence)
        for (w in VOUCHING_WORDS) {
            var at = f.indexOf(w)
            while (at >= 0) {
                val end = at + w.length
                val before = if (at == 0) -1 else PortableText.codePointAt(f, if (at >= 2 && f[at - 1].isLowSurrogate() && f[at - 2].isHighSurrogate()) at - 2 else at - 1)
                val after = if (end >= f.length) -1 else PortableText.codePointAt(f, end)
                if ((before < 0 || !pyWord(before)) && (after < 0 || !pyWord(after))) return true
                at = f.indexOf(w, at + 1)
            }
        }
        return false
    }

    /** Station's `_SENTENCE_SPLIT`: after `.`, `!`, `?`, `؟` followed by white space, and on new lines. */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?؟])$S+|\\n+")

    // The patterns below are case-insensitive in Station: they are written in lower case and run on
    // [PortableText.fold]ed text (same length as the text, so a position is the original's), fix loop 7.
    private val HEADER_RE = Regex("^(from|subject|to|cc|date|reply-to)$S*:")
    private val QUOTE_CUT_RE = Regex(
        "^$S*(on$S.+${S}wrote:$S*$|le$S.+${S}a${S}écrit$S*:|el$S.+${S}escribió:|-{2,}$S*(original message|forwarded message)" +
            "|from:$S.+$S(sent|date):|sent from my$S|--$S*$)",
    )
    private val FOOTER_RE = Regex(
        "unsubscribe|view (it |this email )?in (your |a )?browser|manage (your )?(email )?preferences|email preferences" +
            "|you('| a)re receiving this|you received this (email|message) because|this (email|message) was sent to" +
            "|se désabonner|désinscri|darse de baja|cancelar (la )?suscripci[oó]n|إلغاء الاشتراك",
    )
    private val URL_RE = Regex("https?://$NOT_S+|www\\.$NOT_S+")
    /** Python's `str.splitlines()` boundaries. */
    private val LINE_BREAK = Regex("\\r\\n|[\\n\\r\\x0B\\f\\x1C\\x1D\\x1E\\x85\\u2028\\u2029]")
    /** Bidi embedding, override and isolate controls: never kept in a phrase shown to the user. */
    private val BIDI_CONTROLS = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")

    private fun isPySpace(c: Char): Boolean =
        c in "\t\n\u000B\u000C\r \u0085      　" || c in '\u001C'..'\u001F' || c in ' '..' '

    /** Python's `str.strip()`. */
    internal fun pyStrip(s: String): String = s.trim(::isPySpace)

    /** Python's `str.splitlines()` (no trailing empty line). */
    internal fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = s.split(LINE_BREAK)
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    /** Station's `_split_email`: (sender, subject, body) of a "From / Subject / body" layout; plain text is all body. */
    internal fun splitEmail(text: String): Triple<String, String, String> {
        val lines = splitLines(text)
        var sender = ""
        var subject = ""
        var i = 0
        while (i < lines.size && i < 8 && HEADER_RE.find(PortableText.fold(lines[i])) != null) {
            val key = pyStrip(lines[i].substringBefore(':')).lowercase()
            val value = pyStrip(lines[i].substringAfter(':'))
            if (key == "from" && sender.isEmpty()) sender = value else if (key == "subject" && subject.isEmpty()) subject = value
            i += 1
        }
        return Triple(sender, subject, lines.drop(i).joinToString("\n"))
    }

    /** Station's `own_text`: the subject and body without quoted replies, signatures, footers or links. */
    fun ownText(text: String): String {
        val (_, subject, body) = splitEmail(text)
        val kept = mutableListOf<String>()
        for (line in splitLines(body)) {
            if (QUOTE_CUT_RE.find(PortableText.fold(line)) != null) break
            if (line.trimStart(::isPySpace).startsWith(">")) continue
            kept += line
        }
        var own = (listOf(subject) + kept).joinToString("\n")
        FOOTER_RE.find(PortableText.fold(own))?.let { own = own.substring(0, it.range.first) } // everything from the first footer phrase on
        return PortableText.replaceFolded(own, URL_RE, " ")
    }

    /**
     * Station's `self_vouching`: the first sentence of the sender's own words that vouches for the
     * message, cut to [MAX_PHRASE_CHARS] code points (79 + "…"), or null. Bidi controls inside the
     * sentence are removed (the phrase is displayed; Station shows it in its own direction).
     */
    fun selfVouching(text: String): String? {
        if (text.isEmpty()) return null
        for (raw in ownText(text).split(SENTENCE_SPLIT)) {
            val sentence = pyStrip(raw)
            if (sentence.isNotEmpty() && vouches(sentence)) {
                val cut = if (codePoints(sentence) <= MAX_PHRASE_CHARS) sentence
                else takeCodePoints(sentence, MAX_PHRASE_CHARS - 1).trimEnd(::isPySpace) + "…"
                return BIDI_CONTROLS.replace(cut, "")
            }
        }
        return null
    }

    private fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n += 1
        }
        return n
    }

    private fun takeCodePoints(s: String, count: Int): String {
        var i = 0
        var n = 0
        while (i < s.length && n < count) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n += 1
        }
        return s.substring(0, i)
    }
}
