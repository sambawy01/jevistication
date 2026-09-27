package dev.loupe.kit.judgments

import dev.loupe.engine.FailurePosture
import dev.loupe.templates.BaselineMode
import dev.loupe.templates.Shape
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's Edit sheet for a judgment (audit P1-3, 2026-09-27): `JudgmentBook.reword`. */
class JudgmentBookEditTest {
    private fun created(r: BookResult): UserJudgment = (r as? BookResult.Created)?.judgment ?: error("refused: ${(r as BookResult.Refused).reasons}")

    private val tax get() = created(JudgmentBook.fromTemplate("tax-receipt", emptyMap(), emptyList()))

    private fun edit(j: UserJudgment, title: String = j.title, question: String = j.question, options: String? = JudgmentBook.optionsText(j),
                     invariant: String = j.invariant, breaks: String = j.breaks, lookalikes: String = j.lookalikes) =
        JudgmentBook.reword(j, title, question, options, invariant, breaks, lookalikes)

    @Test
    fun aNewTitleOnlyKeepsCalibration() {
        val j = tax
        val e = created(edit(j, title = "Receipts for my accountant"))
        assertEquals(j.id, e.id)
        assertEquals("Receipts for my accountant", e.title)
        assertEquals(j.criteriaHash, e.criteriaHash)
        assertFalse(JudgmentBook.calibrationRestarts(j, e))
        assertEquals("Saved \"Receipts for my accountant\".", JudgmentBook.editNotice(j, e))
    }

    @Test
    fun newWordingRestartsCalibrationAndKeepsTheSettings() {
        val j = tax.copy(threshold = 0.8, baselineMode = BaselineMode.ALWAYS_LAYA, criteriaInPrompt = true)
        val e = created(edit(j, question = "Is this a record of a payment I need for my taxes?"))
        assertEquals(j.id, e.id)
        assertEquals(0.8, e.threshold)
        assertEquals(BaselineMode.ALWAYS_LAYA, e.baselineMode)
        assertTrue(e.criteriaInPrompt)
        assertTrue(JudgmentBook.calibrationRestarts(j, e))
        assertTrue(JudgmentBook.editNotice(j, e).contains("calibration starts again"))
    }

    @Test
    fun twoOptionsCanBeRenamedButNotMadeBare() {
        val j = tax
        assertIs<Shape.Binary>(j.shape)
        val renamed = created(edit(j, options = "a receipt for tax\nnot a tax receipt"))
        assertEquals(listOf("a receipt for tax", "not a tax receipt"), renamed.shape.candidates)
        val bare = edit(j, options = "yes\nno")
        assertIs<BookResult.Refused>(bare)
        assertIs<BookResult.Refused>(edit(j, options = "a receipt\na receipt"))
        assertIs<BookResult.Refused>(edit(j, options = "a receipt\nnot a receipt\nmaybe"), "a two-option judgment keeps exactly two")
    }

    @Test
    fun theLintStillApplies() {
        assertIs<BookResult.Refused>(edit(tax, question = "  "))
        assertIs<BookResult.Refused>(edit(tax, question = "Is there no receipt?"), "absence questions are refused")
    }

    @Test
    fun blankCriteriaGoBackToUnwritten() {
        val e = created(edit(tax, invariant = "  ", breaks = "", lookalikes = "an invoice not yet paid"))
        assertEquals(UserJudgment.UNWRITTEN, e.invariant)
        assertEquals(UserJudgment.UNWRITTEN, e.breaks)
        assertEquals("an invoice not yet paid", e.lookalikes)
    }

    @Test
    fun aScoreKeepsItsBands() {
        val input = EditorInput.empty().copy(title = "Urgency", question = "How urgent is this message?", shape = DraftShape.SCORE,
                                             bandsText = "can wait\nthis week\ntoday", onFailure = FailurePosture.NULL_ACTION)
        val j = created(JudgmentBook.fromEditor(input, emptyList()))
        assertNull(JudgmentBook.optionsText(j))
        val e = created(edit(j, question = "How soon does this message need an answer?", options = "ignored\nlines"))
        assertEquals(j.shape, e.shape)
        assertEquals("How soon does this message need an answer?", e.question)
    }
}
