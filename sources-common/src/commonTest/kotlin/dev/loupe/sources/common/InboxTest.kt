package dev.loupe.sources.common

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Inbox (epic #7 child 15): batches of CSV rows, mail files, ZIP archives and shared text. */
class InboxTest {
    private val now = 1_790_000_000_000L   // 2026-09-21
    private val statement = "Date,Description,Amount,Currency\n05/06/2026,STREAMFLIX.COM,-9.99,GBP\n05/07/2026,STREAMFLIX.COM,-9.99,GBP\n12/07/2026,Cafe Luna,\"-1,234.50\",GBP\n,no date,1.00,GBP\n"
    private val eml = "From: Ada <ada@example.com>\r\nTo: me@example.com\r\nSubject: Your invoice\r\nDate: Mon, 1 Jun 2026 09:00:00 +0000\r\n\r\nInvoice attached, see https://pay.example/1\r\n"

    private fun setup(): Pair<Inbox, String> {
        val home = newTempDir()
        val staging = "$home-in"
        InboxFs.createDirectories(staging)
        return Inbox(home, NoPlatformExtractors, TimeZone.UTC, Inbox.Limits()) to staging
    }

    private fun file(dir: String, name: String, bytes: ByteArray): String = "$dir/$name".also { InboxFs.writeNew(it, bytes) }

    @Test
    fun csvRowsBecomeItemsWithColumnContextAndTheirOrigin() {
        val (inbox, staging) = setup()
        val batch = inbox.importFiles(listOf(file(staging, "statement.csv", statement.encodeToByteArray())), "statement.csv", "Files", now)
        assertEquals(4, batch.itemCount); assertEquals(4, batch.rowCount); assertEquals(0, batch.skippedCount)
        val items = inbox.items()
        assertEquals(4, items.size)
        assertTrue(items.all { it.sourceId == PhoneSourceIds.INBOX && it.kind == ItemKind.CSV })
        assertTrue(items.all { it.facts["imported"] == "Imported · Files · statement.csv · 2026-09-21" })
        val first = items.first()
        assertEquals("inbox:${batch.id}/statement.csv#row1", first.id)
        assertEquals("STREAMFLIX.COM", first.name)
        assertEquals(LocalDate(2026, 6, 5), first.date); assertEquals(DateOrigin.CSV_COLUMN, first.dateOrigin)
        assertEquals("-999", first.facts["amount_minor"]); assertEquals("GBP", first.facts["currency"])
        assertTrue(first.text.startsWith("Row 1 of statement.csv (columns: Date, Description, Amount, Currency)"), first.text)
        assertTrue("Amount: -9.99" in first.text)
        assertNull(items.last().facts["amount_minor"], "a row without a date keeps its context but no statement facts")
        assertTrue(first.id != items[1].id && first.contentHash != items[1].contentHash)
    }

    @Test
    fun theSameStatementTwiceIsMarkedNotDoubled() {
        val (inbox, staging) = setup()
        inbox.importFiles(listOf(file(staging, "a.csv", statement.encodeToByteArray())), "a.csv", "Files", now)
        val again = inbox.importFiles(listOf(file(staging, "b.csv", statement.encodeToByteArray())), "b.csv", "Share sheet", now + 1)
        assertEquals(4, again.alreadyImported)
        assertTrue(inbox.cached(again.id)!!.result.items.all { it.duplicateOf != null })
        assertEquals(2, inbox.batches().size)
        assertEquals(again.id, inbox.batches().first().id, "newest first")
    }

    @Test
    fun emlAndMboxAreMailItems() {
        val (inbox, staging) = setup()
        val paths = listOf(
            file(staging, "invoice.eml", eml.encodeToByteArray()),
            file(staging, "subscriptions.mbox", SourceFs.readBytes("$SAMPLE_DIR/mail/subscriptions-2026.mbox")),
        )
        val batch = inbox.importFiles(paths, "mail", "Files", now)
        assertEquals(15, batch.emailCount, "one .eml plus the sample mbox's 14 messages")
        val invoice = inbox.items().single { it.id == "inbox:${batch.id}/invoice.eml" }
        assertEquals("ada@example.com", invoice.email!!.fromAddress)
        assertEquals(listOf("https://pay.example/1"), invoice.email!!.links)
        assertTrue(inbox.items().any { it.id == "inbox:${batch.id}/subscriptions.mbox#14" })
    }

    @Test
    fun zipArchivesAreUnpackedSafelyAndTheirCsvsRead() {
        val (inbox, staging) = setup()
        val zip = TestZip()
            .stored("export/statement.csv", statement)
            .stored("export/mail/invoice.eml", eml)
            .stored("../escape.txt", "must not land outside")
            .add(TestZip.Member("link", "/etc".encodeToByteArray(), unixMode = 0xA1ED))
            .stored("nested.zip", "PK")
            .stored("export/notes.txt", "Remember to cancel the gym membership in October.")
            .bytes()
        val batch = inbox.importFiles(listOf(file(staging, "export.zip", zip)), "export.zip", "Files", now)
        val items = inbox.items()
        assertEquals(4, batch.rowCount); assertEquals(1, batch.emailCount)
        assertEquals(6, batch.itemCount)
        assertTrue(items.any { it.id == "inbox:${batch.id}/export.zip/export/notes.txt" }, items.map { it.id }.toString())
        assertTrue(items.any { it.id == "inbox:${batch.id}/export.zip/export/statement.csv#row1" })
        val skipped = inbox.cached(batch.id)!!.result.skipped.associate { it.path to it.reason }
        assertTrue(skipped.getValue("export.zip/../escape.txt").startsWith("unsafe path"))
        assertEquals("symbolic link: refused", skipped["export.zip/link"])
        assertEquals("archive inside an archive: not opened", skipped["export.zip/nested.zip"])
        assertEquals(3, batch.skippedCount)
        assertFalse(SourceFs.exists(staging.substringBeforeLast('/') + "/escape.txt"))
    }

    @Test
    fun sharedTextAndLinksBecomeItems() {
        val (inbox, _) = setup()
        val link = inbox.importText("https://shop.example/order/77", "", "Share sheet", now)
        val item = inbox.items().single()
        assertEquals("Link", link.name.removeSuffix(".txt"))
        assertTrue(item.text.contains("Link shared to Loupe: https://shop.example/order/77"))
        assertEquals("1", item.facts["links"])
        assertTrue(item.facts.getValue("imported").startsWith("Imported · Share sheet"))
    }

    @Test
    fun removingABatchRemovesItsItemsCacheAndCopy() {
        val (inbox, staging) = setup()
        val a = inbox.importFiles(listOf(file(staging, "a.csv", statement.encodeToByteArray())), "a.csv", "Files", now)
        val b = inbox.importText("A note long enough to count as text.", "note", "Pasted", now + 5)
        assertEquals(5, inbox.items().size)
        assertTrue(inbox.remove(a.id))
        assertFalse(inbox.remove(a.id))
        assertEquals(listOf(b.id), inbox.batches().map { it.id })
        assertEquals(1, inbox.items().size)
        assertNull(inbox.cached(a.id))
        inbox.setEnabled(false)
        assertTrue(inbox.items().isEmpty(), "off: the Inbox leaves every judgment and watcher")
    }

    @Test
    fun unreadableFilesAreSkippedWithTheReason() {
        val (inbox, staging) = setup()
        val batch = inbox.importFiles(
            listOf(file(staging, "empty.csv", ByteArray(0)), file(staging, "tool.exe", byteArrayOf(0x4D, 0x5A, 0)), "$staging/missing.csv"),
            "odd", "Files", now,
        )
        assertEquals(0, batch.itemCount)
        val why = inbox.cached(batch.id)!!.result.skipped.associate { it.path to it.reason }
        assertEquals("empty file", why["empty.csv"]); assertEquals("unsupported type: .exe", why["tool.exe"]); assertEquals("not a file", why["missing.csv"])
        assertEquals("statement_2026_.csv", Inbox.safeName("../statement?2026*.csv"))
        assertEquals("upload", Inbox.safeName("..."))
    }
}
