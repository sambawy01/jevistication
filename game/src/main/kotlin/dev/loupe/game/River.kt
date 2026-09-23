package dev.loupe.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** A half-open span of columns `[from, to)`. */
data class Span(val from: Double, val to: Double) {
    val width: Double get() = to - from
    val center: Double get() = (from + to) / 2
    operator fun contains(x: Double): Boolean = x >= from && x < to
}

/** Something the generator places on a row; it becomes a live entity when the row scrolls into view. */
sealed interface Spawn {
    val x: Double

    data class Enemy(val kind: EnemyKind, override val x: Double, val vx: Double) : Spawn
    data class Depot(override val x: Double) : Spawn
}

/**
 * One row of river, covering `y` in `[index, index + 1)`.
 *
 * Land is the left bank `[0, left)`, an optional island `[islandFrom, islandTo)` and the right bank
 * `[COLUMNS - right, COLUMNS)`; everything else is water. Banks and islands are whole columns, so
 * the terrain is exact and the collision test is exact.
 */
data class Row(
    val index: Int,
    val left: Int,
    val right: Int,
    val islandFrom: Int,
    val islandTo: Int,
    /** True when a bridge spans this row's water. It must be shot before the plane reaches it. */
    val bridge: Boolean,
    val spawns: List<Spawn>,
) {
    val hasIsland: Boolean get() = islandTo > islandFrom

    /** The water channels on this row, left to right: one, or two around an island. */
    val water: List<Span>
        get() = if (hasIsland) {
            listOf(Span(left.toDouble(), islandFrom.toDouble()), Span(islandTo.toDouble(), (Rules.COLUMNS - right).toDouble()))
        } else {
            listOf(Span(left.toDouble(), (Rules.COLUMNS - right).toDouble()))
        }

    /** True when any land lies in the open column range `(x0, x1)`. */
    fun landIn(x0: Double, x1: Double): Boolean =
        x0 < left ||
            x1 > Rules.COLUMNS - right ||
            (hasIsland && x0 < islandTo && x1 > islandFrom)

    /** The channel containing [x], or null when [x] is over land. */
    fun channelAt(x: Double): Span? = water.firstOrNull { x in it }
}

/**
 * Procedural river, generated row by row from a seed.
 *
 * The bank shape is the prototype's idea (river-raid-2k's `MapChunk._build`): the bank width on
 * each side follows the magnitude of Perlin noise sampled down the river, and when the river is
 * wide an island rises in the middle. What is new is the guarantee that matters to a pilot that
 * cannot see intent, only walls: **the river is always passable.**
 *
 * - Every wall moves at most one column per row ([MAX_WALL_STEP]). The plane crosses two columns per
 *   row ([Rules.LATERAL] / [Rules.SCROLL]), so it can always out-steer a closing wall.
 * - Every channel is at least [MIN_CHANNEL] columns wide — several plane-widths — including the two
 *   channels either side of an island, because the island is clamped after the banks move.
 * - Islands grow from nothing, one column per side per row, so one never appears under the plane.
 *
 * Generation is strictly sequential and depends on nothing but the seed, so any code that reads row
 * `n` — the live game, a look-ahead copy of it, a test — sees the same row.
 */
class RiverGenerator(seed: Long) {
    private val random = Random(seed)
    private val noiseOffset = random.nextDouble(0.0, 1000.0)
    private var bank = 3
    private var islandHalf = 0
    private var nextDepotRow = Rules.SAFE_START_ROWS + random.nextInt(8, 20)
    private var index = 0

    fun next(): Row {
        val i = index++
        val nearBridge = bridgeDistance(i) <= BRIDGE_APPROACH

        val n = abs(Noise.perlin(noiseOffset * FREQUENCY, (i + noiseOffset) * FREQUENCY))
        var bankTarget = (1 + floor(n * Rules.HALF).toInt()).coerceIn(MIN_BANK, MAX_BANK)
        var islandTarget = (Rules.HALF - bankTarget - ISLAND_GAP).coerceAtLeast(0)
        if (i < Rules.SAFE_START_ROWS) {
            bankTarget = 3
            islandTarget = 0
        }
        if (nearBridge) bankTarget = BRIDGE_BANK
        // No island while the banks close in on a bridge, nor while they open out after it: an
        // island rising dead centre just past a bridge, where every pilot has lined up to shoot it,
        // is a trap rather than a challenge.
        if (bridgeDistance(i) <= BRIDGE_APPROACH + ISLAND_CLEARANCE) islandTarget = 0

        bank += (bankTarget - bank).coerceIn(-MAX_WALL_STEP, MAX_WALL_STEP)
        islandHalf += (islandTarget - islandHalf).coerceIn(-MAX_WALL_STEP, MAX_WALL_STEP)
        // Clamped after the bank moved, so a widening bank squeezes the island, never the channel.
        islandHalf = islandHalf.coerceIn(0, (Rules.HALF - bank - MIN_CHANNEL).coerceAtLeast(0))

        val row = Row(
            index = i,
            left = bank,
            right = bank,
            islandFrom = Rules.HALF - islandHalf,
            islandTo = Rules.HALF + islandHalf,
            bridge = i > 0 && i % BRIDGE_EVERY == 0,
            spawns = emptyList(),
        )
        return row.copy(spawns = spawnsFor(row))
    }

