package dev.loupe.sources

import dev.loupe.engine.Item
import java.nio.file.Path
import java.time.LocalDate

/** What an item was read as. */
enum class ItemKind(val title: String) {
    TEXT("text"),
    MARKDOWN("markdown"),
    CSV("CSV"),
    JSON("JSON"),
    HTML("HTML"),
    EMAIL("email"),
    PDF("PDF"),
    IMAGE("image"),
}

/** A folder of files, or a mail export: an `.mbox` file, an `.eml` file, or a folder of them. */
enum class SourceType(val title: String) {
    FOLDER("Folder"),
    MAIL_EXPORT("Mail export"),
}

/** A place the user asked Loupe to read. Read-only, always. */
data class SourceSpec(val id: String, val type: SourceType, val path: Path) {
    init {
        require(id.isNotBlank()) { "a source needs an id" }
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
}

/**
 * One thing a source yielded: a file, or one message inside a mail export.
 *
 * The **mechanical facts** — hash, MIME type, size, date, sender, links, duplicate-of — were
 * computed before any model is asked anything (A3), and are shown to the user as facts. [text] is
 * what the model reads: the extracted text, never rewritten, prefixed with the headers or file name
 * that identify it. Nothing here is a summary.
 */
data class SourceItem(
    /** Stable across scans of the same place: the path, plus `#n` for the n-th message of an mbox. */
    val id: String,
    val sourceId: String,
    val kind: ItemKind,
    /** The file this came from. For a message inside an mbox, the mbox file. */
    val path: Path,
    /** The n-th message of an mbox (from 1), or null for a whole file. */
    val messageIndex: Int?,
    /** A short name for lists: the file name, or the email's subject. */
    val name: String,
    /** What the model reads. Empty when [hasText] is false. */
    val text: String,
    /**
     * False when there is nothing to judge on — an image (no OCR on the desktop) or a PDF with no
     * text layer (a scan). Such items are counted and shown but never sent to the model: a model
     * handed a file name and three stray characters will answer confidently about noise.
     */
    val hasText: Boolean,
    /** True when [text] was cut at [Scanner.Limits.maxTextChars], with the marker appended. */
    val textTruncated: Boolean,
    val sizeBytes: Long,
    /** SHA-256 of the raw bytes (for an mbox message, of the message's own bytes). */
    val contentHash: String,
    /** The content type by signature where one is known, else by extension. */
    val mime: String,
    val date: LocalDate?,
    val dateOrigin: DateOrigin?,
    val email: EmailFacts?,
    /** Anything else read mechanically: page count, image size, camera, and so on. */
    val facts: Map<String, String>,
    /** The id of the first item seen with byte-identical content, or null if this is the first. */
    val duplicateOf: String? = null,
) {
    /** The engine's view of this item. */
    fun toItem(): Item = Item(id, text)

    /** Where it lives, for display: the path, plus the message number inside an mbox. */
    val location: String get() = if (messageIndex == null) path.toString() else "$path (message $messageIndex)"
}

/** A file that was not read, and why. Shown as a count by reason, never hidden. */
data class Skipped(val path: String, val reason: String)

/** The outcome of scanning a set of sources. */
data class ScanResult(
    val items: List<SourceItem>,
    val skipped: List<Skipped>,
    /** Sources that could not be opened at all (moved, deleted, permission denied). */
    val unavailable: List<Pair<SourceSpec, String>>,
) {
    fun countsByKind(): Map<ItemKind, Int> = items.groupingBy { it.kind }.eachCount()

    fun skippedByReason(): Map<String, Int> =
        skipped.groupingBy { it.reason.substringBefore(':') }.eachCount()

    /** Items with no text to judge on. */
    val withoutText: Int get() = items.count { !it.hasText }

    /** Items that are byte-identical copies of an earlier one. */
    val duplicates: Int get() = items.count { it.duplicateOf != null }

    companion object {
        val EMPTY: ScanResult = ScanResult(emptyList(), emptyList(), emptyList())
    }
}

/** Where a scan is, for a progress bar. */
data class ScanProgress(val filesSeen: Int, val itemsRead: Int, val skipped: Int, val current: String)
