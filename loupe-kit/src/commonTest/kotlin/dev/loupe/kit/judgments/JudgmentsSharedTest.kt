package dev.loupe.kit.judgments

import dev.loupe.engine.Backend
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Scored
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.Shape
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JudgmentsSharedTest {
    private fun item(id: String, text: String, hasText: Boolean = true, duplicateOf: String? = null) = SourceItem(
        id = id, sourceId = "sample", kind = ItemKind.TEXT, path = "/x/$id", messageIndex = null, name = id,
        text = text, hasText = hasText, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "h-$id",
        mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = duplicateOf,
    )

    private fun created(r: BookResult): UserJudgment = (r as? BookResult.Created)?.judgment ?: error("refused: ${(r as BookResult.Refused).reasons}")

    /** Answers the first option with [p] when the text contains "receipt", else 1 - p. */
    private fun backend(p: Double = 0.95, calls: IntArray = IntArray(1)) = Backend { j, state ->
        calls[0]++
        if ("boom" in state.text) error("model exploded")
        val first = if ("receipt" in state.text) p else 1 - p
        Scored(mapOf(j.candidates[0] to first, j.candidates[1] to 1 - first))
    }

    private class Recorder(val cancelAfter: Int = Int.MAX_VALUE) : SweepObserver {
        val rows = mutableListOf<LedgerRow>()
        val progress = mutableListOf<SweepProgress>()
        override fun onSweepProgress(progress: SweepProgress) { this.progress += progress }
        override fun onSweepRows(rows: List<LedgerRow>) { this.rows += rows }
        override fun isCancelled(): Boolean = (progress.lastOrNull()?.done ?: 0) >= cancelAfter
    }

    @Test
    fun templateBecomesAJudgmentWithUniqueIds() {
        val first = created(JudgmentBook.fromTemplate("tax-receipt", emptyMap(), emptyList()))
        assertEquals("j-tax-receipt", first.id)
        assertEquals("tax-receipt", first.templateId)
        assertTrue(first.shape is Shape.Binary, "library yes/no templates say what their options mean")
        assertFalse(first.criteriaInPrompt)
        val second = created(JudgmentBook.fromTemplate("tax-receipt", emptyMap(), listOf(first)))
        assertEquals("j-tax-receipt-2", second.id)
        assertEquals(2, JudgmentBook.usesOf("tax-receipt", listOf(first, second)))
    }

    @Test
    fun templateWithParametersNeedsValues() {
        val refused = JudgmentBook.fromTemplate("about-project", emptyMap(), emptyList())
        assertTrue(refused is BookResult.Refused)
        val t = TemplateLibrary.byId("about-project")!!
        val ok = JudgmentBook.fromTemplate("about-project", t.parameters.associate { it.name to it.example }, emptyList())
        assertTrue(ok is BookResult.Created)
        assertTrue(JudgmentBook.fromTemplate("nope", emptyMap(), emptyList()) is BookResult.Refused)
    }

    private val base = EditorInput.empty().copy(title = "Landlord", question = "Is this from my landlord?")

    @Test
    fun editorRefusesBareYesNoAndBlankOptions() {
        assertTrue(JudgmentBook.findings(base).any { it.rule == "options-say-what-they-mean" })
        val bare = base.copy(positive = "yes", negative = "no")
        assertTrue(JudgmentBook.findings(bare).any { it.rule == "bare-yes-no" })
        val good = base.copy(positive = "from my landlord", negative = "not from my landlord")
        assertEquals(emptyList(), JudgmentBook.findings(good))
        val j = created(JudgmentBook.fromEditor(good, emptyList()))
        assertEquals(listOf("from my landlord", "not from my landlord"), j.shape.candidates)
        assertNull(j.templateId)
    }

    @Test
    fun editorRunsTheEngineLint() {
        val prose = base.copy(question = "Explain why this is from my landlord?", positive = "a", negative = "b")
        assertTrue(JudgmentBook.findings(prose).any { it.rule == "asks-for-prose" })
        assertTrue(JudgmentBook.fromEditor(prose, emptyList()) is BookResult.Refused)
    }

    @Test
    fun pickAndScoreCompile() {
        val pick = base.copy(question = "Which kind of bill is this?", shape = DraftShape.PICK, optionsText = "energy\nwater, phone\nnone of these")
        val p = created(JudgmentBook.fromEditor(pick, emptyList()))
        assertEquals(Shape.Pick(listOf("energy", "water", "phone", "none of these"), "none of these"), p.shape)
        assertTrue(JudgmentBook.findings(pick.copy(optionsText = "only one")).any { it.rule == "too-few-options" })

        val score = base.copy(question = "How urgent is this letter?", shape = DraftShape.SCORE, bandsText = "no deadline\nwithin a month\nthis week")
        val s = created(JudgmentBook.fromEditor(score, emptyList()))
        assertEquals(Shape.Ordinal(1..3, listOf("no deadline", "within a month", "this week")), s.shape)
        assertEquals("2 — within a month", JudgmentResults.shownAnswer(s, "2"))
        assertTrue(JudgmentBook.findings(score.copy(bandsText = "one")).any { it.rule == "too-few-bands" })
    }

    @Test
    fun criteriaInPromptChangesTheHash() {
        val good = base.copy(positive = "from my landlord", negative = "not from my landlord", invariant = "sent by the landlord", breaks = "anyone else")
        val off = created(JudgmentBook.fromEditor(good, emptyList()))
        val on = created(JudgmentBook.fromEditor(good.copy(criteriaInPrompt = true), emptyList()))
        assertTrue(on.criteriaInPrompt)
        assertTrue(off.criteriaHash != on.criteriaHash, "the model reads different text, so calibration restarts")
        assertEquals(on.criteriaHash, JudgmentBook.withCriteriaInPrompt(off, true).criteriaHash)
        assertTrue(JudgmentBook.criteriaApplicable(off))
        assertTrue(JudgmentBook.criteriaNotice(true).contains("starts again"))
    }

    @Test
    fun sweepJudgesTextItemsSkipsDecidedAndAnswersDuplicatesByRule() {
        val dup = created(JudgmentBook.fromTemplate("is-duplicate", emptyMap(), emptyList()))
        val items = listOf(item("a", "a receipt"), item("b", "a copy", duplicateOf = "/x/a"), item("c", "", hasText = false))
        val calls = IntArray(1)
        val rec = Recorder()
        val plan = JudgmentResults.plan(emptyList(), dup, items, rerunAll = false)
        assertEquals(2, plan.toJudge.size)
        assertEquals(1, plan.withoutText)
        val end = JudgmentSweep(backend(calls = calls)).run(dup, plan, rec)
        assertEquals(2, end.done)
        assertEquals(1, end.mechanical)
        assertEquals(1, calls[0], "the duplicate never reaches the model")
        assertFalse(end.running)
        assertNull(end.error)
        assertEquals(2, rec.rows.size)

        val results = JudgmentResults.rows(rec.rows, dup, emptyMap(), items)
        val byRule = results.single { it.mechanical }
        assertEquals("b", byRule.itemId)
        assertNotNull(byRule.ruleNote)
        assertTrue(byRule.ruleNote!!.startsWith("Answered by rule: exact-duplicate"))

        val again = JudgmentResults.plan(rec.rows, dup, items, rerunAll = false)
        assertEquals(0, again.toJudge.size)
        assertEquals(2, again.alreadyDecided)
        assertEquals(2, JudgmentResults.plan(rec.rows, dup, items, rerunAll = true).toJudge.size)
    }

    @Test
    fun resultsMarkUnsureAndCountByWording() {
        val tax = created(JudgmentBook.fromTemplate("tax-receipt", emptyMap(), emptyList()))
        val items = listOf(item("r", "a receipt"), item("n", "a note about a payment"), item("m", "maybe receipt"))
        val rec = Recorder()
        // 0.6 sits below the 0.80 threshold for the "receipt" items: unsure; the note is 0.4/0.6 -> unsure too.
        JudgmentSweep(backend(p = 0.6)).run(tax, JudgmentResults.plan(emptyList(), tax, items, false), rec)
        val rows = JudgmentResults.rows(rec.rows, tax, emptyMap(), items)
        assertEquals(3, rows.size)
        assertTrue(rows.all { !it.acted })
        assertTrue(rows.first().status(tax).startsWith("unsure — leans"))
        val counts = JudgmentResults.counts(rec.rows, tax, emptyMap())
        assertEquals(JudgmentCounts(3, 0, 3, 0, 0, 0), counts)

        val reworded = (tax.reword("Is this a record of a payment I need for my taxes?") as UserJudgment.EditResult.Edited).judgment
        val after = JudgmentResults.counts(rec.rows, reworded, emptyMap())
        assertEquals(0, after.decisions)
        assertEquals(3, after.earlierWording)
    }

    @Test
    fun sweepCancelsBetweenItemsAndKeepsWhatRan() {
        val tax = created(JudgmentBook.fromTemplate("tax-receipt", emptyMap(), emptyList()))
        val items = (1..10).map { item("i$it", "receipt $it") }
        val rec = Recorder(cancelAfter = 3)
        val end = JudgmentSweep(backend()).run(tax, JudgmentResults.plan(emptyList(), tax, items, false), rec)
        assertTrue(end.cancelled)
        assertEquals(3, end.done)
        assertEquals(3, rec.rows.size)
        assertTrue(JudgmentResults.rows(rec.rows, tax, emptyMap(), items).all { it.acted })
    }

    @Test
    fun aFailingModelIsUnusableNotAnException() {
        val phishing = TemplateLibrary.ALL.first { it.warnOnly && it.shape is Shape.Binary }
        val j = created(JudgmentBook.fromTemplate(phishing.id, phishing.parameters.associate { it.name to it.example }, emptyList()))
        val items = listOf(item("x", "boom"), item("y", "a receipt"))
        val rec = Recorder()
        val end = JudgmentSweep(backend()).run(j, JudgmentResults.plan(emptyList(), j, items, false), rec)
        assertEquals(1, end.unusable)
        assertNull(end.error)
        val rows = JudgmentResults.rows(rec.rows, j, emptyMap(), items)
        assertTrue(rows.first().unusable, "unusable rows sort first")
        assertEquals("could not judge", rows.first().status(j))
        assertEquals("no signal (not an all-clear)", JudgmentResults.shownAnswer(j, (j.shape as Shape.Binary).negative))
        assertEquals(FailurePosture.LOUD, j.onFailure)
    }

    @Test
    fun receiptGateAnswersAProductPageByRuleAndKeepsItOutOfTheUnsureQueue() {
        val receipt = created(JudgmentBook.fromTemplate("is-receipt", emptyMap(), emptyList()))
        val items = listOf(
            item("stroller", STROLLER_PAGE),
            item("receipt", "John Lewis receipt No. 8841-2210-77. TOTAL £1,199.00. Paid by VISA ************4412"),
            item("ar", "فاتورة ضريبية مبسطة\nرقم الفاتورة: 20931\nالإجمالي: ١٣٫٠٠ ريال\nطريقة الدفع: مدى"),
        )
        val calls = IntArray(1)
        val rec = Recorder()
        // A torn model (0.52 on everything) is what put the stroller in the Unsure queue.
        val torn = Backend { j, _ -> calls[0]++; Scored(mapOf(j.candidates[0] to 0.52, j.candidates[1] to 0.48)) }
        val end = JudgmentSweep(torn).run(receipt, JudgmentResults.plan(emptyList(), receipt, items, false), rec)
        assertEquals(3, end.done)
        assertEquals(1, end.mechanical)
        assertEquals(2, calls[0], "the product page never reaches the model; the two receipts do")

        val stroller = rec.rows.single { it.itemId == "stroller" }
        assertEquals("not a receipt", stroller.distribution.argmax)
        assertEquals(dev.loupe.engine.ResolvedBy.Mechanical(dev.loupe.templates.TransactionEvidence.CHECK), stroller.resolvedBy)
        val note = JudgmentResults.rows(rec.rows, receipt, emptyMap(), items).single { it.itemId == "stroller" }.ruleNote!!
        assertTrue("no sign of a payment" in note, note)

        val queue = dev.loupe.kit.measure.JudgmentMeasure.queue(rec.rows, listOf(receipt), emptyMap(), items)
        assertTrue(queue.none { it.itemId == "stroller" }, "a rule's answer is never an Unsure question")
        assertEquals(setOf("receipt", "ar"), queue.map { it.itemId }.toSet())

        // A model answer logged before the gate existed is dropped from the queue too.
        val legacy = rec.rows.map { if (it.itemId == "stroller") it.copy(resolvedBy = dev.loupe.engine.ResolvedBy.Model, distribution = rec.rows.first { r -> r.itemId == "receipt" }.distribution) else it }
        val legacyQueue = dev.loupe.kit.measure.JudgmentMeasure.queue(legacy, listOf(receipt), emptyMap(), items)
        assertTrue(legacyQueue.none { it.itemId == "stroller" })
    }

    private companion object {
        const val STROLLER_PAGE = """File: Bugaboo Donkey 5 Mono complete stroller.pdf

Bugaboo Donkey 5 Mono complete stroller
★★★★★ 4.8 (212 reviews)
£1,199.00
Pay in 3 interest-free payments of £399.67 with Klarna.
Add to basket
In stock – Free delivery on orders over £50
Product details
2-year warranty, extendable when you register."""
    }
}
