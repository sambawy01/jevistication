package dev.loupe.kit.measure

import dev.loupe.engine.Backend
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Scored
import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.kit.judgments.JudgmentResults
import dev.loupe.kit.judgments.JudgmentSweep
import dev.loupe.kit.judgments.SweepObserver
import dev.loupe.kit.judgments.SweepProgress
import dev.loupe.kit.settings.EngineSettingsStore
import dev.loupe.kit.settings.Features
import dev.loupe.templates.TransactionEvidence
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The 2026-09-28 launch hang: the Unsure queue re-ran the evidence gate over every ledger row's full
 * text on each draw. Over 3,000 model answers on 20–50 KB texts (logged as if before the gate, so the
 * queue must re-check every one — the owner's case), a draw is quick, a second draw with the memo reads
 * no text again, and the memo changes nothing in what is drawn.
 */
class UnsureQueueSpeedTest {
    private val backend = Backend { j, state ->
        val p = 0.5 + (state.text.length % 45) / 100.0
        Scored(mapOf(j.candidates[0] to p, j.candidates[1] to 1 - p))
    }

    private fun receipt(): UserJudgment = (JudgmentBook.fromTemplate("is-receipt", emptyMap(), emptyList()) as BookResult.Created).judgment

    /** Every item answered by the model: `rules_first` off keeps the gate out of the run. */
    private fun modelRows(j: UserJudgment, items: List<dev.loupe.sources.common.SourceItem>): List<LedgerRow> {
        val store = EngineSettingsStore(directory = null)
        store.setBool("global.rules_first", false)
        store.setBool("global.use_calibration", false)
        val sink = mutableListOf<LedgerRow>()
        JudgmentSweep(backend).runWith(j, JudgmentResults.plan(emptyList(), j, items, false), object : SweepObserver {
            override fun onSweepProgress(progress: SweepProgress) {}
            override fun onSweepRows(rows: List<LedgerRow>) { sink += rows }
            override fun isCancelled() = false
        }, autoBaseline = false, policy = store.current.policy(Features.JUDGMENTS))
        return sink
    }

    @Test
    fun aDrawOverThreeThousandLongRowsIsQuickAndTheMemoChangesNothing() {
        val items = LargeLedgerFixture.items(3_000)
        assertTrue(items.all { it.text.length in 20_000..55_000 })
        val j = receipt()
        val rows = modelRows(j, items)
        assertEquals(3_000, rows.count { !it.isMechanical }, "every item is a model answer the gate must re-check")

        val plain = JudgmentMeasure.queue(rows, listOf(j), emptyMap(), items)
        val memo = EvidenceMemo()
        lateinit var cold: List<UnsureEntry>
        lateinit var warm: List<UnsureEntry>
        val coldTime = time { cold = JudgmentMeasure.queue(rows, listOf(j), emptyMap(), items, memo = memo) }
        val readCold = memo.assessed
        val warmTime = time { warm = JudgmentMeasure.queue(rows, listOf(j), emptyMap(), items, memo = memo) }
        println("QUEUE-SPEED 3000 rows cold=${coldTime.inWholeMilliseconds}ms warm=${warmTime.inWholeMilliseconds}ms texts-read=$readCold")

        fun keys(q: List<UnsureEntry>) = q.map { "${it.itemId}|${it.reason}|${it.informativeness}" }
        assertEquals(keys(plain), keys(cold), "the memo changes nothing")
        assertEquals(keys(plain), keys(warm))
        assertEquals(JudgmentMeasure.QUEUE_SIZE, plain.size)
        // Receipts and bare totals stay; minutes and shop listings are answered "no" by the gate.
        assertTrue(plain.all { it.item.text.startsWith("John Lewis") || it.item.text.startsWith("Cafe Luna") })
        assertEquals(3_000, readCold, "each text read once")
        assertEquals(readCold, memo.assessed, "a second draw reads no text")
        assertTrue(coldTime.inWholeMilliseconds < 3_000, "a cold draw took $coldTime")
        assertTrue(warmTime.inWholeMilliseconds < 1_000, "a warm draw took $warmTime")
    }

    @Test
    fun theGateInTheQueueReadsTheModelsBudget() {
        val j = receipt()
        val filler = "Minutes of the residents' meeting. Present: A, B, C. ".repeat(100)
        val late = LargeLedgerFixture.items(1).single().copy(id = "late", contentHash = "late", text = filler + "Receipt No. 8841-2210")
        val rows = modelRows(j, listOf(late))
        // Past the default budget the evidence is unseen, as it is by the model: answered by rule, not queued.
        assertTrue(JudgmentMeasure.queue(rows, listOf(j), emptyMap(), listOf(late)).isEmpty())
        // Under a bigger `text_chars` it is read, and the item is a question again.
        assertEquals(1, JudgmentMeasure.queue(rows, listOf(j), emptyMap(), listOf(late), textChars = filler.length + 40).size)
        assertTrue(filler.length > TransactionEvidence.SCAN_CHARS)
    }

    private inline fun time(block: () -> Unit): Duration {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow()
    }
}
