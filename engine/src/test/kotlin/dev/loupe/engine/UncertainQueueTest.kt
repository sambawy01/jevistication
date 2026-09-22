package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UncertainQueueTest {

    private val judgment = Judgment.Choice("j", "Is this urgent?", listOf("yes", "no"))

    private fun row(id: String, yes: Double, correction: String? = null) = LedgerRow(
        judgmentId = id,
        criteriaHash = judgment.criteriaHash,
        distribution = judgment.validate(mapOf("yes" to yes, "no" to 1.0 - yes)),
        action = if (yes >= 0.5) "yes" else "no",
        propensity = Probability.of(1.0),
        correction = correction,
    )

    /** One near-tie, one mild, and several very confident rows. */
    private fun ledger() = listOf(
        row("tied", 0.50),
        row("close", 0.55),
        row("mild", 0.70),
    ) + (0 until 10).map { row("sure-$it", 0.99) }

    @Test
    fun `margin is zero for a tie and large for a confident call`() {
        assertEquals(0.0, judgment.validate(mapOf("yes" to 0.5, "no" to 0.5)).margin, 1e-12)
        assertEquals(0.98, judgment.validate(mapOf("yes" to 0.99, "no" to 0.01)).margin, 1e-12)
    }

    @Test
    fun `puts the most uncertain decisions first`() {
        val queue = UncertainQueue.select(ledger(), size = 3, auditShare = 0.0)
        assertEquals(listOf("tied", "close", "mild"), queue.map { it.row.judgmentId })
        assertTrue(queue.all { it.reason == SelectionReason.UNCERTAIN })
        assertEquals(1.0, queue.first().informativeness, 1e-12)
    }

    @Test
    fun `mixes in a random audit arm of confident decisions`() {
        val queue = UncertainQueue.select(ledger(), size = 10, auditShare = 0.3, seed = 1L)
        val audits = queue.filter { it.reason == SelectionReason.AUDIT }

        assertEquals(3, audits.size)
        // The audit arm is drawn from rows the model was sure about -- that is its whole purpose.
        assertTrue(audits.all { it.row.judgmentId.startsWith("sure-") })
    }

    @Test
    fun `without an audit arm the queue is purely the hard cases`() {
        val queue = UncertainQueue.select(ledger(), size = 5, auditShare = 0.0)
        assertTrue(queue.none { it.reason == SelectionReason.AUDIT })
    }

    @Test
    fun `is deterministic for a given seed`() {
        val a = UncertainQueue.select(ledger(), size = 8, auditShare = 0.5, seed = 4L)
        val b = UncertainQueue.select(ledger(), size = 8, auditShare = 0.5, seed = 4L)
        assertEquals(a.map { it.row.judgmentId }, b.map { it.row.judgmentId })
    }

    @Test
    fun `skips decisions the user has already corrected`() {
        val rows = listOf(row("reviewed", 0.50, correction = "no"), row("fresh", 0.52))
        val queue = UncertainQueue.select(rows, size = 5)
        assertEquals(listOf("fresh"), queue.map { it.row.judgmentId })
    }

    @Test
    fun `never returns more than exists`() {
        val queue = UncertainQueue.select(ledger(), size = 500, auditShare = 0.2)
        assertEquals(13, queue.size)
    }

    @Test
    fun `an empty ledger or zero size yields an empty queue`() {
        assertTrue(UncertainQueue.select(emptyList(), size = 5).isEmpty())
        assertTrue(UncertainQueue.select(ledger(), size = 0).isEmpty())
    }

    @Test
    fun `rejects invalid parameters`() {
        assertFailsWith<IllegalArgumentException> { UncertainQueue.select(ledger(), size = -1) }
        assertFailsWith<IllegalArgumentException> {
            UncertainQueue.select(ledger(), size = 5, auditShare = 1.5)
        }
    }
}
