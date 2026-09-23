package dev.loupe.kit.measure

import dev.loupe.engine.Backend
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Scored
import dev.loupe.engine.SelectionReason
import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.kit.judgments.JudgmentResults
import dev.loupe.kit.judgments.JudgmentSweep
import dev.loupe.kit.judgments.SweepObserver
import dev.loupe.kit.judgments.SweepProgress
import dev.loupe.persistence.CorrectionCodec
import dev.loupe.persistence.CorrectionRecord
import dev.loupe.persistence.JudgmentCodec
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors the desktop's `the queue, corrections, calibration, slider and baseline form one loop`. */
class JudgmentMeasureTest {
    private fun item(id: String, text: String) = SourceItem(
        id = id, sourceId = "sample", kind = ItemKind.TEXT, path = "/x/$id", messageIndex = null, name = id,
        text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "h-$id",
        mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = null,
    )

    /** Item i gets first-option mass 0.5 + i/100 (so margins differ and some are confident). */
    private val backend = Backend { j, state ->
        val i = state.text.substringAfter("#").substringBefore(" ").toInt()
        val p = 0.5 + i / 100.0
        Scored(mapOf(j.candidates[0] to p, j.candidates[1] to 1 - p))
    }

    private val items = (0 until 48).map { i -> item("i$i", "#$i " + if (i % 2 == 0) "your receipt total paid" else "hello there") }

    private fun receipt(): UserJudgment = (JudgmentBook.fromTemplate("is-receipt", emptyMap(), emptyList()) as BookResult.Created).judgment

    private fun sweep(j: UserJudgment): List<LedgerRow> {
        val rows = mutableListOf<LedgerRow>()
        JudgmentSweep(backend).run(j, JudgmentResults.plan(emptyList(), j, items, false), object : SweepObserver {
            override fun onSweepProgress(progress: SweepProgress) {}
            override fun onSweepRows(rows: List<LedgerRow>) { rowsSink += rows }
            override fun isCancelled() = false
        })
        return rowsSink.toList().also { rowsSink.clear() }
    }
    private val rowsSink = mutableListOf<LedgerRow>()

    @Test
    fun queueCorrectionsCalibrationSliderAndBaselineFormOneLoop() {
        val j = receipt()
        val ledger = sweep(j)
        val log = mutableListOf<CorrectionRecord>()
        fun index() = CorrectionCodec.index(log)

        val queue = JudgmentMeasure.queue(ledger, listOf(j), index(), items)
        assertTrue(queue.isNotEmpty())
        assertTrue(queue.any { it.reason == SelectionReason.AUDIT }, "the random audit arm is present")
        assertTrue(queue.first().informativeness >= queue.filter { it.reason == SelectionReason.UNCERTAIN }.last().informativeness)
        assertEquals(j.shape.candidates, queue.first().options.map { it.first })

        val before = JudgmentMeasure.summary(ledger, j, index())
        assertEquals(0, before.corrections)
        assertNull(before.agreement)
        assertNull(before.ece)
        assertEquals(JudgmentMeasure.MIN_FOR_RELIABILITY, before.correctionsNeededForReliability)
        assertNotNull(before.gateMessage)
        assertEquals(10, JudgmentMeasure.overall(ledger, listOf(j), index()).needed)
        assertNull(JudgmentMeasure.overall(ledger, listOf(j), index()).agreement)

        val rows = JudgmentMeasure.effectiveRows(ledger, j, index()).take(12)
        rows.forEachIndexed { i, row ->
            val other = j.shape.candidates.first { it != row.distribution.argmax }
            val truth = if (i % 4 == 0) other else row.distribution.argmax
            log += JudgmentMeasure.correction(j, row.itemId!!, truth, truth == row.distribution.argmax, "2026-09-23T00:00:00Z")
        }
        val after = JudgmentMeasure.summary(ledger, j, index())
        assertEquals(12, after.corrections)
        assertEquals(9.0 / 12, after.agreement!!, 1e-9)
        assertEquals(JudgmentMeasure.MIN_FOR_RELIABILITY - 12, after.correctionsNeededForReliability)
        assertNull(after.ece, "calibration stays gated below 30")
        assertEquals("Agrees with you 75% over 12 corrections", JudgmentMeasure.overall(ledger, listOf(j), index()).line)
        val corrected = rows.map { it.itemId }.toSet()
        assertTrue(JudgmentMeasure.queue(ledger, listOf(j), index(), items).none { it.itemId in corrected })

        // Undo appends a retraction.
        log += JudgmentMeasure.retraction(queue.first().key.copy(itemId = rows.last().itemId!!), "2026-09-23T00:00:01Z")
        assertEquals(11, JudgmentMeasure.summary(ledger, j, index()).corrections)

        // The slider previews over logged rows; applying writes the threshold.
        val preview = JudgmentMeasure.preview(ledger, j, index(), 0.5)
        assertTrue(preview.preview.additionalActions > 0)
        assertTrue(preview.line.startsWith("${preview.preview.additionalActions} more acted on"))
        assertEquals(0.5, JudgmentMeasure.withThreshold(j, 0.5).threshold)

        val verdict = assertNotNull(JudgmentMeasure.baseline(ledger, j, index(), items))
        assertEquals(11, verdict.report.n)
        assertTrue(verdict.baselineDescription.contains("receipt"))
        assertTrue(verdict.enough)
    }

