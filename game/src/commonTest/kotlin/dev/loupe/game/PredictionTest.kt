package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Prediction]: motion-aware collision prediction per way. Scenarios are built on seed 1's opening
 * river (banks three columns in, no island, nothing spawned before row 24) with the plane in the
 * middle, then checked against the simulation itself: every predicted hit must be what happens when
 * the real world is stepped with the same plan. Common code: runs on the JVM and the iOS simulator,
 * and the golden digest pins both to the same predictions.
 */
class PredictionTest {
    private fun open(x: Double = Rules.HALF.toDouble()): World = World(1).also {
        it.clearEntities()
        it.placePlayer(x)
    }

    /** Steps [world] itself with the plan and returns the tick and cause of death, or null. */
    private fun flown(world: World, first: Action, horizon: Int = Prediction.HORIZON_TICKS): Pair<Int, DeathCause>? {
        for (t in 0 until horizon) {
            world.step(Prediction.planned(first, t))
            if (world.over) return if (world.death == DeathCause.FUEL) null else (t + 1) to world.death!!
        }
        return null
    }

    @Test
    fun `a stationary boat dead ahead is in the way - and a lane over is clear`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX, w.playerY + 6, 0.0))
        val straight = Prediction.of(w, Action.HOLD)
        assertEquals("boat", straight.hitWith)
        assertFalse(straight.crossing, "it is in the way, not crossing in")
        assertNotNull(straight.hitTicks)
        assertTrue(Prediction.of(w, Action.LEFT).clear)
        assertTrue(Prediction.of(w, Action.RIGHT).clear)
        assertEquals(straight.hitTicks!! to DeathCause.ENEMY, flown(w, Action.HOLD))
    }

    @Test
    fun `a heli crossing into the path is predicted - though it is not in the way now`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 6, 4.5))
        val straight = Prediction.of(w, Action.HOLD)
        assertEquals("heli", straight.hitWith)
        assertTrue(straight.crossing)
        assertEquals(straight.hitTicks!! to DeathCause.ENEMY, flown(w, Action.HOLD))
    }

    @Test
    fun `a heli in line now but moving away leaves straight clear - and says so`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX + 0.5, w.playerY + 6, 4.5))
        val straight = Prediction.of(w, Action.HOLD)
        assertTrue(straight.clear)
        assertEquals("heli", straight.leaving)
        assertNull(flown(w, Action.HOLD))
    }

    @Test
    fun `a boat moving parallel to the path - off it - is neither a hit nor leaving`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX - 5.0, w.playerY + 6, -2.5))
        val straight = Prediction.of(w, Action.HOLD)
        assertTrue(straight.clear)
        assertNull(straight.leaving)
        assertFalse(straight.crossing)
    }

    @Test
    fun `a heli that turns round at the bank comes back into the path`() {
        // Flying right into the right bank (land from column 25): it turns round and crosses back.
        val w = open(x = 20.0)
        val heli = Enemy(EnemyKind.HELI, 24.0, w.playerY + 6, 4.5)
        w.addEnemy(heli)
        val straight = Prediction.of(w, Action.HOLD)
        assertEquals("heli", straight.hitWith)
        assertTrue(straight.crossing)
        // The straight-line reading (no turn-round) had it flying off to the right: clear.
        val o = Observation.of(w, Mechanics.legalActions(w))
        assertNull(o.path(0)!!.enemyRows, "the old linear projection misses the bounce")
        assertEquals(straight.hitTicks!! to DeathCause.ENEMY, flown(w, Action.HOLD))
    }

    @Test
    fun `with several enemies the first hit is reported`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX, w.playerY + 9, 0.0))
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 5, 4.5))
        val straight = Prediction.of(w, Action.HOLD)
        assertEquals("heli", straight.hitWith)
        assertTrue(straight.crossing)
        assertEquals(straight.hitTicks!! to DeathCause.ENEMY, flown(w, Action.HOLD))
    }

    @Test
    fun `the plan's own shot clears a boat in line`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX, w.playerY + 8, 0.0))
        assertEquals("boat", Prediction.of(w, Action.HOLD).hitWith)
        val firing = Prediction.of(w, Action.HOLD_FIRE)
        assertTrue(firing.clear)
        assertEquals("boat", firing.shoots)
        assertNull(firing.leaving, "shot, not moving away")
    }

    @Test
    fun `land ahead on a steered path is predicted`() {
        val w = open(x = 4.6)
        val left = Prediction.of(w, Action.LEFT)
        assertEquals("land", left.hitWith)
        assertTrue(Prediction.of(w, Action.RIGHT).clear)
        assertEquals(left.hitTicks!! to DeathCause.BANK, flown(w, Action.LEFT))
    }

    @Test
    fun `prediction leaves the world untouched`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 6, 4.5))
        val before = w.fingerprint()
        Prediction.of(w, Action.HOLD_FIRE)
        assertEquals(before, w.fingerprint())
    }

    @Test
    fun `every prediction in real flights matches the simulation stepped with that plan`() {
        var checked = 0
        var hits = 0
        for (seed in 1L..3L) {
            GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE).use { s ->
                while (!s.world.over && s.world.tick < 3_000) {
                    if (s.world.tick % 30 == 0L) {
                        for (a in listOf(Action.LEFT, Action.HOLD, Action.RIGHT_FIRE)) {
                            val p = Prediction.of(s.world, a)
                            val truth = flown(s.world.copy(), a)
                            assertEquals(truth?.first, p.hitTicks, "seed $seed tick ${s.world.tick} $a")
                            if (truth != null) hits++
                            checked++
                        }
                    }
                    s.tick()
                }
            }
        }
        assertTrue(checked > 200 && hits > 10, "$checked checked, $hits hits")
    }

    /** 64-bit FNV-1a over the UTF-8 bytes of [s]. */
    private fun fnv(h0: ULong, s: String): ULong {
        var h = h0
        for (b in s.encodeToByteArray()) h = (h xor (b.toLong() and 0xff).toULong()) * 0x100000001b3uL
        return h
    }

    @Test
    fun `golden - predictions on three progressive rivers - the same on every platform`() {
        var h = 0xcbf29ce484222325uL
        var n = 0
        var nanos = 0L
        var legalNanos = 0L
        var observed = 0
        for (seed in 1L..3L) {
            GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE).use { s ->
                while (!s.world.over && s.world.tick < 4_000) {
                    if (s.world.tick % 12 == 0L) {
                        val l0 = GameClock.nanoTime()
                        val legal = Mechanics.legalActions(s.world)
                        legalNanos += GameClock.nanoTime() - l0
                        val t0 = GameClock.nanoTime()
                        val o = Observation.of(s.world, legal)
                        nanos += GameClock.nanoTime() - t0
                        observed++
                        for (p in o.paths) {
                            h = fnv(h, "${p.steer}:${canonical(p.predicted!!)}\n")
                            n++
                        }
                    }
                    s.tick()
                }
            }
        }
        println("prediction golden: $n predictions, ${h.toString(16)}; Observation.of with prediction ${nanos / observed / 1000} us mean (the legal set: ${legalNanos / observed / 1000} us)")
        assertEquals(GOLDEN, h.toString(16).padStart(16, '0'))
    }

    /** Every field, Doubles as raw IEEE-754 bits: text formatting of a Double may differ by platform. */
    private fun canonical(p: Prediction): String {
        val f = p.fuel!!
        return "${p.hitTicks}|${p.hitWith}|${p.crossing}|${p.leaving}|${p.shoots}|" +
            "${f.refuelTicks}|${f.depotAhead?.toRawBits()}|${f.depotAcross?.toRawBits()}|${f.dryTicks}|${f.emptyTicks}"
    }

    private companion object {
        const val GOLDEN = "d45502ee16964cf6"
    }
}

