package dev.loupe.kit.settings

import dev.loupe.engine.Backend
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.Scored
import dev.loupe.kit.flights.FlightFacts
import dev.loupe.kit.flights.FlightJudge
import dev.loupe.kit.flights.FlightJudgment
import dev.loupe.kit.flights.FlightLeg
import dev.loupe.kit.flights.FlightPriorities
import dev.loupe.kit.flights.Term
import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.kit.judgments.JudgmentResults
import dev.loupe.kit.judgments.JudgmentSweep
import dev.loupe.kit.judgments.SweepObserver
import dev.loupe.kit.judgments.SweepProgress
import dev.loupe.kit.privacy.PrivacyCheck
import dev.loupe.kit.sweep.CoordinatorObserver
import dev.loupe.kit.sweep.CoordinatorProgress
import dev.loupe.kit.sweep.ModelLane
import dev.loupe.kit.sweep.StopReason
import dev.loupe.kit.sweep.SweepCoordinator
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.MechanicalCheck
import dev.loupe.templates.UserJudgment
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consumers under Model settings: the defaults reproduce what each did before (threshold, text
 * budget, mechanical-first, auto baseline), and `use_laya` off routes to rules and sets the flag
 * the app's banner reads.
 */
class SettingsConsumersTest {
    private fun item(id: String, text: String, duplicateOf: String? = null, kind: ItemKind = ItemKind.TEXT) = SourceItem(
        id = id, sourceId = "sample", kind = kind, path = "/x/$id", messageIndex = null, name = id,
        text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "h-$id",
        mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = duplicateOf,
    )

    /** A yes/no judgment with a keyword baseline and the exact-duplicate rule. */
    private fun receipt(): UserJudgment = (JudgmentBook.fromTemplate("is-receipt", emptyMap(), emptyList()) as BookResult.Created).judgment
        .copy(mechanical = MechanicalCheck.EXACT_DUPLICATE)

    private class Seen {
        var calls = 0
        val budgets = mutableListOf<Int>()
    }

    /** Leans [p] to the first option; records every call and the text budget it was given. */
    private fun backend(seen: Seen, p: Double = 0.95) = Backend { j, state ->
        seen.calls++
        seen.budgets += state.budget
        Scored(mapOf(j.candidates[0] to p, j.candidates[1] to 1 - p))
    }

    private val never = Backend { _, _ -> error("Laya must not be asked") }

    private class Recorder : SweepObserver {
        val rows = mutableListOf<LedgerRow>()
        var last: SweepProgress? = null
        override fun onSweepProgress(progress: SweepProgress) { last = progress }
        override fun onSweepRows(rows: List<LedgerRow>) { this.rows += rows }
        override fun isCancelled(): Boolean = false
    }

    private val items = listOf(
        item("a", "your receipt total paid"),
        item("b", "hello there"),
        item("c", "your receipt total paid", duplicateOf = "a"),
    )

    private fun sweep(j: UserJudgment, b: Backend?, policy: RunPolicy, auto: Boolean = false): Recorder {
        val r = Recorder()
        JudgmentSweep(b).runWith(j, JudgmentResults.planFor(emptyList(), j, items, false, policy.useLaya), r, auto, policy)
        return r
    }

    @Test
    fun sweepDefaultsAreTheOldSweep() {
        val j = receipt()
        val seenOld = Seen()
        val old = Recorder()
        JudgmentSweep(backend(seenOld)).run(j, JudgmentResults.plan(emptyList(), j, items, false), old)
        val seenNew = Seen()
        val new = sweep(j, backend(seenNew), RunPolicy.defaults(Features.JUDGMENTS))
        assertEquals(old.rows.map { it.itemId to it.action }, new.rows.map { it.itemId to it.action })
        assertEquals(old.rows.map { it.resolvedBy }, new.rows.map { it.resolvedBy })
        assertEquals(listOf(4_000, 4_000), seenNew.budgets, "the 4,000-character budget; the duplicate is answered by rule")
        assertEquals(seenOld.calls, seenNew.calls)
        assertFalse(new.last!!.layaOff)
    }

    @Test
    fun sweepReadsThresholdBudgetAndRulesFirst() {
        val j = receipt()   // yes/no: starts at 0.80
        val seen = Seen()
        val policy = RunPolicy.defaults(Features.JUDGMENTS).copy(acceptConfidence = 0.99, textChars = 1_000, rulesFirst = false)
        val r = sweep(j, backend(seen, p = 0.95), policy)
        assertEquals(3, seen.calls, "rules_first off: Laya answers the exact duplicate too")
        assertTrue(seen.budgets.all { it == 1_000 })
        assertTrue(r.rows.all { it.resolvedBy == ResolvedBy.Model })
        assertTrue(r.rows.none { it.action == "yes" || it.action == j.shape.candidates[0] }, "0.95 is below 0.99: every answer is unsure")

        // A threshold set on Measure still wins over accept_confidence.
        val measured = j.copy(threshold = 0.9)
        val r2 = sweep(measured, backend(Seen(), p = 0.95), policy)
        assertTrue(r2.rows.any { it.action == j.shape.candidates[0] })
    }

