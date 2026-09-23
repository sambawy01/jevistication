package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LedgerTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    @Test
    fun `a decision writes a row carrying a propensity`() {
        val ledger = Ledger()
        val dist = receipt.validate(mapOf("yes" to 0.9, "no" to 0.1))
        ledger.append(
            LedgerRow(
                judgmentId = receipt.id,
                criteriaHash = receipt.criteriaHash,
                distribution = dist,
                action = dist.argmax,
                propensity = dist.getValue(dist.argmax),
            ),
        )
        assertEquals(1, ledger.size)
        val row = ledger.rows().single()
        assertEquals("yes", row.action)
        assertEquals(0.9, row.propensity.value)
        assertNull(row.correction)
    }

    @Test
    fun `rows are returned in append order`() {
        val ledger = Ledger()
        repeat(3) { i ->
            val dist = receipt.validate(mapOf("yes" to 0.6, "no" to 0.4))
            ledger.append(LedgerRow("j$i", receipt.criteriaHash, dist, "yes", dist.getValue("yes")))
        }
        assertEquals(listOf("j0", "j1", "j2"), ledger.rows().map { it.judgmentId })
    }

    @Test
    fun `rows snapshot is not affected by later appends`() {
        val ledger = Ledger()
        val dist = receipt.validate(mapOf("yes" to 0.6, "no" to 0.4))
        ledger.append(LedgerRow("a", receipt.criteriaHash, dist, "yes", dist.getValue("yes")))
        val snapshot = ledger.rows()
        ledger.append(LedgerRow("b", receipt.criteriaHash, dist, "yes", dist.getValue("yes")))
        assertEquals(1, snapshot.size)
        assertEquals(2, ledger.size)
    }
}
