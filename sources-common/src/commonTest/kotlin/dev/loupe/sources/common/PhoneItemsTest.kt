package dev.loupe.sources.common

import dev.loupe.engine.TextState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epic #7 child 7: the phone sources' item builders, the same on the JVM and the iOS simulator. */
class PhoneItemsTest {
    private val items = PhoneItems()

    @Test
    fun aScreenshotWithOcrTextIsATextItemFlaggedAsAScreenshot() {
        val i = items.photo("ABC/L0/001", "IMG_0042.PNG", "  Receipt\nTotal £12.40 paid by card  ", "2026-09-01T10:00:00", null,
            "1170x2532", null, hasLocation = false, screenshot = true, sizeBytes = 900_000)
        assertEquals("photos:ABC/L0/001", i.id)
        assertEquals(PhoneSourceIds.PHOTOS, i.sourceId)
        assertEquals(ItemKind.IMAGE, i.kind)
        assertTrue(i.hasText)
        assertEquals("Screenshot: IMG_0042.PNG\n\nReceipt\nTotal £12.40 paid by card", i.text)
        assertEquals("yes", i.facts["screenshot"])
        assertEquals(LocalDate(2026, 9, 1), i.date)
        assertEquals(DateOrigin.PHOTO_CREATED, i.dateOrigin)
    }

    @Test
    fun aPhotoWithoutTextHasNoTextAndPrefersTheExifDate() {
        val i = items.photo("X", "IMG_1.HEIC", "ok", "2026-09-01", "2026-08-30", null, "Apple iPhone", hasLocation = true,
            screenshot = false, sizeBytes = 1)
        assertFalse(i.hasText)
        assertEquals("", i.text)
        assertEquals(LocalDate(2026, 8, 30), i.date)
        assertEquals(DateOrigin.EXIF, i.dateOrigin)
        assertEquals("GPS position recorded", i.facts["location"])
        assertNull(i.facts["screenshot"])
        // A new OCR result changes the hash: an incremental rescan sees the change.
        assertNotEquals(i.contentHash, items.photo("X", "IMG_1.HEIC", "a longer text now", "2026-09-01", "2026-08-30",
            null, null, true, false, 1).contentHash)
    }

    @Test
    fun anEventCarriesTitleAttendeesAndRecurrencePerOccurrence() {
        val e = items.event("E1", "Dentist", "2026-10-02T09:30", "2026-10-02T10:00", false, "High St", "Home",
            "Dr Smile", listOf("Alex <alex@x.example>", "Sam"), "every 6 months", "Bring the insurance card")
        assertEquals("calendar:E1@2026-10-02T09:30", e.id)
        assertEquals(ItemKind.EVENT, e.kind)
        assertEquals(LocalDate(2026, 10, 2), e.date)
        assertEquals(DateOrigin.EVENT_START, e.dateOrigin)
        assertTrue(e.text.startsWith("Event: Dentist\nWhen: 2026-10-02T09:30 to 2026-10-02T10:00\n"), e.text)
        assertTrue("Attendees: Alex <alex@x.example>, Sam" in e.text)
        assertTrue("Repeats: every 6 months" in e.text)
        assertTrue(e.text.endsWith("Bring the insurance card"))
        assertEquals("2", e.facts["attendees"])
        assertNotEquals(e.id, items.event("E1", "Dentist", "2027-04-02T09:30", null, false, null, null, null, emptyList(), null, null).id)
    }

    @Test
    fun aContactKeepsLowercasedDistinctEmailsAndPhones() {
        val c = items.contact("C1", "", "Acme Ltd", listOf("Ops@Acme.example", "ops@acme.example", "not-an-email"), listOf(" +1 555 0100 ", ""))
        assertEquals("Acme Ltd", c.name)
        assertEquals(ItemKind.CONTACT, c.kind)
        assertEquals("ops@acme.example", c.facts["emails"])
        assertEquals("+1 555 0100", c.facts["phones"])
    }

    @Test
    fun longTextIsCutWithTheEngineMarker() {
        val e = PhoneItems(200).event("E", "T", "2026-01-01", null, true, null, null, null, emptyList(), null, "x".repeat(500))
        assertTrue(e.textTruncated)
        assertEquals(200, e.text.length)
        assertTrue(e.text.endsWith(TextState.TRUNCATION_MARKER))
    }

    @Test
    fun onlineLabelMergeAndRetain() {
        val a = items.contact("A", "A", null, emptyList(), emptyList())
        val b = items.contact("B", "B", null, emptyList(), emptyList())
        val b2 = items.contact("B", "B two", null, emptyList(), emptyList())
        val merged = PhoneItems.merge(ScanResult(listOf(a, b), emptyList(), emptyList()), ScanResult(listOf(b2), emptyList(), emptyList()))
        assertEquals(listOf("A", "B two"), merged.items.map { it.name })
        val online = PhoneItems.labelOnline(merged, "imap.example.com", "2026-09-23T10:00:00Z")
        assertEquals("Online · imap.example.com · fetched 2026-09-23T10:00:00Z", online.items[0].facts["online"])
        assertEquals(listOf("contacts:B"), PhoneItems.retainIds(merged, setOf("contacts:A"), invert = true).items.map { it.id })
    }

    @Test
    fun theCacheRoundTripsThePhoneKinds() {
        val dir = newTempDir()
        val lib = SourceLibrary(dir)
        val result = ScanResult(listOf(items.event("E", "T", "2026-01-01", null, true, null, null, null, emptyList(), null, null),
            items.contact("C", "N", null, listOf("n@x.example"), emptyList())), emptyList(), emptyList())
        lib.store("calendar", result, 1L)
        assertEquals(result.items, lib.cached("calendar")!!.result.items)
    }
}
