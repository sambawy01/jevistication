package dev.loupe.kit.measure

import dev.loupe.engine.Backend
import dev.loupe.engine.Calibration
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Fixture
import dev.loupe.engine.Harness
import dev.loupe.engine.JudgmentReport
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Policy
import dev.loupe.engine.Probability
import dev.loupe.engine.ReliabilityBin
import dev.loupe.engine.Scored
import dev.loupe.engine.SelectionReason
import dev.loupe.engine.ThresholdPreview
import dev.loupe.engine.ThresholdSlider
import dev.loupe.engine.UncertainQueue
import dev.loupe.engine.VisibleCalibration
import dev.loupe.templates.BaselineMode
import dev.loupe.templates.TransactionEvidence
import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.CorrectionRecord
import dev.loupe.persistence.DataExport
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.UserJudgment

/** One item in the phone's Unsure queue: which judgment asked, the logged row, and why it was picked. */
data class UnsureEntry(
    val judgment: UserJudgment,
    val row: LedgerRow,
    val item: SourceItem,
    val reason: SelectionReason,
    /** `1 - margin`: 1 when the top two answers are tied. */
    val informativeness: Double,
) {
    val itemId: String get() = item.id
    val isAudit: Boolean get() = reason == SelectionReason.AUDIT
    val modelPick: String get() = row.distribution.argmax

    /** The answers as (label, raw mass), in the judgment's order: one button each. */
    val options: List<Pair<String, Double>>
        get() = judgment.shape.candidates.map { it to (if (it in row.distribution.labels) row.distribution.getValue(it).value else 0.0) }

    /**
     * Why this item is in front of you, in the owner's words (no "model", no "torn"): for an
     * uncertain item, how sure Loupe was — the share it gave the positive option of a yes/no, or its
     * top pick otherwise — e.g. "Loupe wasn't sure (52% yes) — your answer teaches it"; for the
     * audit arm, "a random check on an answer Loupe was sure of" (2026-09-25: "Loupe", not "Laya").
     */
    val why: String
        get() {
            if (isAudit) return "a random check on an answer Loupe was sure of"
            val positive = judgment.positiveLabel
            val lean = if (positive != null && judgment.shape.candidates.size == 2) {
                val mass = if (positive in row.distribution.labels) row.distribution.getValue(positive).value else 0.0
                "${formatPercent(mass)} yes"
            } else {
                "${formatPercent(row.distribution.getValue(modelPick).value)} “$modelPick”"
            }
            return "Loupe wasn't sure ($lean) — your answer teaches it"
        }

    /** The key the answer is filed under: this item, under this judgment's exact wording. */
    val key: CorrectionKey get() = CorrectionKey(judgment.id, judgment.criteriaHash, item.id)
}

/** D2 for one judgment, gated exactly as the desktop gates it. */
data class MeasureSummary(
    val decisions: Int,
    val earlierWording: Int,
    /** Corrected model answers (not unusable, not mechanical). */
    val corrections: Int,
    val unusable: Int,
    val mechanical: Int,
    val coverage: Double,
    val declined: Double,
    /** Null below [JudgmentMeasure.MIN_FOR_AGREEMENT]. */
    val agreement: Double?,
    val selectiveAccuracy: Double?,
    val selectiveN: Int,
    /** ECE, Brier and reliability bins: null / empty below [JudgmentMeasure.MIN_FOR_RELIABILITY]. */
    val ece: Double?,
    val brier: Double?,
    val reliability: List<ReliabilityBin>,
    val overconfident: List<ReliabilityBin>,
    val correctionsNeededForAgreement: Int,
    val correctionsNeededForReliability: Int,
) {
    val calibrationShown: Boolean get() = ece != null

    /** The honest "not yet" line, or null once calibration is shown. */
    val gateMessage: String?
        get() = if (calibrationShown) {
            null
        } else {
            "$correctionsNeededForReliability more correction(s) needed for a reliability diagram and ECE. " +
                "Until then the probabilities shown are the model's raw output, uncalibrated."
        }
}

/** D4 on the phone: the model against the dumb baseline on the user's own corrected items. */
data class BaselineVerdict(val report: JudgmentReport, val baselineDescription: String) {
    val enough: Boolean get() = report.n >= JudgmentMeasure.MIN_FOR_BASELINE
    val baselineWins: Boolean get() = enough && !report.beatsBaseline

    /** The desktop's verdict, word for word. */
    val message: String
        get() = when {
            !enough -> "Only ${report.n} corrected item(s): too few to say which wins. ${JudgmentMeasure.MIN_FOR_BASELINE - report.n} more, please."
            report.beatsBaseline -> "On your ${report.n} corrected items the model beats the baseline, compared at equal (full) coverage."
            else -> "The model is NOT beating the dumb baseline on your ${report.n} corrected items. Until it does, the baseline is the honest choice for this judgment."
        }
}

