package dev.loupe.engine

import kotlin.math.abs

/** A labelled calibration example: what the model said, and what the user says is true. */
data class CalibrationExample(val raw: Distribution, val trueLabel: String)

/**
 * Calibration metrics. §7 of the build plan reports ECE alongside selective accuracy, and A6's
 * acceptance criterion is stated in terms of ECE on a held-out calibration split.
 */
object Calibration {
    const val DEFAULT_BINS: Int = 10

    /**
     * Expected calibration error over [bins] equal-width confidence bins, measured on the
     * top-mass label: the size-weighted mean gap between a bin's mean confidence and its
     * accuracy. 0 is perfectly calibrated.
     */
    fun ece(
        predictions: List<Pair<CalibratedDistribution, String>>,
        bins: Int = DEFAULT_BINS,
    ): Double {
        require(bins > 0) { "bins must be positive, was $bins" }
        if (predictions.isEmpty()) return 0.0

        val buckets = Array(bins) { mutableListOf<Pair<Double, Boolean>>() }
        for ((dist, trueLabel) in predictions) {
            val top = dist.argmax
            val confidence = dist.getValue(top).value
            val index = (confidence * bins).toInt().coerceAtMost(bins - 1)
            buckets[index].add(confidence to (top == trueLabel))
        }

        var ece = 0.0
        for (bucket in buckets) {
            if (bucket.isEmpty()) continue
            val meanConfidence = bucket.sumOf { it.first } / bucket.size
            val accuracy = bucket.count { it.second }.toDouble() / bucket.size
            ece += (bucket.size.toDouble() / predictions.size) * abs(meanConfidence - accuracy)
        }
        return ece
    }
}
