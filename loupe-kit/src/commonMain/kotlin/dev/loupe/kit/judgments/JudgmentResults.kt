package dev.loupe.kit.judgments

import dev.loupe.engine.FailurePosture
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Policy
import dev.loupe.engine.ResolvedBy
import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.DataExport
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.Shape
import dev.loupe.templates.UserJudgment

/**
 * One decision as the phone's Results screen shows it — the common twin of the desktop's
 * `DecisionView`, over `:sources-common` items.
 */
data class ResultRow(
    val row: LedgerRow,
    /** The scanned item, or null when its source is no longer scanned. */
    val item: SourceItem?,
    val topLabel: String,
    /** The model's raw mass on [topLabel]. Uncalibrated: the app runs the identity recalibrator. */
    val topMass: Double,
    /** At or above the threshold (and not held back by a cut input), so the engine acts. */
    val acted: Boolean,
    val unusable: Boolean,
    /** A mechanical check answered; the model was not asked. */
    val mechanical: Boolean,
    val correction: String?,
) {
    val itemId: String get() = row.itemId ?: ""

    /** The mechanical check that answered, from the row's `resolvedBy`; null for a model row. */
    val mechanicalCheck: String? get() = (row.resolvedBy as? ResolvedBy.Mechanical)?.check

    /** "Answered by rule: …; the model was not asked." for a mechanical row, else null. */
    val ruleNote: String?
        get() {
            val check = mechanicalCheck ?: return null
            val copyOf = item?.duplicateOf?.let { " — a byte-identical copy of ${it.substringAfterLast('/')}" } ?: ""
            return "Answered by rule: $check$copyOf; the model was not asked."
        }

    /** The desktop's "Input was cut: read first N of M …" note, or null when the whole item was read. */
    val inputCutNote: String?
        get() {
            val t = row.truncation?.takeIf { it.isCut } ?: return null
            val parts = listOfNotNull(
                t.textBudget?.let { "first ${it.kept} of ${it.total} characters (text budget)" },
                t.modelContext?.let { "first ${it.kept} of ${it.total} tokens (model context)" },
            )
            return "Input was cut: read " + parts.joinToString(", then ") + "."
        }

    /** "Criteria were shortened …" when the options' descriptions were cut to fit; else null. */
    val criteriaCutNote: String?
        get() {
            val c = row.truncation?.optionCriteria ?: return null
            return "Criteria were shortened to fit: the model read ${c.kept} of ${c.total} option tokens."
        }

    /** The model's (or rule's) whole distribution, labels in the judgment's order, as (label, mass). */
    fun masses(judgment: UserJudgment): List<Pair<String, Double>> =
        judgment.shape.candidates.filter { it in row.distribution.labels }.map { it to row.distribution.getValue(it).value }

    /** The status in words: the answer, "unsure — leans …", or "could not judge". */
    fun status(judgment: UserJudgment): String = when {
        unusable -> "could not judge"
        acted -> JudgmentResults.shownAnswer(judgment, topLabel)
        else -> "unsure — leans ${JudgmentResults.shownAnswer(judgment, topLabel)}"
    }
}

/** Counts for one judgment under its current wording, for the My judgments list. */
data class JudgmentCounts(
    val decisions: Int,
    val acted: Int,
    val unsure: Int,
    val unusable: Int,
    val mechanical: Int,
    /** Rows logged under wording the judgment no longer has: kept, not counted. */
    val earlierWording: Int,
)

/** What a sweep would do right now, before it starts. */
data class SweepPlan(
    val toJudge: List<SourceItem>,
    /** Items with no text (images, scans): not sent to the model. */
    val withoutText: Int,
    /** Items already decided under the current wording: not re-run. */
    val alreadyDecided: Int,
)

/**
 * The per-judgment selection the phone's screens read — the desktop's `Analysis.views` rule, over
 * common items: **only decisions under the judgment's current wording count**, latest per item.
 */
