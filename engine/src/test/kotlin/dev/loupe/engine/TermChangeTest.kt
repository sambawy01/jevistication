package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermChangeTest {

    @Test
    fun `catches a renewal premium rising`() {
        val last = "Your annual premium: £450.00\nExcess: £250.00"
        val now = "Your annual premium: £553.50\nExcess: £250.00"

        val comparison = TermChangeDetector.compare(last, now)
        assertEquals(1, comparison.changed.size)

        val change = comparison.changed.single()
        assertEquals("annual premium", change.label)
        assertEquals(45000, change.beforeMinor)
        assertEquals(55350, change.afterMinor)
        assertTrue(change.increased)
        assertEquals(23.0, change.percentChange!!, 1e-9)
    }

    @Test
    fun `reports nothing when the terms are unchanged`() {
        val text = "Monthly charge: £12.99"
        assertTrue(!TermChangeDetector.compare(text, text).hasChanges())
    }

    @Test
    fun `reports a decrease as a negative move`() {
        val comparison = TermChangeDetector.compare("Monthly charge: £20.00", "Monthly charge: £15.00")
        val change = comparison.changed.single()
        assertTrue(!change.increased)
        assertEquals(-25.0, change.percentChange!!, 1e-9)
    }

    @Test
    fun `notices a term appearing and disappearing`() {
        val comparison = TermChangeDetector.compare(
            "Monthly charge: £10.00\nOld fee: £5.00",
            "Monthly charge: £10.00\nService fee: £7.50",
        )
        assertTrue("service fee" in comparison.added)
        assertTrue("old fee" in comparison.removed)
        assertEquals(750, comparison.added.getValue("service fee"))
    }

    @Test
    fun `parses thousands separators and bare amounts`() {
        val amounts = TermChangeDetector.amounts("Total due: £1,234.56\nDeposit: £900")
        assertEquals(123456, amounts.getValue("total due"))
        assertEquals(90000, amounts.getValue("deposit"))
    }

    @Test
    fun `requires a currency marker so reference numbers are not terms`() {
        val amounts = TermChangeDetector.amounts("Policy number 8837261 dated 2026-01-01")
        assertTrue(amounts.isEmpty())
    }

    @Test
    fun `matches a label across a harmless wording change`() {
        // "Your monthly premium" and "Monthly premium" agree on the last two words.
        val comparison = TermChangeDetector.compare(
            "Your monthly premium: £30.00",
            "Monthly premium: £36.00",
        )
        assertEquals("monthly premium", comparison.changed.single().label)
    }

    @Test
    fun `percent is undefined when the previous amount was zero`() {
        assertNull(TermChange("fee", 0, 500).percentChange)
    }

    @Test
    fun `ranks the biggest proportional move first`() {
        val comparison = TermChangeDetector.compare(
            "Admin fee: £10.00\nAnnual premium: £1000.00",
            "Admin fee: £20.00\nAnnual premium: £1100.00",
        )
        assertEquals(listOf("admin fee", "annual premium"), comparison.changed.map { it.label })
    }

    @Test
    fun `handles euro and dollar amounts`() {
        val amounts = TermChangeDetector.amounts("Monthly total: €49.99 and Setup cost: $19.00")
        assertEquals(4999, amounts.getValue("monthly total"))
        assertEquals(1900, amounts.getValue("setup cost"))
    }
}
