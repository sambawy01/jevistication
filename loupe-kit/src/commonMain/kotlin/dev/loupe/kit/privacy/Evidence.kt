package dev.loupe.kit.privacy

import kotlinx.datetime.LocalDate

/*
 * "Show where" (owner rule 2026-09-24: any result that refers to a file must give the user a way
 * to verify it). Evidence is RE-DERIVED from the item's text when the user taps, handed to the
 * screen, and dropped: nothing here is stored, logged or written. Every value leaves this file
 * masked ([PrivacyMask]); only the offsets of the match are exact, so the phone can draw the OCR
 * line's box over a photo.
 *
 * Also here: the stricter card rule for OCR'd text ([CardRules]). Station's rule (Luhn + 13–19
 * digits + known IIN) is kept for files; text that came from on-device OCR must also not be part
 * of a longer digit run, a date or time, a phone number, an IMEI, a tracking/order number or a hex
 * string, and needs a card cue nearby or strict 4-4-4-4 (Amex 4-6-5) grouping.
 */

/** Card network from the IIN, or null when the prefix is not one Loupe knows. */
object CardBrands {
    fun of(digits: String): String? {
        fun p(n: Int) = digits.take(n).toIntOrNull() ?: -1
        return when {
            digits.startsWith("4") -> "Visa"
            p(2) in 51..55 || p(4) in 2221..2720 -> "Mastercard"
            p(2) == 34 || p(2) == 37 -> "American Express"
            p(4) == 6011 || p(2) == 65 || p(3) in 644..649 -> "Discover"
            p(2) == 35 -> "JCB"
            p(3) in 300..305 || p(2) == 36 || p(2) == 38 -> "Diners Club"
            p(2) == 50 -> "Maestro"
            else -> null
        }
    }
}

/** Why a card-shaped number was taken (the checks it passed) or turned down. */
data class CardVerdict(val ok: Boolean, val checks: List<String>, val rejected: String?)

object CardRules {
    /** Words that say a number is a payment card. Latin ones match as whole words. */
    val CUES: List<String> = listOf(
        "card", "cards", "card no", "card number", "visa", "mastercard", "master card", "amex", "american express",
        "discover", "maestro", "debit", "credit", "exp", "expiry", "expires", "exp date", "valid thru", "valid through",
        "good thru", "cvv", "cvc", "cardholder", "card holder",
        "بطاقة", "البطاقة", "بطاقه", "ائتمان", "فيزا", "ماستر", "كارت",
    )

    /** Words that say a long number is something else. Checked just before (and after) the number. */
    val NOT_CARD: List<String> = listOf(
        "imei", "meid", "serial", "s/n", "sn", "tracking", "track", "awb", "shipment", "waybill", "consignment",
        "order", "order no", "invoice", "inv", "ref", "reference", "booking", "ticket", "barcode", "ean", "upc", "isbn",
        "tel", "phone", "mobile", "fax", "whatsapp", "account", "acct", "policy", "customer id", "transaction id", "txn",
        "رقم الطلب", "الطلب", "شحنة", "الشحنة", "تتبع", "فاتورة", "هاتف", "موبايل", "جوال", "تليفون", "حساب",
    )

    private val ARABIC = Regex("[؀-ۿ]")
    private val GROUPED_16 = Regex("""^\d{4}([ -])\d{4}\1\d{4}\1\d{4}$""")
    private val GROUPED_AMEX = Regex("""^\d{4}([ -])\d{6}\1\d{5}$""")

    private fun wordAt(text: String, i: Int): Boolean = i in text.indices && (text[i].isLetterOrDigit())

    /** Whole-word (Latin) or substring (Arabic) hits of [words] in [text]. */
    fun hits(words: List<String>, text: String): List<String> {
        val low = text.lowercase()
        return words.filter { w ->
            if (ARABIC.containsMatchIn(w)) w in low
            else {
                var from = 0
                var found = false
                while (!found) {
                    val i = low.indexOf(w, from)
                    if (i < 0) break
                    if (!wordAt(low, i - 1) && !wordAt(low, i + w.length)) found = true
                    from = i + 1
                }
                found
            }
        }
    }