object JudgmentResults {
    fun effectiveRows(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>): List<LedgerRow> =
        DataExport.effectiveRows(all, judgment.id, judgment.criteriaHash, corrections)

    /** Rows for [judgment], least sure first (the order that needs the user most). */
    fun rows(
        all: List<LedgerRow>,
        judgment: UserJudgment,
        corrections: Map<CorrectionKey, String>,
        items: List<SourceItem>,
    ): List<ResultRow> {
        val byId = items.associateBy { it.id }
        return effectiveRows(all, judgment, corrections).map { row ->
            val top = row.distribution.argmax
            val mass = row.distribution.getValue(top).value
            val unusable = row.action == Policy.UNUSABLE
            ResultRow(
                row = row,
                item = row.itemId?.let(byId::get),
                topLabel = top,
                topMass = mass,
                // Policy.onCutInput: a cut input only acts under an OPEN posture; otherwise it queues.
                acted = !unusable && mass >= judgment.threshold && !(row.truncated && judgment.onFailure != FailurePosture.OPEN),
                unusable = unusable,
                mechanical = row.isMechanical,
                correction = row.correction,
            )
        }.sortedWith(compareBy<ResultRow> { if (it.unusable) 0 else 1 }.thenBy { it.topMass })
    }

    fun counts(all: List<LedgerRow>, judgment: UserJudgment, corrections: Map<CorrectionKey, String>): JudgmentCounts {
        val rows = rows(all, judgment, corrections, emptyList())
        return JudgmentCounts(
            decisions = rows.size,
            acted = rows.count { it.acted },
            unsure = rows.count { !it.acted && !it.unusable },
            unusable = rows.count { it.unusable },
            mechanical = rows.count { it.mechanical },
            earlierWording = all.count { it.judgmentId == judgment.id && it.criteriaHash != judgment.criteriaHash },
        )
    }

    /** Which of [items] a sweep sends to the engine: those with text, not yet decided under this wording. */
    fun plan(
        all: List<LedgerRow>,
        judgment: UserJudgment,
        items: List<SourceItem>,
        rerunAll: Boolean,
    ): SweepPlan {
        val decided = if (rerunAll) emptySet() else effectiveRows(all, judgment, emptyMap()).mapNotNull { it.itemId }.toSet()
        val withText = items.filter { it.hasText }
        val todo = withText.filter { it.id !in decided }
        return SweepPlan(todo, items.size - withText.size, withText.size - todo.size)
    }

    /** How an answer is shown: warn-only negatives are "no signal", never "safe"; scores show their band. */
    fun shownAnswer(judgment: UserJudgment, label: String): String {
        val shape = judgment.shape
        if (judgment.warnOnly && shape is Shape.Binary && label == shape.negative) return "no signal (not an all-clear)"
        if (judgment.warnOnly && shape == Shape.YesNo && label == "no") return "no signal (not an all-clear)"
        if (shape is Shape.Ordinal) {
            val i = shape.candidates.indexOf(label)
            if (i >= 0) return "$label — ${shape.bands[i]}"
        }
        return label
    }

    /** "yes/no", "choice of N", "score 1–5": the shape in a few words. */
    fun shapeName(shape: Shape): String = when (shape) {
        Shape.YesNo, is Shape.Binary -> "yes/no"
        is Shape.Pick -> "choice of ${shape.candidates.size}"
        is Shape.Ordinal -> "score ${shape.range.first}–${shape.range.last}"
    }

    /** The desktop's words for a failure posture. */
    fun postureText(p: FailurePosture): String = when (p) {
        FailurePosture.LOUD -> "Loud — if the model's answer cannot be used, you are told; quiet failure here would look like \"nothing to worry about\"."
        FailurePosture.NULL_ACTION -> "Null action — an unusable answer changes nothing and the item waits for you."
        FailurePosture.OPEN -> "Open — an unusable answer simply finds nothing; nothing depends on it."
    }
}
