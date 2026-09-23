package dev.loupe.kit.watchers

import dev.loupe.sources.common.InboxFs
import dev.loupe.sources.common.Inbox
import dev.loupe.sources.common.NoPlatformExtractors
import dev.loupe.sources.common.SourceFs
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Inbox CSV rows (epic #7 child 15) feed the recurring-money watcher like any other source's items. */
class InboxChargesTest {
    @Test
    fun importedStatementRowsAreCharges() {
        val home = SAMPLE_DIR.substringBeforeLast("/src/") + "/build/tmp/inbox-charges-" + kotlin.random.Random.nextLong().toULong()
        InboxFs.createDirectories("$home/in")
        val csv = "Date,Merchant,Amount\n" + (1..6).joinToString("") { "2026-0$it-05,Streamflix,-9.99\n" }
        InboxFs.writeNew("$home/in/s.csv", csv.encodeToByteArray())
        val inbox = Inbox("$home/home", NoPlatformExtractors, TimeZone.UTC, Inbox.Limits())
        inbox.importFiles(listOf("$home/in/s.csv"), "s.csv", "Files", 1_790_000_000_000L)
        inbox.importFiles(listOf("$home/in/s.csv"), "again", "Files", 1_790_000_000_001L)
        val charges = WatcherRun.charges(inbox.items())
        assertEquals(6, charges.size, "the second import is marked as already imported, not counted twice")
        assertEquals(999L, charges.first().second.amountMinor)
        assertEquals(LocalDate(2026, 1, 5), charges.first().second.date)
        val run = WatcherRun.run(inbox.items(), LocalDate(2026, 9, 23), null)
        assertEquals(listOf("Streamflix"), run.recurring.map { it.merchant })
        InboxFs.deleteRecursively(home)
        assertEquals(false, SourceFs.exists(home))
    }
}
