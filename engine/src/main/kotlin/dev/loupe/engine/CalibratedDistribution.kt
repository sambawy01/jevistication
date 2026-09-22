package dev.loupe.engine

/**
 * A [Distribution] that has passed through a [Recalibrator].
 *
 * A7's policy runner accepts only this type, and the constructor is `internal`, so the only way to
 * obtain one is through a recalibrator. That makes "the policy never reads a raw, uncalibrated
 * probability" a compile-time guarantee rather than a convention.
 */
class CalibratedDistribution internal constructor(val distribution: Distribution) {
    /** The highest-mass label after calibration. */
    val argmax: String get() = distribution.argmax

    /** The calibrated mass on [label]. @throws IllegalArgumentException if [label] is not present. */
    fun getValue(label: String): Probability = distribution.getValue(label)

    override fun equals(other: Any?): Boolean =
        other is CalibratedDistribution && other.distribution == distribution

    override fun hashCode(): Int = distribution.hashCode()

    override fun toString(): String = "CalibratedDistribution($distribution)"
}
