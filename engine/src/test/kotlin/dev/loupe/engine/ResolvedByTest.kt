package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolvedByTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    private fun row(
        yes: Double,
        correction: String? = null,
        resolvedBy: ResolvedBy = ResolvedBy.Model,
        itemId: String? = null,
    ) = LedgerRow(
        judgmentId = receipt.id,
        criteriaHash = receipt.criteriaHash,
        distribution = receipt.validate(mapOf("yes" to yes, "no" to 1.0 - yes)),
        action = if (yes >= 0.5) "yes" else "no",
        propensity = Probability.of(1.0),
        correction = correction,
        itemId = itemId,
        resolvedBy = resolvedBy,
    )

    private val dup = ResolvedBy.Mechanical("exact-duplicate")

    @Test
    fun `the engine records which path answered each row`() {
        val engine = DecisionEngine(Backend { _, _ -> mapOf("yes" to 0.8, "no" to 0.2) }, Probability.of(0.5))
        val mech = engine.decide(receipt, Item("a", "x")) { Mechanical.Resolved("yes", "exact-duplicate") }
        val model = engine.decide(receipt, Item("b", "x"))
        val broken = DecisionEngine(Backend { _, _ -> mapOf("maybe" to 1.0) }, Probability.of(0.5))
            .decide(receipt, Item("c", "x"))

        assertEquals(dup, mech.row.resolvedBy)
        assertTrue(mech.resolvedMechanically)
        assertEquals(ResolvedBy.Model, model.row.resolvedBy)
        assertEquals(ResolvedBy.Unusable, broken.row.resolvedBy)
        assertTrue(broken.row.failure != null)
    }

    @Test
    fun `codes round-trip and a missing code reads as legacy`() {
        for (r in listOf(ResolvedBy.Model, ResolvedBy.Unusable, dup, ResolvedBy.Mechanical("mime:image"))) {
            assertEquals(r, ResolvedBy.parse(r.code, if (r == ResolvedBy.Unusable) "x" else null))
        }
        assertEquals(ResolvedBy.Model, ResolvedBy.parse(null, null))
        assertEquals(ResolvedBy.Unusable, ResolvedBy.parse(null, "bad json"))
        assertFailsWith<IllegalArgumentException> { ResolvedBy.parse("oracle", null) }
        assertFailsWith<IllegalArgumentException> { ResolvedBy.Mechanical(" ") }
    }

    @Test
    fun `a row's resolver must agree with its failure`() {
        assertFailsWith<IllegalArgumentException> { row(0.9, resolvedBy = ResolvedBy.Unusable) }
        val legacyFailure = LedgerRow("j", "h", receipt.noInformation(), Policy.UNUSABLE, Probability.of(1.0), failure = "x")
        assertEquals(ResolvedBy.Unusable, legacyFailure.resolvedBy)
        assertFalse(legacyFailure.isModelPrediction)
    }

    @Test
    fun `export writes the resolver for every row`() {
        val lines = Export.ledgerToJsonl(listOf(row(0.9), row(1.0, resolvedBy = dup))).lines()
        assertTrue(lines[0].contains(""""resolvedBy":"model""""))
        assertTrue(lines[1].contains(""""resolvedBy":"mechanical:exact-duplicate""""))
    }

    @Test
    fun `calibration counts mechanical rows as decisions, never as model predictions`() {
        // A confidently wrong model row, plus ten certain, correct mechanical rows.
        val rows = listOf(row(0.9, correction = "no")) + List(10) { row(1.0, correction = "yes", resolvedBy = dup) }
        val view = VisibleCalibration.forJudgment(rows, receipt.id)!!
        assertEquals(11, view.decisions)
        assertEquals(10, view.mechanical)
        assertEquals(1, view.corrections)
        assertEquals(0.0, view.agreement)
        assertTrue(Export.calibrationToJson(listOf(view)).contains(""""mechanical":10"""))
    }

    @Test
    fun `the threshold slider and the queue ignore mechanical rows`() {
        val rows = listOf(row(0.7, itemId = "m")) + List(5) { row(1.0, resolvedBy = dup, itemId = "d$it") }
        val preview = ThresholdSlider.preview(rows, Probability.of(0.8), Probability.of(0.6))
        assertEquals(1, preview.rowsConsidered)
        assertEquals(1, preview.additionalActions)

        val queue = UncertainQueue.select(rows, size = 10, auditShare = 1.0)
        assertTrue(queue.none { it.row.isMechanical })
    }

    @Test
    fun `threshold replay never moves a mechanical row`() {
        // At threshold 1.0 a model row with mass 1.0 would still act; a mechanical row always does.
        val rows = List(4) { row(1.0, resolvedBy = dup) }
        val replay = OffPolicy.replayThreshold(rows, Probability.of(1.0), reward = { 1.0 })
        assertEquals(1.0, replay.support)
        assertEquals(0.0, replay.delta)
    }

    @Test
    fun `the harness keeps mechanical answers out of model and baseline figures`() {
        val engine = DecisionEngine(Backend { _, _ -> mapOf("yes" to 0.2, "no" to 0.8) }, Probability.of(0.5))
        val fixtures = listOf(
            Fixture(Item("dup1", "copy"), "yes", "s"),
            Fixture(Item("dup2", "copy"), "yes", "s"),
            Fixture(Item("real", "receipt"), "yes", "s"),
        )
        val report = Harness.evaluate(
            receipt, fixtures, engine, baseline = { "yes" },
            mechanical = { if (it.id.startsWith("dup")) Mechanical.Resolved("yes", "exact-duplicate") else Mechanical.Deferred },
        )
        assertEquals(2, report.mechanical)
        assertEquals(1, report.n)
        // Only the model's own (wrong) answer is scored; the two free correct answers are not.
        assertEquals(0.0, report.accuracyAtFullCoverage)
        assertEquals(1.0, report.baselineAccuracy)
    }
}
