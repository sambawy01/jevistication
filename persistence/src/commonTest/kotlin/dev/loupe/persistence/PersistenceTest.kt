package dev.loupe.persistence

import dev.loupe.engine.Distribution
import dev.loupe.engine.Export
import dev.loupe.engine.Extent
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Probability
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.Truncation
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs on the JVM and the iOS simulator: the same files must read the same everywhere. */
class PersistenceTest {

    private val rows = listOf(
        LedgerRow("j", "h", Distribution.of("yes" to 0.1 + 0.2, "no" to 1 - (0.1 + 0.2)), "yes", Probability.of(1.0), itemId = "/a/b.txt"),
        LedgerRow("j", "h", Distribution.of("a" to 1.0 / 3, "b" to 1.0 / 3, "c" to 1.0 / 3), "__abstain__", Probability.of(0.95), failure = "bad \"json\"\n\u2028", itemId = "/x#2"),
        LedgerRow("j", "h", Distribution.of("yes" to 1.0, "no" to 0.0), "yes", Probability.of(1.0), itemId = "/d", resolvedBy = ResolvedBy.Mechanical("exact-duplicate")),
        LedgerRow(
            "web.flights.fit", "h2", Distribution.of("fits" to 0.62, "does not fit" to 0.38), "__abstain__", Probability.of(1.0),
            itemId = "web:duffel:off_1", resolvedBy = ResolvedBy.Model,
            truncation = Truncation(Extent(3986, 9000, Extent.Measure.CHARACTERS), Extent(760, 1400, Extent.Measure.TOKENS), Extent(40, 52, Extent.Measure.TOKENS)),
        ),
        LedgerRow("k", "h", Distribution.of("yes" to 0.7, "no" to 0.3), "yes", Probability.of(1.0), itemId = "/w", truncation = Truncation.NONE),
    )

    @Test
    fun ledgerRoundTripsLosslesslyThroughTheFile() {
        val dir = newTempDir()
        LedgerStore(dir).appendLedger(rows.take(2))
        LedgerStore(dir).appendLedger(rows.drop(2))
        val loaded = LedgerStore(dir).readLedger()
        assertEquals(0, loaded.unreadableLines)
        assertEquals(rows, loaded.values)
        assertEquals(Export.ledgerToJsonl(rows) + "\n", PlatformFiles.readText(joinPath(dir, "ledger.jsonl")))
    }

    @Test
    fun aTornLastLineIsCountedNotFatal() {
        val dir = newTempDir()
        val store = LedgerStore(dir)
        store.appendLedger(rows)
        PlatformFiles.appendDurably(store.ledgerPath, "{\"judgmentId\":\"j\",\"crit")
        val loaded = LedgerStore(dir).readLedger()
        assertEquals(rows, loaded.values)
        assertEquals(1, loaded.unreadableLines)
    }

    @Test
    fun legacyLinesLoadWithTheirDefaults() {
        val dir = newTempDir()
        PlatformFiles.appendDurably(
            joinPath(dir, "ledger.jsonl"),
            """{"judgmentId":"j","criteriaHash":"h","action":"yes","propensity":1.0,"correction":null,"failure":null,"distribution":{"yes":1.0,"no":0.0}}""" + "\n" +
                """{"judgmentId":"j","itemId":"/b","criteriaHash":"h","action":"__unusable__","propensity":1,"correction":null,"failure":"bad","distribution":{"yes":0.5,"no":0.5}}""" + "\r\n\n",
        )
        val loaded = LedgerStore(dir).readLedger()
        assertEquals(0, loaded.unreadableLines)
        assertEquals(listOf(ResolvedBy.Model, ResolvedBy.Unusable), loaded.values.map { it.resolvedBy })
        assertEquals(listOf(null, "/b"), loaded.values.map { it.itemId })
        assertEquals(listOf(null, null), loaded.values.map { it.truncation })
        assertEquals(listOf(false, false), loaded.values.map { it.truncated })
    }

