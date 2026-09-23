package dev.loupe.desktop.core

import dev.loupe.engine.Backend
import dev.loupe.engine.Calibration
import dev.loupe.engine.CalibrationExample
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Fixture
import dev.loupe.engine.Harness
import dev.loupe.engine.JudgmentCalibrationView
import dev.loupe.engine.JudgmentReport
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Policy
import dev.loupe.engine.Probability
import dev.loupe.engine.QueueEntry
import dev.loupe.engine.ThresholdPreview
import dev.loupe.engine.ThresholdSlider
import dev.loupe.engine.UncertainQueue
import dev.loupe.engine.VisibleCalibration
import dev.loupe.engine.TemperatureScaling
import dev.loupe.sources.SourceItem
import dev.loupe.templates.UserJudgment

/** The key a correction is filed under: one item, under one judgment's exact wording. */
data class CorrectionKey(val judgmentId: String, val criteriaHash: String, val itemId: String)

/** One decision as the screens show it: the ledger row, the item it was about, and its status. */
data class DecisionView(
    val row: LedgerRow,
    /** The scanned item, or null when its source is no longer scanned. */
    val item: SourceItem?,
    val topLabel: String,
    /** The model's raw mass on [topLabel]. Uncalibrated: the app runs the identity recalibrator. */
    val topMass: Double,
    /** At or above the judgment's current threshold, so the engine would act rather than queue. */
    val acted: Boolean,
    val unusable: Boolean,
    val mechanical: Boolean,
    val correction: String?,
) {
    /** What the engine says, in words: its answer, or that it declined. */
    val status: String
        get() = when {
            unusable -> "could not judge"
            acted -> topLabel
            else -> "unsure"
        }
}

/** Per-judgment calibration, exactly as D2 asks for it, with the honest "not yet" built in. */
data class CalibrationSummary(
    val decisions: Int,
    /** Decisions made under earlier wording: kept in the ledger, not counted here. */
    val earlierWording: Int,
    val corrections: Int,
    val unusable: Int,
    val coverage: Double,
    val declined: Double,
    /** Correct among corrected items the engine acted on; null below [Analysis.MIN_FOR_AGREEMENT]. */
    val selectiveAccuracy: Double?,
    val selectiveN: Int,
    /** Correct among all corrected items (forced to answer); null below the minimum. */
    val agreement: Double?,
    /** ECE, reliability and over-confident bins; null below [Analysis.MIN_FOR_RELIABILITY]. */
    val view: JudgmentCalibrationView?,
    /** A held-out temperature fit, once there are [Analysis.MIN_FOR_FIT] corrections. */
    val heldOut: HeldOutFit?,
    /** How many more corrections before a reliability diagram is shown; zero once it is. */
    val correctionsNeededForReliability: Int,
)

/** Temperature fitted on one half of the corrections and scored on the other — never on itself. */
data class HeldOutFit(val temperature: Double, val fitN: Int, val testN: Int, val rawEce: Double, val fittedEce: Double)

/** Model against the dumb baseline on the user's own corrected items (D4). */
data class BaselineComparison(val report: JudgmentReport, val baselineDescription: String)

/** Counts for the census screen. */
data class Census(
    val total: Int,
    val byAnswer: List<Pair<String, Int>>,
    val byMonth: List<Pair<String, Int>>,
    val bySource: List<Pair<String, Int>>,
)

/**
 * Everything the per-judgment screens compute, as pure functions over the ledger, the corrections
 * and the scanned items — the engine does the arithmetic; this only selects what it is given.
 *
 * One rule runs through all of it: **only decisions made under the judgment's current wording
 * count**, and of those only the latest per item. Rewording a judgment therefore starts its numbers
 * again, honestly, rather than mixing evidence about two different questions.
 */
object Analysis {
    /** Below this many corrections, accuracy figures are not shown — the interval would be enormous. */
    const val MIN_FOR_AGREEMENT: Int = 10

