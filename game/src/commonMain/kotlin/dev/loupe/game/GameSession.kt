package dev.loupe.game

import kotlin.concurrent.Volatile

/** The keys a human is holding. Written by the UI thread, read by the simulation. */
data class HumanInput(val left: Boolean = false, val right: Boolean = false, val fire: Boolean = false) {
    val action: Action get() = Action.of((if (right) 1 else 0) - (if (left) 1 else 0), fire)
}

/** Who is flying. */
sealed interface Control {
    /** A pilot, asked at decision rate through a [Decider]. */
    class Piloted(val decider: Decider) : Control

    /** The keyboard, every tick. */
    data object Human : Control
}

/**
 * One run of the game: a [World], whoever is flying it, and the machinery between them.
 *
 * Each [tick]:
 *
 * 1. A decision that has landed replaces the one being flown. A model decision whose top raw
 *    probability is below [threshold] is **handed off**: until the next decision lands, the human's
 *    keys fly the plane ("your turn"). With no one at the keys that is the no-op.
 * 2. If no decision is in flight and one is due ([decisionInterval] ticks since the last request),
 *    the legal actions are computed and an observation is submitted. The simulation never waits.
 * 3. The action to fly is checked by the safety override ([Mechanics.safetyOverride]); if it is about
 *    to crash the plane and something else is not, the something else is flown and recorded.
 * 4. The world steps once.
 *
 * Not thread-safe: call it from one thread. Only [threshold], [human] and [overrideEnabled] may be
 * written from another (the UI), and they are volatile.
 */
class GameSession(
    val seed: Long,
    val control: Control,
    /** Ticks between decision requests: 6 at 60 Hz is 10 decisions a second. */
    val decisionInterval: Int = DEFAULT_DECISION_INTERVAL,
    threshold: Double = 0.0,
    overrideEnabled: Boolean = true,
    /** The clock throughput is measured against: wall time live, simulation time headless. */
    private val clock: () -> Long = GameClock::nanoTime,
    /** How the river hardens with rows flown; [Difficulty.CLASSIC] is the original game. */
    val difficulty: Difficulty = Difficulty.CLASSIC,
) : AutoCloseable {
    init {
        require(decisionInterval >= 1) { "decision interval must be at least one tick, was $decisionInterval" }
    }

    val world: World = World(seed, difficulty)
    val stats: DecisionStats = DecisionStats()

    @Volatile var threshold: Double = threshold
        set(value) {
            require(value in 0.0..1.0) { "threshold must be in [0,1], was $value" }
            field = value
        }

    @Volatile var human: HumanInput = HumanInput()

    @Volatile var overrideEnabled: Boolean = overrideEnabled

    /**
     * A floor on the interval, in ticks, from a host's cap (Model settings'
     * `features.game.max_decisions_per_s`): the game never asks faster than this. 1 = no cap.
     */
    @Volatile var minInterval: Int = 1
        set(value) {
            require(value >= 1) { "min interval must be at least one tick, was $value" }
            field = value
        }

    /** Ticks between decision requests right now: the level's cadence, held to [minInterval]. */
    val currentInterval: Int
        get() = maxOf(difficulty.decisionInterval(world.level, decisionInterval), minInterval)

    /** Decisions per second the game is asking for right now. */
    val askedPerSecond: Double get() = Rules.TICK_HZ.toDouble() / currentInterval

    /** The decision being flown, or null before the first one lands. */
    var current: PilotDecision? = null; private set

    /** True while the current decision was handed to the human. */
    var handedOff: Boolean = false; private set

    /** The action actually flown on the last tick, after any override. */
    var lastFlown: Action = Action.HOLD; private set

    /** The most recent override, for the UI's flash. */
    var lastOverride: OverrideEvent? = null; private set

    /** The legal set [current] was chosen from — what was offered, and why the rest were not. */
    var currentLegal: LegalActions? = null; private set

    /** The legal set behind the decision in flight, to hold the pilot to it. */
    private var pendingLegal: LegalActions? = null

    /** The observation behind the decision in flight, for the collision-avoidance count. */
    private var pendingObservation: Observation? = null
    private var nextRequestTick: Long = 0

    fun tick() {
        if (world.over) return
        val decider = (control as? Control.Piloted)?.decider

        if (decider != null) {
            decider.poll(world.tick)?.let { landed -> land(landed) }
            if (!decider.busy && world.tick >= nextRequestTick) {
                val legal = Mechanics.legalActions(world)
                pendingLegal = legal
                val observation = Observation.of(world, legal)
                pendingObservation = observation
                decider.submit(observation)
                val interval = currentInterval
                // Honest accounting: a request that goes out after it was due is late, and every
                // whole interval that passed with the pilot still busy was a decision not made.
                stats.recordRequest(dueTick = nextRequestTick, sentTick = world.tick, interval = interval)
                nextRequestTick = world.tick + interval
            }
        }

        val wanted = when {
            decider == null -> human.action
            handedOff -> human.action
            else -> current?.action ?: Action.HOLD
        }
        var flown = wanted
        if (overrideEnabled) {
            Mechanics.safetyOverride(world, wanted)?.let { replacement ->
                flown = replacement
                lastOverride = OverrideEvent(world.tick, wanted, replacement)
                stats.recordOverride(world.tick)
            }
        }
        lastFlown = flown
        world.step(flown)
    }

    private fun land(landed: PilotDecision) {
        val legal = pendingLegal
        val decision = if (legal != null && landed.action !in legal.actions && landed.source != DecisionSource.FAILURE) {
            // A pilot answering outside its candidate set is a bug in the pilot, not a move.
            landed.copy(action = Action.HOLD, source = DecisionSource.FAILURE, failure = "pilot chose ${landed.action}, not a legal action")
        } else {
            landed
        }
        pendingLegal = null
        currentLegal = legal
        current = decision
        stats.record(decision, clock())
        pendingObservation?.let { o -> if (decision.source != DecisionSource.FAILURE) stats.recordCollisionChoice(o, decision.action) }
        pendingObservation = null
        val top = decision.topProbability
        handedOff = decision.source == DecisionSource.MODEL && top != null && top < threshold
        if (handedOff) stats.recordHandOff()
        stats.recordLanding(world.tick - decision.observedTick, currentInterval)
    }

    override fun close() {
        (control as? Control.Piloted)?.decider?.close()
    }

    companion object {
        const val DEFAULT_DECISION_INTERVAL: Int = 6
    }
}
