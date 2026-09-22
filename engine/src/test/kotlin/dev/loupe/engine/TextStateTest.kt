package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextStateTest {

    @Test
    fun `keeps every item verbatim when they fit the budget`() {
        val state = TextState.build(listOf("a" to "hello", "b" to "world"), budget = 100)
        assertTrue(state.isComplete)
        assertEquals(listOf(Fit.VERBATIM, Fit.VERBATIM), state.items.map { it.fit })
        assertEquals("hello\nworld", state.text)
        assertTrue(state.incomplete.isEmpty())
    }

    @Test
    fun `truncates with an explicit marker rather than rewriting`() {
        val original = "0123456789".repeat(10)
        val state = TextState.build(listOf("big" to original), budget = 40)
        val item = state.items.single()

        assertEquals(Fit.TRUNCATED, item.fit)
        assertTrue(item.text.endsWith(TextState.TRUNCATION_MARKER))
        // What survived is a genuine prefix of the original: nothing was reworded.
        val surviving = item.text.removeSuffix(TextState.TRUNCATION_MARKER)
        assertTrue(original.startsWith(surviving), "truncated text must be a prefix of the original")
        assertEquals(40, item.text.length)
        assertTrue(!state.isComplete)
    }

    @Test
    fun `removes an item when there is no room left`() {
        val state = TextState.build(
            listOf("first" to "x".repeat(50), "second" to "y".repeat(50)),
            budget = 50,
        )
        assertEquals(Fit.VERBATIM, state.items[0].fit)
        assertEquals(Fit.REMOVED, state.items[1].fit)
        assertEquals("", state.items[1].text)
    }

    @Test
    fun `never exceeds the budget`() {
        val state = TextState.build(
            listOf("a" to "x".repeat(30), "b" to "y".repeat(30), "c" to "z".repeat(30)),
            budget = 45,
        )
        val used = state.items.sumOf { it.text.length }
        assertTrue(used <= 45, "used $used exceeded budget 45")
    }

    @Test
    fun `a zero budget removes everything`() {
        val state = TextState.build(listOf("a" to "hello"), budget = 0)
        assertEquals(Fit.REMOVED, state.items.single().fit)
        assertEquals("", state.text)
    }

    @Test
    fun `reports which items are incomplete so a judgment can declare its posture`() {
        val state = TextState.build(
            listOf("ok" to "short", "cut" to "x".repeat(100), "gone" to "y".repeat(100)),
            budget = 60,
        )
        assertEquals(listOf("cut", "gone"), state.incomplete.map { it.id })
    }

    @Test
    fun `rejects a negative budget`() {
        assertFailsWith<IllegalArgumentException> { TextState.build(listOf("a" to "b"), -1) }
    }

    @Test
    fun `rejects a duplicate item id`() {
        assertFailsWith<IllegalArgumentException> {
            TextState.build(listOf("a" to "one", "a" to "two"), 100)
        }
    }
}
