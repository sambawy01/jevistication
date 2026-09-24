package dev.loupe.kit.measure

import dev.loupe.engine.Distribution
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Policy
import dev.loupe.engine.Probability
import dev.loupe.engine.ResolvedBy
import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.DataExport
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.BaselineMode
import dev.loupe.templates.UserJudgment

/**
 * Who answers now, for one judgment: the model or its keyword baseline (owner decision B,
 * 2026-09-24, matching Loupe Station's `measure/baseline.py` `decide`).
 *
 * [compared] is how many of the user's corrections (current wording, latest per item, items still
 * scanned) have both a model answer and a baseline answer; [modelRight] / [baselineRight] how many
 * each got right. Under [BaselineMode.AUTO] the baseline answers when [compared] >=
 * [AutoBaseline.MIN_CORRECTIONS] and it is **strictly** more accurate; a tie keeps the model.
 */
data class AutoBaselineVerdict(
    val mode: BaselineMode,
    val hasBaseline: Boolean,
    val compared: Int,
    val modelRight: Int,
    val baselineRight: Int,
) {
    /** Enough corrections for the automatic rule to decide. */
    val enough: Boolean get() = compared >= AutoBaseline.MIN_CORRECTIONS

    /** Under Auto, the corrections say the baseline is strictly more accurate. */
    val baselineWinsOnCorrections: Boolean get() = enough && baselineRight > modelRight

    /** The baseline answers from the next run on. */
    val baselineAnswers: Boolean
        get() = hasBaseline && when (mode) {
            BaselineMode.ALWAYS_BASELINE -> true
            BaselineMode.ALWAYS_LAYA -> false
            BaselineMode.AUTO -> baselineWinsOnCorrections
        }

    /** Under Auto the baseline answers but the model is still asked, and its answer kept alongside. */
    val automatic: Boolean get() = mode == BaselineMode.AUTO && baselineAnswers

    /** One plain line for the Measure / Baseline screens (iOS and desktop). */
    val line: String
        get() = when {
            !hasBaseline -> "This judgment has no baseline rule, so Laya always answers."
            mode == BaselineMode.ALWAYS_BASELINE -> "Always baseline: the keyword rule answers and Laya is not asked."
            mode == BaselineMode.ALWAYS_LAYA -> "Always Laya: Laya answers, whatever your corrections say about the baseline."
            !enough -> "Auto: Laya answers. The baseline rule takes over only if it is right more often than Laya on at least " +
                "${AutoBaseline.MIN_CORRECTIONS} of your corrections (${AutoBaseline.MIN_CORRECTIONS - compared} more needed; $compared so far)."
            baselineAnswers -> "Auto: the baseline rule answers. On your $compared corrections it was right $baselineRight times, Laya $modelRight. " +
                "Laya is still asked and its answer is kept beside the rule's."
            else -> "Auto: Laya answers. On your $compared corrections Laya was right $modelRight times, the baseline $baselineRight."
        }
}

object AutoBaseline {
    /** Corrections needed before they decide (Station's `MIN_LABELS_TO_SWITCH`). */
    const val MIN_CORRECTIONS: Int = 30

    /** The ledger's check name for a row the baseline answered automatically: `mechanical:auto-baseline`. */
    const val CHECK: String = "auto-baseline"

    /** The ledger's check name for "Always baseline": `mechanical:baseline` (the model is not asked). */
    const val ALWAYS_CHECK: String = "baseline"

    /**
     * The model's own answer on [row], where it has one: a model row's top label, or the answer
     * kept alongside an automatic-baseline row. Null for unusable rows and for mechanical rows the
     * model never saw (Always baseline, an exact duplicate).
     */
    fun modelAnswer(row: LedgerRow): String? = when {
        row.action == Policy.UNUSABLE -> null
        row.isModelPrediction -> row.distribution.argmax
        row.resolvedBy == ResolvedBy.Mechanical(CHECK) -> row.modelDistribution?.argmax
        else -> null
    }

    /**
     * The verdict from corrected rows. [rows] are the judgment's effective rows (current wording,
     * latest per item, corrections applied); [baselineAnswer] gives the baseline's answer for an
     * item id, or null when the item is gone. Platform-neutral so the desktop, with its own item
     * type, runs the same rule.
     */
    fun verdict(mode: BaselineMode, hasBaseline: Boolean, rows: List<LedgerRow>, baselineAnswer: (String) -> String?): AutoBaselineVerdict {
        var compared = 0
        var model = 0
        var base = 0
        if (hasBaseline) {
            for (row in rows.distinctBy { it.itemId }) {
                val truth = row.correction ?: continue
                val id = row.itemId ?: continue
                val m = modelAnswer(row) ?: continue
                val b = baselineAnswer(id) ?: continue
                compared++
                if (m == truth) model++
                if (b == truth) base++
            }
        }
        return AutoBaselineVerdict(mode, hasBaseline, compared, model, base)
    }

    /** [verdict] for one judgment over the phone's common items. */
    fun verdict(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>, items: List<SourceItem>): AutoBaselineVerdict {
        val baseline = judgment.baseline
        val byId = items.associateBy { it.id }
        val rows = DataExport.effectiveRows(all, judgment.id, judgment.criteriaHash, corrections)
        return verdict(judgment.baselineMode, baseline != null, rows) { id -> byId[id]?.let { baseline?.answer(it.text) } }
    }

    /**
     * The row logged when the baseline answers automatically: the baseline's [answer] as the
     * decision, certain (it is a rule), `mechanical:auto-baseline`, with the model's distribution
     * from [modelRow] kept in `modelDistribution` (none when the model's answer was unusable).
     */
    fun resolve(modelRow: LedgerRow, answer: String): LedgerRow {
        require(answer in modelRow.distribution.labels) { "baseline answer '$answer' is not an option of ${modelRow.judgmentId}" }
        val usable = modelRow.failure == null && modelRow.action != Policy.UNUSABLE
        return modelRow.copy(
            distribution = Distribution.of(modelRow.distribution.labels.associateWith { if (it == answer) 1.0 else 0.0 }),
            action = answer,
            propensity = Probability.of(1.0),
            failure = null,
            resolvedBy = ResolvedBy.Mechanical(CHECK),
            modelDistribution = if (usable) modelRow.distribution else null,
        )
    }
}
