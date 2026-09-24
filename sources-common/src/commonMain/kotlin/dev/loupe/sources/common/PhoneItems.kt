package dev.loupe.sources.common

import dev.loupe.engine.ContentHash
import dev.loupe.engine.TextState
import kotlinx.datetime.LocalDate

/** The ids of the phone's sources (epic #7 child 7). Also the `sourceId` of every item they yield. */
object PhoneSourceIds {
    const val PHOTOS: String = "photos"
    const val FILES: String = "files"
    const val SHARED: String = "shared"
    const val CALENDAR: String = "calendar"
    const val CONTACTS: String = "contacts"
    const val MAIL: String = "mail"

    /** The Inbox (child 15): imported CSVs, mail files, ZIP archives and shared text. Not a phone source. */
    const val INBOX: String = "inbox"

    val ALL: List<String> = listOf(PHOTOS, FILES, SHARED, CALENDAR, CONTACTS, MAIL)
}

/**
 * Builds [SourceItem]s from records the phone's frameworks hand over — a photo's metadata and its
 * on-device OCR text (PhotoKit, ImageIO, Vision), a calendar event (EventKit), a contact card
 * (Contacts) — in common code, so the item shape, the text the model reads, the hashing and the
 * truncation are the same rules as the scanner's, and testable on the JVM. The Swift wrappers only
 * read the frameworks; they decide nothing here. Files, the share-sheet inbox and IMAP mail need no
 * builder: they are files on disk (mail as one `.eml` per message) read by [SourceScanner].
 */
class PhoneItems(private val maxTextChars: Int) {
    constructor() : this(20_000)

    init {
        require(maxTextChars > 100)
    }

    /**
     * A photo. [ocrText] is Vision's text, already on-device; [takenIso] the EXIF date when there is
     * one, else [createdIso] (the library's creation date). A photo's id is PhotoKit's local
     * identifier, which is stable for the life of the library.
     */
    fun photo(
        localId: String,
        name: String,
        ocrText: String,
        createdIso: String?,
        takenIso: String?,
        dimensions: String?,
        camera: String?,
        hasLocation: Boolean,
        screenshot: Boolean,
        sizeBytes: Long,
    ): SourceItem {
        val text = ocrText.trim()
        val hasText = text.count { it.isLetterOrDigit() } >= SourceScanner.MIN_TEXT
        val facts = linkedMapOf<String, String>()
        dimensions?.let { facts["dimensions"] = it }
        camera?.let { facts["camera"] = it }
        if (hasLocation) facts["location"] = "GPS position recorded"
        if (screenshot) facts["screenshot"] = "yes"
        facts["text"] = if (hasText) "read on this iPhone (Vision OCR)" else "no text found by on-device OCR"
        val taken = takenIso?.let(::day)
        val created = createdIso?.let(::day)
        // Owner rule 2026-09-24: the state says the text came from OCR of a picture, so a category
        // question reads "a photo of a receipt", not a code file or a document typed as text.
        val header = photoHeader(name, screenshot)
        return item(
            id = "photos:$localId", sourceId = PhoneSourceIds.PHOTOS, kind = ItemKind.IMAGE, path = "photos/$localId",
            name = name, text = if (hasText) "$header\n\n$text" else "", hasText = hasText, sizeBytes = sizeBytes,
            hashOf = "photo\u0000$localId\u0000$text\u0000${takenIso ?: createdIso}", mime = "image/*",
            date = taken ?: created,
            dateOrigin = if (taken != null) DateOrigin.EXIF else if (created != null) DateOrigin.PHOTO_CREATED else null,
            facts = facts,
        )
    }

    /**
     * A calendar event. [startIso]/[endIso] are local `yyyy-MM-ddTHH:mm` (or `yyyy-MM-dd` all day).
     * The id carries the occurrence's start, so each occurrence of a recurring event is its own item.
     */
    fun event(
        eventId: String,
        title: String,
        startIso: String,
        endIso: String?,
        allDay: Boolean,
        location: String?,
        calendar: String?,
        organizer: String?,
        attendees: List<String>,
        recurrence: String?,
        notes: String?,
    ): SourceItem {
        val name = title.ifBlank { "(untitled event)" }
        val text = buildString {
            append("Event: ").append(name).append('\n')
            append("When: ").append(startIso)
            if (endIso != null && endIso != startIso) append(" to ").append(endIso)
            if (allDay) append(" (all day)")
            append('\n')
            location?.takeIf { it.isNotBlank() }?.let { append("Where: ").append(it).append('\n') }
            calendar?.takeIf { it.isNotBlank() }?.let { append("Calendar: ").append(it).append('\n') }
            organizer?.takeIf { it.isNotBlank() }?.let { append("Organiser: ").append(it).append('\n') }
            if (attendees.isNotEmpty()) append("Attendees: ").append(attendees.joinToString(", ")).append('\n')
            recurrence?.takeIf { it.isNotBlank() }?.let { append("Repeats: ").append(it).append('\n') }
            notes?.trim()?.takeIf { it.isNotEmpty() }?.let { append('\n').append(it) }
        }.trimEnd()
        val facts = linkedMapOf<String, String>()
        facts["starts"] = startIso
        endIso?.let { facts["ends"] = it }
        if (allDay) facts["allDay"] = "yes"
        calendar?.takeIf { it.isNotBlank() }?.let { facts["calendar"] = it }
        if (attendees.isNotEmpty()) facts["attendees"] = attendees.size.toString()
        recurrence?.takeIf { it.isNotBlank() }?.let { facts["recurrence"] = it }
        val start = day(startIso)
        return item(
            id = "calendar:$eventId@$startIso", sourceId = PhoneSourceIds.CALENDAR, kind = ItemKind.EVENT,
            path = "calendar/$eventId", name = name, text = text, hasText = true, sizeBytes = text.length.toLong(),
            hashOf = text, mime = "text/calendar", date = start, dateOrigin = start?.let { DateOrigin.EVENT_START }, facts = facts,
        )
    }

