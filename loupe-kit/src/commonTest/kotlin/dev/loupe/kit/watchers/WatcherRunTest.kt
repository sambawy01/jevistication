package dev.loupe.kit.watchers

import dev.loupe.engine.Cadence
import dev.loupe.engine.ImpersonationReason
import dev.loupe.persistence.CorrectionKey
import dev.loupe.sources.common.PlatformExtractors
import dev.loupe.sources.common.SourceItem
import dev.loupe.sources.common.SourceRoot
import dev.loupe.sources.common.SourceScanner
import dev.loupe.sources.common.SourceType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform's real PDF reader (PDFBox on the JVM, PDFKit on the iOS simulator). */
internal expect fun sampleReaders(): PlatformExtractors

/**
 * The shared watcher orchestration over the shipped synthetic sample, on the JVM and the iOS
 * simulator alike: the same planted findings the desktop's `WatchersTest` asserts.
 */
class WatcherRunTest {
    private val today = LocalDate(2026, 9, 23)

    private val items: List<SourceItem> by lazy {
        SourceScanner(sampleReaders(), TimeZone.UTC).scan(
            listOf(
                SourceRoot("sample", SourceType.FOLDER, "$SAMPLE_DIR/documents", "sample:documents/"),
                SourceRoot("sample", SourceType.MAIL_EXPORT, "$SAMPLE_DIR/mail", "sample:mail/"),
            ),
        ).items
    }

    @Test
    fun theWatchersFireOnWhatTheSampleHides() {
        val report = WatcherRun.run(items, today, backend = null)

        val passport = report.expiryCandidates.single { "passport" in it.item.name }
        assertEquals(LocalDate(2027, 1, 14), passport.expiry)
        assertEquals(113, passport.daysRemaining)
        assertTrue(passport.breachesRule)
        assertNull(report.expiryAlerts, "no model, so no model half")

        val streamflix = report.recurring.single { it.merchant == "Streamflix" }
        assertEquals(Cadence.MONTHLY, streamflix.cadence)
        assertEquals(4, streamflix.occurrences)
        assertEquals(999, streamflix.typicalAmountMinor)
        assertEquals(Cadence.MONTHLY, report.recurring.single { it.merchant == "CloudBox" }.cadence)

        val premium = report.termChanges.flatMap { it.changes }.single { it.label == "annual premium" }
        assertEquals(45000, premium.beforeMinor)
        assertEquals(55350, premium.afterMinor)
        assertEquals(23.0, premium.percentChange!!, 1e-9)

        // "Mum" on another domain is reported; CloudBox's second address on its own domain is not (Trap).
        val impostor = report.impersonation.single()
        assertEquals("mum.family@quickmail.example", impostor.item.email!!.fromAddress)
        assertTrue(impostor.signals.any { it.reason == ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS })

        assertTrue(report.fraud.isNotEmpty())
        assertTrue(report.fraud.all { it.item.email!!.fromAddress == "service@paypa1-secure.example" }, report.fraud.map { it.what }.toString())
        assertTrue(report.fraud.any { it.what.startsWith("link http://paypal.account-verify.example") })
    }

    @Test
    fun noWatcherWordingEverBlesses() {
        val summary = WatcherFindings.summarise(WatcherRun.run(items, today, null), items, setOf("sample"))
        val words = summary.findings.flatMap { it.evidence + it.title + it.why }.map { it.lowercase() }
        assertTrue(words.none { w -> listOf("is safe", "legitimate", "trusted", "all clear", "all-clear").any { it in w } }, words.toString())
    }

    @Test
    fun findingsCarryTheEvidenceAndAreLabelledSample() {
        val summary = WatcherFindings.summarise(WatcherRun.run(items, today, null), items, setOf("sample"))
        val f = summary.findings
        assertTrue(f.all { it.sample }, "every finding here comes from the sample")

        val premium = f.single { it.watcher == WatcherKind.TERM_CHANGE && it.key.endsWith(":annual premium") }
        assertEquals("Annual premium up 23%", premium.title)
        assertEquals("Annual premium: £450.00 → £553.50 (+23%)", premium.evidence.first())
        assertTrue(premium.itemId.endsWith("home-insurance-renewal-2026.pdf"))
        assertTrue(premium.otherItemId!!.endsWith("home-insurance-renewal-2025.pdf"))

        val passport = f.single { it.watcher == WatcherKind.EXPIRY && "passport" in it.itemName }
        assertTrue(passport.title.endsWith("expires in 113 days"), passport.title)
        assertTrue(passport.evidence.any { "Date of expiry: 14 JAN 2027" in it }, passport.evidence.toString())
        assertTrue("not loaded" in passport.why)

        val mum = f.single { it.watcher == WatcherKind.IMPERSONATION }
        assertTrue(mum.evidence.first().contains("mum.family@quickmail.example"))

        val paypal = f.single { it.watcher == WatcherKind.SITE_FRAUD }
        assertTrue(paypal.evidence.any { "paypal.account-verify.example" in it })

        // Most urgent first; the census sums the two monthly subscriptions.
        assertEquals(WatcherKind.IMPERSONATION, f.first().watcher)
        assertEquals(f.sortedBy { it.watcher.ordinal }.map { it.key }, f.map { it.key })
        val census = summary.census
        assertTrue(census.sample)
        assertEquals(census.rows.sumOf { it.monthlyMinor ?: 0 }, census.monthlyTotalMinor)
        assertEquals(999, census.rows.single { it.merchant == "Streamflix" }.monthlyMinor)
    }

    @Test
    fun verdictsAreCorrectionsAndSetFindingsAside() {
        val report = WatcherRun.run(items, today, null)
        val first = WatcherFindings.summarise(report, items, setOf("sample"))
        val premium = first.findings.single { it.key.endsWith(":annual premium") }
        val mum = first.findings.single { it.watcher == WatcherKind.IMPERSONATION }

        val dismiss = WatcherFindings.correction(mum, FindingVerdict.DISMISSED, "2026-09-23T10:00:00Z")
        assertEquals("watcher:impersonation", dismiss.judgmentId)
        assertEquals("dismissed", dismiss.label)
        val confirm = WatcherFindings.correction(premium, FindingVerdict.CONFIRMED, "2026-09-23T10:00:01Z")
        assertTrue(confirm.confirmed)

        val index = listOf(dismiss, confirm).associate { CorrectionKey(it.judgmentId, it.criteriaHash, it.itemId) to it.label!! }
        val after = WatcherFindings.summarise(report, items, setOf("sample"), index)
        assertTrue(after.findings.none { it.key == mum.key })
        assertEquals(1, after.setAside)
        assertEquals(FindingVerdict.CONFIRMED, after.findings.single { it.key == premium.key }.verdict)

        // Not the sample: nothing is labelled sample.
        assertTrue(WatcherFindings.summarise(report, items, emptySet()).findings.none { it.sample })
    }

    @Test
    fun formatting() {
        assertEquals("553.50", WatcherFindings.money(55350))
        assertEquals("+23", WatcherFindings.signed(23.0))
        assertEquals("-4.5", WatcherFindings.signed(-4.5))
        assertEquals(433, WatcherFindings.monthly(Cadence.WEEKLY, 100))
    }
}
