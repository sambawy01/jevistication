package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TruncationTest {

    private fun receipt(posture: FailurePosture = FailurePosture.NULL_ACTION) =
        Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"), posture)

    private val sure = Backend.ofMasses { _, _ -> mapOf("yes" to 0.9, "no" to 0.1) }

    /** A backend whose context holds [limit] "tokens" (characters, here) and says so when it cuts. */
    private fun limited(limit: Int) = Backend { _, state ->
        val n = state.text.length
        Scored(mapOf("yes" to 0.9, "no" to 0.1), if (n > limit) Extent(limit, n, Extent.Measure.TOKENS) else null)
    }

    @Test
    fun `a short item reaches the ledger known whole - and its decision is unchanged`() {
        val outcome = DecisionEngine(sure, Probability.of(0.5)).decide(receipt(), Item("a", "TOTAL 12.40"))
        assertEquals(Truncation.NONE, outcome.row.truncation)
        assertFalse(outcome.row.truncated)
        assertEquals(Decision.Act("yes", Probability.of(0.9)), outcome.decision)
    }

    @Test
    fun `an over-length item reaches the ledger marked cut by the text budget`() {
        val engine = DecisionEngine(sure, Probability.of(0.5), stateBudget = 100)
        val outcome = engine.decide(receipt(FailurePosture.OPEN), Item("long", "x".repeat(500)))
        val cut = outcome.row.truncation!!.textBudget!!
        assertEquals(Extent(100 - TextState.TRUNCATION_MARKER.length, 500, Extent.Measure.CHARACTERS), cut)
        assertNull(outcome.row.truncation!!.modelContext)
        assertTrue(outcome.row.truncated)
    }

    @Test
    fun `a model-context cut is carried from the backend onto the row`() {
        val outcome = DecisionEngine(limited(50), Probability.of(0.5)).decide(receipt(), Item("d", "y".repeat(80)))
        assertEquals(Truncation(modelContext = Extent(50, 80, Extent.Measure.TOKENS)), outcome.truncation)
    }

    @Test
    fun `both cuts are recorded separately`() {
        val engine = DecisionEngine(limited(50), Probability.of(0.5), stateBudget = 100)
        val t = engine.decide(receipt(), Item("d", "z".repeat(300))).row.truncation!!
        assertEquals(300, t.textBudget!!.total)
        assertEquals(Extent(50, 100, Extent.Measure.TOKENS), t.modelContext)
    }

    @Test
    fun `a cut input does not act under NULL_ACTION or LOUD - it queues - deterministically`() {
        for (posture in listOf(FailurePosture.NULL_ACTION, FailurePosture.LOUD)) {
            val engine = DecisionEngine(sure, Probability.of(0.5), stateBudget = 100, exploration = 1.0)
            val outcome = engine.decide(receipt(posture), Item("long", "x".repeat(500)))
            val abstain = assertIs<Decision.Abstain>(outcome.decision)
            assertEquals("yes", abstain.topLabel)
            assertEquals(outcome.row.truncation, abstain.inputCut)
            assertEquals(Policy.ABSTAIN, outcome.row.action)
            assertEquals(1.0, outcome.row.propensity.value)
        }
    }

    @Test
    fun `a cut input under OPEN still acts - and the cut stays visible`() {
        val outcome = DecisionEngine(sure, Probability.of(0.5), stateBudget = 100)
            .decide(receipt(FailurePosture.OPEN), Item("long", "x".repeat(500)))
        assertIs<Decision.Act>(outcome.decision)
        assertTrue(outcome.row.truncated)
    }

    @Test
    fun `onCutInput leaves uncut and unknown inputs - and abstentions - alone`() {
        val act = Decision.Act("yes", Probability.of(0.9))
        val cut = Truncation(textBudget = Extent(1, 2, Extent.Measure.CHARACTERS))
        assertSame(act, Policy.onCutInput(act, null, FailurePosture.NULL_ACTION))
        assertSame(act, Policy.onCutInput(act, Truncation.NONE, FailurePosture.LOUD))
        val abstain = Decision.Abstain("yes", Probability.of(0.3))
        assertSame(abstain, Policy.onCutInput(abstain, cut, FailurePosture.NULL_ACTION))
    }

    @Test
    fun `mechanical rows carry no truncation record`() {
        val outcome = DecisionEngine(sure, Probability.of(0.5), stateBudget = 10)
            .decide(receipt(), Item("m", "x".repeat(50))) { Mechanical.Resolved("yes", "exact-duplicate") }
        assertNull(outcome.row.truncation)
    }

    @Test
    fun `a row built without the field is unknown - reading as not truncated`() {
        val row = LedgerRow("j", "h", Distribution.of("yes" to 1.0, "no" to 0.0), "yes", Probability.of(1.0))
        assertNull(row.truncation)
        assertFalse(row.truncated)
    }

    @Test
    fun `an extent must record a real cut - in the right unit`() {
        assertFailsWith<IllegalArgumentException> { Extent(5, 5, Extent.Measure.TOKENS) }
        assertFailsWith<IllegalArgumentException> { Truncation(textBudget = Extent(1, 2, Extent.Measure.TOKENS)) }
    }

    @Test
    fun `truncation is exported only when known`() {
        val base = LedgerRow("j", "h", Distribution.of("yes" to 1.0, "no" to 0.0), "yes", Probability.of(1.0))
        assertFalse(Export.ledgerToJsonl(listOf(base)).contains("truncation"))
        assertTrue(Export.ledgerToJsonl(listOf(base.copy(truncation = Truncation.NONE))).contains("\"truncation\":{}"))
        val cut = base.copy(truncation = Truncation(Extent(3, 9, Extent.Measure.CHARACTERS), Extent(2, 4, Extent.Measure.TOKENS)))
        assertTrue(
            Export.ledgerToJsonl(listOf(cut)).contains(
                "\"truncation\":{\"textBudget\":{\"kept\":3,\"total\":9,\"unit\":\"chars\"}," +
                    "\"modelContext\":{\"kept\":2,\"total\":4,\"unit\":\"tokens\"}}",
            ),
        )
    }
}
