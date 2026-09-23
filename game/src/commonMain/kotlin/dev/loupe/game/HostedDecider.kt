package dev.loupe.game

/**
 * A [Decider] whose pilot is run by the host, on whatever thread the host chooses — the iPhone app
 * runs Laya on a background dispatch queue this way, so a slow model never blocks a frame.
 *
 * The protocol, all but [decide] on the simulation thread:
 *
 * 1. The session [submit]s an observation (it is then [busy]).
 * 2. The host [take]s it — once — and calls [decide] off the simulation thread. [decide] touches
 *    only the pilot and the immutable [Observation], never the world.
 * 3. The host hands the result back on the simulation thread with [deliver]; the next [poll] lands it.
 *
 * A pilot that throws never escapes [decide] (it becomes a [DecisionSource.FAILURE] hold). After
 * [close], deliveries are dropped, so a decision finishing after its run ended cannot reach a new one.
 */
class HostedDecider(override val pilot: Pilot) : Decider {
    private var submitted: Observation? = null
    private var waiting: Observation? = null
    private var landed: PilotDecision? = null

    /** True once closed; a host may stop scheduling work for it. */
    var closed: Boolean = false
        private set

    override val busy: Boolean get() = submitted != null

    override fun submit(observation: Observation) {
        check(submitted == null) { "a decision is already in flight" }
        submitted = observation
        waiting = observation
    }

    /** The observation to decide, once per submission; null when there is nothing new. */
    fun take(): Observation? {
        if (closed) return null
        val o = waiting ?: return null
        waiting = null
        return o
    }

    /** Runs the pilot. Safe to call from any thread; never throws. */
    fun decide(observation: Observation): PilotDecision = safely(pilot, observation)

    /** Hands back the decision for the observation in flight. Ignored after [close] or when stale. */
    fun deliver(decision: PilotDecision) {
        val inFlight = submitted ?: return
        if (closed || decision.observedTick != inFlight.tick || waiting != null) return
        landed = decision
    }

    override fun poll(tick: Long): PilotDecision? {
        val decision = landed ?: return null
        landed = null
        submitted = null
        return decision
    }

    override fun close() {
        closed = true
        waiting = null
    }
}
