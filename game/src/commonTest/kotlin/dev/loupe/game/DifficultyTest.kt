package dev.loupe.game

import dev.loupe.engine.Backend
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The difficulty schedule: a pure function of row index, deterministic per seed on every platform
 * (this runs on the JVM and the iOS simulator and pins the same digest on both), passable at every
 * level, and — with a fake backend — honest about decisions the game asked for and did not get.
 */
class DifficultyTest {

    @Test
    fun classicIsTheOriginalGame() {
        val c = Difficulty.CLASSIC
        for (row in listOf(0, 199, 200, 5_000, 1_000_000)) assertEquals(1, c.level(row))
        assertEquals(Rules.SCROLL, c.scroll(1))
        assertEquals(Rules.LATERAL, c.lateral(1))
        assertEquals(Rules.FUEL_DRAIN_PER_S, c.fuelDrain(1))
        assertEquals(GameSession.DEFAULT_DECISION_INTERVAL, c.decisionInterval(1, GameSession.DEFAULT_DECISION_INTERVAL))
        assertEquals(RiverGenerator.MIN_CHANNEL, c.minChannel(1))
        // The default world is the classic one, so the parity goldens do not move.
        assertEquals(World(9).fingerprint(), World(9, Difficulty.CLASSIC).fingerprint())
    }

    @Test
    fun levelsClimbEveryLevelRowsAndRushStartsHigh() {
        val p = Difficulty.PROGRESSIVE
        assertEquals(1, p.level(0))
        assertEquals(1, p.level(Difficulty.LEVEL_ROWS - 1))
        assertEquals(2, p.level(Difficulty.LEVEL_ROWS))
        assertEquals(Difficulty.MAX_LEVEL, p.level(1_000_000))
        assertEquals(Difficulty.RUSH_START, Difficulty.RUSH.level(0))
        assertEquals(Difficulty.RUSH_START + 1, Difficulty.RUSH.level(Difficulty.LEVEL_ROWS))
        assertEquals(400, p.firstRow(3))
        assertEquals(0, Difficulty.RUSH.firstRow(Difficulty.RUSH_START))
    }

    @Test
    fun everyLevelIsFasterAndAsksMoreOften() {
        val p = Difficulty.PROGRESSIVE
        val intervals = (1..Difficulty.MAX_LEVEL).map { p.decisionInterval(it, 6) }
        assertEquals(listOf(6, 5, 4, 3, 3, 2, 2, 2, 2), intervals)
        for (l in 2..Difficulty.MAX_LEVEL) {
            assertTrue(p.scroll(l) > p.scroll(l - 1))
            assertTrue(p.fuelDrain(l) > p.fuelDrain(l - 1))
            assertTrue(p.enemyChance(l) >= p.enemyChance(l - 1))
            assertTrue(p.bridgeSpacing(l) <= p.bridgeSpacing(l - 1))
            // Sideways speed keeps two columns of reach per row at every level.
            assertEquals(2.0, p.lateral(l) / p.scroll(l), 1e-12)
        }
    }

    @Test
    fun theWorldSpeedsUpAndAnnouncesEachLevel() {
        val world = World(3, Difficulty.progressive(levelRows = 30))
        val ups = mutableListOf<GameEvent.LevelUp>()
        var lastY = world.cameraY
        var fastest = 0.0
        while (world.level < 3 && world.tick < 2_000) {
            world.step(Mechanics.safetyOverride(world, Action.HOLD) ?: Action.HOLD)
            world.events.filterIsInstance<GameEvent.LevelUp>().forEach { ups += it }
            fastest = maxOf(fastest, world.cameraY - lastY)
            lastY = world.cameraY
            if (world.over) break
        }
        assertEquals(listOf(2, 3), ups.map { it.level }, "one LevelUp per level, in order")
        assertTrue(fastest > Rules.SCROLL * Rules.DT, "the scroll per tick grew")
    }

    @Test
    fun progressiveRiversAreDeterministicAndMatchTheRecordedDigests() {
        val digests = listOf(Difficulty.PROGRESSIVE, Difficulty.RUSH).flatMap { d -> listOf(1L, 7L).map { riverDigest(it, d) } }
        // The same seed twice gives the same river.
        assertEquals(digests, listOf(Difficulty.PROGRESSIVE, Difficulty.RUSH).flatMap { d -> listOf(1L, 7L).map { riverDigest(it, d) } })
        assertEquals(GOLDEN_RIVER, digests)
        assertEquals(GOLDEN_MATCH, listOf(1L, 2L).map { seed ->
            val e = Match.episode(seed, BaselinePilot(), Match.Settings(maxTicks = 3_600, difficulty = Difficulty.PROGRESSIVE))
            "${e.seed}:${e.score}:${e.rows.toRawBits()}:${e.death}:${e.decisions}:${e.level}"
        })
    }

