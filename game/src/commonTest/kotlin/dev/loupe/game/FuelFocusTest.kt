package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals

class FuelFocusTest {
    private val all = Action.entries.toList()

    private fun obs(fuel: Int, depot: Sighting?) = Observation(
        tick = 0, playerX = 10.0, fuelPercent = fuel, weaponReady = true, score = 0,
        waterLeft = 5.0, waterRight = 5.0, landAheadRows = null, farChannels = emptyList(),
        threats = emptyList(), depot = depot, bridgeAheadRows = null,
        legal = LegalActions(all, emptyMap()),
    )

    @Test fun lowFuelSteersTowardTheDepot() =
        assertEquals(listOf(Action.LEFT, Action.LEFT_FIRE), ModelPilot.fuelFocus(obs(30, Sighting("depot", 6.0, -3.0)), all))

    @Test fun lowFuelNeverShootsTheDepotInLine() =
        assertEquals(listOf(Action.HOLD), ModelPilot.fuelFocus(obs(30, Sighting("depot", 6.0, 0.2)), all))

    @Test fun fullTankLeavesTheModelFree() =
        assertEquals(all, ModelPilot.fuelFocus(obs(95, Sighting("depot", 6.0, -3.0)), all))

    @Test fun unreachableDepotLeavesTheModelFree() =
        assertEquals(all, ModelPilot.fuelFocus(obs(30, Sighting("depot", 1.0, -6.0)), all))

    private fun armed(threat: Sighting) = obs(95, null).copy(threats = listOf(threat))

    @Test fun enemyInLineMeansShoot() =
        assertEquals(listOf(Action.LEFT_FIRE, Action.HOLD_FIRE, Action.RIGHT_FIRE), ModelPilot.gates(armed(Sighting("boat", 8.0, 0.5)), all))

    // Changed 2026-09-25: the gun gate now also drops a shot with nothing in line (it only wastes
    // the reload; left to the model it fired on half of all decisions), so each way is offered once.
    @Test fun enemyOffLineLeavesEveryWayWithTheGunQuiet() =
        assertEquals(listOf(Action.LEFT, Action.HOLD, Action.RIGHT), ModelPilot.gates(armed(Sighting("boat", 8.0, 4.0)), all))

    @Test fun aDepotInLineIsNeverShotEvenWithATargetBehindIt() =
        assertEquals(
            listOf(Action.LEFT, Action.HOLD, Action.RIGHT),
            ModelPilot.fireGate(obs(95, Sighting("depot", 5.0, 0.3)).copy(threats = listOf(Sighting("boat", 9.0, 0.5))), all),
        )

    @Test fun theGunGateNeverRemovesAWay() {
        // Firing wanted, but only the quiet move is legal going left: left stays, quiet.
        val legal = listOf(Action.LEFT, Action.HOLD_FIRE, Action.RIGHT_FIRE)
        assertEquals(listOf(Action.LEFT, Action.HOLD_FIRE, Action.RIGHT_FIRE), ModelPilot.fireGate(armed(Sighting("boat", 8.0, 0.5)), legal))
        // Nothing to shoot, but only the firing move is legal straight on: straight stays, firing.
        assertEquals(listOf(Action.LEFT, Action.HOLD_FIRE), ModelPilot.fireGate(obs(95, null), listOf(Action.LEFT, Action.LEFT_FIRE, Action.HOLD_FIRE)))
    }

    @Test fun aReloadingGunNeverFires() =
        assertEquals(listOf(Action.LEFT, Action.HOLD, Action.RIGHT), ModelPilot.gates(armed(Sighting("boat", 8.0, 0.5)).copy(weaponReady = false), all))

    @Test fun lowFuelOutranksATarget() =
        assertEquals(listOf(Action.HOLD), ModelPilot.gates(obs(30, Sighting("depot", 6.0, 0.2)).copy(threats = listOf(Sighting("boat", 8.0, 0.5))), all))
}
