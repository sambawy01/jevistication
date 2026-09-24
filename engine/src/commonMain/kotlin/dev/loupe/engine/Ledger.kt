package dev.loupe.engine

/**
 * What produced a ledger row's answer (A5).
 *
 * Before this field existed a mechanically answered row was told apart from a model row only by
 * its shape (all mass on one label, propensity 1) — which a sure model answer can also have. The
 * harness, calibration and counterfactuals must not count a hash match as a model prediction, so
 * the distinction is recorded, not inferred.
 *
 * A user's correction is not a resolver: it is a label *about* a row, kept in
 * [LedgerRow.correction] (and, on desktop, in its own append-only file), and never changes what
 * answered it.
 *
 * Serialised by [code]: `model`, `unusable`, or `mechanical:<check>`.
 */
sealed interface ResolvedBy {
    /** The stable string written to the ledger and exports. */
    val code: String

    /** The model was consulted and its answer validated. Calibration and A8 count these rows. */
    data object Model : ResolvedBy {
        override val code: String = "model"
    }

    /**
     * A mechanical check answered and the model was never consulted (A3). [check] names it, as in
     * [Mechanical.Resolved.by] — e.g. `exact-duplicate`.
     */
    data class Mechanical(val check: String) : ResolvedBy {
        init {
            require(check.isNotBlank()) { "a mechanical resolver must name its check" }
        }

        override val code: String get() = "$MECHANICAL_PREFIX$check"
    }

    /**
     * The model was consulted but its answer failed validation (A4). The row's distribution is a
     * flat placeholder, not an opinion; [LedgerRow.failure] says why.
     */
    data object Unusable : ResolvedBy {
        override val code: String = "unusable"
    }

    companion object {
        private const val MECHANICAL_PREFIX = "mechanical:"

        /**
         * The resolver for a row written before this field existed: [Unusable] when it carries a
         * failure, otherwise [Model]. A legacy mechanical row cannot be told from a sure model row
         * by its contents alone, so it reads as [Model] — the conservative choice, since it only
         * ever *adds* a certain, correct-by-construction row to the model's side.
         */
        fun legacy(failure: String?): ResolvedBy = if (failure != null) Unusable else Model

        /** Parses [code]; a missing code falls back to [legacy]. */
        fun parse(code: String?, failure: String?): ResolvedBy = when {
            code == null -> legacy(failure)
            code == Model.code -> Model
            code == Unusable.code -> Unusable
            code.startsWith(MECHANICAL_PREFIX) -> Mechanical(code.removePrefix(MECHANICAL_PREFIX))
            else -> throw IllegalArgumentException("unknown resolvedBy '$code'")
        }
    }
}

/**
 * One append-only ledger row (A5): the full record of a single decision.
 *
 * The [distribution] and [propensity] are recorded in full because A8's counterfactuals cannot
 * be reconstructed from the selected answer alone — propensity in particular must be logged from
 * the first decision or every later counterfactual is biased. [criteriaHash] pins the judgment
 * wording the decision was made under.
 */
data class LedgerRow(
    /** The judgment this decision answered. */
    val judgmentId: String,
    /** Fingerprint of the judgment wording at decision time; see [Judgment.criteriaHash]. */
    val criteriaHash: String,
    /** The full calibrated distribution the decision was drawn from. */
    val distribution: Distribution,
    /** The action taken (typically the selected label, or an abstention marker). */
    val action: String,
    /** The probability with which [action] was selected — required for A8's off-policy estimates. */
    val propensity: Probability,
    /** The user's correction, once known; null until they correct it (or never). */
    val correction: String? = null,
    /**
     * Why the model's answer was unusable, when it was. Null on a normal decision.
     *
     * Kept so a run of malformed responses is visible in the history rather than looking like a
     * run of genuinely uncertain ones.
     */
    val failure: String? = null,
    /**
     * The item this decision was about, when the caller knows it. Null for rows logged before
     * items carried identities, and in tests that decide over anonymous text.
     *
     * Without it a ledger can answer "how sure was the engine" but not "about what", so the
     * uncertain queue could not show the user the thing it wants corrected and a correction could
     * not be tied back to the file or message it describes.
     */
    val itemId: String? = null,
    /**
     * What answered this row. Defaults to [ResolvedBy.legacy], so rows built or loaded without it
     * read as model rows (or unusable ones when they carry a [failure]).
     */
    val resolvedBy: ResolvedBy = ResolvedBy.legacy(failure),
    /**
     * Whether the model's input was cut (§8, A3), and where: by the text-state character budget,
     * by the model's context, or both. [Truncation.NONE] when it read the item whole.
     *
     * Null means **unknown**: rows logged before this field existed, and rows where no model read
     * anything (mechanical answers). Unknown reads as not truncated in [truncated]; a reader that
     * must tell "known whole" from "never recorded" checks for null.
     */
    val truncation: Truncation? = null,
    /**
     * The model's own distribution, kept **alongside** a mechanical answer that replaced it: a
     * judgment switched to its baseline automatically (`resolvedBy` = `mechanical:auto-baseline`)
     * still asks the model, logs the baseline's answer as the decision and keeps what the model said
     * here, so the user's later corrections keep measuring both. Null on every other row.
     */
    val modelDistribution: Distribution? = null,
) {
    init {
        require((resolvedBy is ResolvedBy.Unusable) == (failure != null)) {
            "resolvedBy=${resolvedBy.code} is inconsistent with failure=${failure != null}"
        }
    }

    /** True when the model's answer is what this row records — the rows model metrics may count. */
    val isModelPrediction: Boolean get() = resolvedBy is ResolvedBy.Model

    /** True when a mechanical check answered and the model was never consulted. */
    val isMechanical: Boolean get() = resolvedBy is ResolvedBy.Mechanical

    /** True when the model is known to have read less than the item's whole text. */
    val truncated: Boolean get() = truncation?.isCut == true
}

/**
 * An append-only log of decisions (A5). Rows can be added and read; there is no API to mutate or
 * remove one, because a deterministic, rewritable log makes every later counterfactual biased.
 */
class Ledger {
    private val rows = mutableListOf<LedgerRow>()

    /** Appends [row] to the end of the log. */
    fun append(row: LedgerRow) {
        rows.add(row)
    }

    /** A snapshot of all rows, in append order. */
    fun rows(): List<LedgerRow> = rows.toList()

    /** The number of rows logged so far. */
    val size: Int get() = rows.size
}
