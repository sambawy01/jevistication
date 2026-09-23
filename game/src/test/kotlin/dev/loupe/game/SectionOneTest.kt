package dev.loupe.game

import dev.loupe.game.RiverGenerator.Companion.BRIDGE_APPROACH
import dev.loupe.game.RiverGenerator.Companion.BRIDGE_BANK
import dev.loupe.game.RiverGenerator.Companion.BRIDGE_EVERY
import dev.loupe.game.RiverGenerator.Companion.DEPOT_GAP_MAX
import dev.loupe.game.RiverGenerator.Companion.DEPOT_GAP_MIN
import dev.loupe.game.RiverGenerator.Companion.DEPOT_MARGIN
import dev.loupe.game.RiverGenerator.Companion.ENEMY_CHANCE
import dev.loupe.game.RiverGenerator.Companion.ENEMY_EVERY
import dev.loupe.game.RiverGenerator.Companion.FREQUENCY
import dev.loupe.game.RiverGenerator.Companion.HELI_SHARE
import dev.loupe.game.RiverGenerator.Companion.ISLAND_CLEARANCE
import dev.loupe.game.RiverGenerator.Companion.ISLAND_GAP
import dev.loupe.game.RiverGenerator.Companion.MAX_BANK
import dev.loupe.game.RiverGenerator.Companion.MAX_WALL_STEP
import dev.loupe.game.RiverGenerator.Companion.MIN_BANK
import dev.loupe.game.RiverGenerator.Companion.MIN_CHANNEL
import dev.loupe.game.RiverGenerator.Companion.MOVING_BOAT_SHARE
import dev.loupe.game.RiverGenerator.Companion.NO_SPAWN_NEAR_BRIDGE
import dev.loupe.game.RiverGenerator.Companion.bridgeDistance
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Section 1 must be the game as it was before sections existed: the same rows, spawns included. */
class SectionOneTest {

    @Test
    fun `section 1 generates exactly the river the original generator did`() {
        for (seed in (-50L..250L) + listOf(Long.MAX_VALUE, Long.MIN_VALUE)) {
            val original = OriginalRiverGenerator(seed)
            val river = River(seed)
            for (i in 0 until Difficulty.firstRow(2)) {
                assertEquals(original.next(), river.row(i), "seed $seed row $i")
            }
        }
    }
}

/** The generator as it was on `main` at cc179d5, verbatim but for its name: the reference for section 1. */
private class OriginalRiverGenerator(seed: Long) {
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
        if (bridgeDistance(i) <= BRIDGE_APPROACH + ISLAND_CLEARANCE) islandTarget = 0

        bank += (bankTarget - bank).coerceIn(-MAX_WALL_STEP, MAX_WALL_STEP)
        islandHalf += (islandTarget - islandHalf).coerceIn(-MAX_WALL_STEP, MAX_WALL_STEP)
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

    private fun placeIn(row: Row, margin: Double): Double {
        val channels = row.water.filter { it.width > 2 * margin }
        val channel = if (channels.isEmpty()) row.water.maxBy { it.width } else channels[random.nextInt(channels.size)]
        if (channel.width <= 2 * margin) return channel.center
        return random.nextDouble(channel.from + margin, channel.to - margin)
    }
}
