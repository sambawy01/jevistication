package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.TEST_TMP
import dev.loupe.kit.watchers.TRACKING_FIXTURES
import dev.loupe.sources.common.CsvRows
import dev.loupe.sources.common.Inbox
import dev.loupe.sources.common.InboxFs
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.NoPlatformExtractors
import dev.loupe.sources.common.SourceFs
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.random.Random

/** One labelled item of the set. */
internal data class Fixture(
    val id: String,
    val lang: String,
    val expect: String,
    val item: SourceItem,
    val merchant: String?,
    val currency: String?,
    val amountMinor: Long?,
    val expiry: LocalDate?,
    val doc: String?,
)

internal object TrackingFixtures {
    val TODAY: LocalDate = LocalDate(2026, 9, 28)

    fun load(): List<Fixture> {
        val dir = "$TRACKING_FIXTURES/items"
        val names = SourceFs.list(dir)!!.filter { it.isRegularFile && it.name.endsWith(".txt") }.map { it.name }.sorted()
        return names.map { parse(it.removeSuffix(".txt"), SourceFs.readBytes("$dir/$it").decodeToString()) }
    }

    fun parse(id: String, raw: String): Fixture {
        val head = raw.substringBefore("\n---\n")
        val body = raw.substringAfter("\n---\n").trimEnd('\n')
        val meta = head.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val date = meta.getValue("date")
        val item = when (val kind = meta.getValue("kind")) {
            "email" -> TrackingItems.email(id, date, meta.getValue("from"), meta.getValue("subject"), body)
            "image" -> TrackingItems.image(id, date, body)
            "pdf" -> TrackingItems.file("$id.pdf", date, ItemKind.PDF, body)
            "html" -> TrackingItems.file("$id.html", date, ItemKind.HTML, body)
            "text" -> TrackingItems.file("$id.txt", date, ItemKind.TEXT, body)
            "event" -> TrackingItems.event(id, date, meta.getValue("subject"), body)
            else -> error("$id: unknown kind $kind")
        }
        return Fixture(id, meta.getValue("lang"), meta.getValue("expect"), item, meta["merchant"], meta["currency"],
                       meta["amount"]?.let { CsvRows.parseNumber(it) }, meta["expiry"]?.let { LocalDate.parse(it) }, meta["doc"])
    }

    /** statement.csv imported through the Inbox, as the phone imports a bank export: one item per row (CsvRows). */
    fun statementRows(): List<SourceItem> {
        val home = "$TEST_TMP/tracking-inbox-" + Random.nextLong().toULong()
        InboxFs.createDirectories("$home/in")
        InboxFs.writeNew("$home/in/statement.csv", SourceFs.readBytes("$TRACKING_FIXTURES/statement.csv"))
        val inbox = Inbox("$home/home", NoPlatformExtractors, TimeZone.UTC, Inbox.Limits())
        inbox.importFiles(listOf("$home/in/statement.csv"), "statement.csv", "Files", 1_790_000_000_000L)
        val rows = inbox.items()
        InboxFs.deleteRecursively(home)
        return rows
    }
}
