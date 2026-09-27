package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The question linter's absence rule across white space (fix loop 8). Its patterns use the portable
 * space class (Rx.SP: ASCII white space plus NBSP, U+2007, U+2009 and U+202F, the parity B1 decision),
 * so "no logo" with a no-break space is absence phrasing on every platform. At 699761a the rule used
 * `\s`, which is ASCII-only on the JVM, Kotlin/Native and Android alike (measured: docs/BUILD.md,
 * "Parity B1/B2: changed answers"), so these questions were not flagged anywhere. Other Unicode spaces
 * (U+3000, U+2028, U+2000–U+2006, U+200A, U+205F) are outside the class, as before, on every platform.
 */
class JudgmentLintSpacesTest {
    private val phrases = listOf(
        "Is there no{S}logo?", "Does it not{S}have a date?", "لا{S}يوجد توقيع", "من{S}دون توقيع", "men{S}gheir tawkee3", "el tawkee3 mesh{S}mawgood",
    )

    private fun flagged(q: String) = JudgmentLint.absence(q).isNotEmpty()

    @Test
    fun `absence phrasing is flagged across a no-break or narrow no-break space`() {
        for (sep in listOf(" ", "\t", " ", " ", " ", " ")) {
            for (p in phrases) {
                val q = p.replace("{S}", sep)
                assertEquals(true, flagged(q), "U+${sep[0].code.toString(16)}: $q")
            }
        }
    }

    @Test
    fun `Unicode spaces outside the portable class do not join the words`() {
        for (sep in listOf("　", " ", " ", " ", " ")) {
            for (p in phrases) {
                val q = p.replace("{S}", sep)
                assertEquals(false, flagged(q), "U+${sep[0].code.toString(16)}: $q")
            }
        }
    }

    @Test
    fun `presence questions stay clean across a no-break space`() {
        for (q in listOf("Does it show a logo?", "هل يوجد توقيع؟", "Is there a due date?", "fih tawkee3?")) {
            assertEquals(false, flagged(q), q)
        }
    }
}
