package dev.loupe.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * The calibration prior's arithmetic on one answer (Loupe Station's `model_calibration.json`): the
 * model's logits divided by [temperature], and for a two-option (yes/no) question a bias [bias]
 * added to the logit of [biasLabel] — Platt scaling, `p(yes) = sigmoid((z_yes - z_no) / T + b)`.
 *
 * Works on the backend's masses: with the checkpoint's own temperature at 1 (the multilingual
 * checkpoint ships 1.0 everywhere), `ln p` is the logit up to a constant, and softmax ignores
 * constants, so this is exactly the logit form. A temperature never changes which label wins; a
 * non-zero bias can (the fitted files carry 0 unless a bias beat the temperature out of sample).
 */
class LogitScaling(val temperature: Double, val bias: Double = 0.0, val biasLabel: String? = null) : Recalibrator {

    init {
        require(temperature.isFinite() && temperature > 0.0) { "temperature must be finite and positive, was $temperature" }
        require(bias.isFinite()) { "bias must be finite, was $bias" }
    }

    override fun calibrate(raw: Distribution): CalibratedDistribution {
        val logits = raw.labels.associateWith { label ->
            ln(raw.getValue(label).value.coerceAtLeast(FLOOR)) / temperature + if (label == biasLabel) bias else 0.0
        }
        val top = logits.values.max()
        val e = logits.mapValues { exp(it.value - top) }
        val total = e.values.sum()
        return CalibratedDistribution(Distribution.of(e.mapValues { it.value / total }))
    }

    override fun toString(): String = "LogitScaling(T=$temperature, b=$bias on $biasLabel)"

    private companion object {
        /** The smallest mass a double softmax of float logits yields that we take a log of. */
        const val FLOOR: Double = 1e-300
    }
}
