package dev.loupe.kit.flights

import dev.loupe.engine.AuthorResult
import dev.loupe.engine.Backend
import dev.loupe.engine.Decision
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Judgment
import dev.loupe.engine.JudgmentAuthor
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Policy
import dev.loupe.engine.Probability
import dev.loupe.engine.Recalibrator
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.TextState
import dev.loupe.engine.Truncation

/** A stated term of an offer: the helper reports refund and change terms only when Duffel does. */
enum class Term { YES, NO, NOT_STATED }

/** One direction of a trip, already reduced to what a traveller reads. */
data class FlightLeg(
    val from: String,
    val to: String,
    /** Airport-local `HH:mm`, or `--:--` when unknown. */
    val departs: String,
    val arrives: String,
    val stops: Int,
    /** Minutes in the air and on the ground, or a negative number when the helper did not say. */
    val durationMinutes: Int,
    /** Flight numbers joined by `+`, e.g. `TP1350` or `IB3101+IB3166`. */
    val flights: String,
)

/** One offer as the ranker sees it. Built by the app from the helper's wire schema. */
data class FlightFacts(
    val id: String,
    /** Exactly as the helper sent it (a decimal string); never parsed to a floating point. */
    val price: String,
    val currency: String,
    val airline: String,
    val legs: List<FlightLeg>,
    val checkedBags: Int,
    val carryOnBags: Int,
    val refundable: Term,
    val changeable: Term,
)

/**
 * An offer as a compact [TextState] (A3). One line per fact, most important first, so that if the
 * budget ever cuts, it cuts conditions before price and times — and the cut is recorded, never hidden.
 */
object FlightState {
    /** Characters. A typical round trip is ~250; the budget leaves room for a three-leg itinerary. */
    const val BUDGET: Int = 480

    fun lines(offer: FlightFacts): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += "price" to "Price: ${offer.currency} ${offer.price}"
        out += "airline" to "Airline: ${offer.airline}"
        offer.legs.forEachIndexed { i, leg ->
            val name = when {
                offer.legs.size == 2 && i == 0 -> "Outbound"
                offer.legs.size == 2 && i == 1 -> "Return"
                else -> "Leg ${i + 1}"
            }
            val stops = if (leg.stops == 0) "nonstop" else "${leg.stops} stop${if (leg.stops == 1) "" else "s"}"
            val duration = if (leg.durationMinutes >= 0) ", ${leg.durationMinutes / 60}h ${leg.durationMinutes % 60}m" else ""
            out += "leg$i" to "$name ${leg.from}-${leg.to}: departs ${leg.departs}, arrives ${leg.arrives}, $stops$duration (${leg.flights})"
        }
        out += "bags" to "Bags: ${bags(offer.checkedBags, "checked")}, ${bags(offer.carryOnBags, "carry-on")}"
        out += "terms" to "Refundable: ${term(offer.refundable)}; changes: ${term(offer.changeable)}"
        return out
    }

    fun of(offer: FlightFacts, budget: Int = BUDGET): TextState = TextState.build(lines(offer), budget)

    private fun bags(n: Int, kind: String) = if (n == 0) "no $kind bag listed" else "$n $kind"
    private fun term(t: Term) = when (t) {
        Term.YES -> "yes"
        Term.NO -> "no"
        Term.NOT_STATED -> "not stated"
    }
}

/** The outcome of compiling the user's priorities into a judgment. */
sealed interface FlightJudgment {
    data class Ready(val judgment: Judgment.Choice) : FlightJudgment

    /** C2's lint refused the text; [reasons] are its findings, shown to the user. */
    data class Refused(val reasons: List<String>) : FlightJudgment
}

/**
 * The user's plain-language priorities as a Choice judgment, through C2's authoring path
 * ([JudgmentAuthor]) — the same lint every user-written judgment passes.
 *
 * Two options that say what they mean rather than bare yes/no (docs/BUILD.md trap: bare yes/no
 * options make Laya ignore the question).
 */
object FlightPriorities {
    const val FITS: String = "fits"
    const val MISSES: String = "does not fit"
    const val ID: String = "web.flights.fit"

    /** Asked when the user wrote nothing: price order is the rule baseline's default, too. */
    const val DEFAULT_PRIORITIES: String = "cheap and convenient"

    fun question(priorities: String): String {
        val text = priorities.trim().trimEnd('?', '.', ' ').replace('?', ',').ifEmpty { DEFAULT_PRIORITIES }
        return "Does this flight offer fit these priorities: $text?"
    }

    fun compile(priorities: String): FlightJudgment =
        when (val r = JudgmentAuthor.compile(ID, question(priorities), listOf(FITS, MISSES), FailurePosture.NULL_ACTION)) {
            is AuthorResult.Compiled -> FlightJudgment.Ready(
                r.judgment.copy(
                    descriptions = mapOf(
                        FITS to "meets the priorities listed",
                        MISSES to "breaks at least one priority listed",
                    ),
                ),
            )
            is AuthorResult.Rejected -> FlightJudgment.Refused(r.findings.map { it.message })
        }
}

