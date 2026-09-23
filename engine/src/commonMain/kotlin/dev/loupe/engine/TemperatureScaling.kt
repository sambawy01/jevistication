package dev.loupe.engine

import kotlin.math.ln
import kotlin.math.pow

/**
 * Temperature scaling (A6): one parameter, fitted to soften or sharpen the model's masses.
 *
 * Masses are raised to the power `1/T` and renormalised — the probability-space form of dividing
 * logits by `T`, used because the backend hands us a distribution rather than raw logits. `T > 1`
 * softens an overconfident model, `T < 1` sharpens an underconfident one, and `T == 1` is a
 * pass-through. Scaling is monotone, so it never changes which label wins; it changes only how
 * sure the engine claims to be, which is exactly what the policy's threshold reads.
 *
 * A6 fits this per judgment × source × option-count, on a calibration split and never on test.
 */
class TemperatureScaling private constructor(val temperature: Double) : Recalibrator {

    init {
        require(temperature.isFinite() && temperature > 0.0) {
            "temperature must be finite and positive, was $temperature"
        }
    }

    override fun calibrate(raw: Distribution): CalibratedDistribution {
        val exponent = 1.0 / temperature
        val scaled = raw.labels.associateWith { raw.getValue(it).value.pow(exponent) }
        val total = scaled.values.sum()
        require(total > 0.0 && total.isFinite()) {
            "temperature $temperature collapsed the distribution to zero mass"
        }
        return CalibratedDistribution(Distribution.of(scaled.mapValues { it.value / total }))
    }

    override fun toString(): String = "TemperatureScaling(T=$temperature)"

    companion object {
        /** Smallest and largest temperature the fit will consider. */
        const val MIN_TEMPERATURE: Double = 0.05
        const val MAX_TEMPERATURE: Double = 20.0

        /** Probability floor used when taking a log, so a zero mass cannot produce -infinity. */
        private const val EPSILON: Double = 1e-12

        /** A recalibrator at a known temperature. */
        fun of(temperature: Double): TemperatureScaling = TemperatureScaling(temperature)

        /**
         * Fits the temperature minimising the negative log likelihood of the true labels on
         * [examples], by coarse-to-fine search over [MIN_TEMPERATURE]..[MAX_TEMPERATURE].
         *
         * The search is deterministic, so a given calibration split always yields the same fit.
         *
         * @throws IllegalArgumentException if [examples] is empty, or an example's true label is
         *   not a candidate of its own distribution.
         */
        fun fit(examples: List<CalibrationExample>): TemperatureScaling {
            require(examples.isNotEmpty()) { "cannot fit a temperature on no examples" }
            for (example in examples) {
                require(example.trueLabel in example.raw.labels) {
                    "true label '${example.trueLabel}' is not a candidate of its distribution"
                }
            }

            var low = MIN_TEMPERATURE
            var high = MAX_TEMPERATURE
            var best = 1.0
            // Four refinement rounds over 24 points each: ~1e-4 resolution, no gradients needed.
            repeat(4) {
                val steps = 24
                var bestLoss = Double.MAX_VALUE
                for (i in 0..steps) {
                    val t = low + (high - low) * i / steps
                    if (t <= 0.0) continue
                    val loss = negativeLogLikelihood(examples, t)
                    if (loss < bestLoss) {
                        bestLoss = loss
                        best = t
                    }
                }
                val window = (high - low) / steps
                low = (best - window).coerceAtLeast(MIN_TEMPERATURE)
                high = (best + window).coerceAtMost(MAX_TEMPERATURE)
            }
            return TemperatureScaling(best)
        }

        /** Mean negative log likelihood of the true labels at temperature [t]. */
        private fun negativeLogLikelihood(examples: List<CalibrationExample>, t: Double): Double {
            val exponent = 1.0 / t
            var sum = 0.0
            for (example in examples) {
                var total = 0.0
                var trueMass = 0.0
                for (label in example.raw.labels) {
                    val p = example.raw.getValue(label).value.pow(exponent)
                    total += p
                    if (label == example.trueLabel) trueMass = p
                }
                if (total <= 0.0 || !total.isFinite()) return Double.MAX_VALUE
                sum -= ln((trueMass / total).coerceAtLeast(EPSILON))
            }
            return sum / examples.size
        }
    }
}
