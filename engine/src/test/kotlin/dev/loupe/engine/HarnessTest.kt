package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessTest {

    private val urgent = Judgment.Choice("is-urgent", "Is this urgent?", listOf("yes", "no"))

    /**
     * A model that is confident and right on easy items, and confidently *wrong* on hard ones —
     * the §6 situation the whole abstention design exists for.
     */
    private val backend = Backend { _, state ->
        if (state.text.contains("EASY")) {
            mapOf("yes" to 0.95, "no" to 0.05)
        } else {
            mapOf("yes" to 0.55, "no" to 0.45)
        }
    }

    /** 70 easy items truly "yes"; 30 hard items truly "no", which the model gets wrong. */
    private fun fixtures(): List<Fixture> =
        (0 until 70).map { Fixture(Item("easy-$it", "EASY"), "yes", "g-easy-$it") } +
            (0 until 30).map { Fixture(Item("hard-$it", "HARD"), "no", "g-hard-$it") }

    private fun engine(threshold: Double) =
        DecisionEngine(backend, threshold = Probability.of(threshold))

    @Test
    fun `abstention buys selective accuracy at the cost of coverage`() {
        val report = Harness.evaluate(urgent, fixtures(), engine(0.8), baseline = { "no" })

        // It acted only on the easy items, and was right on all of them.
        assertEquals(0.7, report.coverage, 1e-9)
        assertEquals(1.0, report.selectiveAccuracy, 1e-9)
        assertEquals(0.3, report.abstentionRate, 1e-9)
        // Forced to answer everything, it would have been right 70% of the time.
        assertEquals(0.7, report.accuracyAtFullCoverage, 1e-9)
    }

    @Test
    fun `says so when a judgment does not beat its dumb baseline`() {
        // A baseline that always answers "yes" is right on exactly the 70 easy items -- a tie,
        // and a tie is not a win.
        val report = Harness.evaluate(urgent, fixtures(), engine(0.8), baseline = { "yes" })
        assertEquals(0.7, report.baselineAccuracy, 1e-9)
        assertFalse(report.beatsBaseline, "a tie with the dumb baseline is not beating it")
        assertTrue(report.summary().contains("DOES NOT beat baseline"))
    }

    @Test
    fun `reports beating a genuinely worse baseline`() {
        val report = Harness.evaluate(urgent, fixtures(), engine(0.8), baseline = { "no" })
        assertEquals(0.3, report.baselineAccuracy, 1e-9)
        assertTrue(report.beatsBaseline)
        assertTrue(report.summary().contains("beats baseline"))
    }

    @Test
    fun `reports ece and brier over the whole distribution`() {
        val report = Harness.evaluate(urgent, fixtures(), engine(0.8), baseline = { "no" })
        assertTrue(report.ece > 0.0, "an overconfident model should show calibration error")
        assertTrue(report.brier > 0.0)
        assertTrue(report.brier <= 2.0)
    }

    @Test
    fun `reliability bins account for every item`() {
        val report = Harness.evaluate(urgent, fixtures(), engine(0.8), baseline = { "no" })
        assertEquals(100, report.reliability.sumOf { it.count })
        assertEquals(100, report.n)
    }

    @Test
    fun `a threshold of zero gives full coverage and equal accuracies`() {
        val report = Harness.evaluate(urgent, fixtures(), engine(0.0), baseline = { "no" })
        assertEquals(1.0, report.coverage, 1e-9)
        assertEquals(report.accuracyAtFullCoverage, report.selectiveAccuracy, 1e-9)
    }

    @Test
    fun `rejects evaluating on no fixtures`() {
        assertFailsWith<IllegalArgumentException> {
            Harness.evaluate(urgent, emptyList(), engine(0.5), baseline = { "no" })
        }
    }
}
