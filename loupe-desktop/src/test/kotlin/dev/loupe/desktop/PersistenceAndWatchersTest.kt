package dev.loupe.desktop

import dev.loupe.desktop.core.Correction
import dev.loupe.desktop.core.JudgmentCodec
import dev.loupe.desktop.core.Store
import dev.loupe.desktop.core.Watchers
import dev.loupe.engine.Cadence
import dev.loupe.engine.Distribution
import dev.loupe.engine.ImpersonationReason
import dev.loupe.engine.Export
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Extent
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.Truncation
import dev.loupe.engine.Probability
import dev.loupe.sources.SampleData
import dev.loupe.sources.Scanner
import dev.loupe.sources.SourceSpec
import dev.loupe.sources.SourceType
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `every template's judgment round-trips through the saved file exactly`() {
        val store = Store(tmp)
        val judgments = TemplateLibrary.ALL.map { t ->
            (t.instantiate("j-${t.id}", t.parameters.associate { it.name to it.example }) as Template.InstantiateResult.Created).judgment
        } + (JudgmentDraft(question = "Which pile", options = listOf("keep", "bin", "not sure")).compile("j-mine") as UserJudgment.EditResult.Edited).judgment
        store.saveJudgments(judgments)
        val loaded = Store(tmp).loadJudgments()
        assertEquals(judgments, loaded)
        assertEquals(judgments.map { it.criteriaHash }, loaded.map { it.criteriaHash })
        // And the codec itself, one by one.
        for (j in judgments) assertEquals(j, JudgmentCodec.decode(JudgmentCodec.encode(j)))
    }

    @Test
    fun `ledger rows round-trip losslessly and a torn last line is counted, not fatal`() {
        val store = Store(tmp)
        val rows = listOf(
            LedgerRow("j", "h", Distribution.of("yes" to 0.1 + 0.2, "no" to 1 - (0.1 + 0.2)), "yes", Probability.of(1.0), itemId = "/a/b.txt"),
            LedgerRow("j", "h", Distribution.of("a" to 1.0 / 3, "b" to 1.0 / 3, "c" to 1.0 / 3), "__abstain__", Probability.of(0.95), failure = "bad \"json\"\n", itemId = "/x#2"),
        )
        store.appendLedger(rows)
        Files.writeString(tmp.resolve("ledger.jsonl"), "{\"judgmentId\":\"j\",\"crit", StandardOpenOption.APPEND)
        val (loaded, bad) = Store(tmp).loadLedger()
        assertEquals(rows, loaded)
        assertEquals(1, bad)
    }

    @Test
    fun `resolvedBy round-trips through the saved ledger and the export`() {
        val store = Store(tmp)
        val rows = listOf(
            LedgerRow("j", "h", Distribution.of("yes" to 1.0, "no" to 0.0), "yes", Probability.of(1.0), itemId = "/d", resolvedBy = ResolvedBy.Mechanical("exact-duplicate")),
            LedgerRow("j", "h", Distribution.of("yes" to 0.7, "no" to 0.3), "yes", Probability.of(0.9), itemId = "/m", resolvedBy = ResolvedBy.Model),
            LedgerRow("j", "h", Distribution.of("yes" to 0.5, "no" to 0.5), "__unusable__", Probability.of(1.0), failure = "bad", itemId = "/u", resolvedBy = ResolvedBy.Unusable),
        )
        store.appendLedger(rows)
        val (loaded, bad) = Store(tmp).loadLedger()
        assertEquals(rows, loaded)
        assertEquals(0, bad)
        assertEquals(Export.ledgerToJsonl(rows), Export.ledgerToJsonl(loaded))
    }

    @Test
    fun `a ledger line written before resolvedBy existed still loads, as a model or unusable row`() {
        Files.writeString(
            tmp.resolve("ledger.jsonl"),
            """{"judgmentId":"j","itemId":"/a","criteriaHash":"h","action":"yes","propensity":1.0,"correction":null,"failure":null,"distribution":{"yes":1.0,"no":0.0}}""" + "\n" +
                """{"judgmentId":"j","itemId":"/b","criteriaHash":"h","action":"__unusable__","propensity":1.0,"correction":null,"failure":"bad","distribution":{"yes":0.5,"no":0.5}}""" + "\n",
        )
        val (loaded, bad) = Store(tmp).loadLedger()
        assertEquals(0, bad)
        assertEquals(listOf(ResolvedBy.Model, ResolvedBy.Unusable), loaded.map { it.resolvedBy })
    }

    @Test
    fun `truncation round-trips through the saved ledger and the export, and legacy lines load as unknown`() {
        val store = Store(tmp)
        val base = LedgerRow("j", "h", Distribution.of("yes" to 0.7, "no" to 0.3), "yes", Probability.of(1.0), itemId = "/m")
        val rows = listOf(
            base,
            base.copy(itemId = "/w", truncation = Truncation.NONE),
            base.copy(itemId = "/c", action = "__abstain__", truncation = Truncation(Extent(3986, 9000, Extent.Measure.CHARACTERS), Extent(760, 1400, Extent.Measure.TOKENS))),
        )
        store.appendLedger(rows)
        val (loaded, bad) = Store(tmp).loadLedger()
        assertEquals(0, bad)
        assertEquals(rows, loaded)
        assertEquals(Export.ledgerToJsonl(rows), Export.ledgerToJsonl(loaded))
        assertEquals(listOf(null, Truncation.NONE), loaded.take(2).map { it.truncation })
        assertEquals(listOf(false, false, true), loaded.map { it.truncated })
    }

    @Test
    fun `corrections append, and a retraction is a record too`() {
        val store = Store(tmp)
        val a = Correction("j", "h", "i1", "yes", Instant.parse("2026-09-23T10:00:00Z"), confirmed = true)
        val b = Correction("j", "h", "i1", null, Instant.parse("2026-09-23T10:01:00Z"), confirmed = false)
        store.appendCorrection(a)
        store.appendCorrection(b)
        assertEquals(listOf(a, b) to 0, Store(tmp).loadCorrections())
    }

    @Test
    fun `sources round-trip`() {
        val store = Store(tmp)
        val specs = listOf(SourceSpec("src-1", SourceType.FOLDER, tmp.resolve("docs")), SourceSpec("src-2", SourceType.MAIL_EXPORT, tmp.resolve("x.mbox")))
        store.saveSources(specs)
        assertEquals(specs, Store(tmp).loadSources())
    }
}

