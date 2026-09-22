package dev.loupe.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

    private val ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val NUMERIC = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})\b""")
    private val DAY_MONTH_YEAR =
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})\b""")
    private val MONTH_DAY_YEAR =
        Regex("""\b([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""")

    /**
     * Finds every date in [text], in order of appearance.
     *
     * @param dayFirst which reading to prefer for an ambiguous numeric date such as `03/04/2026`.
     *   The other reading is reported in [DateMatch.alternate].
     */
    fun find(text: String, dayFirst: Boolean = true): List<DateMatch> {
        val matches = mutableListOf<Pair<Int, DateMatch>>()

        for (m in ISO.findAll(text)) {
            val date = dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?: continue
            matches += m.range.first to DateMatch(m.value, date, "iso", ambiguous = false)
        }

        for (m in NUMERIC.findAll(text)) {
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
                text = m.value,
                date = preferred,
                pattern = if (dayFirst) "numeric-dmy" else "numeric-mdy",
                ambiguous = ambiguous,
                alternate = if (ambiguous) other else null,
            )
        }

        for (m in DAY_MONTH_YEAR.findAll(text)) {
            val month = MONTHS[m.groupValues[2].lowercase()] ?: continue
            val date = dateOrNull(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) ?: continue
            matches += m.range.first to DateMatch(m.value, date, "textual-dmy", ambiguous = false)
        }

        for (m in MONTH_DAY_YEAR.findAll(text)) {
            val month = MONTHS[m.groupValues[1].lowercase()] ?: continue
            val date = dateOrNull(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) ?: continue
            matches += m.range.first to DateMatch(m.value, date, "textual-mdy", ambiguous = false)
        }

        return matches
            .sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.text to it.date }
    }

    /** Days from [from] until [date]; negative when [date] has already passed. */
    fun daysUntil(date: LocalDate, from: LocalDate): Long = ChronoUnit.DAYS.between(from, date)

    /**
     * True when [date] falls before [from] plus [months] — the shape of a rule like "Schengen
     * requires six months of passport validity". Already-expired dates count as within.
     */
    fun expiresWithin(date: LocalDate, from: LocalDate, months: Long): Boolean {
        require(months >= 0) { "months must not be negative, was $months" }
        return date.isBefore(from.plusMonths(months))
    }

    /** A [LocalDate], or null when the numbers do not name a real day. */
    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
