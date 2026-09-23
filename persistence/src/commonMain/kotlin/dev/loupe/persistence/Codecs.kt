package dev.loupe.persistence

import dev.loupe.engine.Distribution
import dev.loupe.engine.Export
import dev.loupe.engine.Extent
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Probability
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.Truncation
import dev.loupe.templates.Baseline
import dev.loupe.templates.MechanicalCheck
import dev.loupe.templates.Shape
import dev.loupe.templates.SourceKind
import dev.loupe.templates.UserJudgment

/**
 * One ledger line (A5), in **exactly** the lossless format `Export.ledgerToJsonl` writes: the store
 * is its own export. Reading is lenient where history demands it — a line written before
 * `resolvedBy`, `itemId` or `truncation` existed takes their legacy defaults.
 */
object LedgerCodec {
    /** Rows as JSON Lines, no trailing newline (as [Export.ledgerToJsonl]). */
    fun encode(rows: List<LedgerRow>): String = Export.ledgerToJsonl(rows)

    fun parseRow(line: String): LedgerRow {
        val o = JsonValue.parse(line).asObj
        val masses = LinkedHashMap<String, Double>()
        for ((label, mass) in o.req("distribution").asObj.fields) masses[label] = mass.asDouble
        val failure = o.optStr("failure")
        return LedgerRow(
            judgmentId = o.str("judgmentId"),
            criteriaHash = o.str("criteriaHash"),
            distribution = Distribution.of(masses),
            action = o.str("action"),
            propensity = Probability.of(o.req("propensity").asDouble),
            correction = o.optStr("correction"),
            failure = failure,
            itemId = o.optStr("itemId"),
            // Lines written before the field existed default per ResolvedBy.legacy.
            resolvedBy = ResolvedBy.parse(o.optStr("resolvedBy"), failure),
            // Absent on lines written before the field existed: unknown, which reads as not cut.
            truncation = o["truncation"]?.takeUnless { it.isNull }?.asObj?.let { t ->
                Truncation(t.optExtent("textBudget"), t.optExtent("modelContext"), t.optExtent("optionCriteria"))
            },
        )
    }

    private fun JsonValue.Obj.optExtent(key: String): Extent? =
        this[key]?.takeUnless { it.isNull }?.asObj?.let { e ->
            Extent(e.req("kept").asInt, e.req("total").asInt, Extent.Measure.parse(e.str("unit")))
        }
}

/**
 * A user's verdict on one decision, as the corrections file stores it: under one judgment's exact
 * wording, the item is [label]. Appended, never edited; a null [label] retracts the one before.
 * [at] is the ISO-8601 instant exactly as written, so a line round-trips byte for byte.
 */
data class CorrectionRecord(
    val judgmentId: String,
    val criteriaHash: String,
    val itemId: String,
    val label: String?,
    val at: String,
    val confirmed: Boolean,
)

/** The key a correction applies to: a correction under old wording is not reused as truth. */
data class CorrectionKey(val judgmentId: String, val criteriaHash: String, val itemId: String)

object CorrectionCodec {
    /** One line, without its newline, as the desktop wrote it with Gson. */
    fun encode(c: CorrectionRecord): String = JsonText.compact(
        JsonValue.obj(
            "judgmentId" to JsonValue.Str(c.judgmentId),
            "criteriaHash" to JsonValue.Str(c.criteriaHash),
            "itemId" to JsonValue.Str(c.itemId),
            "label" to JsonValue.str(c.label),
            "at" to JsonValue.Str(c.at),
            "confirmed" to JsonValue.Bool(c.confirmed),
        ),
    )

    fun decode(line: String): CorrectionRecord {
        val o = JsonValue.parse(line).asObj
        return CorrectionRecord(
            judgmentId = o.str("judgmentId"),
            criteriaHash = o.str("criteriaHash"),
            itemId = o.str("itemId"),
            label = o.optStr("label"),
            at = o.str("at"),
            confirmed = o["confirmed"]?.asBoolean ?: false,
        )
    }

    /** The latest correction per key; a retraction removes the key. Order is the file's order. */
    fun index(corrections: List<CorrectionRecord>): Map<CorrectionKey, String> {
        val index = LinkedHashMap<CorrectionKey, String>()
        for (c in corrections) {
            val key = CorrectionKey(c.judgmentId, c.criteriaHash, c.itemId)
            if (c.label == null) index.remove(key) else index[key] = c.label
        }
        return index
    }
}

