package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DistributionTest {

    @Test
    fun `builds a valid distribution and exposes its masses`() {
        val d = Distribution.of("yes" to 0.7, "no" to 0.3)
        assertEquals(setOf("yes", "no"), d.labels)
        assertEquals(0.7, d["yes"]?.value)
        assertEquals(0.3, d["no"]?.value)
    }

    @Test
    fun `argmax returns the highest-mass label`() {
        val d = Distribution.of("a" to 0.2, "b" to 0.5, "c" to 0.3)
        assertEquals("b", d.argmax)
    }

    @Test
    fun `argmax breaks ties toward the first label supplied`() {
        val d = Distribution.of("first" to 0.5, "second" to 0.5)
        assertEquals("first", d.argmax)
    }

    @Test
    fun `get returns null for a non-candidate label`() {
        val d = Distribution.of("only" to 1.0)
        assertNull(d["missing"])
    }

    @Test
    fun `rejects an empty distribution`() {
        assertFailsWith<IllegalArgumentException> { Distribution.of(emptyMap()) }
    }

    @Test
    fun `rejects masses that do not sum to one`() {
        assertFailsWith<IllegalArgumentException> { Distribution.of("a" to 0.5, "b" to 0.4) }
    }

    @Test
    fun `rejects a mass outside the unit interval`() {
        assertFailsWith<IllegalArgumentException> { Distribution.of("a" to 1.5, "b" to -0.5) }
    }

    @Test
    fun `rejects a duplicate candidate label`() {
        assertFailsWith<IllegalArgumentException> { Distribution.of("a" to 0.5, "a" to 0.5) }
    }

    @Test
    fun `accepts masses summing to one within tolerance`() {
        // three thirds do not sum to exactly 1.0 in binary floating point
        val d = Distribution.of("a" to 1.0 / 3, "b" to 1.0 / 3, "c" to 1.0 / 3)
        assertEquals("a", d.argmax)
    }
}
