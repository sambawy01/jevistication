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

    @Test fun enemyOffLineLeavesTheModelFree() =
        assertEquals(all, ModelPilot.gates(armed(Sighting("boat", 8.0, 4.0)), all))

    @Test fun lowFuelOutranksATarget() =
        assertEquals(listOf(Action.HOLD), ModelPilot.gates(obs(30, Sighting("depot", 6.0, 0.2)).copy(threats = listOf(Sighting("boat", 8.0, 0.5))), all))
}
