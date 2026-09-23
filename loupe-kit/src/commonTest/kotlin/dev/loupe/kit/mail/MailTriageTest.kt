package dev.loupe.kit.mail

import dev.loupe.kit.watchers.SAMPLE_DIR
import dev.loupe.kit.watchers.sampleReaders
import dev.loupe.persistence.CorrectionKey
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceFs
import dev.loupe.sources.common.SourceItem
import dev.loupe.sources.common.SourceRoot
import dev.loupe.sources.common.SourceScanner
import dev.loupe.sources.common.SourceType
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mail triage over Station's realistic cases (labels from the keyword rules, evidence for phishing,
 * the provider for spam) and over the shipped sample inbox, on the JVM and the iOS simulator.
 */
class MailTriageTest {
    private fun message(c: EmailCase) = MailMessage(
        id = c.id, sender = c.sender, subject = c.subject, dateIso = null, body = c.body,
        text = "From: ${c.sender}\nSubject: ${c.subject}\n\n${c.body}", replyTo = c.replyTo, links = c.links,
        provider = "gmail", labels = c.labels,
    )

    private fun row(id: String) = MailTriage.triage(message(CASES.getValue(id)))

    // Station's test_rows_for_the_bug_report_emails / test_rows_phishing_label_and_suppression, with
    // the keyword rules answering instead of Laya: the labels follow the evidence.
    @Test
    fun rowsFollowTheEvidence() {
        val alert = row("google-alert")
        assertFalse(alert.phishing)
        assertFalse(alert.spam)
        assertEquals("known_sender", alert.verdict.reasons[0].code)
        assertFalse(MailClassify.PHISHING_LABEL in alert.labels)
        val receipt = row("godaddy-receipt")
        assertFalse(receipt.phishing)
        assertEquals("updates", receipt.providerCategory?.key)
        assertTrue(receipt.transactional)
        for (cid in listOf("jomashop-sale", "newsletter-fr", "cloudflare-event")) {
            val news = row(cid)
            assertFalse(news.phishing, cid)
            assertEquals(ProviderCategory("promotions", "gmail", "Gmail"), news.providerCategory)
            assertFalse(MailClassify.URGENT_LABEL in news.labels || MailClassify.NEEDS_REPLY_LABEL in news.labels, cid)
            assertFalse(news.spam, cid)                    // Promotions alone is bulk, not spam (no text reading >= 0.9)
        }
        // the provider's own Spam verdict labels spam, and is not weak
        val lottery = row("lottery-spam")
        assertTrue(lottery.spam)
        assertTrue(MailClassify.SPAM_LABEL in lottery.labels && MailClassify.SPAM_LABEL !in lottery.weakLabels)
        // the rules' scam wording ("you have won", "claim your prize") adds the text weight next to
        // the freemail reply-to and the throw-away TLD, as Laya's reading does in Station
        assertTrue(lottery.phishing)
        assertEquals("phishing", lottery.categoryKey)
    }

    @Test
    fun phishingRowsCarryTheLabelAndTheirSignals() {
        val r = row("paypal-phish")
        assertTrue(r.phishing && r.verdict.score >= Phishing.PHISHING_AT)
        assertTrue(MailClassify.PHISHING_LABEL in r.labels)
        assertFalse(MailClassify.NEEDS_REPLY_LABEL in r.labels || MailClassify.URGENT_LABEL in r.labels) // never floats a scam to the top
        assertEquals(MailSection.PHISHING, r.section)
        assertTrue(r.signals.any { it.code == "sender_lookalike_brand" })
        assertEquals("within 24 hours", r.urgentCue)
        assertTrue(row("cib-phish-ar").verdict.codes.contains("sender_brand_in_domain_bait"))
        assertTrue(row("dhl-phish").phishing)
        // a "phishing" category from the words alone, without evidence, proposes nothing for it
        val alert = MailTriage.triage(message(CASES.getValue("google-alert")).copy(body = "Verify your account now", text = "Verify your account now"))
        assertEquals("phishing", alert.categoryKey)
        assertFalse(alert.phishing)
        assertFalse(MailClassify.PHISHING_LABEL in alert.labels)
    }

    @Test
    fun customerMailNeedsAReply() {
        val r = row("customer-catering")
        assertTrue(MailClassify.NEEDS_REPLY_LABEL in r.labels)
        assertEquals(MailSection.NEEDS_REPLY, r.section)
        val inv = row("supplier-invoice")
        assertEquals("bank_payment", inv.categoryKey)    // "bank" is the earlier rule, as in Station's map order
        assertTrue("Laya/Bank Payment" in inv.labels && "Laya/Bank Payment" in inv.weakLabels)
    }

