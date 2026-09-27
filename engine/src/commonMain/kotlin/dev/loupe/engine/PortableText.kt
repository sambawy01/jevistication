package dev.loupe.engine

/**
 * Text primitives that give the same answer on the JVM, Android, iOS and Loupe Station.
 *
 * The regex engines disagree about `\d \s \w \b \p{..}` and case-insensitive matching (the JDK and
 * Kotlin/Native are ASCII, Android's ICU is Unicode-aware), and the platforms' Unicode data differs
 * in version (docs/ANDROID-PLAN.md, "Known parity gaps", B1 and B2). Shared code therefore never
 * leans on an engine's classes or case folding: it spells its classes out ([Rx]), folds digits and
 * case itself ([matchForm]), and reads character properties and normal forms from Loupe's own pinned
 * Unicode data ([UNICODE_VERSION]). `tools/android-regex-check/lint.py` keeps it that way, and
 * `tools/parity/corpus.json` pins the answers on every platform.
 *
 * Every function here that returns a String of the same length as its input says so: those keep
 * match indexes valid in the original text, so a match found on the folded form can be cut out of
 * the original.
 */
object PortableText {

    /** The Unicode version of the pinned data (IDNA, normal forms, categories, case folding). */
    val UNICODE_VERSION: String get() = UnicodeDataTable.UNICODE_VERSION

