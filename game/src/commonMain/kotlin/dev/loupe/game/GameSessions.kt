package dev.loupe.game

import dev.loupe.engine.Backend

/**
 * Factories for the hosts that cannot use Kotlin default arguments (Swift, through LoupeKit). Every
 * session here measures against [GameClock], so a host reading [DecisionStats.decisionsPerSecond]
 * passes `GameClock.nanoTime()`.
 */
object GameSessions {
    /** The keys (touch) fly, every tick. */
    fun human(seed: Long): GameSession = GameSession(seed, Control.Human)

    /** The scripted baseline, computed in step so it is deterministic (as the desktop game does). */
    fun baseline(seed: Long): GameSession = GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 0)))

    /** A pilot the host runs off the simulation thread through [decider]. */
    fun hosted(seed: Long, decider: HostedDecider, decisionInterval: Int = GameSession.DEFAULT_DECISION_INTERVAL): GameSession =
        GameSession(seed, Control.Piloted(decider), decisionInterval = decisionInterval)

    /** The Laya pilot over [backend] — the same [ModelPilot] the desktop game flies. */
    fun modelDecider(backend: Backend): HostedDecider = HostedDecider(ModelPilot(backend))

    /** The baseline behind a [HostedDecider], for a host that wants one code path for both. */
    fun baselineDecider(): HostedDecider = HostedDecider(BaselinePilot())

    /** The six actions in their fixed display order. */
    val actions: List<Action> get() = Action.entries

    /** The raw model probability of [action] in [decision], or -1 when none was reported. */
    fun raw(decision: PilotDecision?, action: Action): Double = decision?.raw?.get(action) ?: -1.0

    /** The row at [index] of [world]'s river, for a renderer. */
    fun row(world: World, index: Int): Row = world.river.row(index.coerceAtLeast(0))
}
