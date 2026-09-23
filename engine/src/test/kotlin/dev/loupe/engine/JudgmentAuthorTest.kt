package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JudgmentAuthorTest {

    private fun rulesFor(question: String) = JudgmentLint.check(question).map { it.rule }.toSet()

    @Test
    fun `accepts a plain decidable question`() {
        assertTrue(JudgmentLint.check("Is this a receipt?").isEmpty())
        assertTrue(JudgmentLint.check("Does this need a reply?").isEmpty())
    }

    @Test
    fun `rejects a numeric rating scale`() {
        assertTrue("rating-scale" in rulesFor("Rate the urgency 1-10"))
        assertTrue("rating-scale" in rulesFor("Score this out of 100"))
        assertTrue("rating-scale" in rulesFor("On a scale, how urgent is this?"))
    }

    @Test
    fun `rejects asking for an explanation`() {
        assertTrue("asks-for-prose" in rulesFor("Is this urgent, and explain why"))
        assertTrue("asks-for-prose" in rulesFor("Describe this document"))
        assertTrue("asks-for-prose" in rulesFor("Summarise the attachment"))
    }

    @Test
    fun `rejects asking the engine to produce text`() {
        assertTrue("asks-to-write" in rulesFor("Write a reply to this"))
        assertTrue("asks-to-write" in rulesFor("Draft a response"))
    }

    @Test
    fun `rejects two questions in one`() {
        assertTrue("multiple-questions" in rulesFor("Is this a receipt? Is it a duplicate?"))
    }

    @Test
    fun `rejects an empty question`() {
        assertTrue("empty" in rulesFor("   "))
    }

    @Test
    fun `infers yes-no candidates for a yes-no question`() {
        val result = JudgmentAuthor.compile("r", "Is this a receipt?")
        val compiled = assertIs<AuthorResult.Compiled>(result)
        assertEquals(listOf("yes", "no"), compiled.judgment.candidates)
    }

    @Test
    fun `requires explicit candidates when the question is not yes-no`() {
        val result = JudgmentAuthor.compile("k", "Which category does this belong to?")
        val rejected = assertIs<AuthorResult.Rejected>(result)
        assertTrue(rejected.findings.any { it.rule == "no-candidates" })
    }

    @Test
    fun `accepts a non-yes-no question when candidates are supplied`() {
        val result = JudgmentAuthor.compile(
            "k",
            "Which category does this belong to?",
            candidates = listOf("bill", "receipt", "statement"),
        )
        val compiled = assertIs<AuthorResult.Compiled>(result)
        assertEquals(3, compiled.judgment.candidates.size)
    }

    @Test
    fun `reports invalid candidates rather than throwing`() {
        val result = JudgmentAuthor.compile("k", "Is this a receipt?", candidates = listOf("only"))
        val rejected = assertIs<AuthorResult.Rejected>(result)
        assertTrue(rejected.findings.any { it.rule == "invalid-candidates" })
    }

    @Test
    fun `a compiled judgment is immediately usable by the engine`() {
        // C2's acceptance: someone who has not read the source gets a working scored classifier.
        val compiled = assertIs<AuthorResult.Compiled>(
            JudgmentAuthor.compile("is-urgent", "Is this urgent?"),
        )
        val engine = DecisionEngine(
            backend = Backend.ofMasses { _, _ -> mapOf("yes" to 0.9, "no" to 0.1) },
            threshold = Probability.of(0.7),
        )
        val outcome = engine.decide(compiled.judgment, Item("m1", "server is down"))
        assertEquals("yes", (outcome.decision as Decision.Act).label)
    }
}
