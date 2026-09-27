package dev.loupe.engine

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * A date found in text, with how it was read.
 *
 * [ambiguous] is not a detail to hide: `03/04/2026` is two different dates depending on locale,
 * and the expiry radar acting on the wrong one is exactly the failure it exists to prevent. A
 * judgment that receives an ambiguous date should treat it as uncertain rather than guess.
 */
data class DateMatch(
    /** The matched substring, kept verbatim so it can be shown back to the user. */
    val text: String,
    /** The preferred reading. */
    val date: LocalDate,
    /** Which reader matched, for the A3 per-check breakdown. */
    val pattern: String,
    /** True when another calendar-valid reading of the same text exists. */
    val ambiguous: Boolean,
    /** The other plausible reading, when [ambiguous]. */
    val alternate: LocalDate? = null,
)

/**
 * Mechanical date extraction and arithmetic (A3), the half of the expiry radar that is exact.
 *
 * Identifying *what a document is* stays a judgment; finding the dates in it and doing the
 * arithmetic does not, so none of this consults the model.
 */
object DateFacts {

    private val MONTHS: Map<String, Int> = buildMap {
        val names = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
        names.forEachIndexed { index, name ->
            put(name, index + 1)
            put(name.take(3), index + 1)
        }
        put("sept", 9)
    }

    // Matched on digit-folded text (Arabic-Indic and Persian digits read as 0-9, the owner's decision),
    // with the classes spelled out so every regex engine agrees (PortableText, Rx).
    private val ISO = Regex("""${Rx.WB_START}([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})${Rx.WB_END}""")
    private val NUMERIC = Regex("""${Rx.WB_START}([0-9]{1,2})[/.\-]([0-9]{1,2})[/.\-]([0-9]{4})${Rx.WB_END}""")
    private val DAY_MONTH_YEAR =
        Regex("""${Rx.WB_START}([0-9]{1,2})(?:st|nd|rd|th)?${Rx.SP}+([A-Za-z]{3,9})\.?,?${Rx.SP}+([0-9]{4})${Rx.WB_END}""")
    private val MONTH_DAY_YEAR =
        Regex("""${Rx.WB_START}([A-Za-z]{3,9})\.?${Rx.SP}+([0-9]{1,2})(?:st|nd|rd|th)?,?${Rx.SP}+([0-9]{4})${Rx.WB_END}""")

    /**
     * Finds every date in [text], in order of appearance.
     *
     * Digits may be ASCII, Arabic-Indic (U+0660-0669) or Persian (U+06F0-06F9); [DateMatch.text] is
     * the text as written.
     *
     * @param dayFirst which reading to prefer for an ambiguous numeric date such as `03/04/2026`.
     *   The other reading is reported in [DateMatch.alternate].
     */
    fun find(text: String, dayFirst: Boolean = true): List<DateMatch> {
        val matches = mutableListOf<Pair<Int, DateMatch>>()
        // Same length as text, so a match's range cuts the original out of text.
        val folded = PortableText.foldDigits(text)
        fun verbatim(m: MatchResult): String = text.substring(m.range)

        for (m in ISO.findAll(folded)) {
            val date = dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?: continue
            matches += m.range.first to DateMatch(verbatim(m), date, "iso", ambiguous = false)
        }

        for (m in NUMERIC.findAll(folded)) {
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            val readingDayFirst = dateOrNull(year, second, first)
            val readingMonthFirst = dateOrNull(year, first, second)
            val preferred = if (dayFirst) {
                readingDayFirst ?: readingMonthFirst
            } else {
                readingMonthFirst ?: readingDayFirst
            } ?: continue
            val other = if (dayFirst) readingMonthFirst else readingDayFirst
            val ambiguous = readingDayFirst != null &&
                readingMonthFirst != null &&
                readingDayFirst != readingMonthFirst
            matches += m.range.first to DateMatch(
                text = verbatim(m),
                date = preferred,
                pattern = if (dayFirst) "numeric-dmy" else "numeric-mdy",
                ambiguous = ambiguous,
                alternate = if (ambiguous) other else null,
            )
        }

        for (m in DAY_MONTH_YEAR.findAll(folded)) {
            val month = MONTHS[m.groupValues[2].lowercase()] ?: continue
            val date = dateOrNull(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) ?: continue
            matches += m.range.first to DateMatch(verbatim(m), date, "textual-dmy", ambiguous = false)
        }

        for (m in MONTH_DAY_YEAR.findAll(folded)) {
            val month = MONTHS[m.groupValues[1].lowercase()] ?: continue
            val date = dateOrNull(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) ?: continue
            matches += m.range.first to DateMatch(verbatim(m), date, "textual-mdy", ambiguous = false)
        }

        return matches
            .sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.text to it.date }
    }

    /** Days from [from] until [date]; negative when [date] has already passed. */
    fun daysUntil(date: LocalDate, from: LocalDate): Long = from.daysUntil(date).toLong()

    /**
     * True when [date] falls before [from] plus [months] — the shape of a rule like "Schengen
     * requires six months of passport validity". Already-expired dates count as within.
     */
    fun expiresWithin(date: LocalDate, from: LocalDate, months: Long): Boolean {
        require(months >= 0) { "months must not be negative, was $months" }
        return date < from.plus(months, DateTimeUnit.MONTH)
    }

    /** A [LocalDate], or null when the numbers do not name a real day. */
    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate(year, month, day) }.getOrNull()
}
