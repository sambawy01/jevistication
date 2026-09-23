package dev.loupe.sources.common

import dev.loupe.engine.ContentHash
import dev.loupe.engine.TextState
import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import dev.loupe.persistence.StoreLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** One import into the Inbox: what it was, where it came from, what it yielded. */
data class InboxBatch(
    val id: String,
    val name: String,
    /** Where it came from, in words: "Files", "Share sheet", "Pasted", "Test fixture". */
    val origin: String,
    val createdAtEpochMillis: Long,
    val files: List<String>,
    val itemCount: Int,
    val rowCount: Int,
    val emailCount: Int,
    val skippedCount: Int,
    /** Items whose content an earlier batch already brought in (marked `duplicateOf` that item). */
    val alreadyImported: Int,
) {
    /** The `SourceLibrary` id this batch's items are cached under. */
    val cacheId: String get() = "inbox-$id"

    /** "Imported · Files · statement.csv · 2026-09-24": on every item of the batch. */
    fun label(zone: TimeZone): String =
        "Imported · $origin · $name · ${Instant.fromEpochMilliseconds(createdAtEpochMillis).toLocalDateTime(zone).date}"
}

/**
 * The Inbox (epic #7 child 15): where imported things land — CSV files (each row an item, with its
 * column context), `.eml` and `.mbox` mail, ZIP archives (unpacked by [ZipReader], one level, with
 * its safety rules), and text or links shared from other apps. Ported from Loupe Station's
 * the `laya_studio/items` package (@ ea7697a) — `csvimport.py` (as [CsvRows]), `archive.py` (mail files, here
 * through the existing [SourceScanner] / `MimeParser`), `uploads.py` (size limits, safe names) and
 * the Sources view's removable imports — reshaped for the phone: no server, no SQLite.
 *
 * Every import is a batch under `<home>/inbox-batches/<batch id>/` (a private copy of what was imported,
 * plus what a ZIP unpacked to), cached as its own `SourceLibrary` source (`inbox-<batch id>`) and
 * listed in `<home>/sources/inbox.json`. Its items have source id [PhoneSourceIds.INBOX] and the fact
 * `imported` saying where they came from, so the scanner, sweep, watchers, privacy check and mail
 * triage read them like any other source's. Removing a batch removes its copy, its cache and its
 * items; nothing outside the Inbox's folder is ever changed.
 */
