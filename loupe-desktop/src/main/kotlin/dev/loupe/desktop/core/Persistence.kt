package dev.loupe.desktop.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.loupe.engine.LedgerRow
import dev.loupe.persistence.CorrectionRecord
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.LedgerCodec
import dev.loupe.persistence.LedgerStore
import dev.loupe.persistence.PlatformFiles
import dev.loupe.sources.SourceSpec
import dev.loupe.sources.SourceType
import dev.loupe.templates.Baseline
import dev.loupe.templates.UserJudgment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A user's verdict on one decision: the item, under one judgment's exact wording, is [label].
 *
 * Corrections are **appended**, never edited, like the ledger they annotate — the latest one for a
 * (judgment, wording, item) wins, and a null [label] retracts the one before it (undo). Keying on
 * the criteria hash is deliberate: a correction given under old wording is not silently reused as
 * truth for a question that has since been reworded.
 */
data class Correction(
    val judgmentId: String,
    val criteriaHash: String,
    val itemId: String,
    /** The right answer, or null to retract the previous correction. */
    val label: String?,
    val at: Instant,
    /** True when the user confirmed the model's own answer rather than changing it. */
    val confirmed: Boolean,
)

/** What loading the stores found, including lines it could not read (never silently dropped). */
data class LoadReport(val ledgerRows: Int, val corrections: Int, val unreadableLines: Int)

/**
 * The app's persistence, in its home directory: plain files, human-readable, append-only where
 * the data is append-only.
 *
 * - `ledger.jsonl` — every decision, in **exactly the lossless format `Export.ledgerToJsonl`
 *   writes**: full distribution, propensity, criteria hash, item. Appended, never rewritten, so a
 *   crash mid-write loses at most the last line, which loading reports rather than hiding.
 * - `corrections.jsonl` — every correction and retraction, appended.
 * - `judgments.json` and `sources.json` — small, rewritten atomically (write, then rename).
 *
 * No SQLite: these files are already the portable format F4 exports, so the store is its own
 * export, and nothing native is added to the dependency set.
 */
class Store(val home: Path) {
    /** The ledger, corrections and judgments files: common code, shared with the iPhone app. */
    val files: LedgerStore = LedgerStore(home.toString())
    private val sourcesFile = home.resolve("sources.json")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // ------------------------------------------------------------------ ledger

    /** Appends [rows] and forces them to disk before returning. */
    fun appendLedger(rows: List<LedgerRow>) = files.appendLedger(rows)

    fun loadLedger(): Pair<List<LedgerRow>, Int> = files.readLedger().let { it.values to it.unreadableLines }

    // ------------------------------------------------------------------ corrections

    fun appendCorrection(correction: Correction) = files.appendCorrection(correction.toRecord())

    fun loadCorrections(): Pair<List<Correction>, Int> {
        val loaded = files.readCorrections()
        var bad = loaded.unreadableLines
        val out = loaded.values.mapNotNull { r ->
            runCatching { Correction(r.judgmentId, r.criteriaHash, r.itemId, r.label, Instant.parse(r.at), r.confirmed) }
                .onFailure { bad++ }.getOrNull()
        }
        return out to bad
    }

    // ------------------------------------------------------------------ judgments and sources

    fun saveJudgments(judgments: List<UserJudgment>) = files.saveJudgments(judgments)

    fun loadJudgments(): List<UserJudgment> = files.readJudgments()

    fun saveSources(sources: List<SourceSpec>) {
        val array = JsonArray().apply {
            sources.forEach { s ->
                add(JsonObject().apply {
                    addProperty("id", s.id)
                    addProperty("type", s.type.name)
                    addProperty("path", s.path.toString())
                })
            }
        }
        PlatformFiles.writeAtomically(sourcesFile.toString(), gson.toJson(array))
    }

    fun loadSources(): List<SourceSpec> {
        if (!Files.exists(sourcesFile)) return emptyList()
        return JsonParser.parseString(Files.readString(sourcesFile)).asJsonArray.mapNotNull { e ->
            runCatching {
                val o = e.asJsonObject
                SourceSpec(o.get("id").asString, SourceType.valueOf(o.get("type").asString), Path.of(o.get("path").asString))
            }.getOrNull()
        }
    }

    companion object {
        /** One ledger line, as `Export.ledgerToJsonl` writes it, back to a row. */
        fun parseRow(line: String): LedgerRow = LedgerCodec.parseRow(line)
    }
}

internal fun Correction.toRecord(): CorrectionRecord =
    CorrectionRecord(judgmentId, criteriaHash, itemId, label, at.toString(), confirmed)

/** A [UserJudgment] to and from JSON: the common codec (`dev.loupe.persistence.JudgmentCodec`). */
object JudgmentCodec {
    fun encode(j: UserJudgment): JsonValue.Obj = dev.loupe.persistence.JudgmentCodec.encode(j)

    fun decode(o: JsonValue.Obj): UserJudgment = dev.loupe.persistence.JudgmentCodec.decode(o)

    fun encodeBaseline(b: Baseline): JsonValue.Obj = dev.loupe.persistence.JudgmentCodec.encodeBaseline(b)

    fun decodeBaseline(o: JsonValue.Obj): Baseline = dev.loupe.persistence.JudgmentCodec.decodeBaseline(o)
}
