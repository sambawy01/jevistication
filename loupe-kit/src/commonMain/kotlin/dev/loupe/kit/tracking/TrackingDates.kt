package dev.loupe.kit.tracking

import dev.loupe.engine.DateFacts
import dev.loupe.engine.DateMatch
import kotlinx.datetime.LocalDate

/**
 * Dates for the trackers: `DateFacts.find` over digit-folded text (so ٢٠٢٦ reads), plus the two forms Egyptian
 * documents use that `DateFacts` does not: year first with slashes or dots (٢٠٢٨/٠٣/١٤) and Arabic month names
 * (٣٠ يونيو ٢٠٢٧). Ambiguous numeric dates keep `DateFacts`' reading and alternate.
 */
object TrackingDates {
    private val YMD = Regex("(?<![0-9])([0-9]{4})[/.]([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9])")
    private val AR_MONTHS: Map<String, Int> = mapOf(
        "يناير" to 1, "فبراير" to 2, "مارس" to 3, "ابريل" to 4, "مايو" to 5, "يونيو" to 6, "يونيه" to 6,
        "يوليو" to 7, "يوليه" to 7, "اغسطس" to 8, "سبتمبر" to 9, "اكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
    )
    private val AR_DMY = Regex("(?<![0-9])([0-9]{1,2})\\s+(${AR_MONTHS.keys.joinToString("|")})\\s+([0-9]{4})(?![0-9])")

    /** Every date in [text], in order of appearance, each date once. */
    fun find(text: String): List<DateMatch> {
        val folded = TrackingText.foldDigits(text)
        val form = TrackingText.matchForm(text)
        val found = mutableListOf<Pair<Int, DateMatch>>()
        for (m in DateFacts.find(folded)) found += folded.indexOf(m.text).coerceAtLeast(0) to m
        for (m in YMD.findAll(folded)) {
            val date = date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: continue
            found += m.range.first to DateMatch(text.substring(m.range.first, m.range.last + 1), date, "ymd-slash", ambiguous = false)
        }
        for (m in AR_DMY.findAll(form)) {
            val month = AR_MONTHS[m.groupValues[2]] ?: continue
            val date = date(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) ?: continue
            found += m.range.first to DateMatch(text.substring(m.range.first, m.range.last + 1), date, "arabic-month", ambiguous = false)
        }
        return found.sortedBy { it.first }.map { it.second }.distinctBy { it.date to it.alternate }
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()
}
