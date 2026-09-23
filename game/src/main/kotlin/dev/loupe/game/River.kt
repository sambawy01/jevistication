package dev.loupe.game

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
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
 * wide an island rises in the middle. From section 2 on, a second noise makes the whole river drift
 * sideways ([Difficulty.meander]). What is new is the guarantee that matters to a pilot that cannot
 * see intent, only walls: **the river is always passable, at every section's speed.**
 *
 * - Walls move in whole columns, **at most one column in any row ([MAX_WALL_STEP]), and on average
 *   at most [Difficulty.wallStep] columns per row** — a fixed share of the plane's reach at that
 *   section's speed. The generator earns [Difficulty.wallStep] of move budget per row (never holding
 *   more than one move) and spends one per move. Only one kind of move happens in a row: either the
 *   banks and the island change width (they move different walls), or the whole river drifts one
 *   column — never both, so no wall ever moves two columns at once. In section 1 the budget is a
 *   full move every row and there is no drift, which is exactly the original generator.
 * - Every channel is at least [Difficulty.minChannel] columns wide, including the two channels
 *   either side of an island, because the island is clamped after the banks move — and the clamp
 *   only ever opens a channel.
 * - Islands grow from nothing, one column per side per row, so one never appears under the plane.
 * - The drift never pushes a bank below [MIN_BANK], and within [BRIDGE_APPROACH] + [ISLAND_CLEARANCE]
 *   rows of a bridge it heads back to centre, so every bridge sits between [BRIDGE_BANK]-wide banks.
 *
 * Generation is strictly sequential and depends on nothing but the seed, so any code that reads row
 * `n` — the live game, a look-ahead copy of it, a test — sees the same row. A row's difficulty
 * depends only on its index, so it is part of that determinism.
 */
class RiverGenerator(seed: Long) {
    private val random = Random(seed)
    private val noiseOffset = random.nextDouble(0.0, 1000.0)
    private var bank = 3
    private var islandHalf = 0

    /** Sideways drift of the whole river, columns right of centre. */
    private var drift = 0

    /** Wall moves available: earns [Difficulty.wallStep] a row, never more than one move banked. */
    private var budget = 1.0
    private var nextDepotRow = Rules.SAFE_START_ROWS + random.nextInt(8, 20)
    private var index = 0

    fun next(): Row {
        val i = index++
        val d = Difficulty.forRow(i)
        val nearBridge = bridgeDistance(i) <= BRIDGE_APPROACH
        val straightening = bridgeDistance(i) <= BRIDGE_APPROACH + ISLAND_CLEARANCE

        val n = abs(Noise.perlin(noiseOffset * FREQUENCY, (i + noiseOffset) * d.frequency))
        var bankTarget = (1 + floor(n * Rules.HALF).toInt()).coerceIn(MIN_BANK, d.maxBank)
        var islandTarget = (Rules.HALF - bankTarget - d.islandGap).coerceAtLeast(0)
        var driftTarget = if (d.meander == 0) {
            0
        } else {
            val m = Noise.perlin(noiseOffset * FREQUENCY + DRIFT_LANE, (i + noiseOffset) * DRIFT_FREQUENCY)
            ((m * DRIFT_GAIN).coerceIn(-1.0, 1.0) * d.meander).roundToInt()
        }
        if (i < Rules.SAFE_START_ROWS) {
            bankTarget = 3
            islandTarget = 0
            driftTarget = 0
        }
        if (nearBridge) bankTarget = BRIDGE_BANK
        // No island while the banks close in on a bridge, nor while they open out after it: an
        // island rising dead centre just past a bridge, where every pilot has lined up to shoot it,
        // is a trap rather than a challenge. The drift straightens out over the same stretch.
        if (straightening) {
            islandTarget = 0
            driftTarget = 0
        }
        // A drifting river needs bank on the side it drifts toward.
        bankTarget = maxOf(bankTarget, abs(driftTarget) + MIN_BANK)
        islandTarget = minOf(islandTarget, (Rules.HALF - bankTarget - d.islandGap).coerceAtLeast(0))

        // Budget carries over between moves (so the average is exactly wallStep), but at most one
        // move is ever banked, and a move needs a whole one: never two moves in a row.
        budget += d.wallStep
        if (budget >= 1.0 - EPSILON && moveWalls(bankTarget, islandTarget, driftTarget, d.minChannel, driftFirst = straightening)) {
            budget -= 1.0
        }
        budget = minOf(budget, 1.0)
        // Clamped after the bank moved, so a widening bank squeezes the island, never the channel.
        islandHalf = islandHalf.coerceIn(0, (Rules.HALF - bank - d.minChannel).coerceAtLeast(0))

        val row = Row(
            index = i,
            left = bank + drift,
            right = bank - drift,
            islandFrom = Rules.HALF + drift - islandHalf,
            islandTo = Rules.HALF + drift + islandHalf,
            bridge = i > 0 && i % BRIDGE_EVERY == 0,
            spawns = emptyList(),
        )
        return row.copy(spawns = spawnsFor(row, d))
    }

