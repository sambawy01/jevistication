package dev.loupe.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DecisionEngineTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    private fun backendSaying(yes: Double) =
        Backend.ofMasses { _, _ -> mapOf("yes" to yes, "no" to 1.0 - yes) }

    @Test
    fun `decides end to end and writes a ledger row carrying a propensity`() {
        val engine = DecisionEngine(backendSaying(0.95), threshold = Probability.of(0.8))
        val outcome = engine.decide(receipt, Item("photo-1", "TOTAL 12.40 VAT 2.07"))

        val act = assertIs<Decision.Act>(outcome.decision)
        assertEquals("yes", act.label)
        assertFalse(outcome.resolvedMechanically)

        assertEquals(1, engine.ledger.size)
        val row = engine.ledger.rows().single()
        assertEquals("is-receipt", row.judgmentId)
        assertEquals(receipt.criteriaHash, row.criteriaHash)
        assertEquals("yes", row.action)
        assertEquals(1.0, row.propensity.value) // deterministic engine
        assertEquals(0.95, row.distribution.getValue("yes").value)
    }

    @Test
    fun `abstains below the threshold and still logs the row`() {
        val engine = DecisionEngine(backendSaying(0.55), threshold = Probability.of(0.9))
        val outcome = engine.decide(receipt, Item("photo-2", "blurry"))

        assertIs<Decision.Abstain>(outcome.decision)
        assertEquals(Policy.ABSTAIN, engine.ledger.rows().single().action)
        assertEquals(1, engine.ledger.size)
    }

    @Test
    fun `a mechanical answer never reaches the model`() {
        var consulted = false
        val backend = Backend.ofMasses { _, _ -> consulted = true; mapOf("yes" to 0.5, "no" to 0.5) }
        val engine = DecisionEngine(backend, threshold = Probability.of(0.5))

        val outcome = engine.decide(receipt, Item("dup-1", "anything")) {
            Mechanical.Resolved("no", by = "hash-duplicate")
        }

        assertFalse(consulted, "a mechanical answer must not consult the model")
        assertTrue(outcome.resolvedMechanically)
        assertEquals("no", (outcome.decision as Decision.Act).label)
        assertEquals(1.0, engine.ledger.rows().single().propensity.value)
        assertEquals(1.0, engine.ledger.rows().single().distribution.getValue("no").value)
    }

    @Test
    fun `tracks the share resolved without the model`() {
        val engine = DecisionEngine(backendSaying(0.9), threshold = Probability.of(0.5))
        engine.decide(receipt, Item("a", "x")) { Mechanical.Resolved("yes", by = "hash") }
        engine.decide(receipt, Item("b", "x"))
        engine.decide(receipt, Item("c", "x")) { Mechanical.Resolved("no", by = "mime") }
        engine.decide(receipt, Item("d", "x"))

        assertEquals(4, engine.stats.total)
        assertEquals(0.5, engine.stats.resolvedShare)
        assertEquals(mapOf("hash" to 1, "mime" to 1), engine.stats.byCheck())
    }

    @Test
    fun `rejects a mechanical answer that is not a candidate`() {
        val engine = DecisionEngine(backendSaying(0.9), threshold = Probability.of(0.5))
        assertFailsWith<IllegalArgumentException> {
            engine.decide(receipt, Item("a", "x")) { Mechanical.Resolved("maybe", by = "bogus") }
        }
    }

    @Test
    fun `contains a malformed backend response at the A4 boundary`() {
        // A4 requires that a malformed response never throws: it is caught at the boundary and
        // becomes an unusable decision, so one bad answer cannot abort a sweep over a library.
        val engine = DecisionEngine(
            Backend.ofMasses { _, _ -> mapOf("yes" to 0.5, "no" to 0.2) }, // does not normalise
            threshold = Probability.of(0.5),
        )
        assertIs<Decision.Unusable>(engine.decide(receipt, Item("a", "x")).decision)
    }

    @Test
    fun `contains a backend naming an unknown candidate`() {
        val engine = DecisionEngine(
            Backend.ofMasses { _, _ -> mapOf("yes" to 0.4, "no" to 0.3, "perhaps" to 0.3) },
            threshold = Probability.of(0.5),
        )
        val decision = engine.decide(receipt, Item("a", "x")).decision
        assertTrue(assertIs<Decision.Unusable>(decision).reason.contains("perhaps"))
    }

    @Test
    fun `calibration is applied before the threshold is read`() {
        // Raw 0.95 would clear a 0.8 threshold; softened to about 0.62 it must not.
        val engine = DecisionEngine(
            backend = backendSaying(0.95),
            threshold = Probability.of(0.8),
            recalibrator = TemperatureScaling.of(4.0),
        )
        assertIs<Decision.Abstain>(engine.decide(receipt, Item("a", "x")).decision)
    }

    @Test
    fun `the state budget truncates an oversized item rather than rewriting it`() {
        var seen: TextState? = null
        val backend = Backend.ofMasses { _, state -> seen = state; mapOf("yes" to 0.9, "no" to 0.1) }
        val engine = DecisionEngine(backend, threshold = Probability.of(0.5), stateBudget = 50)

        engine.decide(receipt, Item("big", "x".repeat(500)))

        val item = seen!!.items.single()
        assertEquals(Fit.TRUNCATED, item.fit)
        assertTrue(item.text.endsWith(TextState.TRUNCATION_MARKER))
        assertTrue(item.text.length <= 50)
    }

    @Test
    fun `exploration produces propensity variation the counterfactual engine can use`() {
        val engine = DecisionEngine(
            backend = backendSaying(0.9),
            threshold = Probability.of(0.5),
            exploration = 0.3,
            random = Random(1),
        )
        repeat(200) { engine.decide(receipt, Item("item-$it", "x")) }

        val propensities = engine.ledger.rows().map { it.propensity.value }.toSet()
        assertTrue(propensities.size > 1, "exploration must vary the logged propensity")
        assertTrue(propensities.all { it > 0.0 }, "no action may be logged with zero propensity")
        // Every logged propensity is a real probability of the action actually taken.
        assertTrue(propensities.all { it <= 1.0 })
    }

    @Test
    fun `an explored ledger supports an off-policy replay`() {
        val engine = DecisionEngine(
            backend = backendSaying(0.85),
            threshold = Probability.of(0.5),
            exploration = 0.4,
            random = Random(7),
        )
        repeat(300) { engine.decide(receipt, Item("item-$it", "x")) }

        val replay = OffPolicy.replayThreshold(
            rows = engine.ledger.rows(),
            threshold = Probability.of(0.5),
            reward = { if (it.action == "yes") 1.0 else 0.0 },
        )
        assertTrue(replay.supportingRows > 0, "an explored ledger should support a replay")
        assertTrue(replay.deltaLower <= replay.delta && replay.delta <= replay.deltaUpper)
    }

    @Test
    fun `rejects an exploration rate outside the unit interval`() {
        assertFailsWith<IllegalArgumentException> {
            DecisionEngine(backendSaying(0.9), Probability.of(0.5), exploration = 1.5)
        }
    }
}
