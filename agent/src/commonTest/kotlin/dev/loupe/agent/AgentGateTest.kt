package dev.loupe.agent

import dev.loupe.engine.Decision
import dev.loupe.engine.Extent
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Probability
import dev.loupe.engine.Truncation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentGateTest {
    private fun act(p: Double, label: String = "renewal") = Decision.Act(label, Probability.of(p))

    @Test
    fun `an act above the bar is eligible - and carries what decided it`() {
        val v = AgentGate.consider("item-1", "j-renewal", act(0.93))
        assertTrue(v is GateVerdict.Eligible, "got $v")
        assertEquals("item-1", v.evidence.itemId)
        assertEquals("j-renewal", v.evidence.judgmentId)
        assertEquals("renewal", v.evidence.label)
        assertEquals(AgentGate.DEFAULT_BAR.value, v.evidence.bar.value)
    }

    @Test
    fun `an abstention never reaches a provider`() {
        val v = AgentGate.consider("i", "j", Decision.Abstain("renewal", Probability.of(0.51)))
        assertTrue(v is GateVerdict.Skipped, "got $v")
        assertTrue(v.reason.contains("unsure"), v.reason)
    }

    @Test
    fun `an unusable answer never reaches a provider`() {
        val v = AgentGate.consider("i", "j", Decision.Unusable("masses did not sum to 1", FailurePosture.LOUD))
        assertTrue(v is GateVerdict.Skipped, "got $v")
        assertTrue(v.reason.contains("could not use"), v.reason)
    }

    @Test
    fun `an act below the bar is skipped even though the engine acted`() {
        // The engine's own threshold is lower: it acts to show you something, the agent acts to
        // spend money and send text off the device, so it holds a higher bar.
        val v = AgentGate.consider("i", "j", act(0.72))
        assertTrue(v is GateVerdict.Skipped, "got $v")
        assertTrue(v.reason.contains("below the agent's bar"), v.reason)
    }

    @Test
    fun `a cut input is skipped - because part of an item is not the item`() {
        val cut = Truncation(textBudget = Extent(kept = 2_000, total = 9_000, unit = Extent.Measure.CHARACTERS))
        val v = AgentGate.consider("i", "j", act(0.99), truncation = cut)
        assertTrue(v is GateVerdict.Skipped, "got $v")
        assertTrue(v.reason.contains("only part"), v.reason)
    }

    @Test
    fun `no caller can lower the bar below the floor`() {
        val v = AgentGate.consider("i", "j", act(0.30), bar = Probability.of(0.01))
        assertTrue(v is GateVerdict.Skipped, "a bar of 0.01 must be raised to the floor, got $v")

        val ok = AgentGate.consider("i", "j", act(0.65), bar = Probability.of(0.01))
        assertTrue(ok is GateVerdict.Eligible, "got $ok")
        assertEquals(AgentGate.FLOOR.value, ok.evidence.bar.value, "the floor must be the recorded bar")
    }

    @Test
    fun `sifting a batch counts what never left the device`() {
        val items = buildList {
            add(GateInput("a", "j", act(0.95)))
            add(GateInput("b", "j", act(0.91)))
            repeat(8) { add(GateInput("skip-$it", "j", Decision.Abstain("x", Probability.of(0.4)))) }
        }
        val r = AgentGate.sift(items)
        assertEquals(2, r.eligible.size)
        assertEquals(8, r.skipped.size)
        assertEquals(10, r.considered)
        assertEquals("2 of 10 items reached your provider; 8 never left this device", r.summary)
    }

    @Test
    fun `the evidence line names the judgment - the answer and both numbers`() {
        val v = AgentGate.consider("i", "j-phishing", act(0.93, "phishing")) as GateVerdict.Eligible
        assertEquals("j-phishing answered \"phishing\" at 93% (the agent's bar is 80%)", v.evidence.line)
    }
}
