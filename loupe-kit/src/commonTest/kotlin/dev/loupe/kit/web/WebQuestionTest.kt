package dev.loupe.kit.web

import dev.loupe.engine.Backend
import dev.loupe.engine.Policy
import dev.loupe.engine.ResolvedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebQuestionTest {
    private fun ready(q: String, t: WebDecisionType, ar: Boolean = false) =
        assertIs<WebQuestionResult.Ready>(WebQuestion.compile(q, t, ar), "expected '$q' as $t to compile")

    @Test
    fun `every decision type compiles with neutral described options`() {
        val yn = ready("Is Saturday dry enough for the trip?", WebDecisionType.YES_NO).judgment
        assertEquals(listOf(WebQuestion.MATCH, WebQuestion.NO_MATCH), yn.candidates)
        assertEquals("Is Saturday dry enough for the trip?", yn.question)
        assertEquals(2, yn.descriptions.size)
        assertFalse(yn.ordinal)

        val pick = ready("Which day this week is best for the beach?", WebDecisionType.PICK).judgment
        assertEquals(listOf(WebQuestion.STRONG, WebQuestion.WEAK), pick.candidates)
        assertEquals("Is this item the best answer to: Which day this week is best for the beach?", pick.question)
        assertFalse(pick.ordinal)

        for (t in listOf(WebDecisionType.SCORE, WebDecisionType.RANK)) {
            val j = ready("Score each day for a run at 7am", t).judgment
            assertEquals(WebQuestion.LEVELS, j.candidates)
            assertEquals(5, j.descriptions.size)
            assertTrue(j.ordinal)
            // Laya sends the levels highest first; the written order (and the hash) stay lowest first.
            // (LayaRuntimeRules.reversesScore keys on this flag; backend-laya-common tests the reversal.)
            assertEquals("How well does this item fit: Score each day for a run at 7am?", j.question)
        }
        // No option label names a sector.
        val all = WebQuestion.LEVELS + listOf(WebQuestion.MATCH, WebQuestion.NO_MATCH, WebQuestion.STRONG, WebQuestion.WEAK)
        for (w in listOf("dry", "rain", "time", "delay", "rate", "cheap")) assertTrue(all.none { it.contains(w) })
    }

    @Test
    fun `the ordinal flag is not part of the criteria hash`() {
        val j = ready("Rank departures by reliability and arrival time", WebDecisionType.RANK).judgment
        assertEquals(j.criteriaHash, j.copy(ordinal = false).criteriaHash)
    }

    @Test
    fun `the lint refuses what a classifier cannot answer for each type`() {
        for (t in WebDecisionType.entries) {
            assertTrue(WebQuestion.findings("Explain why the rate moved", t).isNotEmpty(), "prose, $t")
            assertTrue(WebQuestion.findings("Write me a summary of the week", t).isNotEmpty(), "compose, $t")
            assertTrue(WebQuestion.findings("Is it dry? Is it warm?", t).isNotEmpty(), "two questions, $t")
            assertTrue(WebQuestion.findings("Rate each day 1 to 10", t).isNotEmpty(), "free number, $t")
            assertTrue(WebQuestion.findings("  ", t).isNotEmpty(), "empty, $t")
            assertTrue(WebQuestion.findings("x".repeat(WebQuestion.MAX_CHARS + 1), t).isNotEmpty(), "too long, $t")
        }
    }

    @Test
    fun `arabic questions compile with an arabic frame`() {
        val j = ready("أي يوم هذا الأسبوع هو الأفضل للشاطئ؟", WebDecisionType.PICK, ar = true).judgment
        assertEquals("هل هذا العنصر هو الأفضل للسؤال: أي يوم هذا الأسبوع هو الأفضل للشاطئ؟", j.question)
        assertIs<WebQuestionResult.Refused>(WebQuestion.compile("هل الجو جاف؟ هل هو دافئ؟", WebDecisionType.YES_NO, true))
    }

    @Test
    fun `decision types round trip by id`() {
        for (t in WebDecisionType.entries) assertEquals(t, WebDecisionType.of(t.id))
    }

    private val items = listOf(
        WebItem("mon", listOf("Day: Monday", "Rain chance: 80%")),
        WebItem("sat", listOf("Day: Saturday", "Rain chance: 5%")),
    )

    @Test
    fun `a yes no judge reads the first option and writes one ledger row per item`() {
        val j = ready("Is this day dry enough for the trip?", WebDecisionType.YES_NO).judgment
        val backend = Backend.ofMasses { _, s ->
            if (s.text.contains("Saturday")) mapOf(WebQuestion.MATCH to 0.9, WebQuestion.NO_MATCH to 0.1)
            else mapOf(WebQuestion.MATCH to 0.3, WebQuestion.NO_MATCH to 0.7)
        }
        val judge = WebJudge(backend, "open-meteo", WebQuestion.startThreshold(WebDecisionType.YES_NO), WebJudge.BUDGET)
        val d = items.map { judge.decide(j, it) }
        assertEquals(0.9, d[1].verdict.value, 1e-9)
        assertFalse(d[1].verdict.unsure)
        assertTrue(d[0].verdict.unsure)          // 0.7 on "does not match" is under 0.80
        assertEquals("web:open-meteo:sat", d[1].row.itemId)
        assertEquals(ResolvedBy.Model, d[1].row.resolvedBy)
        assertEquals(listOf("sat", "mon"), WebJudge.order(d.map { it.verdict }).map { it.id })
    }

    @Test
    fun `a score judge maps the expected level to 0 to 1 and reports the top level`() {
        val j = ready("Score each day for a run at 7am", WebDecisionType.SCORE).judgment
        val backend = Backend.ofMasses { _, _ -> mapOf("very poor" to 0.0, "poor" to 0.0, "fair" to 0.0, "good" to 0.2, "very good" to 0.8) }
        val v = WebJudge(backend, "open-meteo", 0.6, WebJudge.BUDGET).decide(j, items[0]).verdict
        assertEquals(5, v.level)
        assertEquals((3 * 0.2 + 4 * 0.8) / 4, v.value, 1e-9)
        assertFalse(v.unsure)
    }

    @Test
    fun `an unusable answer is logged as unusable and sorts last`() {
        val j = ready("Which departure is best", WebDecisionType.PICK).judgment
        val bad = WebJudge(Backend.ofMasses { _, _ -> mapOf("nonsense" to 1.0) }, "national-rail-darwin", 0.8, WebJudge.BUDGET)
        val d = bad.decide(j, items[0])
        assertTrue(d.verdict.failure != null)
        assertEquals(Policy.UNUSABLE, d.row.action)
        assertEquals(ResolvedBy.Unusable, d.row.resolvedBy)
        val good = WebVerdict("b", 0.1, 0, 0.0, false, false, null)
        assertEquals(listOf("b", "mon"), WebJudge.order(listOf(d.verdict, good)).map { it.id })
    }
}
