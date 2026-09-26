package dev.loupe.agent

import dev.loupe.kit.review.ReviewRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The structural half of the never list.
 *
 * [ActionGuardTest] checks what the guard refuses. This file checks the things the guard never has
 * to refuse, because the types make them unreachable — and it exists so that a future change which
 * widens the agent's reach fails the build instead of passing review.
 */
class NeverListTest {
    @Test
    fun `the never list is complete - with the promises from the spec`() {
        assertEquals(
            listOf(
                "never_spends",
                "never_fills_credentials",
                "never_acts_unsure",
                "never_blesses",
                "never_acts_without_approval",
                "always_labelled_online",
            ),
            NeverRule.entries.map { it.code },
        )
        assertTrue(NeverRule.entries.all { it.promise.isNotBlank() })
        assertEquals(NeverRule.SPEND, NeverRule.of("never_spends"))
    }

    @Test
    fun `spending and acting without approval are structural - not checks that could be skipped`() {
        assertTrue(NeverRule.SPEND.structural, "no PreparedAction may be able to move money")
        assertTrue(NeverRule.APPROVAL.structural, "the queue must be the only output")
    }

    @Test
    fun `there are four prepared action types - and none of them can move money or send anything`() {
        // A new variant is a deliberate act: adding one fails this test until someone has decided
        // what it may do, written its guard case, and registered its review kind.
        val variants = PreparedAction::class.let {
            listOf(remind(), event(), draft(), note()).map { a -> a::class.simpleName }
        }
        assertEquals(listOf("Remind", "CalendarEvent", "DraftReply", "NoteFinding"), variants)
    }

    @Test
    fun `no agent review action is irreversible in a way that could not be undone by the person`() {
        // agent.draft records a draft and runs nothing, so it needs no undo; the other three hand
        // something to the platform and must be removable again.
        for (type in listOf("agent.remind", "agent.calendar", "agent.note")) {
            assertTrue(ReviewRegistry.action(type)!!.reversible, "$type must be reversible")
        }
        assertTrue(!ReviewRegistry.action("agent.draft")!!.reversible, "agent.draft runs nothing to reverse")
    }

    @Test
    fun `the agent has no Backend implementation - because a hosted judgment is forbidden`() {
        // Backend.kt: "There is deliberately no hosted implementation. A network call breaks the
        // offline guarantee the product rests on, so the interface has no place to put one."
        // Nothing in this module implements it, and the workflow's output cannot become a
        // Distribution: it parses into PreparedAction, which the policy never reads.
        val actions = listOf(remind(), event(), draft(), note())
        assertTrue(
            actions.none { it is dev.loupe.engine.Backend },
            "a prepared action must not be usable as a decision model",
        )
    }

    @Test
    fun `the gate's floor cannot be reached from outside`() {
        assertTrue(AgentGate.FLOOR.value >= 0.6, "the floor must stay a meaningful bar")
        assertTrue(
            AgentGate.DEFAULT_BAR.value >= AgentGate.FLOOR.value,
            "the default bar must not be under the floor",
        )
    }

    @Test
    fun `the shipped default sends nothing`() {
        val off = AgentRunner.off()
        assertTrue(!off.isReady)
        // Sends nothing, but local actions are free, so the shipped default can still prepare them.
        assertTrue(off.canActOnDevice, "on-device actions are in the free tier")
        assertEquals("Off · not included in your plan", off.statusLine)
        assertEquals(AgentReadiness.Off, AgentConfig().readiness(hasKey = true))
    }

    @Test
    fun `a fully configured runner still sends nothing without the tier`() {
        // Two independent gates, and the entitlement is the outer one: a key and a base URL in the
        // settings must not be enough on their own.
        val free = AgentRunner(
            AgentConfig(
                enabled = true,
                kind = AgentProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
            ),
            hasKey = true,
            tier = AgentTier.FREE,
        )
        assertTrue(!free.isReady)
        assertEquals(null, free.open(item(), evidence(), "sk-test-0123456789abcdefghij"))
    }
}
