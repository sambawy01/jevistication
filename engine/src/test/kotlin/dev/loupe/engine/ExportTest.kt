package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    private fun row(yes: Double, action: String, correction: String? = null) = LedgerRow(
        judgmentId = receipt.id,
        criteriaHash = "abc123",
        distribution = receipt.validate(mapOf("yes" to yes, "no" to 1.0 - yes)),
        action = action,
        propensity = Probability.of(1.0),
        correction = correction,
    )

    @Test
    fun `exports a ledger row losslessly, distribution and propensity included`() {
        val jsonl = Export.ledgerToJsonl(listOf(row(0.75, "yes")))
        assertEquals(
            """{"judgmentId":"is-receipt","criteriaHash":"abc123","action":"yes",""" +
                """"propensity":1.0,"correction":null,"failure":null,""" +
                """"distribution":{"yes":0.75,"no":0.25}}""",
            jsonl,
        )
    }

    @Test
    fun `writes one line per row`() {
        val jsonl = Export.ledgerToJsonl(listOf(row(0.6, "yes"), row(0.4, "no")))
        assertEquals(2, jsonl.lines().size)
        assertTrue(jsonl.lines().all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun `an empty ledger exports as empty text`() {
        assertEquals("", Export.ledgerToJsonl(emptyList()))
    }

    @Test
    fun `includes a correction once the user has made one`() {
        val jsonl = Export.ledgerToJsonl(listOf(row(0.9, "yes", correction = "no")))
        assertTrue(jsonl.contains(""""correction":"no""""))
    }

    @Test
    fun `escapes quotes, backslashes and newlines`() {
        assertEquals("""ab\"cd""".let { "\"ab\\\"cd\"" }, Json.string("""ab"cd"""))
        assertEquals("\"a\\\\b\"", Json.string("""a\b"""))
        assertEquals("\"line1\\nline2\"", Json.string("line1\nline2"))
        assertEquals("\"tab\\there\"", Json.string("tab\there"))
    }

    @Test
    fun `escapes control characters as unicode`() {
        assertEquals("\"\\u0001\"", Json.string("\u0001"))
        assertEquals("\"\\u001f\"", Json.string("\u001F"))
    }

    @Test
    fun `leaves ordinary unicode text intact`() {
        assertEquals("\"caf\u00e9 \u2014 r\u00e9sum\u00e9\"", Json.string("caf\u00e9 \u2014 r\u00e9sum\u00e9"))
    }

    @Test
    fun `exports the judgment library with its three-part template`() {
        val json = Export.judgmentsToJson(listOf(BuiltInJudgments.RECEIPT))
        assertTrue(json.startsWith("[{") && json.endsWith("}]"))
        assertTrue(json.contains(""""id":"is-receipt""""))
        assertTrue(json.contains(""""invariant":"""))
        assertTrue(json.contains(""""breaks":"""))
        assertTrue(json.contains(""""lookalikes":"""))
        assertTrue(json.contains(""""candidates":["yes","no"]"""))
    }

    @Test
    fun `exports every built-in judgment`() {
        val json = Export.judgmentsToJson(BuiltInJudgments.ALL)
        for (definition in BuiltInJudgments.ALL) {
            assertTrue(json.contains(""""id":"${definition.judgment.id}""""))
        }
    }

    @Test
    fun `exports calibration with nulls where nothing is measured yet`() {
        val views = VisibleCalibration.perJudgment(listOf(row(0.9, "yes")))
        val json = Export.calibrationToJson(views)
        assertTrue(json.contains(""""agreement":null"""))
        assertTrue(json.contains(""""ece":null"""))
        assertTrue(json.contains(""""corrections":0"""))
    }

    @Test
    fun `exports measured calibration with its reliability bins`() {
        val views = VisibleCalibration.perJudgment(
            listOf(row(0.9, "yes", correction = "yes"), row(0.9, "yes", correction = "no")),
        )
        val json = Export.calibrationToJson(views)
        assertTrue(json.contains(""""agreement":0.5"""))
        assertTrue(json.contains(""""reliability":["""))
        assertTrue(json.contains(""""meanConfidence":"""))
    }

    @Test
    fun `an empty export is a valid empty array`() {
        assertEquals("[]", Export.judgmentsToJson(emptyList()))
        assertEquals("[]", Export.calibrationToJson(emptyList()))
    }
}
