package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Per-option descriptions on a Choice, and the option-criteria cut the backend reports. */
class OptionCriteriaTest {

    private val bare = Judgment.Choice("r", "Is this a receipt?", listOf("receipt", "not a receipt"))
    private val described = bare.copy(descriptions = mapOf("receipt" to "a payee, an amount paid, a date"))

    @Test
    fun `descriptions are validated and only enter the hash when present`() {
        assertEquals(bare.criteriaHash, bare.copy(descriptions = emptyMap()).criteriaHash)
        assertNotEquals(bare.criteriaHash, described.criteriaHash)
        assertNotEquals(described.criteriaHash, bare.copy(descriptions = mapOf("receipt" to "other")).criteriaHash)
        assertEquals(listOf("a payee, an amount paid, a date", null), described.descriptionList)
        assertFailsWith<IllegalArgumentException> { bare.copy(descriptions = mapOf("elsewhere" to "x")) }
        assertFailsWith<IllegalArgumentException> { bare.copy(descriptions = mapOf("receipt" to " ")) }
    }

    @Test
    fun `an option-criteria cut is recorded but does not stop the judgment acting`() {
        val cut = Extent(40, 60, Extent.Measure.TOKENS)
        val backend = Backend { j, _ -> Scored(mapOf(j.candidates[0] to 0.95, j.candidates[1] to 0.05), optionCriteria = cut) }
        val outcome = DecisionEngine(backend, Probability.of(0.5)).decide(described, Item("i", "TOTAL PAID 12.45"))
        assertIs<Decision.Act>(outcome.decision)
        assertEquals(cut, outcome.row.truncation?.optionCriteria)
        assertFalse(outcome.row.truncation!!.isCut)
        assertTrue(outcome.row.truncation!!.criteriaCut)
        assertFalse(outcome.row.truncated)
        assertTrue("\"optionCriteria\":{\"kept\":40,\"total\":60,\"unit\":\"tokens\"}" in Export.ledgerToJsonl(listOf(outcome.row)))
        assertFailsWith<IllegalArgumentException> { Truncation(optionCriteria = Extent(1, 2, Extent.Measure.CHARACTERS)) }
    }

    @Test
    fun `the judgment export names the option criteria only when the model reads them`() {
        val plain = Export.judgmentsToJson(listOf(JudgmentDefinition(bare, "i", "b", "l")))
        assertFalse("optionCriteria" in plain)
        val shown = Export.judgmentsToJson(listOf(JudgmentDefinition(described, "i", "b", "l")))
        assertTrue("\"optionCriteria\":{\"receipt\":\"a payee, an amount paid, a date\"}" in shown, shown)
    }
}
