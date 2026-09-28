package dev.loupe.kit.tracking

import dev.loupe.sources.common.CsvRows

/**
 * The text forms the trackers match against (spec 2026-09-28 §7.2). Both transforms keep the length, so an offset
 * found in a form is an offset in the original text too, and evidence can be cut from the original verbatim.
 */
object TrackingText {
    /** A letter or digit of [matchForm] text: ASCII letters and digits, and the Arabic letters U+0621..U+064A. */
    private const val WORD = "a-z0-9ء-ي"
    private const val META = ".^$*+?()[]{}|\\"

    /**
     * Arabic-Indic (U+0660..0669) and Persian (U+06F0..06F9) digits to ASCII `0-9`; same length.
     *
     * The one digit fold of the trackers. Station's `parity` branch adds `dev.loupe.engine.PortableText.foldDigits`
     * with the same contract; when it is on `main`, this body becomes `PortableText.foldDigits(s)` and nothing else
     * changes.
     */
    fun foldDigits(s: String): String = CsvRows.normalizeDigits(s)

    /**
     * Lower case, digits folded, and the Arabic letters written several ways brought to one: أ إ آ ٱ → ا, ى → ي,
     * ة → ه. Same length as [s].
     */
    fun matchForm(s: String): String {
        val folded = foldDigits(s)
        val out = CharArray(folded.length)
        for (i in folded.indices) {
            out[i] = when (val c = folded[i]) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                else -> c.lowercaseChar()
            }
        }
        return out.concatToString()
    }

    /** [p] as a regex over [matchForm] text: normalised, metacharacters escaped, any run of spaces matching one or more. */
    fun phrase(p: String): String =
        matchForm(p).trim().split(Regex("\\s+")).joinToString("\\s+") { word ->
            buildString { for (c in word) { if (c in META) append('\\'); append(c) } }
        }

    /**
     * Whole-word alternatives over [matchForm] text: no letter or digit right before or after. An entry is a
     * literal phrase (see [phrase]) unless it starts with `re:`; then the rest is a regex already in the match form
     * (lower case; never an upper-case escape such as `\S`). A literal with Arabic letters may carry the attached
     * prefixes و ف, ب ل ك and ال in front and a pronoun or plural suffix after (بمبلغ, وتم الدفع, اشتراكك).
     */
    fun words(entries: List<String>): Regex {
        val alts = entries.map { e ->
            if (e.startsWith("re:")) {
                e.removePrefix("re:")
            } else {
                val body = phrase(e)
                if (body.any { it in 'ء'..'ي' }) "(?:و|ف)?(?:ب|ل|ك)?(?:ال)?$body(?:ك|كم|ه|ها|هم|ي|ات)?" else body
            }
        }
        return Regex("(?<![$WORD])(?:${alts.joinToString("|")})(?![$WORD])")
    }
}
