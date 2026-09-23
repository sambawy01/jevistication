package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisibleCalibrationTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))
    private val urgent = Judgment.Choice("is-urgent", "Is this urgent?", listOf("yes", "no"))

    private fun row(j: Judgment.Choice, yes: Double, correction: String? = null) = LedgerRow(
        judgmentId = j.id,
        criteriaHash = j.criteriaHash,
        distribution = j.validate(mapOf("yes" to yes, "no" to 1.0 - yes)),
        action = if (yes >= 0.5) "yes" else "no",
        propensity = Probability.of(1.0),
        correction = correction,
    )

    @Test
    fun `reports agreement over the corrections made`() {
        val rows = listOf(
            row(receipt, 0.95, "yes"),
            row(receipt, 0.90, "yes"),
            row(receipt, 0.85, "no"), // engine said yes, user said no
            row(receipt, 0.60),       // not corrected
        )
        val view = VisibleCalibration.forJudgment(rows, "is-receipt")!!
        assertEquals(4, view.decisions)
        assertEquals(3, view.corrections)
        assertEquals(2.0 / 3.0, view.agreement!!, 1e-9)
    }

    @Test
    fun `measures nothing until the user has corrected something`() {
        val view = VisibleCalibration.forJudgment(listOf(row(receipt, 0.9)), "is-receipt")!!
        assertNull(view.agreement)
        assertNull(view.ece)
        assertEquals(0, view.corrections)
        assertTrue(view.summary().contains("nothing measured"))
    }

    @Test
    fun `keeps judgments separate rather than averaging them`() {
        val rows = listOf(
            row(receipt, 0.95, "yes"),
            row(receipt, 0.95, "yes"),
            row(urgent, 0.95, "no"), // confidently wrong
        )
        val views = VisibleCalibration.perJudgment(rows).associateBy { it.judgmentId }

        assertEquals(2, views.size)
        assertEquals(1.0, views.getValue("is-receipt").agreement!!, 1e-9)
        assertEquals(0.0, views.getValue("is-urgent").agreement!!, 1e-9)
    }

    @Test
    fun `orders judgments by how many decisions they have made`() {
        val rows = List(3) { row(urgent, 0.8, "yes") } + List(5) { row(receipt, 0.8, "yes") }
        assertEquals(
            listOf("is-receipt", "is-urgent"),
            VisibleCalibration.perJudgment(rows).map { it.judgmentId },
        )
    }

    @Test
    fun `names the bins where it claims more confidence than it earned`() {
        // Claims 0.95 four times, right only once.
        val rows = listOf(
            row(receipt, 0.95, "yes"),
            row(receipt, 0.95, "no"),
            row(receipt, 0.95, "no"),
            row(receipt, 0.95, "no"),
        )
        val view = VisibleCalibration.forJudgment(rows, "is-receipt")!!
        assertTrue(view.overconfidentBins.isNotEmpty())
        assertTrue(view.overconfidentBins.all { it.meanConfidence > it.accuracy })
        assertTrue(view.ece!! > 0.5)
    }

    @Test
    fun `returns null for a judgment with no decisions logged`() {
        assertNull(VisibleCalibration.forJudgment(listOf(row(receipt, 0.9)), "not-a-judgment"))
    }
}
