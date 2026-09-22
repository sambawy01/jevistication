package dev.loupe.engine

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateFactsTest {

    @Test
    fun `finds an iso date`() {
        val found = DateFacts.find("Expires 2026-09-22 per the policy.")
        assertEquals(1, found.size)
        assertEquals(LocalDate.of(2026, 9, 22), found.single().date)
        assertEquals("iso", found.single().pattern)
        assertFalse(found.single().ambiguous)
    }

    @Test
    fun `flags a genuinely ambiguous numeric date and reports both readings`() {
        val match = DateFacts.find("valid until 03/04/2026").single()
        assertTrue(match.ambiguous, "03/04/2026 is ambiguous and must say so")
        assertEquals(LocalDate.of(2026, 4, 3), match.date)
        assertEquals(LocalDate.of(2026, 3, 4), match.alternate)
    }

    @Test
    fun `a numeric date with a day above twelve is unambiguous`() {
        val match = DateFacts.find("issued 25/12/2026").single()
        assertFalse(match.ambiguous)
        assertEquals(LocalDate.of(2026, 12, 25), match.date)
        assertNull(match.alternate)
    }

    @Test
    fun `identical readings are not ambiguous`() {
        val match = DateFacts.find("on 05/05/2026").single()
        assertFalse(match.ambiguous)
        assertEquals(LocalDate.of(2026, 5, 5), match.date)
    }

    @Test
    fun `month-first preference flips the reading`() {
        val match = DateFacts.find("03/04/2026", dayFirst = false).single()
        assertEquals(LocalDate.of(2026, 3, 4), match.date)
        assertEquals(LocalDate.of(2026, 4, 3), match.alternate)
        assertTrue(match.ambiguous)
    }

    @Test
    fun `reads textual dates in both orders`() {
        assertEquals(
            LocalDate.of(2026, 9, 22),
            DateFacts.find("expires 22 September 2026").single().date,
        )
        assertEquals(
            LocalDate.of(2026, 9, 22),
            DateFacts.find("expires September 22, 2026").single().date,
        )
        assertEquals(
            LocalDate.of(2026, 1, 3),
            DateFacts.find("dated 3rd Jan 2026").single().date,
        )
    }

    @Test
    fun `skips numbers that do not name a real day`() {
        assertTrue(DateFacts.find("2026-02-30").isEmpty())
        assertTrue(DateFacts.find("45/45/2026").isEmpty())
    }

    @Test
    fun `finds several dates in order of appearance`() {
        val found = DateFacts.find("issued 2020-01-15, expires 2030-01-14")
        assertEquals(
            listOf(LocalDate.of(2020, 1, 15), LocalDate.of(2030, 1, 14)),
            found.map { it.date },
        )
    }

    @Test
    fun `counts days until a date, negative once passed`() {
        val from = LocalDate.of(2026, 9, 22)
        assertEquals(10, DateFacts.daysUntil(LocalDate.of(2026, 10, 2), from))
        assertEquals(-1, DateFacts.daysUntil(LocalDate.of(2026, 9, 21), from))
        assertEquals(0, DateFacts.daysUntil(from, from))
    }

    @Test
    fun `applies a validity rule like Schengen's six months`() {
        val today = LocalDate.of(2026, 9, 22)
        val passportExpiry = LocalDate.of(2027, 1, 22) // four months away
        assertTrue(
            DateFacts.expiresWithin(passportExpiry, today, months = 6),
            "a passport expiring in four months fails a six-month rule",
        )
        assertFalse(DateFacts.expiresWithin(passportExpiry, today, months = 3))
    }

    @Test
    fun `an already-expired date counts as within any window`() {
        val today = LocalDate.of(2026, 9, 22)
        assertTrue(DateFacts.expiresWithin(LocalDate.of(2020, 1, 1), today, months = 6))
    }

    @Test
    fun `rejects a negative window`() {
        assertFailsWith<IllegalArgumentException> {
            DateFacts.expiresWithin(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), -1)
        }
    }
}