    // ---------------------------------------------------------------------------------- digits
    /**
     * The value of an ASCII (0-9), Arabic-Indic (U+0660-0669) or Extended Arabic-Indic / Persian
     * (U+06F0-06F9) digit, else -1. The owner's decision: all three are read as numbers everywhere.
     */
    fun digitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in '٠'..'٩' -> c - '٠'
        in '۰'..'۹' -> c - '۰'
        else -> -1
    }

    /** True for the digits [digitValue] reads. Never `Char.isDigit()`, which takes every script's. */
    fun isDigit(c: Char): Boolean = digitValue(c) >= 0

    /** Arabic-Indic and Persian digits to ASCII `0-9`; same length. */
    fun foldDigits(s: String): String {
        var i = 0
        while (i < s.length && (s[i] < '٠' || digitValue(s[i]) < 0)) i++
        if (i == s.length) return s
        val out = StringBuilder(s.length).append(s, 0, i)
        while (i < s.length) {
            val c = s[i++]
            val v = if (c >= '٠') digitValue(c) else -1
            out.append(if (v >= 0) '0' + v else c)
        }
        return out.toString()
    }

    /**
     * [s] as a Long when it is an optional sign and digits of [isDigit] only (the three scripts,
     * even mixed), else null. Unlike `String.toLongOrNull`, no other script's digits are accepted.
     */
    fun toLongOrNull(s: String): Long? {
        val f = foldDigits(s)
        val body = if (f.startsWith('-') || f.startsWith('+')) f.substring(1) else f
        if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
        return f.toLongOrNull()
    }

    /** As [toLongOrNull], for an Int. */
    fun toIntOrNull(s: String): Int? = toLongOrNull(s)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    // ---------------------------------------------------------------------------------- spaces
    /**
     * The space characters every rule treats as space: ASCII whitespace (tab, LF, VT, FF, CR, space)
     * plus no-break space U+00A0, figure space U+2007, thin space U+2009 and narrow no-break space
     * U+202F (French and Arabic typesetting put the last three between a number and its currency
     * or thousands). The same set on every platform; see [Rx.SP].
     */
    const val SPACE_CHARS: String = " \t\n\u000B\u000C\r    "

    fun isSpace(c: Char): Boolean = SPACE_CHARS.indexOf(c) >= 0

    /** The non-empty runs of [s] between [SPACE_CHARS]: `split(Regex("\\s+")).filter { it.isNotEmpty() }`, portably. */
    fun splitSpaces(s: String): List<String> {
        val out = ArrayList<String>()
        var start = -1
        for (i in s.indices) {
            if (isSpace(s[i])) {
                if (start >= 0) out += s.substring(start, i)
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) out += s.substring(start)
        return out
    }

    /** [s] with every run of [SPACE_CHARS] made one ASCII space, and none at either end. */
    fun collapseSpaces(s: String): String = splitSpaces(s).joinToString(" ")

    // ------------------------------------------------------------------------------------ case
    /**
     * The simple case fold `lower(upper(c))` of every code point, from the pinned data: what the
     * JDK's case-insensitive matching compares, but the same everywhere. `İ` (U+0130) and `ı`
     * (U+0131) fold to `i`; the Kelvin sign to `k`; `ß` stays `ß` (it never matches `ss`); Greek
     * final `ς` folds to `σ`. Same length as [s] (the table keeps every fold within its plane).
     */
    fun fold(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.code >= 0x80 || c in 'A'..'Z') break
            i++
        }
        if (i == s.length) return s
        val out = StringBuilder(s.length).append(s, 0, i)
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                appendCodePoint(out, UnicodeData.fold(0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)))
                i += 2
            } else {
                out.append(UnicodeData.fold(c.code).toChar())
                i++
            }
        }
        return out.toString()
    }

    /**
     * Lower case from the pinned data: the simple lowercase of every code point, and `İ` (U+0130) as
     * `i` + U+0307 (its one unconditional special casing), as Kotlin's `lowercase()` does with the
     * root locale, but the same on every platform. Unlike `lowercase()` there is no final-sigma rule:
     * `Σ` is always `σ`. For host names and other identifiers; keyword matching uses [matchForm].
     */
    fun lowercase(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.code >= 0x80 || c in 'A'..'Z') break
            i++
        }
        if (i == s.length) return s
        val out = StringBuilder(s.length + 2).append(s, 0, i)
        while (i < s.length) {
            val cp = codePointAt(s, i)
            if (cp == 0x130) out.append("i\u0307") else appendCodePoint(out, UnicodeData.lower(cp))
            i += if (cp >= 0x10000) 2 else 1
        }
        return out.toString()
    }

    /**
     * The form every rule matches against: [fold]ed case and [foldDigits]ed digits. Same length as
     * [s]. Patterns matched against it are written in lower case with ASCII digits.
     */
    fun matchForm(s: String): String = fold(foldDigits(s))

    // ----------------------------------------------------------------------- character classes
    /**
     * [text] with every match of [regex] replaced by [replacement] (taken literally), where [regex]
     * is written for [fold]ed text and runs on `fold(text)`: case-insensitive matching of a
     * lowercase pattern without an engine's case folding. Text outside the matches is kept as written.
     */
    fun replaceFolded(text: String, regex: Regex, replacement: String): String {
        val folded = fold(text)
        val out = StringBuilder(text.length)
        var last = 0
        for (m in regex.findAll(folded)) {
            out.append(text, last, m.range.first).append(replacement)
            last = m.range.last + 1
        }
        return out.append(text, last, text.length).toString()
    }

    /** A letter (general category L*) in the pinned data. */
    fun isLetter(codePoint: Int): Boolean = UnicodeData.category(codePoint) == UnicodeData.LETTER

    /** A number: decimal digit, letter number or other number (Nd, Nl, No). */
    fun isNumber(codePoint: Int): Boolean = UnicodeData.category(codePoint).let {
        it == UnicodeData.DECIMAL_DIGIT || it == UnicodeData.LETTER_NUMBER || it == UnicodeData.OTHER_NUMBER
    }

    /** `\p{L}\p{Nd}\p{Nl}\p{No}`, portably: the "word character" of the keyword rules. */
    fun isLetterOrNumber(codePoint: Int): Boolean = isLetter(codePoint) || isNumber(codePoint)

    /** A decimal digit of any script (Nd). */
    fun isDecimalDigit(codePoint: Int): Boolean = UnicodeData.category(codePoint) == UnicodeData.DECIMAL_DIGIT

    /**
     * The maximal runs of letters and numbers ([isLetterOrNumber]) in [s], in order: what
     * `Regex("[\\p{L}\\p{Nd}\\p{Nl}\\p{No}]+").findAll(s)` finds, the same on every platform.
     */
    fun letterNumberRuns(s: String): List<String> {
        val out = ArrayList<String>()
        var start = -1
        var i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i)
            val n = if (cp >= 0x10000) 2 else 1
            if (isLetterOrNumber(cp)) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out += s.substring(start, i)
                start = -1
            }
            i += n
        }
        if (start >= 0) out += s.substring(start)
        return out
    }

    /** A letter or a decimal digit (L, Nd). */
    fun isLetterOrDecimalDigit(codePoint: Int): Boolean =
        UnicodeData.category(codePoint).let { it == UnicodeData.LETTER || it == UnicodeData.DECIMAL_DIGIT }

    /** A mark (Mn, Mc, Me). */
    fun isMark(codePoint: Int): Boolean = UnicodeData.category(codePoint).let {
        it == UnicodeData.NONSPACING_MARK || it == UnicodeData.SPACING_MARK || it == UnicodeData.ENCLOSING_MARK
    }

    /** A nonspacing or enclosing mark (Mn, Me): what "strip the accents" drops after NFKD. */
    fun isNonspacingOrEnclosingMark(codePoint: Int): Boolean = UnicodeData.category(codePoint).let {
        it == UnicodeData.NONSPACING_MARK || it == UnicodeData.ENCLOSING_MARK
    }

    /** The code point at [index] of [s] (a surrogate pair read whole), or -1 out of range. */
    fun codePointAt(s: String, index: Int): Int {
        if (index < 0 || index >= s.length) return -1
        val c = s[index]
        if (c.isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[index + 1].code - 0xDC00)
        }
        return c.code
    }

    /** The code point that ends just before [index] of [s] (a surrogate pair read whole), or -1. */
    fun codePointBefore(s: String, index: Int): Int {
        if (index <= 0 || index > s.length) return -1
        val c = s[index - 1]
        if (c.isLowSurrogate() && index >= 2 && s[index - 2].isHighSurrogate()) {
            return 0x10000 + ((s[index - 2].code - 0xD800) shl 10) + (c.code - 0xDC00)
        }
        return c.code
    }

    /**
     * [s] with every non-ASCII UTF-16 unit replaced by a marker of its class, ASCII kept: a letter
     * becomes [SHADOW_LETTER], a decimal digit [SHADOW_DIGIT], another number [SHADOW_NUMBER], a mark
     * [SHADOW_MARK], anything else [SHADOW_OTHER] (both units of a surrogate pair get the marker of
     * the pair's code point). Same length as [s]. A regex written with [Rx.LN] and friends then
     * matches the shadow exactly as `\p{L}\p{Nd}...` would match [s] on an engine with this Unicode
     * data, and `s.substring(match.range)` is the original text.
     */
    fun shadow(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i)
            val n = if (cp >= 0x10000) 2 else 1
            val marker = if (cp < 0x80) {
                cp.toChar()
            } else {
                when (UnicodeData.category(cp)) {
                    UnicodeData.LETTER -> SHADOW_LETTER
                    UnicodeData.DECIMAL_DIGIT -> SHADOW_DIGIT
                    UnicodeData.LETTER_NUMBER, UnicodeData.OTHER_NUMBER -> SHADOW_NUMBER
                    UnicodeData.NONSPACING_MARK, UnicodeData.SPACING_MARK, UnicodeData.ENCLOSING_MARK -> SHADOW_MARK
                    else -> SHADOW_OTHER
                }
            }
            repeat(n) { out.append(marker) }
            i += n
        }
        return out.toString()
    }

    const val SHADOW_LETTER: Char = ''
    const val SHADOW_DIGIT: Char = ''
    const val SHADOW_NUMBER: Char = ''
    const val SHADOW_MARK: Char = ''
    const val SHADOW_OTHER: Char = ''

    // ------------------------------------------------------------------------------- keywords
    /**
     * True when [keyword] occurs in [text] as a whole word: compared in [matchForm], and not glued
     * to a letter or number ([isLetterOrNumber]) on either side. The portable form of
     * `(?<![\p{L}\p{Nd}\p{Nl}\p{No}])keyword(?![\p{L}\p{Nd}\p{Nl}\p{No}])` with IGNORE_CASE. A keyword
     * that starts or ends with a non-word character (`"4b."`) needs no boundary on that side, as
     * with the regex.
     */
    fun containsWord(text: String, keyword: String): Boolean = indexOfWord(text, keyword) >= 0

    /** Where [containsWord] finds [keyword] first, or -1. [folded] may pass `matchForm(text)` in. */
    fun indexOfWord(text: String, keyword: String, folded: String = matchForm(text)): Int {
        if (keyword.isEmpty()) return -1
        val k = matchForm(keyword)
        var from = 0
        while (true) {
            val at = folded.indexOf(k, from)
            if (at < 0) return -1
            val end = at + k.length
            val before = codePointBefore(folded, at)
            val after = codePointAt(folded, end)
            if ((before < 0 || !isLetterOrNumber(before)) && (after < 0 || !isLetterOrNumber(after))) return at
            from = at + 1
        }
    }

    // ------------------------------------------------------------------------------ normal forms
    fun nfc(s: String): String = normalize(s) { UnicodeData.compose(UnicodeData.decompose(it, compat = false)) }

    fun nfd(s: String): String = normalize(s) { UnicodeData.decompose(it, compat = false) }

    fun nfkc(s: String): String = normalize(s) { UnicodeData.compose(UnicodeData.decompose(it, compat = true)) }

    fun nfkd(s: String): String = normalize(s) { UnicodeData.decompose(it, compat = true) }

    /**
     * toNFKC_Casefold: case folded (full folding, so `ß` is `ss`), NFKC, default ignorables (soft
     * hyphen, zero-width joiners, variation selectors) removed. The IDNA mapping step.
     */
    fun nfkcCasefold(s: String): String {
        if (s.all { it.code < 0x80 }) return s.lowercase()
        return fromCodePoints(UnicodeData.nfkcCasefold(codePoints(s)))
    }

    private inline fun normalize(s: String, form: (IntArray) -> IntArray): String {
        if (s.all { it.code < 0x80 }) return s
        return fromCodePoints(form(codePoints(s)))
    }

    private fun fromCodePoints(cps: IntArray): String {
        val sb = StringBuilder(cps.size)
        for (cp in cps) appendCodePoint(sb, cp)
        return sb.toString()
    }

    // --------------------------------------------------------------------- Unicode 3.2 drift
    /**
     * False when the IDNA mapping of [codePoint] under Unicode 3.2 nameprep (RFC 3491, what
     * `java.net.IDN` and older resolvers use; a character unassigned in 3.2 passes through unchanged)
     * differs from the pinned mapping (B.1, then NFKC_Casefold). Such a character can make one host
     * name read as two different names on old and new software. About 5,600 code points: the 174 of
     * 3.2 whose mapping changed (Georgian and Cherokee case pairs, newly ignorable controls, six CJK
     * corrections) and later ones that map to something else (U+1F130 to `a`, U+1E030 to `а`, new
     * capital letters). New characters that map to themselves (Burmese, emoji, CJK) are stable.
     */
    fun stableSinceUnicode32(codePoint: Int): Boolean = UnicodeData.stableSince32(codePoint)

    /**
     * The code points of [text] (a label or a whole host) that stand in for other characters: any
     * whose NFKC is not itself (compatibility variants: full-width `ｐ`, mathematical `𝗽`, enclosed
     * `🄰`, ligatures, superscripts; and canonical singletons: the Kelvin sign `K`, the Ångström sign,
     * the Ohm sign, CJK compatibility ideographs, Greek oxia), the invisible default ignorables IDNA
     * removes (soft hyphen, variation selectors, ZWSP), and a zero-width joiner or non-joiner that
     * CONTEXTJ does not allow where it stands (Persian and Indic names keep theirs). No real name is
     * written with them: IDNA maps them away, so the written name hides the one it reaches. `é`,
     * Hangul and every other character that NFKC keeps are not stand-ins. In order, without repeats.
     */
    fun disguisedCodePoints(text: String): List<Int> {
        val out = LinkedHashSet<Int>()
        for (label in text.split('.', '\u3002', '\uFF0E', '\uFF61')) {
            val cps = codePoints(label)
            for (i in cps.indices) {
                val cp = cps[i]
                if (cp < 0x80) continue
                val stand = if (cp == 0x200C || cp == 0x200D) {
                    !Uts46.contextJ(cps, i)
                } else {
                    val k = UnicodeData.compose(UnicodeData.decompose(intArrayOf(cp), compat = true))
                    !(k.size == 1 && k[0] == cp) || UnicodeData.nfkcCasefoldChar(cp).isEmpty()
                }
                if (stand) out += cp
            }
        }
        return out.toList()
    }

    /** The code points of [label] that are not [stableSinceUnicode32], in order, without repeats. */
    fun unicode32Drift(label: String): List<Int> =
        codePoints(label).filter { it >= 0x80 && !stableSinceUnicode32(it) }.distinct()
}

