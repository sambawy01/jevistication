package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltInJudgmentsTest {

    @Test
    fun `ships the library the spec names`() {
        assertEquals(7, BuiltInJudgments.ALL.size)
        val ids = BuiltInJudgments.ALL.map { it.judgment.id }
        assertTrue("is-receipt" in ids)
        assertTrue("needs-reply" in ids)
        assertTrue("unsubscribe-candidate" in ids)
        assertTrue("is-duplicate" in ids)
        assertTrue("is-stale" in ids)
        assertTrue("is-expiring-document" in ids)
        assertTrue("is-junk" in ids)
    }

    @Test
    fun `every built-in passes the lint it ships with`() {
        // Construction runs the lint, so this is really asserting the library is authored to the
        // same standard it holds users to.
        for (definition in BuiltInJudgments.ALL) {
            assertTrue(
                JudgmentLint.check(definition.judgment.question).isEmpty(),
                "${definition.judgment.id} fails its own lint",
            )
        }
    }

    @Test
    fun `every built-in is authored to the three-part template`() {
        for (definition in BuiltInJudgments.ALL) {
            assertTrue(definition.invariant.isNotBlank(), "${definition.judgment.id} has no invariant")
            assertTrue(definition.breaks.isNotBlank(), "${definition.judgment.id} has no breaks")
            assertTrue(definition.lookalikes.isNotBlank(), "${definition.judgment.id} has no lookalikes")
        }
    }

    @Test
    fun `built-in ids are unique`() {
        val ids = BuiltInJudgments.ALL.map { it.judgment.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `looks a built-in up by id`() {
        assertNotNull(BuiltInJudgments.byId("is-receipt"))
        assertNull(BuiltInJudgments.byId("not-a-judgment"))
    }

    @Test
    fun `a built-in runs through the engine`() {
        val engine = DecisionEngine(
            backend = Backend { _, _ -> mapOf("yes" to 0.93, "no" to 0.07) },
            threshold = Probability.of(0.8),
        )
        val outcome = engine.decide(
            BuiltInJudgments.RECEIPT.judgment,
            Item("photo-9", "TOTAL 12.40  VAT 2.07  CARD ****1234"),
        )
        assertEquals("yes", (outcome.decision as Decision.Act).label)
        assertEquals("is-receipt", engine.ledger.rows().single().judgmentId)
    }
}
