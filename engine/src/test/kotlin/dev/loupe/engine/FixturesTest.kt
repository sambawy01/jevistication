package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixturesTest {

    private fun corpus(groups: Int, perGroup: Int): List<Fixture> =
        (0 until groups).flatMap { g ->
            (0 until perGroup).map { i ->
                Fixture(Item("g$g-i$i", "text"), if (g % 2 == 0) "yes" else "no", "group-$g")
            }
        }

    @Test
    fun `never splits a source group across two splits`() {
        val split = Fixtures.splitByGroup(corpus(groups = 20, perGroup = 5), seed = 3L)

        val dev = split.dev.map { it.sourceGroup }.toSet()
        val cal = split.calibration.map { it.sourceGroup }.toSet()
        val test = split.test.map { it.sourceGroup }.toSet()

        assertTrue((dev intersect cal).isEmpty(), "dev and calibration share a group")
        assertTrue((dev intersect test).isEmpty(), "dev and test share a group")
        assertTrue((cal intersect test).isEmpty(), "calibration and test share a group")
    }

    @Test
    fun `accounts for every fixture exactly once`() {
        val fixtures = corpus(groups = 13, perGroup = 4)
        val split = Fixtures.splitByGroup(fixtures, seed = 1L)
        assertEquals(fixtures.size, split.size)
        assertEquals(
            fixtures.map { it.item.id }.toSet(),
            (split.dev + split.calibration + split.test).map { it.item.id }.toSet(),
        )
    }

    @Test
    fun `is deterministic for a given seed`() {
        val fixtures = corpus(groups = 15, perGroup = 3)
        val a = Fixtures.splitByGroup(fixtures, seed = 42L)
        val b = Fixtures.splitByGroup(fixtures, seed = 42L)
        assertEquals(a.test.map { it.item.id }, b.test.map { it.item.id })
        assertEquals(a.calibration.map { it.item.id }, b.calibration.map { it.item.id })
    }

    @Test
    fun `keeps calibration distinct from test so nothing is fitted on test`() {
        val split = Fixtures.splitByGroup(corpus(groups = 20, perGroup = 5), seed = 5L)
        assertTrue(split.calibration.isNotEmpty())
        assertTrue(split.test.isNotEmpty())
        assertTrue(
            split.calibration.map { it.item.id }.intersect(split.test.map { it.item.id }.toSet())
                .isEmpty(),
        )
    }

    @Test
    fun `roughly respects the requested shares`() {
        val fixtures = corpus(groups = 50, perGroup = 4) // 200 items
        val split = Fixtures.splitByGroup(fixtures, calibrationShare = 0.25, testShare = 0.25, seed = 9L)
        assertTrue(split.test.size in 40..70, "test was ${split.test.size}")
        assertTrue(split.calibration.size in 40..70, "calibration was ${split.calibration.size}")
    }

    @Test
    fun `an empty corpus splits into empty splits`() {
        val split = Fixtures.splitByGroup(emptyList())
        assertEquals(0, split.size)
    }

    @Test
    fun `rejects shares that leave no room for dev`() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.splitByGroup(corpus(4, 2), calibrationShare = 0.6, testShare = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            Fixtures.splitByGroup(corpus(4, 2), calibrationShare = 0.0, testShare = 0.2)
        }
    }
}