/** One Laya decision on an offer: what the app shows, and the ledger row it writes. */
data class FlightDecision(val verdict: OfferVerdict, val row: LedgerRow)

/** Laya's verdict on one offer. */
data class OfferVerdict(
    val id: String,
    /** Calibrated probability that the offer fits (identity calibration until corrections exist). */
    val fit: Double,
    /** Gap between the two options' masses: the uncertain queue's measure (D1). */
    val margin: Double,
    /** The engine would not act on this answer: below threshold, input cut, or unusable. */
    val unsure: Boolean,
    /** The model or the text budget read less than the whole offer. */
    val truncated: Boolean,
    /** Set when the model's answer could not be used at all. */
    val failure: String?,
)

/**
 * Scores offers against a compiled priorities judgment with any [Backend] (Laya on the phone, a
 * fake in tests), through the engine's own path: validate (A4) → recalibrate → policy (A7) →
 * the cut-input rule (§8). An offer the policy would not act on is marked unsure — the same rule
 * that sends an item to the uncertain queue.
 */
class FlightJudge(
    private val backend: Backend,
    /** The starting threshold for a two-option judgment (`UserJudgment.defaultThreshold`). */
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val recalibrator: Recalibrator = Recalibrator.Identity,
) {
    /** For Swift, which does not see Kotlin default arguments. */
    constructor(backend: Backend) : this(backend, DEFAULT_THRESHOLD, Recalibrator.Identity)

    fun judge(judgment: Judgment.Choice, offer: FlightFacts): OfferVerdict = decide(judgment, offer).verdict

    /**
     * Judges [offer] and returns the ledger row (A5) this decision writes, built exactly as
     * `DecisionEngine` builds one: the full calibrated distribution, the policy's action, a
     * propensity of 1 (no exploration here, so the policy's choice is certain), the cut record, and
     * `resolvedBy` Model — or Unusable, with a flat distribution, when validation failed. The item
     * is `web:duffel:<offer id>`: the source (epic #6) and the offer. Rule-baseline rankings write
     * no rows; they are not model decisions.
     */
    fun decide(judgment: Judgment.Choice, offer: FlightFacts): FlightDecision {
        val state = FlightState.of(offer)
        val itemId = itemId(offer.id)
        val scored = runCatching { backend.score(judgment, state) }
        val raw = scored.mapCatching { judgment.validate(it.masses) }
        val failure = raw.exceptionOrNull()
        if (failure != null) {
            val reason = failure.message ?: "unusable response"
            val s = scored.getOrNull()
            val row = LedgerRow(
                judgmentId = judgment.id,
                criteriaHash = judgment.criteriaHash,
                distribution = judgment.noInformation(),
                action = Policy.UNUSABLE,
                propensity = Probability.of(1.0),
                failure = reason,
                itemId = itemId,
                resolvedBy = ResolvedBy.Unusable,
                truncation = Truncation(state.budgetCut, s?.modelContext, s?.optionCriteria),
            )
            val verdict = OfferVerdict(offer.id, 0.0, 0.0, unsure = true, truncated = state.budgetCut != null, failure = reason)
            return FlightDecision(verdict, row)
        }
        val s = scored.getOrThrow()
        val truncation = Truncation(state.budgetCut, s.modelContext, s.optionCriteria)
        val calibrated = recalibrator.calibrate(raw.getOrThrow())
        val decision = Policy.onCutInput(Policy.decide(calibrated, Probability.of(threshold)), truncation, judgment.onFailure)
        val verdict = OfferVerdict(
            id = offer.id,
            fit = calibrated.getValue(FlightPriorities.FITS).value,
            margin = calibrated.distribution.margin,
            unsure = decision !is Decision.Act,
            truncated = truncation.isCut,
            failure = null,
        )
        val row = LedgerRow(
            judgmentId = judgment.id,
            criteriaHash = judgment.criteriaHash,
            distribution = calibrated.distribution,
            action = Policy.actionOf(decision),
            propensity = Probability.of(1.0),
            itemId = itemId,
            resolvedBy = ResolvedBy.Model,
            truncation = truncation,
        )
        return FlightDecision(verdict, row)
    }

    companion object {
        const val DEFAULT_THRESHOLD: Double = 0.80

        /** The ledger source for flight offers (epic #6): offers come from Duffel via the helper. */
        const val SOURCE: String = "web:duffel"

        /** The ledger item id of an offer: `web:duffel:<offer id>`. */
        fun itemId(offerId: String): String = "$SOURCE:$offerId"

        /**
         * Best first: usable answers by fit, then unusable ones; ties keep the order given (the
         * caller passes offers in the rule ranking, so a tie falls back to the baseline).
         */
        fun order(verdicts: List<OfferVerdict>): List<OfferVerdict> =
            verdicts.withIndex()
                .sortedWith(compareBy<IndexedValue<OfferVerdict>> { it.value.failure != null }
                    .thenByDescending { it.value.fit }
                    .thenBy { it.index })
                .map { it.value }
    }
}
