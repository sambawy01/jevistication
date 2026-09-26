package dev.loupe.templates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A baseline pattern the platform's regex engine rejects fails with a clear error, never mid-sweep. */
class BaselinePatternTest {

    @Test
    fun `an invalid pattern fails at definition with a message naming it`() {
        val e = assertFailsWith<IllegalArgumentException> { Baseline.Pattern("(unclosed", "yes", "no", "a bad group") }
        val message = assertNotNull(e.message)
        assertTrue("\"(unclosed\"" in message, message)
        assertTrue("not a valid regular expression" in message, message)
    }

    @Test
    fun `problem reports an invalid pattern and passes a valid one`() {
        assertNotNull(Baseline.Pattern.problem("(unclosed"))
        assertNotNull(Baseline.Pattern.problem("[z-a]"))
        assertNull(Baseline.Pattern.problem("""\?\s*$|\?\s*\n"""))
    }

    @Test
    fun `a valid pattern answers as before and ignores case`() {
        val b = Baseline.Pattern("""\bcopy\b""", "yes", "no", "the word copy")
        assertEquals("yes", b.answer("Report COPY.pdf"))
        assertEquals("no", b.answer("Report copying.pdf"))
        assertEquals(b, b.copy())
    }

    @Test
    fun `every library pattern compiles`() {
        val patterns = TemplateLibrary.ALL.mapNotNull { it.baseline as? Baseline.Pattern }
        assertTrue(patterns.isNotEmpty())
        for (p in patterns) assertNull(Baseline.Pattern.problem(p.pattern), p.pattern)
    }
}
