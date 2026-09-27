package dev.loupe.kit.settings

import dev.loupe.engine.CalibratedDistribution
import dev.loupe.engine.Distribution
import dev.loupe.engine.Judgment
import dev.loupe.engine.LogitScaling
import dev.loupe.engine.Recalibrator
import dev.loupe.engine.TextState
import dev.loupe.persistence.JsonValue
import kotlin.math.exp
import kotlin.math.ln

/*
 * The calibration prior and the confidence gates: Loupe Station's two versioned files, read and
 * applied the same way on the iPhone and the desktop.
 *
 * PROVENANCE: the formats, the lookup order, the minimum-n rules, the low-trust languages and the
 * band rules are Loupe Station's (`~/laya-studio`, commit 0ff886d: docs/model-calibration.md,
 * laya_studio/calibration_prior.py, laya_studio/measure/gates.py). The **numbers** are not
 * Station's: logits differ by export and quantisation, so the phone ships files fitted on its own
 * graph (INT8, the fixed-choice export) by ModelPriorFitTest from our labelled sets — see
 * docs/BUILD.md, 2026-09-27.
 *
 * On the phone every input runs on the multilingual checkpoint, so [ModelPrior.CHECKPOINT] is the
 * only cell read. The backend returns masses, not logits; with the checkpoint's own temperatures at
 * 1 (the multilingual checkpoint ships 1.0 for every type) `ln p` is the raw logit up to a constant,
 * which softmax ignores, so the file's formula applies to them exactly.
 */

/** A calibration or gates file that is not format_version 1 of its format, or whose values are out of range. */
class ModelPriorError(message: String) : IllegalArgumentException(message)

/** One cell of the calibration file: `p = softmax(z / T)`, plus `b` on the yes logit for a yes/no question. */
data class CalibrationCell(val temperature: Double, val bias: Double, val name: String)

/** `model_calibration.json` (format `loupe-calibration`, format_version 1). */
class CalibrationFile private constructor(val version: String, private val checkpoints: Map<String, Checkpoint>) {

    private class Checkpoint(
        val types: Map<String, Pair<Double, Double>>,
        val languages: Map<String, Map<String, Pair<Double, Double>>>,
        val lowTrust: Set<String>,
    )

    fun has(checkpoint: String): Boolean = checkpoint in checkpoints

    /** `languages[group][type]` when present, else `types[type]`; the identity where neither is. */
    fun cell(checkpoint: String, type: String, language: String?): CalibrationCell {
        val cp = checkpoints[checkpoint] ?: return CalibrationCell(1.0, 0.0, "identity")
        if (language != null) {
            cp.languages[language]?.get(type)?.let { (t, b) -> return CalibrationCell(t, b, "$checkpoint/$type/$language") }
        }
        val p = cp.types[type] ?: return CalibrationCell(1.0, 0.0, "$checkpoint/$type:identity")
        return CalibrationCell(p.first, p.second, "$checkpoint/$type")
    }

    /** A group whose confidence must not drive an automatic action (Franco-Arabic). */
    fun lowTrust(checkpoint: String, language: String?): Boolean =
        language != null && language in (checkpoints[checkpoint]?.lowTrust ?: emptySet())

