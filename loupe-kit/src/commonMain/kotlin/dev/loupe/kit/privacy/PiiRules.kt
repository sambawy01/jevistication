package dev.loupe.kit.privacy

import dev.loupe.engine.ContentHash
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/*
 * Deterministic personal-data and business-data detectors. Counts and redacted previews only.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/scan/pii_rules.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). The rule ids,
 * labels, tables (governorates, IBAN lengths, payroll terms), regular expressions, thresholds and
 * preview format are copied verbatim; only the language changed. Differences, all mechanical:
 * the in-memory distinct-value hash is SHA-256 (the engine's `ContentHash`) instead of BLAKE2b-64,
 * and `\d`/`\s` are ASCII here where Python's are Unicode (Arabic-Indic digits are still folded to
 * ASCII first, exactly as the original does).
 *
 * Signals (each [PiiSignal]):
 *   egypt_national_id  14 digits: century (2/3), valid birth date, valid governorate code
 *   passport_number    a passport-number shape within a few words after "passport" / "جواز"
 *   iban               IBAN with a valid mod-97 checksum (and the country's length when known)
 *   card_number        13-19 digit card number with a known network prefix and a valid Luhn check
 *   email / phone      addresses and phone numbers (Egyptian +20 / 01x mobiles, international +...)
 *   contact_list       many distinct emails/phones in one file: looks like a customer or contact list
 *   payroll_headers    3+ salary/payroll column-header terms incl. a salary term (English and Arabic)
 * No detected value is ever kept: previews show at most the first character.
 */

/**
 * A pattern whose Python original starts with a negative lookbehind, run as the pattern without it
 * plus a check of the character before each match. Identical results: when the character before a
 * match is excluded the search resumes one character later, exactly as the lookbehind would. Why:
 * Kotlin/Native's regex engine makes a leading lookbehind cost O(n) per position (a 50,000-character
 * text took minutes on the iOS simulator), so the station's patterns are split at that point.
 */
class GuardedRegex(pattern: String, options: Set<RegexOption> = emptySet(), private val excludedBefore: (Char) -> Boolean) {
    val body = Regex(pattern, options)

    fun findAll(input: CharSequence, startIndex: Int = 0): Sequence<MatchResult> = sequence {
        // Only positions the lookbehind allows are tried, each with an anchored match, so a pattern
        // that starts with a long run (ASSIGN's possessive prefix) is never tried mid-run.
        var pos = startIndex
        while (pos <= input.length) {
            if (pos > 0 && excludedBefore(input[pos - 1])) {
                pos++
                continue
            }
            val m = body.matchAt(input, pos)
            if (m == null) {
                pos++
                continue
            }
            yield(m)
            pos = if (m.value.isEmpty()) pos + 1 else m.range.last + 1
        }
    }

    fun replace(input: CharSequence, transform: (MatchResult) -> CharSequence): String {
        val sb = StringBuilder()
        var last = 0
        for (m in findAll(input)) {
            sb.append(input, last, m.range.first).append(transform(m))
            last = m.range.last + 1
        }
        return sb.append(input, last, input.length).toString()
    }

    fun matchEntire(input: CharSequence): MatchResult? = body.matchEntire(input)
}

internal fun isAsciiDigit(c: Char) = c in '0'..'9'
internal fun isAsciiLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'
internal fun isAsciiAlnum(c: Char) = isAsciiLetter(c) || isAsciiDigit(c)

/** One detector's hits in one text: counts and redacted previews, never a value. */
data class PiiSignal(
    val type: String,
    val label: String,
    val count: Int,
    val distinct: Int,
    val previews: List<String>,
)

object PiiRules {
    private const val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹"
    private const val ASCII_DIGITS = "01234567890123456789"

