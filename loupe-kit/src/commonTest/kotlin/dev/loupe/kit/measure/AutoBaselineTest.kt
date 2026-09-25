package dev.loupe.kit.measure

import dev.loupe.engine.Backend
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.Scored
import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.kit.judgments.JudgmentResults
import dev.loupe.kit.judgments.JudgmentSweep
import dev.loupe.kit.judgments.SweepObserver
import dev.loupe.kit.judgments.SweepProgress
import dev.loupe.kit.sweep.CoordinatorObserver
import dev.loupe.kit.sweep.CoordinatorProgress
import dev.loupe.kit.sweep.ModelLane
import dev.loupe.kit.sweep.StopReason
import dev.loupe.kit.sweep.SweepCoordinator
import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.JudgmentCodec
import dev.loupe.persistence.LedgerCodec
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.BaselineMode
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decision B (2026-09-24, as Loupe Station): a judgment is answered by its keyword baseline
 * automatically once the baseline is strictly more accurate on at least 30 of the user's
 * corrections; logged as mechanical with the model's answer kept alongside; Auto / Always baseline /
 * Always Laya is the manual override.
 */
class AutoBaselineTest {
    private fun item(id: String, text: String) = SourceItem(
        id = id, sourceId = "sample", kind = ItemKind.TEXT, path = "/x/$id", messageIndex = null, name = id,
        text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "h-$id",
        mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = null,
    )

    private var calls = 0

    /** The model always leans to the first option (0.7), so it is right only on half the items. */
    private val backend = Backend { j, _ ->
        calls++
        Scored(mapOf(j.candidates[0] to 0.7, j.candidates[1] to 0.3))
    }

    private val items = (0 until 48).map { i -> item("i$i", "#$i " + if (i % 2 == 0) "your receipt total paid" else "hello there, about the payment") }

    private fun receipt(): UserJudgment = (JudgmentBook.fromTemplate("is-receipt", emptyMap(), emptyList()) as BookResult.Created).judgment

    private fun sweep(j: UserJudgment, auto: Boolean, rerunAll: Boolean = false, ledger: List<LedgerRow> = emptyList()): List<LedgerRow> {
        val out = mutableListOf<LedgerRow>()
        JudgmentSweep(backend).run(j, JudgmentResults.plan(ledger, j, items, rerunAll), object : SweepObserver {
            override fun onSweepProgress(progress: SweepProgress) {}
            override fun onSweepRows(rows: List<LedgerRow>) { out += rows }
            override fun isCancelled() = false
        }, autoBaseline = auto)
        return out
    }

    /** The user's truth: exactly what the baseline says (so the baseline is right on every item). */
    private fun truth(j: UserJudgment, i: SourceItem) = j.baseline!!.answer(i.text)

    private fun corrections(j: UserJudgment, n: Int): Map<CorrectionKey, String> =
        items.take(n).associate { CorrectionKey(j.id, j.criteriaHash, it.id) to truth(j, it) }

    @Test
    fun switchesOnlyFromThirtyCorrectionsAndOnlyWhenStrictlyBetter() {
        val j = receipt()
        assertEquals(BaselineMode.AUTO, j.baselineMode)                           // the default
        val ledger = sweep(j, auto = false)
        val few = AutoBaseline.verdict(ledger, j, corrections(j, 29), items)
        assertEquals(29, few.compared)
        assertFalse(few.enough)
        assertFalse(few.baselineAnswers)
        assertTrue("1 more needed" in few.line, few.line)
        val enough = AutoBaseline.verdict(ledger, j, corrections(j, 30), items)
        assertEquals(30, enough.compared)
        assertTrue(enough.baselineRight > enough.modelRight)
        assertTrue(enough.baselineAnswers && enough.automatic)
        assertTrue(enough.line.startsWith("Auto: the baseline rule answers"), enough.line)
        // a tie keeps Laya
        val tie = AutoBaseline.verdict(BaselineMode.AUTO, true, List(30) { k -> row(j, "t$k", model = j.shape.candidates[0], truth = j.shape.candidates[0]) }) { j.shape.candidates[0] }
        assertEquals(tie.modelRight, tie.baselineRight)
        assertFalse(tie.baselineAnswers)
    }

    private fun row(j: UserJudgment, id: String, model: String, truth: String): LedgerRow {
        val other = j.shape.candidates.first { it != model }
        return LedgerRow(
            j.id, j.criteriaHash, dev.loupe.engine.Distribution.of(mapOf(model to 0.8, other to 0.2)), model,
            dev.loupe.engine.Probability.of(0.8), correction = truth, itemId = id, resolvedBy = ResolvedBy.Model,
        )
    }

