package dev.loupe.game

import dev.loupe.engine.Backend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PilotTest {

    private fun observation(world: World = World(1).also { it.clearEntities() }): Observation =
        Observation.of(world, Mechanics.legalActions(world))

    /** A backend that puts [mass] on one label and spreads the rest evenly. */
    private fun favouring(label: String, mass: Double = 0.7) = Backend.ofMasses { judgment, _ ->
        val others = judgment.candidates.filter { it != label }
        judgment.candidates.associateWith { if (it == label) mass else (1 - mass) / others.size }
    }

    @Test
    fun `the model is asked over exactly the legal actions, and its raw answer is kept`() {
        var offered: List<String>? = null
        var question: String? = null
        val pilot = ModelPilot(Backend.ofMasses { judgment, state ->
            offered = judgment.candidates
            question = judgment.question
            assertTrue(state.isComplete, "the state must never be cut")
            favouring("steer left and shoot").score(judgment, state).masses
        })
        val o = observation()
        val decision = pilot.decide(o)

        assertEquals(o.legal.actions.map { it.label }, offered)
        assertTrue("hold course" in offered!!, "the explicit no-op must be offered in open water")
        assertEquals(ModelPilot.QUESTION, question)
        assertEquals(DecisionSource.MODEL, decision.source)
        assertEquals(Action.LEFT_FIRE, decision.action)
        val raw = assertNotNull(decision.raw)
        assertEquals(1.0, raw.values.sum(), 1e-9)
        assertEquals(0.7, decision.topProbability!!, 1e-9)
    }

    @Test
    fun `with one legal action the model is not consulted`() {
        val world = World(1)
        world.clearEntities()
        val legal = LegalActions(listOf(Action.RIGHT), Action.entries.filter { it != Action.RIGHT }.associateWith { Exclusion.FATAL })
        var called = false
        val decision = ModelPilot(Backend.ofMasses { _, _ -> called = true; emptyMap() }).decide(Observation.of(world, legal))
        assertEquals(DecisionSource.MECHANICAL, decision.source)
        assertEquals(Action.RIGHT, decision.action)
        assertNull(decision.raw)
        assertTrue(!called, "the backend was consulted for a mechanical decision")
    }

    @Test
    fun `a backend that throws or returns junk yields the null action, never an exception`() {
        val o = observation()
        val junk: List<Pair<String, Backend>> = listOf(
            "throws" to Backend.ofMasses { _, _ -> error("model file truncated") },
            "unknown label" to Backend.ofMasses { j, _ -> j.candidates.associateWith { 0.0 } + ("barrel roll" to 1.0) },
            "missing label" to Backend.ofMasses { j, _ -> mapOf(j.candidates.first() to 1.0) },
            "NaN" to Backend.ofMasses { j, _ -> j.candidates.associateWith { Double.NaN } },
            "negative" to Backend.ofMasses { j, _ -> j.candidates.mapIndexed { i, c -> c to if (i == 0) -1.0 else 2.0 / (j.candidates.size - 1) }.toMap() },
            "does not normalise" to Backend.ofMasses { j, _ -> j.candidates.associateWith { 0.9 } },
            "empty" to Backend.ofMasses { _, _ -> emptyMap() },
        )
        for ((name, backend) in junk) {
            val decision = ModelPilot(backend).decide(o)
            assertEquals(DecisionSource.FAILURE, decision.source, name)
            assertEquals(Action.HOLD, decision.action, name)
            assertNotNull(decision.failure, name)
            assertNull(decision.raw, name)
        }
    }

    @Test
    fun `a whole game flown by a failing model keeps running, with every failure counted`() {
        var calls = 0
        val flaky = Backend.ofMasses { j, s ->
            calls++
            if (calls % 3 == 0) error("intermittent") else favouring("hold course and shoot").score(j, s).masses
        }
        val session = GameSession(4, Control.Piloted(LockstepDecider(ModelPilot(flaky), 3)))
        repeat(2_000) { session.tick() }
        assertTrue(session.stats.count(DecisionSource.FAILURE) > 0)
        assertTrue(session.stats.count(DecisionSource.MODEL) > 0)
        assertTrue(session.world.tick > 0)
    }

    @Test
    fun `a pilot that answers outside its legal set is treated as a failure`() {
        val rogue = object : Pilot {
            override val name = "rogue"
            override fun decide(observation: Observation) = PilotDecision(Action.LEFT, DecisionSource.BASELINE, observedTick = observation.tick)
        }
        val session = GameSession(1, Control.Piloted(LockstepDecider(rogue, 0)), overrideEnabled = false)
        session.world.clearEntities()
        session.world.placePlayer(session.world.river.rowAt(session.world.playerY).left + Rules.PLAYER_W / 2 + 0.05)
        session.tick()
        session.tick()
        val current = assertNotNull(session.current)
        assertEquals(DecisionSource.FAILURE, current.source)
        assertEquals(Action.HOLD, current.action)
    }

    @Test
    fun `a pilot that throws is contained by the decider`() {
        val crashing = object : Pilot {
            override val name = "crashing"
            override fun decide(observation: Observation): PilotDecision = throw IllegalStateException("boom")
        }
        val decider = LockstepDecider(crashing, 0)
        decider.submit(observation())
        val decision = assertNotNull(decider.poll(0))
        assertEquals(DecisionSource.FAILURE, decision.source)
        assertTrue(decision.failure!!.contains("boom"))
    }

    @Test
    fun `below the threshold, control passes to the human for that decision`() {
        // An even split over the legal actions: the top raw mass is 1/n, well under 0.5.
        val unsure = Backend.ofMasses { j, _ -> j.candidates.associateWith { 1.0 / j.candidates.size } }
        val session = GameSession(1, Control.Piloted(LockstepDecider(ModelPilot(unsure), 0)), threshold = 0.5, overrideEnabled = false)
        session.world.clearEntities()
        session.human = HumanInput(right = true)
        val x0 = session.world.playerX
        repeat(3) { session.tick() }
        assertTrue(session.handedOff, "an unsure decision should hand off")
        assertEquals(Action.RIGHT, session.lastFlown)
        assertTrue(session.world.playerX > x0)
        assertTrue(session.stats.handOffs > 0)

        session.threshold = 0.0
        repeat(12) { session.tick() }
        assertTrue(!session.handedOff, "at threshold 0 the model keeps control")
    }

    @Test
    fun `the async decider never blocks the simulation`() {
        val release = CountDownLatch(1)
        val slow = object : Pilot {
            override val name = "slow"
            override fun decide(observation: Observation): PilotDecision {
                release.await(5, TimeUnit.SECONDS)
                return PilotDecision(Action.HOLD_FIRE, DecisionSource.BASELINE, observedTick = observation.tick)
            }
        }
        GameSession(1, Control.Piloted(AsyncDecider(slow))).use { session ->
            val started = System.nanoTime()
            repeat(30) { session.tick() }
            assertTrue(System.nanoTime() - started < 2_000_000_000L, "ticks waited for the pilot")
            assertNull(session.current, "nothing can have landed yet")
            assertEquals(30L, session.world.tick)
            release.countDown()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (session.current == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                session.tick()
            }
            assertEquals(Action.HOLD_FIRE, assertNotNull(session.current).action)
        }
    }

    @Test
    fun `the baseline completes runs and flies a fair distance`() {
        val settings = Match.Settings(maxTicks = 120 * Rules.TICK_HZ)
        val episodes = Match.run((1L..6L).toList(), listOf(BaselinePilot()), settings)
        episodes.forEach { assertTrue(it.ticks > 0) }
        assertTrue(episodes.map { it.rows }.average() > 300, Match.report(episodes, settings))
        assertTrue(episodes.any { it.death == null }, "no baseline run survived 120 s:\n" + Match.report(episodes, settings))
        assertTrue(episodes.sumOf { it.kills } > 0)
    }

    @Test
    fun `the match reports the same result twice`() {
        val settings = Match.Settings(maxTicks = 30 * Rules.TICK_HZ)
        val once = Match.run(listOf(3L), listOf(BaselinePilot()), settings).single()
        val twice = Match.run(listOf(3L), listOf(BaselinePilot()), settings).single()
        assertEquals(once.copy(latencyP50Ms = null, latencyP95Ms = null), twice.copy(latencyP50Ms = null, latencyP95Ms = null))
    }

    @Test
    fun `human input maps to actions`() {
        assertEquals(Action.HOLD, HumanInput().action)
        assertEquals(Action.LEFT_FIRE, HumanInput(left = true, fire = true).action)
        assertEquals(Action.HOLD, HumanInput(left = true, right = true).action)
        assertIs<Control.Human>(GameSession(1, Control.Human).control)
    }
}
