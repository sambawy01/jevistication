package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MimeFactsTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    private val pdf = bytes(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31)
    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
    private val zip = bytes(0x50, 0x4B, 0x03, 0x04)

    @Test
    fun `sniffs known signatures`() {
        assertEquals(ContentType.PDF, MimeFacts.sniff(pdf))
        assertEquals(ContentType.PNG, MimeFacts.sniff(png))
        assertEquals(ContentType.JPEG, MimeFacts.sniff(jpeg))
        assertEquals(ContentType.ZIP, MimeFacts.sniff(zip))
        assertEquals(ContentType.UNKNOWN, MimeFacts.sniff(bytes(0x01, 0x02)))
    }

    @Test
    fun `an empty or short buffer is unknown, not a crash`() {
        assertEquals(ContentType.UNKNOWN, MimeFacts.sniff(ByteArray(0)))
        assertEquals(ContentType.UNKNOWN, MimeFacts.sniff(bytes(0x25)))
    }

    @Test
    fun `magic bytes beat a lying extension`() {
        assertEquals(ContentType.ZIP, MimeFacts.detect("statement.pdf", zip))
        assertTrue(MimeFacts.extensionLies("statement.pdf", zip))
    }

    @Test
    fun `an honest extension does not count as lying`() {
        assertEquals(ContentType.PDF, MimeFacts.detect("statement.pdf", pdf))
        assertFalse(MimeFacts.extensionLies("statement.pdf", pdf))
    }

    @Test
    fun `falls back to the extension only when the bytes say nothing`() {
        val plain = "hello, this is a note".toByteArray()
        assertEquals(ContentType.PLAIN_TEXT, MimeFacts.detect("note.txt", plain))
        assertEquals(ContentType.UNKNOWN, MimeFacts.detect("note.unknownext", plain))
    }

    @Test
    fun `office documents are recognised as zip containers`() {
        assertEquals(ContentType.ZIP, MimeFacts.fromExtension("report.docx"))
        assertEquals(ContentType.ZIP, MimeFacts.fromExtension("budget.xlsx"))
    }

    @Test
    fun `knows which types need OCR before judging`() {
        assertTrue(ContentType.PNG.isImage)
        assertTrue(ContentType.JPEG.isImage)
        assertFalse(ContentType.PDF.isImage)
        assertFalse(ContentType.PLAIN_TEXT.isImage)
    }

    @Test
    fun `carries the mime string`() {
        assertEquals("application/pdf", ContentType.PDF.mime)
        assertEquals("image/png", ContentType.PNG.mime)
    }
}

class OcrFactsTest {

    @Test
    fun `accepts a real line of text`() {
        assertTrue(OcrFacts.hasUsableText("TOTAL 12.40 VAT 2.07"))
    }

    @Test
    fun `rejects output too short to read`() {
        assertFalse(OcrFacts.hasUsableText("ab"))
        assertFalse(OcrFacts.hasUsableText("   "))
    }

    @Test
    fun `rejects noise that is mostly stray marks`() {
        assertFalse(OcrFacts.hasUsableText("|| ~~ '' .. -- ,, ;; ::"))
    }

    @Test
    fun `thresholds are adjustable`() {
        assertTrue(OcrFacts.hasUsableText("abc", minCharacters = 3))
        assertFalse(OcrFacts.hasUsableText("abc", minCharacters = 4))
    }

    @Test
    fun `rejects nonsensical thresholds`() {
        assertFailsWith<IllegalArgumentException> { OcrFacts.hasUsableText("x", minCharacters = -1) }
        assertFailsWith<IllegalArgumentException> { OcrFacts.hasUsableText("x", minLetterRatio = 2.0) }
    }
}