/** The run's crash-avoidance and takeover counters, as the results card reads them. */
class CrashCountersTest {
    private fun heliCrossing(): Observation {
        val w = World(1).also { it.clearEntities() }
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 6, 4.5))
        return Observation.of(w, Mechanics.legalActions(w))
    }

    @Test
    fun `a safe way flown while another was predicted to crash counts as avoided`() {
        val s = DecisionStats()
        val o = heliCrossing()
        s.recordCrashChoice(o, Action.RIGHT)
        s.recordCrashChoice(o, Action.HOLD)
        assertEquals(2, s.crashChoices)
        assertEquals(1, s.crashesAvoided)
    }

    @Test
    fun `running dry while another way reaches fuel is a predicted crash too`() {
        val w = World(1).also { it.clearEntities() }
        w.setFuel(5.0)
        w.addDepot(Depot(w.playerX - 7.0, w.playerY + 2.5))
        val o = Observation.of(w, Mechanics.legalActions(w))
        assertTrue(o.path(1)!!.predicted!!.clear, "no collision to the right")
        assertTrue(o.predictedCrash(1), "but it runs dry")
        assertFalse(o.predictedCrash(-1))
        val s = DecisionStats()
        s.recordCrashChoice(o, Action.LEFT, FuelGate.NONE)
        s.recordCrashChoice(o, Action.RIGHT, FuelGate.NONE)
        assertEquals(2, s.crashChoices)
        assertEquals(1, s.crashesAvoided)
    }

    @Test
    fun `with every way safe there was no choice to count`() {
        val s = DecisionStats()
        val w = World(1).also { it.clearEntities() }
        s.recordCrashChoice(Observation.of(w, Mechanics.legalActions(w)), Action.HOLD)
        assertEquals(0, s.crashChoices)
    }

    @Test
    fun `consecutive override ticks are one takeover`() {
        val s = DecisionStats()
        listOf(10L, 11L, 12L, 20L, 22L, 23L).forEach { s.recordOverride(it) }
        assertEquals(6, s.overrides)
        assertEquals(3, s.takeovers)
    }

    @Test
    fun `a session counts avoided collisions from the decisions it lands`() {
        val session = GameSession(4, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE)
        repeat(3_000) { session.tick() }
        val s = session.stats
        assertTrue(s.crashChoices > 0, "some decisions had a predicted crash on offer")
        assertTrue(s.crashesAvoided in 0..s.crashChoices)
        assertTrue(s.takeovers in 1..s.overrides)
        assertEquals(100.0 * (s.total - s.lateAnswers) / s.total, s.onTimePercent()!!, 1e-9)
        session.close()
    }
}

