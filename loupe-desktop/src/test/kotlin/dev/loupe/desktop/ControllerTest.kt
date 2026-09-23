package dev.loupe.desktop

import dev.loupe.desktop.core.Analysis
import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
import dev.loupe.engine.Backend
import dev.loupe.engine.Extent
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Scored
import dev.loupe.engine.Policy
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.SelectionReason
import dev.loupe.sources.Scanner
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.TemplateLibrary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stub model with the shape of a real one: confident on clear keyword evidence, unsure in the
 * middle, so the queue, the slider and calibration have something to work with. Not Laya.
 */
internal val stubBackend = Backend.ofMasses { judgment, state ->
    val text = state.text.lowercase()
    val c = judgment.candidates
    if (c.size == 2) {
        val yes = when {
            "total paid" in text || "we charged" in text || "payment received" in text -> 0.93
            "paid" in text || "£" in text -> 0.62
            "invoice" in text -> 0.48
            else -> 0.12
        }
        mapOf(c[0] to yes, c[1] to 1 - yes)
    } else {
        val first = 0.5
        c.mapIndexed { i, label -> label to if (i == 0) first else (1 - first) / (c.size - 1) }.toMap()
    }
}

internal fun controller(home: Path, backend: Backend? = stubBackend, today: LocalDate = LocalDate.of(2026, 9, 23)): LoupeController =
    LoupeController(
        store = Store(home),
        scanner = Scanner(zone = ZoneOffset.UTC),
        today = { today },
        modelLoader = { if (backend == null) ModelState.Unavailable("no model in this test") else ModelState.Ready(backend, "stub", null) },
    )

internal fun LoupeController.awaitModel() {
    val deadline = System.currentTimeMillis() + 10_000
    while (model is ModelState.Loading && System.currentTimeMillis() < deadline) Thread.sleep(5)
}

class ControllerTest {

    @TempDir
    lateinit var tmp: Path

    private val open = mutableListOf<LoupeController>()

    @AfterTest
    fun closeAll() = open.forEach { it.close() }

    private fun started(backend: Backend? = stubBackend): LoupeController = controller(tmp.resolve("home"), backend).also {
        open += it
        runBlocking { it.start().join() }
        it.awaitModel()
    }

    @Test
    fun `loads the sample, runs a template across it, and every judged item gets a ledger row`() {
        val app = started()
        runBlocking { app.loadSampleData().join() }
        assertTrue(app.scan.items.size > 40, "scanned ${app.scan.items.size}")
        assertTrue(app.useTemplate(TemplateLibrary.byId("is-receipt")!!).isEmpty())
        val judgment = app.selectedJudgment!!
        runBlocking { app.sweep(judgment.id)!!.join() }
        val withText = app.scan.items.count { it.hasText }
        val sweep = app.sweep!!
        assertFalse(sweep.running)
        assertEquals(withText, sweep.done)
        assertEquals(app.scan.items.size - withText, sweep.withoutText)
        assertEquals(withText, app.ledger.size)
        assertTrue(app.ledger.all { it.itemId != null && it.criteriaHash == judgment.criteriaHash })
        // A second sweep skips what is already decided under this wording.
        runBlocking { app.sweep(judgment.id)!!.join() }
        assertEquals(0, app.sweep!!.total)
        assertEquals(withText, app.ledger.size)
    }

