package dev.loupe.kit.privacy

import kotlinx.datetime.LocalDate

/*
 * "Show where" (owner rule 2026-09-24: any result that refers to a file must give the user a way
 * to verify it). Evidence is RE-DERIVED from the item's text when the user taps, handed to the
 * screen, and dropped: nothing here is stored, logged or written. Every value leaves this file
 * masked ([PrivacyMask]); only the offsets of the match are exact, so the phone can draw the OCR
 * line's box over a photo.
 *
 * Aligned with Loupe Station's `laya_studio/scan/evidence.py` and `pii_rules.py` (commit 4cb9026,
 * owner decision C 2026-09-24): Station's card rule ([PiiRules.cardCheck]), `mask_value`
 * ([PrivacyMask]) and `masked_context` ([PrivacyEvidence.context]); the row shape where it applies.
 */

/** Card network name from the issuer range and length (Station's CARD_BRANDS), or null. */
object CardBrands {
    fun of(digits: String): String? = PiiRules.cardBrand(digits)?.let { PiiRules.BRAND_NAMES[it] }
}

/**
 * What a card number passed (Station's `card_check` dict): the network ([brand] id, [brandName]),
 * the digit count, Luhn, the printed [grouping] ("4-4-4-4") and the card word nearby ([cue]).
 */
data class CardCheck(
    val brand: String?,
    val brandName: String,
    val digits: Int,
    val luhn: Boolean,
    val grouping: String?,
    val cue: String?,
    val ocr: Boolean,
) {
    /** Station's `check` for "Show where" (brand_name goes in the row's `brand`, not here). */
    fun asMap(): Map<String, String> = buildMap {
        brand?.let { put("brand", it) }
        put("digits", digits.toString())
        put("luhn", luhn.toString())
        grouping?.let { put("grouping", it) }
        cue?.let { put("cue", it) }
        put("ocr", ocr.toString())
    }

    /** The same checks in words. */
    fun lines(): List<String> = listOfNotNull(
        "Luhn checksum passes",
        "$digits digits, a length ${brandName.ifEmpty { "the network" }} issues",
        "issuer range (IIN) is $brandName",
        grouping?.let { "printed in a card layout ($it)" },
        cue?.let { "card word “$it” nearby" },
        "not part of a longer number, a date, or an order, tracking or phone number",
    )
}

/** Masked forms of detected values (Station's `mask_value`). The only way a value reaches the screen. */
object PrivacyMask {
    const val DOT = "•"

    private fun dots(n: Int) = DOT.repeat(maxOf(0, n))

    /** "•••• •••• •••• 1234": all but the last four hidden, in groups of four. */
    fun card(value: String): String {
        val d = value.filter { it.isDigit() }
        return (List((d.length - 4 + 3) / 4) { "••••" } + d.takeLast(4)).joinToString(" ")
    }

    /** "EG•• •••• •••• 0002": the country and the last four. */
    fun iban(value: String): String {
        val v = value.replace(" ", "")
        return (listOf(v.take(2) + "••") + List(maxOf(1, (v.length - 8 + 3) / 4)) { "••••" } + v.takeLast(4)).joinToString(" ")
    }

    /** National ID: dots, then the last three. */
    fun nationalId(value: String): String = dots(value.length - 3) + value.takeLast(3)

    /** Passport: the first character and the last two ("A••••••78"). */
    fun passport(value: String): String = value.take(1) + dots(maxOf(1, value.length - 3)) + value.takeLast(2)

    /** Phone: dots, then the last four. */
    fun phone(value: String): String = dots(maxOf(2, value.length - 4)) + value.takeLast(4)

    /** Email: the first letter and the domain ("a•••@example.com"). */
    fun email(value: String): String {
        val local = value.substringBefore('@')
        val domain = value.substringAfter('@', "")
        return local.take(1) + "•••@" + domain
    }

    fun of(kind: String, value: String): String = when (kind) {
        "card_number" -> card(value)
        "iban" -> iban(value)
        "egypt_national_id" -> nationalId(value)
        "passport_number" -> passport(value)
        "phone" -> phone(value)
        "email" -> email(value)
        "payroll_headers" -> value
        else -> dots(value.length)
    }
}

/**
 * One place a finding's rule matched (Station's "Show where" row where it applies: type, masked,
 * brand, context, check, line). [start]/[end] are offsets in the text the rules read (so the phone
 * can map a match to its OCR line); [masked] and [context] are safe to show.
 */
