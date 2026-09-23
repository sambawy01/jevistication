package dev.loupe.sources.common

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The common scanner over the shipped sample, on the JVM and on the iOS simulator alike. */
class SampleScanTest {
    /** Stands in for PDFKit/PDFBox and ImageIO/metadata-extractor: fixed answers, so this tests the scanner. */
    private object FakeExtractors : PlatformExtractors {
        override fun readPdf(path: String): PdfInfo =
            if (path.endsWith("scanned-letter.pdf")) PdfInfo("", 1, null, null) else PdfInfo("A text layer for $path", 1, "2026-09-01", "fake")

        override fun readImage(path: String): ImageInfo = ImageInfo(mapOf("dimensions" to "1x1"), null)
    }

    private fun scan(prefix: String? = null) = SourceScanner(FakeExtractors, TimeZone.UTC).scan(
        listOf(
            SourceRoot("docs", SourceType.FOLDER, "$SAMPLE_DIR/documents", prefix),
            SourceRoot("mail", SourceType.MAIL_EXPORT, "$SAMPLE_DIR/mail", prefix),
        ),
    )

    @Test
    fun readsEverySupportedKind() {
        val result = scan()
        val kinds = result.countsByKind()
        for (k in ItemKind.entries) assertTrue((kinds[k] ?: 0) > 0, "no $k: $kinds")
        assertEquals(24, kinds[ItemKind.EMAIL])
        assertEquals(3, result.skipped.size, result.skipped.toString())
        assertEquals(setOf("unsupported type", "extension lies"), result.skippedByReason().keys)
        assertEquals(1, result.duplicates)
        assertTrue(result.unavailable.isEmpty())
    }

    @Test
    fun phishingMailFacts() {
        val phish = scan().items.single { it.path.endsWith("phishing-paypal.eml") }
        val email = assertNotNull(phish.email)
        assertEquals("service@paypa1-secure.example", email.fromAddress)
        assertEquals("2026-09-21", phish.dateIso)
        assertEquals(1, Regex("unusual activity").findAll(phish.text).count())
        assertEquals(64, phish.contentHash.length)
    }

    @Test
    fun mboxMessagesAreItemsWithStableIds() {
        val mbox = scan("sample:").items.filter { it.path.endsWith(".mbox") }
        assertEquals((1..14).toList(), mbox.map { it.messageIndex })
        assertEquals("sample:subscriptions-2026.mbox#1", mbox.first().id)
        assertEquals("CloudBox Plus — payment received", mbox.first { it.email?.fromAddress == "receipts@cloudbox.example" }.name)
    }

    @Test
    fun pdfWithoutTextLayerHasNoText() {
        val scanPdf = scan().items.single { it.path.endsWith("scanned-letter.pdf") }
        assertFalse(scanPdf.hasText)
        assertEquals("", scanPdf.text)
        assertNull(scanPdf.duplicateOf)
    }

    @Test
    fun cacheRoundTripsEveryField() {
        val result = scan("sample:")
        val lib = SourceLibrary(newTempDir())
        assertNull(lib.cached("sample"))
        lib.store("sample", result, 1_000L)
        val back = assertNotNull(lib.cached("sample"))
        assertEquals(result, back.result)
        assertEquals(1_000L, back.scannedAtEpochMillis)
        assertTrue(lib.isEnabled("sample", default = true))
        assertEquals(result.items.size, lib.items(listOf("sample")).size)
        lib.setEnabled("sample", false)
        assertFalse(lib.isEnabled("sample", default = true))
        assertTrue(lib.items(listOf("sample")).isEmpty())
    }
}