    @Test
    fun automaticRowsAreMechanicalWithTheModelsAnswerKept() {
        val j = receipt()
        val first = sweep(j, auto = false)
        calls = 0
        val rows = sweep(j, auto = true, rerunAll = true, ledger = first)
        assertEquals(items.size, calls, "the model is still asked")
        for ((r, it) in rows.zip(items)) {
            assertEquals(ResolvedBy.Mechanical(AutoBaseline.CHECK), r.resolvedBy)
            assertEquals("mechanical:auto-baseline", r.resolvedBy.code)
            assertEquals(truth(j, it), r.action)
            assertEquals(j.shape.candidates[0], assertNotNull(r.modelDistribution).argmax)
            assertEquals(r.action, AutoBaseline.resolve(r, r.action).action)
        }
        // mechanical rows are never model evidence and never queued ...
        assertTrue(rows.all { it.isMechanical && !it.isModelPrediction })
        assertTrue(JudgmentMeasure.queue(rows, listOf(j), emptyMap(), items).isEmpty())
        // ... but the corrections keep measuring both, through the answer kept alongside
        val c = corrections(j, 40)
        val v = AutoBaseline.verdict(rows, j, c, items)
        assertEquals(40, v.compared)
        assertTrue(v.baselineAnswers)
        assertEquals(40, JudgmentMeasure.baseline(rows, j, c, items)!!.report.n)
        // the ledger file keeps the model's answer
        val back = LedgerCodec.parseRow(LedgerCodec.encode(listOf(rows[1])))
        assertEquals(rows[1], back)
        assertTrue("\"modelDistribution\"" in LedgerCodec.encode(listOf(rows[1])))
        assertFalse("modelDistribution" in LedgerCodec.encode(first.take(1)))
    }

    @Test
    fun manualOverrides() {
        val j = receipt()
        val ledger = sweep(j, auto = false)
        val c = corrections(j, 40)
        val laya = JudgmentMeasure.withBaselineMode(j, BaselineMode.ALWAYS_LAYA)
        assertFalse(AutoBaseline.verdict(ledger, laya, c, items).baselineAnswers)
        assertTrue(AutoBaseline.verdict(ledger, laya, c, items).line.startsWith("Always model"))
        val always = JudgmentMeasure.withBaselineMode(j, BaselineMode.ALWAYS_BASELINE)
        assertTrue(always.useBaseline)
        calls = 0
        val rows = sweep(always, auto = false)
        assertEquals(0, calls, "Always baseline does not ask the model")
        assertTrue(rows.all { it.resolvedBy == ResolvedBy.Mechanical(AutoBaseline.ALWAYS_CHECK) && it.modelDistribution == null })
        // modes survive the file; Auto is not written; a file from before the modes reads as before
        for (m in BaselineMode.entries) assertEquals(m, JudgmentCodec.decode(JudgmentCodec.encode(JudgmentMeasure.withBaselineMode(j, m))).baselineMode)
        assertFalse("baselineMode" in dev.loupe.persistence.JsonText.compact(JudgmentCodec.encode(j)))
        val legacy = JudgmentCodec.encode(j).let { o -> JsonValue.Obj(LinkedHashMap(o.fields).also { it["useBaseline"] = JsonValue.Bool(true) }) }
        assertEquals(BaselineMode.ALWAYS_BASELINE, JudgmentCodec.decode(legacy).baselineMode)
        assertEquals(j.criteriaHash, always.criteriaHash)                         // not part of the wording
    }

    @Test
    fun theCoordinatorSwitchesByItself() {
        val j = receipt()
        val first = sweep(j, auto = false).take(10)                               // 10 decided, 38 still to judge
        val c = items.take(10).associate { CorrectionKey(j.id, j.criteriaHash, it.id) to truth(j, it) }
        val rows = mutableListOf<LedgerRow>()
        val host = object : CoordinatorObserver {
            override fun onCoordinatorProgress(progress: CoordinatorProgress) {}
            override fun onCoordinatorRows(rows: List<LedgerRow>) { rows.also { r -> this@AutoBaselineTest.sink += r } }
            override fun stopReason(): StopReason? = null
        }
        sink.clear()
        SweepCoordinator(backend, ModelLane()).run(listOf(j), items, first, "2026-09-24", host, c)
        assertTrue(sink.isNotEmpty() && sink.all { it.isModelPrediction }, "10 corrections: Laya still answers")
        // with 30 corrections the baseline answers
        val all = sweep(j, auto = false)
        val c30 = corrections(j, 30)
        sink.clear()
        val fresh = all.take(30)
        SweepCoordinator(backend, ModelLane()).run(listOf(j), items, fresh, "2026-09-24", host, c30)
        assertTrue(sink.isNotEmpty() && sink.all { it.resolvedBy == ResolvedBy.Mechanical(AutoBaseline.CHECK) }, sink.map { it.resolvedBy.code }.toString())
        rows.clear()
    }

    private val sink = mutableListOf<LedgerRow>()
}
