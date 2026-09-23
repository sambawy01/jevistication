package dev.loupe.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateTextTest {

    /** Every observation a baseline run produces, over several seeds. */
    private fun observations(): List<Observation> {
        val seen = mutableListOf<Observation>()
        for (seed in 1L..5L) {
            val session = GameSession(seed, Control.Piloted(LockstepDecider(BaselinePilot(), 3)))
            while (!session.world.over && session.world.tick < 4_000) {
                if (session.world.tick % 6 == 0L) seen += Observation.of(session.world, Mechanics.legalActions(session.world))
                session.tick()
            }
        }
        return seen
    }

    @Test
    fun `the text state stays inside its budget in every state a run produces`() {
        val all = observations()
        assertTrue(all.size > 1_000)
        val texts = all.map(StateText::describe)
        val longest = texts.maxBy { it.length }
        assertTrue(longest.length <= StateText.MAX_CHARS, "${longest.length} chars: $longest")
        // A word count is a conservative stand-in for tokens here (the gated test counts real ones):
        // numbers and short English words are one or two tokens each.
        val words = texts.maxOf { it.split(Regex("[\\s.,%]+")).count(String::isNotEmpty) }
        assertTrue(words <= 75, "$words words")
        println("text state: ${texts.size} states, longest ${longest.length} chars, at most $words words")
        println("sample: ${texts[texts.size / 2]}")
    }

    @Test
    fun `the state is relative to the plane and names what matters`() {
        val world = World(1)
        world.clearEntities()
        world.setFuel(20.0)
        world.addEnemy(Enemy(EnemyKind.HELI, world.playerX - 2, world.playerY + 4, 4.5))
        world.addDepot(Depot(world.playerX + 3, world.playerY + 9))
        val text = StateText.describe(Observation.of(world, Mechanics.legalActions(world)))
        assertTrue(text.startsWith("fuel 20% low, gun ready."), text)
        assertTrue("heli 4 ahead 2 left moving right." in text, text)
        assertTrue("fuel depot 9 ahead 3 right." in text, text)
    }

    @Test
    fun `the same state always gives the same text`() {
        val a = World(9).also { repeat(500) { _ -> it.step(Action.HOLD_FIRE) } }
        val b = World(9).also { repeat(500) { _ -> it.step(Action.HOLD_FIRE) } }
        assertEquals(
            StateText.describe(Observation.of(a, Mechanics.legalActions(a))),
            StateText.describe(Observation.of(b, Mechanics.legalActions(b))),
        )
    }
}
