package dev.loupe.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTransportTest {
    private fun session() = AgentRunner(readyConfig(), hasKey = true, tier = AgentTier.CONNECTED)
        .open(item(), evidence(), TEST_KEY, atIso = "2026-09-25T10:00:00Z")!!

    @Test
    fun `runTo drives a good answer through to proposals`() {
        val answer = """{"actions":[{"type":"note","headline":"Renewal due 2 October","detail":"12 USD."}]}"""
        val sent = mutableListOf<AgentCall>()
        val run = session().runTo(AgentTransport { completion(answer) }) { sent += it }

        assertTrue(run.ok, run.failure?.message ?: "")
        assertEquals(1, run.proposals.size)
        assertEquals(1, sent.size, "a good answer needs one call")
    }

    @Test
    fun `onCall sees each call before it is sent, which is where the preview is shown`() {
        val seen = mutableListOf<String>()
        var answered = false
        session().runTo(
            AgentTransport {
                if (answered) completion("""{"actions":[]}""") else completion("not json").also { answered = true }
            },
        ) { seen += it.preview }

        assertEquals(2, seen.size, "the repair turn is a call too, and must be previewable")
        assertTrue(seen[1].contains("That reply was invalid"), seen[1])
    }

    @Test
    fun `a transport that throws becomes an unreachable run, not an exception`() {
        val run = session().runTo(AgentTransport { throw RuntimeException("connection reset") })
        assertTrue(!run.ok)
        assertTrue(run.failure is AgentFailure.Unreachable, run.failure?.message ?: "")
        assertTrue(run.failure!!.message.contains("connection reset"), run.failure!!.message)
        assertEquals(EgressRecord.Outcome.FAILED, run.egress.outcome)
    }

    @Test
    fun `a transport that throws with the key in the message never leaks it`() {
        val run = session().runTo(AgentTransport { throw RuntimeException("failed with Bearer $TEST_KEY") })
        assertTrue(!run.failure!!.message.contains(TEST_KEY), run.failure!!.message)
    }

    @Test
    fun `a transport that never settles is stopped rather than spun on`() {
        // The session spends one repair turn, so this cannot happen today; the guard is here so a
        // future change to that rule fails loudly instead of hanging a screen.
        var calls = 0
        val run = session().runTo(AgentTransport { calls++; completion("never json") })
        assertTrue(!run.ok)
        assertTrue(calls <= AgentRetry.MAX_RETRIES + 3, "made $calls calls")
    }

    @Test
    fun `an off runner has nothing to drive`() {
        assertEquals(null, AgentRunner.off().open(item(), evidence(), TEST_KEY))
    }

    @Test
    fun `retry covers exactly the statuses worth another attempt`() {
        for (s in listOf(429, 500, 502, 503, 504)) {
            assertTrue(AgentRetry.shouldRetry(s, attemptsMade = 1), "$s should retry once")
            assertTrue(!AgentRetry.shouldRetry(s, attemptsMade = 2), "$s must not retry twice")
        }
        for (s in listOf(200, 400, 401, 403, 404, 422)) {
            assertTrue(!AgentRetry.shouldRetry(s, attemptsMade = 1), "$s must not retry")
        }
    }

    @Test
    fun `the delay grows, honours Retry-After, and is capped`() {
        assertEquals(0.6, AgentRetry.delaySeconds(0))
        assertEquals(1.2, AgentRetry.delaySeconds(1))
        assertEquals(2.4, AgentRetry.delaySeconds(2))
        assertEquals(5.0, AgentRetry.delaySeconds(0, retryAfter = 5.0), "the provider's own figure wins")
        assertEquals(AgentRetry.MAX_DELAY_SECONDS, AgentRetry.delaySeconds(0, retryAfter = 900.0))
        assertEquals(AgentRetry.MAX_DELAY_SECONDS, AgentRetry.delaySeconds(30))
        assertEquals(0.6, AgentRetry.delaySeconds(0, retryAfter = -1.0), "a nonsense figure is ignored")
    }
}
