package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class BoolAndScoreTest {

    // ---- Bool ----

    private val urgent = Judgment.Bool("is-urgent", "Is this urgent?")

    @Test
    fun `a bool judgment validates a yes-no response`() {
        val d = urgent.validate(mapOf("yes" to 0.8, "no" to 0.2))
        assertEquals(0.8, urgent.probabilityOfYes(d).value, 1e-12)
        assertEquals("yes", d.argmax)
    }

    @Test
    fun `a bool judgment rejects an unknown label like any other`() {
        assertFailsWith<IllegalArgumentException> {
            urgent.validate(mapOf("yes" to 0.5, "maybe" to 0.5))
        }
    }

    @Test
    fun `a bool judgment runs through the engine`() {
        val engine = DecisionEngine(
            Backend.ofMasses { _, _ -> mapOf("yes" to 0.91, "no" to 0.09) },
            threshold = Probability.of(0.8),
        )
        val outcome = engine.decide(urgent, Item("m1", "the server is on fire"))
        assertEquals("yes", assertIs<Decision.Act>(outcome.decision).label)
        assertEquals("is-urgent", engine.ledger.rows().single().judgmentId)
    }

    @Test
    fun `a bool judgment carries its failure posture`() {
        val loud = Judgment.Bool("x", "Is this expiring?", FailurePosture.LOUD)
        assertEquals(FailurePosture.LOUD, loud.asChoice.onFailure)
    }

    // ---- Score ----

    private val priority = Judgment.Score("priority", "How much does this matter?", 1..5)

    @Test
    fun `a score judgment offers its range as candidates`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), priority.candidates)
    }

    @Test
    fun `expected value uses the whole distribution, not just the peak`() {
        // Peak is 3, but the mass leans high, so the expected value should exceed 3.
        val d = priority.validate(
            mapOf("1" to 0.0, "2" to 0.1, "3" to 0.4, "4" to 0.3, "5" to 0.2),
        )
        assertEquals(3, priority.mostLikely(d))
        assertEquals(3.6, priority.expectedValue(d), 1e-9)
    }

    @Test
    fun `expected value of a certain answer is that answer`() {
        val d = priority.validate(mapOf("1" to 0.0, "2" to 0.0, "3" to 0.0, "4" to 1.0, "5" to 0.0))
        assertEquals(4.0, priority.expectedValue(d), 1e-12)
    }

    @Test
    fun `a score judgment rejects a value outside its range`() {
        assertFailsWith<IllegalArgumentException> {
            priority.validate(mapOf("1" to 0.5, "9" to 0.5))
        }
    }

    @Test
    fun `a score judgment needs a range with something to choose between`() {
        assertFailsWith<IllegalArgumentException> { Judgment.Score("x", "How much?", 3..3) }
        assertFailsWith<IllegalArgumentException> { Judgment.Score("x", "How much?", IntRange.EMPTY) }
    }

    @Test
    fun `changing the range changes the criteria hash`() {
        val wide = Judgment.Score("priority", "How much does this matter?", 1..10)
        assertNotEquals(priority.criteriaHash, wide.criteriaHash)
    }

    @Test
    fun `a score judgment runs through the engine`() {
        val engine = DecisionEngine(
            Backend.ofMasses { _, _ -> mapOf("1" to 0.0, "2" to 0.0, "3" to 0.05, "4" to 0.15, "5" to 0.8) },
            threshold = Probability.of(0.7),
        )
        val outcome = engine.decide(priority, Item("t1", "tax deadline tomorrow"))
        assertEquals("5", assertIs<Decision.Act>(outcome.decision).label)
    }

    @Test
    fun `a malformed score response is contained like any other`() {
        val engine = DecisionEngine(Backend.ofMasses { _, _ -> mapOf("3" to 2.0) }, Probability.of(0.5))
        assertIs<Decision.Unusable>(engine.decide(priority, Item("t1", "x")).decision)
    }
}