class WatchersTest {

    @TempDir
    lateinit var tmp: Path

    private val today = LocalDate.of(2026, 9, 23)

    private fun sampleItems() = SampleData.materialize(tmp.resolve("s")).let { root ->
        Scanner(zone = ZoneOffset.UTC).scan(
            listOf(
                SourceSpec("docs", SourceType.FOLDER, root.resolve(SampleData.DOCUMENTS)),
                SourceSpec("mail", SourceType.MAIL_EXPORT, root.resolve(SampleData.MAIL)),
            ),
        ).items
    }

    @Test
    fun `the watchers fire on what the sample dataset hides`() {
        val report = Watchers.run(sampleItems(), today, backend = null)

        // Expiry: the SPECIMEN passport lapses 14 Jan 2027, inside six months of 23 Sep 2026.
        val passport = report.expiryCandidates.single { "passport" in it.item.name }
        assertEquals(LocalDate.of(2027, 1, 14), passport.expiry)
        assertTrue(passport.breachesRule)

        // Recurring money: two monthly subscriptions, four charges each.
        val streamflix = report.recurring.single { it.merchant == "Streamflix" }
        assertEquals(Cadence.MONTHLY, streamflix.cadence)
        assertEquals(4, streamflix.occurrences)
        assertEquals(999, streamflix.typicalAmountMinor)
        assertEquals(Cadence.MONTHLY, report.recurring.single { it.merchant == "CloudBox" }.cadence)

        // Term change: the renewal premium rises 23%, across two PDFs a year apart.
        val premium = report.termChanges.flatMap { it.changes }.single { it.label == "annual premium" }
        assertEquals(45000, premium.beforeMinor)
        assertEquals(55350, premium.afterMinor)
        assertEquals(23.0, premium.percentChange!!, 1e-9)

        // Impersonation: "Mum" writing from an address Mum never uses.
        // CloudBox writing from support@ as well as receipts@ is the same organisation's own domain,
        // which is not reported; "Mum" on another domain is.
        val impostor = report.impersonation.single()
        assertEquals("mum.family@quickmail.example", impostor.item.email!!.fromAddress)
        assertTrue(impostor.signals.any { it.reason == ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS })

        // Site fraud: the fake PayPal sender and its link, and nothing from the genuine senders.
        assertTrue(report.fraud.isNotEmpty())
        assertTrue(report.fraud.all { it.item.email!!.fromAddress == "service@paypa1-secure.example" }, report.fraud.map { it.what }.toString())
        assertTrue(report.fraud.any { it.what.startsWith("link http://paypal.account-verify.example") })
    }

    @Test
    fun `no watcher wording ever blesses`() {
        val report = Watchers.run(sampleItems(), today, backend = null)
        val words = report.fraud.map { it.assessment.summary() }
        assertTrue(words.none { w -> listOf("safe", "legitimate", "trusted").any { it in w.lowercase() } })
    }

    @Test
    fun `a person claims no brand, an institution does`() {
        assertEquals(null, Watchers.claimedBrand("Mum", "mum@family.example"))
        assertEquals("paypal", Watchers.claimedBrand("PayPal Security", "service@paypa1-secure.example"))
        assertEquals("streamflix", Watchers.claimedBrand("Streamflix", "billing@streamflix.example"))
    }
}
