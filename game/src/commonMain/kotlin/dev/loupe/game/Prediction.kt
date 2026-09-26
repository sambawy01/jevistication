package dev.loupe.game

import kotlin.math.ceil
import kotlin.math.floor

/**
 * What happens if the plane flies one way: a collision predicted by **stepping the real simulation
 * forward** on a copy of the world, exactly as [Mechanics] checks a move — every boat and heli moves
 * with its real sideways speed, turns round at the banks, and wakes up when the plane comes within
 * [Rules.ACTIVATION_ROWS]; the river scrolls at the level's speed; bullets already in the air (and
 * the ones the plan fires) fly and hit. Nothing is re-derived, so nothing can disagree with the game.
 *
 * The plan for a way is: hold [first] for [Mechanics.HOLD_TICKS] (one held decision: a lane change
 * of about three columns), then fly straight with the same gun setting, for [HORIZON_TICKS].
 *
 * The same plan is also flown in a copy where every enemy stands still. Comparing the two tells
 * *why*: a hit in both is something **in the way**; a hit only when things move is something
 * **crossing in**; a hit only when they stand still is something **moving away** (or shot first).
 */
data class Prediction(
    /** Ticks until the predicted collision, or null when the plan is clear for [HORIZON_TICKS]. */
    val hitTicks: Int?,
    /** What it hits: "boat", "heli", "land" or "bridge"; null when clear. */
    val hitWith: String? = null,
    /** True when the enemy hit is not in the way now and moves into it. */
    val crossing: Boolean = false,
    /** An enemy that is in the way now but will have moved out of it: its kind, else null. */
    val leaving: String? = null,
    /** The first enemy the plan's own shots destroy, if any: its kind. */
    val shoots: String? = null,
    /** Whether this way reaches fuel before the tank is empty ([FuelOutlook]); null when not asked. */
    val fuel: FuelOutlook? = null,
) {
    /** No collision predicted within [HORIZON_TICKS]. Says nothing about fuel: see [FuelOutlook.runsDry]. */
    val clear: Boolean get() = hitTicks == null

    companion object {
        /**
         * 1.5 s (90 ticks). Why: a lane change of three columns takes about 0.2 s and a decision
         * lands 0.1–0.2 s after it is asked, so 1.5 s is seven or more decisions of warning; beyond
         * it the plane can cross the whole channel (14 columns a second) before arriving, so a hit
         * further out is always still avoidable later. It is also inside the 14 rows (2 s at level
         * 1) at which enemies start moving, so what is predicted is almost always already moving.
         */
        const val HORIZON_TICKS: Int = 90

        /**
         * Predicts the plan that holds [first], then flies straight with its gun setting; with
         * [fuel], also whether that way reaches fuel in time ([FuelOutlook.of]).
         */
        fun of(world: World, first: Action, horizon: Int = HORIZON_TICKS, fuel: Boolean = true): Prediction {
            val moving = fly(world.copy(), first, horizon)
            // With nothing moving, standing still changes nothing: skip the second flight.
            val frozen = if (world.enemies.none { it.alive && it.vx != 0.0 }) {
                moving
            } else {
                val frozenWorld = world.copy()
                frozenWorld.enemies.forEach { it.vx = 0.0 }
                fly(frozenWorld, first, horizon)
            }
            val hitEnemy = moving.hitEnemy
            val crossing = hitEnemy != null && frozen.hitEnemy != hitEnemy
            val leaving = frozen.hitEnemy
                ?.takeIf { it != hitEnemy && (moving.hitTicks == null || moving.hitTicks > frozen.hitTicks!!) }
                ?.takeIf { key -> key !in moving.destroyed }
            return Prediction(
                hitTicks = moving.hitTicks,
                hitWith = moving.hitWith,
                crossing = crossing,
                leaving = leaving?.kind?.word,
                shoots = moving.destroyed.firstOrNull()?.kind?.word,
                fuel = if (fuel) FuelOutlook.of(world, first) else null,
            )
        }

        /** The plan's action at tick [t] (0-based). */
        fun planned(first: Action, t: Int): Action = if (t < Mechanics.HOLD_TICKS) first else Action.of(0, first.fire)

        /** An enemy, identified across world copies: kind and row never change. */
        private data class Key(val kind: EnemyKind, val y: Double)

        private class Flight(val hitTicks: Int?, val hitWith: String?, val hitEnemy: Key?, val destroyed: List<Key>)

        private fun fly(probe: World, first: Action, horizon: Int): Flight {
            val destroyed = mutableListOf<Key>()
            for (t in 0 until horizon) {
                val before = probe.enemies.filter { it.alive }.map { Key(it.kind, it.y) }
                probe.step(planned(first, t))
                for (e in probe.events) {
                    if (e is GameEvent.Destroyed) before.firstOrNull { it.y == e.y && it.kind.word == e.what }?.let { destroyed += it }
                }
                if (probe.over) {
                    return when (probe.death) {
                        DeathCause.ENEMY -> {
                            val plane = probe.player
                            val e = probe.enemies.first { it.alive && it.overlaps(plane) }
                            Flight(t + 1, e.kind.word, Key(e.kind, e.y), destroyed)
                        }
                        DeathCause.BANK -> Flight(t + 1, "land", null, destroyed)
                        DeathCause.BRIDGE -> Flight(t + 1, "bridge", null, destroyed)
                        else -> Flight(null, null, null, destroyed) // fuel: every way burns the same
                    }
                }
            }
            return Flight(null, null, null, destroyed)
        }
    }
}