/** A [UserJudgment] to and from JSON, every field explicit so the file reads on its own. */
object JudgmentCodec {
    fun encode(j: UserJudgment): JsonValue.Obj {
        val f = LinkedHashMap<String, JsonValue>()
        f["id"] = JsonValue.Str(j.id)
        f["title"] = JsonValue.Str(j.title)
        f["question"] = JsonValue.Str(j.question)
        f["shape"] = encodeShape(j.shape)
        f["invariant"] = JsonValue.Str(j.invariant)
        f["breaks"] = JsonValue.Str(j.breaks)
        f["lookalikes"] = JsonValue.Str(j.lookalikes)
        f["onFailure"] = JsonValue.Str(j.onFailure.name)
        f["sources"] = JsonValue.strings(j.sources.map { it.name })
        j.baseline?.let { f["baseline"] = encodeBaseline(it) }
        j.templateId?.let { f["templateId"] = JsonValue.Str(it) }
        f["parameters"] = JsonValue.Obj(LinkedHashMap(j.parameters.mapValues { JsonValue.Str(it.value) }))
        f["warnOnly"] = JsonValue.Bool(j.warnOnly)
        j.mechanical?.let { f["mechanical"] = JsonValue.Str(it.name) }
        j.desktopNote?.let { f["desktopNote"] = JsonValue.Str(it) }
        f["threshold"] = JsonValue.num(j.threshold)
        // Written only when on, so a file from before the option existed reads the same.
        if (j.criteriaInPrompt) f["criteriaInPrompt"] = JsonValue.Bool(true)
        if (j.useBaseline) f["useBaseline"] = JsonValue.Bool(true)
        // Written for a reader of the file; recomputed from the wording on load, never trusted.
        f["criteriaHash"] = JsonValue.Str(j.criteriaHash)
        return JsonValue.Obj(f)
    }

    fun decode(o: JsonValue.Obj): UserJudgment = UserJudgment(
        id = o.req("id").asString,
        title = o.req("title").asString,
        question = o.req("question").asString,
        shape = decodeShape(o.req("shape").asObj),
        invariant = o.req("invariant").asString,
        breaks = o.req("breaks").asString,
        lookalikes = o.req("lookalikes").asString,
        onFailure = FailurePosture.valueOf(o.req("onFailure").asString),
        sources = o.req("sources").asArr.items
            .mapNotNull { runCatching { SourceKind.valueOf(it.asString) }.getOrNull() }.toSet(),
        baseline = o["baseline"]?.takeUnless { it.isNull }?.let { decodeBaseline(it.asObj) },
        templateId = o.optStr("templateId"),
        parameters = o["parameters"]?.takeUnless { it.isNull }?.asObj?.fields?.mapValues { it.value.asString }.orEmpty(),
        warnOnly = o["warnOnly"]?.asBoolean ?: false,
        mechanical = o["mechanical"]?.takeUnless { it.isNull }?.let { MechanicalCheck.valueOf(it.asString) },
        desktopNote = o.optStr("desktopNote"),
        threshold = o.req("threshold").asDouble,
        criteriaInPrompt = o["criteriaInPrompt"]?.takeUnless { it.isNull }?.asBoolean ?: false,
        useBaseline = o["useBaseline"]?.takeUnless { it.isNull }?.asBoolean ?: false,
    ).also { it.choice } // validates the id and options now, so a bad file fails on load, not mid-sweep

    /** The whole `judgments.json` file (pretty, as the desktop wrote it). */
    fun encodeFile(judgments: List<UserJudgment>): String = JsonText.pretty(JsonValue.Arr(judgments.map(::encode)))

    /** Every judgment in the file that decodes; one that does not is skipped, as on desktop. */
    fun decodeFile(text: String): List<UserJudgment> =
        JsonValue.parse(text).asArr.items.mapNotNull { runCatching { decode(it.asObj) }.getOrNull() }

