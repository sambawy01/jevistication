package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JudgmentTest {

    private val receipt = Judgment.Choice(
        id = "is-receipt",
        question = "Is this a receipt?",
        candidates = listOf("yes", "no"),
    )

    @Test
    fun `validate turns a well-formed response into a distribution`() {
        val d = receipt.validate(mapOf("yes" to 0.8, "no" to 0.2))
        assertEquals("yes", d.argmax)
        assertEquals(0.8, d["yes"]?.value)
    }

    @Test
    fun `validate rejects an unknown candidate label`() {
        assertFailsWith<IllegalArgumentException> {
            receipt.validate(mapOf("yes" to 0.5, "no" to 0.3, "maybe" to 0.2))
        }
    }

    @Test
    fun `validate rejects a response missing a candidate`() {
        assertFailsWith<IllegalArgumentException> { receipt.validate(mapOf("yes" to 1.0)) }
    }

    @Test
    fun `validate rejects masses that do not normalise`() {
        assertFailsWith<IllegalArgumentException> {
            receipt.validate(mapOf("yes" to 0.5, "no" to 0.2))
        }
    }

    @Test
    fun `validate rejects a mass outside the unit interval`() {
        assertFailsWith<IllegalArgumentException> {
            receipt.validate(mapOf("yes" to 1.4, "no" to -0.4))
        }
    }

    @Test
    fun `Choice requires at least two candidates`() {
        assertFailsWith<IllegalArgumentException> {
            Judgment.Choice("x", "one?", listOf("only"))
        }
    }

    @Test
    fun `Choice requires distinct candidates`() {
        assertFailsWith<IllegalArgumentException> {
            Judgment.Choice("x", "dup?", listOf("a", "a"))
        }
    }

    @Test
    fun `Choice requires a non-blank id`() {
        assertFailsWith<IllegalArgumentException> {
            Judgment.Choice("  ", "blank?", listOf("a", "b"))
        }
    }
}
