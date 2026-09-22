package dev.loupe.engine

import kotlin.math.abs

/**
 * A normalised probability distribution over a fixed set of candidate labels.
 *
 * This is what the decision model returns for a Choice judgment (A1's acceptance criterion is
 * "a Choice over 200 candidates returns a normalised distribution") and what A5 records, in
 * full, in every ledger row. Construction enforces the invariants the rest of the engine relies
 * on: at least one candidate, every mass a valid [Probability], and the masses summing to 1
 * within [TOLERANCE]. A distribution you hold is therefore always well-formed.
 */
class Distribution private constructor(
    private val masses: Map<String, Probability>,
) {
    /** The candidate labels, in the order they were supplied. */
    val labels: Set<String> get() = masses.keys

    /** The mass assigned to [label], or null if [label] is not a candidate. */
    operator fun get(label: String): Probability? = masses[label]

    /**
     * The mass assigned to [label].
     *
     * @throws IllegalArgumentException if [label] is not a candidate.
     */
    fun getValue(label: String): Probability =
        masses[label] ?: throw IllegalArgumentException("not a candidate label: $label")

    /**
     * The label carrying the greatest mass — the selected answer for a Choice.
     * Ties break to the label supplied first.
     */
    val argmax: String
        get() = masses.entries.maxByOrNull { it.value.value }!!.key

    override fun equals(other: Any?): Boolean = other is Distribution && other.masses == masses

    override fun hashCode(): Int = masses.hashCode()

    override fun toString(): String = "Distribution($masses)"

    companion object {
        /** Masses must sum to 1 within this absolute tolerance. */
        const val TOLERANCE: Double = 1e-6

        /**
         * Builds a [Distribution] from label to mass.
         *
         * @throws IllegalArgumentException if there are no candidates, a mass is outside [0,1]
         *   or NaN, or the masses do not sum to 1 within [TOLERANCE].
         */
        fun of(masses: Map<String, Double>): Distribution {
            require(masses.isNotEmpty()) { "a distribution needs at least one candidate" }
            val validated = LinkedHashMap<String, Probability>(masses.size)
            var sum = 0.0
            for ((label, mass) in masses) {
                val p = Probability.of(mass)
                validated[label] = p
                sum += p.value
            }
            require(abs(sum - 1.0) <= TOLERANCE) {
                "distribution masses must sum to 1 (within $TOLERANCE), summed to $sum"
            }
            return Distribution(validated)
        }

        /**
         * Builds a [Distribution] from label to mass pairs.
         *
         * @throws IllegalArgumentException on a duplicate label, in addition to the conditions
         *   documented on the [Map] overload.
         */
        fun of(vararg masses: Pair<String, Double>): Distribution {
            val map = LinkedHashMap<String, Double>(masses.size)
            for ((label, mass) in masses) {
                require(!map.containsKey(label)) { "duplicate candidate label: $label" }
                map[label] = mass
            }
            return of(map)
        }
    }
}
