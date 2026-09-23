package dev.loupe.game

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DifficultyTest {

    private val sections = 1..(Difficulty.CAP_SECTION + 4)

    @Test
    fun `section 1 is the original game`() {
        val d = Difficulty.of(1)
        assertEquals(Rules.SCROLL, d.scroll)
        assertEquals(1.0, d.wallStep)
        assertEquals(RiverGenerator.MIN_CHANNEL, d.minChannel)
        assertEquals(RiverGenerator.MAX_BANK, d.maxBank)
        assertEquals(RiverGenerator.ISLAND_GAP, d.islandGap)
        assertEquals(RiverGenerator.FREQUENCY, d.frequency)
        assertEquals(0, d.meander)
        assertEquals(RiverGenerator.ENEMY_CHANCE, d.enemyChance)
        assertEquals(RiverGenerator.HELI_SHARE, d.heliShare)
        assertEquals(RiverGenerator.MOVING_BOAT_SHARE, d.movingBoatShare)
        assertEquals(1.0, d.enemySpeed)
        assertEquals(RiverGenerator.DEPOT_GAP_MIN, d.depotGapMin)
        assertEquals(RiverGenerator.DEPOT_GAP_MAX, d.depotGapMax)
        assertEquals(Rules.FUEL_DRAIN_PER_S, d.fuelDrainPerS)
        assertEquals(Rules.FUEL_REFILL_PER_S, d.fuelRefillPerS)
    }

    @Test
    fun `every section is at least as hard as the one before, and the cap holds`() {
        for (s in sections.drop(1)) {
            val a = Difficulty.of(s - 1)
            val b = Difficulty.of(s)
            assertEquals(s, b.section)
            assertTrue(b.scroll >= a.scroll, "scroll fell at section $s")
            assertTrue(b.wallStep <= a.wallStep, "walls may only slow as the plane speeds up ($s)")
            assertTrue(b.maxBank >= a.maxBank && b.islandGap <= a.islandGap, "channels widened at section $s")
            assertTrue(b.frequency >= a.frequency && b.meander >= a.meander, "less twist at section $s")
            assertTrue(b.enemyChance >= a.enemyChance && b.heliShare >= a.heliShare, "fewer enemies at section $s")
            assertTrue(b.movingBoatShare >= a.movingBoatShare && b.enemySpeed >= a.enemySpeed, "tamer enemies at section $s")
            assertTrue(b.depotGapMin >= a.depotGapMin && b.depotGapMax >= a.depotGapMax, "more depots at section $s")
            assertTrue(b.fuelPerRow >= a.fuelPerRow, "cheaper fuel per row at section $s")
        }
        assertTrue(Difficulty.of(Difficulty.CAP_SECTION).scroll > Difficulty.of(1).scroll, "the game never got faster")
        val cap = Difficulty.of(Difficulty.CAP_SECTION)
        for (s in Difficulty.CAP_SECTION..Difficulty.CAP_SECTION + 50) {
            assertEquals(cap.copy(section = s), Difficulty.of(s), "section $s is past the cap and must equal it")
        }
        assertEquals(Difficulty.SCROLL_CAP, cap.scroll)
    }

    @Test
    fun `the passability constants are derived from the speed, and keep their margins at the cap`() {
        for (s in sections) {
            val d = Difficulty.of(s)
            assertEquals(min(1.0, Difficulty.WALL_TRACK * d.reach), d.wallStep, 1e-12)
            // The average wall speed leaves the plane spare reach; a single one-column step fits in one row's reach.
            assertTrue(d.wallStep <= 0.7 * d.reach + 1e-12, "section $s: walls take ${d.wallStep} of reach ${d.reach}")
            // Even counting whole ticks only, with a 10% margin, one row's reach covers a one-column step.
            val lattice = Passability.latticeReach(d.scroll)
            assertTrue(0.9 * lattice >= RiverGenerator.MAX_WALL_STEP, "section $s: reach $lattice too short for a one-column step")
            assertEquals(ceil(Rules.PLAYER_W + 1 + 2 * RiverGenerator.MAX_WALL_STEP).toInt(), d.minChannel)
        }
    }

    @Test
    fun `sections change at every bridge`() {
        assertEquals(1, Difficulty.sectionOfRow(0))
        assertEquals(1, Difficulty.sectionOfRow(139))
        assertEquals(2, Difficulty.sectionOfRow(140))
        assertEquals(10, Difficulty.sectionOfRow(9 * 140))
        for (s in sections) {
            val row = Difficulty.firstRow(s)
            assertEquals(s, Difficulty.sectionOfRow(row))
            if (s > 1) assertTrue(River(1).row(row).bridge, "section $s does not start at a bridge")
        }
    }

    @Test
    fun `the river is passable in every section, across the transitions and past the cap, on many seeds`() {
        val rows = Difficulty.firstRow(sections.last + 1)
        for (seed in seeds) {
            val failure = Passability.firstImpassableRow(River(seed), rows)
            assertNull(failure, "seed $seed: no way through at row $failure (section ${failure?.let(Difficulty::sectionOfRow)})")
        }
    }

    @Test
    fun `the checker is not vacuous - it finds a river it cannot pass`() {
        // A wall that jumps across the whole channel in one row, as a fixed-step generator would
        // produce given enough speed, is caught.
        val rows = (0 until 40).map { i ->
            if (i < 20) Row(i, 10, 4, 0, 0, false, emptyList()) else Row(i, 1, 22, 0, 0, false, emptyList())
        }
        // Rows 18–20 are the first footprint that holds both channels, and they share no column.
        assertEquals(18, Passability.firstImpassableRow(rows) { Difficulty.of(1).reach })
        // A minimum-width channel sliding one column a row for 22 rows is passable at section 1's
        // reach and at the cap's...
        val sliding = (0 until 40).map { i ->
            val s = (i - 10).coerceIn(0, 22)
            Row(i, 1 + s, 22 - s, 0, 0, false, emptyList())
        }
        assertNull(Passability.firstImpassableRow(sliding) { Difficulty.of(1).reach })
        assertNull(Passability.firstImpassableRow(sliding) { Difficulty.of(Difficulty.CAP_SECTION).reach })
        // ...but not with less than a column of reach a row, nor one column narrower.
        assertNotNull(Passability.firstImpassableRow(sliding) { 1.0 })
        val narrower = sliding.map { it.copy(right = it.right + 1) }
        assertNotNull(Passability.firstImpassableRow(narrower) { Difficulty.of(1).reach })
    }

    @Test
    fun `walls move at most one column a row and no faster on average than the section allows`() {
        for (seed in seeds.take(12)) {
            val river = River(seed)
            val rows = (0 until Difficulty.firstRow(sections.last + 1)).map(river::row)
            for (i in 1 until rows.size) {
                val a = rows[i - 1]
                val b = rows[i]
                val d = Difficulty.forRow(i)
                assertTrue(abs(a.left - b.left) <= 1 && abs(a.right - b.right) <= 1, "seed $seed row $i: bank jumped")
                if (a.hasIsland && b.hasIsland) {
                    assertTrue(abs(a.islandFrom - b.islandFrom) <= 1 && abs(a.islandTo - b.islandTo) <= 1, "seed $seed row $i: island jumped")
                } else if (b.hasIsland) {
                    assertTrue(b.islandTo - b.islandFrom <= 2, "seed $seed row $i: island appeared at full size")
                }
                assertTrue(b.left >= RiverGenerator.MIN_BANK && b.right >= RiverGenerator.MIN_BANK, "seed $seed row $i: bank under the minimum")
                for (channel in b.water) {
                    assertTrue(channel.width >= d.minChannel, "seed $seed row $i: channel $channel narrower than ${d.minChannel}")
                }
            }
            // Average: within each section, over any window, walls move on at most budget + 1 rows.
            for (s in sections) {
                val d = Difficulty.of(s)
                val from = Difficulty.firstRow(s) + 1
                val until = Difficulty.firstRow(s + 1)
                val moved = (from until until).map { i -> rows[i].wallsOf() != rows[i - 1].wallsOf() }
                for (window in listOf(5, 10, 20, 40)) {
                    for (start in 0..moved.size - window) {
                        val n = (start until start + window).count { moved[it] }
                        assertTrue(n <= window * d.wallStep + 1 + 1e-9, "seed $seed section $s: $n moves in $window rows at step ${d.wallStep}")
                    }
                }
            }
        }
    }

    @Test
    fun `every bridge sits between narrowed, centred banks, in every section`() {
        for (seed in seeds.take(12)) {
            val river = River(seed)
            for (s in sections.drop(1)) {
                val row = river.row(Difficulty.firstRow(s))
                assertTrue(row.bridge)
                assertEquals(RiverGenerator.BRIDGE_BANK, row.left, "seed $seed bridge $s")
                assertEquals(RiverGenerator.BRIDGE_BANK, row.right, "seed $seed bridge $s")
                assertTrue(!row.hasIsland)
            }
        }
    }

    @Test
    fun `later sections really are harder - narrower, twistier, busier, drier`() {
        val stats = sections.associateWith { SectionStats() }
        for (seed in seeds) {
            val river = River(seed)
            for (i in 0 until Difficulty.firstRow(sections.last + 1)) {
                val row = river.row(i)
                val st = stats.getValue(Difficulty.sectionOfRow(i))
                st.rows++
                st.narrowest += row.water.minOf { it.width }
                if (i > 0 && row.wallsOf() != river.row(i - 1).wallsOf()) st.wallMoves++
                st.drift += abs(row.left - row.right) / 2.0
                for (spawn in row.spawns) {
                    when (spawn) {
                        is Spawn.Depot -> st.depots++
                        is Spawn.Enemy -> {
                            st.enemies++
                            if (spawn.vx != 0.0) st.moving++
                            st.speed += abs(spawn.vx)
                        }
                    }
                }
            }
        }
        println("section | scroll | reach | wallStep | minCh | maxBank | islGap | meander | enemy% | heli% | movBoat% | enemySpd× | depotGap | drain/s | refill/s | fuel%/row")
        for (s in sections) {
            val d = Difficulty.of(s)
            println(
                String.format(
                    Locale.ROOT, "%7d | %6.2f | %5.2f | %8.3f | %5d | %7d | %6d | %7d | %6.2f | %5.2f | %8.2f | %9.2f | %4d-%-3d | %7.2f | %8.1f | %9.3f",
                    s, d.scroll, d.reach, d.wallStep, d.minChannel, d.maxBank, d.islandGap, d.meander, d.enemyChance,
                    d.heliShare, d.movingBoatShare, d.enemySpeed, d.depotGapMin, d.depotGapMax, d.fuelDrainPerS, d.fuelRefillPerS, d.fuelPerRow,
                ),
            )
        }
        println("measured over ${seeds.size} seeds, per 100 rows:")
        println("section | mean narrowest channel | wall-move rows | mean drift | enemies | moving share | mean |vx| | depots")
        for ((s, st) in stats) {
            val per = 100.0 / st.rows
            println(
                String.format(
                    Locale.ROOT, "%7d | %22.2f | %14.1f | %10.2f | %7.1f | %12.2f | %9.2f | %6.2f",
                    s, st.narrowest / st.rows, st.wallMoves * per, st.drift / st.rows, st.enemies * per,
                    st.moving.toDouble() / st.enemies, st.speed / st.moving, st.depots * per,
                ),
            )
        }
        val first = stats.getValue(1)
        // Densities compare against section 2: section 1 opens with a spawn-free safe start, which
        // would flatter the comparison.
        val second = stats.getValue(2)
        val cap = stats.getValue(Difficulty.CAP_SECTION)
        assertTrue(cap.narrowest / cap.rows < first.narrowest / first.rows - 2.0, "channels did not narrow")
        assertTrue(cap.enemies / cap.rows.toDouble() > 1.5 * second.enemies / second.rows.toDouble(), "enemies did not multiply")
        assertTrue(cap.moving.toDouble() / cap.enemies > first.moving.toDouble() / first.enemies + 0.15, "no more of them move")
        assertTrue(cap.speed / cap.moving > 1.4 * first.speed / first.moving, "they are not faster")
        assertTrue(cap.depots / cap.rows.toDouble() < 0.8 * second.depots / second.rows.toDouble(), "depots are not rarer")
        assertTrue(cap.drift / cap.rows > 1.0 && first.drift == 0.0, "the river does not twist")
    }

    @Test
    fun `the world flies each section at its speed and burns its fuel`() {
        for (s in listOf(1, 4, Difficulty.CAP_SECTION, Difficulty.CAP_SECTION + 3)) {
            val world = World(3, startSection = s)
            world.clearEntities()
            assertEquals(s, world.section)
            val y0 = world.cameraY
            val fuel0 = world.fuel
            repeat(Rules.TICK_HZ) { world.step(followChannel(world, fire = false)) }
            assertNull(world.death, "section $s start")
            val d = Difficulty.of(s)
            assertEquals(d.scroll, world.cameraY - y0, 1e-6, "section $s rows per second")
            assertEquals(d.fuelDrainPerS, fuel0 - world.fuel, 1e-6, "section $s fuel per second")
        }
    }

    @Test
    fun `passing a bridge speeds the plane up`() {
        val world = World(2)
        world.clearEntities()
        while (world.playerY < Difficulty.firstRow(2) - 1) {
            world.clearEntities()
            val wanted = followChannel(world, fire = false)
            world.step(Mechanics.safetyOverride(world, wanted) ?: wanted)
            assertNull(world.death)
        }
        val before = world.cameraY
        world.step(Action.HOLD)
        assertEquals(Difficulty.of(1).scroll * Rules.DT, world.cameraY - before, 1e-9)
        while (world.playerY < Difficulty.firstRow(2)) {
            world.clearEntities()
            world.step(Action.HOLD)
        }
        assertEquals(2, world.section)
        val at = world.cameraY
        world.clearEntities()
        world.step(Action.HOLD)
        assertEquals(Difficulty.of(2).scroll * Rules.DT, world.cameraY - at, 1e-9)
    }

    @Test
    fun `difficulty is part of the determinism - same seed, same world, in every section`() {
        for (s in listOf(1, 5, Difficulty.CAP_SECTION + 2)) {
            fun run(): List<String> {
                val world = World(11, startSection = s)
                return (0 until 1_200).mapNotNull { t ->
                    world.step(Action.entries[(t / 9) % Action.entries.size])
                    if (t % 100 == 0) world.fingerprint() else null
                } + world.fingerprint()
            }
            assertEquals(run(), run(), "section $s")
        }
        val a = River(77)
        val b = River(77)
        for (i in 0 until Difficulty.firstRow(Difficulty.CAP_SECTION + 3)) assertEquals(a.row(i), b.row(i))
    }

    @Test
    fun `the safety override keeps a reckless pilot off the banks at the cap`() {
        // Holding hard left for ten seconds at top speed, with nothing else on the river: only the
        // override stands between the plane and the bank, and it must win every time.
        for (seed in 1L..6L) {
            val guarded = GameSession(seed, Control.Human, overrideEnabled = true, startSection = Difficulty.CAP_SECTION)
            guarded.human = HumanInput(left = true)
            repeat(10 * Rules.TICK_HZ) {
                guarded.world.clearEntities()
                guarded.tick()
            }
            assertNull(guarded.world.death, "seed $seed: the override let the plane hit the bank at ${guarded.world.playerY}")
            assertTrue(guarded.stats.overrides > 0)
            // Right as well, since the river drifts.
            val right = GameSession(seed, Control.Human, overrideEnabled = true, startSection = Difficulty.CAP_SECTION)
            right.human = HumanInput(right = true)
            repeat(10 * Rules.TICK_HZ) {
                right.world.clearEntities()
                right.tick()
            }
            assertNull(right.world.death, "seed $seed: holding right, the override lost at ${right.world.playerY}")
        }
    }

    @Test
    fun `at the cap, legal moves never crash within their hold`() {
        var checked = 0
        for (seed in 1L..3L) {
            val session = GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 3)), startSection = Difficulty.CAP_SECTION)
            while (!session.world.over && session.world.tick < 2_400) {
                if (session.world.tick % 5 == 0L) {
                    val world = session.world
                    val legal = Mechanics.legalActions(world)
                    if (Action.entries.any { Mechanics.survives(world, it, Mechanics.HOLD_TICKS, Mechanics.RECOVERY_TICKS) }) {
                        for (action in legal.actions) {
                            assertEquals(Mechanics.HOLD_TICKS, Mechanics.survivalTicks(world, action, Mechanics.HOLD_TICKS), "seed $seed tick ${world.tick}: $action")
                            checked++
                        }
                    }
                }
                session.tick()
            }
        }
        assertTrue(checked > 500, "only $checked offered actions checked")
    }

    private class SectionStats {
        var rows = 0
        var narrowest = 0.0
        var wallMoves = 0
        var drift = 0.0
        var enemies = 0
        var moving = 0
        var speed = 0.0
        var depots = 0
    }

    private companion object {
        val seeds: List<Long> = (1L..24L) + listOf(99L, 12345L, -7L, 424242L, Long.MAX_VALUE, Long.MIN_VALUE)
    }
}