    @Test
    fun `the queue, corrections, calibration, slider and baseline form one loop`() {
        val app = started()
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        val j = app.selectedJudgment!!
        runBlocking { app.sweep(j.id)!!.join() }

        val queue = Analysis.queue(app.ledger, j, app.correctionIndex)
        assertTrue(queue.isNotEmpty())
        assertTrue(queue.any { it.reason == SelectionReason.AUDIT }, "the random audit arm is present")
        // The most informative entry comes first.
        assertTrue(queue.first().informativeness >= queue.filter { it.reason == SelectionReason.UNCERTAIN }.last().informativeness)

        val before = Analysis.calibration(app.ledger, j, app.correctionIndex)
        assertEquals(0, before.corrections)
        assertNull(before.agreement)
        assertEquals(Analysis.MIN_FOR_RELIABILITY, before.correctionsNeededForReliability)

        // The user answers twelve items: the model's answer on most, a change on some.
        val rows = Analysis.effectiveRows(app.ledger, j, app.correctionIndex).take(12)
        rows.forEachIndexed { i, row ->
            val other = j.shape.candidates.first { it != row.distribution.argmax }
            val truth = if (i % 4 == 0) other else row.distribution.argmax
            app.correct(j.id, row.itemId!!, truth, confirmed = truth == row.distribution.argmax)
        }
        val after = Analysis.calibration(app.ledger, j, app.correctionIndex)
        assertEquals(12, after.corrections)
        assertEquals(9.0 / 12, after.agreement!!, 1e-9)
        assertEquals(Analysis.MIN_FOR_RELIABILITY - 12, after.correctionsNeededForReliability)
        // Corrected items leave the queue.
        val corrected = rows.map { it.itemId }.toSet()
        assertTrue(Analysis.queue(app.ledger, j, app.correctionIndex).none { it.row.itemId in corrected })

        // Undo retracts the latest correction, appending rather than erasing.
        assertTrue(app.undoLastCorrection(j.id))
        assertEquals(11, Analysis.calibration(app.ledger, j, app.correctionIndex).corrections)
        assertEquals(13, app.corrections.size)

        // The slider previews over logged rows, grounded in corrections where they cover the change.
        val preview = Analysis.preview(app.ledger, j, app.correctionIndex, 0.5)
        assertTrue(preview.additionalActions > 0)
        app.setThreshold(j.id, 0.5)
        assertEquals(0.5, app.judgment(j.id)!!.threshold)

        // The baseline runs through Harness on the corrected items, replaying what the model said.
        val comparison = assertNotNull(Analysis.baseline(app.ledger, app.judgment(j.id)!!, app.correctionIndex, app.itemsById))
        assertEquals(11, comparison.report.n)
        assertTrue(comparison.baselineDescription.contains("receipt"))
    }

    @Test
    fun `judgments, the ledger and corrections survive a restart`() {
        val home = tmp.resolve("home")
        val first = controller(home).also { open += it }
        runBlocking { first.start().join(); first.loadSampleData().join() }
        first.awaitModel()
        first.useTemplate(TemplateLibrary.byId("reply-from-sender")!!, mapOf("sender" to "priya@example.org"))
        val j = first.selectedJudgment!!
        runBlocking { first.sweep(j.id)!!.join() }
        val row = first.ledger.first()
        first.correct(j.id, row.itemId!!, j.shape.candidates[1])
        first.setThreshold(j.id, 0.7)
        first.close()
        open.remove(first)

        val second = controller(home).also { open += it }
        runBlocking { second.start().join() }
        assertEquals(listOf(j.copy(threshold = 0.7)), second.judgments)
        assertEquals(first.ledger, second.ledger)
        assertEquals(first.corrections, second.corrections)
        assertEquals(first.sources, second.sources)
        assertEquals(j.shape.candidates[1], Analysis.effectiveRows(second.ledger, second.judgments.single(), second.correctionIndex).first { it.itemId == row.itemId }.correction)
        assertEquals(0, second.loadReport!!.unreadableLines)
    }

    @Test
    fun `rewording a judgment restarts its calibration and keeps the old rows`() {
        val app = started()
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        val j = app.selectedJudgment!!
        runBlocking { app.sweep(j.id)!!.join() }
        val decided = app.ledger.size
        assertTrue(app.reword(j.id, "Is this proof that money was paid?", null, j.title, j.invariant, j.breaks, j.lookalikes).isEmpty())
        val reworded = app.judgment(j.id)!!
        assertNotEquals(j.criteriaHash, reworded.criteriaHash)
        val summary = Analysis.calibration(app.ledger, reworded, app.correctionIndex)
        assertEquals(0, summary.decisions)
        assertEquals(decided, summary.earlierWording)
        // The lint still applies to an edit.
        assertTrue(app.reword(j.id, "Explain why this is a receipt?", null, j.title, j.invariant, j.breaks, j.lookalikes).isNotEmpty())
    }

    @Test
    fun `a sweep can be cancelled, keeps what ran, and never blocks the caller`() {
        val slow = Backend.ofMasses { judgment, state ->
            Thread.sleep(40)
            stubBackend.score(judgment, state).masses
        }
        val app = started(slow)
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        val t0 = System.nanoTime()
        val job = app.sweep(app.selectedJudgmentId!!)!!
        assertTrue((System.nanoTime() - t0) / 1e6 < 200, "starting a sweep must return at once")
        Thread.sleep(300)
        app.cancelSweep()
        runBlocking { job.join() }
        val state = app.sweep!!
        assertTrue(state.cancelled)
        assertFalse(state.running)
        assertTrue(state.done in 1 until state.total, "done ${state.done} of ${state.total}")
        assertEquals(state.done, app.ledger.size)
        assertEquals(state.done, Store(tmp.resolve("home")).loadLedger().first.size, "what ran is on disk")
    }

