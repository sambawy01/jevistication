package dev.loupe.desktop.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.loupe.engine.Distribution
import dev.loupe.engine.Export
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Probability
import dev.loupe.sources.SourceSpec
import dev.loupe.sources.SourceType
import dev.loupe.templates.Baseline
import dev.loupe.templates.MechanicalCheck
import dev.loupe.templates.Shape
import dev.loupe.templates.SourceKind
import dev.loupe.templates.UserJudgment
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
    private val ledgerFile = home.resolve("ledger.jsonl")
    private val correctionsFile = home.resolve("corrections.jsonl")
    private val judgmentsFile = home.resolve("judgments.json")
    private val sourcesFile = home.resolve("sources.json")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()

    init {
        Files.createDirectories(home)
    }

    // ------------------------------------------------------------------ ledger

    /** Appends [rows] and forces them to disk before returning. */
    fun appendLedger(rows: List<LedgerRow>) {
        if (rows.isEmpty()) return
        append(ledgerFile, Export.ledgerToJsonl(rows) + "\n")
    }

    fun loadLedger(): Pair<List<LedgerRow>, Int> = readLines(ledgerFile) { parseRow(it) }

    // ------------------------------------------------------------------ corrections

    fun appendCorrection(correction: Correction) {
        val o = JsonObject().apply {
            addProperty("judgmentId", correction.judgmentId)
            addProperty("criteriaHash", correction.criteriaHash)
            addProperty("itemId", correction.itemId)
            if (correction.label == null) add("label", null) else addProperty("label", correction.label)
            addProperty("at", correction.at.toString())
            addProperty("confirmed", correction.confirmed)
        }
        append(correctionsFile, o.toString() + "\n")
    }

    fun loadCorrections(): Pair<List<Correction>, Int> = readLines(correctionsFile) { line ->
        val o = JsonParser.parseString(line).asJsonObject
        Correction(
            judgmentId = o.str("judgmentId"),
            criteriaHash = o.str("criteriaHash"),
            itemId = o.str("itemId"),
            label = o.get("label")?.takeUnless { it.isJsonNull }?.asString,
            at = Instant.parse(o.str("at")),
            confirmed = o.get("confirmed")?.asBoolean ?: false,
        )
    }

    // ------------------------------------------------------------------ judgments and sources

    fun saveJudgments(judgments: List<UserJudgment>) {
        val array = JsonArray().apply { judgments.forEach { add(JudgmentCodec.encode(it)) } }
        writeAtomically(judgmentsFile, gson.toJson(array))
    }

    fun loadJudgments(): List<UserJudgment> {
        if (!Files.exists(judgmentsFile)) return emptyList()
        val array = JsonParser.parseString(Files.readString(judgmentsFile)).asJsonArray
        return array.mapNotNull { runCatching { JudgmentCodec.decode(it.asJsonObject) }.getOrNull() }
    }

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
        writeAtomically(sourcesFile, gson.toJson(array))
    }

    fun loadSources(): List<SourceSpec> {
        if (!Files.exists(sourcesFile)) return emptyList()
        return JsonParser.parseString(Files.readString(sourcesFile)).asJsonArray.mapNotNull { e ->
            runCatching {
                val o = e.asJsonObject
                SourceSpec(o.str("id"), SourceType.valueOf(o.str("type")), Path.of(o.str("path")))
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun append(file: Path, text: String) = synchronized(lock) {
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buffer = StandardCharsets.UTF_8.encode(text)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(false)
        }
    }

    private fun writeAtomically(file: Path, text: String) = synchronized(lock) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Parses each non-blank line; a line that fails is counted, not thrown. */
    private fun <T> readLines(file: Path, parse: (String) -> T): Pair<List<T>, Int> {
        if (!Files.exists(file)) return emptyList<T>() to 0
        var bad = 0
        val out = ArrayList<T>()
        Files.newBufferedReader(file).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                runCatching { parse(line) }.onSuccess { out += it }.onFailure { bad++ }
            }
        }
        return out to bad
    }

    companion object {
        /** One ledger line, as `Export.ledgerToJsonl` writes it, back to a row. */
        fun parseRow(line: String): LedgerRow {
            val o = JsonParser.parseString(line).asJsonObject
            val dist = o.getAsJsonObject("distribution")
            val masses = LinkedHashMap<String, Double>()
            for ((label, mass) in dist.entrySet()) masses[label] = mass.asDouble
            return LedgerRow(
                judgmentId = o.str("judgmentId"),
                criteriaHash = o.str("criteriaHash"),
                distribution = Distribution.of(masses),
                action = o.str("action"),
                propensity = Probability.of(o.get("propensity").asDouble),
                correction = o.optStr("correction"),
                failure = o.optStr("failure"),
                itemId = o.optStr("itemId"),
            )
        }

        private fun JsonObject.str(key: String): String =
            get(key)?.takeUnless { it.isJsonNull }?.asString ?: throw IllegalArgumentException("missing '$key'")

        private fun JsonObject.optStr(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    }
}

