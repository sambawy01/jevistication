package dev.loupe.sources

import dev.loupe.sources.common.MimeParser
import dev.loupe.sources.common.SourceRoot
import dev.loupe.sources.common.SourceScanner
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.loupe.sources.common.SourceType as CommonSourceType

/**
 * Epic #7 child 2: the common scanner (pure-Kotlin MIME and text extractors, PDFBox and
 * metadata-extractor as its platform readers here) yields the desktop `Scanner`'s items for the
 * sample set — same ids, text, facts, hashes, dates and duplicates, and the same skips.
 */
class ParityTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the common scanner matches the desktop scanner on every sample item`() {
        val root = SampleData.materialize(tmp.resolve("sample"))
        val docs = root.resolve(SampleData.DOCUMENTS)
        val mail = root.resolve(SampleData.MAIL)
        val desktop = Scanner(zone = ZoneOffset.UTC).scan(
            listOf(SourceSpec("docs", SourceType.FOLDER, docs), SourceSpec("mail", SourceType.MAIL_EXPORT, mail)),
        )
        val common = SourceScanner(DesktopPlatformExtractors(ZoneOffset.UTC), TimeZone.UTC).scan(
            listOf(
                SourceRoot("docs", CommonSourceType.FOLDER, docs.toString()),
                SourceRoot("mail", CommonSourceType.MAIL_EXPORT, mail.toString()),
            ),
        )
        assertEquals(desktop.items.map { it.id }, common.items.map { it.id })
        for ((d, c) in desktop.items.zip(common.items)) {
            val where = d.id
            assertEquals(d.sourceId, c.sourceId, where)
            assertEquals(d.kind.name, c.kind.name, where)
            assertEquals(d.path.toString(), c.path, where)
            assertEquals(d.messageIndex, c.messageIndex, where)
            assertEquals(d.name, c.name, where)
            assertEquals(d.text, c.text, where)
            assertEquals(d.hasText, c.hasText, where)
            assertEquals(d.textTruncated, c.textTruncated, where)
            assertEquals(d.sizeBytes, c.sizeBytes, where)
            assertEquals(d.contentHash, c.contentHash, where)
            assertEquals(d.mime, c.mime, where)
            assertEquals(d.date?.toString(), c.date?.toString(), where)
            assertEquals(d.dateOrigin?.name, c.dateOrigin?.name, where)
            assertEquals(d.facts, c.facts, where)
            assertEquals(d.duplicateOf, c.duplicateOf, where)
            assertEquals(d.email?.let { listOf(it.fromName, it.fromAddress, it.to, it.subject, it.date?.toString(), it.links, it.attachmentNames) },
                c.email?.let { listOf(it.fromName, it.fromAddress, it.to, it.subject, it.date?.toString(), it.links, it.attachmentNames) }, where)
        }
        assertEquals(desktop.skipped.map { it.path to it.reason }, common.skipped.map { it.path to it.reason })
        assertEquals(desktop.unavailable.size, common.unavailable.size)
    }

    @Test
    fun `the common MIME parser matches mime4j on every sample message`() {
        val root = SampleData.materialize(tmp.resolve("mime"))
        val messages = mutableListOf<ByteArray>()
        java.nio.file.Files.list(root.resolve("mail/inbox")).use { s -> s.sorted().forEach { messages += java.nio.file.Files.readAllBytes(it) } }
        messages += dev.loupe.sources.common.Mbox.split(java.nio.file.Files.readAllBytes(root.resolve("mail/subscriptions-2026.mbox")))
        assertEquals(24, messages.size)
        for (bytes in messages) {
            val jvm = EmailExtractor.parse(bytes, ZoneOffset.UTC)
            val common = MimeParser.parseEmail(bytes, TimeZone.UTC)
            assertEquals(jvm.modelText(), common.modelText())
            assertEquals(jvm.body, common.body)
            assertEquals(jvm.facts.links, common.facts.links)
        }
    }
}
