package dev.loupe.kit.tracking

import dev.loupe.engine.ContentHash
import dev.loupe.sources.common.CsvRows
import dev.loupe.sources.common.DateOrigin
import dev.loupe.sources.common.EmailFacts
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.PhoneItems
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate

/** Source items as the phone's sources produce them, for the tracking tests. */
internal object TrackingItems {
    /** An email: headers, a blank line, the body (the shape `WatcherRun` reads). [from] is `Name <address>`. */
    fun email(id: String, date: String, from: String, subject: String, body: String): SourceItem {
        val name = from.substringBefore('<').trim().ifEmpty { null }
        val address = from.substringAfter('<', "").substringBefore('>').ifEmpty { from.trim() }
        val d = LocalDate.parse(date)
        val text = "From: $from\nSubject: $subject\nDate: $date\n\n$body"
        return SourceItem(
            id = "mail:$id", sourceId = "mail", kind = ItemKind.EMAIL, path = "mail/$id.eml", messageIndex = null, name = subject,
            text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = ContentHash.of(text),
            mime = "message/rfc822", date = d, dateOrigin = DateOrigin.EMAIL_HEADER,
            email = EmailFacts(name, address, emptyList(), subject, d, emptyList(), emptyList()), facts = emptyMap(),
        )
    }

    /** A photo or screenshot with its on-device OCR text (the Photos source's own builder). */
    fun image(id: String, date: String, ocr: String): SourceItem =
        PhoneItems().photo(localId = id, name = "$id.jpg", ocrText = ocr, createdIso = date, takenIso = date, dimensions = null,
                           camera = null, hasLocation = false, screenshot = true, sizeBytes = ocr.length.toLong())

    /** A file (PDF, text, HTML) with the text its extractor read. */
    fun file(id: String, date: String, kind: ItemKind, text: String): SourceItem = SourceItem(
        id = "files:$id", sourceId = "files", kind = kind, path = "files/$id", messageIndex = null, name = id, text = text,
        hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = ContentHash.of(text),
        mime = when (kind) { ItemKind.PDF -> "application/pdf"; ItemKind.HTML -> "text/html"; else -> "text/plain" },
        date = LocalDate.parse(date), dateOrigin = DateOrigin.FILE_MODIFIED, email = null, facts = emptyMap(),
    )

    /** An all-day calendar event with notes (the Calendar source's own builder). */
    fun event(id: String, date: String, title: String, notes: String): SourceItem =
        PhoneItems().event(eventId = id, title = title, startIso = date, endIso = null, allDay = true, location = null,
                           calendar = "Personal", organizer = null, attendees = emptyList(), recurrence = null, notes = notes)

    /** One statement row as the Inbox imports it (statement facts read by CsvRows at import). */
    fun csvRow(id: String, date: String, merchant: String, minor: Long, currency: String, direction: String? = "debit"): SourceItem {
        val facts = linkedMapOf("row" to "1", "file" to "statement.csv", "amount_minor" to minor.toString(),
                                "amount" to CsvRows.formatMinor(minor) + " " + currency, "currency" to currency, "merchant" to merchant)
        if (direction != null) facts["direction"] = direction
        val text = "$date, $merchant, ${CsvRows.formatMinor(minor)} $currency"
        return SourceItem(
            id = "inbox:$id", sourceId = "inbox", kind = ItemKind.CSV, path = "inbox/statement.csv#$id", messageIndex = null, name = merchant,
            text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = ContentHash.of(text),
            mime = "text/csv", date = LocalDate.parse(date), dateOrigin = DateOrigin.CSV_COLUMN, email = null, facts = facts,
        )
    }
}
