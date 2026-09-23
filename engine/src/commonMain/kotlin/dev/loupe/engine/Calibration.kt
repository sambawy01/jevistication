package dev.loupe.engine

import kotlin.jvm.JvmName

import kotlin.math.abs

/** A labelled calibration example: what the model said, and what the user says is true. */
data class CalibrationExample(val raw: Distribution, val trueLabel: String)

/** One equal-width confidence bin of a reliability diagram. */
data class ReliabilityBin(
    val lower: Double,
    val upper: Double,
    val count: Int,
    val meanConfidence: Double,
    val accuracy: Double,
) {
    /** How far this bin's confidence sits from its accuracy. */
    val gap: Double get() = abs(meanConfidence - accuracy)
}

/**
 * Calibration metrics. §7 reports these per judgment alongside selective accuracy, and A6's
 * acceptance criterion is stated in terms of ECE on a held-out calibration split.
 */
object Calibration {
    const val DEFAULT_BINS: Int = 10

    /**
     * Reliability bins over the top-mass label: within each confidence band, how confident the
     * model was on average and how often it was actually right.
     */
    fun reliability(
        predictions: List<Pair<Distribution, String>>,
        bins: Int = DEFAULT_BINS,
    ): List<ReliabilityBin> {
        require(bins > 0) { "bins must be positive, was $bins" }
        val buckets = Array(bins) { mutableListOf<Pair<Double, Boolean>>() }
        for ((dist, trueLabel) in predictions) {
            val top = dist.argmax
            val confidence = dist.getValue(top).value
            val index = (confidence * bins).toInt().coerceAtMost(bins - 1)
            buckets[index].add(confidence to (top == trueLabel))
        }
        return buckets.mapIndexed { index, bucket ->
            ReliabilityBin(
                lower = index.toDouble() / bins,
                upper = (index + 1).toDouble() / bins,
                count = bucket.size,
                meanConfidence = if (bucket.isEmpty()) 0.0 else bucket.sumOf { it.first } / bucket.size,
                accuracy = if (bucket.isEmpty()) 0.0 else bucket.count { it.second }.toDouble() / bucket.size,
            )
        }
    }

    /**
     * Expected calibration error: the size-weighted mean gap between a bin's confidence and its
     * accuracy. 0 is perfectly calibrated.
     */
    @JvmName("eceOfDistributions")
    fun ece(predictions: List<Pair<Distribution, String>>, bins: Int = DEFAULT_BINS): Double {
        require(bins > 0) { "bins must be positive, was $bins" }
        if (predictions.isEmpty()) return 0.0
        return reliability(predictions, bins)
            .filter { it.count > 0 }
            .sumOf { (it.count.toDouble() / predictions.size) * it.gap }
    }

    /** ECE over already-calibrated distributions. */
    fun ece(
        predictions: List<Pair<CalibratedDistribution, String>>,
        bins: Int = DEFAULT_BINS,
    ): Double = ece(predictions.map { it.first.distribution to it.second }, bins)

    /**
     * Multiclass Brier score: the mean squared error of the whole distribution against the truth,
     * not just of its top label. Lower is better; 0 is a perfect confident prediction.
     *
     * It is reported beside ECE because a model can be well calibrated and still uninformative —
     * always saying 0.5 on a coin flip is perfectly calibrated and tells you nothing.
     */
    fun brier(predictions: List<Pair<Distribution, String>>): Double {
        if (predictions.isEmpty()) return 0.0
        var total = 0.0
        for ((dist, trueLabel) in predictions) {
            for (label in dist.labels) {
                val actual = if (label == trueLabel) 1.0 else 0.0
                val error = dist.getValue(label).value - actual
                total += error * error
            }
        }
        return total / predictions.size
    }
}
