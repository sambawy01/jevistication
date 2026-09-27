package dev.loupe.kit.measure

import dev.loupe.kit.settings.CalibrationFile
import dev.loupe.kit.settings.GatesFile
import dev.loupe.kit.settings.LangGroup
import dev.loupe.kit.settings.ModelPrior
import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

/**
 * Fits the phone's own `model_calibration.json` and `model_gates.json` from labelled raw answers.
 *
 * PROVENANCE: the method is Loupe Station's `tools/lab/fit_calibration.py` and the gate sweep its
 * `laya_studio/measure/gates.py` (commit 0ff886d; the sweep itself a port of jeval, Apache-2.0,
 * github.com/rlaope/jeval commit 61bdcccf9038 `jeval/costs.py`): temperatures on **raw** logits,
 * 1-D Newton on 1/T (Platt for yes/no with a ridge on b), 5-fold cross-validation grouped by input
 * text, the simplest of identity < temperature < Platt that wins out of sample by [MIN_GAIN], a
 * language cell only with [MIN_N_CELL] labels and a [MIN_GAIN] out-of-sample gain over the type's,
 * a bias only with [MIN_N_BIAS]; gate lines by a cost sweep over 0.00..1.00 on the out-of-sample
 * calibrated answers, no line below 30 records, a language's own line only where jeval's split rule
 * says it pays (>= 60 records). Two differences, both because the phone runs one checkpoint for
 * every language: every group is fitted into the multilingual type cells (Station fits multilingual
 * on the groups auto-routing sends it), and there is no second checkpoint to borrow a pooled line
 * from. The folds are shuffled with Kotlin's seeded `Random(11)`, not Python's, so fold membership
 * differs from Station's; the method does not.
 */
object CalibrationFit {
    const val FOLDS: Int = 5
    const val SEED: Int = 11
    const val MIN_N_CELL: Int = 60
    const val MIN_N_BIAS: Int = 100
    const val MIN_GAIN: Double = 0.01
    const val RIDGE_B: Double = 0.5
    const val MIN_GOLD_RECORDS: Int = 30
    const val MIN_SEGMENT_RECORDS: Int = 60
    const val SPLIT_COST_TOLERANCE: Double = 0.02
    private const val STEPS: Int = 101
    val LANGS: List<String> = listOf(LangGroup.EN, LangGroup.AR, LangGroup.FRANCO, LangGroup.ES_FR)

    /**
     * One labelled answer. [raw] are the raw logits in Station's order — a yes/no as `[z_no, z_yes]`
     * (so class 1 is yes) — [y] the index of the right answer, [textId] what the folds group by.
     */
    data class Row(val type: String, val lang: String, val textId: String, val raw: List<Double>, val y: Int, val phishing: Boolean = false)

    /** The costs of a line: a wrong automatic answer, a case sent to a person, a right answer sent anyway. */
    data class Costs(val falseAccept: Double, val escalate: Double, val falseReject: Double = 0.0)

    /** Station's placeholder costs (tools/lab/gate_costs.json at 0ff886d): relative, not a recommendation. */
    val PLACEHOLDER_COSTS: Map<String, Costs?> = mapOf(
        "act" to Costs(10.0, 1.0), "confirm" to Costs(2.0, 1.0), "phishing_act" to null, "phishing_confirm" to Costs(3.0, 1.0),
    )

    // ------------------------------------------------------------------ maths

    fun sigmoid(x: Double): Double = if (x >= 0) 1.0 / (1.0 + exp(-x)) else exp(x).let { it / (1.0 + it) }

    fun softmax(z: List<Double>): List<Double> {
        val m = z.max()
        val e = z.map { exp(it - m) }
        val s = e.sum()
        return e.map { it / s }
    }

    /** The file's formula on raw logits (a yes/no's `[z_no, z_yes]`). */
    fun apply(raw: List<Double>, type: String, t: Double, b: Double): List<Double> {
        if (type == ModelPrior.NOUL) {
            val p = sigmoid((raw[1] - raw[0]) / t + b)
            return listOf(1.0 - p, p)
        }
        return softmax(raw.map { it / t })
    }

    fun nll(rows: List<Row>, type: String, t: Double, b: Double): Double =
        if (rows.isEmpty()) 0.0 else rows.sumOf { -ln(max(1e-12, apply(it.raw, type, t, b)[it.y])) } / rows.size

