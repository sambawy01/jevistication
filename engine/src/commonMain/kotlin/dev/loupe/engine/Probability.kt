package dev.loupe.engine

import kotlin.jvm.JvmInline

/**
 * A probability in the closed interval [0, 1].
 *
 * The engine never lets a bare [Double] stand in for a probability. A4 requires that any
 * probability outside [0,1] be rejected at the boundary, and A7 forbids the policy runner
 * from ever reading an unvalidated value. Constructing a [Probability] is that boundary:
 * once you hold one, it is in range by construction.
 */
@JvmInline
value class Probability private constructor(val value: Double) {

    companion object {
        /**
         * Returns a [Probability] for [value].
         *
         * @throws IllegalArgumentException if [value] is NaN or outside [0,1].
         */
        fun of(value: Double): Probability {
            require(!value.isNaN()) { "probability must be a number, was NaN" }
            require(value in 0.0..1.0) { "probability must be in [0,1], was $value" }
            return Probability(value)
        }

        /** Returns a [Probability] for [value], or null if it is NaN or outside [0,1]. */
        fun ofOrNull(value: Double): Probability? =
            if (!value.isNaN() && value in 0.0..1.0) Probability(value) else null
    }
}
