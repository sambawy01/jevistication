package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.WatcherFindings
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.sources.common.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatcherTrackingTest {
    private val today = LocalDate(2026, 9, 28)

    private fun netflix(month: String, amount: String) = TrackingItems.email("nf-$month-${amount.filter { it.isLetter() }}", "2026-$month-03",
        "Netflix <info@mailer.netflix.com>", "Your Netflix payment receipt", "We've charged your card.\n\nAmount paid: $amount")

    @Test
    fun aMerchantInTwoCurrenciesIsTwoRowsAndTwoTotals() {
        val items = listOf("06", "07", "08").flatMap { m -> listOf(netflix(m, "EGP 165.00"), netflix(m, "USD 9.99")) }
        val report = WatcherRun.run(items, today, null)
        assertEquals(setOf("Netflix (EGP)", "Netflix (USD)"), report.recurring.map { it.merchant }.toSet())
        assertEquals("USD", report.currencyOf["Netflix (USD)"])
        val summary = WatcherFindings.summarise(report, items, emptySet())
        assertEquals(mapOf("EGP" to 16500L, "USD" to 999L), WatcherFindings.monthlyByCurrency(summary.census.rows))
        assertEquals(setOf("EGP", "USD"), summary.census.rows.map { it.currency }.toSet())
    }

    @Test
    fun rowsWithoutACurrencyTakeTheMerchantsOneCurrency() {
        val mails = listOf("06", "07", "08").map { netflix(it, "EGP 165.00") }
        val rows = listOf("06", "07", "08").map { TrackingItems.csvRow("b1/bank.csv#row$it", "2026-$it-04", "NETFLIX.COM", 16500, "") }
        val lone = TrackingItems.csvRow("b1/bank.csv#row9", "2026-09-04", "NETFLIX.COM", 16500, "")
        val charges = WatcherRun.trackedCharges(mails + rows + lone)
        assertEquals(setOf("Netflix"), charges.map { it.merchant }.toSet(), "no Netflix (no currency) beside Netflix (EGP)")
        assertEquals(setOf("EGP"), charges.map { it.currency }.toSet())
        assertEquals(4, charges.size, "three receipts with their rows and one row alone")
        val report = WatcherRun.run(mails + rows + lone, today, null)
        assertEquals(listOf("Netflix"), report.recurring.map { it.merchant })
        assertEquals("EGP", report.currencyOf["Netflix"])

        val usd = listOf("06", "07", "08").map { netflix(it, "USD 9.99") }
        val mixed = WatcherRun.trackedCharges(mails + usd + lone)
        assertEquals(setOf("Netflix (EGP)", "Netflix (USD)", "Netflix (no currency)"), mixed.map { it.merchant }.toSet(),
                     "two known currencies: the row without one is not guessed")
    }

    @Test
    fun censusRowsCarryTheirLinesAndEveryReference() {
        val mails = listOf("06", "07", "08").map { netflix(it, "EGP 165.00") }
        val rows = listOf("06", "07", "08").map { TrackingItems.csvRow("nf$it", "2026-$it-04", "NETFLIX.COM", 16500, "EGP") }
        val items = mails + rows
        val report = WatcherRun.run(items, today, null)
        assertEquals(3, report.chargesFound, "each charge once, though read twice")
        val row = WatcherFindings.summarise(report, items, emptySet()).census.rows.single()
        assertEquals("Netflix", row.merchant)
        assertEquals(3, row.occurrences)
        assertEquals("EGP", row.currency)
        assertEquals(items.map { it.id }.toSet(), row.itemIds.toSet())
        assertEquals(listOf("Amount paid: EGP 165.00", "Amount paid: EGP 165.00", "Amount paid: EGP 165.00"), row.lines)
    }

    @Test
    fun expiryRowsCarryTheRulesKindAndTheirLine() {
        val id = TrackingItems.image("id", "2026-03-02", "بطاقة تحقيق الشخصية\nالبطاقة سارية حتى ٢٠٢٨/٠٣/١٤")
        val report = WatcherRun.run(listOf(id), today, null)
        val row = WatcherFindings.summarise(report, listOf(id), emptySet()).expiries.single()
        assertEquals("national_id", row.documentKind)
        assertEquals("2028-03-14", row.expiryIso)
        assertEquals("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", row.line)
        assertTrue(row.findingKey == null, "years away: on the timeline, not a finding")
    }

    @Test
    fun aDocumentExpiredMoreThanAYearAgoIsOnTheTimelineWithoutAnAlert() {
        val old = TrackingItems.file("licence-old", "2024-01-01", ItemKind.TEXT, "Driving licence\nValid until 15/05/2024")
        val oldReport = WatcherRun.run(listOf(old), today, null)
        val oldSummary = WatcherFindings.summarise(oldReport, listOf(old), emptySet())
        val oldRow = oldSummary.expiries.single()
        assertTrue(oldRow.findingKey == null, "expired more than a year ago: on the timeline, not an alert")
        assertTrue(oldSummary.findings.none { it.itemId == old.id })

        val recent = TrackingItems.file("licence-recent", "2026-01-01", ItemKind.TEXT, "Driving licence\nValid until 15/05/2026")
        val recentReport = WatcherRun.run(listOf(recent), today, null)
        val recentSummary = WatcherFindings.summarise(recentReport, listOf(recent), emptySet())
        val recentRow = recentSummary.expiries.single()
        assertEquals("expiry:" + recent.id, recentRow.findingKey)
        assertTrue(recentSummary.findings.any { it.itemId == recent.id })
    }

    @Test
    fun theDesktopsChargePairsStillCome() {
        val mails = listOf("06", "07", "08").map { netflix(it, "EGP 165.00") }
        val pairs = WatcherRun.charges(mails)
        assertEquals(mails.map { it.id }, pairs.map { it.first.id })
        assertEquals(16500L, pairs.first().second.amountMinor)
    }
}
