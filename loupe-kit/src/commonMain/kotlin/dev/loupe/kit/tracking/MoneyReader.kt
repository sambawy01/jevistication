package dev.loupe.kit.tracking

import dev.loupe.sources.common.CsvRows

/** An amount written in text: minor units, its currency (ISO 4217), the matched text verbatim and where it starts. */
data class Money(val minor: Long, val currency: String, val text: String, val start: Int)

/**
 * Amounts with their currency (spec §7.2 "Money"): EGP / ج.م / جم / جنيه / LE / L.E. / E£ / geneh alongside £ $ €,
 * Arabic and Persian digits (through the fold), thousands separators in both scripts (`,` and `٬`) and both decimal
 * marks (`.` and `٫`). A number counts only next to a currency marker, so dates, phone numbers and reference codes
 * never read as money. Amounts keep their currency; nothing is converted.
 */
object MoneyReader {
    private const val NUM = "[0-9][0-9,٬]*(?:[.٫][0-9]{1,2})?"
    private const val BEFORE = "US\\$|E£|£|\\$|€|EGP|Egp|egp|USD|usd|EUR|eur|GBP|gbp|L\\.E\\.?|LE"
    private const val AFTER = "EGP|Egp|egp|USD|usd|EUR|eur|GBP|gbp|L\\.E\\.?|LE|" +
        "جنيه(?:ا|اً)?\\s+(?:إ|ا)سترليني|جنيهات|جنيه(?:ا|اً)?(?:\\s+مصري)?|ج\\.\\s?م\\.?|جم|" +
        "geneh|Geneh|gneh|genih|ginih|€|\\$|£"
    private val PREFIXED = Regex("(?<![A-Za-z])($BEFORE)\\s?($NUM)(?![0-9])")
    private val SUFFIXED = Regex("(?<![0-9.,٫٬])($NUM)\\s?($AFTER)(?![A-Za-zء-ي])")

    /** Lines that say what the amount is ("Total", "المبلغ", "إجمالي"): the receipt's amount is on one of them. */
    private val LABEL = TrackingText.words(listOf(
        "total", "amount", "paid", "charged", "billed", "debited",
        "المبلغ", "مبلغ", "الاجمالي", "اجمالي", "القيمة", "المدفوع", "mablagh",
    ))

    /** Every amount in [text], in order of appearance. */
    fun find(text: String): List<Money> {
        val t = TrackingText.foldDigits(text)
        val out = mutableListOf<Pair<Int, Money>>()
        for (m in PREFIXED.findAll(t)) {
            val minor = number(m.groupValues[2]) ?: continue
            val numberAt = m.range.first + m.value.indexOf(m.groupValues[2])
            out += numberAt to Money(minor, code(m.groupValues[1]), text.substring(m.range.first, m.range.last + 1), m.range.first)
        }
        for (m in SUFFIXED.findAll(t)) {
            val minor = number(m.groupValues[1]) ?: continue
            out += m.range.first to Money(minor, code(m.groupValues[2]), text.substring(m.range.first, m.range.last + 1), m.range.first)
        }
        return out.distinctBy { it.first }.map { it.second }.sortedBy { it.start }
    }

    /** The amount a receipt is about: the first on a labelled line, else the first at all; null without one. */
    fun best(text: String): Money? {
        val all = find(text)
        return all.firstOrNull { LABEL.containsMatchIn(TrackingText.matchForm(lineAt(text, it.start))) } ?: all.firstOrNull()
    }

    /** ISO 4217 for a written marker; the Egyptian ones (and a bare جنيه) are EGP. */
    fun code(token: String): String {
        val t = TrackingText.matchForm(token)
        return when {
            "سترليني" in t || t == "£" || t == "gbp" -> "GBP"
            t == "$" || t == "us$" || t == "usd" -> "USD"
            t == "€" || t == "eur" -> "EUR"
            else -> "EGP"
        }
    }

    /** The line of [text] that holds [index], verbatim, without its line break. */
    fun lineAt(text: String, index: Int): String {
        val start = if (index <= 0) 0 else text.lastIndexOf('\n', index - 1) + 1
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    private fun number(raw: String): Long? = CsvRows.parseNumber(raw.trimEnd(',', '٬', '.', '٫'))
}
