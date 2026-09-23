package dev.loupe.engine

import kotlin.math.roundToInt
import kotlin.random.Random

/** Why an item was put in front of the user. */
enum class SelectionReason {
    /** Chosen because a correction here teaches the model most. */
    UNCERTAIN,

    /** Chosen at random from confident decisions, to catch drift. */
    AUDIT,
}

/** One item awaiting review. */
data class QueueEntry(
    val row: LedgerRow,
    val reason: SelectionReason,
    /** `1 - margin`: 1 when the top two labels are tied, 0 when one label takes everything. */
    val informativeness: Double,
)

/**
 * The uncertain queue (D1).
 *
 * You never review everything. You review what teaches the model most — the decisions where the
 * top two labels were nearly tied — **plus a random sample of confident ones**.
 *
 * The audit arm is not padding. A labelled set drawn only from what the model found hard is
 * biased: it will never contain the case the model is confidently, quietly wrong about, so drift
 * in the easy majority goes unmeasured. The audit arm is what makes the corrections a usable
 * calibration and fine-tuning set rather than a collection of edge cases.
 */
object UncertainQueue {

    /**
     * Selects at most [size] decisions for review from [rows].
     *
     * Rows already carrying a correction are skipped: they have been reviewed.
     *
     * @param auditShare the fraction of the queue drawn at random from confident decisions.
     * @param seed fixed, so the same ledger yields the same queue.
     */
    fun select(
        rows: List<LedgerRow>,
        size: Int,
        auditShare: Double = 0.1,
        seed: Long = 0L,
    ): List<QueueEntry> {
        require(size >= 0) { "size must not be negative, was $size" }
        require(auditShare in 0.0..1.0) { "auditShare must be in [0,1], was $auditShare" }

        // A mechanical answer has nothing to teach the model and cannot drift, so it is never queued.
        val unreviewed = rows.filter { it.correction == null && !it.isMechanical }
        if (size == 0 || unreviewed.isEmpty()) return emptyList()

        val ranked = unreviewed.sortedWith(
            compareByDescending<LedgerRow> { 1.0 - it.distribution.margin }
                .thenBy { it.judgmentId },
        )

        val auditTarget = (size * auditShare).roundToInt().coerceAtMost(size)
        val uncertainTarget = (size - auditTarget).coerceAtMost(ranked.size)

        val uncertain = ranked.take(uncertainTarget)
        val remainder = ranked.drop(uncertainTarget)
        val audit = remainder.shuffled(Random(seed)).take(auditTarget.coerceAtMost(remainder.size))

        return uncertain.map { QueueEntry(it, SelectionReason.UNCERTAIN, informativeness(it)) } +
            audit.map { QueueEntry(it, SelectionReason.AUDIT, informativeness(it)) }
    }

    private fun informativeness(row: LedgerRow): Double = 1.0 - row.distribution.margin
}