class Inbox(
    home: String,
    private val extractors: PlatformExtractors,
    private val zone: TimeZone,
    private val limits: Limits,
) {
    @Throws(Exception::class)
    constructor(home: String, extractors: PlatformExtractors) : this(home, extractors, TimeZone.currentSystemDefault(), Limits())

    data class Limits(
        /** Per imported file (a CSV has its own, Station's 20 MB). */
        val maxFileBytes: Long = 200L * 1024 * 1024,
        val maxFilesPerBatch: Int = 200,
        val maxTextChars: Int = 20_000,
        val zip: ZipReader.Limits = ZipReader.Limits(),
    )

    private val root = home.trimEnd('/')
    private val dir = "$root/inbox-batches"
    private val manifest = "$root/sources/inbox.json"
    private val lock = StoreLock()

    @Suppress("MemberVisibilityCanBePrivate")
    val library: SourceLibrary = SourceLibrary(root)

    init {
        InboxFs.createDirectories(dir)
    }

    fun isEnabled(): Boolean = library.isEnabled(PhoneSourceIds.INBOX, true)

    @Throws(Exception::class)
    fun setEnabled(on: Boolean) = library.setEnabled(PhoneSourceIds.INBOX, on)

    /** Every batch, newest first. */
    fun batches(): List<InboxBatch> = lock.withLock { readManifest() }.sortedByDescending { it.createdAtEpochMillis }

    fun cached(batchId: String): CachedScan? = batches().firstOrNull { it.id == batchId }?.let { library.cached(it.cacheId) }

    /** Items of every batch, oldest batch first — empty when the Inbox is switched off. */
    fun items(): List<SourceItem> =
        if (!isEnabled()) emptyList() else batches().reversed().flatMap { library.cached(it.cacheId)?.result?.items.orEmpty() }

    /**
     * Imports [paths] (files the user picked or shared) as one batch named [name]. Each file is
     * copied into the Inbox first, so the original is never read again or changed.
     */
    @Throws(Exception::class)
    fun importFiles(paths: List<String>, name: String, origin: String, nowEpochMillis: Long): InboxBatch {
        require(paths.isNotEmpty()) { "nothing to import" }
        require(paths.size <= limits.maxFilesPerBatch) { "at most ${limits.maxFilesPerBatch} files at a time" }
        val id = newId(nowEpochMillis)
        val files = "$dir/$id/files"
        InboxFs.createDirectories(files)
        val skipped = mutableListOf<Skipped>()
        val taken = mutableSetOf<String>()
        val staged = mutableListOf<String>()
        for (p in paths) {
            val base = safeName(p.substringAfterLast('/'))
            val shown = p.substringAfterLast('/')
            when {
                !SourceFs.exists(p) || SourceFs.isDirectory(p) -> skipped += Skipped(shown, "not a file")
                SourceFs.size(p) > limits.maxFileBytes -> skipped += Skipped(shown, "too large: over ${limits.maxFileBytes / (1024 * 1024)} MB")
                else -> {
                    val target = "$files/${unique(base, taken)}"
                    InboxFs.writeNew(target, SourceFs.readBytes(p))
                    staged += target
                }
            }
        }
        return ingest(id, name, origin, nowEpochMillis, staged, skipped)
    }

    /** Imports shared or pasted text as one batch; a lone link is kept as "Link shared to Loupe: …". */
    @Throws(Exception::class)
    fun importText(text: String, title: String, origin: String, nowEpochMillis: Long): InboxBatch {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "nothing to import" }
        val id = newId(nowEpochMillis)
        val files = "$dir/$id/files"
        InboxFs.createDirectories(files)
        val isLink = Regex("https?://\\S+").matches(trimmed)
        val body = if (isLink) "Link shared to Loupe: $trimmed\n" else "$trimmed\n"
        val name = safeName(title.ifBlank { if (isLink) "Link" else "Shared text" }).removeSuffix(".txt") + ".txt"
        InboxFs.writeNew("$files/$name", body.encodeToByteArray())
        return ingest(id, title.ifBlank { name }, origin, nowEpochMillis, listOf("$files/$name"), emptyList())
    }

    /** Removes a batch: its private copy, its cache, its items. False if there was no such batch. */
    @Throws(Exception::class)
    fun remove(batchId: String): Boolean = lock.withLock {
        val all = readManifest()
        val batch = all.firstOrNull { it.id == batchId } ?: return@withLock false
        writeManifest(all.filter { it.id != batchId })
        library.forget(batch.cacheId)
        InboxFs.deleteRecursively("$dir/${batch.id}")
        true
    }

    // ------------------------------------------------------------------ reading a batch

    private fun ingest(id: String, name: String, origin: String, now: Long, staged: List<String>, early: List<Skipped>): InboxBatch {
        val items = mutableListOf<SourceItem>()
        val skipped = early.toMutableList()
        val unavailable = mutableListOf<Skipped>()
        val base = "$dir/$id"
        var zips = 0
        for (file in staged) {
            val fileName = file.substringAfterLast('/')
            if (ext(fileName) == "zip") {
                zips++
                val out = "$base/unpacked/$zips-${fileName.substringBeforeLast('.')}"
                val result = ZipReader(limits.zip).read(SourceFs.readBytes(file))
                if (result.refused != null) {
                    skipped += Skipped(fileName, result.refused)
                    continue
                }
                skipped += result.skipped.map { Skipped("$fileName/${it.path}", it.reason) }
                val written = mutableListOf<Pair<String, String>>()
                for (e in result.entries) {
                    val target = "$out/${e.path}"
                    InboxFs.createDirectories(target.substringBeforeLast('/'))
                    InboxFs.writeNew(target, e.bytes)
                    written += target to "$fileName/${e.path}"
                }
                for ((path, shown) in written.sortedBy { it.second }) readOne(id, path, shown, items, skipped)
            } else {
                readOne(id, file, fileName, items, skipped)
            }
        }
        // Label every item with where it came from, then mark what an earlier batch already has.
        val draft = InboxBatch(id, name.take(200), origin, now, staged.map { it.substringAfterLast('/') }, 0, 0, 0, 0, 0)
        val label = draft.label(zone)
        val earlier = items()
            .filter { it.duplicateOf == null }
            .associateBy({ it.contentHash }, { it.id })
        val labelled = items.map { it.copy(facts = LinkedHashMap(it.facts).apply { put("imported", label) }) }
        val firstInBatch = labelled.groupBy { it.contentHash }.mapValues { (_, g) -> g.minWith(compareBy<SourceItem>({ it.fileName.length }, { it.id })).id }
        var already = 0
        val marked = labelled.map { item ->
            val prior = earlier[item.contentHash]
            when {
                prior != null -> { already++; item.copy(duplicateOf = prior) }
                firstInBatch.getValue(item.contentHash) != item.id -> item.copy(duplicateOf = firstInBatch.getValue(item.contentHash))
                else -> item
            }
        }
        val result = ScanResult(marked, skipped, unavailable)
        val batch = draft.copy(
            itemCount = marked.size,
            rowCount = marked.count { it.facts["row"] != null },
            emailCount = marked.count { it.kind == ItemKind.EMAIL },
            skippedCount = skipped.size,
            alreadyImported = already,
        )
        library.store(batch.cacheId, result, now)
        lock.withLock { writeManifest(readManifest().filter { it.id != id } + batch) }
        return batch
    }

    /** One file of a batch: a CSV as rows, anything else through the common scanner. */
    private fun readOne(batchId: String, path: String, shown: String, items: MutableList<SourceItem>, skipped: MutableList<Skipped>) {
        val rel = shown
        val idPrefix = "inbox:$batchId/" + rel.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val e = ext(path)
        if (e == "csv" || e == "tsv") {
            try {
                items += csvItems(batchId, path, rel)
            } catch (x: Unreadable) {
                skipped += Skipped(rel, x.message ?: "unreadable")
            }
            return
        }
        val scan = SourceScanner(extractors, zone, SourceScanner.Limits(maxTextChars = limits.maxTextChars))
            .scan(listOf(SourceRoot(PhoneSourceIds.INBOX, SourceType.FOLDER, path, idPrefix)))
        items += scan.items.map { it.copy(duplicateOf = null) }
        skipped += scan.skipped.map { Skipped(rel, it.reason) } + scan.unavailable.map { Skipped(rel, it.reason) }
    }

    /** Each row of a CSV as an item: the row's cells under their column names, plus statement facts. */
    private fun csvItems(batchId: String, path: String, rel: String): List<SourceItem> {
        val size = SourceFs.size(path)
        if (size > CsvRows.MAX_CSV_BYTES) throw Unreadable("too large for a CSV: over ${CsvRows.MAX_CSV_BYTES / (1024 * 1024)} MB")
        if (size == 0L) throw Unreadable("empty file")
        val bytes = SourceFs.readBytes(path)
        if (PlainText.looksBinary(bytes)) throw Unreadable("binary content: named .${ext(path)} but not text")
        val table = CsvRows.read(CsvRows.decode(bytes))
        if (table.rows.isEmpty()) throw Unreadable("no rows")
        val fileName = rel.substringAfterLast('/')
        val occurrences = mutableMapOf<String, Int>()
        val out = table.rows.map { row ->
            val text = buildString {
                append("Row ").append(row.number).append(" of ").append(fileName)
                append(" (columns: ").append(table.headers.joinToString(", ")).append(")\n")
                for ((i, h) in table.headers.withIndex()) {
                    val v = row.cells.getOrNull(i).orEmpty()
                    if (v.isNotEmpty()) append(h).append(": ").append(v).append('\n')
                }
                row.cells.drop(table.headers.size).filter { it.isNotEmpty() }.forEach { append("(extra): ").append(it).append('\n') }
            }.trimEnd()
            val m = row.money
            val facts = linkedMapOf("row" to row.number.toString(), "file" to fileName, "columns" to table.headers.joinToString(", "))
            if (m != null) {
                facts["amount_minor"] = m.amountMinor.toString()
                facts["amount"] = CsvRows.formatMinor(m.amountMinor) + (m.currency?.let { " $it" } ?: "")
                m.currency?.let { facts["currency"] = it }
                m.direction?.let { facts["direction"] = it }
                m.merchant?.let { facts["merchant"] = it }
                m.description?.let { facts["description"] = it }
            }
            // Station's row identity: normalised content plus its occurrence number within the file,
            // so the same statement imported twice gives the same hashes (and is marked, not doubled).
            val key = if (m != null) {
                listOf(m.date.toString(), m.amountMinor.toString(), m.currency.orEmpty(), m.merchant.orEmpty().lowercase(), m.description.orEmpty().lowercase()).joinToString("|")
            } else {
                row.cells.joinToString("\u001f")
            }
            val n = (occurrences[key] ?: 0) + 1
            occurrences[key] = n
            val truncated = text.length > limits.maxTextChars
            val kept = if (truncated) text.take(limits.maxTextChars - TextState.TRUNCATION_MARKER.length) + TextState.TRUNCATION_MARKER else text
            val hasText = text.count { it.isLetterOrDigit() } >= SourceScanner.MIN_TEXT
            SourceItem(
                id = "inbox:$batchId/$rel#row${row.number}", sourceId = PhoneSourceIds.INBOX, kind = ItemKind.CSV, path = rel,
                messageIndex = null, name = m?.merchant ?: m?.description ?: row.cells.firstOrNull { it.isNotEmpty() }?.take(80) ?: "Row ${row.number}",
                text = kept, hasText = hasText, textTruncated = truncated, sizeBytes = text.length.toLong(),
                contentHash = ContentHash.of("csv-row\u0000$key#$n"), mime = "text/csv",
                date = m?.date, dateOrigin = m?.let { DateOrigin.CSV_COLUMN }, email = null, facts = facts,
            )
        }
        return out
    }

    // ------------------------------------------------------------------ manifest

    private fun newId(now: Long): String = lock.withLock {
        val ids = readManifest().map { it.id }.toSet()
        var n = 0
        var id = "b$now"
        while (id in ids || SourceFs.exists("$dir/$id")) id = "b$now-${++n}"
        id
    }

    private fun readManifest(): List<InboxBatch> {
        val text = PlatformFiles.readText(manifest) ?: return emptyList()
        return try {
            JsonValue.parse(text).asObj["batches"]!!.asArr.items.map { v ->
                val o = v.asObj
                InboxBatch(
                    id = o["id"]!!.asString, name = o["name"]!!.asString, origin = o["origin"]!!.asString,
                    createdAtEpochMillis = o["createdAt"]!!.asString.toLong(),
                    files = o["files"]!!.asArr.items.map { it.asString },
                    itemCount = o["items"]!!.asInt, rowCount = o["rows"]!!.asInt, emailCount = o["emails"]!!.asInt,
                    skippedCount = o["skipped"]!!.asInt, alreadyImported = o["alreadyImported"]!!.asInt,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeManifest(batches: List<InboxBatch>) {
        val obj = JsonValue.obj(
            "version" to JsonValue.num(1),
            "batches" to JsonValue.Arr(
                batches.map { b ->
                    JsonValue.obj(
                        "id" to JsonValue.str(b.id), "name" to JsonValue.str(b.name), "origin" to JsonValue.str(b.origin),
                        "createdAt" to JsonValue.str(b.createdAtEpochMillis.toString()), "files" to JsonValue.strings(b.files),
                        "items" to JsonValue.num(b.itemCount), "rows" to JsonValue.num(b.rowCount), "emails" to JsonValue.num(b.emailCount),
                        "skipped" to JsonValue.num(b.skippedCount), "alreadyImported" to JsonValue.num(b.alreadyImported),
                    )
                },
            ),
        )
        PlatformFiles.createDirectories(manifest.substringBeforeLast('/'))
        PlatformFiles.writeAtomically(manifest, JsonText.pretty(obj))
    }

    companion object {
        private fun ext(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

        private fun unique(name: String, taken: MutableSet<String>): String {
            var candidate = name
            var n = 2
            while (!taken.add(candidate.lowercase())) {
                val dot = name.lastIndexOf('.')
                candidate = if (dot > 0) "${name.substring(0, dot)} $n${name.substring(dot)}" else "$name $n"
                n++
            }
            return candidate
        }

        /** Station's `uploads.safe_name`: a base name only, odd characters replaced, no leading dot. */
        fun safeName(name: String): String {
            val base = name.replace('\\', '/').substringAfterLast('/').ifEmpty { "upload" }
            val cleaned = base.map { c -> if (c.isLetterOrDigit() || c in "._- ()") c else '_' }.joinToString("").trim(' ', '.')
            return cleaned.ifEmpty { "upload" }.take(120)
        }
    }
}