    private fun encodeShape(s: Shape): JsonValue.Obj = when (s) {
        Shape.YesNo -> JsonValue.obj("type" to JsonValue.Str("yesno"))
        is Shape.Binary -> JsonValue.obj(
            "type" to JsonValue.Str("binary"),
            "positive" to JsonValue.Str(s.positive),
            "negative" to JsonValue.Str(s.negative),
        )
        is Shape.Pick -> JsonValue.Obj(
            linkedMapOf<String, JsonValue>("type" to JsonValue.Str("pick"), "options" to JsonValue.strings(s.candidates))
                .also { m -> s.noOp?.let { m["noOp"] = JsonValue.Str(it) } },
        )
        is Shape.Ordinal -> JsonValue.obj(
            "type" to JsonValue.Str("ordinal"),
            "from" to JsonValue.num(s.range.first),
            "to" to JsonValue.num(s.range.last),
            "bands" to JsonValue.strings(s.bands),
        )
    }

    private fun decodeShape(o: JsonValue.Obj): Shape = when (o.req("type").asString) {
        "yesno" -> Shape.YesNo
        "binary" -> Shape.Binary(o.req("positive").asString, o.req("negative").asString)
        "pick" -> Shape.Pick(list(o.req("options")), o.optStr("noOp"))
        "ordinal" -> Shape.Ordinal(o.req("from").asInt..o.req("to").asInt, list(o.req("bands")))
        else -> throw IllegalArgumentException("unknown shape ${o["type"]}")
    }

    fun encodeBaseline(b: Baseline): JsonValue.Obj {
        fun s(v: String) = JsonValue.Str(v)
        return when (b) {
            is Baseline.Keyword -> JsonValue.obj(
                "type" to s("keyword"), "keywords" to JsonValue.strings(b.keywords),
                "whenFound" to s(b.whenFound), "otherwise" to s(b.otherwise),
            )
            is Baseline.KeywordMap -> JsonValue.obj(
                "type" to s("keywordMap"), "otherwise" to s(b.otherwise),
                "rules" to JsonValue.Arr(
                    b.rules.map { r -> JsonValue.obj("keywords" to JsonValue.strings(r.keywords), "label" to s(r.label)) },
                ),
            )
            is Baseline.Pattern -> JsonValue.obj(
                "type" to s("pattern"), "pattern" to s(b.pattern), "meaning" to s(b.meaning),
                "whenFound" to s(b.whenFound), "otherwise" to s(b.otherwise),
            )
            is Baseline.DateBefore -> JsonValue.obj(
                "type" to s("dateBefore"), "before" to s(b.before),
                "whenFound" to s(b.whenFound), "otherwise" to s(b.otherwise),
            )
            is Baseline.SenderIs -> JsonValue.obj(
                "type" to s("sender"), "sender" to s(b.sender), "andAsksQuestion" to JsonValue.Bool(b.andAsksQuestion),
                "whenFound" to s(b.whenFound), "otherwise" to s(b.otherwise),
            )
            is Baseline.Constant -> JsonValue.obj("type" to s("constant"), "label" to s(b.label))
        }
    }

    fun decodeBaseline(o: JsonValue.Obj): Baseline {
        fun s(k: String) = o.req(k).asString
        return when (s("type")) {
            "keyword" -> Baseline.Keyword(list(o.req("keywords")), s("whenFound"), s("otherwise"))
            "keywordMap" -> Baseline.KeywordMap(
                o.req("rules").asArr.items.map { r ->
                    val ro = r.asObj
                    Baseline.KeywordMap.Rule(list(ro.req("keywords")), ro.req("label").asString)
                },
                s("otherwise"),
            )
            "pattern" -> Baseline.Pattern(s("pattern"), s("whenFound"), s("otherwise"), s("meaning"))
            "dateBefore" -> Baseline.DateBefore(s("before"), s("whenFound"), s("otherwise"))
            "sender" -> Baseline.SenderIs(s("sender"), s("whenFound"), s("otherwise"), o["andAsksQuestion"]?.asBoolean ?: false)
            "constant" -> Baseline.Constant(s("label"))
            else -> throw IllegalArgumentException("unknown baseline ${s("type")}")
        }
    }

    private fun list(v: JsonValue): List<String> = v.asArr.items.map { it.asString }
}

internal fun JsonValue.Obj.req(key: String): JsonValue = this[key] ?: throw IllegalArgumentException("missing '$key'")

internal fun JsonValue.Obj.str(key: String): String =
    this[key]?.takeUnless { it.isNull }?.asString ?: throw IllegalArgumentException("missing '$key'")

internal fun JsonValue.Obj.optStr(key: String): String? = this[key]?.takeUnless { it.isNull }?.asString
