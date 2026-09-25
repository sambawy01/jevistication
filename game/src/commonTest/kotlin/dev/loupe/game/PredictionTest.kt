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
                            h = fnv(h, "${p.steer}:${p.predicted}\n")
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

    private companion object {
        const val GOLDEN = "1a7c8314e66b8841"
    }
}

/** The run's collision-avoidance and takeover counters, as the results card reads them. */
class CollisionCountersTest {
    private fun heliCrossing(): Observation {
        val w = World(1).also { it.clearEntities() }
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 6, 4.5))
        return Observation.of(w, Mechanics.legalActions(w))
    }

    @Test
    fun `a safe way flown while another was predicted to crash counts as avoided`() {
        val s = DecisionStats()
        val o = heliCrossing()
        s.recordCollisionChoice(o, Action.RIGHT)
        s.recordCollisionChoice(o, Action.HOLD)
        assertEquals(2, s.collisionChoices)
        assertEquals(1, s.collisionsAvoided)
    }

    @Test
    fun `with every way safe there was no choice to count`() {
        val s = DecisionStats()
        val w = World(1).also { it.clearEntities() }
        s.recordCollisionChoice(Observation.of(w, Mechanics.legalActions(w)), Action.HOLD)
        assertEquals(0, s.collisionChoices)
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
        assertTrue(s.collisionChoices > 0, "some decisions had a predicted crash on offer")
        assertTrue(s.collisionsAvoided in 0..s.collisionChoices)
        assertTrue(s.takeovers in 1..s.overrides)
        assertEquals(100.0 * (s.total - s.lateAnswers) / s.total, s.onTimePercent()!!, 1e-9)
        session.close()
    }
}
