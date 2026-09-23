package dev.loupe.engine

/**
 * The outcome of running the policy over a calibrated distribution (A7).
 */
sealed interface Decision {
    /** Take [label] as the answer; [propensity] is the calibrated mass it was selected with. */
    data class Act(val label: String, val propensity: Probability) : Decision

    /**
     * Decline to act — the item goes to the uncertain queue (D1).
     *
     * [inputCut] is set when the policy would have acted but the model read only part of the item
     * and the judgment's posture does not allow acting on part of it (see [Policy.onCutInput]).
     */
    data class Abstain(
        val topLabel: String,
        val topMass: Probability,
        val inputCut: Truncation? = null,
    ) : Decision

    /**
     * The model's answer could not be used at all.
     *
     * The engine never acts on one of these. [posture] is the judgment's declared stance on who
     * hears about it, and it is carried here rather than decided by the caller so that a judgment
     * cannot be made quietly permissive by the code that happens to call it.
     */
    data class Unusable(val reason: String, val posture: FailurePosture) : Decision
}

/**
 * The policy runner (A7): pure and total. It reads only a [CalibratedDistribution] — the type
 * system forbids passing a raw [Distribution] — and never performs I/O, so the same input always
 * yields the same decision.
 */
object Policy {
    /**
     * The action recorded when the policy declines to act. Reserved: it is not a candidate label,
     * so it cannot collide with one, and the A8 replay compares against it directly.
     */
    const val ABSTAIN: String = "__abstain__"

    /** The action recorded when the model's answer could not be used. */
    const val UNUSABLE: String = "__unusable__"

    /** The action string a [Decision] records in a [LedgerRow]. */
    fun actionOf(decision: Decision): String = when (decision) {
        is Decision.Act -> decision.label
        is Decision.Abstain -> ABSTAIN
        is Decision.Unusable -> UNUSABLE
    }

    /**
     * Acts on the highest-mass label when its calibrated mass is at least [threshold]; otherwise
     * abstains, and the item queues. This is "never acts on something it is unsure about" (§4 of
     * the spec) expressed as a total function: every calibrated distribution yields a decision.
     */
    /**
     * The rule for a decision whose input was cut (§8: the judgment is told which). An answer the
     * model gave about part of an item is not an answer about the item, so a judgment only acts on
     * one when it declared that quiet misses are acceptable:
     *
     * - [FailurePosture.OPEN] — the decision stands; the cut is recorded and shown, nothing more.
     * - [FailurePosture.NULL_ACTION] and [FailurePosture.LOUD] — an [Decision.Act] becomes an
     *   [Decision.Abstain] carrying the cut, so the item queues for the user rather than being
     *   acted on. An abstention stays one.
     *
     * An uncut input ([Truncation.isCut] false, or unknown) passes through untouched.
     */
    fun onCutInput(decision: Decision, truncation: Truncation?, posture: FailurePosture): Decision {
        if (truncation?.isCut != true || posture == FailurePosture.OPEN) return decision
        return when (decision) {
            is Decision.Act -> Decision.Abstain(decision.label, decision.propensity, inputCut = truncation)
            is Decision.Abstain, is Decision.Unusable -> decision
        }
    }

    fun decide(calibrated: CalibratedDistribution, threshold: Probability): Decision {
        val top = calibrated.argmax
        val mass = calibrated.getValue(top)
        return if (mass.value >= threshold.value) {
            Decision.Act(top, mass)
        } else {
            Decision.Abstain(top, mass)
        }
    }
}