    companion object {
        const val FORMAT: String = "loupe-calibration"
        const val FORMAT_VERSION: Int = 1
        val TYPES: List<String> = listOf(ModelPrior.NOUL, ModelPrior.CHOICE, ModelPrior.SCORE)

        /** Every checkpoint at T = 1, b = 0: what `use_calibration = false` means. */
        val IDENTITY: CalibrationFile = CalibrationFile(
            "identity",
            mapOf(ModelPrior.CHECKPOINT to Checkpoint(TYPES.associateWith { 1.0 to 0.0 }, emptyMap(), emptySet())),
        )

        fun parse(text: String): CalibrationFile = from(
            try {
                JsonValue.parse(text)
            } catch (e: IllegalArgumentException) {
                throw ModelPriorError("not JSON: ${e.message}")
            },
        )

        /** Station's `validate`: T in [0.05, 50], |b| <= 20, a bias only on noul. */
        fun from(data: JsonValue): CalibrationFile {
            val o = data as? JsonValue.Obj ?: throw ModelPriorError("not a $FORMAT file")
            if ((o["format"] as? JsonValue.Str)?.value != FORMAT) throw ModelPriorError("not a $FORMAT file")
            val fv = runCatching { o["format_version"]?.asInt }.getOrNull()
            if (fv != FORMAT_VERSION) throw ModelPriorError("format_version ${o["format_version"]} is not $FORMAT_VERSION")
            val cps = o["checkpoints"] as? JsonValue.Obj
            if (cps == null || cps.fields.isEmpty()) throw ModelPriorError("no checkpoints")
            val out = LinkedHashMap<String, Checkpoint>()
            for ((name, raw) in cps.fields) {
                val cp = raw as? JsonValue.Obj ?: throw ModelPriorError("$name: not an object")
                val types = cp["types"] as? JsonValue.Obj ?: throw ModelPriorError("$name: no types")
                val t = types.fields.mapValues { (type, p) ->
                    if (type !in TYPES) throw ModelPriorError("$name: unknown type '$type'")
                    params(p, type, "$name/$type")
                }
                val langs = (cp["languages"] as? JsonValue.Obj)?.fields.orEmpty().mapValues { (lang, cells) ->
                    (cells as? JsonValue.Obj)?.fields.orEmpty().mapValues { (type, p) ->
                        if (type !in TYPES) throw ModelPriorError("$name/$lang: unknown type '$type'")
                        params(p, type, "$name/$lang/$type")
                    }
                }
                val low = (cp["low_trust_languages"] as? JsonValue.Arr)?.items.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }.toSet()
                out[name] = Checkpoint(t, langs, low)
            }
            return CalibrationFile(o["version"]?.takeUnless { it.isNull }?.asString ?: "?", out)
        }

        private fun params(p: JsonValue, type: String, where: String): Pair<Double, Double> {
            val o = p as? JsonValue.Obj ?: throw ModelPriorError("$where: not an object")
            val t = runCatching { o["T"]?.takeUnless { it.isNull }?.asDouble ?: 1.0 }.getOrElse { throw ModelPriorError("$where: T must be a number") }
            val b = runCatching { o["b"]?.takeUnless { it.isNull }?.asDouble ?: 0.0 }.getOrElse { throw ModelPriorError("$where: b must be a number") }
            if (!(t.isFinite() && t in 0.05..50.0) || !(b.isFinite() && b in -20.0..20.0)) {
                throw ModelPriorError("$where: T must be in [0.05, 50] and |b| <= 20 (T=$t, b=$b)")
            }
            if (type != ModelPrior.NOUL && b != 0.0) throw ModelPriorError("$where: a bias is only defined for noul")
            return t to b
        }
    }
}

/** Where one calibrated answer goes. */
enum class Band {
    /** Confidence at or above the act line: used as it is. */
    ACT,

    /** At or above the confirm line: a person confirms a pre-filled answer. */
    CONFIRM,

    /** Below it, a phishing act, a low-trust language, or no lines: a person answers. */
    HUMAN,
}

/** The band of one answer, with the lines that decided it. [reason] is set when a fixed rule overrode the lines. */
data class Gate(val band: Band, val act: Double?, val confirm: Double?, val cell: String, val version: String, val reason: String? = null)

/** `model_gates.json` (format `loupe-gates`, format_version 1). */
class GatesFile private constructor(val version: String, private val data: JsonValue.Obj) {

    /** (act, confirm) for a checkpoint x key cell (`noul`, `choice`, `score`, `phishing`); a language's own line where the file has one. */
    fun lines(checkpoint: String, key: String, language: String?): Pair<Double?, Double?> {
        val cell = ((data["checkpoints"] as? JsonValue.Obj)?.get(checkpoint) as? JsonValue.Obj)?.get(key) as? JsonValue.Obj
            ?: return null to null
        val own = if (language != null) ((cell["languages"] as? JsonValue.Obj)?.get(language) as? JsonValue.Obj) else null
        fun line(name: String): Double? {
            val v = own?.fields?.get(name) ?: cell[name]
            return v?.takeUnless { it.isNull }?.asDouble
        }
        return line("act") to line("confirm")
    }

