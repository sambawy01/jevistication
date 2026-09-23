package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedDeciderTest {

    @Test
    fun theSimulationNeverWaitsAndADeliveredDecisionLandsOnce() {
        val decider = HostedDecider(BaselinePilot())
        val session = GameSession(1, Control.Piloted(decider), clock = { 0L })
        session.tick()
        assertTrue(decider.busy)
        val observation = assertNotNull(decider.take())
        assertNull(decider.take(), "an observation is handed out once")
        repeat(20) { session.tick() } // nothing delivered: the world keeps moving, holding course
        assertNull(session.current)
        assertEquals(21L, session.world.tick)
        decider.deliver(decider.decide(observation))
        session.tick()
        assertEquals(DecisionSource.BASELINE, session.current?.source)
        assertNotNull(decider.take(), "the next request goes out as soon as one lands and one is due")
    }

    @Test
    fun aThrowingPilotBecomesAFailureHoldAndLateDeliveriesAreDropped() {
        val boom = object : Pilot {
            override val name = "boom"
            override fun decide(observation: Observation): PilotDecision = error("no model")
        }
        val d = HostedDecider(boom)
        val s = GameSession(2, Control.Piloted(d), clock = { 0L })
        s.tick()
        val o = assertNotNull(d.take())
        val failed = d.decide(o)
        assertEquals(DecisionSource.FAILURE, failed.source)
        assertEquals(Action.HOLD, failed.action)
        d.close()
        d.deliver(failed)
        s.tick()
        assertNull(s.current, "a decision delivered after close must not land")
    }
}