/** A [UserJudgment] to and from JSON, every field explicit so the file reads on its own. */
object JudgmentCodec {
    fun encode(j: UserJudgment): JsonObject = JsonObject().apply {
        addProperty("id", j.id)
        addProperty("title", j.title)
        addProperty("question", j.question)
        add("shape", encodeShape(j.shape))
        addProperty("invariant", j.invariant)
        addProperty("breaks", j.breaks)
        addProperty("lookalikes", j.lookalikes)
        addProperty("onFailure", j.onFailure.name)
        add("sources", JsonArray().apply { j.sources.forEach { add(it.name) } })
        j.baseline?.let { add("baseline", encodeBaseline(it)) }
        j.templateId?.let { addProperty("templateId", it) }
        add("parameters", JsonObject().apply { j.parameters.forEach { (k, v) -> addProperty(k, v) } })
        addProperty("warnOnly", j.warnOnly)
        j.mechanical?.let { addProperty("mechanical", it.name) }
        j.desktopNote?.let { addProperty("desktopNote", it) }
        addProperty("threshold", j.threshold)
        // Written for a reader of the file; recomputed from the wording on load, never trusted.
        addProperty("criteriaHash", j.criteriaHash)
    }

    fun decode(o: JsonObject): UserJudgment = UserJudgment(
        id = o.get("id").asString,
        title = o.get("title").asString,
        question = o.get("question").asString,
        shape = decodeShape(o.getAsJsonObject("shape")),
        invariant = o.get("invariant").asString,
        breaks = o.get("breaks").asString,
        lookalikes = o.get("lookalikes").asString,
        onFailure = FailurePosture.valueOf(o.get("onFailure").asString),
        sources = o.getAsJsonArray("sources").mapNotNull { runCatching { SourceKind.valueOf(it.asString) }.getOrNull() }.toSet(),
        baseline = o.get("baseline")?.takeUnless { it.isJsonNull }?.let { decodeBaseline(it.asJsonObject) },
        templateId = o.get("templateId")?.takeUnless { it.isJsonNull }?.asString,
        parameters = o.getAsJsonObject("parameters")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty(),
        warnOnly = o.get("warnOnly")?.asBoolean ?: false,
        mechanical = o.get("mechanical")?.takeUnless { it.isJsonNull }?.let { MechanicalCheck.valueOf(it.asString) },
        desktopNote = o.get("desktopNote")?.takeUnless { it.isJsonNull }?.asString,
        threshold = o.get("threshold").asDouble,
    ).also { it.choice } // validates the id and options now, so a bad file fails on load, not mid-sweep