private fun Row.wallsOf(): List<Int> = listOf(left, right, islandFrom, islandTo)

/**
 * An exhaustive, conservative check that a river can be flown, terrain only.
 *
 * The plane's bottom edge is sampled at every whole row, at columns on a quarter-column grid. From
 * row `i` to row `i + 1` it may move at most 90% of the *least* the real plane can move while one
 * row scrolls by — whole ticks only: `floor(TICK_HZ / scroll)` ticks of `LATERAL × DT`, at the
 * faster of the two rows' sections ([latticeReach]). The whole box it sweeps doing so — its width
 * plus the move, over rows `i`, `i + 1` and `i + 2`, since a 1.5-row box can straddle three rows —
 * must be clear of land, with a further half-step of the plane's own movement ([PAD]) on each side,
 * because the real plane's column moves in steps of `LATERAL × DT` and may not land on the grid.
 * Any path this finds, the real plane can fly; so a river it passes is passable.
 */
internal object Passability {
    private const val STEP = 0.25
    private const val PAD = Rules.LATERAL * Rules.DT / 2
    private val xs = (0..(Rules.COLUMNS / STEP).toInt()).map { it * STEP }

    /** Fewest columns the plane can cross while one row scrolls by at [scroll]: whole ticks only. */
    fun latticeReach(scroll: Double): Double = floor(Rules.TICK_HZ / scroll) * Rules.LATERAL * Rules.DT