/** Me's single line. [agreement] is null until [JudgmentMeasure.MIN_FOR_AGREEMENT] corrections exist. */
data class OverallAgreement(val corrections: Int, val agreement: Double?) {
    val needed: Int get() = (JudgmentMeasure.MIN_FOR_AGREEMENT - corrections).coerceAtLeast(0)

    val line: String
        get() = if (agreement == null) {
            "$needed more correction(s) before Loupe says how often it agrees with you (it has $corrections)."
        } else {
            "Agrees with you ${formatPercent(agreement)} over $corrections corrections"
        }
}

/** The slider's counterfactual, in the words the phone shows. */
data class PreviewText(val preview: ThresholdPreview) {
    val line: String
        get() {
            val change = when {
                preview.additionalActions > 0 -> "${preview.additionalActions} more acted on"
                preview.fewerActions > 0 -> "${preview.fewerActions} fewer acted on"
                else -> return "no change at this threshold"
            }
            val rescued = preview.mistakesIntroduced?.let { "you would have rescued $it" } ?: "rescued: unknown (none of those items has your answer)"
            return "$change · $rescued"
        }

    val basis: String get() = "Counted over ${preview.rowsConsidered} logged decision(s), resting on ${preview.correctionsConsulted} of your corrections — not predicted."
}

/**
 * The per-judgment measurement the phone shows (D1-D4), as pure functions over the ledger, the
 * corrections and the scanned items — the desktop's `Analysis`, over common items. One rule runs
 * through all of it: only decisions under the judgment's **current wording** count (criteria-hash
 * re-keying), latest per item.
 */
object JudgmentMeasure {
    const val MIN_FOR_AGREEMENT: Int = 10
    const val MIN_FOR_RELIABILITY: Int = 30
    const val MIN_FOR_BASELINE: Int = 10
    const val QUEUE_SIZE: Int = 50
    const val AUDIT_SHARE: Double = 0.2
    const val SEED: Long = 11L

    fun effectiveRows(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>): List<LedgerRow> =
        DataExport.effectiveRows(all, judgment.id, judgment.criteriaHash, corrections)

    private fun acted(row: LedgerRow, judgment: UserJudgment): Boolean {
        val mass = row.distribution.getValue(row.distribution.argmax).value
        return row.action != Policy.UNUSABLE && mass >= judgment.threshold &&
            !(row.truncated && judgment.onFailure != FailurePosture.OPEN)
    }

    /** D1 across every judgment: most torn first, plus a random audit arm of confident ones. */
    fun queue(
        all: List<LedgerRow>,
        judgments: List<UserJudgment>,
        corrections: Map<CorrectionKey, String>,
        items: List<SourceItem>,
        size: Int = QUEUE_SIZE,
    ): List<UnsureEntry> {
        val byId = items.associateBy { it.id }
        val byJudgment = judgments.associateBy { it.id }
        // Only items still scanned: the phone cannot show (or answer) what it no longer has.
        // A model answer logged before the evidence gate existed, on an item the gate now answers
        // by rule (a shop's product page under "Is this a receipt?"), is not a question worth asking.
        val candidates = judgments.flatMap { j ->
            effectiveRows(all, j, corrections).filter { r ->
                val item = r.itemId?.let(byId::get)
                item != null && (r.isMechanical || TransactionEvidence.ruleAnswer(j, item.text) == null)
            }
        }
        return UncertainQueue.select(candidates, size, AUDIT_SHARE, SEED).map { e ->
            UnsureEntry(byJudgment.getValue(e.row.judgmentId), e.row, byId.getValue(e.row.itemId!!), e.reason, e.informativeness)
        }
    }

    /** An answer in the queue: a correction under the judgment's current wording. */
    fun correction(judgment: UserJudgment, itemId: String, label: String, confirmed: Boolean, at: String): CorrectionRecord {
        require(label in judgment.shape.candidates) { "'$label' is not an option of ${judgment.id}" }
        return CorrectionRecord(judgment.id, judgment.criteriaHash, itemId, label, at, confirmed)
    }

    /** Undo: appends a retraction (the log is never rewritten). */
    fun retraction(key: CorrectionKey, at: String): CorrectionRecord =
        CorrectionRecord(key.judgmentId, key.criteriaHash, key.itemId, null, at, false)