/**
 * [FuelOutlook]: whether a way reaches fuel before the tank is empty. Scenarios on seed 1's opening
 * river (classic: 2.5% a second, 7 rows a second), each checked against the simulation stepped with
 * the same plan ([FuelOutlook.planned]): hold the way's move for [Mechanics.HOLD_TICKS], then home
 * in on the depot on the way that heads for it, straight on on the others.
 */
class FuelOutlookTest {
    private fun open(x: Double = Rules.HALF.toDouble(), fuel: Double = 50.0): World = World(1).also {
        it.clearEntities()
        it.placePlayer(x)
        it.setFuel(fuel)
    }

    /** Steps [world] itself (no enemies, no bridges on it) with the fuel plan for [target]: the first refuel tick, or null. */
    private fun flown(world: World, first: Action, target: Depot): Int? {
        val heads = FuelOutlook.heads(first.steer, target.x - world.playerX)
        for (t in 0 until FuelOutlook.FUEL_HORIZON_TICKS) {
            val before = world.tally.refuelTicks
            world.step(FuelOutlook.planned(world, first, t, target, heads))
            if (world.tally.refuelTicks > before) return t + 1
            if (world.over) return null
            if (world.depots.none { it.alive && it.x == target.x && it.y == target.y && it.y + it.height > world.playerY }) return null
        }
        return null
    }

