package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThresholdSliderTest {

    private val judgment = Judgment.Choice("j", "Is this junk?", listOf("yes", "no"))

    private fun row(yes: Double, correction: String? = null) = LedgerRow(
        judgmentId = judgment.id,
        criteriaHash = judgment.criteriaHash,
        distribution = judgment.validate(mapOf("yes" to yes, "no" to 1.0 - yes)),
        action = if (yes >= 0.9) "yes" else Policy.ABSTAIN,
        propensity = Probability.of(1.0),
        correction = correction,
    )

    @Test
    fun `counts what lowering the threshold would newly act on`() {
        val rows = listOf(row(0.95), row(0.75), row(0.72), row(0.40))
        val preview = ThresholdSlider.preview(rows, Probability.of(0.9), Probability.of(0.7))

        assertEquals(2, preview.additionalActions)
        assertEquals(0, preview.fewerActions)
        assertEquals(4, preview.rowsConsidered)
    }

    @Test
    fun `counts what raising the threshold would stop acting on`() {
        val rows = listOf(row(0.95), row(0.92), row(0.40))
        val preview = ThresholdSlider.preview(rows, Probability.of(0.9), Probability.of(0.99))

        assertEquals(0, preview.additionalActions)
        assertEquals(2, preview.fewerActions)
    }

    @Test
    fun `grounds the cost in corrections the user actually made`() {
        val rows = listOf(
            row(0.75, correction = "yes"), // engine's top label was right
            row(0.72, correction = "no"),  // engine's top label was wrong -- a rescue
            row(0.71, correction = "yes"),
        )
        val preview = ThresholdSlider.preview(rows, Probability.of(0.9), Probability.of(0.7))

        assertEquals(3, preview.additionalActions)
        assertEquals(3, preview.correctionsConsulted)
        assertEquals(1, preview.mistakesIntroduced)
        assertTrue(preview.summary().contains("you would have rescued 1"))
    }

    @Test
    fun `says the cost is unknown rather than inventing one`() {
        val rows = listOf(row(0.75), row(0.72))
        val preview = ThresholdSlider.preview(rows, Probability.of(0.9), Probability.of(0.7))

        assertNull(preview.mistakesIntroduced)
        assertEquals(0, preview.correctionsConsulted)
        assertTrue(preview.summary().contains("cost is unknown"))
    }

    @Test
    fun `reports no change when the threshold does not move anything`() {
        val rows = listOf(row(0.95), row(0.40))
        val preview = ThresholdSlider.preview(rows, Probability.of(0.9), Probability.of(0.85))
        assertEquals(0, preview.additionalActions)
        assertEquals(0, preview.fewerActions)
        assertTrue(preview.summary().contains("no change"))
    }

    @Test
    fun `an empty ledger previews nothing`() {
        val preview = ThresholdSlider.preview(emptyList(), Probability.of(0.9), Probability.of(0.5))
        assertEquals(0, preview.rowsConsidered)
        assertEquals(0, preview.additionalActions)
    }
}
