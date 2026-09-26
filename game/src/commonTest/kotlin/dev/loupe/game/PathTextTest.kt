package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the model pilot reads — the ways it is offered, each way's words, the scene — pinned. The
 * golden digest runs on the JVM and the iOS simulator (this is common code), so Laya is asked the
 * same question, word for word, on the desktop and on the phone.
 */
class PathTextTest {
    private fun open(): World = World(1).also { it.clearEntities() }

    private fun observe(w: World) = Observation.of(w, Mechanics.legalActions(w))

    @Test
    fun `open water reads safe every way`() {
        val o = observe(open())
        assertEquals(listOf("safe", "safe", "safe"), listOf(-1, 0, 1).map { PathText.describe(o, it) })
        assertEquals("fuel 100%", PathText.scene(o))
    }

    @Test
    fun `a heli crossing into the straight path reads as a crash there only`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 6, 4.5))
        val o = observe(w)
        assertEquals("crash: heli crossing in, 0.5 s", PathText.describe(o, 0))
        assertEquals("safe", PathText.describe(o, 1))
    }

    @Test
    fun `a heli in line but moving away reads safe - and says why`() {
        val w = open()
        // 1.4 columns right: overlapping the plane's path now (0.75 + 0.8 > 1.4), but outside the
        // gun's line (1.2), so no shot is planned — it is only moving away.
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX + 1.4, w.playerY + 6, 4.5))
        val o = observe(w)
        assertEquals("safe, heli moving away", PathText.describe(o, 0))
    }

    @Test
    fun `a boat dead ahead that the gun will hit reads safe straight on`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX, w.playerY + 5, 0.0))
        val o = observe(w)
        assertEquals("boat", o.path(0)!!.predicted!!.shoots)
        assertEquals("safe", PathText.describe(o, 0))
    }

    @Test
    fun `fuel is mentioned only when the tank is not full`() {
        val w = open()
        w.addDepot(Depot(w.playerX + 5, w.playerY + 12))
        assertEquals("safe", PathText.describe(observe(w), 1))
        w.setFuel(60.0)
        val low = observe(w)
        assertEquals("safe, fuel that way", PathText.describe(low, 1))
        assertEquals("safe", PathText.describe(low, -1))
        assertEquals("fuel 60%", PathText.scene(low))
    }

    @Test
    fun `a way that runs dry while another reaches fuel reads as a crash`() {
        val w = open()
        w.addDepot(Depot(w.playerX + 5, w.playerY + 12))
        w.setFuel(30.0)
        val o = observe(w)
        assertTrue(o.path(1)!!.predicted!!.fuel!!.reaches)
        val dry = o.path(-1)!!.predicted!!.fuel!!
        assertTrue(dry.runsDry)
        assertEquals("crash: out of fuel in ${PathText.seconds(dry.dryTicks!!)}", PathText.describe(o, -1))
        assertEquals("crash: out of fuel in 12 s", PathText.describe(o, -1), "30% at 2.5% a second")
        assertEquals("safe, fuel that way", PathText.describe(o, 1))
        assertEquals("fuel 30%, low", PathText.scene(o))
        // A collision is said first: it comes sooner.
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX - 3.0, w.playerY + 4, 0.0))
        assertTrue(PathText.describe(observe(w), -1).startsWith("crash: boat"))
    }

    @Test
    fun `with no way reaching fuel running dry is no way's fault - nothing is said`() {
        val w = open()
        w.setFuel(10.0)
        val o = observe(w)
        assertTrue(o.paths.all { it.predicted!!.fuel!!.runsDry })
        assertEquals(listOf("safe", "safe", "safe"), listOf(-1, 0, 1).map { PathText.describe(o, it) })
    }

    @Test
    fun `land ahead on a path is named with when`() {
        // Seed 1's banks sit 3 columns in at the start: a hard-left path meets them.
        val w = open()
        w.placePlayer(4.6)
        val o = observe(w)
        assertEquals("crash: land in 0.5 s", PathText.describe(o, -1))
        assertEquals("safe", PathText.describe(o, 1))
    }

    @Test
    fun `seconds are rounded to the half second - at least half a second`() {
        assertEquals(listOf("0.5 s", "0.5 s", "1 s", "1.5 s"), listOf(1, 30, 60, 90).map(PathText::seconds))
    }

    @Test
    fun `the question is one word per way with its description`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 4.0, w.playerY + 6, 4.5))
        val o = observe(w)
        val offered = ModelPilot.gates(o, o.legal.actions)
        assertEquals(listOf(Action.LEFT, Action.HOLD, Action.RIGHT), offered)
        val j = ModelPilot.judgment(o, offered)
        assertEquals("Which way is safest?", j.question)
        assertEquals(listOf("left", "straight", "right"), j.candidates)
        assertEquals(listOf(PathText.describe(o, -1), "crash: heli crossing in, 0.5 s", "safe"), j.descriptionList)
    }

    /** 64-bit FNV-1a over the UTF-8 bytes of [s]. */
    private fun fnv(h0: ULong, s: String): ULong {
        var h = h0
        for (b in s.encodeToByteArray()) h = (h xor (b.toLong() and 0xff).toULong()) * 0x100000001b3uL
        return h
    }

    @Test
    fun `golden - every question the model would be asked on three progressive rivers`() {
        var h = 0xcbf29ce484222325uL
        var asked = 0
        for (seed in 1L..3L) {
            GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 4)), difficulty = Difficulty.PROGRESSIVE).use { s ->
                while (!s.world.over && s.world.tick < 4_000) {
                    if (s.world.tick % 6 == 0L) {
                        val o = observe(s.world)
                        val offered = ModelPilot.gates(o, o.legal.actions)
                        h = fnv(h, offered.joinToString(",") { it.name } + "|")
                        if (offered.size > 1) {
                            val j = ModelPilot.judgment(o, offered)
                            h = fnv(h, j.candidates.joinToString(",") + "|" + j.descriptionList.joinToString(";") + "|" + PathText.scene(o) + "\n")
                            asked++
                        }
                    }
                    s.tick()
                }
            }
        }
        println("path text golden: $asked questions, ${h.toString(16)}")
        assertEquals(GOLDEN, h.toString(16).padStart(16, '0'))
    }

    private companion object {
        const val GOLDEN = "1642ad4bb499f3df"
    }
}