    @Test
    fun layaOffRoutesToTheBaselineAndSetsTheFlag() {
        val j = receipt()
        assertNotNull(j.baseline)
        val off = RunPolicy.defaults(Features.JUDGMENTS).copy(useLaya = false)
        val r = sweep(j, never, off)
        assertTrue(r.last!!.layaOff)
        assertEquals(3, r.rows.size)
        assertEquals(ResolvedBy.Mechanical(JudgmentSweep.LAYA_OFF_CHECK), r.rows.first { it.itemId == "a" }.resolvedBy)
        assertEquals(ResolvedBy.Mechanical("exact-duplicate"), r.rows.first { it.itemId == "c" }.resolvedBy)
        // No backend at all is fine while Laya is off.
        assertTrue(sweep(j, null, off).last!!.error == null)

        // Without a baseline no rule covers the items: they stay undecided, and are counted.
        val bare = j.copy(baseline = null)
        val r2 = sweep(bare, never, off)
        assertEquals(listOf("c"), r2.rows.map { it.itemId })
        assertEquals(2, r2.last!!.noRule)

        // Back on: the rules' answers are not "decided", so Laya judges those items next run.
        val plan = JudgmentResults.planFor(r.rows, j, items, rerunAll = false, layaOn = true)
        assertEquals(listOf("a", "b"), plan.toJudge.map { it.id })
        assertEquals(0, JudgmentResults.planFor(r.rows, j, items, rerunAll = false, layaOn = false).toJudge.size)
    }

    @Test
    fun coordinatorReadsJudgmentsAndWatchers() {
        val j = receipt()
        val store = EngineSettingsStore(null)
        store.setBool("features.judgments.use_laya", false)
        store.setBool("features.watchers.use_laya", false)
        val observer = object : CoordinatorObserver {
            override fun onCoordinatorProgress(progress: CoordinatorProgress) {}
            override fun onCoordinatorRows(rows: List<LedgerRow>) {}
            override fun stopReason(): StopReason? = null
        }
        val result = SweepCoordinator(null, ModelLane()).runWith(listOf(j), items, emptyList(), "2026-09-24", observer, emptyMap(), store.current)
        assertNull(result.error)
        assertEquals(listOf(Features.JUDGMENTS, Features.WATCHERS), result.layaOff)
        assertTrue(result.watchers!!.layaOff)
        assertNull(result.watchers!!.expiryAlerts)

        val seen = Seen()
        val on = SweepCoordinator(backend(seen), ModelLane()).run(listOf(j), items, emptyList(), "2026-09-24", observer)
        assertEquals(emptyList(), on.layaOff)
        assertTrue(seen.calls > 0)
    }

    @Test
    fun watchersLayaOffNeverAsksTheModel() {
        val docs = listOf(item("p", "Passport. Date of expiry: 2026-11-01. Valid until 2026-11-01."))
        val today = LocalDate.parse("2026-09-24")
        val seen = Seen()
        val on = WatcherRun.run(docs, today, backend(seen, p = 0.9))
        assertNotNull(on.expiryAlerts)
        assertFalse(on.layaOff)
        assertTrue(seen.budgets.all { it == 4_000 })
        val off = WatcherRun.run(docs, today, never, WatcherRun.SIX_MONTHS, RunPolicy.defaults(Features.WATCHERS).copy(useLaya = false))
        assertNull(off.expiryAlerts)
        assertTrue(off.layaOff)
        assertEquals(on.expiryCandidates.size, off.expiryCandidates.size, "the mechanical half still runs")
    }

    @Test
    fun flightJudgeDefaultsAreTheOldJudge() {
        val judgment = (FlightPriorities.compile("cheap") as FlightJudgment.Ready).judgment
        val offer = FlightFacts("o1", "120.00", "EUR", "TAP", listOf(FlightLeg("LIS", "LHR", "08:00", "10:30", 0, 150, "TP1350")), 1, 1, Term.YES, Term.NO)
        val a = Seen()
        val b = Seen()
        val old = FlightJudge(backend(a, p = 0.85)).decide(judgment, offer)
        val new = FlightJudge.forPolicy(backend(b, p = 0.85), RunPolicy.defaults(Features.FLIGHTS)).decide(judgment, offer)
        assertEquals(old.verdict, new.verdict)
        assertEquals(a.budgets, b.budgets)
        assertEquals(listOf(480), b.budgets)
        val strict = FlightJudge.forPolicy(backend(Seen(), p = 0.85), RunPolicy.defaults(Features.FLIGHTS).copy(acceptConfidence = 0.9, textChars = 200))
        assertTrue(strict.decide(judgment, offer).verdict.unsure)
    }

    @Test
    fun privacyReadContentOffChecksNamesOnly() {
        val docs = listOf(item("keys.txt", "anthropic key sk-ant-api03-" + "a".repeat(90)))
        val on = PrivacyCheck.summariseWith(docs, emptySet(), emptyMap(), readContent = true)
        val defaults = PrivacyCheck.summariseToday(docs, emptySet(), emptyMap())
        assertEquals(defaults, on)
        val off = PrivacyCheck.summariseWith(docs, emptySet(), emptyMap(), readContent = false)
        assertTrue(off.findings.none { it.risk == "secret" })
    }
}
