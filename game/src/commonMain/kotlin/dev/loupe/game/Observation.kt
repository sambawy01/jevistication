package dev.loupe.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Something seen ahead, relative to the plane: [ahead] rows up, [across] columns right (negative is left). */
data class Sighting(val what: String, val ahead: Double, val across: Double, val moving: Int = 0)

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
) {
    companion object {
        const val NEAR_ROWS: Int = 6
        const val FAR_ROW: Int = 9
        const val MAX_THREATS: Int = 3
        const val THREAT_RANGE: Double = 16.0

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
