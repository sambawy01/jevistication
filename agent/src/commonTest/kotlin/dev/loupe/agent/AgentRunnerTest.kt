package dev.loupe.agent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives a session to completion, answering each call from [responses] in order. */
internal fun drive(session: AgentSession, responses: List<AgentHttpResponse>): AgentRun {
    assertNotNull(session.start(), "the session must produce a first call")
    var i = 0
    var guard = 0
    while (true) {
        val response = responses[minOf(i, responses.size - 1)]
        when (val step = session.receive(response)) {
            is AgentStep.Done -> return step.run
            is AgentStep.Send -> {
                i++
                if (++guard > 8) error("the session did not finish")
            }
        }
    }
}

class AgentRunnerTest {
    private fun runner(config: AgentConfig = readyConfig(), hasKey: Boolean = true) =
        AgentRunner(config, hasKey, AgentTier.CONNECTED)

    private fun session(
        item: AgentItem = item(),
        e: AgentEvidence = evidence(),
    ): AgentSession = runner().open(item, e, TEST_KEY, question = "Is this a renewal notice?", atIso = "2026-09-25T10:00:00Z")!!

    // ------------------------------------------------------------------------------ the off switch

    @Test
    fun `a runner built off cannot open a session`() {
        assertNull(AgentRunner.off().open(item(), evidence(), TEST_KEY))
        assertTrue(!AgentRunner.off().isReady)
    }

    @Test
    fun `an unconfigured or keyless runner cannot open a session`() {
        assertNull(runner(hasKey = false).open(item(), evidence(), null))
        assertNull(runner(readyConfig().copy(model = "")).open(item(), evidence(), TEST_KEY))
        assertNull(runner().open(item(), evidence(), apiKey = null), "a hosted provider needs a key")
    }

    @Test
    fun `a session cannot be opened for an item the evidence is not about`() {
        assertNull(runner().open(item(), evidence(itemId = "another-item"), TEST_KEY))
    }

    @Test
    fun `the gate is usable with the tier off, because skipping costs nothing`() {
        val r = AgentRunner.off().sift(listOf(GateInput("a", "j", dev.loupe.engine.Decision.Act("x", dev.loupe.engine.Probability.of(0.9)))))
        assertEquals(1, r.eligible.size)
    }

    // ------------------------------------------------------------------------------- a good run

    @Test
    fun `a good answer becomes queue proposals and an egress record`() {
        val answer = """{"actions":[
            {"type":"remind","when":"2026-10-02","text":"Renew example.com","because":"renews on 2 October 2026"},
            {"type":"note","headline":"The fee is 12 USD","detail":"Stated in the notice."}]}"""
        val run = drive(session(), listOf(completion(answer)))

        assertTrue(run.ok, run.failure?.message ?: "")
        assertEquals(2, run.proposals.size)
        assertEquals(2, run.actions.size)
        assertTrue(run.report.clean)

        val e = run.egress
        assertEquals(EgressRecord.Outcome.OK, e.outcome)
        assertEquals("item-1", e.itemId)
        assertEquals("DeepSeek", e.provider)
        assertEquals("api.deepseek.com", e.host)
        assertEquals("deepseek-chat", e.model)
        assertEquals(800, e.tokensIn)
        assertEquals(120, e.tokensOut)
        assertEquals(2, e.actionsPrepared)
        assertEquals(0, e.actionsRefused)
        assertTrue(e.line.contains("2 prepared"), e.line)
    }

    @Test
    fun `an empty action list is a successful run with nothing to queue`() {
        val run = drive(session(), listOf(completion("""{"actions":[]}""")))
        assertTrue(run.ok)
        assertEquals(0, run.proposals.size)
        assertEquals(EgressRecord.Outcome.OK, run.egress.outcome)
    }

    // ------------------------------------------------------------------ the guard inside the run

    @Test
    fun `an action the never list refuses never reaches the queue, and the refusal is recorded`() {
        val answer = """{"actions":[
            {"type":"note","headline":"This email is legitimate","detail":"Nothing to worry about."},
            {"type":"remind","when":"2026-10-02","text":"Renew example.com","because":"stated"}]}"""
        val run = drive(session(), listOf(completion(answer)))

        assertTrue(run.ok, "the run itself succeeded; one action was refused")
        assertEquals(1, run.proposals.size, "only the reminder may be queued")
        assertEquals(1, run.report.blocked.size)
        assertEquals(NeverRule.BLESS, run.report.blocked[0].rule)
        assertEquals(listOf("never_blesses"), run.egress.refusedRules)
        assertEquals(1, run.egress.actionsRefused)
    }