    private val perTick = Rules.FUEL_DRAIN_PER_S * Rules.DT

    @Test
    fun `a depot ahead is reached - at the tick the simulation refuels`() {
        val w = open()
        val depot = Depot(w.playerX, w.playerY + 10)
        w.addDepot(depot)
        val straight = FuelOutlook.of(w, Action.HOLD)
        assertTrue(straight.reaches)
        assertEquals(flown(w.copy(), Action.HOLD, depot), straight.refuelTicks)
        assertEquals(10.0, straight.depotAhead!!, 1e-9)
        assertFalse(straight.runsDry)
    }

    @Test
    fun `a depot close on one side is missed by the other way - which runs dry when low`() {
        val w = open(fuel = 5.0)
        val depot = Depot(w.playerX - 7.0, w.playerY + 2.5)
        w.addDepot(depot)
        val left = FuelOutlook.of(w, Action.LEFT)
        val right = FuelOutlook.of(w, Action.RIGHT)
        assertTrue(left.reaches)
        assertEquals(flown(w.copy(), Action.LEFT, depot), left.refuelTicks)
        assertFalse(right.reaches)
        assertNull(flown(w.copy(), Action.RIGHT, depot), "the simulation agrees: steering right misses it")
        // 5% lasts 2 s; the next depot comes at least a mean gap after this one, well beyond that.
        assertTrue(right.runsDry)
        assertEquals(right.emptyTicks, right.dryTicks)
        assertEquals(kotlin.math.ceil(5.0 / perTick - 1e-9).toInt(), right.emptyTicks)
        // With a full tank, missing it is not running dry.
        w.setFuel(100.0)
        assertFalse(FuelOutlook.of(w, Action.RIGHT).runsDry)
    }

    @Test
    fun `only the way heading for a depot to one side reaches it - straight on flies past`() {
        val w = open(fuel = 60.0)
        val depot = Depot(w.playerX + 6.0, w.playerY + 18)
        w.addDepot(depot)
        val right = FuelOutlook.of(w, Action.RIGHT)
        assertTrue(right.reaches)
        assertEquals(flown(w.copy(), Action.RIGHT, depot), right.refuelTicks)
        for (a in listOf(Action.HOLD, Action.LEFT)) {
            assertFalse(FuelOutlook.of(w, a).reaches, "$a")
            assertNull(flown(w.copy(), a, depot), "$a: the simulation flies past it too")
        }
    }

    @Test
    fun `a way whose own shot destroys the depot does not reach it`() {
        val w = open()
        val depot = Depot(w.playerX, w.playerY + 8)
        w.addDepot(depot)
        assertTrue(FuelOutlook.of(w, Action.HOLD).reaches)
        val firing = FuelOutlook.of(w, Action.HOLD_FIRE)
        assertFalse(firing.reaches)
        assertNull(flown(w.copy(), Action.HOLD_FIRE, depot))
    }

    @Test
    fun `with several depots the first one the way can reach is reported`() {
        val w = open()
        val near = Depot(w.playerX + 7.0, w.playerY + 3.0)
        val far = Depot(w.playerX - 3.0, w.playerY + 20.0)
        w.addDepot(near)
        w.addDepot(far)
        val left = FuelOutlook.of(w, Action.LEFT)
        assertEquals(flown(w.copy(), Action.LEFT, far), left.refuelTicks)
        assertEquals(20.0, left.depotAhead!!, 1e-9)
        val right = FuelOutlook.of(w, Action.RIGHT)
        assertEquals(flown(w.copy(), Action.RIGHT, near), right.refuelTicks)
        assertEquals(3.0, right.depotAhead!!, 1e-9)
    }