    @Test
    fun `without the model, model actions are refused with a reason and mechanical ones still work`() {
        val app = started(backend = null)
        assertTrue(app.model is ModelState.Unavailable)
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        assertNull(app.sweep(app.selectedJudgmentId!!))
        assertTrue(app.notice!!.contains("not loaded"))
        runBlocking { app.runWatchers().join() }
        val report = app.watcherReport!!
        assertNull(report.expiryAlerts, "the model half does not run without the model")
        assertTrue(report.expiryCandidates.isNotEmpty())
        assertTrue(report.fraud.isNotEmpty())
    }

    @Test
    fun `the duplicate template answers exact copies mechanically, without the model`() {
        var calls = 0
        val counting = Backend.ofMasses { j, s -> calls++; stubBackend.score(j, s).masses }
        val app = started(counting)
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-duplicate")!!)
        runBlocking { app.sweep(app.selectedJudgmentId!!)!!.join() }
        assertEquals(1, app.sweep!!.mechanical)
        assertEquals(app.sweep!!.done - 1, calls)
        val j = app.selectedJudgment!!
        val copy = app.scan.items.single { it.duplicateOf != null }
        val view = Analysis.views(Analysis.effectiveRows(app.ledger, j, app.correctionIndex), j, app.itemsById).single { it.item?.id == copy.id }
        assertTrue(view.mechanical)
        assertEquals("exact-duplicate", view.mechanicalCheck)
        assertEquals(ResolvedBy.Mechanical("exact-duplicate"), view.row.resolvedBy)
        assertEquals(j.positiveLabel, view.status)
        assertEquals(1, app.ledger.count { it.isMechanical })
        assertTrue(app.ledger.filterNot { it.isMechanical }.all { it.resolvedBy == ResolvedBy.Model })

        // Correcting the mechanical row counts as a decision but not as a model correction.
        app.correct(j.id, copy.id, j.positiveLabel!!, confirmed = true)
        val summary = Analysis.calibration(app.ledger, j, app.correctionIndex)
        assertEquals(1, summary.mechanical)
        assertEquals(0, summary.corrections)

        // The export carries the resolver losslessly.
        app.export(tmp.resolve("export"))
        val parsed = Files.readAllLines(tmp.resolve("export/loupe-ledger.jsonl")).filter { it.isNotBlank() }.map(Store::parseRow)
        assertEquals(app.ledger.map { it.resolvedBy }, parsed.map { it.resolvedBy })
    }

    @Test
    fun `a cut input is marked on its decision view, never acted on, and exported`() {
        // A model that reads only half of every other item, and says so. Masses are confident, so
        // every item would act if it had been read whole.
        val half = Backend { j, s ->
            val n = s.text.length
            val cut = n > 1 && Math.floorMod(s.items.single().id.hashCode(), 2) == 0
            Scored(mapOf(j.candidates[0] to 0.95, j.candidates[1] to 0.05), if (cut) Extent(n / 2, n, Extent.Measure.TOKENS) else null)
        }
        val app = started(half)
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        runBlocking { app.sweep(app.selectedJudgmentId!!)!!.join() }
        val j = app.selectedJudgment!!
        assertEquals(FailurePosture.NULL_ACTION, j.onFailure)
        val views = Analysis.views(Analysis.effectiveRows(app.ledger, j, app.correctionIndex), j, app.itemsById)
        val (cut, whole) = views.filter { !it.mechanical && !it.unusable }.partition { it.row.truncated }
        assertTrue(cut.isNotEmpty() && whole.isNotEmpty(), "sample data should have both")
        for (v in cut) {
            assertFalse(v.acted, "a cut input must not act under NULL_ACTION")
            assertEquals(
                "Input was cut: read first ${v.row.truncation!!.modelContext!!.kept} of ${v.row.truncation!!.modelContext!!.total} tokens (model context).",
                v.inputCutNote,
            )
        }
        whole.forEach { assertNull(it.inputCutNote); assertTrue(it.acted, "an uncut input is decided as before") }

        app.export(tmp.resolve("export"))
        val parsed = Files.readAllLines(tmp.resolve("export/loupe-ledger.jsonl")).filter { it.isNotBlank() }.map(Store::parseRow)
        assertEquals(app.ledger.map { it.truncation }, parsed.map { it.truncation })
    }