    /** Below this many, no reliability diagram or ECE: ten bins need more than a handful of points. */
    const val MIN_FOR_RELIABILITY: Int = 30

    /** Below this many, no temperature fit: half of them are held out to score it. */
    const val MIN_FOR_FIT: Int = 60

    /** Below this many, the baseline comparison says so instead of declaring a winner. */
    const val MIN_FOR_BASELINE: Int = 10

    /** The latest correction per key; a retraction removes the key. Order is the file's order. */
    fun correctionIndex(corrections: List<Correction>): Map<CorrectionKey, String> {
        val index = LinkedHashMap<CorrectionKey, String>()
        for (c in corrections) {
            val key = CorrectionKey(c.judgmentId, c.criteriaHash, c.itemId)
            if (c.label == null) index.remove(key) else index[key] = c.label
        }
        return index
    }

    /**
     * The rows that count for [judgment]: current wording, latest per item, with the user's
     * correction merged in as `LedgerRow.correction` — the field every engine function reads.
     */
    fun effectiveRows(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>): List<LedgerRow> {
        val hash = judgment.criteriaHash
        val latest = LinkedHashMap<String, LedgerRow>()
        for (row in all) {
            if (row.judgmentId != judgment.id || row.criteriaHash != hash) continue
            val item = row.itemId ?: continue
            latest.remove(item)
            latest[item] = row
        }
        return latest.values.map { row ->
            val label = corrections[CorrectionKey(judgment.id, hash, row.itemId!!)]
            if (label != null && label in row.distribution.labels) row.copy(correction = label) else row
        }
    }

    /** Rows for [judgment] logged under wording it no longer has. */
    fun earlierWording(all: List<LedgerRow>, judgment: UserJudgment): Int =
        all.count { it.judgmentId == judgment.id && it.criteriaHash != judgment.criteriaHash }

    fun views(rows: List<LedgerRow>, judgment: UserJudgment, items: Map<String, SourceItem>): List<DecisionView> =
        rows.map { row ->
            val top = row.distribution.argmax
            val mass = row.distribution.getValue(top).value
            val unusable = row.action == Policy.UNUSABLE
            DecisionView(
                row = row,
                item = row.itemId?.let(items::get),
                topLabel = top,
                topMass = mass,
                acted = !unusable && mass >= judgment.threshold,
                unusable = unusable,
                // Answered by the judgment's mechanical check (an exact duplicate by hash), not the model.
                mechanical = judgment.mechanical != null && mass == 1.0 && row.itemId?.let(items::get)?.duplicateOf != null,
                correction = row.correction,
            )
        }

    fun calibration(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>): CalibrationSummary {
        val rows = effectiveRows(all, judgment, corrections)
        val views = views(rows, judgment, emptyMap())
        val corrected = views.filter { it.correction != null && !it.unusable }
        val actedCorrected = corrected.filter { it.acted }
        val n = views.size
        val enough = corrected.size >= MIN_FOR_AGREEMENT
        return CalibrationSummary(
            decisions = n,
            earlierWording = earlierWording(all, judgment),
            corrections = corrected.size,
            unusable = views.count { it.unusable },
            coverage = if (n == 0) 0.0 else views.count { it.acted }.toDouble() / n,
            declined = if (n == 0) 0.0 else views.count { !it.acted && !it.unusable }.toDouble() / n,
            selectiveAccuracy = if (enough && actedCorrected.isNotEmpty()) actedCorrected.count { it.topLabel == it.correction }.toDouble() / actedCorrected.size else null,
            selectiveN = actedCorrected.size,
            agreement = if (enough) corrected.count { it.topLabel == it.correction }.toDouble() / corrected.size else null,
            view = if (corrected.size >= MIN_FOR_RELIABILITY) {
                VisibleCalibration.forJudgment(rows.filter { it.action != Policy.UNUSABLE }, judgment.id)
            } else {
                null
            },
            heldOut = if (corrected.size >= MIN_FOR_FIT) heldOut(corrected.map { it.row }) else null,
            correctionsNeededForReliability = (MIN_FOR_RELIABILITY - corrected.size).coerceAtLeast(0),
        )
    }

