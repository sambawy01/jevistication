package dev.loupe.sources

import dev.loupe.engine.ContentHash
import dev.loupe.engine.ContentType
import dev.loupe.engine.MimeFacts
import dev.loupe.engine.TextState
import org.apache.james.mime4j.mboxiterator.MboxIterator
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads folders and mail exports into [SourceItem]s — **read-only, and nothing leaves the machine.**
 *
 * The contract, each part enforced here rather than hoped for:
 *
 * - **Read-only.** Files are opened for reading and nothing else; no file in a source is created,
 *   written, moved, renamed or deleted, and a test checks every byte and timestamp afterwards.
 *   Symbolic links are not followed, so a link cannot lead the scan outside the folder chosen.
 * - **Mechanical first.** Hash, MIME type (magic bytes over the extension, `MimeFacts`), size,
 *   dates, sender and links are read before any model is involved, and exact duplicates are found
 *   by hash.
 * - **Nothing is hidden.** Every file not read is recorded with its reason — too large, an
 *   unsupported type, an extension that lies about the bytes, encrypted, damaged — and shown as a
 *   count by reason.
 * - **Nothing is rewritten.** Extracted text is kept verbatim, cut only at [Limits.maxTextChars]
 *   with the engine's own truncation marker.
 */
class Scanner(
    private val limits: Limits = Limits(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    data class Limits(
        /** Larger files are skipped, with the reason shown. */
        val maxFileBytes: Long = 50L * 1024 * 1024,
        /** Extracted text kept per item; the engine's own state budget is smaller still. */
        val maxTextChars: Int = 20_000,
        /** How deep into sub-folders to go. */
        val maxDepth: Int = 16,
        /** Messages read from one mbox before the rest are skipped. */
        val maxMboxMessages: Int = 20_000,
    ) {
        init {
            require(maxFileBytes > 0 && maxTextChars > 100 && maxDepth > 0 && maxMboxMessages > 0)
        }
    }

    /**
     * Scans [sources] in order. [isCancelled] is polled between files; a cancelled scan returns
     * what it had read so far.
     */
    fun scan(
        sources: List<SourceSpec>,
        progress: (ScanProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ScanResult {
        val items = mutableListOf<SourceItem>()
        val skipped = mutableListOf<Skipped>()
        val unavailable = mutableListOf<Pair<SourceSpec, String>>()
        var seen = 0

        for (source in sources) {
            if (isCancelled()) break
            val root = source.path
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                unavailable += source to "not found — moved, renamed or on a disconnected drive"
                continue
            }
            if (!Files.isReadable(root)) {
                unavailable += source to "not readable — permission denied"
                continue
            }
            val files = if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) listFiles(root, skipped) else listOf(root)
            for (file in files) {
                if (isCancelled()) break
                seen++
                progress(ScanProgress(seen, items.size, skipped.size, file.fileName.toString()))
                try {
                    items += readFile(source, file)
                } catch (e: Unreadable) {
                    skipped += Skipped(file.toString(), e.message ?: "unreadable")
                } catch (e: SkipFile) {
                    skipped += Skipped(file.toString(), e.message ?: "skipped")
                } catch (e: IOException) {
                    skipped += Skipped(file.toString(), "could not read: ${e.message ?: e::class.simpleName}")
                } catch (e: RuntimeException) {
                    // A parser bug on one strange file must not end the scan of a whole folder.
                    skipped += Skipped(file.toString(), "could not read: ${e.message ?: e::class.simpleName}")
                }
            }
        }
        progress(ScanProgress(seen, items.size, skipped.size, "done"))
        return ScanResult(markDuplicates(items), skipped, unavailable)
    }

    private class SkipFile(reason: String) : Exception(reason)

    /** Regular files under [root], sorted so a scan is deterministic; links and hidden files noted. */
    private fun listFiles(root: Path, skipped: MutableList<Skipped>): List<Path> {
        val out = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            emptySet(),
            limits.maxDepth,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    val name = dir.fileName.toString()
                    if (name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                    if (name.substringAfterLast('.', "").lowercase() in PACKAGES) {
                        skipped += Skipped(dir.toString(), "app or library package: not read")
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    when {
                        attrs.isSymbolicLink -> skipped += Skipped(file.toString(), "symbolic link: not followed")
                        file.fileName.toString().startsWith(".") -> Unit // .DS_Store and friends: not user content
                        attrs.isRegularFile -> out.add(file)
                        else -> skipped += Skipped(file.toString(), "not a regular file")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    skipped += Skipped(file.toString(), "could not read: ${exc.message ?: "access denied"}")
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return out.sorted()
    }

    private fun readFile(source: SourceSpec, file: Path): List<SourceItem> {
        val size = Files.size(file)
        if (size > limits.maxFileBytes) throw SkipFile("too large: over ${limits.maxFileBytes / (1024 * 1024)} MB")
        val name = file.fileName.toString()
        val extension = name.substringAfterLast('.', "").lowercase()
        val kind = KINDS[extension]
        if (extension == "mbox") return readMbox(source, file)
        if (kind == null) {
            throw SkipFile(if (extension.isEmpty()) "unsupported type: no extension" else "unsupported type: .$extension")
        }
        if (size == 0L) throw SkipFile("empty file")

        val bytes = Files.readAllBytes(file)
        val sniffed = MimeFacts.sniff(bytes)
        if (sniffed != ContentType.UNKNOWN && sniffed !in ACCEPTS.getValue(kind)) {
            throw SkipFile("extension lies: named .$extension but the bytes are ${sniffed.mime}")
        }
        val modified = Files.getLastModifiedTime(file).toInstant().atZone(zone).toLocalDate()
        val hash = ContentHash.of(bytes)
        val mime = if (sniffed != ContentType.UNKNOWN) sniffed.mime else MIME_BY_KIND.getValue(kind)

        return listOf(
            when (kind) {
                ItemKind.EMAIL -> emailItem(source, file, null, bytes, hash, size)
                ItemKind.PDF -> {
                    val pdf = PdfExtractor.read(bytes, zone)
                    val facts = linkedMapOf("pages" to pdf.pages.toString())
                    pdf.producer?.let { facts["producer"] = it }
                    val hasText = pdf.text.count { it.isLetterOrDigit() } >= MIN_TEXT
                    if (!hasText) facts["text"] = "no text layer (a scan?) — no OCR on the desktop"
                    item(source, file, kind, name, if (hasText) "File: $name\n\n${pdf.text}" else "", hasText, size, hash, mime,
                        pdf.created ?: modified, if (pdf.created != null) DateOrigin.PDF_INFO else DateOrigin.FILE_MODIFIED, null, facts)
                }
                ItemKind.IMAGE -> {
                    val image = ImageExtractor.read(bytes, zone)
                    val facts = LinkedHashMap(image.facts)
                    facts["text"] = "not read — no OCR on the desktop"
                    item(source, file, kind, name, "", false, size, hash, mime,
                        image.taken ?: modified, if (image.taken != null) DateOrigin.EXIF else DateOrigin.FILE_MODIFIED, null, facts)
                }
                else -> {
                    if (PlainText.looksBinary(bytes)) throw SkipFile("binary content: named .$extension but not text")
                    val decoded = PlainText.decode(bytes)
                    val text = if (kind == ItemKind.HTML) HtmlText.toText(decoded) else decoded.trim()
                    val facts = linkedMapOf<String, String>()
                    if (kind == ItemKind.CSV) facts["rows"] = decoded.lineSequence().count { it.isNotBlank() }.toString()
                    val links = if (kind == ItemKind.HTML) Links.find(decoded) else Links.find(decoded)
                    if (links.isNotEmpty()) facts["links"] = links.size.toString()
                    val hasText = text.count { it.isLetterOrDigit() } >= MIN_TEXT
                    item(source, file, kind, name, if (hasText) "File: $name\n\n$text" else "", hasText, size, hash, mime,
                        modified, DateOrigin.FILE_MODIFIED, null, facts)
                }
            },
        )
    }

    private fun readMbox(source: SourceSpec, file: Path): List<SourceItem> {
        val out = mutableListOf<SourceItem>()
        val iterator = try {
            MboxIterator.fromFile(file.toFile())
                .charset(Charsets.UTF_8)
                .fromLine(MBOX_FROM_LINE)
                .maxMessageSize(limits.maxFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .build()
        } catch (e: Exception) {
            throw Unreadable("not a readable mbox: ${e.message ?: e::class.simpleName}")
        }
        iterator.use {
            var index = 0
            for (wrapper in it) {
                index++
                if (index > limits.maxMboxMessages) {
                    throw Unreadable("mbox has more than ${limits.maxMboxMessages} messages: the rest were not read")
                }
                val bytes = wrapper.asInputStream(Charsets.UTF_8).readAllBytes()
                out += emailItem(source, file, index, bytes, ContentHash.of(bytes), bytes.size.toLong())
            }
        }
        if (out.isEmpty()) throw Unreadable("mbox contains no messages")
        return out
    }

    private fun emailItem(source: SourceSpec, file: Path, index: Int?, bytes: ByteArray, hash: String, size: Long): SourceItem {
        val parsed = EmailExtractor.parse(bytes, zone)
        val facts = linkedMapOf<String, String>()
        if (parsed.facts.links.isNotEmpty()) facts["links"] = parsed.facts.links.size.toString()
        if (parsed.facts.attachmentNames.isNotEmpty()) facts["attachments"] = parsed.facts.attachmentNames.joinToString(", ")
        val text = parsed.modelText()
        val date = parsed.facts.date
        val modified = Files.getLastModifiedTime(file).toInstant().atZone(zone).toLocalDate()
        return item(
            source, file, ItemKind.EMAIL,
            parsed.facts.subject ?: file.fileName.toString(),
            text, text.isNotBlank(), size, hash, "message/rfc822",
            date ?: modified, if (date != null) DateOrigin.EMAIL_HEADER else DateOrigin.FILE_MODIFIED,
            parsed.facts, facts, index,
        )
    }

    private fun item(
        source: SourceSpec,
        file: Path,
        kind: ItemKind,
        name: String,
        text: String,
        hasText: Boolean,
        size: Long,
        hash: String,
        mime: String,
        date: LocalDate?,
        dateOrigin: DateOrigin?,
        email: EmailFacts?,
        facts: Map<String, String>,
        index: Int? = null,
    ): SourceItem {
        val truncated = text.length > limits.maxTextChars
        val kept = if (truncated) text.take(limits.maxTextChars - TextState.TRUNCATION_MARKER.length) + TextState.TRUNCATION_MARKER else text
        return SourceItem(
            id = if (index == null) file.toString() else "$file#$index",
            sourceId = source.id,
            kind = kind,
            path = file,
            messageIndex = index,
            name = name,
            text = kept,
            hasText = hasText,
            textTruncated = truncated,
            sizeBytes = size,
            contentHash = hash,
            mime = mime,
            date = date,
            dateOrigin = dateOrigin,
            email = email,
            facts = facts,
        )
    }

    /**
     * Marks byte-identical copies, by the SHA-256 already computed.
     *
     * Which copy is shown as the original is a display tiebreak, not a judgment: the one with the
     * shortest file name ("receipt.txt" over "receipt (copy).txt"), then the earliest path. Deciding
     * which copy is worth *keeping* — the better scan, the signed one — is the duplicate judgment's
     * job, and this never deletes anything.
     */
    private fun markDuplicates(items: List<SourceItem>): List<SourceItem> {
        val canonical = items.groupBy { it.contentHash }
            .mapValues { (_, group) -> group.minWith(compareBy<SourceItem>({ it.path.fileName.toString().length }, { it.id })).id }
        return items.map { item ->
            val first = canonical.getValue(item.contentHash)
            if (first == item.id) item else item.copy(duplicateOf = first)
        }
    }

    companion object {
        /** Fewer letters and digits than this is nothing to judge on. */
        const val MIN_TEXT: Int = 12

        /** mbox "From " separator lines; permissive about the sender field, strict about the year. */
        private const val MBOX_FROM_LINE = "^From \\S+.*\\d{4}$"

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

        /** Signatures a kind may legitimately carry. Anything else sniffed means the name lies. */
        private val ACCEPTS: Map<ItemKind, Set<ContentType>> = mapOf(
            ItemKind.PDF to setOf(ContentType.PDF),
            ItemKind.IMAGE to setOf(ContentType.PNG, ContentType.JPEG, ContentType.GIF),
        ).withDefault { emptySet() }

        private val MIME_BY_KIND: Map<ItemKind, String> = mapOf(
            ItemKind.TEXT to "text/plain", ItemKind.MARKDOWN to "text/markdown", ItemKind.CSV to "text/csv",
            ItemKind.JSON to "application/json", ItemKind.HTML to "text/html", ItemKind.EMAIL to "message/rfc822",
            ItemKind.PDF to "application/pdf", ItemKind.IMAGE to "image/*",
        )

        /** macOS bundles are directories but not folders of user documents. */
        private val PACKAGES = setOf("app", "photoslibrary", "bundle", "framework", "musiclibrary", "tvlibrary", "pkg")
    }
}
