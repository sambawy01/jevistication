package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProbabilityTest {

    @Test
    fun `accepts the endpoints and interior of the unit interval`() {
        assertEquals(0.0, Probability.of(0.0).value)
        assertEquals(1.0, Probability.of(1.0).value)
        assertEquals(0.5, Probability.of(0.5).value)
    }

    @Test
    fun `rejects a value below zero`() {
        assertFailsWith<IllegalArgumentException> { Probability.of(-0.0001) }
    }

    @Test
    fun `rejects a value above one`() {
        assertFailsWith<IllegalArgumentException> { Probability.of(1.0001) }
    }

    @Test
    fun `rejects NaN`() {
        assertFailsWith<IllegalArgumentException> { Probability.of(Double.NaN) }
    }

    @Test
    fun `ofOrNull returns null outside the interval instead of throwing`() {
        assertNull(Probability.ofOrNull(2.0))
        assertNull(Probability.ofOrNull(-1.0))
        assertNull(Probability.ofOrNull(Double.NaN))
        assertEquals(0.25, Probability.ofOrNull(0.25)?.value)
    }
}