    /**
     * Station's card rule, plus — for [ocr] text — the stricter one. [text] is the digit-folded text
     * the [range] (the CARD regex match) was found in; [digits] its digits.
     */
    fun judge(text: String, range: IntRange, digits: String, ocr: Boolean): CardVerdict {
        val checks = mutableListOf<String>()
        if (digits.length !in 13..19) return CardVerdict(false, checks, "not 13–19 digits")
        val brand = CardBrands.of(digits) ?: return CardVerdict(false, checks, "no known card prefix (IIN)")
        if (digits.toSet().size <= 2) return CardVerdict(false, checks, "too few distinct digits")
        if (!PiiRules.luhnOk(digits)) return CardVerdict(false, checks, "fails the Luhn check")
        checks += "Luhn checksum passes"
        checks += "${digits.length} digits (13–19 allowed)"
        checks += "IIN ${digits.take(4)} is $brand"
        if (!ocr) return CardVerdict(true, checks, null)

        val raw = text.substring(range)
        val before = text.substring(maxOf(0, range.first - 30), range.first)
        val after = text.substring(range.last + 1, minOf(text.length, range.last + 1 + 30))
        // part of a longer digit run ("1234 5678 9012 3456 7890", "12-3456789012345678")
        if (Regex("""\d[ \-]?$""").containsMatchIn(before) || Regex("""^[ \-]?\d""").containsMatchIn(after)) {
            return CardVerdict(false, checks, "part of a longer digit run")
        }
        // a date, a time, a decimal or a version ("12/2024 ...", "4111....1111.5")
        if (Regex("""\d[/.:]$""").containsMatchIn(before) || Regex("""^[/.:]\d""").containsMatchIn(after)) {
            return CardVerdict(false, checks, "part of a date, time or decimal")
        }
        // hex or another code: letters touching the number
        if ((before.isNotEmpty() && before.last().isLetter()) || (after.isNotEmpty() && after.first().isLetter())) {
            return CardVerdict(false, checks, "part of a code or hex string")
        }
        // a phone number
        if (Regex("""\+\s?$""").containsMatchIn(before) || digits.startsWith("00")) {
            return CardVerdict(false, checks, "looks like a phone number")
        }
        val nearBefore = before.takeLast(24)
        val not = hits(NOT_CARD, nearBefore) + hits(NOT_CARD, after.take(12))
        if (not.isNotEmpty()) return CardVerdict(false, checks, "labelled “${not.first()}”: not a card")
        if (digits.length == 15 && brand == "JCB") return CardVerdict(false, checks, "15 digits starting 35: an IMEI shape")
        val window = text.substring(maxOf(0, range.first - 40), minOf(text.length, range.last + 1 + 40))
        val cues = hits(CUES, window)
        val grouped = GROUPED_16.matches(raw) || GROUPED_AMEX.matches(raw)
        if (cues.isEmpty() && !grouped) {
            return CardVerdict(false, checks, "photo text: no card word nearby and not grouped 4-4-4-4")
        }
        if (cues.isNotEmpty()) checks += "card word “${cues.first()}” nearby"
        if (grouped) checks += if (digits.length == 15) "grouped 4-6-5" else "grouped 4-4-4-4"
        checks += "not a longer number, date, phone, IMEI, tracking/order number or hex"
        return CardVerdict(true, checks, null)
    }
}

/** Masked forms of detected values. The only way a value reaches the screen. */
object PrivacyMask {
    const val DOT = "•"

    private fun dots(n: Int) = DOT.repeat(maxOf(0, n))

    /** "•••• •••• •••• 1234" (the digits grouped by four, all but the last four hidden). */
    fun card(digits: String): String {
        val d = digits.filter { it.isDigit() }
        val hidden = dots(d.length - 4)
        return (hidden.chunked(4) + d.takeLast(4)).joinToString(" ")
    }