/**
 * Regex building blocks spelled out, so a pattern means the same on every engine. Shared main
 * sources use these instead of `\d \s \w \b \D \S \W \B \p{..}` and case-insensitive flags
 * (`tools/android-regex-check/lint.py` fails the build otherwise).
 */
object Rx {
    /** Inside a class: the characters of [PortableText.SPACE_CHARS]. */
    const val SPACE: String = PortableText.SPACE_CHARS

    /** One space character: `\s` with NBSP, U+2007, U+2009 and U+202F added, the same everywhere. */
    const val SP: String = "[$SPACE]"

    /** One non-space character. */
    const val NSP: String = "[^$SPACE]"

    /**
     * Inside a class: ASCII whitespace only (the JDK's `\s`), for machine syntax such as mail headers
     * and HTML tags, where a no-break space is not a separator. Human text uses [SPACE].
     */
    const val ASCII_SPACE: String = " \t\n\u000B\u000C\r"

    /** One ASCII whitespace character. */
    const val ASCII_SP: String = "[$ASCII_SPACE]"

    /** One character that is not ASCII whitespace. */
    const val ASCII_NSP: String = "[^$ASCII_SPACE]"

    /** Inside a class: the digits [PortableText.isDigit] reads (ASCII, Arabic-Indic, Persian). */
    const val DIGITS: String = "0-9٠-٩۰-۹"

