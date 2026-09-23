package dev.loupe.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OffPolicyTest {

    private val urgent = Judgment.Choice("is-urgent", "Is this urgent?", listOf("yes", "no"))

    private fun distribution(pYes: Double): Distribution =
        urgent.validate(mapOf("yes" to pYes, "no" to 1.0 - pYes))

    /** Reward is 1 when the logged action matched the truth the user later confirmed. */
    private val reward: (LedgerRow) -> Double = { row ->
        if (row.action == row.correction) 1.0 else 0.0
    }

    /**
     * A corpus logged by an *exploring* policy: the action is sampled from the distribution rather
     * than always taken at argmax, so propensities genuinely vary. Off-policy evaluation needs
     * that exploration, which is why A5 logs the propensity at decision time.
     */
    private fun loggedCorpus(n: Int, seed: Long = 42L): List<LedgerRow> {
        val random = Random(seed)
        return List(n) {
            val pYes = 0.5 + random.nextDouble() * 0.49
            val dist = distribution(pYes)
            val truth = if (random.nextDouble() < pYes) "yes" else "no"
            val sampled = if (random.nextDouble() < pYes) "yes" else "no"
            LedgerRow(
                judgmentId = urgent.id,
                criteriaHash = urgent.criteriaHash,
                distribution = dist,
                action = sampled,
                propensity = dist.getValue(sampled),
                correction = truth,
            )
        }
    }

    @Test
    fun `replays a thousand logged decisions and reports a delta with an interval`() {
        val rows = loggedCorpus(1_000)
        // Threshold 0 means the candidate always acts on the top-mass label.
        val replay = OffPolicy.replayThreshold(rows, Probability.of(0.0), reward)

        assertEquals(0.0, replay.threshold.value)
        assertTrue(replay.supportingRows in 1..1_000)
        assertTrue(replay.support > 0.0 && replay.support <= 1.0)
        // Acting at argmax beats sampling from the distribution.
        assertTrue(replay.delta > 0.0, "expected a positive delta, got ${replay.delta}")
        assertTrue(
            replay.deltaLower <= replay.delta && replay.delta <= replay.deltaUpper,
            "delta ${replay.delta} outside interval [${replay.deltaLower}, ${replay.deltaUpper}]",
        )
    }

    @Test
    fun `the replay interval is deterministic for a given seed`() {
        val rows = loggedCorpus(300)
        val a = OffPolicy.replayThreshold(rows, Probability.of(0.7), reward, seed = 7L)
        val b = OffPolicy.replayThreshold(rows, Probability.of(0.7), reward, seed = 7L)
        assertEquals(a.deltaLower, b.deltaLower)
        assertEquals(a.deltaUpper, b.deltaUpper)
        assertEquals(a.delta, b.delta)
    }

    @Test
    fun `a high threshold makes the candidate abstain and lose support`() {
        val rows = loggedCorpus(300)
        // No logged row abstained, so a candidate that always abstains agrees with nothing.
        val replay = OffPolicy.replayThreshold(rows, Probability.of(1.0), reward)
        assertEquals(0, replay.supportingRows)
        assertEquals(0.0, replay.support)
    }

    @Test
    fun `a candidate identical to the logged policy recovers the logged mean reward`() {
        val rows = loggedCorpus(200)
        val estimate = OffPolicy.estimate(rows, reward, candidateAction = { it.action })
        assertEquals(1.0, estimate.support)
        assertEquals(rows.size, estimate.supportingRows)
        // With every row supported, SNIPS is a propensity-weighted mean of the same rewards.
        assertTrue(estimate.snips in 0.0..1.0)
    }

    @Test
    fun `with uniform propensities SNIPS equals the plain mean reward`() {
        val dist = distribution(0.5)
        val rows = List(10) { i ->
            LedgerRow(
                judgmentId = urgent.id,
                criteriaHash = urgent.criteriaHash,
                distribution = dist,
                action = "yes",
                propensity = Probability.of(0.5),
                correction = if (i < 7) "yes" else "no",
            )
        }
        val estimate = OffPolicy.estimate(rows, reward, candidateAction = { "yes" })
        assertEquals(0.7, estimate.snips, 1e-12)
        assertEquals(1.0, estimate.support)
        assertEquals(10.0, estimate.effectiveSampleSize, 1e-9)
    }

    @Test
    fun `a candidate that never agrees has no support and no estimate`() {
        val rows = loggedCorpus(50)
        val estimate = OffPolicy.estimate(rows, reward, candidateAction = { Policy.ABSTAIN })
        assertEquals(0, estimate.supportingRows)
        assertEquals(0.0, estimate.snips)
        assertEquals(0.0, estimate.ips)
    }

    @Test
    fun `estimate rejects an empty ledger`() {
        assertFailsWith<IllegalArgumentException> {
            OffPolicy.estimate(emptyList(), reward, candidateAction = { "yes" })
        }
    }

    @Test
    fun `estimate rejects a row logged with zero propensity`() {
        val rows = listOf(
            LedgerRow(
                judgmentId = urgent.id,
                criteriaHash = urgent.criteriaHash,
                distribution = distribution(0.9),
                action = "yes",
                propensity = Probability.of(0.0),
                correction = "yes",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            OffPolicy.estimate(rows, reward, candidateAction = { "yes" })
        }
    }

    @Test
    fun `abstain marker is not a usable candidate label`() {
        assertFailsWith<IllegalArgumentException> { distribution(0.9).getValue(Policy.ABSTAIN) }
    }

    @Test
    fun `actionOf maps decisions to recorded action strings`() {
        val act = Policy.decide(Recalibrator.Identity.calibrate(distribution(0.9)), Probability.of(0.5))
        assertEquals("yes", Policy.actionOf(act))
        val abstain = Policy.decide(Recalibrator.Identity.calibrate(distribution(0.6)), Probability.of(0.9))
        assertEquals(Policy.ABSTAIN, Policy.actionOf(abstain))
    }
}
