package dev.loupe.agent

/**
 * Whatever performs the HTTP call.
 *
 * The module deliberately has no implementation of this in common code. A transport needs sockets,
 * a TLS stack and a clock, and keeping all three outside means the gate, the prompt, the parse and
 * the never list are testable with nothing but a string of JSON — and that [AgentCall.preview] is
 * the literal body, because no later step can add to it.
 *
 * An implementation must not retry on its own account beyond what [AgentRetry] describes, must not
 * follow a redirect to another host (the endpoint rules exist to decide where a request may go),
 * and must never log [AgentCall.headers] — [AgentCall.safeHeaders] is there for that.
 */
fun interface AgentTransport {
    /** Posts [call] and returns what came back, or throws to mean "could not reach it". */
    fun post(call: AgentCall): AgentHttpResponse
}

/**
 * When to try again, as a pure function, so the policy is testable and the sleeping is the
 * platform's business.
 *
 * Deliberately stingy. Every attempt is the user's money, and a provider that is rate limiting is
 * telling us something true about capacity rather than inviting us to ask harder.
 */
object AgentRetry {
    /** At most this many extra attempts per call. */
    const val MAX_RETRIES: Int = 1

    /** Never wait longer than this, whatever `Retry-After` says. */
    const val MAX_DELAY_SECONDS: Double = 20.0

    /** True when [status] is worth one more attempt and there are attempts left. */
    fun shouldRetry(status: Int, attemptsMade: Int, maxRetries: Int = MAX_RETRIES): Boolean =
        status in AgentWire.RETRY_STATUS && attemptsMade <= maxRetries

    /**
     * How long to wait before attempt number [attemptsMade] + 1.
     *
     * The provider's own `Retry-After` wins when it gave one, capped; otherwise exponential from
     * 0.6s, the same curve the iPhone's client uses.
     */
    fun delaySeconds(attemptsMade: Int, retryAfter: Double? = null): Double {
        if (retryAfter != null && retryAfter > 0) return minOf(retryAfter, MAX_DELAY_SECONDS)
        var d = 0.6
        repeat(attemptsMade.coerceAtLeast(0)) { d *= 2 }
        return minOf(d, MAX_DELAY_SECONDS)
    }
}

/**
 * Drives one session to completion through [transport].
 *
 * The whole tier in one call, for a caller that has a transport and does not want the state
 * machine. It keeps the never-throws contract: a transport that throws becomes an
 * [AgentFailure.Unreachable] run, not an exception out of a screen.
 *
 * [onCall] is invoked with each call **before** it is sent, which is where a caller shows the
 * preview and records that something is about to leave the device.
 */
fun AgentSession.runTo(
    transport: AgentTransport,
    onCall: (AgentCall) -> Unit = {},
): AgentRun {
    var call = start() ?: return failedRun(AgentFailure.Config("The agent tier is off."))
    var guard = 0
    while (true) {
        onCall(call)
        val response = runCatching { transport.post(call) }.getOrElse { t ->
            return failedRun(AgentFailure.Unreachable(AgentWire.redact(t.message ?: "no answer")))
        }
        when (val step = receive(response)) {
            is AgentStep.Done -> return step.run
            is AgentStep.Send -> {
                call = step.call
                // The session spends at most one repair turn, so this cannot spin; the guard is
                // here so that a future change to that rule fails loudly instead of hanging.
                if (++guard > AgentRetry.MAX_RETRIES + 2) {
                    return failedRun(AgentFailure.BadResponse("The provider did not settle on an answer."))
                }
            }
        }
    }
}