    /** Walks the river of a real run: from the plane's start, bottom edge on row 3 at the centre column. */
    fun firstImpassableRow(river: River, rows: Int): Int? {
        val start = Rules.PLAYER_ROW.toInt()
        return firstImpassableRow((0 until rows + 3).map(river::row), startRow = start, startX = Rules.HALF.toDouble()) { i ->
            min(latticeReach(Difficulty.forRow(i).scroll), latticeReach(Difficulty.forRow(i + 1).scroll))
        }
    }

    /**
     * The first row index the plane cannot get past, or null. [reach] is columns per row for the step
     * from row `i`. The plane starts on [startRow] at [startX], or anywhere clear when that is null.
     */
    fun firstImpassableRow(rows: List<Row>, startRow: Int = 0, startX: Double? = null, reach: (Int) -> Double): Int? {
        val half = Rules.PLAYER_W / 2 + PAD
        fun clear(row: Row) = BooleanArray(xs.size) { k -> !row.landIn(xs[k] - half, xs[k] + half) }
        var reachable = if (startX == null) clear(rows[startRow]) else BooleanArray(xs.size) { abs(xs[it] - startX) < 1e-9 }
        for (i in startRow until rows.size - 3) {
            val a = clear(rows[i])
            val b = clear(rows[i + 1])
            val c = clear(rows[i + 2])
            val safe = BooleanArray(xs.size) { a[it] && b[it] && c[it] }
            val from = BooleanArray(xs.size) { reachable[it] && safe[it] }
            if (from.none { it }) return i
            val d = 0.9 * reach(i)
            val next = BooleanArray(xs.size)
            // Sweep both ways within each run of safe columns: a run is one gap between walls.
            var last = Double.NEGATIVE_INFINITY
            for (k in xs.indices) {
                if (!safe[k]) { last = Double.NEGATIVE_INFINITY; continue }
                if (from[k]) last = xs[k]
                if (xs[k] - last <= d + 1e-9) next[k] = true
            }
            last = Double.POSITIVE_INFINITY
            for (k in xs.indices.reversed()) {
                if (!safe[k]) { last = Double.POSITIVE_INFINITY; continue }
                if (from[k]) last = xs[k]
                if (last - xs[k] <= d + 1e-9) next[k] = true
            }
            reachable = next
        }
        return null
    }
}
