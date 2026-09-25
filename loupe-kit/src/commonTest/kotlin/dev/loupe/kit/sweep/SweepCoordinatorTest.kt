package dev.loupe.kit.sweep

import dev.loupe.engine.Backend
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Scored
import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SweepCoordinatorTest {
    private fun item(id: String, text: String, hasText: Boolean = true) = SourceItem(
        id = id, sourceId = "sample", kind = ItemKind.TEXT, path = "/x/$id", messageIndex = null, name = id,
        text = text, hasText = hasText, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "h-$id",
        mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = null,
    )

    private fun judgment(template: String, existing: List<UserJudgment> = emptyList()): UserJudgment =
        (JudgmentBook.fromTemplate(template, emptyMap(), existing) as BookResult.Created).judgment

    /** Sure of "receipt" items, torn (0.55) on the rest; [onCall] runs before each answer. */
    private class Fake(val onCall: (Int) -> Unit = {}) : Backend {
        var calls = 0
        override fun score(judgment: dev.loupe.engine.Judgment.Choice, state: dev.loupe.engine.TextState): Scored {
            calls++
            onCall(calls)
            val first = if ("receipt" in state.text) 0.97 else 0.55
            return Scored(mapOf(judgment.candidates[0] to first, judgment.candidates[1] to 1 - first))
        }
    }

    private class Host(var stop: StopReason? = null, val stopAfterRows: Int = Int.MAX_VALUE) : CoordinatorObserver {
        val rows = mutableListOf<LedgerRow>()
        val progress = mutableListOf<CoordinatorProgress>()
        override fun onCoordinatorProgress(progress: CoordinatorProgress) { this.progress += progress }
        override fun onCoordinatorRows(rows: List<LedgerRow>) { this.rows += rows }
        override fun stopReason(): StopReason? = stop ?: if ((progress.lastOrNull()?.done ?: 0) >= stopAfterRows) StopReason.EXPIRED else null
    }

    private val items = (1..20).map { item("i$it", if (it % 4 == 0) "a receipt for £$it" else "note number $it about a payment") } +
        item("img", "", hasText = false)

    private fun twoJudgments(): List<UserJudgment> {
        val a = judgment("is-receipt")
        return listOf(a, judgment("tax-receipt", listOf(a)))
    }

    @Test
    fun sweepsEveryJudgmentOverEveryItemThenTheWatchers() {
        val js = twoJudgments()
        val backend = Fake()
        val host = Host()
        val r = SweepCoordinator(backend, ModelLane()).run(js, items, emptyList(), "2026-09-23", host)
        assertTrue(r.finished)
        assertNull(r.error)
        assertEquals(40, r.progress.total, "20 items with text × 2 judgments; the image is never sent")
        assertEquals(40, r.progress.done)
        assertEquals(40, host.rows.size)
        assertEquals(40, r.summary.sorted)
        assertEquals(30, r.summary.needYou, "the 0.55 answers are below threshold and wait for the user")
        assertNotNull(r.watchers)
        assertFalse(host.progress.last().running)
        assertTrue(host.progress.any { it.watching })
        assertNotNull(r.progress.medianMillis)
        assertEquals("40 sorted, 30 need you", r.summary.line)
    }

    @Test
    fun skipsItemsAlreadyJudgedUnderTheCurrentWording() {
        val js = twoJudgments()
        val first = Host()
        SweepCoordinator(Fake(), ModelLane()).run(js, items, emptyList(), "2026-09-23", first)
        val backend = Fake()
        val again = Host()
        val r = SweepCoordinator(backend, ModelLane()).run(js, items, first.rows, "2026-09-23", again)
        assertTrue(r.finished)
        assertEquals(0, r.progress.total)
        assertEquals(40, r.summary.alreadyDecided)
        assertEquals(0, again.rows.size)

        // Reworded: the criteria hash changes, so every item is judged again for that judgment only.
        val reworded = js[0].copy(criteriaInPrompt = !js[0].criteriaInPrompt)
        val third = Host()
        val r3 = SweepCoordinator(Fake(), ModelLane()).run(listOf(reworded, js[1]), items, first.rows, "2026-09-23", third)
        assertTrue(reworded.criteriaHash != js[0].criteriaHash)
        assertEquals(20, r3.progress.total)
        assertTrue(third.rows.all { it.judgmentId == reworded.id })
    }

    @Test
    fun cancelThenResumeJudgesEveryItemExactlyOnce() {
        val js = twoJudgments()
        val host = Host(stopAfterRows = 25)
        val r = SweepCoordinator(Fake(), ModelLane()).run(js, items, emptyList(), "2026-09-23", host)
        assertEquals(StopReason.EXPIRED, r.stopped)
        assertNull(r.watchers, "the watchers run only after every sweep finished")
        assertEquals(25, host.rows.size, "every decided row is handed over before the stop returns")

        val cancelled = Host(stop = StopReason.CANCELLED)
        val c = SweepCoordinator(Fake(), ModelLane()).run(js, items, host.rows, "2026-09-23", cancelled)
        assertEquals(StopReason.CANCELLED, c.stopped)
        assertEquals(0, cancelled.rows.size)

        val resumed = Host()
        val r2 = SweepCoordinator(Fake(), ModelLane()).run(js, items, host.rows, "2026-09-23", resumed)
        assertTrue(r2.finished)
        assertEquals(15, r2.progress.total)
        val all = host.rows + resumed.rows
        assertEquals(40, all.size)
        assertEquals(40, all.map { it.judgmentId to it.itemId }.toSet().size, "no item judged twice")
    }

    @Test
    fun foregroundClaimPreemptsASweepAndItResumesWhenReleased() {
        val js = twoJudgments()
        val lane = ModelLane()
        var claim: ModelClaim? = null
        var idle = 0
        lane.onIdle { idle++ }
        // Foreground work (a flight ranking, say) starts while the sweep is on its 7th call.
        val backend = Fake { n -> if (n == 7) claim = lane.claim(ModelPriority.FOREGROUND) }
        val host = Host()
        val r = SweepCoordinator(backend, lane).run(js, items, emptyList(), "2026-09-23", host)
        assertEquals(StopReason.PREEMPTED, r.stopped)
        assertEquals(7, backend.calls, "the sweep yields after the item in flight, never mid-item")
        assertEquals(7, host.rows.size)
        assertTrue(lane.shouldYield(ModelPriority.SWEEP))
        assertFalse(lane.shouldYield(ModelPriority.FOREGROUND))

        // Still claimed: a new run stops before its first item.
        val blocked = Host()
        val b = SweepCoordinator(Fake(), lane).run(js, items, host.rows, "2026-09-23", blocked)
        assertEquals(StopReason.PREEMPTED, b.stopped)
        assertEquals(0, blocked.rows.size)

        claim!!.release()
        claim!!.release()
        assertEquals(1, idle, "idle fires once when the last claim above a sweep goes")
        assertTrue(lane.isFree())
        val rest = Host()
        val done = SweepCoordinator(Fake(), lane).run(js, items, host.rows, "2026-09-23", rest)
        assertTrue(done.finished)
        assertEquals(33, rest.rows.size)
    }

    @Test
    fun priorityOrderIsForegroundThenGameThenSweep() {
        val lane = ModelLane()
        val game = lane.claim(ModelPriority.GAME)
        assertTrue(lane.shouldYield(ModelPriority.SWEEP), "sweeps pause for the game")
        assertFalse(lane.shouldYield(ModelPriority.GAME))
        val fg = lane.claim(ModelPriority.FOREGROUND)
        assertTrue(lane.shouldYield(ModelPriority.GAME), "the game yields to foreground work")
        assertEquals(ModelPriority.FOREGROUND, lane.highest())
        fg.release()
        assertEquals(ModelPriority.GAME, lane.highest())
        game.release()
        assertNull(lane.highest())
        // A sweep's own claim never makes another sweep yield.
        val sweep = lane.claim(ModelPriority.SWEEP)
        assertFalse(lane.shouldYield(ModelPriority.SWEEP))
        sweep.release()
    }

    @Test
    fun aFailingJudgmentIsReportedAndTheOthersStillRun() {
        val js = twoJudgments()
        val backend = Backend { j, state ->
            if (j.criteriaHash == js[0].criteriaHash) error("model exploded")
            Scored(mapOf(j.candidates[0] to 0.9, j.candidates[1] to 0.1))
        }
        val host = Host()
        val r = SweepCoordinator(backend, ModelLane()).run(js, items, emptyList(), "2026-09-23", host)
        assertTrue(r.finished)
        assertTrue(host.rows.any { it.judgmentId == js[1].id })
        assertEquals(20, host.rows.count { it.judgmentId == js[1].id })
    }
}