    /** 1-D Newton on a = 1/T (convex); T in [0.05, 50]. */
    fun fitTemperature(rows: List<Row>, type: String): Double {
        var a = 1.0
        repeat(60) {
            var g = 0.0
            var h = 0.0
            for (r in rows) {
                val z = r.raw
                if (type == ModelPrior.NOUL) {
                    val d = z[1] - z[0]
                    val p = sigmoid(a * d)
                    val yv = if (r.y == 1) 1.0 else 0.0
                    g += (p - yv) * d
                    h += p * (1 - p) * d * d
                } else {
                    val p = softmax(z.map { a * it })
                    val ez = p.indices.sumOf { p[it] * z[it] }
                    g += ez - z[r.y]
                    h += p.indices.sumOf { p[it] * z[it] * z[it] } - ez * ez
                }
            }
            if (h <= 1e-12) return min(50.0, max(0.05, 1.0 / a))
            val next = min(20.0, max(0.02, a - g / h))
            if (abs(next - a) < 1e-9) return min(50.0, max(0.05, 1.0 / next))
            a = next
        }
        return min(50.0, max(0.05, 1.0 / a))
    }

    /** Logistic regression p = sigmoid(a d + b) on d = z_yes - z_no, Newton with a ridge on b. Returns (T, b). */
    fun fitPlatt(rows: List<Row>): Pair<Double, Double> {
        var a = 1.0 / fitTemperature(rows, ModelPrior.NOUL)
        var b = 0.0
        for (iter in 0 until 100) {
            var ga = 0.0; var gb = 0.0; var haa = 0.0; var hab = 0.0; var hbb = 0.0
            for (r in rows) {
                val d = r.raw[1] - r.raw[0]
                val p = sigmoid(a * d + b)
                val yv = if (r.y == 1) 1.0 else 0.0
                val w = p * (1 - p)
                ga += (p - yv) * d; gb += p - yv
                haa += w * d * d; hab += w * d; hbb += w
            }
            gb += RIDGE_B * b
            hbb += RIDGE_B
            val det = haa * hbb - hab * hab
            if (det <= 1e-12) break
            val da = (hbb * ga - hab * gb) / det
            val db = (haa * gb - hab * ga) / det
            val a2 = min(20.0, max(0.02, a - da))
            val b2 = min(20.0, max(-20.0, b - db))
            val done = abs(a2 - a) < 1e-10 && abs(b2 - b) < 1e-10
            a = a2; b = b2
            if (done) break
        }
        return min(50.0, max(0.05, 1.0 / a)) to b
    }

    fun fit(rows: List<Row>, type: String, method: String): Pair<Double, Double> = when (method) {
        "identity" -> 1.0 to 0.0
        "platt" -> fitPlatt(rows)
        else -> fitTemperature(rows, type) to 0.0
    }

    /** A fold per row, grouped by text (the same text never sits in two folds), seeded. */
    fun foldsOf(rows: List<Row>, k: Int = FOLDS, seed: Int = SEED): List<Int> {
        val texts = rows.map { it.textId }.distinct().sorted().shuffled(Random(seed))
        val fold = texts.withIndex().associate { (i, t) -> t to i % k }
        return rows.map { fold.getValue(it.textId) }
    }

    /** Mean out-of-sample NLL; with [fallback] (pool, method) the pool's training part is fitted instead. */
    fun cvNll(rows: List<Row>, type: String, method: String, fold: List<Int>, fallback: Pair<List<Row>, String>? = null): Double {
        var total = 0.0
        var n = 0
        for (f in 0 until FOLDS) {
            val test = rows.filterIndexed { i, _ -> fold[i] == f }
            if (test.isEmpty()) continue
            val (t, b) = if (fallback == null) {
                fit(rows.filterIndexed { i, _ -> fold[i] != f }, type, method)
            } else {
                val ids = test.map { it.textId }.toSet()
                fit(fallback.first.filter { it.textId !in ids }, type, fallback.second)
            }
            total += nll(test, type, t, b) * test.size
            n += test.size
        }
        return total / max(1, n)
    }

    // ------------------------------------------------------------------ the fit

    /** A fitted cell: parameters, method and what the choice rested on. */
    data class Cell(val t: Double, val b: Double, val n: Int, val method: String, val cvNll: Map<String, Double>)

