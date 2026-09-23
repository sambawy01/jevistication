package dev.loupe.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * One charge, as extracted from an email receipt, a statement row or a store record.
 *
 * Amounts are minor units (cents, pence) as a [Long]. Money is never a [Double] here: a census
 * that sums thousands of charges must not accumulate binary rounding error.
 */
data class Charge(val merchant: String, val date: LocalDate, val amountMinor: Long)

/** How often a charge repeats. */
enum class Cadence { WEEKLY, MONTHLY, QUARTERLY, ANNUAL, IRREGULAR }

/** A merchant that charges on a recognisable schedule. */
data class RecurringCharge(
    val merchant: String,
    val cadence: Cadence,
    val occurrences: Int,
    /** Median charge, in minor units — median rather than mean so one annual bill does not skew it. */
    val typicalAmountMinor: Long,
    val lastCharged: LocalDate,
    val daysSinceLastCharge: Long,
) {
    /** Total charged across the observed occurrences, in minor units. */
    fun totalMinor(): Long = typicalAmountMinor * occurrences
}

/**
 * The recurring-money census (C3): classify, then count.
 *
 * This is the census primitive pointed at the most legible value there is — every recurring
 * payment, and how long since each last moved.
 */
object RecurringMoney {

    /**
     * Finds the merchants in [charges] that bill on a schedule.
     *
     * @param minOccurrences how many charges are needed before a cadence is claimed. Two charges
     *   make a gap, not a pattern, so the default is three.
     */
    fun census(
        charges: List<Charge>,
        today: LocalDate,
        minOccurrences: Int = 3,
    ): List<RecurringCharge> {
        require(minOccurrences >= 2) { "minOccurrences must be at least 2, was $minOccurrences" }

        return charges
            .groupBy { it.merchant }
            .mapNotNull { (merchant, merchantCharges) ->
                if (merchantCharges.size < minOccurrences) return@mapNotNull null
                val sorted = merchantCharges.sortedBy { it.date }
                val last = sorted.last().date
                RecurringCharge(
                    merchant = merchant,
                    cadence = cadenceOf(sorted.map { it.date }),
                    occurrences = sorted.size,
                    typicalAmountMinor = median(sorted.map { it.amountMinor }),
                    lastCharged = last,
                    daysSinceLastCharge = last.daysUntil(today).toLong(),
                )
            }
            .sortedByDescending { it.totalMinor() }
    }

    /** Merchants whose last charge is older than [days] — the "still paying, stopped using" list. */
    fun dormant(census: List<RecurringCharge>, days: Long): List<RecurringCharge> =
        census.filter { it.daysSinceLastCharge > days }

    /** Classifies a schedule from the median gap between consecutive charges. */
    private fun cadenceOf(dates: List<LocalDate>): Cadence {
        if (dates.size < 2) return Cadence.IRREGULAR
        val gaps = dates.zipWithNext { a, b -> a.daysUntil(b).toLong() }
        return when (median(gaps)) {
            in 5L..9L -> Cadence.WEEKLY
            in 26L..35L -> Cadence.MONTHLY
            in 80L..100L -> Cadence.QUARTERLY
            in 330L..400L -> Cadence.ANNUAL
            else -> Cadence.IRREGULAR
        }
    }

    /** Lower median, so the result is always one of the observed values. */
    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