    /** Splits by a hash of the item id (so the split is stable), fits on one half, scores the other. */
    private fun heldOut(corrected: List<LedgerRow>): HeldOutFit? {
        val (fit, test) = corrected.partition { (it.itemId.hashCode() and 1) == 0 }
        if (fit.size < 10 || test.size < 10) return null
        val scaling = TemperatureScaling.fit(fit.map { CalibrationExample(it.distribution, it.correction!!) })
        val raw = test.map { it.distribution to it.correction!! }
        val fitted = test.map { scaling.calibrate(it.distribution).distribution to it.correction!! }
        return HeldOutFit(scaling.temperature, fit.size, test.size, Calibration.ece(raw), Calibration.ece(fitted))
    }

    fun preview(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>, candidate: Double): ThresholdPreview =
        ThresholdSlider.preview(
            effectiveRows(all, judgment, corrections).filter { it.action != Policy.UNUSABLE },
            Probability.of(judgment.threshold),
            Probability.of(candidate.coerceIn(0.0, 1.0)),
        )

    /** D1: the most informative unreviewed decisions plus a random audit arm of confident ones. */
    fun queue(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>, size: Int = 25): List<QueueEntry> =
        UncertainQueue.select(effectiveRows(all, judgment, corrections), size, auditShare = 0.2, seed = 11L)

    /**
     * D4 through `Harness.evaluate`, unchanged: the corrected items become fixtures, and a replay
     * backend hands back exactly the distribution the model logged for each one — so the model is
     * not re-run and the comparison is of what it actually said.
     */
    fun baseline(
        all: List<LedgerRow>,
        judgment: UserJudgment,
        corrections: Map<CorrectionKey, String>,
        items: Map<String, SourceItem>,
    ): BaselineComparison? {
        val baseline = judgment.baseline ?: return null
        val rows = effectiveRows(all, judgment, corrections).filter { it.correction != null && it.itemId in items }
        if (rows.isEmpty()) return null
        val logged = rows.associate { it.itemId!! to it.distribution }
        val replay = Backend { _, state ->
            val id = state.items.single().id
            logged.getValue(id).labels.associateWith { logged.getValue(id).getValue(it).value }
        }
        val engine = DecisionEngine(replay, Probability.of(judgment.threshold))
        val fixtures = rows.map { row ->
            val item = items.getValue(row.itemId!!)
            Fixture(item.toItem(), row.correction!!, item.sourceId)
        }
        return BaselineComparison(Harness.evaluate(judgment.choice, fixtures, engine, baseline.asFunction()), baseline.description)
    }

    fun census(views: List<DecisionView>, sourceNames: Map<String, String>): Census {
        fun <K> count(key: (DecisionView) -> K): List<Pair<K, Int>> =
            views.groupingBy(key).eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        return Census(
            total = views.size,
            byAnswer = count { it.correction?.let { c -> "$c (your correction)" } ?: it.status },
            byMonth = views.groupingBy { v -> v.item?.date?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: "no date" }
                .eachCount().entries.sortedBy { it.key }.map { it.key to it.value },
            bySource = count { v -> v.item?.sourceId?.let { sourceNames[it] ?: it } ?: "source not scanned" },
        )
    }

    /** A census of the scanned items themselves, before any judgment: what there is. */
    fun itemCensus(items: List<SourceItem>, sourceNames: Map<String, String>): Census = Census(
        total = items.size,
        byAnswer = items.groupingBy { it.kind.title }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
        byMonth = items.groupingBy { i -> i.date?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: "no date" }
            .eachCount().entries.sortedBy { it.key }.map { it.key to it.value },
        bySource = items.groupingBy { sourceNames[it.sourceId] ?: it.sourceId }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
    )
}
