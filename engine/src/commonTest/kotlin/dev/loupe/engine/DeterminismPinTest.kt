package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins every seeded random sequence the engine draws, with values captured on the JVM before the
 * Kotlin Multiplatform port. `kotlin.random.Random(seed)` is the same XorWow generator on every
 * target, so these must hold unchanged on JVM and iOS alike: a drift would silently move A8's
 * bootstrap intervals, the uncertain queue's audit arm, fixture splits and exploration.
 */
class DeterminismPinTest {

    private val urgent = Judgment.Choice("is-urgent", "Is this urgent?", listOf("yes", "no"))

    private fun explored(seed: Int, n: Int, yes: Double, exploration: Double): List<LedgerRow> {
        val engine = DecisionEngine(
            backend = Backend.ofMasses { _, s -> mapOf("yes" to yes - (s.text.length % 7) * 0.01, "no" to 1.0 - yes + (s.text.length % 7) * 0.01) },
            threshold = Probability.of(0.5),
            exploration = exploration,
            random = kotlin.random.Random(seed),
        )
        repeat(n) { engine.decide(urgent, Item("item-$it", "x".repeat(it % 11 + 1))) }
        return engine.ledger.rows()
    }

    @Test
    fun `exploration draws are pinned`() {
        val rows = explored(seed = 7, n = 40, yes = 0.85, exploration = 0.4)
        val actual = rows.joinToString("") { if (it.action == "yes") "y" else if (it.action == "no") "n" else "a" }
        assertEquals("yynyyyyyyyyyyynyyyyyyyynyyynyyyyynyyyyyy", actual)
        assertEquals(32.872, rows.sumOf { it.propensity.value })
    }

    @Test
    fun `bootstrap interval is pinned`() {
        val rows = explored(seed = 3, n = 300, yes = 0.8, exploration = 0.5)
            .mapIndexed { i, r -> r.copy(correction = if (i % 3 == 0) "no" else "yes") }
        val replay = OffPolicy.replayThreshold(rows, Probability.of(0.6), { if (it.action == it.correction) 1.0 else 0.0 })
        assertEquals(0.047527082204724014, replay.delta)
        assertEquals(0.023971947796573967, replay.deltaLower)
        assertEquals(0.0727848709297616, replay.deltaUpper)
        val replay2 = OffPolicy.replayThreshold(rows, Probability.of(0.0), { if (it.action == it.correction) 1.0 else 0.0 }, seed = 99L)
        assertEquals(0.047527082204724014, replay2.delta)
        assertEquals(0.02549990498720378, replay2.deltaLower)
        assertEquals(0.07151135132560804, replay2.deltaUpper)
    }

    @Test
    fun `audit arm and fixture split are pinned`() {
        val rows = explored(seed = 11, n = 60, yes = 0.9, exploration = 0.0)
            .mapIndexed { i, r -> r.copy(judgmentId = "j$i") }
        val queue = UncertainQueue.select(rows, size = 20, auditShare = 0.5, seed = 5L)
        assertEquals(
            "j16:U,j27:U,j38:U,j49:U,j5:U,j15:U,j26:U,j37:U,j4:U,j48:U," +
                "j50:A,j25:A,j30:A,j13:A,j17:A,j21:A,j40:A,j10:A,j6:A,j52:A",
            queue.joinToString(",") { it.row.judgmentId + ":" + it.reason.name.first() },
        )
        val fixtures = List(50) { Fixture(Item("f$it", "t"), if (it % 2 == 0) "a" else "b", "g${it % 13}") }
        val split = Fixtures.splitByGroup(fixtures, seed = 123L)
        assertEquals(
            "g8,g10,g9,g11,g1,g6,g0|g5,g12,g4|g2,g7,g3",
            listOf(split.dev, split.calibration, split.test)
                .joinToString("|") { s -> s.map { it.sourceGroup }.distinct().joinToString(",") },
        )
    }
}
