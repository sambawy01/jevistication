package dev.loupe.sources.common

import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import dev.loupe.persistence.StoreLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** One source's last scan, as cached: when, what it yielded, what it skipped. */
data class CachedScan(val sourceId: String, val scannedAtEpochMillis: Long, val result: ScanResult) {
    val itemCount: Int get() = result.items.size
    val scannedAtIso: String get() = Instant.fromEpochMilliseconds(scannedAtEpochMillis).toString()
}

/**
 * Scan results kept on the device (epic #7 child 2), so Judgments and the Now watchers read items
 * without rescanning: one JSON file per source under `<home>/sources/`, written atomically through
 * :persistence, plus which sources are switched on. Nothing leaves the device.
 */
class SourceLibrary @Throws(Exception::class) constructor(home: String) {
    private val dir = home.trimEnd('/') + "/sources"
    private val lock = StoreLock()

    init {
        PlatformFiles.createDirectories(dir)
    }

    private fun scanFile(sourceId: String): String {
        require(sourceId.matches(Regex("[A-Za-z0-9._-]+"))) { "bad source id: $sourceId" }
        return "$dir/scan-$sourceId.json"
    }

    private val enabledFile = "$dir/enabled.json"

    /** Stores [result] as [sourceId]'s latest scan, stamped now. */
    @Throws(Exception::class)
    fun store(sourceId: String, result: ScanResult): CachedScan = store(sourceId, result, Clock.System.now().toEpochMilliseconds())

    /** Stores [result] as [sourceId]'s latest scan, replacing the previous one. */
    @Throws(Exception::class)
    fun store(sourceId: String, result: ScanResult, scannedAtEpochMillis: Long): CachedScan =
        lock.withLock {
            val scan = CachedScan(sourceId, scannedAtEpochMillis, result)
            PlatformFiles.writeAtomically(scanFile(sourceId), JsonText.compact(ScanCodec.encode(scan)))
            scan
        }

