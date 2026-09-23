package dev.loupe.sources.common

import dev.loupe.engine.Item
import kotlinx.datetime.LocalDate

/**
 * What an item was read as. The first eight are :sources-desktop's `ItemKind`, same names and order;
 * EVENT and CONTACT are the phone's (epic #7 child 7: EventKit and the Contacts framework), appended
 * so every desktop kind still maps by name.
 */
enum class ItemKind(val title: String) {
    TEXT("text"),
    MARKDOWN("markdown"),
    CSV("CSV"),
    JSON("JSON"),
    HTML("HTML"),
    EMAIL("email"),
    PDF("PDF"),
    IMAGE("image"),
    EVENT("calendar event"),
    CONTACT("contact"),
}

/** A folder of files, or a mail export. */
enum class SourceType(val title: String) {
    FOLDER("Folder"),
    MAIL_EXPORT("Mail export"),
}

/**
 * A place Loupe was asked to read, by absolute path. Read-only, always.
 *
 * Item ids are the absolute path, as on the desktop, unless [idPrefix] is set: then they are the
 * prefix plus the path relative to [path] (`sample:mail/inbox/x.eml`). The phone uses a prefix
 * because an iOS app's container path changes across installs, and ids must not.
 */
data class SourceRoot(val id: String, val type: SourceType, val path: String, val idPrefix: String? = null) {
    init {
        require(id.isNotBlank()) { "a source needs an id" }
    }

    fun idFor(file: String): String {
        val prefix = idPrefix ?: return file
        val root = path.trimEnd('/')
        return prefix + if (file.startsWith("$root/")) file.substring(root.length + 1) else file.substringAfterLast('/')
    }
}

/** The mechanical facts of an email, from its headers. */
data class EmailFacts(
    val fromName: String?,
    val fromAddress: String?,
    val to: List<String>,
    val subject: String?,
    val date: LocalDate?,
    /** Every `http(s)` link in the body, HTML `href`s included, in order, without duplicates. */
    val links: List<String>,
    val attachmentNames: List<String>,
)

/** Where an item's date came from — shown beside it, because a file date is weak evidence. */
enum class DateOrigin(val title: String) {
    EMAIL_HEADER("email Date header"),
    EXIF("photo EXIF"),
    PDF_INFO("PDF creation date"),
    FILE_MODIFIED("file modified time"),
    PHOTO_CREATED("photo library date"),
    EVENT_START("event start"),
    CSV_COLUMN("CSV date column"),
}

/**
 * One thing a source yielded: a file, or one message inside a mail export. The common twin of
 * :sources-desktop's `SourceItem`, with [path] as a string. Mechanical facts first (A3); [text] is
 * what the model reads, verbatim, prefixed with the headers or file name that identify it.
 */
data class SourceItem(
    /** The path, plus `#n` for the n-th message of an mbox — the desktop's id for the same file. */
    val id: String,
    val sourceId: String,
    val kind: ItemKind,
    val path: String,
    val messageIndex: Int?,
    val name: String,
    val text: String,
    val hasText: Boolean,
    val textTruncated: Boolean,
    val sizeBytes: Long,
    val contentHash: String,
    val mime: String,
    val date: LocalDate?,
    val dateOrigin: DateOrigin?,
    val email: EmailFacts?,
    val facts: Map<String, String>,
    val duplicateOf: String? = null,
) {
    fun toItem(): Item = Item(id, text)

    /** `yyyy-MM-dd`, or null: for Swift, which does not see kotlinx-datetime comfortably. */
    val dateIso: String? get() = date?.toString()

    val fileName: String get() = path.substringAfterLast('/')

    val location: String get() = if (messageIndex == null) path else "$path (message $messageIndex)"
}

/** A file that was not read, and why. Shown as a count by reason, never hidden. */
data class Skipped(val path: String, val reason: String)

/** The outcome of scanning a set of sources. */
data class ScanResult(
    val items: List<SourceItem>,
    val skipped: List<Skipped>,
    /** Sources that could not be opened at all, with the reason. */
    val unavailable: List<Skipped>,
) {
    fun countsByKind(): Map<ItemKind, Int> = items.groupingBy { it.kind }.eachCount()

    fun skippedByReason(): Map<String, Int> = skipped.groupingBy { it.reason.substringBefore(':') }.eachCount()

    val withoutText: Int get() = items.count { !it.hasText }

    val duplicates: Int get() = items.count { it.duplicateOf != null }

    companion object {
        val EMPTY: ScanResult = ScanResult(emptyList(), emptyList(), emptyList())
    }
}

/** Where a scan is, for a progress bar. [filesTotal] is known before the first file is read. */
data class ScanProgress(val filesSeen: Int, val filesTotal: Int, val itemsRead: Int, val skipped: Int, val current: String)

/** A file or message could not be read, for a reason worth showing the user. */
class Unreadable(reason: String) : Exception(reason)
