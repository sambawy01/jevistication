package dev.loupe.agent

import dev.loupe.engine.Decision
import dev.loupe.engine.Probability
import dev.loupe.engine.Truncation

/**
 * What the local engine decided about one item, carried with every prepared action.
 *
 * Nothing in this module produces an action without one of these, and the only way to get one is
 * [AgentGate.consider] on a [Decision.Act]. That is how *"never acts on something it is unsure
 * about"* (docs/PRODUCT.md §4) survives the addition of a remote model: the remote model never
 * decides *whether* an item deserves attention, it only proposes *what to do* about an item the
 * on-device engine already decided, at a confidence the engine measured and can be scored on.
 */
data class AgentEvidence(
    /** The source item the action is about, by the id the ledger and the sources use. */
    val itemId: String,
    /** The judgment that flagged it. */
    val judgmentId: String,
    /** The label the engine acted on — the answer the action is a response to. */
    val label: String,
    /** The calibrated mass that label carried. */
    val confidence: Probability,
    /** The gate's own bar, recorded so a reader can see it was met and by how much. */
    val bar: Probability,
    /**
     * True when this is a fact read off the item rather than a model's answer — a date, an amount,
     * a hash.
     *
     * Kept distinct because it is a stronger claim, not a weaker one, and the summary should say so.
     * docs/PRODUCT.md's mechanical-first principle is that deciding *what a document is* is a
     * judgment and the model does that, while finding the date on it is arithmetic and the model
     * never touches it. A reminder built on the arithmetic half deserves to say which half it is.
     */
    val mechanical: Boolean = false,
) {
    /** One line for the review item's summary: what decided this, and how sure it was. */
    val line: String
        get() = if (mechanical) {
            "$judgmentId found \"$label\" in the item itself, not from a model answer"
        } else {
            "$judgmentId answered \"$label\" at ${pct(confidence)} (the agent's bar is ${pct(bar)})"
        }

    private fun pct(p: Probability): String {
        val whole = (p.value * 100.0 + 0.5).toInt()
        return "$whole%"
    }

    companion object {
        /**
         * Evidence for a fact read off the item — a date, an amount — rather than a model answer.
         *
         * Certain by construction, because arithmetic on the item's own text either found the thing
         * or did not. Anything genuinely uncertain about it (a date that could be read two ways) is
         * the caller's to refuse, and [LocalPlanner.fromExpiry] does.
         */
        fun mechanical(itemId: String, source: String, fact: String): AgentEvidence = AgentEvidence(
            itemId = itemId,
            judgmentId = source,
            label = fact,
            confidence = Probability.of(1.0),
            bar = AgentGate.FLOOR,
            mechanical = true,
        )
    }
}

/** Whether one item may reach the agent at all. */
sealed interface GateVerdict {
    data class Eligible(val evidence: AgentEvidence) : GateVerdict

    /**
     * The item stops here. [reason] is written for a person, because it is shown in the agent's
     * own log: a tier the user can see skipping things is a tier they can trust.
     */
    data class Skipped(val reason: String) : GateVerdict
}

/**
 * The gate: the cheap local filter in front of every paid inference call.
 *
 * This is the agent tier's whole economic and privacy argument in one object. A naive phone agent
 * sends every item to a provider because it has nothing that can triage locally. Loupe judges the
 * whole inbox on the device first, so only the handful the engine actually acted on ever becomes a
 * request. Three consequences, all of them the point:
 *
 * - **Cost.** The provider bill is proportional to flagged items, not to mail volume.
 * - **Privacy.** Everything the gate skips never leaves the device, and the skip is recorded with
 *   its reason, so "what went out" is answerable per item rather than in the abstract.
 * - **Calibration.** The gate reads a [Decision] the engine produced and can be measured on
 *   (docs/PRODUCT.md §7). The remote model is never asked to judge, so nothing downstream inherits
 *   an uncalibrated confidence.
 */
object AgentGate {
    /**
     * The agent's own confidence bar, above the engine's acting threshold on purpose.
     *
     * The engine acts to *show* you something; the agent acts to *prepare work* on it, which costs
     * money and, for a hosted provider, sends the item's text off the device. Both are worth a
     * higher bar than a card on a screen. A judgment may raise this, never lower it below
     * [FLOOR].
     */
    val DEFAULT_BAR: Probability = Probability.of(0.80)

    /** No caller may take the bar below this. */
    val FLOOR: Probability = Probability.of(0.60)

    /**
     * Whether [decision] about [itemId] may reach the agent.
     *
     * Everything but [Decision.Act] is skipped, and an [Decision.Act] whose input the model read
     * only part of is skipped too: an answer about part of an item is not an answer about the item
     * (docs/PRODUCT.md §8), and preparing an action from one would launder a partial read into
     * something that looks whole.
     */
    fun consider(
        itemId: String,
        judgmentId: String,
        decision: Decision,
        truncation: Truncation? = null,
        bar: Probability = DEFAULT_BAR,
    ): GateVerdict {
        val effective = if (bar.value < FLOOR.value) FLOOR else bar
        return when (decision) {
            is Decision.Abstain ->
                GateVerdict.Skipped("the engine was unsure (\"${decision.topLabel}\" at ${decision.topMass.value})")

            is Decision.Unusable ->
                GateVerdict.Skipped("the engine could not use the model's answer: ${decision.reason}")

            is Decision.Act -> when {
                truncation?.isCut == true ->
                    GateVerdict.Skipped("the model read only part of the item, so the answer is not about the whole item")

                decision.propensity.value < effective.value ->
                    GateVerdict.Skipped(
                        "\"${decision.label}\" at ${decision.propensity.value} is below the agent's bar " +
                            "of ${effective.value}",
                    )

                else -> GateVerdict.Eligible(
                    AgentEvidence(
                        itemId = itemId,
                        judgmentId = judgmentId,
                        label = decision.label,
                        confidence = decision.propensity,
                        bar = effective,
                    ),
                )
            }
        }
    }

    /**
     * The gate over a batch, with the count of what it let through.
     *
     * The shape a caller wants: the eligible items to spend calls on, and the skips to show in the
     * agent's log. Nothing here makes a request.
     */
    fun sift(items: List<GateInput>, bar: Probability = DEFAULT_BAR): GateResult {
        val eligible = mutableListOf<AgentEvidence>()
        val skipped = mutableListOf<Pair<String, String>>()
        for (i in items) {
            when (val v = consider(i.itemId, i.judgmentId, i.decision, i.truncation, bar)) {
                is GateVerdict.Eligible -> eligible += v.evidence
                is GateVerdict.Skipped -> skipped += i.itemId to v.reason
            }
        }
        return GateResult(eligible = eligible, skipped = skipped, considered = items.size)
    }
}

/** One decision offered to the gate. */
data class GateInput(
    val itemId: String,
    val judgmentId: String,
    val decision: Decision,
    val truncation: Truncation? = null,
)

/** What the gate let through, what it stopped and why, and how much it looked at. */
data class GateResult(
    val eligible: List<AgentEvidence>,
    /** Item id to the reason it stopped here. */
    val skipped: List<Pair<String, String>>,
    val considered: Int,
) {
    /**
     * The line the tier shows to make its own cost and privacy claim checkable:
     * "3 of 412 items reached your provider; 409 never left this device."
     */
    val summary: String
        get() = "${eligible.size} of $considered items reached your provider; " +
            "${skipped.size} never left this device"
}
