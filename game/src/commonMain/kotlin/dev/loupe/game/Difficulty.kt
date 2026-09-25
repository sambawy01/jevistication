package dev.loupe.game

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How hard the river gets as it is flown: a pure function of the **row index**, never of time,
 * input or anything random. That is what keeps the world deterministic: the generator reads the
 * level of the row it is building (in row order, from the seed's one random stream), and the world
 * reads the level of the row under the camera, so the same seed and the same actions give the same
 * world on every platform, and the look-ahead in [Mechanics] stays exact.
 *
 * - [CLASSIC] is the original game: one level for ever, every constant exactly [Rules]' and
 *   [RiverGenerator]'s. The parity goldens are recorded against it and must never move.
 * - [progressive] ramps every [levelRows] rows: faster scroll (and sideways speed with it, so the
 *   passability guarantee — two columns of reach per row — holds at every level), a faster decision
 *   cadence, narrower channels, denser enemies, closer bridges, and fuel that burns faster with
 *   depots further apart.
 * - [rush] is progressive but starts at level [RUSH_START].
 */
class Difficulty private constructor(
    val name: String,
    val progressive: Boolean,
    val startLevel: Int,
    /** Rows per level. */
    val levelRows: Int,
) {
    /** The level of river row [row] (1 for [CLASSIC]). */
    fun level(row: Int): Int {
        if (!progressive) return 1
        return min(MAX_LEVEL, startLevel + max(row, 0) / levelRows)
    }

    /** The level of world height [y]. */
    fun levelAt(y: Double): Int = level(floor(y).toInt())

    /** The first row of [level], or null when this difficulty never reaches it. */
    fun firstRow(level: Int): Int? = when {
        !progressive -> if (level == 1) 0 else null
        level < startLevel || level > MAX_LEVEL -> null
        else -> (level - startLevel) * levelRows
    }

    /** Speed up over the classic game at [level]: 1.0, then +[SPEED_STEP] a level. */
    fun speedFactor(level: Int): Double = if (!progressive) 1.0 else 1.0 + SPEED_STEP * (level - 1)

    /** Forward speed, rows per second. */
    fun scroll(level: Int): Double = if (!progressive) Rules.SCROLL else Rules.SCROLL * speedFactor(level)

    /** Sideways speed, columns per second: always twice the scroll (see [RiverGenerator]). */
    fun lateral(level: Int): Double = if (!progressive) Rules.LATERAL else Rules.LATERAL * speedFactor(level)

    fun fuelDrain(level: Int): Double = if (!progressive) Rules.FUEL_DRAIN_PER_S else Rules.FUEL_DRAIN_PER_S * (1.0 + FUEL_STEP * (level - 1))

    /**
     * Ticks between decision requests at [level], from the session's [base] interval. The cadence
     * rises with the square of the speed-up, so the pilot must decide more often *per row* as the
     * river quickens: at base 6, 10/s at level 1, 12/s at 2, 15/s at 3, 20/s at 4–5, 30/s from 6.
     * Never below [MIN_INTERVAL] ticks.
     */
    fun decisionInterval(level: Int, base: Int): Int {
        if (!progressive) return base
        val f = speedFactor(level)
        return max(MIN_INTERVAL, floor(base / (f * f) + 0.5).toInt()).coerceAtMost(base)
    }

    fun minChannel(level: Int): Int = if (!progressive || level < 3) RiverGenerator.MIN_CHANNEL else NARROW_CHANNEL
    fun maxBank(level: Int): Int = if (!progressive) RiverGenerator.MAX_BANK else RiverGenerator.MAX_BANK + min(2, (level - 1) / 3)
    fun enemyEvery(level: Int): Int = if (!progressive || level < 4) RiverGenerator.ENEMY_EVERY else 2
    fun enemyChance(level: Int): Double =
        if (!progressive) RiverGenerator.ENEMY_CHANCE else min(0.6, RiverGenerator.ENEMY_CHANCE + 0.05 * (level - 1))
    fun depotGapMin(level: Int): Int = if (!progressive) RiverGenerator.DEPOT_GAP_MIN else RiverGenerator.DEPOT_GAP_MIN + 3 * (level - 1)
    fun depotGapMax(level: Int): Int = if (!progressive) RiverGenerator.DEPOT_GAP_MAX else RiverGenerator.DEPOT_GAP_MAX + 4 * (level - 1)

    /** Rows from one bridge to the next when the first sits at [level]. */
    fun bridgeSpacing(level: Int): Int =
        if (!progressive) RiverGenerator.BRIDGE_EVERY else max(MIN_BRIDGE_SPACING, RiverGenerator.BRIDGE_EVERY - 12 * (level - 1))

    override fun toString(): String = name

    companion object {
        const val MAX_LEVEL: Int = 9
        const val LEVEL_ROWS: Int = 200
        const val RUSH_START: Int = 4
        const val SPEED_STEP: Double = 0.12
        const val FUEL_STEP: Double = 0.12
        const val MIN_INTERVAL: Int = 2
        const val NARROW_CHANNEL: Int = 4
        const val MIN_BRIDGE_SPACING: Int = 80

        /** The original game, unchanged. */
        val CLASSIC: Difficulty = Difficulty("classic", progressive = false, startLevel = 1, levelRows = Int.MAX_VALUE)

        val PROGRESSIVE: Difficulty = progressive()
        val RUSH: Difficulty = rush()

        fun progressive(levelRows: Int = LEVEL_ROWS): Difficulty {
            require(levelRows >= 1) { "levelRows must be positive, was $levelRows" }
            return Difficulty("progressive", progressive = true, startLevel = 1, levelRows = levelRows)
        }

        fun rush(levelRows: Int = LEVEL_ROWS): Difficulty {
            require(levelRows >= 1) { "levelRows must be positive, was $levelRows" }
            return Difficulty("rush", progressive = true, startLevel = RUSH_START, levelRows = levelRows)
        }
    }
}
