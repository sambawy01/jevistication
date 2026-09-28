package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.WatcherFindings
import dev.loupe.kit.watchers.WatcherRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One tracker at a time, from the item a source yields, through `WatcherRun`, to the `WatcherFindings` rows the Money
 * and Documents screens show, with their references (spec §7.3 "source item → watcher → Home card → detail →
 * reference"; the Home card and detail are the shell and step 4).
 */
class TrackingEndToEndTest {
    private val today = TrackingFixtures.TODAY
    private val fixtures by lazy { TrackingFixtures.load().associateBy { it.id } }
    private fun items(vararg ids: String) = ids.map { fixtures.getValue(it).item }

    @Test
    fun aNetflixReceiptAndItsStatementRowAreOneChargeWithBothReferences() {
        val mails = items("netflix-en-2026-06", "netflix-en-2026-07", "netflix-en-2026-08")
        val statement = TrackingFixtures.statementRows()
        val all = mails + statement
        val summary = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet())
        val netflix = summary.census.rows.single { it.merchant == "Netflix" }
        assertEquals(3, netflix.occurrences)
        assertEquals("monthly", netflix.cadence)
        assertEquals("EGP", netflix.currency)
        assertEquals(16500L, netflix.monthlyMinor)
        val netflixRows = statement.filter { it.facts["merchant"] == "NETFLIX.COM" }.map { it.id }
        assertEquals((mails.map { it.id } + netflixRows).toSet(), netflix.itemIds.toSet(), "every receipt and every statement row")
        assertEquals(3, netflix.lines.size)
        assertTrue(netflix.lines.all { "165.00" in it }, netflix.lines.toString())
        val shahid = summary.census.rows.single { it.merchant == "Shahid" }
        assertEquals(mapOf("EGP" to 16500L + shahid.monthlyMinor!!), WatcherFindings.monthlyByCurrency(summary.census.rows))
        assertTrue(summary.census.rows.none { it.merchant.contains("SALARY") || it.merchant.contains("CARREFOUR") })
    }

    @Test
    fun calendarPhotoAndFrancoChargesReachTheCensus() {
        val all = items("gym-en-2026-06", "gym-en-2026-07", "gym-en-2026-08", "we-eg-2026-06", "we-eg-2026-07", "we-eg-2026-08",
                        "anghami-franco-2026-06", "anghami-franco-2026-07", "anghami-franco-2026-08",
                        "icloud-en-2026-06", "icloud-en-2026-07", "icloud-en-2026-08")
        val rows = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet()).census.rows.associateBy { it.merchant }
        assertEquals(setOf("Gym membership", "WE", "Anghami", "iCloud"), rows.keys)
        assertEquals(listOf("Paid the monthly membership: EGP 800"), rows.getValue("Gym membership").lines.distinct())
        assertTrue(rows.getValue("Gym membership").itemIds.all { it.startsWith("calendar:") })
        assertTrue(rows.getValue("WE").itemIds.all { it.startsWith("photos:") })
        assertEquals("USD", rows.getValue("iCloud").currency)
        assertEquals(99L, rows.getValue("iCloud").monthlyMinor)
    }

    @Test
    fun anEgyptianIdIsOnTheTimelineWithItsKindAndLine() {
        val all = items("national-id-ar")
        val row = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet()).expiries.single()
        assertEquals("photos:national-id-ar", row.itemId)
        assertEquals("2028-03-14", row.expiryIso)
        assertEquals("national_id", row.documentKind)
        assertEquals("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", row.line)
        assertNull(row.findingKey, "years away: a timeline row, not a warning")
        assertEquals(ExpiryBucket.LATER, ExpiryBucket.of(row.daysRemaining))
    }

    @Test
    fun aCarLicenceInsideTheRuleIsAWarningWithTheLineItWasReadFrom() {
        val all = items("car-licence-ar")
        val summary = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet())
        val row = summary.expiries.single()
        assertEquals("car_licence", row.documentKind)
        assertTrue(row.breachesRule)
        val finding = summary.findings.single { it.key == row.findingKey }
        assertEquals("تاريخ الانتهاء: ١٢/١١/٢٠٢٦", finding.evidence.first())
        assertEquals("photos:car-licence-ar", finding.itemId)
    }

    @Test
    fun theWholeSetSaysNothingItShouldNot() {
        val all = fixtures.values.map { it.item } + TrackingFixtures.statementRows()
        val summary = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet())
        val words = summary.findings.flatMap { it.evidence + it.title + it.why }.map { it.lowercase() }
        assertTrue(words.none { "laya" in it }, "no user-facing Laya")
        assertTrue(words.none { w -> listOf("is safe", "legitimate", "trusted", "all clear", "all-clear").any { it in w } }, words.toString())
    }
}
