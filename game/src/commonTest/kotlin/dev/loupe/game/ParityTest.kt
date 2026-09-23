package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM ↔ iOS parity: this common test runs on both (`:game:jvmTest`, `:game:iosSimulatorArm64Test`)
 * and pins the same digests on each, so the same seed provably gives the same river, the same world,
 * the same legal sets and the same baseline decisions on the desktop and on the phone.
 *
 * Every number goes into the digest as its raw IEEE-754 bits, never as text, so a formatting
 * difference cannot hide (or fake) a divergence. The golden values were recorded on the JVM; a
 * platform whose `cos`/`sin` (the river's Perlin noise) or arithmetic differed by one ulp where it
 * matters would change them.
 */
class ParityTest {

    /** 64-bit FNV-1a over longs. */
    private class Digest {
        var h: ULong = 0xcbf29ce484222325uL
            private set

        fun add(v: Long) {
            var x = v
            repeat(8) {
                h = (h xor (x and 0xff).toULong()) * 0x100000001b3uL
                x = x ushr 8
            }
        }

        fun add(v: Double) = add(v.toRawBits())
        fun add(v: Int) = add(v.toLong())
        fun add(v: Boolean) = add(if (v) 1L else 0L)

        fun hex(): String = h.toString(16).padStart(16, '0')
    }

    private fun Digest.world(w: World) {
        add(w.tick); add(w.cameraY); add(w.playerX); add(w.fuel); add(w.score); add(w.cooldown)
        add(w.death?.ordinal ?: -1)
        add(w.enemies.size)
        w.enemies.forEach { add(it.kind.ordinal); add(it.x); add(it.y); add(it.vx); add(it.alive) }
        add(w.depots.size)
        w.depots.forEach { add(it.x); add(it.y); add(it.alive) }
        add(w.bridges.size)
        w.bridges.forEach { add(it.row); add(it.from); add(it.to); add(it.alive) }
        add(w.bullets.size)
        w.bullets.forEach { add(it.x); add(it.y) }
    }

    private fun riverDigest(seed: Long): String {
        val d = Digest()
        val river = River(seed)
        for (i in 0 until 3_000) {
            val r = river.row(i)
            d.add(r.left); d.add(r.right); d.add(r.islandFrom); d.add(r.islandTo); d.add(r.bridge)
            r.spawns.forEach { s ->
                d.add(s.x)
                when (s) {
                    is Spawn.Enemy -> { d.add(s.kind.ordinal); d.add(s.vx) }
                    is Spawn.Depot -> d.add(-1)
                }
            }
        }
        return d.hex()
    }

    /** A baseline session: world, legal set and decision digested every tick. */
    private fun sessionDigest(seed: Long, ticks: Int): String {
        val d = Digest()
        GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 3)), clock = { 0L }).use { s ->
            var seen: PilotDecision? = null
            while (!s.world.over && s.world.tick < ticks) {
                if (s.world.tick % 10 == 0L) {
                    val legal = Mechanics.legalActions(s.world)
                    legal.actions.forEach { d.add(it.ordinal) }
                    legal.excluded.forEach { (a, why) -> d.add(a.ordinal * 10 + why.ordinal) }
                    d.add(StateText.describe(Observation.of(s.world, legal)).hashCode())
                }
                s.tick()
                val c = s.current
                if (c != null && c !== seen) {
                    d.add(c.action.ordinal); d.add(c.observedTick); d.add(c.source.ordinal)
                    seen = c
                }
                d.add(s.lastFlown.ordinal)
                d.world(s.world)
            }
            d.add(s.stats.total); d.add(s.stats.overrides)
        }
        return d.hex()
    }

    @Test
    fun riverRowsMatchTheRecordedDigests() {
        assertEquals(GOLDEN_RIVER, listOf(1L, 2L, 7L, 12345L).map(::riverDigest))
    }

    @Test
    fun baselineSessionsMatchTheRecordedDigests() {
        assertEquals(GOLDEN_SESSION, listOf(1L, 2L, 7L).map { sessionDigest(it, 3_600) })
    }

    @Test
    fun matchEpisodesMatchTheRecordedResults() {
        val e = Match.run(listOf(1L, 2L), listOf(BaselinePilot()), Match.Settings(maxTicks = 3_600))
        assertEquals(GOLDEN_MATCH, e.map { "${it.seed}:${it.score}:${it.rows.toRawBits()}:${it.death}:${it.decisions}:${it.overrides}" })
    }

    private companion object {
        val GOLDEN_RIVER = listOf("c5db8361c3deb959", "2f32e6de1bf2caad", "fa910d632e1672c5", "a0c18323c6f62c0b")
        val GOLDEN_SESSION = listOf("3f267c8eb2b65cea", "c0cb8adc5cf12170", "53ae441f033fe39c")
        val GOLDEN_MATCH = listOf("1:780:4645634539446599923:FUEL:562:153", "2:1220:4646096334330265873:null:600:104")
    }
}
