package dev.loupe.kit.watchers

import dev.loupe.engine.ImpersonationReason
import dev.loupe.sources.common.EmailFacts
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.PhoneItems
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Epic #7 child 7: the Contacts source's cards are known contacts for the impersonation watcher. */
class AddressBookWatcherTest {
    private val today = LocalDate(2026, 9, 23)

    private fun email(id: String, name: String, address: String) = SourceItem(
        id = id, sourceId = "mail", kind = ItemKind.EMAIL, path = id, messageIndex = null, name = "Hi",
        text = "From: $name <$address>\nSubject: Hi\n\nPlease send the bank details today.", hasText = true,
        textTruncated = false, sizeBytes = 10, contentHash = id, mime = "message/rfc822", date = today, dateOrigin = null,
        email = EmailFacts(name, address, listOf("me@example.com"), "Hi", today, emptyList(), emptyList()), facts = emptyMap(),
    )

    @Test
    fun aCardMakesAKnownNameFromAnUnknownAddressAFinding() {
        val card = PhoneItems().contact("c1", "Dana Rivers", null, listOf("Dana@Rivers.example"), listOf("+44 7700 900123"))
        val mail = listOf(email("m1", "Dana Rivers", "dana.rivers@lookalike.example"), email("m2", "Someone Else", "else@x.example"))

        // Without the address book, one message from Dana is no history: nothing is raised.
        assertTrue(WatcherRun.run(mail, today, backend = null).impersonation.isEmpty())

        val report = WatcherRun.run(mail + card, today, backend = null)
        val finding = report.impersonation.single()
        assertEquals("m1", finding.item.id)
        assertTrue(finding.signals.any { it.reason == ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS })
        // The card itself is not an email and not text for the other watchers.
        assertEquals(2, report.emailsChecked)
    }

    @Test
    fun theCardsOwnAddressIsNotAFinding() {
        val card = PhoneItems().contact("c1", "Dana Rivers", null, listOf("dana@rivers.example"), emptyList())
        val report = WatcherRun.run(listOf(email("m1", "Dana Rivers", "dana@rivers.example"), card), today, backend = null)
        assertTrue(report.impersonation.isEmpty())
        assertEquals(listOf("dana@rivers.example"), WatcherRun.addressBook(listOf(card)).single().addresses.toList())
    }

    @Test
    fun aCardWithoutEmailIsNotAContact() {
        val card = PhoneItems().contact("c2", "Phone Only", null, emptyList(), listOf("+1 555 0100"))
        assertTrue(WatcherRun.addressBook(listOf(card)).isEmpty())
    }
}