    @Test
    fun `write your own goes through the lint and becomes a runnable judgment`() {
        val app = started()
        assertTrue(app.createFromDraft(JudgmentDraft(question = "Rate this from 1 to 10")).isNotEmpty())
        assertTrue(app.createFromDraft(JudgmentDraft(title = "From my landlord", question = "Is this from my landlord?", baselineKeywords = listOf("landlord"))).isEmpty())
        assertEquals("From my landlord", app.selectedJudgment!!.title)
    }

    @Test
    fun `export writes judgments, calibration, a lossless ledger and corrections`() {
        val app = started()
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        val j = app.selectedJudgment!!
        runBlocking { app.sweep(j.id)!!.join() }
        val row = app.ledger.first()
        app.correct(j.id, row.itemId!!, j.positiveLabel!!)
        val files = app.export(tmp.resolve("export"))
        assertEquals(4, files.size)
        assertTrue(files.all { Files.exists(it) })
        val lines = Files.readAllLines(tmp.resolve("export/loupe-ledger.jsonl")).filter { it.isNotBlank() }
        assertEquals(app.ledger.size, lines.size)
        val parsed = lines.map(Store::parseRow)
        assertEquals(app.ledger.map { it.distribution }, parsed.map { it.distribution }, "distributions round-trip exactly")
        assertEquals(j.positiveLabel, parsed.first { it.itemId == row.itemId }.correction)
        assertTrue(Files.readString(tmp.resolve("export/loupe-judgments.json")).contains("\"invariant\""))
        assertTrue(parsed.none { it.action == Policy.UNUSABLE })
    }

    @Test
    fun `criteria in the prompt is off by default, opt-in per judgment, and compared on corrected items`() {
        val app = started()
        runBlocking { app.loadSampleData().join() }
        app.useTemplate(TemplateLibrary.byId("is-receipt")!!)
        val j = app.selectedJudgment!!
        assertFalse(j.criteriaInPrompt)
        assertTrue(j.choice.descriptions.isEmpty(), "default judgments read bare options")
        assertEquals(setOf("a receipt or proof of purchase", "not a receipt"), j.optionCriteria().keys)

        // Nothing corrected yet: the comparison refuses rather than measuring nothing.
        assertNull(app.compareCriteria(j.id))
        runBlocking { app.sweep(j.id)!!.join() }
        Analysis.effectiveRows(app.ledger, j, app.correctionIndex).take(6).forEach { row ->
            app.correct(j.id, row.itemId!!, row.distribution.argmax, confirmed = true)
        }
        runBlocking { app.compareCriteria(j.id)!!.join() }
        val (id, result) = app.criteriaComparison!!
        assertEquals(j.id, id)
        assertEquals(6, result.without.n)
        assertEquals(6, result.with.n)
        assertTrue(result.applicable)

        app.setCriteriaInPrompt(j.id, true)
        val on = app.judgment(j.id)!!
        assertTrue(on.criteriaInPrompt)
        assertEquals(on.optionCriteria(), on.choice.descriptions)
        assertNotEquals(j.criteriaHash, on.criteriaHash, "what the model reads changed, so calibration restarts")
        app.setCriteriaInPrompt(j.id, false)
        assertEquals(j.criteriaHash, app.judgment(j.id)!!.criteriaHash)
    }

    @Test
    fun `the comparison sends descriptions only on the with-criteria arm`() {
        val seen = mutableListOf<Map<String, String>>()
        val recording = Backend { judgment, state -> seen += judgment.descriptions; stubBackend.score(judgment, state) }
        val judgment = (TemplateLibrary.byId("needs-reply")!!.instantiate("nr") as dev.loupe.templates.Template.InstantiateResult.Created).judgment
        val fixtures = listOf(dev.loupe.engine.Fixture(dev.loupe.engine.Item("a", "Can you reply by Friday?"), judgment.shape.candidates[0], "s"))
        val result = Analysis.compareCriteria(recording, judgment, fixtures)!!
        assertEquals(listOf(emptyMap(), judgment.optionCriteria()), seen)
        assertEquals(1, result.with.n)
        // A multi-way pick has no per-option criteria: the comparison says it is not applicable.
        val pick = (TemplateLibrary.byId("receipt-kind")!!.instantiate("rk") as dev.loupe.templates.Template.InstantiateResult.Created).judgment
        assertTrue(pick.optionCriteria().isEmpty())
        assertFalse(Analysis.compareCriteria(stubBackend, pick, listOf(dev.loupe.engine.Fixture(dev.loupe.engine.Item("b", "x"), pick.shape.candidates[0], "s")))!!.applicable)
    }
}
