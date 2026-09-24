package dev.loupe.kit.settings

import dev.loupe.engine.Backend
import dev.loupe.engine.Judgment
import dev.loupe.engine.Scored
import dev.loupe.engine.TextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `memory_mode` / `idle_unload_min`: full keeps Laya, balanced frees it when idle, low after each run. */
class ModelMemoryTest {
    private var now = 0L
    private var opened = 0
    private var closed = 0
    private var failOpen = false

    private fun model() = object : LoadedModel {
        override val backend = Backend { j, _ -> Scored(mapOf(j.candidates[0] to 0.9, j.candidates[1] to 0.1)) }
        override fun close() { closed++ }
    }

    private fun memory(): ModelMemory {
        val m = ModelMemory({ if (failOpen) null else { opened++; model() } }, { now })
        m.install(model())
        return m
    }

    private val j = Judgment.Choice("q", "Is it?", listOf("yes it is", "no it is not"))
    private fun ask(m: ModelMemory) = m.backend.score(j, TextState.build(listOf("a" to "text"), 100))

    @Test
    fun fullNeverUnloads() {
        val m = memory()
        now = 10 * 60 * 60_000L
        assertFalse(m.maintain("full", 10.0, laneFree = true))
        assertTrue(m.isLoaded)
    }

    @Test
    fun balancedUnloadsAfterIdleMinutesAndReloadsOnUse() {
        val m = memory()
        ask(m)
        now += 9 * 60_000L
        assertFalse(m.maintain("balanced", 10.0, laneFree = true))
        now += 60_000L
        assertFalse(m.maintain("balanced", 10.0, laneFree = false), "the game or foreground work holds the lane")
        assertTrue(m.maintain("balanced", 10.0, laneFree = true))
        assertFalse(m.isLoaded)
        assertEquals(1, closed)
        ask(m)
        assertTrue(m.isLoaded)
        assertEquals(1, opened, "the next decision loads it again")
        now += 100 * 60_000L
        assertFalse(m.maintain("balanced", 0.0, laneFree = true), "0 = never")
    }

    @Test
    fun lowUnloadsAfterEachRun() {
        val m = memory()
        ask(m)
        assertTrue(m.afterRun("low", laneFree = true))
        assertFalse(m.afterRun("balanced", laneFree = true))
        ask(m)
        assertFalse(m.afterRun("low", laneFree = false))
        assertTrue(m.maintain("low", 10.0, laneFree = true))
        assertEquals(2, m.unloads)
    }

    @Test
    fun aFailedReloadIsAnErrorInsideScoreAndForgetStopsReloads() {
        val m = memory()
        m.unload()
        failOpen = true
        assertFailsWith<IllegalStateException> { ask(m) }
        failOpen = false
        assertTrue(m.ensureLoaded())
        m.forget()
        assertFailsWith<IllegalStateException> { ask(m) }
        assertFalse(m.ensureLoaded())
    }
}