    /**
     * Spends one move: either the banks and island step toward their targets (they move different
     * walls, so together they still move each wall at most one column), or the river drifts one
     * column. Whichever is further from its target goes first, ties to the banks — except on the
     * straight stretch around a bridge ([driftFirst]), where the drift goes first so the river is
     * centred in time for the bridge. True if a wall moved; a step the channel clamp would undo is
     * not attempted, so it neither spends the budget nor blocks the drift.
     */
    private fun moveWalls(bankTarget: Int, islandTarget: Int, driftTarget: Int, minChannel: Int, driftFirst: Boolean): Boolean {
        var bankStep = sign(bankTarget - bank)
        // A narrowing bank must leave room for the drift on both sides.
        if (bankStep < 0 && bank - 1 - abs(drift) < MIN_BANK) bankStep = 0
        // The island can grow no further than the clamp below allows once the bank has moved.
        val islandCap = (Rules.HALF - (bank + bankStep) - minChannel).coerceAtLeast(0)
        val islandStep = sign(minOf(islandTarget, islandCap) - islandHalf)
        var driftStep = sign(driftTarget - drift)
        if (driftStep != 0 && bank - abs(drift + driftStep) < MIN_BANK) driftStep = 0

        val widthWork = maxOf(
            if (bankStep != 0) abs(bankTarget - bank) else 0,
            if (islandStep != 0) abs(minOf(islandTarget, islandCap) - islandHalf) else 0,
        )
        val driftWork = if (driftStep != 0) abs(driftTarget - drift) else 0
        return when {
            driftWork > widthWork || (driftFirst && driftWork > 0) -> {
                drift += driftStep
                true
            }
            widthWork > 0 -> {
                bank += bankStep
                islandHalf += islandStep
                true
            }
            else -> false
        }
    }

    private fun spawnsFor(row: Row, d: Difficulty): List<Spawn> {
        if (row.index < Rules.SAFE_START_ROWS || bridgeDistance(row.index) <= NO_SPAWN_NEAR_BRIDGE) {
            // Skipping the random draws here is still deterministic: generation is sequential.
            return emptyList()
        }
        val spawns = mutableListOf<Spawn>()
        if (row.index >= nextDepotRow) {
            spawns += Spawn.Depot(placeIn(row, DEPOT_MARGIN))
            nextDepotRow = row.index + random.nextInt(d.depotGapMin, d.depotGapMax + 1)
        } else if (row.index % ENEMY_EVERY == 0 && random.nextDouble() < d.enemyChance) {
            val heli = random.nextDouble() < d.heliShare
            val kind = if (heli) EnemyKind.HELI else EnemyKind.BOAT
            val moving = heli || random.nextDouble() < d.movingBoatShare
            val direction = if (random.nextBoolean()) 1.0 else -1.0
            val vx = if (moving) direction * kind.speed * d.enemySpeed else 0.0
            spawns += Spawn.Enemy(kind, placeIn(row, kind.width / 2 + 0.5), vx)
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
        /** The prototype's noise frequency down the river: section 1's; [Difficulty.frequency] ramps it. */
        const val FREQUENCY: Double = 0.068
        const val MIN_BANK: Int = 1

        /** Section 1's widest bank; [Difficulty.maxBank] ramps it. */
        const val MAX_BANK: Int = 8

        /** No wall ever moves more than this in one row; [Difficulty.wallStep] bounds the average. */
        const val MAX_WALL_STEP: Int = 1

        /** The narrowest channel, in every section; [Difficulty] explains why it cannot shrink. */
        const val MIN_CHANNEL: Int = 5

        /** The prototype's island rule: an island appears when the banks leave this much water (section 1). */
        const val ISLAND_GAP: Int = 7

        const val BRIDGE_EVERY: Int = 140
        const val BRIDGE_BANK: Int = 9
        const val BRIDGE_APPROACH: Int = 14
        const val NO_SPAWN_NEAR_BRIDGE: Int = 4
        const val ISLAND_CLEARANCE: Int = 10

        const val ENEMY_EVERY: Int = 3

        /** Section 1's spawn odds and depot spacing; [Difficulty] ramps each from here. */
        const val ENEMY_CHANCE: Double = 0.3
        const val HELI_SHARE: Double = 0.4
        const val MOVING_BOAT_SHARE: Double = 0.5
        const val DEPOT_GAP_MIN: Int = 40
        const val DEPOT_GAP_MAX: Int = 65
        const val DEPOT_MARGIN: Double = 1.2

        /** The drift noise: its own lane of the Perlin plane, a slow frequency, and a gain to reach full swing. */
        private const val DRIFT_LANE: Double = 57.0
        private const val DRIFT_FREQUENCY: Double = 0.03
        private const val DRIFT_GAIN: Double = 3.0
        private const val EPSILON: Double = 1e-9

        /** Rows to the nearest bridge row (bridges sit on multiples of [BRIDGE_EVERY], from the first). */
        fun bridgeDistance(i: Int): Int {
            val below = (i / BRIDGE_EVERY) * BRIDGE_EVERY
            val above = below + BRIDGE_EVERY
            val dBelow = if (below == 0) Int.MAX_VALUE else i - below
            return minOf(dBelow, above - i)
        }

        private fun sign(v: Int): Int = if (v > 0) 1 else if (v < 0) -1 else 0
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
