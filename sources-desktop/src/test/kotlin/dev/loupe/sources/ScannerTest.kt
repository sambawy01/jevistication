package dev.loupe.sources

import dev.loupe.engine.ContentHash
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerTest {

    @TempDir
    lateinit var tmp: Path

    private val scanner = Scanner(zone = ZoneOffset.UTC)

    private fun sample(): Pair<Path, ScanResult> {
        val root = SampleData.materialize(tmp.resolve("sample"))
        val result = scanner.scan(
            listOf(
                SourceSpec("docs", SourceType.FOLDER, root.resolve(SampleData.DOCUMENTS)),
                SourceSpec("mail", SourceType.MAIL_EXPORT, root.resolve(SampleData.MAIL)),
            ),
        )
        return root to result
    }

    private fun ScanResult.byName(name: String) = items.single { it.path.name == name && it.messageIndex == null }

    @Test
    fun `the sample index lists exactly the files that ship`() {
        val root = SampleData.materialize(tmp.resolve("s"))
        val onDisk = Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.sorted().toList()
        }
        assertEquals(SampleData.files.sorted(), onDisk)
    }

    @Test
    fun `reads every supported type in the sample`() {
        val (_, result) = sample()
        val kinds = result.countsByKind()
        for (kind in ItemKind.entries) assertTrue((kinds[kind] ?: 0) > 0, "no $kind item in the sample: $kinds")
        // 14 messages in the mbox plus 10 .eml files.
        assertEquals(24, kinds[ItemKind.EMAIL])
        assertTrue(result.unavailable.isEmpty())
    }

    @Test
    fun `skips binaries, lying extensions and unsupported types, each with its reason`() {
        val (_, result) = sample()
        val reasons = result.skipped.associate { Path.of(it.path).name to it.reason }
        assertTrue(reasons.getValue("photos-backup.zip").startsWith("unsupported type: .zip"), reasons.toString())
        assertTrue(reasons.getValue("app-data.bin").startsWith("unsupported type: .bin"))
        assertTrue(reasons.getValue("invoice-FINAL.pdf").startsWith("extension lies"), reasons.toString())
        assertEquals(3, result.skipped.size, reasons.toString())
        assertEquals(setOf("unsupported type", "extension lies"), result.skippedByReason().keys)
    }

    @Test
    fun `finds the exact duplicate by hash and points it at the first copy`() {
        val (_, result) = sample()
        val copy = result.byName("fresh-basket-2026-08-14 (copy).txt")
        val original = result.byName("fresh-basket-2026-08-14.txt")
        assertEquals(original.id, copy.duplicateOf)
        assertNull(original.duplicateOf)
        assertEquals(original.contentHash, copy.contentHash)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun `parses email headers, bodies and links with mime4j`() {
        val (_, result) = sample()
        val phish = result.byName("phishing-paypal.eml")
        val email = assertNotNull(phish.email)
        assertEquals("PayPal Security", email.fromName)
        assertEquals("service@paypa1-secure.example", email.fromAddress)
        assertEquals(LocalDate.of(2026, 9, 21), email.date)
        assertEquals(DateOrigin.EMAIL_HEADER, phish.dateOrigin)
        assertTrue("http://paypal.account-verify.example/login" in email.links)
        assertTrue(phish.text.startsWith("From: PayPal Security <service@paypa1-secure.example>"))
        assertTrue("Subject: Your account has been limited" in phish.text)
        // The multipart/alternative body is read once, from its text/plain part.
        assertEquals(1, Regex("unusual activity").findAll(phish.text).count())
    }

    @Test
    fun `reads every message of an mbox as its own item`() {
        val (_, result) = sample()
        val mbox = result.items.filter { it.path.name == "subscriptions-2026.mbox" }
        assertEquals(14, mbox.size, mbox.joinToString { it.name })
        assertEquals((1..14).toList(), mbox.map { it.messageIndex })
        assertTrue(mbox.all { it.id.endsWith("#${it.messageIndex}") })
        val streamflix = mbox.filter { it.email?.fromAddress == "billing@streamflix.example" }
        assertEquals(5, streamflix.size)
        assertEquals(LocalDate.of(2026, 6, 5), streamflix.first().date)
    }

    @Test
    fun `extracts PDF text layers, and reports a scan with no text rather than guessing`() {
        val (_, result) = sample()
        val renewal = result.byName("home-insurance-renewal-2026.pdf")
        assertTrue(renewal.hasText)
        assertTrue("Annual premium: £553.50" in renewal.text, renewal.text)
        assertEquals("1", renewal.facts["pages"])
        assertEquals(DateOrigin.PDF_INFO, renewal.dateOrigin)
        val scan = result.byName("scanned-letter.pdf")
        assertFalse(scan.hasText)
        assertEquals("", scan.text)
        assertTrue(scan.facts.getValue("text").contains("no OCR"))
    }

    @Test
    fun `images yield metadata only, never text`() {
        val (_, result) = sample()
        val png = result.byName("lisbon-sunset.png")
        assertEquals(ItemKind.IMAGE, png.kind)
        assertFalse(png.hasText)
        assertEquals("320x200", png.facts["dimensions"])
        assertEquals("image/png", png.mime)
        val jpg = result.byName("IMG_2051.jpg")
        assertEquals("image/jpeg", jpg.mime)
        assertEquals("320x240", jpg.facts["dimensions"])
    }

    @Test
    fun `strips HTML to its visible text`() {
        val (_, result) = sample()
        val booking = result.byName("casa-azul-booking.html")
        assertTrue("Casa Azul Lisbon — 3 nights from 14 October 2026" in booking.text, booking.text)
        assertTrue("€420.00" in booking.text)
        assertFalse("tracking" in booking.text)
        assertFalse("<" in booking.text.substringAfter("\n\n"))
    }

    @Test
    fun `a scan writes nothing to the source folders and changes no byte or timestamp`() {
        val root = SampleData.materialize(tmp.resolve("ro"))
        fun snapshot(): Map<String, Pair<String, Long>> = Files.walk(root).use { s ->
            s.filter { Files.exists(it) }.toList().associate { p ->
                root.relativize(p).toString() to
                    ((if (Files.isRegularFile(p)) ContentHash.of(p.readBytes()) else "dir") to Files.getLastModifiedTime(p).toMillis())
            }
        }
        val before = snapshot()
        scanner.scan(listOf(SourceSpec("a", SourceType.FOLDER, root)))
        assertEquals(before, snapshot())
    }

    @Test
    fun `files over the size limit are skipped with the reason`() {
        val dir = Files.createDirectories(tmp.resolve("big"))
        dir.resolve("huge.txt").writeText("x".repeat(3000))
        dir.resolve("small.txt").writeText("A short note with enough words to judge on.")
        val small = Scanner(Scanner.Limits(maxFileBytes = 2000), ZoneOffset.UTC)
        val result = small.scan(listOf(SourceSpec("b", SourceType.FOLDER, dir)))
        assertEquals(listOf("small.txt"), result.items.map { it.path.name })
        assertTrue(result.skipped.single().reason.startsWith("too large"))
    }

    @Test
    fun `long text is cut with the engine's marker, never summarised`() {
        val dir = Files.createDirectories(tmp.resolve("long"))
        val body = (1..5000).joinToString(" ") { "word$it" }
        dir.resolve("long.txt").writeText(body)
        val result = Scanner(Scanner.Limits(maxTextChars = 1000), ZoneOffset.UTC).scan(listOf(SourceSpec("c", SourceType.FOLDER, dir)))
        val item = result.items.single()
        assertTrue(item.textTruncated)
        assertEquals(1000, item.text.length)
        assertTrue(item.text.endsWith("[...truncated]"))
        assertTrue(("File: long.txt\n\n" + body).startsWith(item.text.removeSuffix("[...truncated]")))
    }

    @Test
    fun `symbolic links are not followed out of the chosen folder`() {
        val outside = Files.createDirectories(tmp.resolve("outside"))
        outside.resolve("secret.txt").writeText("This file is outside the folder the user chose.")
        val dir = Files.createDirectories(tmp.resolve("chosen"))
        dir.resolve("note.txt").writeText("An ordinary note inside the chosen folder.")
        Files.createSymbolicLink(dir.resolve("link-to-outside"), outside)
        Files.createSymbolicLink(dir.resolve("link.txt"), outside.resolve("secret.txt"))
        val result = scanner.scan(listOf(SourceSpec("d", SourceType.FOLDER, dir)))
        assertEquals(listOf("note.txt"), result.items.map { it.path.name })
        assertTrue(result.skipped.all { it.reason.startsWith("symbolic link") })
    }

    @Test
    fun `a missing source is reported as unavailable, not an error`() {
        val result = scanner.scan(listOf(SourceSpec("gone", SourceType.FOLDER, tmp.resolve("nope"))))
        assertEquals(1, result.unavailable.size)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `a cancelled scan stops between files`() {
        val root = SampleData.materialize(tmp.resolve("c"))
        var polls = 0
        val result = scanner.scan(listOf(SourceSpec("a", SourceType.FOLDER, root)), isCancelled = { ++polls > 4 })
        assertTrue(result.items.size < 10, "read ${result.items.size} items after cancelling")
    }

    @Test
    fun `html text keeps entities and drops scripts`() {
        assertEquals("a & b\n\nc", HtmlText.toText("<script>x()</script><p>a &amp; b</p><div>c</div>"))
        assertEquals(listOf("https://x.example/a", "http://y.example"), Links.find("""<a href="https://x.example/a">x</a> see http://y.example."""))
    }
}