    /**
     * A contact card. Its email addresses feed the impersonation watcher as a known contact (see
     * `WatcherRun`); phone numbers are kept as facts — nothing reads SMS on iOS (risk 4), so today they
     * only describe the card.
     */
    fun contact(contactId: String, name: String, organization: String?, emails: List<String>, phones: List<String>): SourceItem {
        val display = name.ifBlank { organization?.takeIf { it.isNotBlank() } ?: emails.firstOrNull() ?: "(no name)" }
        val cleanEmails = emails.map { it.trim().lowercase() }.filter { '@' in it }.distinct()
        val cleanPhones = phones.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val text = buildString {
            append("Contact: ").append(display)
            organization?.takeIf { it.isNotBlank() && it != display }?.let { append(" (").append(it).append(')') }
            if (cleanEmails.isNotEmpty()) append("\nEmail: ").append(cleanEmails.joinToString(", "))
            if (cleanPhones.isNotEmpty()) append("\nPhone: ").append(cleanPhones.joinToString(", "))
        }
        val facts = linkedMapOf<String, String>()
        if (cleanEmails.isNotEmpty()) facts["emails"] = cleanEmails.joinToString(",")
        if (cleanPhones.isNotEmpty()) facts["phones"] = cleanPhones.joinToString(",")
        organization?.takeIf { it.isNotBlank() }?.let { facts["organisation"] = it }
        return item(
            id = "contacts:$contactId", sourceId = PhoneSourceIds.CONTACTS, kind = ItemKind.CONTACT, path = "contacts/$contactId",
            name = display, text = text, hasText = true, sizeBytes = text.length.toLong(), hashOf = text, mime = "text/vcard",
            date = null, dateOrigin = null, facts = facts,
        )
    }

    private fun item(
        id: String, sourceId: String, kind: ItemKind, path: String, name: String, text: String, hasText: Boolean,
        sizeBytes: Long, hashOf: String, mime: String, date: LocalDate?, dateOrigin: DateOrigin?, facts: Map<String, String>,
    ): SourceItem {
        val truncated = text.length > maxTextChars
        val kept = if (truncated) text.take(maxTextChars - TextState.TRUNCATION_MARKER.length) + TextState.TRUNCATION_MARKER else text
        return SourceItem(
            id = id, sourceId = sourceId, kind = kind, path = path, messageIndex = null, name = name, text = kept,
            hasText = hasText, textTruncated = truncated, sizeBytes = sizeBytes, contentHash = ContentHash.of(hashOf),
            mime = mime, date = date, dateOrigin = dateOrigin, email = null, facts = facts,
        )
    }

    companion object {
        /** What a judgment reads first for an OCR'd photo: "Photo (text recognised): IMG_1507.HEIC". */
        const val OCR_MARK: String = "(text recognised)"

        fun photoHeader(name: String, screenshot: Boolean): String = (if (screenshot) "Screenshot" else "Photo") + " $OCR_MARK: $name"

        /** True when [text] is a photo's OCR text as built by [photo]. */
        fun isRecognisedPhotoText(text: String): Boolean =
            (text.startsWith("Photo $OCR_MARK: ") || text.startsWith("Screenshot $OCR_MARK: "))

        /** `yyyy-MM-dd` from an ISO date or date-time; null if it is not one. */
        fun day(iso: String): LocalDate? = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()

        /**
         * Marks every item of [result] as fetched over the network (PRODUCT.md §4a): "Online", the
         * source it came from, and when. Mail from the user's own IMAP account is the case today.
         */
        fun labelOnline(result: ScanResult, from: String, fetchedIso: String): ScanResult = result.copy(
            items = result.items.map { it.copy(facts = LinkedHashMap(it.facts).apply { put("online", "Online · $from · fetched $fetchedIso") }) },
        )

        /** Merges a fresh scan over a cached one: fresh items win by id, the rest are kept in order. */
        fun merge(cached: ScanResult?, fresh: ScanResult): ScanResult {
            if (cached == null) return fresh
            val ids = fresh.items.map { it.id }.toSet()
            return ScanResult(cached.items.filter { it.id !in ids } + fresh.items, fresh.skipped, fresh.unavailable)
        }

        /** Keeps only the items whose id passes [keep] (a deleted photo, a removed folder). */
        fun retain(result: ScanResult, keep: (String) -> Boolean): ScanResult = result.copy(items = result.items.filter { keep(it.id) })

        /** Swift-friendly [retain]: keeps the items whose id is in [ids]... or not, when [invert]. */
        fun retainIds(result: ScanResult, ids: Set<String>, invert: Boolean): ScanResult =
            retain(result) { (it in ids) != invert }
    }
}