    fun summary(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>): MeasureSummary {
        val rows = effectiveRows(all, judgment, corrections)
        val corrected = rows.filter { it.correction != null && it.action != Policy.UNUSABLE && !it.isMechanical }
        val actedCorrected = corrected.filter { acted(it, judgment) }
        val n = rows.size
        val enough = corrected.size >= MIN_FOR_AGREEMENT
        val reliable = corrected.size >= MIN_FOR_RELIABILITY
        val view = if (reliable) VisibleCalibration.forJudgment(rows.filter { it.action != Policy.UNUSABLE }, judgment.id) else null
        val pairs = corrected.filter { it.isModelPrediction }.map { it.distribution to it.correction!! }
        fun right(r: LedgerRow) = r.distribution.argmax == r.correction
        return MeasureSummary(
            decisions = n,
            earlierWording = all.count { it.judgmentId == judgment.id && it.criteriaHash != judgment.criteriaHash },
            corrections = corrected.size,
            unusable = rows.count { it.action == Policy.UNUSABLE },
            mechanical = rows.count { it.isMechanical },
            coverage = if (n == 0) 0.0 else rows.count { acted(it, judgment) }.toDouble() / n,
            declined = if (n == 0) 0.0 else rows.count { !acted(it, judgment) && it.action != Policy.UNUSABLE }.toDouble() / n,
            agreement = if (enough) corrected.count(::right).toDouble() / corrected.size else null,
            selectiveAccuracy = if (enough && actedCorrected.isNotEmpty()) actedCorrected.count(::right).toDouble() / actedCorrected.size else null,
            selectiveN = actedCorrected.size,
            ece = view?.ece,
            brier = if (reliable && pairs.isNotEmpty()) Calibration.brier(pairs) else null,
            reliability = view?.reliability.orEmpty(),
            overconfident = view?.overconfidentBins.orEmpty(),
            correctionsNeededForAgreement = (MIN_FOR_AGREEMENT - corrected.size).coerceAtLeast(0),
            correctionsNeededForReliability = (MIN_FOR_RELIABILITY - corrected.size).coerceAtLeast(0),
        )
    }

    /** D3: what [candidate] would have changed over the rows already logged (A8). */
    fun preview(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>, candidate: Double): PreviewText =
        PreviewText(
            ThresholdSlider.preview(
                effectiveRows(all, judgment, corrections).filter { it.action != Policy.UNUSABLE },
                Probability.of(judgment.threshold),
                Probability.of(candidate.coerceIn(0.0, 1.0)),
            ),
        )

    /** [judgment] with the slider's value applied. */
    fun withThreshold(judgment: UserJudgment, value: Double): UserJudgment = judgment.copy(threshold = value.coerceIn(0.0, 1.0))

    /**
     * D4 through `Harness.evaluate`, replaying exactly what the model logged (not re-running it),
     * on corrected model-answered items still scanned. Null with no baseline or nothing corrected.
     */
    fun baseline(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>, items: List<SourceItem>): BaselineVerdict? {
        val baseline = judgment.baseline ?: return null
        val byId = items.associateBy { it.id }
        // Model rows, plus automatic-baseline rows through the model answer kept beside them
        // (decision B): once the baseline answers, the user's corrections keep measuring both.
        val rows = effectiveRows(all, judgment, corrections)
            .filter { it.correction != null && it.itemId in byId && (!it.isMechanical || it.modelDistribution != null) }
            .distinctBy { it.itemId }
        if (rows.isEmpty()) return null
        val logged = rows.associateBy { it.itemId!! }
        val replay = Backend { _, state ->
            val row = logged.getValue(state.items.single().id)
            val said = row.modelDistribution ?: row.distribution
            Scored(
                said.labels.associateWith { said.getValue(it).value },
                modelContext = row.truncation?.modelContext,
                optionCriteria = row.truncation?.optionCriteria,
            )
        }
        val engine = DecisionEngine(replay, Probability.of(judgment.threshold))
        val fixtures = rows.map { row -> byId.getValue(row.itemId!!).let { Fixture(it.toItem(), row.correction!!, it.sourceId) } }
        return BaselineVerdict(Harness.evaluate(judgment.choice, fixtures, engine, baseline.asFunction()), baseline.description)
    }

    /** "Always baseline" on or back to Auto (the old switch); see [withBaselineMode]. */
    fun withBaseline(judgment: UserJudgment, on: Boolean): UserJudgment =
        withBaselineMode(judgment, if (on) BaselineMode.ALWAYS_BASELINE else BaselineMode.AUTO)

    /** Who answers from the next run on: Auto, Always baseline or Always Laya (decision B). */
    fun withBaselineMode(judgment: UserJudgment, mode: BaselineMode): UserJudgment =
        judgment.copy(baselineMode = if (judgment.baseline == null) BaselineMode.AUTO else mode)

    /** Decision B's verdict for [judgment]: who answers now, and on what evidence. */
    fun autoBaseline(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>, items: List<SourceItem>): AutoBaselineVerdict =
        AutoBaseline.verdict(all, judgment, corrections, items)

    /**
     * Me's line, pooled over every judgment's corrected model answers under current wording. Shown
     * only from [MIN_FOR_AGREEMENT] corrections; each judgment's own figure is on its Measure screen.
     */
    fun overall(all: List<LedgerRow>, judgments: List<UserJudgment>, corrections: Map<CorrectionKey, String>): OverallAgreement {
        val corrected = judgments.flatMap { effectiveRows(all, it, corrections) }
            .filter { it.correction != null && it.action != Policy.UNUSABLE && !it.isMechanical }
        val n = corrected.size
        return OverallAgreement(n, if (n >= MIN_FOR_AGREEMENT) corrected.count { it.distribution.argmax == it.correction }.toDouble() / n else null)
    }
}

internal fun formatPercent(x: Double): String = "${kotlin.math.round(x * 100).toInt()}%"
