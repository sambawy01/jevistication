package dev.loupe.engine

/**
 * The outcome of a mechanical check (A3): either it answered, and the model is never consulted, or
 * it defers to the model.
 *
 * "Mechanical first" is the cheapest accuracy the engine has — a hash, a MIME type, a date
 * arithmetic or a domain comparison is exact and free, and every item resolved this way is one the
 * model cannot be wrong about.
 */
sealed interface Mechanical<out T> {
    /** A mechanical check answered. [by] names the check, for the tracked breakdown. */
    data class Resolved<out T>(val value: T, val by: String) : Mechanical<T>

    /** No mechanical check could answer; the judgment goes to the model. */
    data object Deferred : Mechanical<Nothing>
}

/**
 * Tracks the share of items resolved without consulting the model — A3's acceptance criterion is
 * that this share is a measured, tracked number, because raising it is how §6's accuracy problem
 * gets easier.
 */
class MechanicalStats {
    private val counts = LinkedHashMap<String, Int>()

    var total: Int = 0
        private set

    var resolved: Int = 0
        private set

    /** Items that fell through to the model. */
    val deferred: Int get() = total - resolved

    /** Share of items a mechanical check answered, in [0,1]. Zero when nothing has been recorded. */
    val resolvedShare: Double get() = if (total == 0) 0.0 else resolved.toDouble() / total

    /** Records one outcome and returns it, so it can be used inline. */
    fun <T> record(outcome: Mechanical<T>): Mechanical<T> {
        total++
        if (outcome is Mechanical.Resolved) {
            resolved++
            counts[outcome.by] = (counts[outcome.by] ?: 0) + 1
        }
        return outcome
    }

    /** How many items each named check resolved, in first-seen order. */
    fun byCheck(): Map<String, Int> = LinkedHashMap(counts)

    override fun toString(): String =
        "MechanicalStats(total=$total, resolved=$resolved, share=$resolvedShare)"
}