    /** "EG•• •••• •••• 0002": the country, then only the last four (the length is not shown). */
    fun iban(iban: String): String {
        val c = iban.replace(" ", "")
        if (c.length < 8) return dots(c.length)
        return c.take(2) + "•• •••• •••• " + c.takeLast(4)
    }

    /** National ID and other numbers: dots, then the last four. */
    fun number(value: String): String {
        val d = value.filter { it.isLetterOrDigit() }
        return if (d.length <= 4) dots(d.length) else dots(d.length - 4) + " " + d.takeLast(4)
    }

    /** "A••••••78". */
    fun passport(value: String): String = if (value.length <= 3) dots(value.length) else value.take(1) + dots(value.length - 3) + value.takeLast(2)

    /** "+20 •••••••• 67" / "•••••••• 78". */
    fun phone(value: String): String {
        val digits = value.filter { it.isDigit() }
        val cc = if (value.trimStart().startsWith("+")) "+" + digits.take(if (digits.startsWith("20")) 2 else 1) + " " else ""
        val rest = if (cc.isEmpty()) digits else digits.drop(cc.count { it.isDigit() })
        return cc + dots(rest.length - 2) + " " + rest.takeLast(2)
    }

    /** "c•••@e•••.com". */
    fun email(value: String): String {
        val at = value.indexOf('@')
        if (at <= 0) return dots(value.length)
        val local = value.substring(0, at)
        val domain = value.substring(at + 1)
        val dot = domain.lastIndexOf('.')
        val host = if (dot > 0) domain.substring(0, dot) else domain
        val tld = if (dot > 0) domain.substring(dot) else ""
        return local.take(1) + dots(3) + "@" + host.take(1) + dots(3) + tld
    }

    fun of(kind: String, value: String): String = when (kind) {
        "card_number" -> card(value)
        "iban" -> iban(value)
        "egypt_national_id" -> number(value)
        "passport_number" -> passport(value)
        "phone" -> phone(value)
        "email" -> email(value)
        else -> value
    }
}

/**
 * One place a finding's rule matched. [start]/[end] are offsets in the text the rules read (so the
 * phone can map a match to its OCR line); [masked] and [context] are safe to show.
 */
data class EvidenceHit(
    val ruleId: String,
    val start: Int,
    val end: Int,
    val masked: String,
    /** Card network, or null. */
    val brand: String?,
    /** The line the match is on, the match masked and any other personal data replaced. */
    val context: String,
    /** The checks it passed: "Luhn checksum passes", "IIN 4539 is Visa", … */
    val checks: List<String>,
)

object PrivacyEvidence {
    const val MAX_HITS = 20
    private const val CONTEXT = 32
    private val DIGIT_RUN = Regex("""\d(?:[ \-]?\d){4,}""")

    /** Whether the rules should treat [kindTitle]/[textFact] as OCR'd text (a photo, a scanned PDF). */
    fun isOcr(isImage: Boolean, textFact: String?): Boolean = isImage || (textFact?.contains("OCR") == true)

    /** Swift entry: today's date from the system. */
    fun findToday(text: String, ruleId: String, ocr: Boolean): List<EvidenceHit> = find(text, ruleId, ocr, PiiRules.systemToday())

