package dev.loupe.engine

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
)

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