    @Test
    fun `fuel exactly at the margin - one tick short runs dry - just enough reaches`() {
        val w = open(fuel = 100.0)
        val depot = Depot(w.playerX, w.playerY + 10)
        w.addDepot(depot)
        val t = FuelOutlook.of(w, Action.HOLD).refuelTicks!!
        // It burns for t - 1 ticks and refuels on tick t: that much fuel, and a hair more, arrives.
        w.setFuel((t - 1) * perTick + 1e-6)
        assertEquals(t, FuelOutlook.of(w, Action.HOLD).refuelTicks)
        assertEquals(t, flown(w.copy(), Action.HOLD, depot))
        w.setFuel((t - 1) * perTick - 1e-6)
        val short = FuelOutlook.of(w, Action.HOLD)
        assertFalse(short.reaches)
        assertNull(flown(w.copy(), Action.HOLD, depot))
        assertTrue(short.runsDry)
        assertEquals(t - 1, short.dryTicks)
        val dead = w.copy()
        repeat(t - 1) { dead.step(Action.HOLD) }
        assertEquals(DeathCause.FUEL, dead.death, "the simulation runs dry on that tick")
    }

    @Test
    fun `the fuel flight goes round an island to the depot beyond it`() {
        // Seed 1's first island on the phone's river: the plane in its right channel, a depot in
        // the left of the river a few rows past the island's end.
        val river = World(1, Difficulty.PROGRESSIVE).river
        val row = (30..400).first { river.row(it).hasIsland && !river.row(it + 12).hasIsland }
        val end = (row..row + 12).first { !river.row(it).hasIsland }
        GameSession(1, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE).use { s ->
            while (s.world.playerY < row - 2 && !s.world.over) s.tick()
            assertFalse(s.world.over)
            val here = s.world.copy().also { it.clearEntities() }
            // Near the channel's right edge, so one held decision of steering left stays in it.
            here.placePlayer(river.row(row).water.last().to - 2.0)
            val x = river.row(row).water.first().center
            val depotRow = end + kotlin.math.ceil(kotlin.math.abs(x - here.playerX) / 2).toInt() + 3
            val depot = Depot(x, depotRow.toDouble())
            here.addDepot(depot)
            // Heading straight for it would cross the island.
            assertTrue(river.row(row + 1).landIn(minOf(x, here.playerX), maxOf(x, here.playerX)))
            val o = FuelOutlook.of(here, Action.LEFT)
            assertEquals(flown(here.fuelProbe(), Action.LEFT, depot), o.refuelTicks)
            assertTrue(o.reaches, "it goes round the island's end to the depot")
            assertFalse(FuelOutlook.of(here, Action.HOLD).reaches, "straight on stays in its channel")
        }
    }

    @Test
    fun `the projection leaves the world untouched`() {
        val w = open()
        w.addDepot(Depot(w.playerX + 3, w.playerY + 12))
        val before = w.fingerprint()
        FuelOutlook.of(w, Action.RIGHT_FIRE)
        assertEquals(before, w.fingerprint())
    }

    @Test
    fun `every fuel outlook in real flights matches the simulation stepped with that plan`() {
        var checked = 0
        var reached = 0
        var dry = 0
        for (seed in 1L..3L) {
            GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE).use { s ->
                while (!s.world.over && s.world.tick < 3_000) {
                    if (s.world.tick % 30 == 0L) {
                        for (a in listOf(Action.LEFT, Action.HOLD, Action.RIGHT_FIRE)) {
                            val o = FuelOutlook.of(s.world, a)
                            val py = s.world.playerY
                            val targets = s.world.depots.filter { it.alive && it.y + it.height > py }.sortedBy { it.y }.take(FuelOutlook.MAX_DEPOTS)
                            val truth = targets.firstNotNullOfOrNull { flown(s.world.fuelProbe(), a, it) }
                            assertEquals(truth, o.refuelTicks, "seed $seed tick ${s.world.tick} $a")
                            if (truth != null) reached++
                            if (o.runsDry) dry++
                            checked++
                        }
                    }
                    s.tick()
                }
            }
        }
        assertTrue(checked > 200 && reached > 20, "$checked checked, $reached reached, $dry dry")
    }
}
