package dev.loupe.engine

import kotlin.random.Random

/** One thing the engine can judge: an identifier and the source text extracted from it. */
data class Item(val id: String, val text: String)

/** What one pass of the engine produced. */
data class DecisionOutcome(
    val decision: Decision,
    /** The row appended to the ledger for this decision. */
    val row: LedgerRow,
    /** True when a mechanical check answered and the model was never consulted. */
    val resolvedMechanically: Boolean,
) {
    /** Whether the model's input was cut, as recorded on [row]; null when no model read it. */
    val truncation: Truncation? get() = row.truncation
}

/**
 * The engine that composes the core into one decision (proving milestone 2, "It decides").
 *
 * The path is exactly the architecture in §8 of the spec:
 * source text → text state → mechanical checks → model → recalibrator → policy → ledger.
 *
 * Two properties are structural rather than conventional. Mechanical checks run **first**, so an
 * item they can answer never reaches the model and cannot be got wrong by it. And every decision —
 * mechanical, acted or abstained — writes a ledger row carrying its propensity, because A8 cannot
 * reconstruct one afterwards.
 */
class DecisionEngine(
    private val backend: Backend,
    private val threshold: Probability,
    private val recalibrator: Recalibrator = Recalibrator.Identity,
    val ledger: Ledger = Ledger(),
    val stats: MechanicalStats = MechanicalStats(),
    private val stateBudget: Int = DEFAULT_STATE_BUDGET,
    /**
     * Probability of sampling an action from the distribution instead of following the policy.
     *
     * Zero means a fully deterministic engine, which is safe but logs a propensity of 1 for every
     * row — and a ledger with no propensity variation can only ever evaluate candidate policies
     * that agree with what was already done. A small exploration rate is what makes A8's
     * counterfactuals answer anything interesting.
     */
    private val exploration: Double = 0.0,
    private val random: Random = Random(0),
) {
    init {
        require(exploration in 0.0..1.0) { "exploration must be in [0,1], was $exploration" }
    }

    /**
     * Decides [judgment] for [item].
     *
     * @param mechanical a check that may answer without the model; it must return one of
     *   [judgment]'s candidate labels when it resolves.
     */
    fun decide(
        judgment: Judgment.Choice,
        item: Item,
        mechanical: (Item) -> Mechanical<String> = { Mechanical.Deferred },
    ): DecisionOutcome {
        val state = TextState.build(listOf(item.id to item.text), stateBudget)

        when (val outcome = stats.record(mechanical(item))) {
            is Mechanical.Resolved -> {
                require(outcome.value in judgment.candidates) {
                    "mechanical check '${outcome.by}' returned '${outcome.value}', " +
                        "which is not a candidate of judgment '${judgment.id}'"
                }
                // A mechanical answer is certain: all mass on the label, taken with probability 1.
                val certain = Distribution.of(
                    judgment.candidates.associateWith { if (it == outcome.value) 1.0 else 0.0 },
                )
                val decision = Decision.Act(outcome.value, Probability.of(1.0))
                return record(
                    judgment, item, certain, decision, Probability.of(1.0),
                    resolvedBy = ResolvedBy.Mechanical(outcome.by),
                )
            }

            is Mechanical.Deferred -> Unit
        }

        // A4: whatever the backend returns is validated before anything else may read it, and
        // a response that fails validation must never escape as an exception. A backend is an
        // untrusted component -- a bad export, a truncated model file, a future implementation
        // with a bug -- and one malformed answer must not take down a sweep over a whole library.
        var modelContext: Extent? = null
        val raw = runCatching {
            val scored = backend.score(judgment, state)
            modelContext = scored.modelContext
            judgment.validate(scored.masses)
        }
            .getOrElse { failure ->
                return record(
                    judgment = judgment,
                    item = item,
                    distribution = judgment.noInformation(),
                    decision = Decision.Unusable(
                        reason = failure.message ?: failure::class.simpleName ?: "unusable response",
                        posture = judgment.onFailure,
                    ),
                    propensity = Probability.of(1.0),
                    resolvedBy = ResolvedBy.Unusable,
                    failure = failure.message ?: "unusable response",
                    truncation = Truncation(state.budgetCut, modelContext),
                )
            }
        val truncation = Truncation(state.budgetCut, modelContext)
        val calibrated = recalibrator.calibrate(raw)
        val greedy = Policy.decide(calibrated, threshold)
        val guarded = Policy.onCutInput(greedy, truncation, judgment.onFailure)
        if (guarded !== greedy) {
            // The input was cut and the judgment may not act on part of an item: it queues,
            // deterministically. Exploration is not allowed to act on it either.
            return record(
                judgment, item, calibrated.distribution, guarded, Probability.of(1.0),
                resolvedBy = ResolvedBy.Model, truncation = truncation,
            )
        }
        val decision = explore(calibrated, greedy)
        val action = Policy.actionOf(decision)

        return record(
            judgment = judgment,
            item = item,
            distribution = calibrated.distribution,
            decision = decision,
            propensity = propensityOf(action, calibrated, Policy.actionOf(greedy)),
            resolvedBy = ResolvedBy.Model,
            truncation = truncation,
        )
    }

    /** Decides a yes/no judgment. */
    fun decide(
        judgment: Judgment.Bool,
        item: Item,
        mechanical: (Item) -> Mechanical<String> = { Mechanical.Deferred },
    ): DecisionOutcome = decide(judgment.asChoice, item, mechanical)

    /** Decides a scored judgment. */
    fun decide(
        judgment: Judgment.Score,
        item: Item,
        mechanical: (Item) -> Mechanical<String> = { Mechanical.Deferred },
    ): DecisionOutcome = decide(judgment.asChoice, item, mechanical)

    /** With probability [exploration], take a sampled label instead of the policy's choice. */
    private fun explore(calibrated: CalibratedDistribution, greedy: Decision): Decision {
        if (exploration <= 0.0 || random.nextDouble() >= exploration) return greedy
        val sampled = sample(calibrated)
        return Decision.Act(sampled, calibrated.getValue(sampled))
    }

    /** Draws a label in proportion to its calibrated mass. */
    private fun sample(calibrated: CalibratedDistribution): String {
        var target = random.nextDouble()
        var last = calibrated.argmax
        for (label in calibrated.distribution.labels) {
            target -= calibrated.getValue(label).value
            last = label
            if (target <= 0.0) return label
        }
        return last
    }

    /**
     * The probability this engine takes [action]: the policy's own choice with weight
     * `1 - exploration`, plus the chance exploration sampled it.
     *
     * Abstention can only come from the policy, never from sampling, since the abstain marker is
     * not a candidate label.
     */
    private fun propensityOf(
        action: String,
        calibrated: CalibratedDistribution,
        greedyAction: String,
    ): Probability {
        val fromPolicy = if (action == greedyAction) 1.0 - exploration else 0.0
        val fromSampling =
            if (action == Policy.ABSTAIN) 0.0 else exploration * (calibrated[action]?.value ?: 0.0)
        return Probability.of((fromPolicy + fromSampling).coerceIn(0.0, 1.0))
    }

    private fun record(
        judgment: Judgment.Choice,
        item: Item,
        distribution: Distribution,
        decision: Decision,
        propensity: Probability,
        resolvedBy: ResolvedBy,
        failure: String? = null,
        truncation: Truncation? = null,
    ): DecisionOutcome {
        val row = LedgerRow(
            judgmentId = judgment.id,
            criteriaHash = judgment.criteriaHash,
            distribution = distribution,
            action = Policy.actionOf(decision),
            propensity = propensity,
            failure = failure,
            itemId = item.id,
            resolvedBy = resolvedBy,
            truncation = truncation,
        )
        ledger.append(row)
        return DecisionOutcome(decision, row, resolvedBy is ResolvedBy.Mechanical)
    }

    private operator fun CalibratedDistribution.get(label: String): Probability? =
        distribution[label]

    companion object {
        /** Characters of state handed to the model for a single item. */
        const val DEFAULT_STATE_BUDGET: Int = 4_000
    }
}