    @Test
    fun calibrationShowsEceBrierAndBinsFromThirtyCorrections() {
        val j = receipt()
        val ledger = sweep(j)
        val log = JudgmentMeasure.effectiveRows(ledger, j, emptyMap()).take(30).map {
            JudgmentMeasure.correction(j, it.itemId!!, it.distribution.argmax, true, "t")
        }
        val s = JudgmentMeasure.summary(ledger, j, CorrectionCodec.index(log))
        assertEquals(30, s.corrections)
        assertNotNull(s.ece)
        assertNotNull(s.brier)
        assertTrue(s.reliability.isNotEmpty())
        assertNull(s.gateMessage)
    }

    @Test
    fun rewordingReKeysCorrectionsAndTheQueue() {
        val j = receipt()
        val ledger = sweep(j)
        val row = ledger.first()
        val log = listOf(JudgmentMeasure.correction(j, row.itemId!!, j.shape.candidates[1], false, "t"))
        val reworded = JudgmentBook.withCriteriaInPrompt(j, true)
        // Under the new wording nothing counts: no rows, no corrections, nothing queued.
        assertEquals(0, JudgmentMeasure.summary(ledger, reworded, CorrectionCodec.index(log)).decisions)
        assertEquals(ledger.size, JudgmentMeasure.summary(ledger, reworded, CorrectionCodec.index(log)).earlierWording)
        assertTrue(JudgmentMeasure.queue(ledger, listOf(reworded), CorrectionCodec.index(log), items).isEmpty())
    }

    @Test
    fun useTheBaselineAnswersByRuleAndSurvivesTheFile() {
        val j = JudgmentMeasure.withBaseline(receipt(), true)
        assertTrue(j.useBaseline)
        val rows = sweep(j)
        assertTrue(rows.all { it.isMechanical }, "baseline answers are never model evidence")
        assertTrue(JudgmentMeasure.queue(rows, listOf(j), emptyMap(), items).isEmpty())
        assertEquals(j, JudgmentCodec.decode(JudgmentCodec.encode(j)))
        assertFalse(JudgmentCodec.decode(JudgmentCodec.encode(receipt())).useBaseline)
        assertEquals(receipt().criteriaHash, j.criteriaHash)
    }

    @Test
    fun answersMustBeOptions() {
        val j = receipt()
        assertTrue(runCatching { JudgmentMeasure.correction(j, "i0", "maybe", false, "t") }.isFailure)
    }
}
