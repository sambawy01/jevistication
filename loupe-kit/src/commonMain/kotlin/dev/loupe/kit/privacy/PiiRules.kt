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
 *   card_number        13-19 digits in a known network's issuer range at a length it issues, a valid Luhn
 *                      check, not part of a longer number / date / order, tracking or phone number; text
 *                      recognised in a picture also needs a card layout or a card word nearby
 *                      ([PiiRules.cardCheck], Station's `card_check` at commit 4cb9026)
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

    // Python _CARD_LOOSE: (?<![\d-])(\d(?:[ -]?\d){12,18})(?![\d]) — masking only: mask more, not less.
    internal val CARD = GuardedRegex("""(\d(?:[ -]?\d){12,18})(?![\d])""") { isAsciiDigit(it) || it == '-' }

    // Python _CARD: (?<![\w+\-/.#])(\d(?:[ -]?\d){12,18})(?!\w|[-/.:]\d) — card-shaped runs not glued to a
    // word, a "+" (a phone), a "#" (an order number), a slash, a dot or a dash (dates, versions, UUIDs).
    internal val CARD_STRICT = GuardedRegex("""(\d(?:[ -]?\d){12,18})(?![A-Za-z0-9_\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u0600-\u06FF\u0750-\u077F]|[-/.:]\d)""") {
        it.isLetterOrDigit() || it == '_' || it in "+-/.#"
    }

    /** A card network: id, name, issuer range (IIN) and the lengths it issues (Station's CARD_BRANDS, in order). */
    class CardNetwork(val id: String, val name: String, prefix: String, val lengths: Set<Int>) {
        val rx = Regex("^(?:$prefix)")
    }

    val CARD_BRANDS: List<CardNetwork> = listOf(
        CardNetwork("amex", "American Express", "3[47]", setOf(15)),
        CardNetwork("jcb", "JCB", "35(?:2[89]|[3-8]\\d)", (16..19).toSet()),
        CardNetwork("diners", "Diners Club", "3(?:0[0-5]|095|[689])", (14..19).toSet()),
        CardNetwork("visa", "Visa", "4", setOf(13, 16, 19)),
        CardNetwork("meeza", "Meeza", "5078", setOf(16)),
        CardNetwork("maestro", "Maestro", "5018|5020|5038|5893|6304|6759|676[1-3]", (13..19).toSet()),
        CardNetwork("mastercard", "Mastercard", "5[1-5]|2(?:22[1-9]|2[3-9]\\d|[3-6]\\d\\d|7[01]\\d|720)", setOf(16)),
        CardNetwork("discover", "Discover", "6011|65|64[4-9]", (16..19).toSet()),
        CardNetwork("unionpay", "UnionPay", "62", (16..19).toSet()),
    )
    val BRAND_NAMES: Map<String, String> = CARD_BRANDS.associate { it.id to it.name }

    /** Printed layouts: 4-4-4-4, 4-6-5 (Amex), 4-6-4 (Diners), 4-4-4-4-3, 4-4-4-1 (old Visa), 4-4-5, 4-4-4-3. */
    val CARD_GROUPINGS: Set<List<Int>> = setOf(
        listOf(4, 4, 4, 4), listOf(4, 6, 5), listOf(4, 6, 4), listOf(4, 4, 4, 4, 3), listOf(4, 4, 4, 1), listOf(4, 4, 5), listOf(4, 4, 4, 3),
    )
    private val CARD_CUE = Regex(
        "(?i)(?<![A-Za-z])(?:card(?:holder| holder| no| number)?|visa|master ?card|amex|american express|discover|jcb|" +
            "maestro|meeza|union ?pay|debit|credit|exp(?:iry|ires|iration)?|valid (?:thru|through|until|from)|good thru|" +
            "cvv2?|cvc2?)(?![A-Za-z])|بطاقة|البطاقة|فيزا|ماستر ?كارد|ميزة|ائتمان|الائتمان|صالحة حتى|تنتهي",
    )
    private val OTHER_NUMBER_CUE = Regex(
        "(?i)(?<![A-Za-z])(?:order|tracking|track|awb|waybill|shipment|consignment|parcel|invoice|inv|ref|reference|" +
            "transaction|txn|imei|meid|serial|s/n|sn|barcode|sku|upc|ean|gtin|iccid|sim|tel|phone|mobile|mob|fax|" +
            "whatsapp|policy|account|acct|customer|member|ticket|booking|pnr)(?![A-Za-z])|رقم الطلب|طلب|شحنة|بوليصة|فاتورة|" +
            "هاتف|موبايل|تليفون|جوال|الرقم التسلسلي|حساب",
    )
    const val CUE_BEFORE = 80
    const val CUE_AFTER = 60
    const val OTHER_BEFORE = 32

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

    /** The network whose issuer range and card length this number fits ("visa", "mastercard", ...), or null. */
    fun cardBrand(digits: String): String? = CARD_BRANDS.firstOrNull { digits.length in it.lengths && it.rx.containsMatchIn(digits) }?.id

    /** 13-19 digits, a known network at a length it issues, not one repeated pattern, a valid Luhn digit. */
    fun cardOk(digits: String): Boolean =
        digits.length in 13..19 && cardBrand(digits) != null && digits.toSet().size > 2 && luhnOk(digits)

    /** Starts with a date written without separators (YYYYMMDD or DDMMYYYY). */
    private fun dateLike(digits: String): Boolean {
        for ((y, m, d) in listOf(Triple(0..3, 4..5, 6..7), Triple(4..7, 2..3, 0..1))) {
            if (digits.length < 8) return false
            val yy = digits.substring(y).toInt()
            if (yy !in 1900..2099) continue
            if (runCatching { LocalDate(yy, digits.substring(m).toInt(), digits.substring(d).toInt()) }.isSuccess) return true
        }
        return false
    }

    private val LEFT_RUN = Regex("""(?:\d+[ -])+$""")
    private val RIGHT_RUN = Regex("""^[ -](\d+)(?![\d/.:])""")

    /** The digits a..b continue with more space- or dash-separated groups: part of a longer number. */
    private fun longerRun(t: String, a: Int, b: Int): Boolean {
        val left = LEFT_RUN.find(t.substring(maxOf(0, a - 40), a))
        if (left != null) {
            val start = a - left.value.length
            if (!(start > 0 && t[start - 1] in "/.:")) return true
        }
        return RIGHT_RUN.containsMatchIn(t.substring(b, minOf(t.length, b + 40)))
    }

    /** "4-4-4-4" when [raw] is printed in one of the card layouts with one kind of separator. */
    fun grouping(raw: String): String? {
        val seps = raw.filter { it == ' ' || it == '-' }.toSet()
        if (seps.size != 1) return null
        val groups = raw.split(' ', '-').map { it.length }
        return if (groups in CARD_GROUPINGS) groups.joinToString("-") else null
    }

    /** The card word nearest to t[a:b] (within 80 characters before or 60 after), lower-cased. */
    private fun cardCue(t: String, a: Int, b: Int): String? {
        var best: Pair<Int, String>? = null
        val head = t.substring(0, a)
        for (m in CARD_CUE.findAll(head, maxOf(0, a - CUE_BEFORE))) best = (a - (m.range.last + 1)) to m.value
        val tail = t.substring(0, minOf(t.length, b + CUE_AFTER))
        CARD_CUE.find(tail, b)?.let { m -> if (best == null || m.range.first - b < best!!.first) best = (m.range.first - b) to m.value }
        return best?.second?.lowercase()
    }

    /**
     * What made the digits t[a:b] a payment card number, or null when they are not one (Station's
     * `card_check`): a known network at a length it issues, Luhn, not a national ID, not part of a
     * longer number, not a date written without separators, not right after an "order / tracking /
     * invoice / IMEI / phone" word (unless a card word is nearer). Text recognised in a picture
     * ([ocr]) must also be printed in a card layout or have a card word nearby.
     */
    fun cardCheck(t: String, a: Int, b: Int, ocr: Boolean, today: LocalDate = systemToday()): CardCheck? {
        val raw = t.substring(a, b)
        val digits = raw.filter { it in '0'..'9' }
        if (!cardOk(digits) || validEgyptNid(digits, today)) return null
        if (longerRun(t, a, b)) return null
        val grouping = grouping(raw)
        if (grouping == null && dateLike(digits)) return null
        val cue = cardCue(t, a, b)
        val head = t.substring(0, a)
        val other = OTHER_NUMBER_CUE.findAll(head, maxOf(0, a - OTHER_BEFORE)).lastOrNull()
        if (other != null) {
            val nearCard = CARD_CUE.findAll(head, maxOf(0, a - OTHER_BEFORE)).lastOrNull()?.let { it.range.last + 1 }
            if (nearCard == null || nearCard < other.range.last + 1) return null
        }
        if (ocr && grouping == null && cue == null) return null
        val brand = cardBrand(digits)
        return CardCheck(brand, BRAND_NAMES[brand].orEmpty(), digits.length, true, grouping, cue, ocr)
    }

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
        // Loupe's one change to Station's format: no "(label)" suffix. The finding's title already
        // names the kind, and the suffix nested inside chips ("×1 (2… (payment card number))").
        val first = value.take(1)
        return if (kind == "email") "$first…@…" else "$first…"
    }

    /** Short names for chips and titles: "Payment card ×1". */
    val CHIP_LABELS: Map<String, String> = mapOf(
        "egypt_national_id" to "Egyptian national ID", "passport_number" to "Passport number", "iban" to "IBAN",
        "card_number" to "Payment card", "email" to "Email address", "phone" to "Phone number",
        "contact_list" to "Contact list", "payroll_headers" to "Payroll headers",
    )

    /** One clean chip: "Payment card ×1". Never nests a preview or another label. */
    fun chip(type: String, n: Int): String = "${CHIP_LABELS[type] ?: LABELS[type] ?: type} ×$n"

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
 * One detector hit in a text (Station's `Match`): kind, span [start, end), the value as the detector
 * normalised it, and what it passed ([check], [lines] in words; [card] for a card). In memory only.
 */
data class PiiMatch(
    val kind: String,
    val start: Int,
    val end: Int,
    val value: String,
    val check: Map<String, String> = emptyMap(),
    val lines: List<String> = emptyList(),
    val card: CardCheck? = null,
)

/** Station's `detect` (and `payroll_terms` for [PiiRules.detectPayroll]). */
fun PiiRules.detect(t: String, ocr: Boolean, today: LocalDate, lo: Int = 0, hi: Int = t.length): List<PiiMatch> {
    val out = mutableListOf<PiiMatch>()
    val end = minOf(t.length, hi + 256)
    val window = t.substring(0, end)
    val taken = mutableListOf<IntRange>()
    fun free(a: Int, b: Int): Boolean = taken.none { a < it.last + 1 && it.first < b }
    fun matches(rx: GuardedRegex): Sequence<MatchResult> =
        if (lo > window.length) emptySequence() else rx.findAll(window, lo).takeWhile { it.range.first < hi }
    fun matches(rx: Regex): Sequence<MatchResult> =
        if (lo > window.length) emptySequence() else rx.findAll(window, lo).takeWhile { it.range.first < hi }

    for (m in matches(NID)) {
        if (validEgyptNid(m.value, today)) {
            taken += m.range
            out += PiiMatch("egypt_national_id", m.range.first, m.range.last + 1, m.value, mapOf("nid" to "true"),
                listOf("14 digits", "century digit, birth date and governorate code are valid"))
        }
    }
    for (m in matches(IBAN)) {
        val raw = m.groupValues[1]
        val iban = ibanFrom(raw) ?: continue
        taken += m.range
        var n = iban.length
        var stop = m.range.first
        for ((i, ch) in raw.withIndex()) {                  // the span of the IBAN itself (it may stop early)
            if (ch != ' ') n--
            if (n == 0) { stop = m.range.first + i + 1; break }
        }
        val known = IBAN_LENGTHS[iban.take(2)]
        out += PiiMatch("iban", m.range.first, stop, iban, mapOf("mod97" to "true", "length" to iban.length.toString(), "country" to iban.take(2)),
            listOfNotNull("mod-97 checksum passes", known?.let { "length $it, right for ${iban.take(2)}" }))
    }
    for (m in matches(CARD_STRICT)) {
        if (!free(m.range.first, m.range.last + 1)) continue
        val chk = cardCheck(t, m.range.first, m.range.last + 1, ocr, today) ?: continue
        taken += m.range
        out += PiiMatch("card_number", m.range.first, m.range.last + 1, m.groupValues[1].filter { it in '0'..'9' }, chk.asMap(), chk.lines(), chk)
    }
    for (k in matches(PASSPORT_KW)) {
        val from = k.range.last + 1
        val tail = t.substring(from, minOf(t.length, from + 60))
        val n = PASSPORT_NO.findAll(tail).firstOrNull() ?: continue
        out += PiiMatch("passport_number", from + n.range.first, from + n.range.last + 1, n.groupValues[1], mapOf("keyword" to k.value.lowercase()),
            listOf("passport-number shape", "within 60 characters after “${k.value}”"))
    }
    for (m in matches(EMAIL)) {
        out += PiiMatch("email", m.range.first, m.range.last + 1, m.groupValues[1].lowercase(), mapOf("shape" to "email"), listOf("email address shape"))
    }
    for (rx in listOf(PHONE_EG, PHONE_INTL)) {
        for (m in matches(rx)) {
            if (!free(m.range.first, m.range.last + 1)) continue
            val g = m.groupValues[1]
            var digits = g.filter { it in '0'..'9' }
            if (rx === PHONE_EG) {
                val s = g.trimStart()
                if (!(s.startsWith("+20") || s.startsWith("0020") || s.startsWith("20") || s.startsWith("0"))) continue
                digits = digits.takeLast(10)                // 1XXXXXXXXX
            }
            val a = m.range.first + (g.length - g.trimStart().length)
            val eg = rx === PHONE_EG
            out += PiiMatch("phone", a, m.range.last + 1, digits, mapOf("shape" to if (eg) "egypt_mobile" else "international"),
                listOf(if (eg) "Egyptian mobile shape (01x)" else "international number (+country code)"))
        }
    }
    return out
}

/** Station's `payroll_terms`: payroll column-header words (not personal data themselves). */
fun PiiRules.detectPayroll(t: String): List<PiiMatch> =
    PAY_CORE.findAll(t).map { PiiMatch("payroll_headers", it.range.first, it.range.last + 1, normTerm(it.value), mapOf("core" to "true"), listOf("payroll term “${normTerm(it.value)}”")) }.toList() +
        PAY_OTHER.findAll(t).map { PiiMatch("payroll_headers", it.range.first, it.range.last + 1, normTerm(it.value), mapOf("core" to "false"), listOf("payroll term “${normTerm(it.value)}”")) }.toList()

/**
 * Scan windows of a stream; accept matches starting in [lo, hi) so overlaps don't double count.
 * Keeps only per-type counts, hashed distinct sets (in memory) and redacted previews.
 */
class PiiCollector(private val today: LocalDate = PiiRules.systemToday(), private val ocr: Boolean = false) {
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

        for (m in PiiRules.detect(t, ocr, today, lo, hi)) add(m.kind, m.value, m.start)
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