    /** Arabic-Indic and Eastern Arabic-Indic digits -> ASCII (same length, so offsets stay valid). */
    fun foldDigits(text: String): String {
        if (text.none { ARABIC_DIGITS.indexOf(it) >= 0 }) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val i = ARABIC_DIGITS.indexOf(ch)
            sb.append(if (i >= 0) ASCII_DIGITS[i] else ch)
        }
        return sb.toString()
    }

    val LABELS: Map<String, String> = mapOf(
        "egypt_national_id" to "Egyptian national ID", "passport_number" to "passport number", "iban" to "IBAN",
        "card_number" to "payment card number", "email" to "email address", "phone" to "phone number",
        "contact_list" to "contact list", "payroll_headers" to "payroll column headers",
    )
    val PERSONAL_SIGNALS: Set<String> = setOf("egypt_national_id", "passport_number", "iban", "card_number")
    val BUSINESS_SIGNALS: Set<String> = setOf("contact_list", "payroll_headers")
    const val CONTACT_LIST_MIN = 20          // distinct emails + phones in one file
    const val PAYROLL_MIN_TERMS = 3          // distinct payroll header terms, at least one of them a salary term
    const val MAX_PREVIEWS = 3
    const val MAX_DISTINCT_TRACKED = 100_000

    // ------------------------------------------------------------------------ Egyptian national ID
    val GOVERNORATES: Set<String> = setOf(
        "01", "02", "03", "04", "11", "12", "13", "14", "15", "16", "17", "18", "19",
        "21", "22", "23", "24", "25", "26", "27", "28", "29", "31", "32", "33", "34",
        "35", "88",
    )
    // Python: (?<!\d)([23])(\d{2})(\d{2})(\d{2})(\d{2})(\d{5})(?!\d)
    internal val NID = GuardedRegex("""([23])(\d{2})(\d{2})(\d{2})(\d{2})(\d{5})(?!\d)""") { isAsciiDigit(it) }

    fun validEgyptNid(s: String, today: LocalDate = systemToday()): Boolean {
        val m = NID.matchEntire(s) ?: return false
        val g = m.groupValues
        val century = g[1]
        val yy = g[2].toInt()
        val mm = g[3].toInt()
        val dd = g[4].toInt()
        if (g[5] !in GOVERNORATES) return false
        val year = (if (century == "2") 1900 else 2000) + yy
        val born = try { LocalDate(year, mm, dd) } catch (_: IllegalArgumentException) { return false }
        return born <= today
    }

    // ------------------------------------------------------------------------ IBAN / cards
    val IBAN_LENGTHS: Map<String, Int> = mapOf(
        "EG" to 29, "AE" to 23, "SA" to 24, "QA" to 29, "KW" to 30, "BH" to 22, "JO" to 30, "LB" to 28, "GB" to 22,
        "DE" to 22, "FR" to 27, "IT" to 27, "ES" to 24, "NL" to 18, "BE" to 16, "CH" to 21, "AT" to 20, "IE" to 22,
        "PT" to 25, "SE" to 24, "NO" to 15, "DK" to 18, "FI" to 18, "PL" to 28, "TR" to 26, "GR" to 27, "CY" to 28,
    )
    // Python: (?<![A-Za-z0-9])([A-Z]{2}\d{2}(?: ?[A-Z0-9]){11,32})
    internal val IBAN = GuardedRegex("""([A-Z]{2}\d{2}(?: ?[A-Z0-9]){11,32})""") { isAsciiAlnum(it) }

    fun ibanOk(s: String): Boolean {
        if (s.length !in 15..34 || !s.take(2).all { it.isLetter() } || !s.substring(2, 4).all { it in '0'..'9' }) return false
        IBAN_LENGTHS[s.take(2)]?.let { if (s.length != it) return false }
        val moved = s.substring(4) + s.take(4)
        // int("".join(str(int(ch, 36)) ...)) % 97, computed digit by digit.
        var rem = 0
        for (ch in moved) {
            val v = ch.digitToIntOrNull(36) ?: return false
            for (d in v.toString()) rem = (rem * 10 + (d - '0')) % 97
        }
        return rem == 1
    }

    internal fun ibanFrom(raw: String): String? {
        val compact = raw.replace(" ", "")
        val want = IBAN_LENGTHS[compact.take(2)]
        if (want != null) {
            val cand = compact.take(want)
            return if (cand.length == want && ibanOk(cand)) cand else null
        }
        for (n in minOf(34, compact.length) downTo 15) {
            if (ibanOk(compact.take(n))) return compact.take(n)
        }
        return null
    }

    // Python: (?<![\d-])(\d(?:[ -]?\d){12,18})(?![\d])
    internal val CARD = GuardedRegex("""(\d(?:[ -]?\d){12,18})(?![\d])""") { isAsciiDigit(it) || it == '-' }
    private val CARD_PREFIX = Regex("""^(?:4|5[1-5]|2(?:2[2-9]|[3-6]\d|7[01]|720)|3[47]|3(?:0[0-5]|[68])|6(?:011|5|4[4-9])|35|50)""")

    fun luhnOk(digits: String): Boolean {
        var total = 0
        var alt = false
        for (ch in digits.reversed()) {
            var d = ch - '0'
            if (alt) {
                d *= 2
                if (d > 9) d -= 9
            }
            total += d
            alt = !alt
        }
        return total % 10 == 0
    }

    fun cardOk(digits: String): Boolean =
        digits.length in 13..19 && CARD_PREFIX.containsMatchIn(digits) && digits.toSet().size > 2 && luhnOk(digits)

    // ------------------------------------------------------------------------ passport / contacts
    internal val PASSPORT_KW = Regex("""(?i)(passport|جواز)""")
    // Python: (?<![A-Za-z0-9])([A-Z]{1,2}\d{6,9})(?![A-Za-z0-9])
    internal val PASSPORT_NO = GuardedRegex("""([A-Z]{1,2}\d{6,9})(?![A-Za-z0-9])""") { isAsciiAlnum(it) }
    // Python: the same pattern with a leading (?<![A-Za-z0-9._%+\-])
    internal val EMAIL = GuardedRegex("""([A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9\-]{1,63}(?:\.[A-Za-z0-9\-]{1,63})*\.[A-Za-z]{2,24})""") { isAsciiAlnum(it) || it in "._%+-" }
    // Python: (?<![\d+])((?:\+20|0020|20)?[ \-]?0?1[0125](?:[ \-]?\d){8})(?!\d)
    internal val PHONE_EG = GuardedRegex("""((?:\+20|0020|20)?[ \-]?0?1[0125](?:[ \-]?\d){8})(?!\d)""") { isAsciiDigit(it) || it == '+' }
    // Python: (?<![\d+])(\+(?!20)\d{1,3}[ \-]?(?:\d[ \-]?){7,13}\d)(?!\d)
    internal val PHONE_INTL = GuardedRegex("""(\+(?!20)\d{1,3}[ \-]?(?:\d[ \-]?){7,13}\d)(?!\d)""") { isAsciiDigit(it) || it == '+' }

    // ------------------------------------------------------------------------ payroll headers
    val PAYROLL_CORE: List<String> = listOf(
        "salary", "salaries", "net pay", "gross pay", "payroll", "basic salary", "net salary", "payslip",
        "الراتب", "راتب", "المرتب", "مرتب", "صافي الراتب", "الأجر", "اجمالي الراتب",
    )
    val PAYROLL_OTHER: List<String> = listOf(
        "employee", "employee id", "employee name", "overtime", "deductions", "allowance", "allowances",
        "bonus", "social insurance", "tax deduction", "position", "department", "hire date", "iban",
        "bank account", "الموظف", "اسم الموظف", "رقم الموظف", "إضافي", "اضافي", "خصومات", "الخصومات",
        "بدلات", "البدلات", "حوافز", "التأمينات", "تأمينات", "الوظيفة", "القسم", "تاريخ التعيين",
    )
    private val ARABIC = Regex("[؀-ۿ]")

    private fun escapeLiteral(s: String): String = buildString {
        for (ch in s) {
            if (ch.isLetterOrDigit() || ch == ' ') append(ch) else append('\\').append(ch)
        }
    }

    /** The station's `_term_regex`, split in two: the Latin half (guarded) and the Arabic half. */
    internal class TermRegex(val latin: GuardedRegex?, val arabic: Regex?) {
        fun findAll(input: CharSequence, startIndex: Int = 0): Sequence<MatchResult> =
            ((latin?.findAll(input, startIndex) ?: emptySequence()) + (arabic?.findAll(input, startIndex) ?: emptySequence()))
                .sortedBy { it.range.first }
    }

    private fun termRegex(terms: List<String>): TermRegex {
        val latin = terms.filter { !ARABIC.containsMatchIn(it) }.sortedByDescending { it.length }
        val arabic = terms.filter { ARABIC.containsMatchIn(it) }.sortedByDescending { it.length }
        // re.escape(t).replace(r"\ ", r"[ _\-]?"): a space in a term also matches "_", "-" or nothing.
        // Python: (?<![A-Za-z])(?:...)(?![A-Za-z]) | (?:arabic...), with re.I.
        val l = if (latin.isEmpty()) null else GuardedRegex(
            "(?:" + latin.joinToString("|") { t -> t.split(' ').joinToString("[ _\\-]?") { escapeLiteral(it) } } + ")(?![A-Za-z])",
            setOf(RegexOption.IGNORE_CASE),
        ) { isAsciiLetter(it) }
        val a = if (arabic.isEmpty()) null else Regex("(?:" + arabic.joinToString("|") { escapeLiteral(it) } + ")", RegexOption.IGNORE_CASE)
        return TermRegex(l, a)
    }

    internal val PAY_CORE = termRegex(PAYROLL_CORE)
    internal val PAY_OTHER = termRegex(PAYROLL_OTHER)
    private val TERM_SEP = Regex("[ _\\-]+")

    internal fun normTerm(t: String): String = t.lowercase().replace(TERM_SEP, " ").trim()

    // ------------------------------------------------------------------------ previews / masking
    /** At most the first character, then an ellipsis and what it is. Never the value. */
    fun preview(kind: String, value: String): String {
        val first = value.take(1)
        return if (kind == "email") "$first…@… (${LABELS.getValue(kind)})" else "$first… (${LABELS.getValue(kind)})"
    }

    private val MASKS: List<Pair<Any, String>> = listOf(
        EMAIL to "[email]", IBAN to "[iban]", NID to "[national-id]", PHONE_EG to "[phone]",
        PHONE_INTL to "[phone]", CARD to "[card]", PASSPORT_NO to "[id-no]",
    )
    private val LONG_DIGITS = Regex("""\d{9,}""")

    /** Replace anything that looks like personal data by a placeholder. */
    fun maskPii(text: String): String {
        var out = foldDigits(text)
        for ((rx, tag) in MASKS) {
            val put = { m: MatchResult -> (if (m.value.isNotEmpty() && m.value[0].isWhitespace()) " " else "") + tag }
            out = when (rx) {
                is GuardedRegex -> rx.replace(out, put)
                else -> (rx as Regex).replace(out, put)
            }
        }
        // long digit runs of any kind (account numbers, IDs typed without structure)
        return LONG_DIGITS.replace(out, "[number]")
    }

    internal fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}

