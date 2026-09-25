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
    fun `open water reads as open water every way`() {
        val o = observe(open())
        assertEquals(listOf("open water", "open water", "open water"), listOf(-1, 0, 1).map { PathText.describe(o, it) })
        assertEquals("fuel 100%", PathText.scene(o))
    }

    @Test
    fun `a boat dead ahead is on the straight path only`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX, w.playerY + 5, 0.0))
        val o = observe(w)
        assertEquals("boat close", PathText.describe(o, 0))
        assertEquals("open water", PathText.describe(o, -1))
        assertEquals("open water", PathText.describe(o, 1))
    }

    @Test
    fun `a moving heli is placed where it will be`() {
        val w = open()
        // Two columns left, flying right at 4.5 columns a second: in line by the time the plane is there.
        w.addEnemy(Enemy(EnemyKind.HELI, w.playerX - 2.0, w.playerY + 3.0, 4.5))
        val o = observe(w)
        assertEquals("heli very close", PathText.describe(o, 0))
    }

    @Test
    fun `fuel is mentioned only when the tank is not full`() {
        val w = open()
        w.addDepot(Depot(w.playerX + 5, w.playerY + 12))
        assertEquals("open water", PathText.describe(observe(w), 1))
        w.setFuel(40.0)
        val low = observe(w)
        assertEquals("fuel that way", PathText.describe(low, 1))
        assertEquals("open water", PathText.describe(low, -1))
        assertEquals("fuel 40%, low", PathText.scene(low))
    }

    @Test
    fun `land ahead on a path is named with how close`() {
        // Seed 1's banks sit 3 columns in at the start: a hard-left path meets them.
        val w = open()
        w.placePlayer(4.6)
        val o = observe(w)
        val left = o.path(-1)!!
        assertTrue(left.landRows != null && left.landRows!! <= 3, "$left")
        assertEquals("land very close", PathText.describe(o, -1))
        assertNull(o.path(1)!!.landRows)
    }

    @Test
    fun `the question is one word per way with its description`() {
        val w = open()
        w.addEnemy(Enemy(EnemyKind.BOAT, w.playerX, w.playerY + 5, 0.0))
        val o = observe(w)
        val offered = ModelPilot.gates(o, o.legal.actions)
        assertEquals(listOf(Action.LEFT_FIRE, Action.HOLD_FIRE, Action.RIGHT_FIRE), offered)
        val j = ModelPilot.judgment(o, offered)
        assertEquals("Which way is safest?", j.question)
        assertEquals(listOf("left", "straight", "right"), j.candidates)
        assertEquals(listOf("open water", "boat close", "open water"), j.descriptionList)
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
        const val GOLDEN = "f40eb949500c77d0"
    }
}