    /** One digit of any of the three scripts, on text that was not digit-folded. */
    const val DIGIT: String = "[$DIGITS]"

    /**
     * One character that is not a line terminator: `.` without DOTALL, spelled out, because the
     * engines disagree on which characters end a line (ICU counts VT and FF, the JDK does not).
     */
    const val IN_LINE: String = "[^\n\u000B\u000C\r\u0085\u2028\u2029]"

    /** Inside a class: the ASCII word characters. */
    const val WORDS: String = "A-Za-z0-9_"

    /** One ASCII word character (the JDK's `\w`). */
    const val W: String = "[$WORDS]"

    /**
     * Not preceded by an ASCII word character: `\b` before a word. A lookbehind: Kotlin/Native
     * evaluates one in O(position), so never lead a pattern with it (every start position pays);
     * use [BoundedRegex] for a word that must start at a boundary.
     */
    const val WB_START: String = "(?<![$WORDS])"

    /** Not followed by an ASCII word character: `\b` after a word. */
    const val WB_END: String = "(?![$WORDS])"

    /**
     * At the start of a match only: the start of the text or one non-word character, consumed.
     * For a pattern answered with `containsMatchIn` only, `(?:^|[^A-Za-z0-9_])word` is `\bword`
     * without a lookbehind (see [WB_START]); the match then includes that one character.
     */
    const val WORD_START_CONSUMED: String = "(?:^|[^$WORDS])"

