package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MechanicsTest {

    /** A world in open water with the plane hard against the left bank. */
    private fun againstLeftBank(): World {
        val world = World(1)
        world.clearEntities()
        val row = world.river.rowAt(world.playerY)
        world.placePlayer(row.left + Rules.PLAYER_W / 2 + 0.05)
        return world
    }

    @Test
    fun `steering into an adjacent bank is not offered`() {
        val legal = Mechanics.legalActions(againstLeftBank())
        assertFalse(Action.LEFT in legal.actions, "offered ${legal.actions}")
        assertFalse(Action.LEFT_FIRE in legal.actions, "offered ${legal.actions}")
        assertEquals(Exclusion.FATAL, legal.excluded[Action.LEFT])
        assertTrue(Action.HOLD in legal.actions, "the no-op must stay on offer when it is safe")
    }

    @Test
    fun `in open water every action is offered, the no-op included`() {
        val world = World(1)
        world.clearEntities()
        assertEquals(Action.entries.toList(), Mechanics.legalActions(world).actions)
    }

    @Test
    fun `an enemy dead ahead makes holding course without shooting illegal`() {
        val world = World(1)
        world.clearEntities()
        world.addEnemy(Enemy(EnemyKind.BOAT, world.playerX, world.playerY + 2.2, 0.0))
        val legal = Mechanics.legalActions(world)
        assertFalse(Action.HOLD in legal.actions, "offered ${legal.actions}")
        assertTrue(Action.HOLD_FIRE in legal.actions, "shooting it is a way out: ${legal.actions}")
    }

    @Test
    fun `low on fuel, shots that would destroy the depot ahead are not offered`() {
        val world = World(1)
        world.clearEntities()
        world.setFuel(20.0)
        world.addDepot(Depot(world.playerX, world.playerY + 8))
        val legal = Mechanics.legalActions(world)
        assertTrue(legal.actions.none { it.fire }, "offered ${legal.actions}")
        assertEquals(Exclusion.SHOOTS_LAST_FUEL, legal.excluded[Action.HOLD_FIRE])

        world.setFuel(90.0)
        assertTrue(Mechanics.legalActions(world).actions.any { it.fire }, "with a full tank the depot is a target")
    }

    @Test
    fun `no legal action ever dies within its hold, across whole baseline runs`() {
        // Property check over thousands of real states: wherever some action survives, every
        // action offered survives the hold (the recovery half is checked by Mechanics itself).
        var checked = 0
        for (seed in 1L..4L) {
            val session = GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 3)))
            while (!session.world.over && session.world.tick < 3_000) {
                if (session.world.tick % 5 == 0L) {
                    val world = session.world
                    val legal = Mechanics.legalActions(world)
                    val anySurvives = Action.entries.any { Mechanics.survives(world, it, Mechanics.HOLD_TICKS, Mechanics.RECOVERY_TICKS) }
                    if (anySurvives) {
                        for (action in legal.actions) {
                            assertEquals(
                                Mechanics.HOLD_TICKS,
                                Mechanics.survivalTicks(world, action, Mechanics.HOLD_TICKS),
                                "seed $seed tick ${world.tick}: offered $action, which crashes",
                            )
                            checked++
                        }
                    }
                }
                session.tick()
            }
        }
        assertTrue(checked > 1_000, "only $checked offered actions checked")
    }

    @Test
    fun `the safety override replaces a fatal action, records it, and saves the plane`() {
        val guarded = GameSession(1, Control.Human, overrideEnabled = true)
        guarded.human = HumanInput(left = true)
        repeat(300) {
            guarded.world.clearEntities()
            guarded.tick()
        }
        assertNull(guarded.world.death, "the override should have kept the plane off the bank")
        val event = assertNotNull(guarded.lastOverride)
        assertEquals(Action.LEFT, event.wanted)
        assertTrue(event.replacedWith.steer >= 0, "replaced with ${event.replacedWith}")
        assertTrue(guarded.stats.overrides > 0)

        val unguarded = GameSession(1, Control.Human, overrideEnabled = false)
        unguarded.human = HumanInput(left = true)
        repeat(300) {
            unguarded.world.clearEntities()
            unguarded.tick()
        }
        assertEquals(DeathCause.BANK, unguarded.world.death)
        assertEquals(0, unguarded.stats.overrides)
    }

    @Test
    fun `the override makes the smallest change that saves the plane`() {
        val world = World(1)
        world.clearEntities()
        world.addEnemy(Enemy(EnemyKind.HELI, world.playerX, world.playerY + 2.0, 0.0))
        assertEquals(Action.HOLD_FIRE, Mechanics.safetyOverride(world, Action.HOLD))
    }

    @Test
    fun `the override leaves a safe action alone`() {
        val world = World(1)
        world.clearEntities()
        assertNull(Mechanics.safetyOverride(world, Action.HOLD))
        assertNull(Mechanics.safetyOverride(world, Action.RIGHT_FIRE))
    }
}