    /** The fitted structure: the type cells and the language cells that earned their place, and why each was chosen. */
    data class Fit(val types: Map<String, Cell>, val languages: Map<String, Map<String, Cell>>, val selection: List<String>)

    /**
     * [allowBias] false keeps every cell to identity or temperature, so calibration can never change
     * which answer wins (the phone's choice: see ModelPriorFitTest for why).
     */
    fun fitAll(rows: List<Row>, allowBias: Boolean = true): Fit {
        val types = LinkedHashMap<String, Cell>()
        val languages = LinkedHashMap<String, MutableMap<String, Cell>>()
        val selection = mutableListOf<String>()
        for (type in CalibrationFile.TYPES) {
            val base = rows.filter { it.type == type }
            if (base.isEmpty()) continue
            val fold = foldsOf(base)
            val candidates = listOf("identity", "temperature") + if (allowBias && type == ModelPrior.NOUL && base.size >= MIN_N_BIAS) listOf("platt") else emptyList()
            val scores = candidates.associateWith { cvNll(base, type, it, fold) }
            var method = "identity"
            if (scores.getValue("temperature") < scores.getValue("identity") * (1 - MIN_GAIN)) method = "temperature"
            if ("platt" in scores && scores.getValue("platt") < scores.getValue(method) * (1 - MIN_GAIN)) method = "platt"
            val (t, b) = fit(base, type, method)
            types[type] = Cell(t, b, base.size, method, scores)
            selection += "$type: n=${base.size}, cv NLL ${scores.fmt()} -> $method"
            for (lg in LANGS) {
                val cell = rows.filter { it.type == type && it.lang == lg }
                if (cell.size < MIN_N_CELL) {
                    selection += "$type/$lg: n=${cell.size} -> type (fewer than $MIN_N_CELL labels)"
                    continue
                }
                val cfold = foldsOf(cell)
                val pool = cvNll(cell, type, method, cfold, fallback = base to method)
                val ccand = listOf("temperature") + if (allowBias && type == ModelPrior.NOUL && cell.size >= MIN_N_BIAS) listOf("platt") else emptyList()
                val cs = ccand.associateWith { cvNll(cell, type, it, cfold) }
                var cm = cs.minBy { it.value }.key
                if (cm == "platt" && cs.getValue("platt") >= cs.getValue("temperature") * (1 - MIN_GAIN)) cm = "temperature"
                if (cs.getValue(cm) < pool * (1 - MIN_GAIN)) {
                    val (ct, cb) = fit(cell, type, cm)
                    languages.getOrPut(lg) { LinkedHashMap() }[type] = Cell(ct, cb, cell.size, cm, cs + ("type_params" to pool))
                    selection += "$type/$lg: n=${cell.size}, cv NLL type ${pool.r4()} vs own ${cs.fmt()} -> $cm"
                } else {
                    selection += "$type/$lg: n=${cell.size}, cv NLL type ${pool.r4()} vs own ${cs.fmt()} -> type (own cell not better out of sample)"
                }
            }
        }
        return Fit(types, languages, selection)
    }

    /** The cell's parameters for a row: its language's own cell, else its type's. */
    fun paramsFor(fit: Fit, type: String, lang: String): Pair<Double, Double> =
        (fit.languages[lang]?.get(type) ?: fit.types.getValue(type)).let { it.t to it.b }