/**
 * Whether one way reaches fuel before the tank is empty — running dry is a crash too. Predicted, like
 * the collisions, by **stepping the real simulation** on a copy of the world ([World.fuelProbe]: the
 * river, the plane, its tank at the level's real burn rate and scroll speed, the depots and the bullets
 * already in the air; enemies and bridges left out, because what is in the way is [Prediction]'s job).
 *
 * The plan for a way is the collision plan's — hold the way's move (with its gun setting, so a shot
 * that would destroy the depot counts) for [Mechanics.HOLD_TICKS], then fly on straight — with two
 * differences: the way that heads for a depot ([heads]: its side, or straight when the depot is
 * nearly in line) keeps steering to it ([home]); and every way goes round land (a steer that would
 * put the plane on land within [LOOK_TICKS] is not taken), because hitting land is the collision
 * prediction's to report. Gun off after the hold. Each depot on the map ahead is tried, nearest
 * first; the first tick the plane refuels is [refuelTicks]. So "this way reaches fuel" means what
 * "this way is safe" means: fly it, and this is what happens.
 *
 * **The fuel horizon** is not [Prediction.HORIZON_TICKS] (1.5 s): a depot can be up to the top of the
 * view, 29 rows ahead — about 4 s at level 1. So each depot's flight runs until the plane refuels,
 * dies, passes the depot, or [FUEL_HORIZON_TICKS] (6 s) elapse, which covers the whole view at every
 * level. Beyond the view nothing is known, so the game's own spacing is used: depots come every
 * [Difficulty.depotGapMin]..[Difficulty.depotGapMax] rows, and none before the first row not yet on
 * the map.
 *
 * When no depot on the map is reached, the way **runs dry** ([dryTicks]) if the tank empties (at the
 * current level's burn rate and scroll speed) before the fuel the plane can count on: the
 * [DEPOTS_COUNTED]rd depot after the last one on the map, at the typical (mean) spacing — the next one
 * with two to spare, because the next one is often across an island or behind a boat (measured
 * 2026-09-26: a third of the depots flown past were neither reached nor shot). Counting on only the
 * next one, or on two, warned too late on the dev seeds (docs/BUILD.md). At level 1 this reads a way
 * as running dry below about 60% — the old fuel gate's threshold, now per way, from the projection.
 */
