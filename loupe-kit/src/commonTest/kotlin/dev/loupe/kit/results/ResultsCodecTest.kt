package dev.loupe.kit.results

import dev.loupe.kit.mail.MailTriage
import dev.loupe.kit.mail.MailRow
import dev.loupe.kit.mail.MailSummary
import dev.loupe.kit.mail.PhishReason
import dev.loupe.kit.mail.ProviderCategory
import dev.loupe.kit.privacy.PrivacyCheck
import dev.loupe.kit.privacy.PrivacyItemListener
import dev.loupe.kit.site.NotCounted
import dev.loupe.kit.site.SiteContextInfo
import dev.loupe.kit.site.SiteFact
import dev.loupe.kit.site.SiteReason
import dev.loupe.kit.watchers.FindingVerdict
import dev.loupe.kit.watchers.SAMPLE_DIR
import dev.loupe.kit.watchers.WatcherFindings
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.kit.watchers.sampleReaders
import dev.loupe.sources.common.SourceFs
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

/**
 * The saved results of the three checks (2026-09-28: the app loads them at launch instead of re-running): every
 * field survives a write and a read, on the JVM and the iOS simulator; a file this build cannot read is no result.
 */
class ResultsCodecTest {
    private val today = LocalDate(2026, 9, 23)

    private val items: List<SourceItem> by lazy {
        SourceScanner(sampleReaders(), TimeZone.UTC).scan(
            listOf(
                SourceRoot("sample", SourceType.FOLDER, "$SAMPLE_DIR/documents", "sample:documents/"),
                SourceRoot("sample", SourceType.MAIL_EXPORT, "$SAMPLE_DIR/mail", "sample:mail/"),
            ),
        ).items
    }

    private fun raw(item: SourceItem): String? =
        if (item.messageIndex == null && item.path.endsWith(".eml")) SourceFs.readBytes(item.path).decodeToString() else null

    @Test
    fun watcherSummariesSurviveAWriteAndARead() {
        val summary = WatcherFindings.summarise(WatcherRun.run(items, today, null), items, setOf("sample"))
        assertTrue(summary.findings.isNotEmpty() && summary.census.rows.isNotEmpty() && summary.expiries.isNotEmpty())
        // A verdict and an irregular merchant (null monthly) are kept too.
        val answered = summary.copy(
            findings = summary.findings.mapIndexed { i, f -> if (i == 0) f.copy(verdict = FindingVerdict.CONFIRMED) else f },
            census = summary.census.copy(rows = summary.census.rows + summary.census.rows.first().copy(merchant = "Odd \"quote\" é", monthlyMinor = null)),
        )
        assertEquals(answered, ResultsCodec.decodeWatchers(ResultsCodec.encodeWatchers(answered)))
    }

    @Test
    fun theTrackingFieldsSurviveAWriteAndARead() {
        val summary = WatcherFindings.summarise(WatcherRun.run(items, today, null), items, setOf("sample"))
        // The tracking engine's fields, set whether or not the sample fills them: currency, the lines read, the kind.
        val row = summary.census.rows.first()
        val tracked = summary.copy(
            census = summary.census.copy(
                rows = summary.census.rows + row.copy(merchant = "Vodafone", currency = "EGP", lines = listOf("تم خصم 250.00 جنيه", "Line \"two\"")) +
                    row.copy(merchant = "No currency", currency = "", lines = emptyList()),
            ),
            expiries = summary.expiries + summary.expiries.first().copy(itemId = "x:passport", documentKind = "passport"),
        )
        val back = ResultsCodec.decodeWatchers(ResultsCodec.encodeWatchers(tracked))
        assertEquals(tracked, back)
        assertEquals("EGP", back!!.census.rows.first { it.merchant == "Vodafone" }.currency)
        assertEquals("passport", back.expiries.last().documentKind)
    }

