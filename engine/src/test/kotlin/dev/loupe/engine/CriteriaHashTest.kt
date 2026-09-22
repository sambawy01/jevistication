package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CriteriaHashTest {

    @Test
    fun `identical wording produces an identical hash`() {
        val a = Judgment.Choice("id", "Is this urgent?", listOf("yes", "no"))
        val b = Judgment.Choice("other-id", "Is this urgent?", listOf("yes", "no"))
        assertEquals(a.criteriaHash, b.criteriaHash)
    }

    @Test
    fun `changing the question changes the hash`() {
        val a = Judgment.Choice("id", "Is this urgent?", listOf("yes", "no"))
        val b = Judgment.Choice("id", "Is this a receipt?", listOf("yes", "no"))
        assertNotEquals(a.criteriaHash, b.criteriaHash)
    }

    @Test
    fun `changing the candidates changes the hash`() {
        val a = Judgment.Choice("id", "Pick one", listOf("a", "b"))
        val b = Judgment.Choice("id", "Pick one", listOf("a", "b", "c"))
        assertNotEquals(a.criteriaHash, b.criteriaHash)
    }
}
