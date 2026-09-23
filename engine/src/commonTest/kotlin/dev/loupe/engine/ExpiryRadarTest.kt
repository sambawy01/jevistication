package dev.loupe.engine

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpiryRadarTest {

    private val today = LocalDate(2026, 9, 22)
    private val schengen = ValidityRule("Schengen", monthsRequired = 6)

    private val docType = Judgment.Choice(
        "document-type",
        "What kind of document is this?",
        listOf("passport", "insurance", "none"),
    )

    /** Says "passport" for anything mentioning one, "none" otherwise. */
    private fun engineFor(confidence: Double = 0.95) = DecisionEngine(
        backend = Backend.ofMasses { _, state ->
            if (state.text.contains("PASSPORT")) {
                mapOf("passport" to confidence, "insurance" to (1 - confidence) / 2, "none" to (1 - confidence) / 2)
            } else {
                mapOf("passport" to 0.02, "insurance" to 0.03, "none" to 0.95)
            }
        },
        threshold = Probability.of(0.8),
    )

    @Test
    fun `raises an alert for a passport that fails the six-month rule`() {
        val items = listOf(Item("p1", "PASSPORT expires 2027-01-22"))
        val alerts = ExpiryRadar.scan(items, docType, engineFor(), schengen, today)

        assertEquals(1, alerts.size)
        val alert = alerts.single()
        assertEquals("passport", alert.documentType)
        assertEquals(LocalDate(2027, 1, 22), alert.expiry)
        assertEquals(122, alert.daysRemaining)
        assertEquals("Schengen", alert.rule.name)
    }

    @Test
    fun `stays silent when the document has enough validity left`() {
        val items = listOf(Item("p1", "PASSPORT expires 2030-01-22"))
        assertTrue(ExpiryRadar.scan(items, docType, engineFor(), schengen, today).isEmpty())
    }

    @Test
    fun `takes the latest date as the expiry - not the issue date`() {
        val items = listOf(Item("p1", "PASSPORT issued 2017-01-20, expires 2027-01-20"))
        val alert = ExpiryRadar.scan(items, docType, engineFor(), schengen, today).single()
        assertEquals(LocalDate(2027, 1, 20), alert.expiry)
    }

    @Test
    fun `takes the earlier reading of an ambiguous date and says it was ambiguous`() {
        // 03/04/2027 is either 3 April or 4 March; for an expiry the earlier one is the safe error.
        val items = listOf(Item("p1", "PASSPORT valid until 03/04/2027"))
        val alert = ExpiryRadar.scan(items, docType, engineFor(), schengen, today).single()
        assertEquals(LocalDate(2027, 3, 4), alert.expiry)
        assertTrue(alert.dateWasAmbiguous)
    }

    @Test
    fun `an uncertain document type raises nothing`() {
        // Below the threshold the engine abstains, and an uncertain type is no basis for an alarm.
        val items = listOf(Item("p1", "PASSPORT expires 2027-01-22"))
        val timid = engineFor(confidence = 0.5)
        assertTrue(ExpiryRadar.scan(items, docType, timid, schengen, today).isEmpty())
    }

    @Test
    fun `ignores documents that are not an expiring type`() {
        val items = listOf(Item("x1", "a shopping list, dated 2027-01-22"))
        assertTrue(ExpiryRadar.scan(items, docType, engineFor(), schengen, today).isEmpty())
    }

    @Test
    fun `ignores an expiring document with no date on it`() {
        val items = listOf(Item("p1", "PASSPORT, no date printed"))
        assertTrue(ExpiryRadar.scan(items, docType, engineFor(), schengen, today).isEmpty())
    }

    @Test
    fun `sorts the most urgent first`() {
        val items = listOf(
            Item("later", "PASSPORT expires 2027-02-20"),
            Item("sooner", "PASSPORT expires 2026-10-20"),
        )
        val alerts = ExpiryRadar.scan(items, docType, engineFor(), schengen, today)
        assertEquals(listOf("sooner", "later"), alerts.map { it.itemId })
    }

    @Test
    fun `rejects a negative validity rule`() {
        assertFailsWith<IllegalArgumentException> { ValidityRule("bad", -1) }
    }
}
