package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PolicyTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    private fun calibrated(yes: Double): CalibratedDistribution =
        Recalibrator.Identity.calibrate(receipt.validate(mapOf("yes" to yes, "no" to 1.0 - yes)))

    @Test
    fun `identity recalibrator passes masses through unchanged`() {
        val c = calibrated(0.73)
        assertEquals(0.73, c.getValue("yes").value)
    }

    @Test
    fun `acts on the top label when its mass meets the threshold`() {
        val d = Policy.decide(calibrated(0.9), Probability.of(0.8))
        val act = assertIs<Decision.Act>(d)
        assertEquals("yes", act.label)
        assertEquals(0.9, act.propensity.value)
    }

    @Test
    fun `abstains when the top mass is below the threshold`() {
        val d = Policy.decide(calibrated(0.6), Probability.of(0.8))
        val abstain = assertIs<Decision.Abstain>(d)
        assertEquals("yes", abstain.topLabel)
        assertEquals(0.6, abstain.topMass.value)
    }

    @Test
    fun `acts when the top mass exactly equals the threshold`() {
        val d = Policy.decide(calibrated(0.8), Probability.of(0.8))
        assertIs<Decision.Act>(d)
    }
}