data class EvidenceHit(
    val ruleId: String,
    val start: Int,
    val end: Int,
    val masked: String,
    /** Card network name, or null. */
    val brand: String?,
    /** One short line around the match: the value replaced by [masked] (in ⟦ ⟧), everything else masked too. */
    val context: String,
    /** The checks it passed, in words: "Luhn checksum passes", "issuer range (IIN) is Visa", … */
    val checks: List<String>,
    /** The checks it passed, as Station's `check` (card: brand, digits, luhn, grouping, cue, ocr; IBAN: mod97, length, country; …). */
    val check: Map<String, String> = emptyMap(),
    /** The 1-based line of the match in a text item (not for recognised text). */
    val line: Int? = null,
) {
    /** Station's row type. */
    val type: String get() = ruleId
}

object PrivacyEvidence {
    const val MAX_HITS = 12
    const val CONTEXT_WIDTH = 48
    private val LONG_DIGITS = Regex("""\d{5,}""")
    private val SPACES = Regex("""\s+""")

    /** Station's `SIGNAL_KINDS`: which detector kinds a finding's rule points at. */
    val SIGNAL_KINDS: Map<String, List<String>> = mapOf(
        "egypt_national_id" to listOf("egypt_national_id"), "passport_number" to listOf("passport_number"), "iban" to listOf("iban"),
        "card_number" to listOf("card_number"), "email" to listOf("email"), "phone" to listOf("phone"),
        "contact_list" to listOf("email", "phone"), "payroll_headers" to listOf("payroll_headers"),
    )

    /** Whether the rules should treat [kindTitle]/[textFact] as OCR'd text (a photo, a scanned PDF). */
    fun isOcr(isImage: Boolean, textFact: String?): Boolean = isImage || (textFact?.contains("OCR") == true)

    /** Swift entry: today's date from the system. */
    fun findToday(text: String, ruleId: String, ocr: Boolean): List<EvidenceHit> = find(text, ruleId, ocr, PiiRules.systemToday())

    /**
     * Re-derives where [ruleId] matched in [text]: Station's `detect` (the same rules as the check, so
     * a finding and its evidence cannot disagree), masked with Station's `mask_value` and
     * `masked_context`. At most [MAX_HITS], in text order. [lines] adds each hit's line number.
     */
    fun find(text: String, ruleId: String, ocr: Boolean, today: LocalDate, lines: Boolean = false): List<EvidenceHit> {
        val kinds = SIGNAL_KINDS[ruleId] ?: return emptyList()
        val t = PiiRules.foldDigits(text)
        val out = mutableListOf<EvidenceHit>()
        val found = if (kinds == listOf("payroll_headers")) PiiRules.detectPayroll(t) else PiiRules.detect(t, ocr, today)
        for (m in found) {
            if (m.kind !in kinds) continue
            if (out.size >= MAX_HITS) break
            val masked = PrivacyMask.of(m.kind, m.value)
            val line = if (lines) t.substring(0, m.start).count { it == '\n' } + 1 else null
            out += EvidenceHit(m.kind, m.start, m.end, masked, m.card?.brandName?.ifEmpty { null }, context(t, m.start, m.end, masked),
                m.lines, m.check, line)
        }
        return out
    }

    /**
     * Station's `masked_context`: one line around t[a:b], the value replaced by `⟦masked⟧`, the rest
     * passed through the secret and personal-data masks (on a 400-character window first, so nothing
     * cut at an edge survives), whitespace collapsed, [width] characters each side, and any run of
     * five or more digits left hidden.
     */
    fun context(text: String, a: Int, b: Int, masked: String, width: Int = CONTEXT_WIDTH): String {
        val t = PiiRules.foldDigits(text)
        var before = PiiRules.maskPii(SecretRules.redactText(t.substring(maxOf(0, a - 400), a)))
        var after = PiiRules.maskPii(SecretRules.redactText(t.substring(b, minOf(t.length, b + 400))))
        before = before.replace(SPACES, " ").takeLast(width)
        after = after.replace(SPACES, " ").take(width)
        fun hide(s: String) = LONG_DIGITS.replace(s) { "•".repeat(it.value.length) }
        val lead = if (a > 0 && before.length >= width) "…" else ""
        val tail = if (b < t.length && after.length >= width) "…" else ""
        return (lead + hide(before) + "⟦" + masked + "⟧" + hide(after) + tail).trim()
    }
}
