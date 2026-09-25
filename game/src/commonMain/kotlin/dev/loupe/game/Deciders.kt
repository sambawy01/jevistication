package dev.loupe.game

/**
 * Runs a [Pilot] at decision rate, decoupled from the simulation tick.
 *
 * The session [submit]s an observation when no decision is in flight, keeps flying the last
 * decision (or the no-op) meanwhile, and [poll]s every tick for one that has landed. A pilot that
 * throws never reaches the game loop: the decider turns it into a [DecisionSource.FAILURE] hold.
 */
interface Decider : AutoCloseable {
    val pilot: Pilot

    /** True while a decision is being computed. */
    val busy: Boolean

    fun submit(observation: Observation)

    /** The decision that has landed by [tick], once; null otherwise. */
    fun poll(tick: Long): PilotDecision?

    override fun close() {}
}

/** The decision a crashed pilot gets: the null action, with the reason kept. */
internal fun failed(observation: Observation, error: Throwable): PilotDecision = PilotDecision(
    action = Action.HOLD,
    source = DecisionSource.FAILURE,
    failure = "pilot threw: ${error.message ?: error::class.simpleName}",
    observedTick = observation.tick,
)

internal fun safely(pilot: Pilot, observation: Observation): PilotDecision =
    try {
        pilot.decide(observation)
    } catch (e: Exception) {
        failed(observation, e)
    }

/**
 * Computes each decision immediately but releases it [delayTicks] later, in simulation time.
 *
 * This is how headless runs stay reproducible: the pilot is charged a fixed, stated latency instead
 * of whatever the machine happened to take, so a model and the baseline flown on the same seed with
 * the same delay face exactly the same world. The real latency is still measured and reported.
 */
class LockstepDecider(override val pilot: Pilot, private val delayTicks: Int) : Decider {
    init {
        require(delayTicks >= 0) { "delay must not be negative, was $delayTicks" }
    }

    private var pending: PilotDecision? = null
    private var readyAt: Long = 0

    override val busy: Boolean get() = pending != null

    override fun submit(observation: Observation) {
        check(pending == null) { "a decision is already in flight" }
        pending = safely(pilot, observation)
        readyAt = observation.tick + delayTicks
    }

    override fun poll(tick: Long): PilotDecision? {
        val decision = pending ?: return null
        if (tick < readyAt) return null
        pending = null
        return decision
    }
}

/**
 * Decision throughput and latency, measured — never assumed.
 *
 * Latency is the pilot's own wall-clock time per decision. Throughput is decisions landed per second
 * over a sliding window of the clock the caller supplies: wall time in the live game, simulation time
 * in a headless run (where it is fixed by the decision interval, and so says nothing about the
 * machine — the latency percentiles do).
 */
class DecisionStats(private val window: Int = 256) {
    private val latencies = LongArray(window)
    private var latencyCount = 0
    private val landedAt = ArrayDeque<Long>()

    var total: Int = 0; private set
    private val bySource = IntArray(DecisionSource.entries.size)
    var handOffs: Int = 0; private set
    var overrides: Int = 0; private set

    fun count(source: DecisionSource): Int = bySource[source.ordinal]

    fun record(decision: PilotDecision, nowNanos: Long) {
        total++
        bySource[decision.source.ordinal]++
        if (decision.source == DecisionSource.MODEL || decision.source == DecisionSource.FAILURE ||
            decision.source == DecisionSource.BASELINE
        ) {
            latencies[latencyCount % window] = decision.latencyNanos
            latencyCount++
        }
        landedAt.addLast(nowNanos)
        while (landedAt.size > 1 && nowNanos - landedAt.first() > RATE_WINDOW_NANOS) landedAt.removeFirst()
    }

    /** Decision requests sent to the pilot. */
    var requested: Int = 0; private set

    /** Requests that went out after they were due, because the pilot was still on the last one. */
    var lateRequests: Int = 0; private set

    /** Whole decision slots that passed with the pilot busy: decisions the game asked for and never got. */
    var dropped: Int = 0; private set

    /** Decisions that landed more than one interval after the state they answered. */
    var lateAnswers: Int = 0; private set

    /**
     * A request due at [dueTick] was sent at [sentTick] with [interval] ticks to the next. The first
     * request of a run (due at tick 0) is never late.
     */
    fun recordRequest(dueTick: Long, sentTick: Long, interval: Int) {
        requested++
        if (sentTick > dueTick) {
            lateRequests++
            dropped += ((sentTick - dueTick) / interval).toInt()
        }
    }

    /** A decision landed [ageTicks] after the tick it observed, while the cadence was [interval]. */
    fun recordLanding(ageTicks: Long, interval: Int) {
        if (ageTicks > interval) lateAnswers++
    }

    /** The most recent latencies in milliseconds, oldest first, at most [n]. */
    fun recentLatenciesMillis(n: Int): List<Double> {
        val have = minOf(latencyCount, window)
        val take = minOf(n, have)
        return List(take) { i -> latencies[(latencyCount - take + i) % window] / 1e6 }
    }

    fun recordHandOff() {
        handOffs++
    }

    fun recordOverride() {
        overrides++
    }

    /** Decisions landed per second over the last two seconds of [nowNanos]'s clock. */
    fun decisionsPerSecond(nowNanos: Long): Double {
        val recent = landedAt.count { nowNanos - it <= RATE_WINDOW_NANOS }
        return recent / (RATE_WINDOW_NANOS / 1e9)
    }

    /** The [q]-quantile (0..1) of recent decision latency in milliseconds, or null before any. */
    fun latencyMillis(q: Double): Double? {
        val n = minOf(latencyCount, window)
        if (n == 0) return null
        val sorted = latencies.copyOf(n).sorted()
        val index = ((n - 1) * q).toInt().coerceIn(0, n - 1)
        return sorted[index] / 1e6
    }

    companion object {
        const val RATE_WINDOW_NANOS: Long = 2_000_000_000L
    }
}
