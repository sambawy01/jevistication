package dev.loupe.game

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationTest {

    @Test
    fun `the same seed and actions give the same world, bit for bit`() {
        fun run(seed: Long): List<String> {
            val world = World(seed)
            val trace = mutableListOf<String>()
            // A fixed, varied action script: every action, including firing, gets exercised.
            for (t in 0 until 1_500) {
                world.step(Action.entries[(t / 7) % Action.entries.size])
                if (t % 50 == 0) trace += world.fingerprint()
            }
            return trace + world.fingerprint()
        }
        assertEquals(run(42), run(42))
        assertNotEquals(run(42), run(43))
    }

    @Test
    fun `whole baseline sessions are deterministic for a seed`() {
        fun run(): String {
            val session = GameSession(7, Control.Piloted(LockstepDecider(BaselinePilot(), 3)))
            repeat(3_000) { session.tick() }
            return session.world.fingerprint() + "|" + session.stats.total + "|" + session.stats.overrides
        }
        assertEquals(run(), run())
    }

    @Test
    fun `a copy evolves exactly like the original and never disturbs it`() {
        val world = World(3)
        repeat(200) { world.step(Action.HOLD_FIRE) }
        val before = world.fingerprint()
        val copy = world.copy()
        repeat(100) { copy.step(Action.LEFT_FIRE) }
        assertEquals(before, world.fingerprint(), "stepping a copy changed the original")
        repeat(100) { world.step(Action.LEFT_FIRE) }
        assertEquals(copy.fingerprint(), world.fingerprint())
    }

    @Test
    fun `the river is always passable and never narrower than the minimum channel`() {
        // Reachability over a discretised column grid: from every reachable column the plane can
        // move at most LATERAL/scroll columns per row — at the speed of the section the row is in —
        // and its whole width must be over water on both this row and the next (its box straddles
        // rows). DifficultyTest has the stricter, three-row sweep over every section.
        val step = 0.25
        val half = Rules.PLAYER_W / 2
        for (seed in listOf(1L, 2L, 3L, 99L, 12345L, -7L)) {
            val river = River(seed)
            var reachable = (0..(Rules.COLUMNS / step).toInt()).map { it * step }
                .filter { x -> !river.row(0).landIn(x - half, x + half) }
                .toSet()
            for (i in 1 until 3_000) {
                val row = river.row(i)
                val prev = river.row(i - 1)
                val reachPerRow = Difficulty.forRow(i).reach * 0.9 // a margin below the true reach
                for (channel in row.water) {
                    assertTrue(channel.width >= RiverGenerator.MIN_CHANNEL, "seed $seed row $i channel $channel too narrow")
                }
                reachable = (0..(Rules.COLUMNS / step).toInt()).map { it * step }.filter { x ->
                    !row.landIn(x - half, x + half) && !prev.landIn(x - half, x + half) &&
                        reachable.any { abs(it - x) <= reachPerRow }
                }.toSet()
                assertTrue(reachable.isNotEmpty(), "seed $seed: no passable column at row $i")
            }
        }
    }

    @Test
    fun `bridges come at regular intervals with narrowed banks`() {
        val river = River(5)
        val bridges = (0 until 1_000).filter { river.row(it).bridge }
        assertEquals(listOf(140, 280, 420, 560, 700, 840, 980), bridges)
        bridges.forEach { assertEquals(RiverGenerator.BRIDGE_BANK, river.row(it).left) }
    }

    @Test
    fun `flying into the bank kills the plane`() {
        val world = World(1)
        world.clearEntities()
        var ticks = 0
        while (!world.over && ticks < 600) {
            world.step(Action.LEFT)
            ticks++
        }
        assertEquals(DeathCause.BANK, world.death)
        assertTrue(ticks < 120, "took $ticks ticks to reach the bank")
    }

    @Test
    fun `hitting an enemy kills the plane, and shooting it scores`() {
        val crash = World(1)
        crash.clearEntities()
        crash.addEnemy(Enemy(EnemyKind.BOAT, crash.playerX, crash.playerY + 3, 0.0))
        repeat(60) { crash.step(Action.HOLD) }
        assertEquals(DeathCause.ENEMY, crash.death)

        val shoot = World(1)
        shoot.clearEntities()
        shoot.addEnemy(Enemy(EnemyKind.HELI, shoot.playerX, shoot.playerY + 6, 0.0))
        repeat(60) { shoot.step(Action.HOLD_FIRE) }
        assertNull(shoot.death)
        assertEquals(Rules.SCORE_HELI, shoot.score)
        assertEquals(1, shoot.tally.kills)
    }

    @Test
    fun `an intact bridge kills, a shot bridge does not`() {
        // Fly (terrain only, guarded by the override) until the first bridge is a few rows ahead,
        // then take the controls away from the override.
        val world = World(1)
        while (!world.over && world.tick < 20_000 && world.bridges.none { it.alive && it.y - world.playerY < 8 }) {
            world.clearEntities(keepBridges = true)
            val wanted = followChannel(world, fire = false)
            world.step(Mechanics.safetyOverride(world, wanted) ?: wanted)
        }
        assertNull(world.death)
        val bridge = world.bridges.first { it.alive }
        world.placePlayer(bridge.x)

        val intact = world.copy()
        repeat(60) { intact.step(Action.HOLD) }
        assertEquals(DeathCause.BRIDGE, intact.death)

        val shot = world.copy()
        repeat(60) { shot.step(Action.HOLD_FIRE) }
        assertNull(shot.death)
        assertEquals(1, shot.tally.bridges)
        assertEquals(Rules.SCORE_BRIDGE, shot.score)
    }

    @Test
    fun `fuel drains, refills over a depot, and running dry ends the run`() {
        val world = World(1)
        world.clearEntities()
        val start = world.fuel
        repeat(Rules.TICK_HZ) { world.step(Action.HOLD) }
        assertEquals(start - Rules.FUEL_DRAIN_PER_S, world.fuel, 1e-6)

        world.setFuel(40.0)
        world.addDepot(Depot(world.playerX, world.playerY + 1))
        repeat(20) { world.step(Action.HOLD) }
        assertTrue(world.fuel > 40.0, "fuel ${world.fuel} did not rise over the depot")
        assertTrue(world.tally.refuelTicks > 0)

        val dry = World(1)
        dry.clearEntities()
        dry.setFuel(0.2)
        repeat(20) { dry.step(Action.HOLD) }
        assertEquals(DeathCause.FUEL, dry.death)
    }

    @Test
    fun `nothing moves once the run is over`() {
        val world = World(1)
        world.setFuel(0.01)
        world.step(Action.HOLD)
        assertTrue(world.over)
        val after = world.fingerprint()
        world.step(Action.LEFT_FIRE)
        assertEquals(after, world.fingerprint())
    }
}

/** Test helper: steer for the middle of the channel five rows ahead, so only a bridge can kill. */
internal fun followChannel(world: World, fire: Boolean): Action {
    val row = world.river.rowAt(world.playerY + 5)
    val channel = row.water.minBy { abs(it.center - world.playerX) }
    val dx = channel.center - world.playerX
    return Action.of(if (dx > 0.3) 1 else if (dx < -0.3) -1 else 0, fire)
}