    companion object {
        const val FORMAT: String = "loupe-gates"
        const val FORMAT_VERSION: Int = 1

        fun parse(text: String): GatesFile = from(
            try {
                JsonValue.parse(text)
            } catch (e: IllegalArgumentException) {
                throw ModelPriorError("not JSON: ${e.message}")
            },
        )

        /** Station's `Gates`: every line null or in [0, 1], a language's too. */
        fun from(data: JsonValue): GatesFile {
            val o = data as? JsonValue.Obj ?: throw ModelPriorError("not a $FORMAT file")
            if ((o["format"] as? JsonValue.Str)?.value != FORMAT) throw ModelPriorError("not a $FORMAT file")
            if (runCatching { o["format_version"]?.asInt }.getOrNull() != FORMAT_VERSION) {
                throw ModelPriorError("unsupported format_version ${o["format_version"]}")
            }
            val cps = o["checkpoints"] as? JsonValue.Obj ?: throw ModelPriorError("no checkpoints")
            for ((name, cp) in cps.fields) {
                for ((key, cell) in (cp as? JsonValue.Obj)?.fields.orEmpty()) {
                    val c = cell as? JsonValue.Obj ?: continue
                    val places = listOf(key to c) + (c["languages"] as? JsonValue.Obj)?.fields.orEmpty().map { (lg, v) -> "$key/$lg" to (v as? JsonValue.Obj) }
                    for ((where, src) in places) for (line in listOf("act", "confirm")) {
                        val v = src?.get(line) ?: continue
                        if (v.isNull) continue
                        val d = runCatching { v.asDouble }.getOrNull()
                        if (v !is JsonValue.Num || d == null || d !in 0.0..1.0) throw ModelPriorError("$name/$where: $line must be null or in [0, 1]")
                    }
                }
            }
            return GatesFile(o["version"]?.takeUnless { it.isNull }?.asString ?: "?", o)
        }
    }
}

/** One answer through the prior: calibrated probabilities (in the judgment's option order), the cell, the group, the band. */
data class PriorAnswer(
    val probabilities: Map<String, Double>,
    val cell: String,
    val language: String,
    val lowTrust: Boolean,
    val gate: Gate,
)

/**
 * The phone's calibration prior and gates (the files in `loupe-kit/data/model-prior/`, embedded at
 * build time), and the one place that applies them.
 *
 * - [recalibrator]: what a `DecisionEngine` takes; with `use_calibration` on it calibrates every
 *   answer by its question type and its input's language group, otherwise the identity.
 * - [gate]: act / confirm / person for one calibrated answer; phishing never acts and a low-trust
 *   language (Franco) always goes to a person, whatever the file says.
 * - [calibratedPhishingProbability]: the phishing formula's reading of the model — the calibrated
 *   p(phishing) of an `is_phishing` yes/no answer.
 */
object ModelPrior {
    /** The one checkpoint the phone runs. */
    const val CHECKPOINT: String = "multilingual"
    const val NOUL: String = "noul"
    const val CHOICE: String = "choice"
    const val SCORE: String = "score"
    const val PHISHING: String = "phishing"

    /** The shipped calibration; the identity (and [loadError] says why) if the embedded file were unreadable. */
    val calibration: CalibrationFile by lazy { runCatching { CalibrationFile.parse(ModelPriorData.CALIBRATION_JSON) }.getOrElse { CalibrationFile.IDENTITY } }

    /** The shipped gates; null (no bands: every answer goes to a person) if the embedded file were unreadable. */
    val gates: GatesFile? by lazy { runCatching { GatesFile.parse(ModelPriorData.GATES_JSON) }.getOrNull() }

    /** Why a shipped file did not load, or null. */
    val loadError: String?
        get() = listOfNotNull(
            runCatching { CalibrationFile.parse(ModelPriorData.CALIBRATION_JSON) }.exceptionOrNull()?.message?.let { "calibration: $it" },
            runCatching { GatesFile.parse(ModelPriorData.GATES_JSON) }.exceptionOrNull()?.message?.let { "gates: $it" },
        ).joinToString("; ").ifEmpty { null }

    /** noul (two options, the first the yes), score (ordinal levels) or choice. */
    fun typeOf(judgment: Judgment.Choice): String = when {
        judgment.ordinal -> SCORE
        judgment.candidates.size == 2 -> NOUL
        else -> CHOICE
    }

    /** Station's rule: a yes/no question whose id contains "phish". */
    fun isPhishing(judgmentId: String, type: String): Boolean = type == NOUL && "phish" in judgmentId.lowercase()

    /** The cell for [judgment] on text in [language]. */
    fun cell(judgment: Judgment.Choice, language: String, file: CalibrationFile = calibration): CalibrationCell =
        file.cell(CHECKPOINT, typeOf(judgment), language)