    private fun spawnsFor(row: Row): List<Spawn> {
        if (row.index < Rules.SAFE_START_ROWS || bridgeDistance(row.index) <= NO_SPAWN_NEAR_BRIDGE) {
            // Skipping the random draws here is still deterministic: generation is sequential.
            return emptyList()
        }
        val spawns = mutableListOf<Spawn>()
        if (row.index >= nextDepotRow) {
            spawns += Spawn.Depot(placeIn(row, DEPOT_MARGIN))
            nextDepotRow = row.index + random.nextInt(DEPOT_GAP_MIN, DEPOT_GAP_MAX + 1)
        } else if (row.index % ENEMY_EVERY == 0 && random.nextDouble() < ENEMY_CHANCE) {
            val heli = random.nextDouble() < HELI_SHARE
            val kind = if (heli) EnemyKind.HELI else EnemyKind.BOAT
            val moving = heli || random.nextDouble() < MOVING_BOAT_SHARE
            val direction = if (random.nextBoolean()) 1.0 else -1.0
            spawns += Spawn.Enemy(kind, placeIn(row, kind.width / 2 + 0.5), if (moving) direction * kind.speed else 0.0)
        }
        return spawns
    }

    /** A random column inside one of the row's channels, at least [margin] from its walls. */
    private fun placeIn(row: Row, margin: Double): Double {
        val channels = row.water.filter { it.width > 2 * margin }
        val channel = if (channels.isEmpty()) row.water.maxBy { it.width } else channels[random.nextInt(channels.size)]
        if (channel.width <= 2 * margin) return channel.center
        return random.nextDouble(channel.from + margin, channel.to - margin)
    }

    companion object {
        /** The prototype's noise frequency down the river. */
        const val FREQUENCY: Double = 0.068
        const val MIN_BANK: Int = 1
        const val MAX_BANK: Int = 8
        const val MAX_WALL_STEP: Int = 1
        const val MIN_CHANNEL: Int = 5

        /** The prototype's island rule: an island appears when the banks leave this much water. */
        const val ISLAND_GAP: Int = 7

        const val BRIDGE_EVERY: Int = 140
        const val BRIDGE_BANK: Int = 9
        const val BRIDGE_APPROACH: Int = 14
        const val NO_SPAWN_NEAR_BRIDGE: Int = 4
        const val ISLAND_CLEARANCE: Int = 10

        const val ENEMY_EVERY: Int = 3
        const val ENEMY_CHANCE: Double = 0.3
        const val HELI_SHARE: Double = 0.4
        const val MOVING_BOAT_SHARE: Double = 0.5
        const val DEPOT_GAP_MIN: Int = 40
        const val DEPOT_GAP_MAX: Int = 65
        const val DEPOT_MARGIN: Double = 1.2

        /** Rows to the nearest bridge row (bridges sit on multiples of [BRIDGE_EVERY], from the first). */
        fun bridgeDistance(i: Int): Int {
            val below = (i / BRIDGE_EVERY) * BRIDGE_EVERY
            val above = below + BRIDGE_EVERY
            val dBelow = if (below == 0) Int.MAX_VALUE else i - below
            return minOf(dBelow, above - i)
        }
    }
}

/**
 * The river as a lazily extended, append-only list of rows.
 *
 * Shared between the live world and its look-ahead copies: rows never change once generated, and
 * generation is sequential, so whichever copy asks first produces exactly the row the others would.
 * Not thread-safe — it is only touched from the simulation thread; pilots receive an [Observation].
 */
class River(val seed: Long) {
    private val generator = RiverGenerator(seed)
    private val rows = ArrayList<Row>()

    fun row(index: Int): Row {
        require(index >= 0) { "row index must not be negative, was $index" }
        while (rows.size <= index) rows += generator.next()
        return rows[index]
    }

    /** The row containing world height [y]. */
    fun rowAt(y: Double): Row = row(floor(y).toInt().coerceAtLeast(0))
}