    /** [sourceId]'s latest scan, or null if it was never scanned (or the cache is unreadable). */
    fun cached(sourceId: String): CachedScan? = lock.withLock {
        val text = PlatformFiles.readText(scanFile(sourceId)) ?: return@withLock null
        try {
            ScanCodec.decode(JsonValue.parse(text).asObj)
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes [sourceId]'s cached scan (an Inbox batch that was removed). */
    fun forget(sourceId: String): Unit = lock.withLock { InboxFs.deleteRecursively(scanFile(sourceId)) }

    /** Whether a source is on; [default] until the user has chosen. */
    fun isEnabled(sourceId: String, default: Boolean): Boolean = lock.withLock {
        val obj = PlatformFiles.readText(enabledFile)?.let { runCatching { JsonValue.parse(it).asObj }.getOrNull() }
        obj?.get(sourceId)?.let { runCatching { it.asBoolean }.getOrNull() } ?: default
    }

    @Throws(Exception::class)
    fun setEnabled(sourceId: String, enabled: Boolean): Unit = lock.withLock {
        val obj = PlatformFiles.readText(enabledFile)?.let { runCatching { JsonValue.parse(it).asObj }.getOrNull() }
            ?: JsonValue.Obj(linkedMapOf())
        obj.fields[sourceId] = JsonValue.Bool(enabled)
        PlatformFiles.writeAtomically(enabledFile, JsonText.pretty(obj))
    }

    /** Items of every listed source that is switched on and has been scanned — what later tabs judge. */
    fun items(sourceIds: List<String>, defaultEnabled: Boolean = true): List<SourceItem> =
        sourceIds.filter { isEnabled(it, defaultEnabled) }.flatMap { cached(it)?.result?.items.orEmpty() }
}

/** The cache's JSON: every field of [SourceItem], so a read-back item equals the scanned one. */
internal object ScanCodec {
    private fun s(v: String?) = JsonValue.str(v)

    fun encode(scan: CachedScan): JsonValue.Obj = JsonValue.obj(
        "version" to JsonValue.num(1),
        "sourceId" to s(scan.sourceId),
        "scannedAt" to JsonValue.Num(scan.scannedAtEpochMillis.toString()),
        "items" to JsonValue.Arr(scan.result.items.map(::item)),
        "skipped" to JsonValue.Arr(scan.result.skipped.map { JsonValue.obj("path" to s(it.path), "reason" to s(it.reason)) }),
        "unavailable" to JsonValue.Arr(scan.result.unavailable.map { JsonValue.obj("path" to s(it.path), "reason" to s(it.reason)) }),
    )

    private fun item(i: SourceItem): JsonValue = JsonValue.obj(
        "id" to s(i.id), "sourceId" to s(i.sourceId), "kind" to s(i.kind.name), "path" to s(i.path),
        "messageIndex" to (i.messageIndex?.let { JsonValue.num(it) } ?: JsonValue.Null),
        "name" to s(i.name), "text" to s(i.text), "hasText" to JsonValue.Bool(i.hasText),
        "textTruncated" to JsonValue.Bool(i.textTruncated), "sizeBytes" to JsonValue.Num(i.sizeBytes.toString()),
        "contentHash" to s(i.contentHash), "mime" to s(i.mime), "date" to s(i.date?.toString()),
        "dateOrigin" to s(i.dateOrigin?.name),
        "email" to (i.email?.let(::email) ?: JsonValue.Null),
        "facts" to JsonValue.Obj(LinkedHashMap(i.facts.mapValues { JsonValue.Str(it.value) })),
        "duplicateOf" to s(i.duplicateOf),
    )

    private fun email(e: EmailFacts): JsonValue = JsonValue.obj(
        "fromName" to s(e.fromName), "fromAddress" to s(e.fromAddress), "to" to JsonValue.strings(e.to),
        "subject" to s(e.subject), "date" to s(e.date?.toString()), "links" to JsonValue.strings(e.links),
        "attachmentNames" to JsonValue.strings(e.attachmentNames),
    )

    private fun JsonValue.Obj.opt(k: String): String? = this[k]?.takeUnless { it.isNull }?.asString

    private fun JsonValue.Obj.req(k: String): String = opt(k) ?: throw IllegalArgumentException("missing '$k'")

    private fun strings(v: JsonValue?): List<String> = v?.asArr?.items?.map { it.asString }.orEmpty()

    fun decode(o: JsonValue.Obj): CachedScan {
        require(o["version"]?.asInt == 1) { "unknown cache version" }
        val items = o["items"]!!.asArr.items.map { v ->
            val i = v.asObj
            SourceItem(
                id = i.req("id"), sourceId = i.req("sourceId"), kind = ItemKind.valueOf(i.req("kind")), path = i.req("path"),
                messageIndex = i["messageIndex"]?.takeUnless { it.isNull }?.asInt, name = i.req("name"), text = i.req("text"),
                hasText = i["hasText"]!!.asBoolean, textTruncated = i["textTruncated"]!!.asBoolean,
                sizeBytes = i.req("sizeBytes").toLong(), contentHash = i.req("contentHash"), mime = i.req("mime"),
                date = i.opt("date")?.let(LocalDate::parse), dateOrigin = i.opt("dateOrigin")?.let(DateOrigin::valueOf),
                email = i["email"]?.takeUnless { it.isNull }?.asObj?.let { e ->
                    EmailFacts(e.opt("fromName"), e.opt("fromAddress"), strings(e["to"]), e.opt("subject"),
                        e.opt("date")?.let(LocalDate::parse), strings(e["links"]), strings(e["attachmentNames"]))
                },
                facts = i["facts"]!!.asObj.fields.mapValues { it.value.asString },
                duplicateOf = i.opt("duplicateOf"),
            )
        }
        fun skips(k: String) = o[k]?.asArr?.items.orEmpty().map { Skipped(it.asObj.req("path"), it.asObj.req("reason")) }
        return CachedScan(o.req("sourceId"), o.req("scannedAt").toLong(), ScanResult(items, skips("skipped"), skips("unavailable")))
    }
}