    @Test
    fun everyLevelIsPassableAndNoChannelIsNarrowerThanItsLevelAllows() {
        val step = 0.25
        val half = Rules.PLAYER_W / 2
        for (difficulty in listOf(Difficulty.PROGRESSIVE, Difficulty.RUSH)) {
            for (seed in listOf(1L, 2L, 99L, -7L)) {
                val river = River(seed, difficulty)
                var reachable = (0..(Rules.COLUMNS / step).toInt()).map { it * step }
                    .filter { x -> !river.row(0).landIn(x - half, x + half) }.toSet()
                for (i in 1 until 2_000) {
                    val row = river.row(i)
                    val prev = river.row(i - 1)
                    val level = difficulty.level(i)
                    val reach = difficulty.lateral(level) / difficulty.scroll(level) * 0.9
                    for (channel in row.water) {
                        assertTrue(channel.width >= difficulty.minChannel(level), "$difficulty seed $seed row $i channel $channel")
                    }
                    reachable = (0..(Rules.COLUMNS / step).toInt()).map { it * step }.filter { x ->
                        !row.landIn(x - half, x + half) && !prev.landIn(x - half, x + half) &&
                            reachable.any { abs(it - x) <= reach }
                    }.toSet()
                    assertTrue(reachable.isNotEmpty(), "$difficulty seed $seed: no passable column at row $i")
                }
            }
        }
    }

    @Test
    fun bridgesComeCloserAtHigherLevels() {
        val river = River(5, Difficulty.PROGRESSIVE)
        val bridges = (0 until 2_000).filter { river.row(it).bridge }
        val gaps = bridges.zipWithNext { a, b -> b - a }
        assertTrue(gaps.first() > gaps.last(), "gaps $gaps")
        assertTrue(gaps.all { it >= Difficulty.MIN_BRIDGE_SPACING })
    }

    /** A fake backend: every candidate equally likely, so the first offered wins. No model. */
    private val fake = Backend.ofMasses { j, _ -> j.candidates.associateWith { 1.0 / j.candidates.size } }

    private fun cadenceRun(delayTicks: Int, difficulty: Difficulty, cap: Int = 1): GameSession {
        val s = GameSession(4, Control.Piloted(LockstepDecider(ModelPilot(fake), delayTicks)), clock = { 0L }, difficulty = difficulty)
        s.minInterval = cap
        repeat(600) { if (!s.world.over) s.tick() }
        return s
    }

    @Test
    fun aPilotFasterThanTheCadenceDropsNothing() {
        val s = cadenceRun(delayTicks = 1, difficulty = Difficulty.rush(levelRows = 10_000)) // level 4: every 3 ticks
        assertEquals(3, s.currentInterval)
        assertEquals(0, s.stats.dropped)
        assertEquals(0, s.stats.lateRequests)
        assertEquals(0, s.stats.lateAnswers)
        assertTrue(s.stats.requested >= s.world.tick / 3 - 1)
    }

    @Test
    fun aPilotSlowerThanTheCadenceIsCountedNotHidden() {
        // Level 4 asks every 3 ticks (20/s); this pilot answers 10 ticks after it was asked (6/s).
        val s = cadenceRun(delayTicks = 10, difficulty = Difficulty.rush(levelRows = 10_000))
        val st = s.stats
        assertTrue(st.requested > 10)
        assertEquals(st.requested - 1, st.lateRequests, "every request after the first went out late")
        // Each cycle is 10 ticks against a 3-tick cadence: 7 ticks late, two whole slots dropped.
        assertEquals(2 * st.lateRequests, st.dropped)
        assertTrue(st.lateAnswers >= st.total - 1, "answers older than one interval are counted late")
        assertEquals(st.requested, st.total + (if ((s.control as Control.Piloted).decider.busy) 1 else 0))
    }

    @Test
    fun theCapHoldsTheCadenceDown() {
        val capped = cadenceRun(delayTicks = 1, difficulty = Difficulty.rush(levelRows = 10_000), cap = 12)
        assertEquals(12, capped.currentInterval)
        assertEquals(5.0, capped.askedPerSecond, 1e-9)
        assertEquals(0, capped.stats.dropped)
    }

    @Test
    fun recentLatenciesAreOldestFirst() {
        val stats = DecisionStats(window = 4)
        (1..6).forEach { stats.record(PilotDecision(Action.HOLD, DecisionSource.MODEL, latencyNanos = it * 1_000_000L), 0) }
        assertEquals(listOf(4.0, 5.0, 6.0), stats.recentLatenciesMillis(3))
        assertEquals(listOf(3.0, 4.0, 5.0, 6.0), stats.recentLatenciesMillis(10))
    }

    private fun riverDigest(seed: Long, difficulty: Difficulty): String {
        var h: ULong = 0xcbf29ce484222325uL
        fun add(v: Long) {
            var x = v
            repeat(8) { h = (h xor (x and 0xff).toULong()) * 0x100000001b3uL; x = x ushr 8 }
        }
        val river = River(seed, difficulty)
        for (i in 0 until 2_000) {
            val r = river.row(i)
            add(r.left.toLong()); add(r.right.toLong()); add(r.islandFrom.toLong()); add(r.islandTo.toLong()); add(if (r.bridge) 1 else 0)
            r.spawns.forEach { s ->
                add(s.x.toRawBits())
                if (s is Spawn.Enemy) { add(s.kind.ordinal.toLong()); add(s.vx.toRawBits()) } else add(-1)
            }
        }
        return h.toString(16).padStart(16, '0')
    }

    private companion object {
        val GOLDEN_RIVER = listOf("eca0d310b96bb0a8", "92d0ce7858a69140", "5a37675dd970fee3", "dc80809f705951a0")
        val GOLDEN_MATCH = listOf("1:1030:4646648025284618940:null:680:3", "2:1320:4646648025284618940:null:680:3")
    }
}
