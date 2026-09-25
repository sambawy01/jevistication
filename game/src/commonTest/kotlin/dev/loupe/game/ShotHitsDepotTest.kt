package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Mechanics.shotHitsDepot], the check human auto-fire holds on (iOS, owner's report 2026-09-25:
 * steering onto the gas station destroyed it). Each "true" case is checked against the real world:
 * firing there does destroy the depot.
 */
class ShotHitsDepotTest {
    private fun open(): World = World(1).also { it.clearEntities() }

    private fun firingDestroysDepot(world: World): Boolean {
        val probe = world.copy()
        probe.step(Action.HOLD_FIRE)
        repeat(60) { probe.step(Action.HOLD) }
        return probe.tally.depotsShot > 0
    }

    @Test
    fun `a depot ahead in line is hit`() {
        val world = open()
        world.addDepot(Depot(world.playerX, world.playerY + 8))
        assertTrue(Mechanics.depotInLineOfFire(world))
        assertTrue(Mechanics.shotHitsDepot(world, 0))
        assertTrue(firingDestroysDepot(world))
    }

    @Test
    fun `the depot the plane is flying over to refuel is hit - the line-of-fire check misses it`() {
        val world = open()
        world.addDepot(Depot(world.playerX, world.playerY - 0.05))
        assertFalse(Mechanics.depotInLineOfFire(world), "the gap this check closes")
        assertTrue(firingDestroysDepot(world), "the real world does destroy it")
        assertTrue(Mechanics.shotHitsDepot(world, 0))
    }

    @Test
    fun `a depot just beyond the view that scrolls into the bullet is hit`() {
        val world = open()
        world.addDepot(Depot(world.playerX, world.playerY + Rules.VIEW_ROWS + 0.5))
        assertFalse(Mechanics.depotInLineOfFire(world), "beyond its reach")
        assertTrue(firingDestroysDepot(world), "the real world does destroy it")
        assertTrue(Mechanics.shotHitsDepot(world, 0))
    }

    @Test
    fun `a depot off the line or already passed is not hit`() {
        val world = open()
        world.addDepot(Depot(world.playerX + 3, world.playerY + 8))
        world.addDepot(Depot(world.playerX, world.playerY - 3))
        assertFalse(firingDestroysDepot(world))
        assertFalse(Mechanics.shotHitsDepot(world, 0))
    }

    @Test
    fun `no shot leaves while the gun reloads`() {
        val world = open()
        world.addDepot(Depot(world.playerX, world.playerY + 8))
        world.step(Action.HOLD_FIRE)
        assertTrue(world.cooldown > 1)
        assertFalse(Mechanics.shotHitsDepot(world, 0))
    }

    @Test
    fun `the check leaves the world untouched`() {
        val world = open()
        world.addDepot(Depot(world.playerX, world.playerY + 8))
        val before = world.fingerprint()
        Mechanics.shotHitsDepot(world, 1)
        assertEquals(before, world.fingerprint())
    }

    @Test
    fun `a depot not spawned yet that the bullet will meet counts`() {
        // Scan a real river for a moment where no depot is on the map yet, but a shot fired now
        // does destroy one that spawns at the top of the view while the bullet flies.
        val world = World(3)
        var found = false
        for (t in 0 until 4000) {
            if (world.over) break
            if (world.weaponReady && world.depots.none { it.alive } && firingDestroysDepot(world)) {
                assertTrue(Mechanics.shotHitsDepot(world, 0), "tick ${world.tick}")
                found = true
                break
            }
            world.step(Mechanics.safetyOverride(world, Action.HOLD) ?: Action.HOLD)
        }
        assertTrue(found, "the river never set up the case")
    }
}
