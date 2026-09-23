package dev.loupe.game

import kotlin.math.abs

/** Why an action was left out of the candidate set. */
enum class Exclusion {
    /** Holding it until the next decision can land leads to a crash no follow-up can avoid. */
    FATAL,

    /** Low on fuel, and the shot would destroy the depot straight ahead. */
    SHOOTS_LAST_FUEL,
}

/** The actions offered to a pilot this decision, and why the rest were not. */
data class LegalActions(val actions: List<Action>, val excluded: Map<Action, Exclusion>) {
    init {
        require(actions.isNotEmpty()) { "a legal set is never empty" }
    }
}

/** A fatal action replaced by the safety override. */
data class OverrideEvent(val tick: Long, val wanted: Action, val replacedWith: Action)

/**
 * The mechanical half of every decision: what exact simulation can answer, answered before any
 * pilot is asked (the spec's "mechanical first"). Two things live here.
 *
 * **The legal candidate set** ([legalActions]). An action is offered only if holding it for as long
 * as a decision is actually held ([HOLD_TICKS]) leaves the plane alive *and* still able to survive
 * the following [RECOVERY_TICKS] with some constant action — that is, the next decision can still
 * save it. The check is a look-ahead on a copy of the real world, so it is exact about banks,
 * enemies, bridges and bullets, not an approximation of them. A model is therefore never offered a
 * move into the bank when another move exists; mass is not wasted on illegal options.
 *
 * **The safety override** ([safetyOverride]). Decisions land late and are held, and the world moves
 * while they are held. Every tick, the held action is re-checked over a shorter horizon; if it has
 * become fatal and another action is not, the other action is flown instead and the event is
 * recorded, so the UI can show that the safety net — not the pilot — made that move.
 *
 * Neither can make an unavoidable crash avoidable. When every action dies, the ones that survive
 * longest are kept, so the set is never empty and the override never pretends.
 */
object Mechanics {
    /** Ticks a decision is held: 10 Hz decisions plus a few ticks of latency. */
    const val HOLD_TICKS: Int = 12

    /** After the hold, some constant action must survive this long. */
    const val RECOVERY_TICKS: Int = 12

    /** The override's horizon for the held action. Shorter: it re-checks every tick. */
    const val SAFETY_TICKS: Int = 8

    fun legalActions(world: World): LegalActions {
        val excluded = linkedMapOf<Action, Exclusion>()
        val alive = Action.entries.filter { survives(world, it, HOLD_TICKS, RECOVERY_TICKS) }
        var legal = if (alive.isNotEmpty()) {
            alive
        } else {
            // Nothing survives: keep whatever lasts longest, and do not call the rest fatal-er.
            val lasting = Action.entries.associateWith { survivalTicks(world, it, HOLD_TICKS + RECOVERY_TICKS) }
            val best = lasting.values.max()
            lasting.filterValues { it == best }.keys.toList()
        }
        Action.entries.filter { it !in legal }.forEach { excluded[it] = Exclusion.FATAL }

        if (world.fuel < Rules.FUEL_LOW && depotInLineOfFire(world)) {
            val holdFire = legal.filter { !it.fire }
            if (holdFire.isNotEmpty()) {
                legal.filter { it.fire }.forEach { excluded[it] = Exclusion.SHOOTS_LAST_FUEL }
                legal = holdFire
            }
        }
        return LegalActions(legal, excluded)
    }

    /**
     * The action to fly instead of [wanted], or null when [wanted] is not about to kill the plane
     * (or when nothing else would do better). Prefers the smallest change: the same steering with
     * the gun on before any change of direction.
     */
    fun safetyOverride(world: World, wanted: Action): Action? {
        if (world.over || survives(world, wanted, SAFETY_TICKS, RECOVERY_TICKS)) return null
        // Same steering first (which, with `wanted` itself excluded, can only mean the gun toggled),
        // then the smallest change of direction, keeping the gun as it was where possible.
        val byCloseness = Action.entries.filter { it != wanted }.sortedWith(
            compareBy<Action>({ abs(it.steer - wanted.steer) }, { if (it.fire == wanted.fire) 0 else 1 }),
        )
        byCloseness.firstOrNull { survives(world, it, SAFETY_TICKS, RECOVERY_TICKS) }?.let { return it }

        val horizon = SAFETY_TICKS + RECOVERY_TICKS
        val wantedLasts = survivalTicks(world, wanted, horizon)
        val best = byCloseness.maxBy { survivalTicks(world, it, horizon) }
        return if (survivalTicks(world, best, horizon) > wantedLasts) best else null
    }

    /**
     * True when holding [first] for [holdTicks] keeps the plane alive and, from there, at least one
     * constant action survives [recoveryTicks] more. Running out of fuel does not count against an
     * action: every action burns the same fuel, so it cannot tell them apart.
     */
    fun survives(world: World, first: Action, holdTicks: Int, recoveryTicks: Int): Boolean {
        val probe = world.copy()
        repeat(holdTicks) {
            probe.step(first)
            if (fatal(probe)) return false
            if (probe.over) return true
        }
        if (survivesHolding(probe, first, recoveryTicks)) return true
        return Action.entries.any { it != first && survivesHolding(probe, it, recoveryTicks) }
    }

    /** Ticks survived holding [action], up to [ticks]. */
    fun survivalTicks(world: World, action: Action, ticks: Int): Int {
        val probe = world.copy()
        for (t in 0 until ticks) {
            probe.step(action)
            if (fatal(probe)) return t
            if (probe.over) return ticks
        }
        return ticks
    }

    private fun survivesHolding(start: World, action: Action, ticks: Int): Boolean {
        val probe = start.copy()
        repeat(ticks) {
            probe.step(action)
            if (fatal(probe)) return false
            if (probe.over) return true
        }
        return true
    }

    private fun fatal(world: World): Boolean = world.over && world.death != DeathCause.FUEL

    /** A live depot ahead, within the gun's reach, that a shot fired now would hit. */
    fun depotInLineOfFire(world: World): Boolean = world.depots.any { depot ->
        depot.alive &&
            depot.y > world.playerY &&
            depot.y - world.playerY < Rules.VIEW_ROWS &&
            abs(depot.x - world.playerX) < (Depot.WIDTH + Rules.BULLET_W) / 2
    }
}