    /** `\b` where either side may be the word: the JDK's ASCII `\b` (lookarounds; see [WB_START]). */
    const val WB: String = "(?:(?<=[$WORDS])(?![$WORDS])|(?<![$WORDS])(?=[$WORDS]))"

    /** `\B`: not a word boundary. */
    const val NWB: String = "(?:(?<=[$WORDS])(?=[$WORDS])|(?<![$WORDS])(?![$WORDS]))"

    /**
     * Inside a class: the Arabic letters (hamza to yeh, the extended letters, Farsi and Urdu ones),
     * without the diacritics, tatweel or Arabic-Indic digits.
     */
    const val ARABIC_LETTERS: String = "ؠ-ؿف-يٮٯٱ-ۓەۮۯۺ-ۼۿ"

    /** Inside a class, on [PortableText.shadow]ed text: `\p{L}\p{Nd}\p{Nl}\p{No}`. */
    const val LN: String = "A-Za-z0-9-"

    /** Inside a class, on shadowed text: `\p{L}\p{Nd}\p{M}` (letters, decimal digits, marks). */
    const val LDM: String = "A-Za-z0-9"
}

/**
 * `(?<![A-Za-z0-9_])` + [pattern], with the lookbehind checked in code: a match must not start right
 * after an ASCII word character (the JDK's `\b` before a word). Kotlin/Native evaluates a lookbehind in
 * O(position), so a leading one made a search quadratic (a 24,000-character text took 45 s on the iOS
 * simulator); this finds the leftmost match of [pattern] from a position and, when the character
 * before it is a word character, searches again one character later: the same matches, in the same
 * order, as the lookbehind would give. With [bounded] false it is the plain regex.
 */
