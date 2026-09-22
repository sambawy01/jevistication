package dev.loupe.engine

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * An off-policy estimate of how a candidate policy would have performed on logged decisions.
 *
 * [support] is the honest caveat that belongs beside every such number: an importance-weighted
 * estimate only sees the rows where the candidate agrees with what was actually logged, so a low
 * support means the estimate rests on few rows however tight its interval looks.
 */
data class OffPolicyEstimate(
    /** Inverse propensity scoring: unbiased, higher variance. */
    val ips: Double,
    /** Self-normalised IPS: biased, much lower variance. Reported as the headline. */
    val snips: Double,
    /** Fraction of logged rows where the candidate would have taken the logged action. */
    val support: Double,
    /** Count of rows contributing to the estimate. */
    val supportingRows: Int,
    /** Kish effective sample size of the importance weights. */
    val effectiveSampleSize: Double,
)

/** The result of replaying logged decisions against a candidate threshold (A8, and D3's slider). */
data class ThresholdReplay(
    val threshold: Probability,
    /** Mean reward actually observed under the logged policy. */
    val loggedMeanReward: Double,
    /** SNIPS estimate of the candidate threshold's mean reward. */
    val candidateMeanReward: Double,
    /** [candidateMeanReward] minus [loggedMeanReward]. */
    val delta: Double,
    /** Bootstrap percentile interval for [delta]. */
    val deltaLower: Double,
    val deltaUpper: Double,
    val support: Double,
    val supportingRows: Int,
)

/**
 * The counterfactual engine (A8): off-policy estimates over logged propensities.
 *
 * Every estimate here depends on the propensity recorded at decision time, which is why A5 makes
 * it non-negotiable — it cannot be reconstructed afterwards, and a reconstructed one biases
 * everything computed from it.
 */
object OffPolicy {

    /**
     * Estimates the mean reward of a candidate policy from logged rows.
     *
     * @param rows the logged decisions; every propensity must be strictly positive.
     * @param reward observed reward for a logged row, conventionally in [0,1].
     * @param candidateAction the action the candidate policy would take for that row.
     * @throws IllegalArgumentException if [rows] is empty or any propensity is zero.
     */
    fun estimate(
        rows: List<LedgerRow>,
        reward: (LedgerRow) -> Double,
        candidateAction: (LedgerRow) -> String,
    ): OffPolicyEstimate {
        require(rows.isNotEmpty()) { "cannot estimate from an empty ledger" }
        rows.forEach {
            require(it.propensity.value > 0.0) {
                "row for judgment '${it.judgmentId}' logged a zero propensity; " +
                    "an action cannot be taken with probability zero"
            }
        }

        var weightSum = 0.0
        var weightSquareSum = 0.0
        var weightedRewardSum = 0.0
        var supporting = 0

        for (row in rows) {
            if (candidateAction(row) != row.action) continue
            val weight = 1.0 / row.propensity.value
            weightSum += weight
            weightSquareSum += weight * weight
            weightedRewardSum += weight * reward(row)
            supporting++
        }

        val ips = weightedRewardSum / rows.size
        val snips = if (weightSum > 0.0) weightedRewardSum / weightSum else 0.0
        val ess = if (weightSquareSum > 0.0) (weightSum * weightSum) / weightSquareSum else 0.0

        return OffPolicyEstimate(
            ips = ips,
            snips = snips,
            support = supporting.toDouble() / rows.size,
            supportingRows = supporting,
            effectiveSampleSize = ess,
        )
    }

    /**
     * Replays [rows] against a candidate [threshold] and reports the delta against what was
     * actually observed, with a bootstrap percentile interval.
     *
     * The candidate acts on the top-mass label when its logged mass meets [threshold] and
     * otherwise records [Policy.ABSTAIN] — the same rule [Policy.decide] applies live, so the
     * replay answers "what would this slider position have done to the decisions I already made".
     *
     * @param bootstrapSamples resamples drawn for the interval.
     * @param confidence interval mass, e.g. 0.95.
     * @param seed fixed so a given ledger and threshold always report the same interval.
     */
    fun replayThreshold(
        rows: List<LedgerRow>,
        threshold: Probability,
        reward: (LedgerRow) -> Double,
        bootstrapSamples: Int = 1_000,
        confidence: Double = 0.95,
        seed: Long = 0L,
    ): ThresholdReplay {
        require(rows.isNotEmpty()) { "cannot replay an empty ledger" }
        require(bootstrapSamples > 0) { "bootstrapSamples must be positive" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be in (0,1)" }

        val candidateAction: (LedgerRow) -> String = { row ->
            val top = row.distribution.argmax
            if (row.distribution.getValue(top).value >= threshold.value) top else Policy.ABSTAIN
        }

        val point = estimate(rows, reward, candidateAction)
        val loggedMean = rows.sumOf(reward) / rows.size
        val delta = point.snips - loggedMean

        val random = Random(seed)
        val deltas = DoubleArray(bootstrapSamples) {
            val resample = List(rows.size) { rows[random.nextInt(rows.size)] }
            val resampled = estimate(resample, reward, candidateAction)
            val resampledLogged = resample.sumOf(reward) / resample.size
            resampled.snips - resampledLogged
        }
        deltas.sort()

        val alpha = (1.0 - confidence) / 2.0
        return ThresholdReplay(
            threshold = threshold,
            loggedMeanReward = loggedMean,
            candidateMeanReward = point.snips,
            delta = delta,
            deltaLower = percentile(deltas, alpha),
            deltaUpper = percentile(deltas, 1.0 - alpha),
            support = point.support,
            supportingRows = point.supportingRows,
        )
    }

    /** Nearest-rank percentile of a pre-sorted array. */
    private fun percentile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val index = (q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Standard error of the mean, for reporting alongside an IPS estimate. */
    fun standardError(values: DoubleArray): Double {
        if (values.size < 2) return Double.NaN
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance / values.size)
    }
}