/**
 * Scan windows of a stream; accept matches starting in [lo, hi) so overlaps don't double count.
 * Keeps only per-type counts, hashed distinct sets (in memory) and redacted previews.
 */
class PiiCollector(private val today: LocalDate = PiiRules.systemToday()) {
    private val counts = LinkedHashMap<String, Int>()
    private val distinct = HashMap<String, MutableSet<String>>()
    private val previews = HashMap<String, MutableList<String>>()
    private val payCore = mutableSetOf<String>()
    private val payOther = mutableSetOf<String>()

    /** (position in the current window, type), for window picking. */
    var hits: MutableList<Pair<Int, String>> = mutableListOf()
        private set

    private fun add(kind: String, value: String, pos: Int) {
        val key = ContentHash.of(value)
        val seen = distinct.getOrPut(kind) { mutableSetOf() }
        counts[kind] = (counts[kind] ?: 0) + 1
        if (key !in seen && seen.size < PiiRules.MAX_DISTINCT_TRACKED) {
            seen += key
            val list = previews.getOrPut(kind) { mutableListOf() }
            if (list.size < PiiRules.MAX_PREVIEWS) list += PiiRules.preview(kind, value)
        }
        hits += pos to kind
    }

    fun scan(text: String, lo: Int = 0, hi: Int = text.length) {
        hits = mutableListOf()
        val t = PiiRules.foldDigits(text)
        val end = minOf(t.length, hi + 256)          // no PII match is longer than this
        val window = t.substring(0, end)
        val taken = mutableListOf<IntRange>()
        fun free(a: Int, b: Int): Boolean = taken.none { a < it.last + 1 && it.first < b }
        fun matches(rx: Regex): Sequence<MatchResult> =
            if (lo > window.length) emptySequence() else rx.findAll(window, lo).takeWhile { it.range.first < hi }
        fun matches(rx: PiiRules.TermRegex): Sequence<MatchResult> =
            if (lo > window.length) emptySequence() else rx.findAll(window, lo).takeWhile { it.range.first < hi }
        fun matches(rx: GuardedRegex): Sequence<MatchResult> =
            if (lo > window.length) emptySequence() else rx.findAll(window, lo).takeWhile { it.range.first < hi }

        for (m in matches(PiiRules.NID)) {
            if (PiiRules.validEgyptNid(m.value, today)) {
                taken += m.range
                add("egypt_national_id", m.value, m.range.first)
            }
        }
        for (m in matches(PiiRules.IBAN)) {
            val iban = PiiRules.ibanFrom(m.groupValues[1])
            if (iban != null) {
                taken += m.range
                add("iban", iban, m.range.first)
            }
        }
        for (m in matches(PiiRules.CARD)) {
            if (free(m.range.first, m.range.last + 1)) {
                val digits = m.groupValues[1].filter { it in '0'..'9' }
                if (PiiRules.cardOk(digits) && !PiiRules.validEgyptNid(digits, today)) {
                    taken += m.range
                    add("card_number", digits, m.range.first)
                }
            }
        }
        for (m in matches(PiiRules.PASSPORT_KW)) {
            val tailEnd = minOf(t.length, m.range.last + 1 + 60)
            val tail = t.substring(m.range.last + 1, tailEnd)
            val n = PiiRules.PASSPORT_NO.findAll(tail).firstOrNull()
            if (n != null) add("passport_number", n.groupValues[1], m.range.first)
        }
        for (m in matches(PiiRules.EMAIL)) {
            add("email", m.groupValues[1].lowercase(), m.range.first)
        }
        for (rx in listOf(PiiRules.PHONE_EG, PiiRules.PHONE_INTL)) {
            for (m in matches(rx)) {
                if (!free(m.range.first, m.range.last + 1)) continue
                var digits = m.groupValues[1].filter { it in '0'..'9' }
                if (rx === PiiRules.PHONE_EG) {
                    val s = m.groupValues[1].trimStart()
                    if (!(s.startsWith("+20") || s.startsWith("0020") || s.startsWith("20") || s.startsWith("0"))) continue
                    digits = digits.takeLast(10)            // 1XXXXXXXXX
                }
                add("phone", digits, m.range.first)
            }
        }
        for (m in matches(PiiRules.PAY_CORE)) payCore += PiiRules.normTerm(m.value)
        for (m in matches(PiiRules.PAY_OTHER)) payOther += PiiRules.normTerm(m.value)
    }

    fun signals(): List<PiiSignal> {
        val out = mutableListOf<PiiSignal>()
        for (kind in listOf("egypt_national_id", "passport_number", "iban", "card_number", "email", "phone")) {
            val c = counts[kind] ?: 0
            if (c > 0) {
                out += PiiSignal(kind, PiiRules.LABELS.getValue(kind), c, distinct[kind]?.size ?: 0, previews[kind]?.toList() ?: emptyList())
            }
        }
        val contacts = (distinct["email"]?.size ?: 0) + (distinct["phone"]?.size ?: 0)
        if (contacts >= PiiRules.CONTACT_LIST_MIN) {
            out += PiiSignal("contact_list", PiiRules.LABELS.getValue("contact_list"), contacts, contacts, emptyList())
        }
        val terms = payCore + payOther
        if (payCore.isNotEmpty() && terms.size >= PiiRules.PAYROLL_MIN_TERMS) {
            out += PiiSignal("payroll_headers", PiiRules.LABELS.getValue("payroll_headers"), terms.size, terms.size, terms.sorted().take(6))
        }
        return out
    }

}