    /**
     * Re-derives where [ruleId] matched in [text]. The same rules as the check (Station's, plus the
     * OCR card rule), so a finding and its evidence cannot disagree.
     */
    fun find(text: String, ruleId: String, ocr: Boolean, today: LocalDate): List<EvidenceHit> {
        val t = PiiRules.foldDigits(text)
        val out = mutableListOf<EvidenceHit>()
        fun add(kind: String, range: IntRange, value: String, brand: String?, checks: List<String>) {
            if (out.size >= MAX_HITS) return
            val masked = PrivacyMask.of(kind, value)
            out += EvidenceHit(kind, range.first, range.last + 1, masked, brand, context(t, range, masked), checks)
        }
        when (ruleId) {
            "card_number" -> for (m in PiiRules.CARD.findAll(t)) {
                val digits = m.groupValues[1].filter { it in '0'..'9' }
                if (PiiRules.validEgyptNid(digits, today)) continue
                val v = CardRules.judge(t, m.range, digits, ocr)
                if (v.ok) add("card_number", m.range, digits, CardBrands.of(digits), v.checks)
            }
            "iban" -> for (m in PiiRules.IBAN.findAll(t)) {
                val iban = PiiRules.ibanFrom(m.groupValues[1]) ?: continue
                val known = PiiRules.IBAN_LENGTHS[iban.take(2)]
                add("iban", m.range, iban, null, listOfNotNull("mod-97 checksum passes", known?.let { "length $it, right for ${iban.take(2)}" }))
            }
            "egypt_national_id" -> for (m in PiiRules.NID.findAll(t)) {
                if (PiiRules.validEgyptNid(m.value, today)) {
                    add("egypt_national_id", m.range, m.value, null, listOf("14 digits", "century digit, birth date and governorate code are valid"))
                }
            }
            "passport_number" -> for (k in PiiRules.PASSPORT_KW.findAll(t)) {
                val from = k.range.last + 1
                val tail = t.substring(from, minOf(t.length, from + 60))
                val n = PiiRules.PASSPORT_NO.findAll(tail).firstOrNull() ?: continue
                val r = (from + n.range.first)..(from + n.range.last)
                add("passport_number", r, n.groupValues[1], null, listOf("passport-number shape", "within 60 characters after “${k.value}”"))
            }
            "email", "contact_list" -> {
                for (m in PiiRules.EMAIL.findAll(t)) add("email", m.range, m.groupValues[1], null, listOf("email address shape"))
                if (ruleId == "contact_list") phones(t, ::add)
            }
            "phone" -> phones(t, ::add)
            "payroll_headers" -> for (rx in listOf(PiiRules.PAY_CORE, PiiRules.PAY_OTHER)) {
                for (m in rx.findAll(t)) {
                    if (out.size >= MAX_HITS) break
                    out += EvidenceHit("payroll_headers", m.range.first, m.range.last + 1, m.value, null,
                        context(t, m.range, m.value), listOf("payroll term “${PiiRules.normTerm(m.value)}”"))
                }
            }
        }
        return out.sortedBy { it.start }
    }

    private fun phones(t: String, add: (String, IntRange, String, String?, List<String>) -> Unit) {
        for (rx in listOf(PiiRules.PHONE_EG, PiiRules.PHONE_INTL)) {
            for (m in rx.findAll(t)) {
                val s = m.groupValues[1].trimStart()
                if (rx === PiiRules.PHONE_EG && !(s.startsWith("+20") || s.startsWith("0020") || s.startsWith("20") || s.startsWith("0"))) continue
                add("phone", m.range, s, null, listOf(if (rx === PiiRules.PHONE_EG) "Egyptian mobile shape (01x)" else "international number (+country code)"))
            }
        }
    }

    /** The match's line, at most [CONTEXT] characters each side, the match masked and the rest passed through [PiiRules.maskPii]. */
    fun context(t: String, range: IntRange, masked: String): String {
        val lineStart = t.lastIndexOf('\n', range.first - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = t.indexOf('\n', range.last + 1).let { if (it < 0) t.length else it }
        val from = maxOf(lineStart, range.first - CONTEXT)
        val to = minOf(lineEnd, range.last + 1 + CONTEXT)
        // Station's placeholders, then any run of five or more digits left (a cut-off number).
        val before = PiiRules.maskPii(t.substring(from, range.first)).replace(DIGIT_RUN, "[number]")
        val after = PiiRules.maskPii(t.substring(range.last + 1, to)).replace(DIGIT_RUN, "[number]")
        return ((if (from > lineStart) "…" else "") + before + masked + after + (if (to < lineEnd) "…" else "")).trim()
    }
}