data class FuelOutlook(
    /** Ticks until the plane first refuels flying this way; null when it reaches no depot on the map. */
    val refuelTicks: Int?,
    /** The depot it refuels on, where it is now: rows ahead of the plane and columns across. */
    val depotAhead: Double? = null,
    val depotAcross: Double? = null,
    /**
     * When no depot on the map is reached: ticks until the tank is empty, if that is before the fuel the
     * plane can count on ([unseenGap]); else null. (With no depot on the map at all no way reaches fuel,
     * and running dry is no one way's fault: [Observation.predictedCrash] and the words ignore it then.)
     */
    val dryTicks: Int? = null,
    /** Ticks until the tank is empty at the current burn rate, refuelling nowhere. */
    val emptyTicks: Int = 0,
) {
    val reaches: Boolean get() = refuelTicks != null
    val runsDry: Boolean get() = dryTicks != null

    companion object {
        /** 6 s: longer than it takes to fly past the top of the view at any level (29 rows at 7 rows/s is 4.1 s). */
        const val FUEL_HORIZON_TICKS: Int = 360

        /** How far ahead the fuel flight checks a steer for land, in ticks (about a row at level 1). */
        const val LOOK_TICKS: Int = 10

        /** Columns off the depot's centre at which the fuel flight stops steering. */
        const val AIM: Double = 0.3

        /** At most this many depots on the map are tried, nearest first. */
        const val MAX_DEPOTS: Int = 3

        /** The depots, after the last one on the map, that a way which reaches none of them relies on. */
        const val DEPOTS_COUNTED: Int = 3

        /**
         * Rows from the last depot on the map to the fuel the plane can count on, at [level]'s spacing:
         * [DEPOTS_COUNTED] mean gaps. Swappable only by the measurement tests (the dev-seed choice).
         */
        internal var unseenGap: (Difficulty, Int) -> Int = { d, level -> DEPOTS_COUNTED * (d.depotGapMin(level) + d.depotGapMax(level)) / 2 }

        fun of(world: World, first: Action): FuelOutlook {
            val level = world.level
            val d = world.difficulty
            val perTick = d.fuelDrain(level) * Rules.DT
            val emptyTicks = ceil(world.fuel / perTick - 1e-9).toInt().coerceAtLeast(0)
            val base = world.fuelProbe()
            val py = world.playerY
            val targets = base.depots.filter { it.alive && it.y + it.height > py }.sortedBy { it.y }.take(MAX_DEPOTS)
            for (target in targets) {
                val reached = fly(base.copy(), first, target) ?: continue
                return FuelOutlook(reached, target.y - py, target.x - world.playerX, null, emptyTicks)
            }
            // Nothing on the map is reached: the fuel counted on is beyond it.
            val last = world.depots.maxOfOrNull { it.y }?.let { floor(it).toInt() }
            val unseen = maxOf(world.firstUnspawnedRow, last?.let { it + unseenGap(d, d.level(it)) } ?: 0)
            val rowsToUnseen = unseen - (py + Rules.PLAYER_H)
            val ticksToUnseen = ceil(rowsToUnseen / (d.scroll(level) * Rules.DT)).toInt()
            return FuelOutlook(null, dryTicks = emptyTicks.takeIf { it < ticksToUnseen }, emptyTicks = emptyTicks)
        }

        /** Flies [probe] for [target]; the tick the plane first refuels, or null. */
        private fun fly(probe: World, first: Action, target: Depot): Int? {
            val heads = heads(first.steer, target.x - probe.playerX)
            for (t in 0 until FUEL_HORIZON_TICKS) {
                val before = probe.tally.refuelTicks
                probe.step(planned(probe, first, t, target, heads))
                if (probe.tally.refuelTicks > before) return t + 1
                if (probe.over) return null
                val live = probe.depots.firstOrNull { it.y == target.y && it.x == target.x }
                if (live == null || !live.alive || live.y + live.height <= probe.playerY) return null
            }
            return null
        }

        /**
         * True when a way steering [steer] heads for a depot [across] columns off the plane's line:
         * its side, or straight on when it is within [PathText.TOWARD] of the line.
         */
        fun heads(steer: Int, across: Double): Boolean = steer == when {
            across < -PathText.TOWARD -> -1
            across > PathText.TOWARD -> 1
            else -> 0
        }

        /**
         * The fuel plan's move at tick [t]: [first] for [Mechanics.HOLD_TICKS]; then, on the way that
         * [heads] for [target], toward it ([home]); on any other way, straight on ([cruise]).
         */
        internal fun planned(p: World, first: Action, t: Int, target: Depot, heads: Boolean): Action = when {
            t < Mechanics.HOLD_TICKS -> first
            heads -> home(p, target)
            else -> cruise(p)
        }

        /** Toward [target], round land: gun off. */
        internal fun home(p: World, target: Depot): Action {
            val dx = target.x - p.playerX
            val order = when {
                dx > AIM -> listOf(1, 0, -1)
                dx < -AIM -> listOf(-1, 0, 1)
                dx >= 0 -> listOf(0, 1, -1)
                else -> listOf(0, -1, 1)
            }
            return Action.of(order.firstOrNull { !landSoon(p, it) } ?: order.first(), false)
        }

        /** Straight on, round land: gun off. */
        internal fun cruise(p: World): Action {
            val order = if (p.playerX > Rules.HALF) listOf(0, -1, 1) else listOf(0, 1, -1)
            return Action.of(order.firstOrNull { !landSoon(p, it) } ?: 0, false)
        }

        /** True when holding [steer] puts the plane over land within [LOOK_TICKS], by the river's rows. */
        private fun landSoon(p: World, steer: Int): Boolean {
            val d = p.difficulty
            val level = p.level
            val half = Rules.PLAYER_W / 2
            val dxPerTick = steer * d.lateral(level) * Rules.DT
            val dyPerTick = d.scroll(level) * Rules.DT
            for (k in 1..LOOK_TICKS) {
                val x = (p.playerX + dxPerTick * k).coerceIn(half, Rules.COLUMNS - half)
                val y = p.playerY + dyPerTick * k
                val lo = floor(y).toInt()
                val hi = floor(y + Rules.PLAYER_H - 1e-9).toInt()
                for (r in lo..hi) if (p.river.row(r).landIn(x - half, x + half)) return true
            }
            return false
        }
    }
}
