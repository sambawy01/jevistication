package dev.loupe.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailurePostureTest {

    private fun judgment(posture: FailurePosture = FailurePosture.NULL_ACTION) =
        Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"), posture)

    private fun engineReturning(response: Map<String, Double>, posture: FailurePosture) =
        DecisionEngine(Backend.ofMasses { _, _ -> response }, Probability.of(0.5)) to judgment(posture)

    @Test
    fun `a malformed response yields an unusable decision instead of throwing`() {
        val (engine, j) = engineReturning(mapOf("yes" to 0.5, "no" to 0.2), FailurePosture.NULL_ACTION)
        val outcome = engine.decide(j, Item("a", "x"))

        val unusable = assertIs<Decision.Unusable>(outcome.decision)
        assertEquals(FailurePosture.NULL_ACTION, unusable.posture)
        assertTrue(unusable.reason.isNotBlank())
    }

    @Test
    fun `an unusable response still writes a ledger row - marked as a failure`() {
        val (engine, j) = engineReturning(mapOf("bogus" to 1.0), FailurePosture.NULL_ACTION)
        engine.decide(j, Item("a", "x"))

        val row = engine.ledger.rows().single()
        assertEquals(Policy.UNUSABLE, row.action)
        assertNotNull(row.failure)
        // The recorded distribution is flat: the honest shape of having no view at all.
        assertEquals(0.5, row.distribution.getValue("yes").value, 1e-9)
    }

    @Test
    fun `the judgment's declared posture is carried - not the caller's preference`() {
        for (posture in FailurePosture.entries) {
            val (engine, j) = engineReturning(mapOf("yes" to 2.0, "no" to -1.0), posture)
            val decision = engine.decide(j, Item("a", "x")).decision
            assertEquals(posture, assertIs<Decision.Unusable>(decision).posture)
        }
    }

    @Test
    fun `a backend that throws is contained rather than propagated`() {
        val engine = DecisionEngine(
            Backend.ofMasses { _, _ -> throw IllegalStateException("model file truncated") },
            Probability.of(0.5),
        )
        val decision = engine.decide(judgment(), Item("a", "x")).decision
        assertTrue(assertIs<Decision.Unusable>(decision).reason.contains("truncated"))
    }

    @Test
    fun `fuzzed malformed responses never throw`() {
        val random = Random(20260922)
        val labels = listOf("yes", "no", "maybe", "", "YES")
        val engine = DecisionEngine(
            Backend.ofMasses { _, _ ->
                buildMap {
                    repeat(random.nextInt(0, 4)) {
                        val value = when (random.nextInt(5)) {
                            0 -> random.nextDouble(-5.0, 5.0)
                            1 -> Double.NaN
                            2 -> Double.POSITIVE_INFINITY
                            3 -> 0.0
                            else -> random.nextDouble()
                        }
                        put(labels[random.nextInt(labels.size)], value)
                    }
                }
            },
            Probability.of(0.5),
        )

        val j = judgment()
        repeat(500) { i ->
            // The assertion is simply that this line never throws, 500 times over.
            engine.decide(j, Item("fuzz-$i", "x"))
        }
        assertEquals(500, engine.ledger.size)
    }

    @Test
    fun `a well-formed response is unaffected by any of this`() {
        val engine = DecisionEngine(Backend.ofMasses { _, _ -> mapOf("yes" to 0.9, "no" to 0.1) }, Probability.of(0.5))
        val outcome = engine.decide(judgment(), Item("a", "x"))
        assertEquals("yes", assertIs<Decision.Act>(outcome.decision).label)
        assertNull(engine.ledger.rows().single().failure)
    }

    @Test
    fun `the expiry judgment declares a loud posture because silence there is the danger`() {
        assertEquals(FailurePosture.LOUD, BuiltInJudgments.EXPIRING.judgment.onFailure)
        assertEquals(FailurePosture.NULL_ACTION, BuiltInJudgments.JUNK.judgment.onFailure)
    }

    @Test
    fun `the harness counts unusable responses as wrong without scoring them as opinions`() {
        val engine = DecisionEngine(Backend.ofMasses { _, _ -> mapOf("yes" to 9.0) }, Probability.of(0.5))
        val fixtures = (0 until 10).map {
            Fixture(Item("f$it", "x"), "yes", "g$it")
        }
        val report = Harness.evaluate(judgment(), fixtures, engine, baseline = { "yes" })

        assertEquals(10, report.n)
        assertEquals(10, report.unusable)
        assertEquals(0.0, report.accuracyAtFullCoverage, 1e-9)
        assertEquals(0.0, report.coverage, 1e-9)
        // The baseline was right every time, so a judgment that cannot answer does not beat it.
        assertEquals(1.0, report.baselineAccuracy, 1e-9)
        assertTrue(!report.beatsBaseline)
    }

    @Test
    fun `the export carries the failure reason`() {
        val (engine, j) = engineReturning(mapOf("yes" to 0.1, "no" to 0.1), FailurePosture.LOUD)
        engine.decide(j, Item("a", "x"))
        assertTrue(Export.ledgerToJsonl(engine.ledger.rows()).contains(""""failure":"""))
    }
}
