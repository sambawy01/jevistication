package dev.loupe.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Something seen ahead, relative to the plane: [ahead] rows up, [across] columns right (negative is left). */
data class Sighting(val what: String, val ahead: Double, val across: Double, val moving: Int = 0)

/**
 * What lies along one way the plane can fly: [steer] (-1 left, 0 straight, 1 right) held for about
 * one decision — a lane change of up to [Observation.LANE] columns — then straight on, looked at
 * [Observation.PATH_ROWS] rows ahead. Rows until the first land, the first enemy (where it will be
 * when the plane gets there) and the nearest fuel depot on that path; null when there is none.
 *
 * It is a sensor, not a verdict: it says what is in the way, not which way to go.
 */
data class PathAhead(
    val steer: Int,
    val landRows: Int?,
    val enemyRows: Int?,
    val enemy: String? = null,
    val depotRows: Int? = null,
)

/**
 * An immutable snapshot of what a pilot may know, taken on the simulation thread.
 *
 * Pilots run on another thread and see only this, never the live [World]: a decision computed
 * while the world keeps moving must read a consistent picture, and a pilot must not be able to
 * reach the simulation at all. Everything is relative to the plane, because "the bank is two
 * columns to your left" is a fact about the decision and "the bank is at column 7" is not.
 */
data class Observation(
    val tick: Long,
    val playerX: Double,
    val fuelPercent: Int,
    val weaponReady: Boolean,
    val score: Int,
    /** Columns of open water to the plane's left and right on its own row. */
    val waterLeft: Double,
    val waterRight: Double,
    /** Rows until the plane's current column turns to land, if it does within [NEAR_ROWS]. */
    val landAheadRows: Int?,
    /** The channels [FAR_ROW] rows ahead, as spans relative to the plane. */
    val farChannels: List<Span>,
    /** Live enemies ahead, nearest first, at most [MAX_THREATS]. */
    val threats: List<Sighting>,
    val depot: Sighting?,
    val bridgeAheadRows: Int?,
    val legal: LegalActions,
    /** What lies along each way the plane can fly, left, straight and right (see [PathAhead]). */
    val paths: List<PathAhead> = emptyList(),
) {
    /** The [PathAhead] for [steer], if one was looked at. */
    fun path(steer: Int): PathAhead? = paths.firstOrNull { it.steer == steer }

    companion object {
        const val NEAR_ROWS: Int = 6
        const val FAR_ROW: Int = 9
        const val MAX_THREATS: Int = 3
        const val THREAT_RANGE: Double = 16.0

        /** How far each [PathAhead] looks, in rows. */
        const val PATH_ROWS: Int = 10

        /** The sideways shift of a steered path, in columns: about one held decision of steering. */
        const val LANE: Double = 3.0

        /** Clearance added around the plane when asking whether something is on a path, in columns. */
        const val PATH_MARGIN: Double = 0.3

        fun of(world: World, legal: LegalActions): Observation {
            val x = world.playerX
            val py = world.playerY
            val here = world.river.rowAt(py + Rules.PLAYER_H / 2)
            val channel = here.channelAt(x) ?: here.water.minBy { abs(it.center - x) }
            val base = floor(py).toInt()
            val landAhead = (1..NEAR_ROWS).firstOrNull { k -> world.river.row(base + k).channelAt(x) == null }
            val far = world.river.row(base + FAR_ROW).water.map { Span(it.from - x, it.to - x) }

            val threats = world.enemies
                .filter { it.alive && it.y + it.height > py && it.y - py < THREAT_RANGE }
                .sortedBy { it.y }
                .take(MAX_THREATS)
                .map { Sighting(it.kind.word, it.y - py, it.x - x, sign(it.vx)) }
            val depot = world.depots
                .filter { it.alive && it.y + it.height > py }
                .minByOrNull { it.y }
                ?.let { Sighting("depot", it.y - py, it.x - x) }
            val bridge = world.bridges.filter { it.alive && it.y >= py }.minByOrNull { it.y }

            return Observation(
                tick = world.tick,
                playerX = x,
                fuelPercent = (world.fuel / Rules.FUEL_MAX * 100).roundToInt(),
                weaponReady = world.weaponReady,
                score = world.score,
                waterLeft = x - Rules.PLAYER_W / 2 - channel.from,
                waterRight = channel.to - x - Rules.PLAYER_W / 2,
                landAheadRows = landAhead,
                farChannels = far,
                threats = threats,
                depot = depot,
                bridgeAheadRows = bridge?.let { (it.y - py).roundToInt() },
                legal = legal,
                paths = listOf(-1, 0, 1).map { path(world, it) },
            )
        }

        /**
         * Looks along [steer]: the plane's centre after `k` rows is `x + steer * min(k * lateral /
         * scroll, LANE)` (it cannot leave the screen). Enemies are placed where their current
         * sideways speed will have taken them by the time the plane reaches their row.
         */
        private fun path(world: World, steer: Int): PathAhead {
            val x = world.playerX
            val py = world.playerY
            val level = world.level
            val scroll = world.difficulty.scroll(level)
            val perRow = world.difficulty.lateral(level) / scroll
            val half = Rules.PLAYER_W / 2
            fun xAt(rows: Double): Double = (x + steer * minOf(rows * perRow, LANE)).coerceIn(half, Rules.COLUMNS - half)

            val base = floor(py).toInt()
            val land = (1..PATH_ROWS).firstOrNull { k ->
                val px = xAt(k.toDouble())
                world.river.row(base + k).landIn(px - half, px + half)
            }
            val enemy = world.enemies
                .filter { it.alive && it.y + it.height > py && it.y - py <= PATH_ROWS }
                .filter { e ->
                    val ahead = (e.y - py).coerceAtLeast(0.0)
                    val ex = e.x + e.vx * (ahead / scroll)
                    abs(ex - xAt(ahead)) < (e.width + Rules.PLAYER_W) / 2 + PATH_MARGIN
                }
                .minByOrNull { it.y }
            val depot = world.depots
                .filter { it.alive && it.y + it.height > py && it.y - py <= PATH_ROWS }
                .filter { d -> abs(d.x - xAt((d.y - py).coerceAtLeast(0.0))) < (Depot.WIDTH + Rules.PLAYER_W) / 2 }
                .minByOrNull { it.y }
            return PathAhead(
                steer = steer,
                landRows = land,
                enemyRows = enemy?.let { (it.y - py).coerceAtLeast(0.0).roundToInt() },
                enemy = enemy?.kind?.word,
                depotRows = depot?.let { (it.y - py).coerceAtLeast(0.0).roundToInt() },
            )
        }

        private fun sign(v: Double): Int = if (v > 0) 1 else if (v < 0) -1 else 0
    }
}