    @Test
    fun correctionsAppendInTheDesktopsExactBytes() {
        val dir = newTempDir()
        val store = LedgerStore(dir)
        val a = CorrectionRecord("j", "h", "i1", "yes", "2026-09-23T10:00:00Z", confirmed = true)
        val b = CorrectionRecord("j", "h", "i1", null, "2026-09-23T10:01:00Z", confirmed = false)
        store.appendCorrection(a)
        store.appendCorrection(b)
        assertEquals(Loaded(listOf(a, b), 0), LedgerStore(dir).readCorrections())
        assertEquals(
            """{"judgmentId":"j","criteriaHash":"h","itemId":"i1","label":"yes","at":"2026-09-23T10:00:00Z","confirmed":true}""" + "\n" +
                """{"judgmentId":"j","criteriaHash":"h","itemId":"i1","label":null,"at":"2026-09-23T10:01:00Z","confirmed":false}""" + "\n",
            store.correctionsText(),
        )
        assertEquals(emptyMap(), CorrectionCodec.index(listOf(a, b)))
        assertEquals(mapOf(CorrectionKey("j", "h", "i1") to "yes"), CorrectionCodec.index(listOf(a)))
    }

    @Test
    fun everyTemplatesJudgmentRoundTripsThroughTheSavedFile() {
        val dir = newTempDir()
        val judgments = allJudgments()
        LedgerStore(dir).saveJudgments(judgments)
        val loaded = LedgerStore(dir).readJudgments()
        assertEquals(judgments, loaded)
        assertEquals(judgments.map { it.criteriaHash }, loaded.map { it.criteriaHash })
        for (j in judgments) assertEquals(j, JudgmentCodec.decode(JudgmentCodec.encode(j)))
        // Rewritten atomically: saving again replaces, and no temporary file is left behind.
        LedgerStore(dir).saveJudgments(judgments.take(1))
        assertEquals(judgments.take(1), LedgerStore(dir).readJudgments())
        assertEquals(false, PlatformFiles.exists(joinPath(dir, "judgments.json.tmp")))
    }

    @Test
    fun theParserRejectsWhatIsNotJson() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1}x", "\"\\q\"", "01", "tru")) {
            assertFailsWith<IllegalArgumentException>(bad) { JsonValue.parse(bad) }
        }
        assertEquals(JsonValue.Str("a\u00e9\n\""), JsonValue.parse("\"a\\u00e9\\n\\\"\""))
    }

    @Test
    fun exportIsLosslessAndCarriesCorrections() {
        val dir = newTempDir()
        val ledger = PhoneLedger.open(dir)
        ledger.appendAll(rows)
        ledger.appendCorrection(CorrectionRecord("web.flights.fit", "h2", "web:duffel:off_1", "fits", "2026-09-23T10:00:00Z", false))
        val reopened = PhoneLedger.open(dir)
        assertEquals(rows, reopened.rows())
        assertEquals(1, reopened.rowsForJudgment("web.flights.fit").size)
        val stats = reopened.stats()
        assertEquals(LedgerStats(5, 3, 1, 1, 3, 1, 0), stats)

        val out = joinPath(dir, "export")
        val paths = reopened.exportTo(out)
        assertEquals(listOf(DataExport.JUDGMENTS, DataExport.CALIBRATION, DataExport.LEDGER, DataExport.CORRECTIONS), paths.map { it.substringAfterLast('/') })
        val exported = PlatformFiles.readText(joinPath(out, DataExport.LEDGER))!!.lines().filter { it.isNotBlank() }.map(PhoneLedger::parseRow)
        val expected = rows.map { if (it.itemId == "web:duffel:off_1") it.copy(correction = "fits") else it }
        assertEquals(expected, exported)
        assertEquals(reopened.store.correctionsText(), PlatformFiles.readText(joinPath(out, DataExport.CORRECTIONS)))
        val calibration = PlatformFiles.readText(joinPath(out, DataExport.CALIBRATION))!!
        assertTrue("\"judgmentId\":\"web.flights.fit\"" in calibration, calibration)
        assertEquals("[]", PlatformFiles.readText(joinPath(out, DataExport.JUDGMENTS)))
    }

    private fun allJudgments(): List<UserJudgment> =
        TemplateLibrary.ALL.map { t ->
            (t.instantiate("j-${t.id}", t.parameters.associate { it.name to it.example }) as Template.InstantiateResult.Created).judgment
        } + (JudgmentDraft(question = "Which pile", options = listOf("keep", "bin", "not sure")).compile("j-mine") as UserJudgment.EditResult.Edited).judgment
}
