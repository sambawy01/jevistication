package dev.loupe.game

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
) {
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

        /** Predicts the plan that holds [first], then flies straight with its gun setting. */
        fun of(world: World, first: Action, horizon: Int = HORIZON_TICKS): Prediction {
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