/**
 * The text state the model reads: a few short clauses, relative to the plane.
 *
 * **Kept short on purpose — build risk 13.** Laya INT8 on a desktop CPU took ~42–46 ms (P50) for a
 * short question and ~1.1 s at 1,024 tokens, and latency scales with tokens. A decision many times
 * a second needs the short end, so this aims well under [MAX_CHARS] characters (roughly 60–90
 * tokens), and a test holds it there. It is built by code from exact facts, never summarised, and
 * it contains nothing from outside the game.
 */
object StateText {
    /** Hard ceiling, asserted by tests; typical states are well under it. */
    const val MAX_CHARS: Int = 360

    fun describe(o: Observation): String = buildString {
        append("fuel ").append(o.fuelPercent).append('%')
        if (o.fuelPercent < (Rules.FUEL_LOW / Rules.FUEL_MAX * 100)) append(" low")
        append(if (o.weaponReady) ", gun ready." else ", gun reloading.")
        append(" water ").append(cols(o.waterLeft)).append(" left, ").append(cols(o.waterRight)).append(" right.")
        o.landAheadRows?.let { append(" land dead ahead in ").append(it).append(" rows.") }
        append(" far ahead water ")
        append(o.farChannels.joinToString(" and ") { "${side(it.from)} to ${side(it.to)}" }).append('.')
        for (t in o.threats) {
            append(' ').append(t.what).append(' ').append(rows(t.ahead)).append(" ahead ").append(side(t.across))
            when (t.moving) {
                1 -> append(" moving right")
                -1 -> append(" moving left")
            }
            append('.')
        }
        o.depot?.let { append(" fuel depot ").append(rows(it.ahead)).append(" ahead ").append(side(it.across)).append('.') }
        o.bridgeAheadRows?.let { append(" bridge ").append(it).append(" ahead.") }
    }

    private fun cols(v: Double): Int = v.coerceAtLeast(0.0).roundToInt()

    private fun rows(v: Double): Int = v.coerceAtLeast(0.0).roundToInt()

    private fun side(dx: Double): String {
        val n = dx.roundToInt()
        return when {
            n == 0 -> "center"
            n < 0 -> "${-n} left"
            else -> "$n right"
        }
    }
}

/**
 * The words the model pilot reads: for each way, what lies along it (from [PathAhead]), and a
 * one-clause scene. Discrete and relative — "land close", "boat very close", "fuel that way" —
 * never raw columns: measured 2026-09-25, Laya read the numeric [StateText] near chance.
 *
 * Built by code from exact facts, never summarised, nothing from outside the game. Distances:
 * up to 3 rows "very close", up to 6 "close", beyond that "ahead" (a path looks
 * [Observation.PATH_ROWS] rows).
 */
object PathText {
    fun near(rows: Int): String = when {
        rows <= 3 -> "very close"
        rows <= 6 -> "close"
        else -> "ahead"
    }

    /** What lies along [steer]; "open water" when nothing does. */
    fun describe(o: Observation, steer: Int): String {
        val p = o.path(steer) ?: return OPEN
        val parts = mutableListOf<String>()
        p.landRows?.let { parts += "land ${near(it)}" }
        p.enemyRows?.let { parts += "${p.enemy ?: "enemy"} ${near(it)}" }
        if (o.fuelPercent < FUEL_WANTED) {
            val onPath = p.depotRows
            if (onPath != null) {
                parts += "fuel ${near(onPath)}"
            } else {
                o.depot?.let { d ->
                    val side = when {
                        d.across < -1.0 -> -1
                        d.across > 1.0 -> 1
                        else -> 0
                    }
                    if (side == steer) parts += "fuel that way"
                }
            }
        }
        return if (parts.isEmpty()) OPEN else parts.joinToString(", ")
    }

    /** The scene beside the ways: the tank, and "low" under half. */
    fun scene(o: Observation): String = if (o.fuelPercent < FUEL_LOW) "fuel ${o.fuelPercent}%, low" else "fuel ${o.fuelPercent}%"

    const val OPEN: String = "open water"

    /** Below this fuel percentage, depots are mentioned at all. */
    const val FUEL_WANTED: Int = 90

    /** Below this fuel percentage, the scene says "low". */
    const val FUEL_LOW: Int = 50
}
