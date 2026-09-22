package dev.loupe.engine

/**
 * What the app reports for one judgment (§7, D2).
 *
 * There is deliberately no aggregate across judgments. "Is this a receipt" and "is this urgent"
 * are different problems with different numbers, and a single system accuracy would hide both.
 */
data class JudgmentReport(
    val judgmentId: String,
    val n: Int,
    /** Share of items acted on rather than queued. */
    val coverage: Double,
    /** Accuracy among the items it acted on — the number the app leads with. */
    val selectiveAccuracy: Double,
    /** Accuracy if it had been forced to answer everything; the apples-to-apples baseline compare. */
    val accuracyAtFullCoverage: Double,
    /** Share sent to the uncertain queue instead of acted on. */
    val abstentionRate: Double,
    val ece: Double,
    val brier: Double,
    val reliability: List<ReliabilityBin>,
    /**
     * Items whose model response could not be used at all.
     *
     * Reported rather than folded away: §7 counts an error as wrong, and a judgment failing on a
     * tenth of its inputs is a different problem from one answering them badly.
     */
    val unusable: Int,
    /** The dumb baseline's accuracy, at full coverage. */
    val baselineAccuracy: Double,
    /** False when the model does not beat its dumb baseline — and the app says so. */
    val beatsBaseline: Boolean,
) {
    /** A one-line summary in the form the app shows it. */
    fun summary(): String = buildString {
        append("$judgmentId: ")
        append("selective %.1f%% at %.0f%% coverage".format(selectiveAccuracy * 100, coverage * 100))
        append(", ECE %.3f".format(ece))
        append(", baseline %.1f%%".format(baselineAccuracy * 100))
        append(if (beatsBaseline) " (beats baseline)" else " (DOES NOT beat baseline)")
    }
}

/**
 * The measurement harness (§7). It runs alongside everything and gates it; it is not a track that
 * finishes.
 *
 * Protocol, fixed: errors count as wrong, there are no retries, and wording is held identical
 * across compared arms — prompt wording moves results as much as the algorithm does, so it is a
 * controlled variable, which is why the criteria hash is pinned into every ledger row.
 */
object Harness {

    /**
     * Runs [judgment] over [fixtures] through [engine] and reports the numbers §7 requires.
     *
     * @param baseline the dumb version of this judgment — sort by date, allowlist the sender,
     *   match a keyword. Every judgment carries one, and the report says which wins.
     */
    fun evaluate(
        judgment: Judgment.Choice,
        fixtures: List<Fixture>,
        engine: DecisionEngine,
        baseline: (Item) -> String,
        bins: Int = Calibration.DEFAULT_BINS,
    ): JudgmentReport {
        require(fixtures.isNotEmpty()) { "cannot evaluate '${judgment.id}' on no fixtures" }

        val predictions = mutableListOf<Pair<Distribution, String>>()
        var acted = 0
        var actedCorrect = 0
        var correctAtFullCoverage = 0
        var baselineCorrect = 0
        var unusable = 0

        for (fixture in fixtures) {
            val outcome = engine.decide(judgment, fixture.item)
            if (baseline(fixture.item) == fixture.trueLabel) baselineCorrect++

            when (val decision = outcome.decision) {
                is Decision.Act -> {
                    acted++
                    if (decision.label == fixture.trueLabel) actedCorrect++
                }
                is Decision.Abstain -> Unit
                is Decision.Unusable -> {
                    // Errors count as wrong and are never retried. The recorded distribution is a
                    // flat placeholder, not an opinion, so it is kept out of the calibration
                    // metrics rather than being scored as if the model had expressed a view.
                    unusable++
                    continue
                }
            }

            val distribution = outcome.row.distribution
            predictions += distribution to fixture.trueLabel
            if (distribution.argmax == fixture.trueLabel) correctAtFullCoverage++
        }

        val n = fixtures.size
        val accuracyAtFullCoverage = correctAtFullCoverage.toDouble() / n
        val baselineAccuracy = baselineCorrect.toDouble() / n

        return JudgmentReport(
            judgmentId = judgment.id,
            n = n,
            coverage = acted.toDouble() / n,
            selectiveAccuracy = if (acted == 0) 0.0 else actedCorrect.toDouble() / acted,
            accuracyAtFullCoverage = accuracyAtFullCoverage,
            abstentionRate = (n - acted).toDouble() / n,
            ece = Calibration.ece(predictions, bins),
            brier = Calibration.brier(predictions),
            reliability = Calibration.reliability(predictions, bins),
            unusable = unusable,
            baselineAccuracy = baselineAccuracy,
            // Compared at equal coverage: forcing the model to answer everything, as the
            // baseline does, is the only honest comparison.
            beatsBaseline = accuracyAtFullCoverage > baselineAccuracy,
        )
    }
}