    private fun encodeShape(s: Shape): JsonObject = JsonObject().apply {
        when (s) {
            Shape.YesNo -> addProperty("type", "yesno")
            is Shape.Binary -> {
                addProperty("type", "binary")
                addProperty("positive", s.positive)
                addProperty("negative", s.negative)
            }
            is Shape.Pick -> {
                addProperty("type", "pick")
                add("options", strings(s.candidates))
                s.noOp?.let { addProperty("noOp", it) }
            }
            is Shape.Ordinal -> {
                addProperty("type", "ordinal")
                addProperty("from", s.range.first)
                addProperty("to", s.range.last)
                add("bands", strings(s.bands))
            }
        }
    }

    private fun decodeShape(o: JsonObject): Shape = when (o.get("type").asString) {
        "yesno" -> Shape.YesNo
        "binary" -> Shape.Binary(o.get("positive").asString, o.get("negative").asString)
        "pick" -> Shape.Pick(list(o.getAsJsonArray("options")), o.get("noOp")?.takeUnless { it.isJsonNull }?.asString)
        "ordinal" -> Shape.Ordinal(o.get("from").asInt..o.get("to").asInt, list(o.getAsJsonArray("bands")))
        else -> throw IllegalArgumentException("unknown shape ${o.get("type")}")
    }

    fun encodeBaseline(b: Baseline): JsonObject = JsonObject().apply {
        when (b) {
            is Baseline.Keyword -> {
                addProperty("type", "keyword"); add("keywords", strings(b.keywords))
                addProperty("whenFound", b.whenFound); addProperty("otherwise", b.otherwise)
            }
            is Baseline.KeywordMap -> {
                addProperty("type", "keywordMap"); addProperty("otherwise", b.otherwise)
                add("rules", JsonArray().apply {
                    b.rules.forEach { r -> add(JsonObject().apply { add("keywords", strings(r.keywords)); addProperty("label", r.label) }) }
                })
            }
            is Baseline.Pattern -> {
                addProperty("type", "pattern"); addProperty("pattern", b.pattern); addProperty("meaning", b.meaning)
                addProperty("whenFound", b.whenFound); addProperty("otherwise", b.otherwise)
            }
            is Baseline.DateBefore -> {
                addProperty("type", "dateBefore"); addProperty("before", b.before)
                addProperty("whenFound", b.whenFound); addProperty("otherwise", b.otherwise)
            }
            is Baseline.SenderIs -> {
                addProperty("type", "sender"); addProperty("sender", b.sender); addProperty("andAsksQuestion", b.andAsksQuestion)
                addProperty("whenFound", b.whenFound); addProperty("otherwise", b.otherwise)
            }
            is Baseline.Constant -> {
                addProperty("type", "constant"); addProperty("label", b.label)
            }
        }
    }

    fun decodeBaseline(o: JsonObject): Baseline {
        fun s(k: String) = o.get(k).asString
        return when (s("type")) {
            "keyword" -> Baseline.Keyword(list(o.getAsJsonArray("keywords")), s("whenFound"), s("otherwise"))
            "keywordMap" -> Baseline.KeywordMap(
                o.getAsJsonArray("rules").map { r ->
                    val ro = r.asJsonObject
                    Baseline.KeywordMap.Rule(list(ro.getAsJsonArray("keywords")), ro.get("label").asString)
                },
                s("otherwise"),
            )
            "pattern" -> Baseline.Pattern(s("pattern"), s("whenFound"), s("otherwise"), s("meaning"))
            "dateBefore" -> Baseline.DateBefore(s("before"), s("whenFound"), s("otherwise"))
            "sender" -> Baseline.SenderIs(s("sender"), s("whenFound"), s("otherwise"), o.get("andAsksQuestion")?.asBoolean ?: false)
            "constant" -> Baseline.Constant(s("label"))
            else -> throw IllegalArgumentException("unknown baseline ${s("type")}")
        }
    }

    private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }

    private fun list(array: JsonArray): List<String> = array.map(JsonElement::getAsString)
}