    @Test
    fun labelNamingIsStations() {
        assertEquals("Laya/Supplier Invoice", MailClassify.choiceLabel("supplier_invoice"))
        assertEquals("Laya/Needs Reply", MailClassify.questionLabel("needs_reply"))
        assertEquals("Laya/Phishing", MailClassify.questionLabel("is_phishing"))
        assertEquals("Laya/VAT Return", MailClassify.choiceLabel("vat_return"))
        assertFalse(MailClassify.validLabel("Laya/a*b"))
    }

    @Test
    fun sortPutsPhishingFirstThenUrgency() {
        val rows = MailTriage.sortRows(EMAIL_CASES.map { MailTriage.triage(message(it)) })
        val firstSafe = rows.indexOfFirst { !it.phishing }
        assertTrue(rows.drop(firstSafe).none { it.phishing })
        val safe = rows.drop(firstSafe).map { it.urgencyLevel }
        assertEquals(safe.sortedDescending(), safe)
    }

    // ------------------------------------------------------------------ the shipped sample
    private val sample: List<SourceItem> by lazy {
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
    fun theSamplePaypalPhishingIsFlaggedWithItsSignals() {
        val summary = MailTriage.summarise(sample, ::raw, emptyMap())
        assertEquals(sample.count { it.kind == ItemKind.EMAIL }, summary.rows.size)
        val paypal = summary.rows.first { it.itemId.endsWith("phishing-paypal.eml") }
        assertTrue(paypal.phishing, paypal.verdict.toString())
        assertEquals(paypal, summary.rows.first())
        val codes = paypal.verdict.codes
        assertTrue(codes.containsAll(setOf("sender_lookalike_brand", "display_brand_mismatch", "link_brand_in_subdomain")), codes.toString())
        assertEquals("PayPal", paypal.verdict.reasons.first { it.code == "sender_lookalike_brand" }.params["brand"])
        // the anchor came from the HTML part: "Verify now" -> paypal.account-verify.example
        assertTrue(paypal.linkChecks.any { it.url == "http://paypal.account-verify.example/login" && it.warn })
        assertEquals("within 24 hours", paypal.urgentCue)
        assertEquals("verify your identity", paypal.phishingCue)
        assertTrue(paypal.signals.map { it.kind }.containsAll(listOf("phishing", "wording", "site")))
        // nothing else in the sample inbox is phishing
        assertEquals(1, summary.phishingCount, summary.rows.filter { it.phishing }.map { it.itemId }.toString())
        assertEquals(listOf(MailSection.PHISHING), summary.sections.take(1))
    }

    @Test
    fun correctionsDecideAndUndo() {
        val first = MailTriage.summarise(sample, ::raw, emptyMap())
        val paypal = first.rows.first { it.phishing }
        val safe = MailTriage.markSafe(paypal, "2026-09-23T10:00:00Z")
        assertEquals(MailTriage.SAFE, safe.label)
        val key = CorrectionKey(MailTriage.JUDGMENT_ID, MailTriage.CRITERIA, paypal.itemId)
        val after = MailTriage.summarise(sample, ::raw, mapOf(key to MailTriage.SAFE)).row(paypal.itemId)!!
        assertFalse(after.phishing)
        assertEquals(MailTriage.SAFE, after.personVerdict)
        assertFalse(MailClassify.PHISHING_LABEL in after.labels)
        assertTrue(after.verdict.flag)                     // the evidence is still shown
        assertNull(MailTriage.retraction(paypal, "2026-09-23T10:01:00Z").label)
        // confirming a clean one makes it phishing
        val council = first.rows.first { it.itemId.endsWith("council-tax-bill.eml") }
        assertFalse(council.phishing)
        val confirmed = MailTriage.summarise(sample, ::raw, mapOf(CorrectionKey(MailTriage.JUDGMENT_ID, MailTriage.CRITERIA, council.itemId) to MailTriage.PHISHING))
        assertTrue(confirmed.row(council.itemId)!!.phishing)
        assertTrue(MailTriage.confirmPhishing(council, "t").confirmed)
    }

    @Test
    fun webLinkItemsGetSiteChecks() {
        val shared = SourceItem(
            id = "inbox:link.txt", sourceId = "inbox", kind = ItemKind.TEXT, path = "/tmp/link.txt", messageIndex = null,
            name = "link.txt", text = "Link shared to Loupe: https://paypal-secure-login.com/verify\n", hasText = true,
            textTruncated = false, sizeBytes = 60, contentHash = "x", mime = "text/plain", date = null, dateOrigin = null,
            email = null, facts = emptyMap(),
        )
        val checks = MailTriage.webLinks(listOf(shared))
        val c = assertNotNull(checks.singleOrNull())
        assertEquals("https://paypal-secure-login.com/verify", c.check.url)
        assertTrue("brand_in_domain_bait" in c.check.station.reasons.map { it.code })
        assertTrue(c.check.warn)
    }
}