    @Test
    fun watcherResultsSavedBeforeTheTrackingFieldsStillRead() {
        val summary = WatcherFindings.summarise(WatcherRun.run(items, today, null), items, setOf("sample"))
        val old = ResultsCodec.encodeWatchers(summary)
            .replace(Regex(""","currency":"[^"]*""""), "")
            .replace(Regex(""","lines":\[[^\]]*]"""), "")
            .replace(Regex(""","documentKind":(null|"[^"]*")"""), "")
        assertTrue("currency" !in old && "\"lines\"" !in old && "documentKind" !in old, "the fields were stripped")
        val back = ResultsCodec.decodeWatchers(old)
        assertEquals(summary.copy(
            census = summary.census.copy(rows = summary.census.rows.map { it.copy(currency = "", lines = emptyList()) }),
            expiries = summary.expiries.map { it.copy(documentKind = null) },
        ), back)
    }

    @Test
    fun privacySummariesSurviveAWriteAndARead() {
        val summary = PrivacyCheck.summarise(items, setOf("sample"), emptyMap(), today)
        assertTrue(summary.findings.any { it.duplicates != null }, "the sample has a duplicate receipt")
        assertEquals(summary, ResultsCodec.decodePrivacy(ResultsCodec.encodePrivacy(summary)))
    }

    @Test
    fun mailSummariesSurviveAWriteAndARead() {
        val summary = MailTriage.summarise(items, ::raw, emptyMap())
        assertTrue(summary.rows.any { it.phishing } && summary.rows.any { it.linkChecks.isNotEmpty() })
        assertEquals(summary, ResultsCodec.decodeMail(ResultsCodec.encodeMail(summary)))
        // The fields the sample leaves empty: a provider category, reason params, facts, cues not counted, a context.
        val row = summary.rows.first { it.linkChecks.isNotEmpty() }
        val check = row.linkChecks.first()
        val rich = row.copy(
            providerCategory = ProviderCategory("promotions", "gmail", "Gmail"),
            verdict = row.verdict.copy(reasons = row.verdict.reasons + PhishReason("online_age", "New domain", 10, mapOf("days" to "3"), "online"), gates = listOf("g")),
            personVerdict = "safe", urgentCue = "act now", phishingCue = null, dateIso = null,
            linkChecks = listOf(
                check.copy(
                    verdict = check.verdict.copy(
                        reasons = check.verdict.reasons + SiteReason("x", "y", 5, "online", mapOf("k" to "v")),
                        facts = listOf(SiteFact("age", "good", "Registered 12 years ago", mapOf("years" to "12"))),
                        notCounted = listOf(NotCounted("laya_prize", "prize", 15, "alone", "It was the only sign.")),
                        context = SiteContextInfo("shop", true, "stripe"),
                    ),
                ),
            ),
        )
        val richer = MailSummary(listOf<MailRow>(rich) + summary.rows, summary.webLinks)
        assertEquals(richer, ResultsCodec.decodeMail(ResultsCodec.encodeMail(richer)))
    }

    @Test
    fun aFileThisBuildCannotReadIsNoResult() {
        val privacy = ResultsCodec.encodePrivacy(PrivacyCheck.summarise(items, emptySet(), emptyMap(), today))
        assertNull(ResultsCodec.decodePrivacy(privacy.dropLast(5)), "a torn write")
        assertNull(ResultsCodec.decodeWatchers(privacy), "another check's file")
        assertNull(ResultsCodec.decodePrivacy(privacy.replace("\"version\":1", "\"version\":2")), "a newer version")
        assertNull(ResultsCodec.decodeMail(""))
    }

    @Test
    fun thePrivacyCheckStopsBetweenItemsWhenCancelled() {
        var seen = 0
        val listener = object : PrivacyItemListener {
            override fun onItem(done: Int, total: Int, findings: Int, skipped: Boolean) { seen = done }
            override fun onDuplicates() { error("the duplicate pass must not run after a cancel") }
            override fun isCancelled(): Boolean = seen >= 3
        }
        val cut = PrivacyCheck.findings(items, emptySet(), today, listener)
        assertEquals(3, seen)
        assertTrue(cut.size < PrivacyCheck.findings(items, emptySet(), today).size)
    }
}