    /** The engine's recalibrator: the prior when [useCalibration], else the identity. */
    fun recalibrator(useCalibration: Boolean, file: CalibrationFile = calibration): Recalibrator =
        if (!useCalibration) Recalibrator.Identity else PriorRecalibrator(file)

    private class PriorRecalibrator(private val file: CalibrationFile) : Recalibrator {
        override fun calibrate(raw: Distribution): CalibratedDistribution = Recalibrator.Identity.calibrate(raw)

        override fun calibrate(raw: Distribution, judgment: Judgment.Choice, state: TextState): CalibratedDistribution {
            val c = cell(judgment, LangGroup.of(state.text), file)
            if (c.temperature == 1.0 && c.bias == 0.0) return Recalibrator.Identity.calibrate(raw)
            val yes = judgment.candidates.first().takeIf { typeOf(judgment) == NOUL }
            return LogitScaling(c.temperature, c.bias, yes).calibrate(raw)
        }

        override fun toString(): String = "ModelPrior(${file.version})"
    }

    /** Calibrates raw [masses] of [judgment] on [text] and bands the answer. */
    fun calibrate(
        judgment: Judgment.Choice,
        masses: Map<String, Double>,
        text: String,
        useCalibration: Boolean = true,
        file: CalibrationFile = calibration,
        gatesFile: GatesFile? = gates,
    ): PriorAnswer {
        val language = LangGroup.of(text)
        val type = typeOf(judgment)
        val c = if (useCalibration) file.cell(CHECKPOINT, type, language) else CalibrationCell(1.0, 0.0, "identity")
        val p = apply(judgment.candidates.map { masses.getValue(it) }, type, c.temperature, c.bias)
        val probabilities = judgment.candidates.zip(p).toMap()
        val lowTrust = file.lowTrust(CHECKPOINT, language)
        return PriorAnswer(probabilities, c.name, language, lowTrust, gate(judgment.id, type, p.max(), language, lowTrust, gatesFile))
    }

    /**
     * The band of one calibrated answer with top probability [confidence]. Fixed rules on top of the
     * file (Station's): a phishing question never acts; a low-trust language is always a person's.
     */
    fun gate(questionId: String, type: String, confidence: Double, language: String?, lowTrust: Boolean, gatesFile: GatesFile? = gates): Gate {
        val phishing = isPhishing(questionId, type)
        val key = if (phishing) PHISHING else type
        if (gatesFile == null) return Gate(Band.HUMAN, null, null, "$CHECKPOINT/$key", "none", "no gates file")
        var (act, confirm) = gatesFile.lines(CHECKPOINT, key, language)
        if (phishing) act = null
        val band = when {
            act != null && confidence >= act -> Band.ACT
            confirm != null && confidence >= confirm -> Band.CONFIRM
            else -> Band.HUMAN
        }
        return if (lowTrust) {
            Gate(Band.HUMAN, act, confirm, "$CHECKPOINT/$key", gatesFile.version, "low_trust_language")
        } else {
            Gate(band, act, confirm, "$CHECKPOINT/$key", gatesFile.version)
        }
    }

    /**
     * The calibrated probability that a message is phishing: the model's raw p(yes) for an
     * `is_phishing` yes/no question ([rawYes], the yes option's mass at the checkpoint's own T = 1),
     * through the noul cell of [text]'s language group. With [useCalibration] off it is [rawYes].
     */
    fun calibratedPhishingProbability(rawYes: Double, text: String, useCalibration: Boolean = true, file: CalibrationFile = calibration): Double {
        require(rawYes in 0.0..1.0) { "a probability, was $rawYes" }
        if (!useCalibration) return rawYes
        val c = file.cell(CHECKPOINT, NOUL, LangGroup.of(text))
        return apply(listOf(rawYes, 1.0 - rawYes), NOUL, c.temperature, c.bias)[0]
    }

    /**
     * The file's formula on the masses [p] of one answer, in option order (a yes/no's yes first):
     * noul `p(yes) = sigmoid((z_yes - z_no) / T + b)`, choice and score `softmax(z / T)`.
     */
    fun apply(p: List<Double>, type: String, temperature: Double, bias: Double): List<Double> {
        val z = p.map { ln(it.coerceAtLeast(1e-300)) / temperature }.toMutableList()
        if (type == NOUL && z.size == 2) z[0] += bias
        val top = z.max()
        val e = z.map { exp(it - top) }
        val s = e.sum()
        return e.map { it / s }
    }
}
