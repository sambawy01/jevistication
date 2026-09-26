package dev.loupe.agent

import dev.loupe.kit.review.ReviewRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentReviewTest {
    private val all = listOf(remind(), event(), draft(), note())

    @Test
    fun `every proposal the agent builds passes the live registry's own field check`() {
        // If this fails, the queue would refuse an action at submit time for a reason the agent
        // could have seen coming -- and the person would see nothing at all.
        for (action in all) {
            val p = AgentReview.proposal(action, at = "2026-09-25T10:00:00Z")
            val kind = ReviewRegistry.kind(p.kind)
            assertNotNull(kind, "unknown kind ${p.kind}")
            assertEquals(emptyList(), kind.check(p.proposal), "${p.kind}: ${kind.check(p.proposal)}")
        }
    }

    @Test
    fun `every proposal uses the agent feature and a registered action`() {
        for (action in all) {
            val p = AgentReview.proposal(action)
            assertEquals(ReviewRegistry.AGENT, p.feature)
            assertTrue(p.feature in ReviewRegistry.FEATURES, p.feature)
            val a = ReviewRegistry.action(p.actionType)
            assertNotNull(a, "unknown action ${p.actionType}")
            assertTrue(p.kind in a.kinds, "${p.actionType} does not accept ${p.kind}")
            assertEquals(emptyList(), a.validate(p.proposal))
        }
    }

    @Test
    fun `an agent action takes no parameters - so it cannot be steered into doing something else`() {
        for (action in all) {
            val p = AgentReview.proposal(action)
            assertEquals(emptyMap(), p.actionParams)
            assertEquals(emptySet(), ReviewRegistry.action(p.actionType)!!.params)
        }
    }

    @Test
    fun `the four action types are exactly the agent's - and none of them spends`() {
        val types = all.map { AgentReview.proposal(it).actionType }.toSet()
        assertEquals(setOf("agent.remind", "agent.calendar", "agent.draft", "agent.note"), types)
    }

    @Test
    fun `the summary names the provider - says Online - and says nothing has happened yet`() {
        val s = AgentReview.summaryFor(remind())
        assertTrue(s.contains("DeepSeek"), s)
        assertTrue(s.contains("(Online)"), s)
        assertTrue(s.contains("j-renewal answered"), s)
        assertTrue(s.contains("Nothing happens until you approve it."), s)
    }

    @Test
    fun `a draft's summary says Loupe will not send it`() {
        val s = AgentReview.summaryFor(draft())
        assertTrue(s.contains("never sends it"), s)
        assertTrue(s.contains("open it in Mail"), s)
    }

    @Test
    fun `every proposal carries the provider and the evidence - because neither may be dropped`() {
        for (action in all) {
            val p = AgentReview.proposal(action)
            assertEquals("DeepSeek (Online)", p.proposal["origin"])
            assertTrue(!p.proposal["evidence"].isNullOrBlank(), p.kind)
            assertEquals("item-1", p.proposal["item_id"])
        }
    }

    @Test
    fun `the source key is content-derived - so the same action is never queued twice`() {
        val a = AgentReview.proposal(remind())
        val b = AgentReview.proposal(remind())
        assertEquals(a.sourceKey, b.sourceKey)

        val other = AgentReview.proposal(remind(whenIso = "2026-10-09"))
        assertTrue(a.sourceKey != other.sourceKey, "a different date is a different proposal")
    }

    @Test
    fun `the source key separates the action types for one item`() {
        val keys = all.map { AgentReview.proposal(it).sourceKey }
        assertEquals(keys.size, keys.toSet().size, "each type needs its own key: $keys")
        assertTrue(keys.all { it.startsWith("agent.") }, keys.toString())
    }

    @Test
    fun `a title over the queue's limit is trimmed rather than refused`() {
        val long = note(headline = "h".repeat(900))
        val allowed = (ActionGuard.check(long) as GuardVerdict.Allowed).action
        val p = AgentReview.proposal(allowed)
        assertTrue(p.title.length <= ActionGuard.MAX_TITLE, "title was ${p.title.length}")
        assertEquals(emptyList(), ReviewRegistry.kind(p.kind)!!.check(p.proposal))
    }

    @Test
    fun `a calendar entry with no end or location omits them rather than sending blanks`() {
        val p = AgentReview.proposal(event())
        assertTrue(!p.proposal.containsKey("end"), p.proposal.toString())
        assertTrue(!p.proposal.containsKey("location"), p.proposal.toString())
        assertEquals(emptyList(), ReviewRegistry.kind("agent_event")!!.check(p.proposal))
    }
}
