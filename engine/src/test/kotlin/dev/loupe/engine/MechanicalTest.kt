package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MechanicalTest {

    @Test
    fun `tracks the share of items resolved without the model`() {
        val stats = MechanicalStats()
        stats.record(Mechanical.Resolved(true, by = "hash"))
        stats.record(Mechanical.Resolved(true, by = "mime"))
        stats.record(Mechanical.Deferred)
        stats.record(Mechanical.Deferred)

        assertEquals(4, stats.total)
        assertEquals(2, stats.resolved)
        assertEquals(2, stats.deferred)
        assertEquals(0.5, stats.resolvedShare)
    }

    @Test
    fun `breaks the resolved count down by check`() {
        val stats = MechanicalStats()
        stats.record(Mechanical.Resolved(1, by = "hash"))
        stats.record(Mechanical.Resolved(2, by = "hash"))
        stats.record(Mechanical.Resolved(3, by = "domain"))
        stats.record(Mechanical.Deferred)

        assertEquals(mapOf("hash" to 2, "domain" to 1), stats.byCheck())
    }

    @Test
    fun `an empty tracker reports a zero share rather than dividing by zero`() {
        val stats = MechanicalStats()
        assertEquals(0, stats.total)
        assertEquals(0.0, stats.resolvedShare)
    }

    @Test
    fun `record returns the outcome so it can be used inline`() {
        val stats = MechanicalStats()
        val outcome = stats.record(Mechanical.Resolved("receipt", by = "regex"))
        assertEquals(Mechanical.Resolved("receipt", "regex"), outcome)
        assertEquals(1, stats.resolved)
    }
}