    /** Out-of-sample calibrated distribution per row (structure from [fit], parameters refitted per fold). */
    fun crossValidated(rows: List<Row>, fit: Fit): Map<Int, List<Double>> {
        val out = HashMap<Int, List<Double>>()
        for (type in CalibrationFile.TYPES) {
            val idx = rows.indices.filter { rows[it].type == type }
            val cellRows = idx.map { rows[it] }
            if (cellRows.isEmpty() || type !in fit.types) continue
            val textFold = cellRows.zip(foldsOf(cellRows)).associate { (r, f) -> r.textId to f }
            val tMethod = fit.types.getValue(type).method
            for (f in 0 until FOLDS) {
                val tb = fit(cellRows.filter { textFold.getValue(it.textId) != f }, type, tMethod)
                val own = fit.languages.filterValues { type in it }.mapValues { (lg, cells) ->
                    fit(cellRows.filter { it.lang == lg && textFold.getValue(it.textId) != f }, type, cells.getValue(type).method)
                }
                for ((i, r) in idx.zip(cellRows)) {
                    if (textFold.getValue(r.textId) != f) continue
                    val (t, b) = own[r.lang] ?: tb
                    out[i] = apply(r.raw, type, t, b)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ metrics

    data class Metrics(val n: Int, val acc: Double, val ece: Double, val wrong: Int, val wrong90: Double, val wrong99: Double, val nll: Double)

    fun metrics(pairs: List<Pair<List<Double>, Int>>): Metrics {
        if (pairs.isEmpty()) return Metrics(0, 0.0, 0.0, 0, 0.0, 0.0, 0.0)
        val n = pairs.size
        fun top(d: List<Double>) = d.indices.maxBy { d[it] }
        val ok = pairs.count { (d, y) -> top(d) == y }
        val wrong = pairs.filter { (d, y) -> top(d) != y }.map { it.first.max() }
        val bins = Array(10) { DoubleArray(3) }
        for ((d, y) in pairs) {
            val c = d.max()
            val b = min(9, (c * 10).toInt())
            bins[b][0] += 1.0; bins[b][1] += c; bins[b][2] += if (top(d) == y) 1.0 else 0.0
        }
        val ece = bins.sumOf { if (it[0] == 0.0) 0.0 else abs(it[1] / it[0] - it[2] / it[0]) * it[0] / n }
        return Metrics(
            n, ok.toDouble() / n, ece, wrong.size,
            if (wrong.isEmpty()) 0.0 else wrong.count { it >= 0.9 }.toDouble() / wrong.size,
            if (wrong.isEmpty()) 0.0 else wrong.count { it >= 0.99 }.toDouble() / wrong.size,
            pairs.sumOf { (d, y) -> -ln(max(1e-12, d[y])) } / n,
        )
    }

    // ------------------------------------------------------------------ gates (the jeval sweep)

    /** One labelled calibrated answer for the sweep. */
    data class Record(val confidence: Double, val correct: Boolean, val prediction: String, val label: String, val segment: String = "")

    data class Line(val threshold: Double?, val costPerCase: Double = 0.0, val n: Int, val reason: String = "")

    private fun fires(when_: String, r: Record) = when_ == "*" || r.prediction == when_
    private fun labelIsWhen(when_: String, r: Record) = if (when_ == "*") r.correct else r.label == when_

    /** Mean cost per case at threshold [t]. */
    fun cost(when_: String, c: Costs, records: List<Record>, t: Double): Double {
        var auto = 0
        var wrongAuto = 0
        var rejected = 0
        for (r in records) {
            if (fires(when_, r) && r.confidence >= t) {
                auto++
                if (!r.correct) wrongAuto++
            } else if (labelIsWhen(when_, r)) {
                rejected++
            }
        }
        val escalated = records.size - auto
        return (wrongAuto * c.falseAccept + escalated * c.escalate + rejected * c.falseReject) / records.size
    }

    /** The cost-minimising line over 0.00..1.00 (ties to the higher line); none below [MIN_GOLD_RECORDS]. */
    fun sweep(when_: String, c: Costs, records: List<Record>): Line {
        if (records.size < MIN_GOLD_RECORDS) {
            return Line(null, n = records.size, reason = "only ${records.size} labelled records, below the $MIN_GOLD_RECORDS a line needs")
        }
        var best = 0
        val costs = (0 until STEPS).map { cost(when_, c, records, it / (STEPS - 1.0)) }
        for (i in 1 until costs.size) if (costs[i] <= costs[best]) best = i
        return Line(round(best.toDouble()) / (STEPS - 1), costs[best], records.size)
    }

    /** jeval's split rule: a segment's own line only when it moves more than one step and changes cost by > 2%. */
    fun segmentSplit(when_: String, c: Costs, records: List<Record>, global: Line): Map<String, Double> {
        val gt = global.threshold ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for ((seg, rs) in records.groupBy { it.segment.ifEmpty { "unknown" } }.entries.sortedBy { it.key }.map { it.key to it.value }) {
            if (rs.size < max(MIN_SEGMENT_RECORDS, MIN_GOLD_RECORDS)) continue
            val own = sweep(when_, c, rs)
            val atGlobal = cost(when_, c, rs, gt)
            val moved = abs(own.threshold!! - gt) > 1.0 / (STEPS - 1) + 1e-12
            val material = abs(own.costPerCase - atGlobal) > SPLIT_COST_TOLERANCE * global.costPerCase
            if (moved && material && own.threshold < 1.0) out[seg] = own.threshold
        }
        return out
    }

    /** The gate lines per key (noul, choice, score, phishing) from the out-of-sample calibrated rows. */
    fun fitGates(rows: List<Row>, cv: Map<Int, List<Double>>, costs: Map<String, Costs?> = PLACEHOLDER_COSTS): Map<String, GateCell> {
        fun records(idx: List<Int>): List<Record> = idx.map { i ->
            val d = cv.getValue(i)
            val pred = d.indices.maxBy { d[it] }
            Record(d.max(), pred == rows[i].y, pred.toString(), rows[i].y.toString(), rows[i].lang)
        }
        val keys = listOf(
            Triple(ModelPrior.NOUL, { r: Row -> r.type == ModelPrior.NOUL && !r.phishing }, "1"),
            Triple(ModelPrior.CHOICE, { r: Row -> r.type == ModelPrior.CHOICE }, "*"),
            Triple(ModelPrior.SCORE, { r: Row -> r.type == ModelPrior.SCORE }, "*"),
            Triple(ModelPrior.PHISHING, { r: Row -> r.type == ModelPrior.NOUL && r.phishing }, "1"),
        )
        val out = LinkedHashMap<String, GateCell>()
        for ((key, select, when_) in keys) {
            val idx = cv.keys.filter { select(rows[it]) }.sorted()
            val recs = records(idx)
            val lines = LinkedHashMap<String, Double?>()
            val langs = LinkedHashMap<String, MutableMap<String, Double>>()
            val notes = mutableListOf<String>()
            for (line in listOf("act", "confirm")) {
                val c = costs[if (key == ModelPrior.PHISHING) "phishing_$line" else line]
                if (c == null) {
                    lines[line] = null
                    notes += "$line: never (fixed rule)"
                    continue
                }
                val res = sweep(when_, c, recs)
                val t = res.threshold
                lines[line] = if (t != null && t >= 1.0) null else t
                notes += "$line: " + when {
                    t == null -> res.reason
                    t >= 1.0 -> "1.00 is optimal at these costs (null = never)"
                    else -> "t=$t, cost/case ${res.costPerCase.r4()}"
                }
                if (t != null && t < 1.0) for ((seg, st) in segmentSplit(when_, c, recs, res)) langs.getOrPut(seg) { LinkedHashMap() }[line] = st
            }
            val act = lines["act"]
            val confirm = lines["confirm"]
            val fixedAct = if (act != null && confirm != null && act < confirm) confirm else act
            for ((_, lv) in langs) {
                val a = lv["act"] ?: fixedAct
                val c = lv["confirm"] ?: confirm
                if (a != null && c != null && a < c) lv["act"] = c
            }
            out[key] = GateCell(fixedAct, confirm, recs.size, langs, notes)
        }
        return out
    }

    data class GateCell(val act: Double?, val confirm: Double?, val n: Int, val languages: Map<String, Map<String, Double>>, val notes: List<String>)

    // ------------------------------------------------------------------ the two files

    fun calibrationJson(fit: Fit, created: String, version: String, labels: String): String {
        fun cell(c: Cell) = JsonValue.obj("T" to JsonValue.Num(c.t.r4().toString()), "b" to JsonValue.Num(c.b.r4().toString()), "n" to JsonValue.num(c.n), "method" to JsonValue.Str(c.method))
        val o = JsonValue.obj(
            "format" to JsonValue.Str(CalibrationFile.FORMAT), "format_version" to JsonValue.num(CalibrationFile.FORMAT_VERSION),
            "version" to JsonValue.Str(version), "created" to JsonValue.Str(created),
            "model" to JsonValue.obj(
                "repo" to JsonValue.Str("convaiinnovations/laya-multilingual"),
                "graph" to JsonValue.Str("laya-multilingual-choice.int8.onnx (the phone's INT8 fixed-choice export)"),
            ),
            "applies_to" to JsonValue.Str("raw logits (the checkpoint's own temperatures are 1.0): this file is applied to the graph's logits at T = 1"),
            "method" to JsonValue.obj(
                "noul" to JsonValue.Str("p_yes = sigmoid((z_yes - z_no) / T + b)"),
                "choice" to JsonValue.Str("p = softmax(z / T)"),
                "score" to JsonValue.Str("p = softmax(z / T), levels in the order the model read them"),
            ),
            "lookup" to JsonValue.Str("languages[group][type] when present, else types[type]; groups: en, ar, franco, es-fr; any other language uses types[type]"),
            "min_n" to JsonValue.obj("language_cell" to JsonValue.num(MIN_N_CELL), "bias" to JsonValue.num(MIN_N_BIAS), "gain" to JsonValue.Num(MIN_GAIN.toString())),
            "fit" to JsonValue.obj(
                "labels" to JsonValue.Str(labels),
                "cv" to JsonValue.Str("$FOLDS folds grouped by input text, seed $SEED"),
                "tool" to JsonValue.Str("loupe-kit/src/jvmTest/kotlin/dev/loupe/kit/measure/ModelPriorFitTest.kt (CalibrationFit)"),
            ),
            "checkpoints" to JsonValue.obj(
                ModelPrior.CHECKPOINT to JsonValue.obj(
                    "types" to JsonValue.Obj(LinkedHashMap(fit.types.mapValues { cell(it.value) as JsonValue })),
                    "languages" to JsonValue.Obj(LinkedHashMap(fit.languages.mapValues { (_, cs) -> JsonValue.Obj(LinkedHashMap(cs.mapValues { cell(it.value) as JsonValue })) as JsonValue })),
                    "low_trust_languages" to JsonValue.strings(listOf(LangGroup.FRANCO)),
                ),
            ),
        )
        return JsonText.pretty(o) + "\n"
    }

    fun gatesJson(cells: Map<String, GateCell>, created: String, version: String, calibrationVersion: String, costs: Map<String, Costs?> = PLACEHOLDER_COSTS): String {
        fun line(v: Double?): JsonValue = if (v == null) JsonValue.Null else JsonValue.Num(((round(v * 100)) / 100).toString())
        fun c(v: Costs?): JsonValue = if (v == null) JsonValue.Null else JsonValue.obj(
            "false_accept" to JsonValue.Num(v.falseAccept.toString()), "escalate" to JsonValue.Num(v.escalate.toString()), "false_reject" to JsonValue.Num(v.falseReject.toString()),
        )
        val cps = LinkedHashMap<String, JsonValue>()
        for ((key, g) in cells) {
            val f = linkedMapOf("act" to line(g.act), "confirm" to line(g.confirm), "n" to JsonValue.num(g.n))
            if (g.languages.isNotEmpty()) f["languages"] = JsonValue.Obj(LinkedHashMap(g.languages.mapValues { (_, lv) -> JsonValue.Obj(LinkedHashMap(lv.mapValues { line(it.value) })) as JsonValue }))
            cps[key] = JsonValue.Obj(LinkedHashMap(f))
        }
        val o = JsonValue.obj(
            "format" to JsonValue.Str(GatesFile.FORMAT), "format_version" to JsonValue.num(GatesFile.FORMAT_VERSION),
            "version" to JsonValue.Str(version), "created" to JsonValue.Str(created), "calibration_version" to JsonValue.Str(calibrationVersion),
            "bands" to JsonValue.obj(
                "act" to JsonValue.Str("confidence >= act: used as it is"),
                "confirm" to JsonValue.Str("confidence >= confirm: a person confirms"),
                "human" to JsonValue.Str("below confirm: a person answers"),
            ),
            "rules" to JsonValue.strings(listOf("phishing questions never act (act is null)", "a low-trust language of the calibration file (franco) is always human")),
            "costs" to JsonValue.obj("act" to c(costs["act"]), "confirm" to c(costs["confirm"]), "phishing_act" to c(costs["phishing_act"]), "phishing_confirm" to c(costs["phishing_confirm"])),
            "costs_note" to JsonValue.Str("PLACEHOLDERS, Loupe Station's (tools/lab/gate_costs.json at 0ff886d), not a recommendation"),
            "checkpoints" to JsonValue.obj(ModelPrior.CHECKPOINT to JsonValue.Obj(cps)),
        )
        return JsonText.pretty(o) + "\n"
    }

    private fun Double.r4(): Double = round(this * 10_000) / 10_000
    private fun Map<String, Double>.fmt(): String = entries.joinToString(", ") { "${it.key} ${it.value.r4()}" }
}
