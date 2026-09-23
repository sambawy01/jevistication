package dev.loupe.sources.common

import dev.loupe.engine.ContentHash
import dev.loupe.engine.ContentType
import dev.loupe.engine.MimeFacts
import dev.loupe.engine.TextState
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Progress and cancellation for a scan; an interface so Swift can implement it. */
interface ScanObserver {
    fun onProgress(progress: ScanProgress) {}

    fun isCancelled(): Boolean = false
}

/**
 * Reads folders and mail exports into [SourceItem]s, read-only — the common port of
 * :sources-desktop's `Scanner`, rule for rule: the same kinds by extension, the same "extension
 * lies" check against the engine's `MimeFacts`, the same skip reasons, SHA-256 by `ContentHash`,
 * duplicates marked by hash with the same tiebreak, text cut with the engine's truncation marker.
 * Ids are the absolute path (plus `#n` in an mbox), exactly as the desktop's.
 */
class SourceScanner(
    private val extractors: PlatformExtractors,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val limits: Limits = Limits(),
) {
    /** For Swift, which does not see Kotlin default arguments: the device's time zone, default limits. */
    constructor(extractors: PlatformExtractors) : this(extractors, TimeZone.currentSystemDefault(), Limits())

    data class Limits(
        val maxFileBytes: Long = 50L * 1024 * 1024,
        val maxTextChars: Int = 20_000,
        val maxDepth: Int = 16,
        val maxMboxMessages: Int = 20_000,
    ) {
        init {
            require(maxFileBytes > 0 && maxTextChars > 100 && maxDepth > 0 && maxMboxMessages > 0)
        }
    }

    private class SkipFile(reason: String) : Exception(reason)

    @Throws(Exception::class)
    fun scan(sources: List<SourceRoot>, observer: ScanObserver = object : ScanObserver {}): ScanResult {
        val items = mutableListOf<SourceItem>()
        val skipped = mutableListOf<Skipped>()
        val unavailable = mutableListOf<Skipped>()
        val plan = mutableListOf<Pair<SourceRoot, List<String>>>()
        for (source in sources) {
            val root = source.path
            if (!SourceFs.exists(root)) {
                unavailable += Skipped(root, "not found — moved, renamed or on a disconnected drive")
                continue
            }
            if (!SourceFs.isReadable(root)) {
                unavailable += Skipped(root, "not readable — permission denied")
                continue
            }
            plan += source to (if (SourceFs.isDirectory(root)) listFiles(root, skipped) else listOf(root))
        }
        val total = plan.sumOf { it.second.size }
        var seen = 0
        outer@ for ((source, files) in plan) {
            for (file in files) {
                if (observer.isCancelled()) break@outer
                seen++
                observer.onProgress(ScanProgress(seen, total, items.size, skipped.size, file.substringAfterLast('/')))
                try {
                    items += readFile(source, file)
                } catch (e: Unreadable) {
                    skipped += Skipped(file, e.message ?: "unreadable")
                } catch (e: SkipFile) {
                    skipped += Skipped(file, e.message ?: "skipped")
                } catch (e: Exception) {
                    // A parser bug on one strange file must not end the scan of a whole folder.
                    skipped += Skipped(file, "could not read: ${e.message ?: e::class.simpleName}")
                }
            }
        }
        observer.onProgress(ScanProgress(seen, total, items.size, skipped.size, "done"))
        return ScanResult(markDuplicates(items), skipped, unavailable)
    }

    /** Regular files under [root] in path order; hidden files skipped silently, links and packages noted. */
    private fun listFiles(root: String, skipped: MutableList<Skipped>): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: String, depth: Int) {
            val entries = SourceFs.list(dir)
            if (entries == null) {
                skipped += Skipped(dir, "could not read: access denied")
                return
            }
            for (e in entries) {
                when {
                    e.isSymbolicLink -> skipped += Skipped(e.path, "symbolic link: not followed")
                    e.name.startsWith(".") -> Unit
                    e.isDirectory -> {
                        if (depth >= limits.maxDepth) continue
                        if (e.name.substringAfterLast('.', "").lowercase() in PACKAGES) {
                            skipped += Skipped(e.path, "app or library package: not read")
                        } else {
                            walk(e.path, depth + 1)
                        }
                    }
                    e.isRegularFile -> out += e.path
                    else -> skipped += Skipped(e.path, "not a regular file")
                }
            }
        }
        walk(root.trimEnd('/'), 1)
        return out.sorted()
    }

    private fun localDate(epochMillis: Long): LocalDate = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date

    private fun readFile(source: SourceRoot, file: String): List<SourceItem> {
        val size = SourceFs.size(file)
        if (size > limits.maxFileBytes) throw SkipFile("too large: over ${limits.maxFileBytes / (1024 * 1024)} MB")
        val name = file.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        val kind = KINDS[extension]
        if (extension == "mbox") return readMbox(source, file)
        if (kind == null) throw SkipFile(if (extension.isEmpty()) "unsupported type: no extension" else "unsupported type: .$extension")
        if (size == 0L) throw SkipFile("empty file")

        val bytes = SourceFs.readBytes(file)
        val sniffed = MimeFacts.sniff(bytes)
        if (sniffed != ContentType.UNKNOWN && sniffed !in (ACCEPTS[kind] ?: emptySet())) {
            throw SkipFile("extension lies: named .$extension but the bytes are ${sniffed.mime}")
        }
        val modified = localDate(SourceFs.modifiedMillis(file))
        val hash = ContentHash.of(bytes)
        val mime = if (sniffed != ContentType.UNKNOWN) sniffed.mime else MIME_BY_KIND.getValue(kind)

        return listOf(
            when (kind) {
                ItemKind.EMAIL -> emailItem(source, file, null, bytes, hash, size, modified)
                ItemKind.PDF -> {
                    val pdf = extractors.readPdf(file)
                    pdf.error?.let { throw Unreadable(it) }
                    val facts = linkedMapOf("pages" to pdf.pages.toString())
                    pdf.producer?.let { facts["producer"] = it }
                    val text = pdf.text.trim()
                    val hasText = text.count { it.isLetterOrDigit() } >= MIN_TEXT
                    if (!hasText) facts["text"] = "no text layer (a scan?) — no OCR on the desktop"
                    val created = pdf.createdIso?.let(LocalDate::parse)
                    item(source, file, kind, name, if (hasText) "File: $name\n\n$text" else "", hasText, size, hash, mime,
                        created ?: modified, if (created != null) DateOrigin.PDF_INFO else DateOrigin.FILE_MODIFIED, null, facts)
                }
                ItemKind.IMAGE -> {
                    val image = extractors.readImage(file)
                    val facts = LinkedHashMap(image.facts)
                    facts["text"] = "not read — no OCR on the desktop"
                    val taken = image.takenIso?.let(LocalDate::parse)
                    item(source, file, kind, name, "", false, size, hash, mime,
                        taken ?: modified, if (taken != null) DateOrigin.EXIF else DateOrigin.FILE_MODIFIED, null, facts)
                }
                else -> {
                    if (PlainText.looksBinary(bytes)) throw SkipFile("binary content: named .$extension but not text")
                    val decoded = PlainText.decode(bytes)
                    val text = if (kind == ItemKind.HTML) HtmlText.toText(decoded) else decoded.trim()
                    val facts = linkedMapOf<String, String>()
                    if (kind == ItemKind.CSV) facts["rows"] = decoded.lineSequence().count { it.isNotBlank() }.toString()
                    val links = Links.find(decoded)
                    if (links.isNotEmpty()) facts["links"] = links.size.toString()
                    val hasText = text.count { it.isLetterOrDigit() } >= MIN_TEXT
                    item(source, file, kind, name, if (hasText) "File: $name\n\n$text" else "", hasText, size, hash, mime,
                        modified, DateOrigin.FILE_MODIFIED, null, facts)
                }
            },
        )
    }

    private fun readMbox(source: SourceRoot, file: String): List<SourceItem> {
        val modified = localDate(SourceFs.modifiedMillis(file))
        val messages = Mbox.split(SourceFs.readBytes(file))
        if (messages.size > limits.maxMboxMessages) {
            throw Unreadable("mbox has more than ${limits.maxMboxMessages} messages: the rest were not read")
        }
        if (messages.isEmpty()) throw Unreadable("mbox contains no messages")
        return messages.mapIndexed { i, bytes ->
            emailItem(source, file, i + 1, bytes, ContentHash.of(bytes), bytes.size.toLong(), modified)
        }
    }

    private fun emailItem(source: SourceRoot, file: String, index: Int?, bytes: ByteArray, hash: String, size: Long, modified: LocalDate): SourceItem {
        val parsed = MimeParser.parseEmail(bytes, zone)
        val facts = linkedMapOf<String, String>()
        if (parsed.facts.links.isNotEmpty()) facts["links"] = parsed.facts.links.size.toString()
        if (parsed.facts.attachmentNames.isNotEmpty()) facts["attachments"] = parsed.facts.attachmentNames.joinToString(", ")
        val text = parsed.modelText()
        val date = parsed.facts.date
        return item(
            source, file, ItemKind.EMAIL, parsed.facts.subject ?: file.substringAfterLast('/'),
            text, text.isNotBlank(), size, hash, "message/rfc822",
            date ?: modified, if (date != null) DateOrigin.EMAIL_HEADER else DateOrigin.FILE_MODIFIED,
            parsed.facts, facts, index,
        )
    }

    private fun item(
        source: SourceRoot, file: String, kind: ItemKind, name: String, text: String, hasText: Boolean,
        size: Long, hash: String, mime: String, date: LocalDate?, dateOrigin: DateOrigin?,
        email: EmailFacts?, facts: Map<String, String>, index: Int? = null,
    ): SourceItem {
        val truncated = text.length > limits.maxTextChars
        val kept = if (truncated) text.take(limits.maxTextChars - TextState.TRUNCATION_MARKER.length) + TextState.TRUNCATION_MARKER else text
        return SourceItem(
            id = source.idFor(file).let { if (index == null) it else "$it#$index" },
            sourceId = source.id, kind = kind, path = file, messageIndex = index, name = name,
            text = kept, hasText = hasText, textTruncated = truncated, sizeBytes = size, contentHash = hash,
            mime = mime, date = date, dateOrigin = dateOrigin, email = email, facts = facts,
        )
    }

    /** Byte-identical copies point at the copy with the shortest file name, then the earliest id. */
    private fun markDuplicates(items: List<SourceItem>): List<SourceItem> {
        val canonical = items.groupBy { it.contentHash }
            .mapValues { (_, group) -> group.minWith(compareBy<SourceItem>({ it.fileName.length }, { it.id })).id }
        return items.map { item ->
            val first = canonical.getValue(item.contentHash)
            if (first == item.id) item else item.copy(duplicateOf = first)
        }
    }

    companion object {
        const val MIN_TEXT: Int = 12

        private val KINDS: Map<String, ItemKind> = mapOf(
            "txt" to ItemKind.TEXT, "text" to ItemKind.TEXT, "log" to ItemKind.TEXT,
            "md" to ItemKind.MARKDOWN, "markdown" to ItemKind.MARKDOWN,
            "csv" to ItemKind.CSV, "tsv" to ItemKind.CSV,
            "json" to ItemKind.JSON,
            "html" to ItemKind.HTML, "htm" to ItemKind.HTML,
            "eml" to ItemKind.EMAIL,
            "pdf" to ItemKind.PDF,
            "jpg" to ItemKind.IMAGE, "jpeg" to ItemKind.IMAGE, "png" to ItemKind.IMAGE, "gif" to ItemKind.IMAGE,
            "heic" to ItemKind.IMAGE, "webp" to ItemKind.IMAGE, "tif" to ItemKind.IMAGE, "tiff" to ItemKind.IMAGE,
        )

        private val ACCEPTS: Map<ItemKind, Set<ContentType>> = mapOf(
            ItemKind.PDF to setOf(ContentType.PDF),
            ItemKind.IMAGE to setOf(ContentType.PNG, ContentType.JPEG, ContentType.GIF),
        )

        private val MIME_BY_KIND: Map<ItemKind, String> = mapOf(
            ItemKind.TEXT to "text/plain", ItemKind.MARKDOWN to "text/markdown", ItemKind.CSV to "text/csv",
            ItemKind.JSON to "application/json", ItemKind.HTML to "text/html", ItemKind.EMAIL to "message/rfc822",
            ItemKind.PDF to "application/pdf", ItemKind.IMAGE to "image/*",
        )

        private val PACKAGES = setOf("app", "photoslibrary", "bundle", "framework", "musiclibrary", "tvlibrary", "pkg")
    }
}
