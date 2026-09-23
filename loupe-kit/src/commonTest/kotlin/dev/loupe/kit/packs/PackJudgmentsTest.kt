package dev.loupe.kit.packs

import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.DraftShape
import dev.loupe.kit.judgments.EditorInput
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.kit.watchers.EXAMPLE_PACK
import dev.loupe.persistence.PlatformFiles
import dev.loupe.templates.Shape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pack questions as judgments: the C2 lint on each, conflicts by id, and export round-trips. */
class PackJudgmentsTest {
    private val bistro: Pack = assertIs<PackParse.Valid>(PackFormat.parse(PlatformFiles.readText(EXAMPLE_PACK)!!)).pack

    @Test
    fun everyQuestionIsLintedAndBareYesNoIsRefused() {
        val plans = PackJudgments.plan(bistro, emptyList())
        assertEquals(bistro.questionCount, plans.size)
        assertEquals(29, plans.size)
        assertEquals(14, plans.count { it.addable })
        // A noul without "true"/"false" descriptions would be bare yes/no: the lint refuses it.
        val bare = plans.filter { it.type == "noul" && it.options == listOf("yes", "no") }
        assertEquals(13, bare.size)
        assertTrue(bare.none { it.addable } && bare.all { p -> p.problems.any { "bare yes/no" in it } }, bare.map { it.problems }.toString())
        val team = plans.first { it.judgmentId == "j-complaint-triage-team" }
        assertEquals(DraftShape.PICK, team.shape)
        assertEquals(listOf("kitchen", "delivery", "packaging", "app_or_payment", "other"), team.options)
        assertTrue(team.addable, team.problems.toString())
        val refund = plans.first { it.judgmentId == "j-complaint-triage-refund-requested" }
        assertEquals(DraftShape.YES_NO, refund.shape)
        assertTrue(refund.options[0].startsWith("asks for money back"))
        val frustration = plans.first { it.questionId == "frustration" }
        assertEquals(DraftShape.SCORE, frustration.shape)
        assertEquals(4, frustration.options.size)
        assertTrue(plans.none { it.conflict })
    }

    @Test
    fun addingSettlesConflictsByReplaceKeepBothOrSkip() {
        val plans = PackJudgments.plan(bistro, emptyList())
        val addable = plans.count { it.addable }
        val first = PackJudgments.add(plans, emptyList(), ConflictChoice.SKIP, emptyList())
        assertEquals(addable, first.added.size)
        assertEquals(plans.size - addable, first.refused.size)
        assertEquals(first.added, first.judgments.map { it.id })

        val again = PackJudgments.plan(bistro, first.judgments)
        assertTrue(again.filter { it.addable }.all { it.conflict })
        val skip = PackJudgments.add(again, first.judgments, ConflictChoice.SKIP, emptyList())
        assertEquals(addable, skip.skipped.size)
        assertEquals(first.judgments, skip.judgments)
        val replace = PackJudgments.add(again, first.judgments, ConflictChoice.REPLACE, emptyList())
        assertEquals(addable, replace.replaced.size)
        assertEquals(first.judgments.map { it.id }, replace.judgments.map { it.id })
        val both = PackJudgments.add(again, first.judgments, ConflictChoice.KEEP_BOTH, emptyList())
        assertEquals(addable * 2, both.judgments.size)
        assertEquals(both.judgments.size, both.judgments.map { it.id }.distinct().size)
        // only the chosen ones
        val one = PackJudgments.add(plans, emptyList(), ConflictChoice.SKIP, listOf("j-complaint-triage-team"))
        assertEquals(listOf("j-complaint-triage-team"), one.added)
    }

    @Test
    fun exportImportsBackToTheSameJudgments() {
        val mine = PackJudgments.add(PackJudgments.plan(bistro, emptyList()), emptyList(), ConflictChoice.SKIP, emptyList()).judgments
        val written = JudgmentBook.fromEditor(
            EditorInput.empty().copy(title = "Receipt", question = "Is this a receipt?", positive = "a receipt or proof of purchase", negative = "not a receipt"),
            mine,
        )
        val all = mine + assertIs<BookResult.Created>(written).judgment
        val export = PackJudgments.export(all)
        assertEquals(all.size, export.exported)
        assertTrue(export.left.isEmpty(), export.left.toString())
        val back = assertIs<PackParse.Valid>(PackFormat.parse(export.text)).pack
        assertEquals(PackJudgments.EXPORT_SLUG, back.slug)
        val plans = PackJudgments.plan(back, all)
        assertEquals(all.map { it.id }, plans.map { it.judgmentId })
        assertTrue(plans.all { it.conflict && it.addable }, plans.filter { !it.addable }.map { it.problems }.toString())
        val readded = PackJudgments.add(plans, emptyList(), ConflictChoice.SKIP, emptyList()).judgments
        assertEquals(all.map { it.shape }, readded.map { it.shape })
        assertEquals(all.map { it.question }, readded.map { it.question })
        assertIs<Shape.Binary>(readded.last().shape)
    }

    @Test
    fun exportOfNothingIsAnEmptyPack() {
        val export = PackJudgments.export(emptyList())
        assertEquals(0, export.exported)
        assertTrue("\"presets\": []" in export.text)
    }
}