    @Test
    fun `a one-time code the model echoed back never reaches the queue`() {
        val answer = """{"actions":[{"type":"reply_draft","to":"a@b.c","subject":"Re: x",
            "body":"Here is the verification code 481920 you asked for.","language":"English",
            "needs_info":[],"notes_for_reviewer":""}]}"""
        val run = drive(session(), listOf(completion(answer)))
        assertEquals(0, run.proposals.size)
        assertEquals(listOf("never_fills_credentials"), run.egress.refusedRules)
    }

    // ----------------------------------------------------------------------------- the repair turn

    @Test
    fun `a malformed answer buys exactly one repair turn`() {
        val bad = completion("I think you should renew it.")
        val good = completion("""{"actions":[{"type":"note","headline":"Renewal due","detail":"2 October."}]}""")
        val s = session()
        assertNotNull(s.start())

        val first = s.receive(bad)
        assertTrue(first is AgentStep.Send, "a malformed answer must ask again, got $first")
        assertTrue(s.repaired)
        assertTrue(
            first.call.messages.any { it.content.contains("That reply was invalid") },
            "the repair turn must say what was wrong",
        )

        val second = s.receive(good)
        assertTrue(second is AgentStep.Done, "got $second")
        assertTrue(second.run.ok)
        assertEquals(1, second.run.proposals.size)
    }

    @Test
    fun `a second malformed answer fails the run rather than spending more of the user's money`() {
        val bad = completion("still not json")
        val run = drive(session(), listOf(bad, bad))
        assertTrue(!run.ok)
        assertTrue(run.failure is AgentFailure.InvalidJson, run.failure?.message ?: "")
        assertEquals(0, run.proposals.size)
        assertEquals(EgressRecord.Outcome.FAILED, run.egress.outcome)
    }

    @Test
    fun `tokens are counted across both turns`() {
        val bad = completion("nope", tokensIn = 500, tokensOut = 20)
        val good = completion("""{"actions":[]}""", tokensIn = 600, tokensOut = 30)
        val run = drive(session(), listOf(bad, good))
        assertEquals(1_100, run.egress.tokensIn)
        assertEquals(50, run.egress.tokensOut)
    }

    // ----------------------------------------------------------------------------------- failures

    @Test
    fun `a refused key fails the run with a message that never carries the key`() {
        val run = drive(
            session(),
            listOf(AgentHttpResponse(401, """{"error":{"message":"invalid key $TEST_KEY"}}""")),
        )
        assertTrue(!run.ok)
        assertTrue(run.failure is AgentFailure.Auth)
        assertTrue(!run.failure!!.message.contains(TEST_KEY), run.failure!!.message)
        assertTrue(!run.egress.problem.contains(TEST_KEY), run.egress.problem)
    }

    @Test
    fun `rate limiting and a server error are reported as themselves`() {
        assertTrue(drive(session(), listOf(AgentHttpResponse(429, ""))).failure is AgentFailure.RateLimited)
        assertTrue(drive(session(), listOf(AgentHttpResponse(503, ""))).failure is AgentFailure.Http)
    }

    @Test
    fun `an item the tier did not touch still gets a record`() {
        val r = AgentRunner.off().notSent("item-9", "j-x", "2026-09-25T10:00:00Z")
        assertEquals(EgressRecord.Outcome.NOT_SENT, r.outcome)
        assertTrue(r.line.contains("nothing sent"), r.line)
    }

    // ------------------------------------------------------------------- the never-throws contract

    @Test
    fun `no provider answer, however malformed, throws out of a session`() {
        // The same contract the engine's A4 boundary keeps for a misbehaving backend: a screen's
        // event handler must never see an exception from here.
        val rng = Random(20260925)
        val alphabet = "{}[]\",:0123456789abcdefghijklmnopqrstuvwxyz\\\n\u0000� ".toList()
        var failures = 0
        repeat(500) {
            val body = buildString { repeat(rng.nextInt(0, 400)) { append(alphabet[rng.nextInt(alphabet.size)]) } }
            val status = listOf(200, 200, 200, 400, 401, 404, 429, 500, 503, 0, 999)[rng.nextInt(11)]
            val run = drive(session(), listOf(AgentHttpResponse(status, body), AgentHttpResponse(status, body)))
            if (!run.ok) failures++
            // Whatever happened, the record is well-formed and nothing was queued without a proposal.
            assertEquals(run.actions.size, run.proposals.size)
            assertTrue(run.egress.itemId == "item-1")
        }
        assertTrue(failures > 400, "random bytes should almost always fail; $failures of 500 did")
    }

    @Test
    fun `a truncated but valid-looking answer fails cleanly`() {
        val run = drive(session(), listOf(completion("""{"actions":[{"type":"note","headline":"""), completion("also bad")))
        assertTrue(!run.ok)
        assertEquals(0, run.proposals.size)
    }
}
