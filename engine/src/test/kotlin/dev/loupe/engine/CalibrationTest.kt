package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalibrationTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    private fun raw(yes: Double): Distribution =
        receipt.validate(mapOf("yes" to yes, "no" to 1.0 - yes))

    /** A model claiming 0.99 on "yes" that is in truth right only 70% of the time. */
    private fun overconfidentExamples(): List<CalibrationExample> =
        List(70) { CalibrationExample(raw(0.99), "yes") } +
            List(30) { CalibrationExample(raw(0.99), "no") }

    @Test
    fun `ece is zero when confidence matches accuracy`() {
        val predictions = List(10) { Recalibrator.Identity.calibrate(raw(1.0)) to "yes" }
        assertEquals(0.0, Calibration.ece(predictions), 1e-12)
    }

    @Test
    fun `ece reports the gap for an overconfident model`() {
        val predictions = overconfidentExamples()
            .map { Recalibrator.Identity.calibrate(it.raw) to it.trueLabel }
        // claims 0.99, right 70% of the time
        assertEquals(0.29, Calibration.ece(predictions), 1e-9)
    }

    @Test
    fun `ece of an empty set is zero`() {
        assertEquals(0.0, Calibration.ece(emptyList<Pair<Distribution, String>>()))
    }

    @Test
    fun `ece rejects a non-positive bin count`() {
        assertFailsWith<IllegalArgumentException> {
            Calibration.ece(emptyList<Pair<Distribution, String>>(), bins = 0)
        }
    }

    @Test
    fun `temperature of one passes masses through`() {
        val c = TemperatureScaling.of(1.0).calibrate(raw(0.73))
        assertEquals(0.73, c.getValue("yes").value, 1e-9)
    }

    @Test
    fun `temperature must be finite and positive`() {
        assertFailsWith<IllegalArgumentException> { TemperatureScaling.of(0.0) }
        assertFailsWith<IllegalArgumentException> { TemperatureScaling.of(-1.0) }
        assertFailsWith<IllegalArgumentException> { TemperatureScaling.of(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { TemperatureScaling.of(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `scaling is monotone so it never changes the winning label`() {
        val hot = TemperatureScaling.of(8.0).calibrate(raw(0.6))
        assertEquals("yes", hot.argmax)
        val cold = TemperatureScaling.of(0.2).calibrate(raw(0.6))
        assertEquals("yes", cold.argmax)
    }

    @Test
    fun `a fitted recalibrator beats the identity baseline on ECE`() {
        val examples = overconfidentExamples()
        val fitted = TemperatureScaling.fit(examples)

        val baselineEce = Calibration.ece(
            examples.map { Recalibrator.Identity.calibrate(it.raw) to it.trueLabel },
        )
        val fittedEce = Calibration.ece(
            examples.map { fitted.calibrate(it.raw) to it.trueLabel },
        )

        assertTrue(fitted.temperature > 1.0, "expected softening, got T=${fitted.temperature}")
        assertTrue(
            fittedEce < baselineEce,
            "fitted ECE $fittedEce should beat baseline $baselineEce",
        )
        assertTrue(fittedEce < 0.05, "fitted ECE $fittedEce should be near zero")
    }

    @Test
    fun `fit softens an overconfident model toward its true accuracy`() {
        val fitted = TemperatureScaling.fit(overconfidentExamples())
        val calibrated = fitted.calibrate(raw(0.99)).getValue("yes").value
        assertEquals(0.70, calibrated, 0.03)
    }

    @Test
    fun `fit is deterministic`() {
        val a = TemperatureScaling.fit(overconfidentExamples()).temperature
        val b = TemperatureScaling.fit(overconfidentExamples()).temperature
        assertEquals(a, b)
    }

    @Test
    fun `fit rejects an empty calibration split`() {
        assertFailsWith<IllegalArgumentException> { TemperatureScaling.fit(emptyList()) }
    }

    @Test
    fun `fit rejects a true label that is not a candidate`() {
        assertFailsWith<IllegalArgumentException> {
            TemperatureScaling.fit(listOf(CalibrationExample(raw(0.9), "maybe")))
        }
    }
}