class BoundedRegex(
    pattern: String,
    private val bounded: Boolean = true,
    /** The characters a match may not follow; ASCII word characters unless given. */
    private val notAfter: (Char) -> Boolean = { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' },
) {
    val regex: Regex = Regex(pattern)

    private fun allowed(input: CharSequence, at: Int): Boolean {
        if (!bounded || at == 0) return true
        return !notAfter(input[at - 1])
    }

    fun find(input: CharSequence, startIndex: Int = 0): MatchResult? {
        var from = startIndex
        while (from <= input.length) {
            val m = regex.find(input, from) ?: return null
            if (allowed(input, m.range.first)) return m
            from = m.range.first + 1
        }
        return null
    }

    fun findAll(input: CharSequence, startIndex: Int = 0): Sequence<MatchResult> = sequence {
        var from = startIndex
        while (from <= input.length) {
            val m = find(input, from) ?: break
            yield(m)
            from = if (m.value.isEmpty()) m.range.first + 1 else m.range.last + 1
        }
    }

    fun containsMatchIn(input: CharSequence): Boolean = find(input) != null

    fun replace(input: CharSequence, transform: (MatchResult) -> CharSequence): String {
        val out = StringBuilder(input.length)
        var last = 0
        for (m in findAll(input)) {
            out.append(input, last, m.range.first).append(transform(m))
            last = m.range.last + 1
        }
        return out.append(input, last, input.length).toString()
    }
}

/**
 * Makes a regex that came from outside the code (a user's baseline pattern in judgments.json) mean
 * the same on every engine, for `containsMatchIn` against [PortableText.matchForm] of the text:
 *
 * - `\d \s \w \b` and their negations become the [Rx] classes;
 * - literal characters, and characters written as escapes (`\uXXXX`, `\xHH`, `\x{H..}`, `\0ooo`),
 *   are put in match form (case folded, Arabic-Indic and Persian digits as ASCII), as the text is;
 *   inside `[...]` each character and range keeps its original and gains its match form, so
 *   `[A-z]`, `[É]` and `[٠-٩]` still accept what they accepted, in the folded text;
 * - case-insensitivity flags are dropped, the fold already did their work;
 * - a `\b` that starts the pattern (nothing before it can match) consumes the character before the
 *   word instead of a lookbehind, which Kotlin/Native evaluates in O(position); not when a group is
 *   repeated (`(\bfoo ){2}`), where the lookbehind form is kept.
 *
 * Constructs whose meaning is engine-defined and cannot be spelled out (`\p{..}`, `\X`, `\R`, `\h`,
 * `\v`, `\N`, a negated class escape or `\b` inside `[...]`) are refused with an
 * [IllegalArgumentException] that names them.
 */
object PortableRegex {

    /** `\b` where nothing precedes it in the pattern: a consumed start, no lookbehind. */
    private const val LEADING_WB: String =
        "(?:(?:^|[^${Rx.WORDS}])(?=[${Rx.WORDS}])|[${Rx.WORDS}](?![${Rx.WORDS}]))"

    /** `\B` where nothing precedes it in the pattern. */
    private const val LEADING_NWB: String =
        "(?:(?:^|[^${Rx.WORDS}])(?![${Rx.WORDS}])|[${Rx.WORDS}](?=[${Rx.WORDS}]))"

    private const val META_OUTSIDE = "\\^$.|?*+()[]{}"
    private const val META_INSIDE = "\\^-[]&"
    private val QUANTIFIED_GROUP = Regex("""\)[*+?{]""")
    private val BACK_REFERENCE = Regex("""\\[1-9]|\\k<""")

    private fun fail(what: String): Nothing =
        throw IllegalArgumentException("$what is not supported in a baseline pattern (it matches differently on each phone)")

    private fun cpString(cp: Int): String = StringBuilder().also { appendCodePoint(it, cp) }.toString()

    /** [cp] as a literal outside a class: escaped when it is a metacharacter. */
    private fun literal(cp: Int): String {
        val s = cpString(cp)
        return if (s.length == 1 && s[0] in META_OUTSIDE) "\\$s" else s
    }

    /** [cp] as a class member: escaped when it would mean something inside `[...]`. */
    private fun member(cp: Int): String {
        val s = cpString(cp)
        return if (s.length == 1 && s[0] in META_INSIDE) "\\$s" else s
    }

    private fun folded(cp: Int): Int = PortableText.codePointAt(PortableText.matchForm(cpString(cp)), 0)

    /**
     * The character an escape at pattern[i] (the backslash) writes, and the escape's length, when
     * it is a character escape (`\uXXXX`, `\xHH`, `\x{H..}`, `\0ooo`, `\t \n \r \f \a \e`, or an
     * escaped non-letter); null for a class, an anchor or a back reference.
     */
    private fun charEscape(pattern: String, i: Int): Pair<Int, Int>? {
        val e = pattern.getOrNull(i + 1) ?: return null
        fun hex(s: String): Int = s.toIntOrNull(16)?.takeIf { it in 0..0x10FFFF } ?: fail("a bad escape \\$e$s")
        return when (e) {
            'u' -> hex(pattern.substring(i + 2, minOf(pattern.length, i + 6)).also { if (it.length < 4) fail("a bad escape \\u") }) to 6
            'x' -> if (pattern.getOrNull(i + 2) == '{') {
                val close = pattern.indexOf('}', i + 2)
                if (close < 0) fail("a bad escape \\x{")
                hex(pattern.substring(i + 3, close)) to close - i + 1
            } else {
                hex(pattern.substring(i + 2, minOf(pattern.length, i + 4)).also { if (it.length < 2) fail("a bad escape \\x") }) to 4
            }
            '0' -> {
                // as java.util.regex: \0n, \0nn, or \0mnn with m <= 3 (\0777 is \077 then 7)
                var j = i + 2
                val max = if (pattern.getOrNull(i + 2)?.let { it in '0'..'3' } == true) i + 5 else i + 4
                while (j < pattern.length && j < max && pattern[j] in '0'..'7') j++
                val digits = pattern.substring(i + 2, j)
                if (digits.isEmpty()) fail("a bad escape \\0")
                digits.toInt(8) to j - i
            }
            't' -> 0x09 to 2
            'n' -> 0x0A to 2
            'r' -> 0x0D to 2
            'f' -> 0x0C to 2
            'a' -> 0x07 to 2
            'e' -> 0x1B to 2
            // an escaped ASCII letter or digit is a class, an anchor or a back reference; any other
            // escaped character (punctuation, `\É`) is that character, as in java.util.regex
            else -> if (e.code < 0x80 && e.isLetterOrDigit()) null else e.code to 2
        }
    }

    fun translate(pattern: String): String {
        val out = StringBuilder(pattern.length + 16)
        // The consumed-start `\b` changes what a match covers, so it is only used when no group is
        // repeated (a repeated group would need the separator each time round).
        val consumedStart = !QUANTIFIED_GROUP.containsMatchIn(pattern) && !BACK_REFERENCE.containsMatchIn(pattern)
        // Whether nothing can have been matched yet on this path: at the pattern's start, or right
        // after `(` or `|` of groups that are themselves leading.
        val groupLeading = ArrayList<Boolean>()
        var leading = true
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '[' -> {
                    leading = false
                    i = translateClass(pattern, i, out)
                }
                c == '\\' && i + 1 < pattern.length -> {
                    val e = pattern[i + 1]
                    val wasLeading = leading
                    leading = false
                    val ch = charEscape(pattern, i)
                    if (ch != null) {
                        out.append(literal(folded(ch.first)))
                        i += ch.second
                        continue
                    }
                    when (e) {
                        'd' -> out.append(Rx.DIGIT)
                        's' -> out.append(Rx.SP)
                        'w' -> out.append(Rx.W)
                        'D' -> out.append("[^${Rx.DIGITS}]")
                        'S' -> out.append(Rx.NSP)
                        'W' -> out.append("[^${Rx.WORDS}]")
                        'b', 'B' -> {
                            // after a consumed start the position is the original's again, so a
                            // second `\b` there takes the lookaround form (leading stays false)
                            out.append(
                                when {
                                    wasLeading && consumedStart && e == 'b' -> LEADING_WB
                                    wasLeading && consumedStart -> LEADING_NWB
                                    e == 'b' -> Rx.WB
                                    else -> Rx.NWB
                                },
                            )
                        }
                        'p', 'P', 'X', 'R', 'h', 'H', 'v', 'V', 'N' -> fail("\\$e")
                        'Q' -> {
                            val end = pattern.indexOf("\\E", i + 2).let { if (it < 0) pattern.length else it }
                            out.append("\\Q").append(PortableText.matchForm(pattern.substring(i + 2, end))).append("\\E")
                            i = if (end >= pattern.length) end else end + 2
                            continue
                        }
                        'k' -> {
                            val close = pattern.indexOf('>', i + 2)
                            if (close < 0) fail("\\k without a name")
                            out.append(pattern, i, close + 1)
                            i = close + 1
                            continue
                        }
                        'c' -> {
                            out.append(pattern, i, minOf(pattern.length, i + 3))
                            i += 3
                            continue
                        }
                        // anchors (\A \z \Z \G) and back references (\1..\9) are kept; \b above
                        else -> out.append(c).append(e)
                    }
                    i += 2
                }
                c == '(' && pattern.startsWith("(?", i) -> {
                    val next = pattern.getOrNull(i + 2)
                    if (next == '<' && pattern.getOrNull(i + 3)?.isLetter() == true) {
                        val close = pattern.indexOf('>', i)
                        if (close < 0) fail("an unterminated group name")
                        out.append(pattern, i, close + 1)
                        i = close + 1
                        groupLeading += leading
                    } else if (next != null && (next.isLetter() || next == '-')) {
                        // inline flags, (?imsx-imsx) or (?imsx-imsx:...): case flags are dropped
                        var j = i + 2
                        while (j < pattern.length && (pattern[j].isLetter() || pattern[j] == '-')) j++
                        if ('x' in pattern.substring(i + 2, j)) fail("the comments flag (?x)")
                        val flags = pattern.substring(i + 2, j).filter { it != 'i' && it != 'u' && it != 'U' }
                        val kept = flags.trimEnd('-').let { if (it == "-") "" else it }
                        when (pattern.getOrNull(j)) {
                            ')' -> if (kept.isNotEmpty()) out.append("(?").append(kept).append(')')
                            ':' -> {
                                out.append("(?").append(kept).append(':')
                                groupLeading += leading
                            }
                            else -> fail("a malformed flag group")
                        }
                        i = j + 1
                    } else {
                        // (?: (?= (?! (?<= (?<!: a lookaround is not a match start of its own
                        val look = next == '=' || next == '!' || next == '<'
                        out.append("(?")
                        i += 2
                        groupLeading += leading && !look
                        if (look) leading = false
                    }
                }
                c == '(' -> {
                    out.append(c)
                    i++
                    groupLeading += leading
                }
                c == '|' -> {
                    out.append(c)
                    i++
                    leading = groupLeading.lastOrNull() ?: true
                }
                c == ')' -> {
                    out.append(c)
                    i++
                    if (groupLeading.isNotEmpty()) groupLeading.removeAt(groupLeading.size - 1)
                    leading = false
                }
                c == '^' || c == '$' -> {
                    // anchors match nothing: a `\b` after `^` is still at the start
                    out.append(c)
                    i++
                }
                else -> {
                    leading = false
                    val cp = PortableText.codePointAt(pattern, i)
                    val n = if (cp >= 0x10000) 2 else 1
                    val f = folded(cp)
                    // a metacharacter stays itself (fold never changes one), unescaped
                    out.append(if (c in META_OUTSIDE) c.toString() else literal(f))
                    i += n
                }
            }
        }
        return out.toString()
    }

    /** [cps] as sorted runs of consecutive code points (first, last). */
    private fun runsOf(cps: Set<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (c in cps.sorted()) {
            if (out.isNotEmpty() && out.last().second == c - 1) out[out.size - 1] = out.last().first to c else out += c to c
        }
        return out
    }

    /** Translates the class starting at pattern[start] (`[`) into [out]; returns the index after `]`. */
    private fun translateClass(pattern: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        out.append('[')
        if (pattern.getOrNull(i) == '^') {
            out.append('^')
            i++
        }
        var first = true
        // One class atom at i: a character (code point, index after it), or null for a class escape.
        fun atom(at: Int): Pair<Int, Int>? {
            val c = pattern[at]
            if (c == '\\' && at + 1 < pattern.length) {
                return charEscape(pattern, at)?.let { (cp, len) -> cp to at + len }
            }
            val cp = PortableText.codePointAt(pattern, at)
            return cp to at + if (cp >= 0x10000) 2 else 1
        }
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == ']' && !first) {
                out.append(']')
                return i + 1
            }
            first = false
            if (c == '[') fail("a class inside [...]")
            if (c == '&' && pattern.getOrNull(i + 1) == '&') fail("&& inside [...]")
            if (c == '\\' && i + 1 < pattern.length && charEscape(pattern, i) == null) {
                when (val e = pattern[i + 1]) {
                    'd' -> out.append(Rx.DIGITS)
                    's' -> out.append(Rx.SPACE)
                    'w' -> out.append(Rx.WORDS)
                    'Q' -> {
                        val end = pattern.indexOf("\\E", i + 2).let { if (it < 0) pattern.length else it }
                        var j = i + 2
                        while (j < end) {
                            val cp = PortableText.codePointAt(pattern, j)
                            out.append(member(cp))
                            val f = folded(cp)
                            if (f != cp) out.append(member(f))
                            j += if (cp >= 0x10000) 2 else 1
                        }
                        i = if (end >= pattern.length) end else end + 2
                        continue
                    }
                    else -> fail("\\$e inside [...]")
                }
                i += 2
                continue
            }
            val (lo, afterLo) = atom(i)!!
            // a range lo-hi (a `-` before `]` is a literal)
            if (pattern.getOrNull(afterLo) == '-' && afterLo + 1 < pattern.length && pattern[afterLo + 1] != ']') {
                val hiAtom = atom(afterLo + 1) ?: fail("a class escape as a range end")
                val hi = hiAtom.first
                if (hi < lo) fail("a reversed range")
                out.append(member(lo)).append('-').append(member(hi))
                if (hi - lo <= 1024) {
                    // every member's match form that the range does not hold already (`[Z-a]` gains `z`)
                    val extra = runsOf((lo..hi).map(::folded).filter { it < lo || it > hi }.toSet())
                    for ((a, b) in extra) out.append(member(a)).also { if (b > a) out.append('-').append(member(b)) }
                } else {
                    val flo = folded(lo)
                    val fhi = folded(hi)
                    if ((flo != lo || fhi != hi) && flo <= fhi) out.append(member(flo)).append('-').append(member(fhi))
                }
                i = hiAtom.second
                continue
            }
            out.append(member(lo))
            val f = folded(lo)
            if (f != lo) out.append(member(f))
            i = afterLo
        }
        fail("an unterminated [...]")
    }
}
