package dev.loupe.engine

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecurringMoneyTest {

    private val today = LocalDate.of(2026, 9, 22)

    private fun monthly(merchant: String, months: Int, amount: Long, from: LocalDate) =
        (0 until months).map { Charge(merchant, from.plusMonths(it.toLong()), amount) }

    @Test
    fun `detects a monthly subscription`() {
        val charges = monthly("Streamly", 6, 999, LocalDate.of(2026, 4, 22))
        val census = RecurringMoney.census(charges, today)

        assertEquals(1, census.size)
        val found = census.single()
        assertEquals("Streamly", found.merchant)
        assertEquals(Cadence.MONTHLY, found.cadence)
        assertEquals(6, found.occurrences)
        assertEquals(999, found.typicalAmountMinor)
    }

    @Test
    fun `recognises weekly, quarterly and annual schedules`() {
        val weekly = (0 until 8).map { Charge("W", LocalDate.of(2026, 7, 1).plusWeeks(it.toLong()), 500) }
        val quarterly = (0 until 4).map { Charge("Q", LocalDate.of(2025, 1, 1).plusMonths(it * 3L), 4500) }
        val annual = (0 until 3).map { Charge("A", LocalDate.of(2023, 5, 1).plusYears(it.toLong()), 9900) }

        val census = RecurringMoney.census(weekly + quarterly + annual, today)
        val byMerchant = census.associateBy { it.merchant }
        assertEquals(Cadence.WEEKLY, byMerchant.getValue("W").cadence)
        assertEquals(Cadence.QUARTERLY, byMerchant.getValue("Q").cadence)
        assertEquals(Cadence.ANNUAL, byMerchant.getValue("A").cadence)
    }

    @Test
    fun `calls a scattered series irregular rather than inventing a schedule`() {
        val charges = listOf(
            Charge("Corner Shop", LocalDate.of(2026, 1, 3), 400),
            Charge("Corner Shop", LocalDate.of(2026, 2, 19), 1200),
            Charge("Corner Shop", LocalDate.of(2026, 8, 2), 250),
        )
        assertEquals(Cadence.IRREGULAR, RecurringMoney.census(charges, today).single().cadence)
    }

    @Test
    fun `two charges are a gap, not a pattern`() {
        val charges = monthly("Twice", 2, 500, LocalDate.of(2026, 8, 22))
        assertTrue(RecurringMoney.census(charges, today).isEmpty())
    }

    @Test
    fun `uses the median so one odd charge does not skew the typical amount`() {
        val charges = listOf(
            Charge("M", LocalDate.of(2026, 5, 1), 1000),
            Charge("M", LocalDate.of(2026, 6, 1), 1000),
            Charge("M", LocalDate.of(2026, 7, 1), 50000), // one annual top-up
            Charge("M", LocalDate.of(2026, 8, 1), 1000),
        )
        assertEquals(1000, RecurringMoney.census(charges, today).single().typicalAmountMinor)
    }

    @Test
    fun `finds the ones still charging but long dormant`() {
        val active = monthly("Active", 4, 999, LocalDate.of(2026, 6, 22))
        val stale = monthly("Forgotten", 4, 1499, LocalDate.of(2025, 1, 22))

        val census = RecurringMoney.census(active + stale, today)
        val dormant = RecurringMoney.dormant(census, days = 180)

        assertEquals(listOf("Forgotten"), dormant.map { it.merchant })
        assertTrue(dormant.single().daysSinceLastCharge > 180)
    }

    @Test
    fun `ranks by total spend`() {
        val cheap = monthly("Cheap", 5, 100, LocalDate.of(2026, 4, 22))
        val pricey = monthly("Pricey", 5, 5000, LocalDate.of(2026, 4, 22))
        assertEquals(
            listOf("Pricey", "Cheap"),
            RecurringMoney.census(cheap + pricey, today).map { it.merchant },
        )
    }

    @Test
    fun `an empty ledger of charges yields an empty census`() {
        assertTrue(RecurringMoney.census(emptyList(), today).isEmpty())
    }

    @Test
    fun `rejects a nonsensical minimum`() {
        assertFailsWith<IllegalArgumentException> {
            RecurringMoney.census(emptyList(), today, minOccurrences = 1)
        }
    }
}
