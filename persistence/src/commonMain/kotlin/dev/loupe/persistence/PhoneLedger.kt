package dev.loupe.persistence

import dev.loupe.engine.LedgerRow
import dev.loupe.engine.ResolvedBy
import dev.loupe.templates.UserJudgment

/** Counts over the ledger, for a one-line summary ("Decisions logged: N"). */
data class LedgerStats(
    /** Every row, model or not. */
    val decisions: Int,
    /** Rows the model's own answer decided — the ones calibration counts. */
    val modelRows: Int,
    val mechanicalRows: Int,
    val unusableRows: Int,
    val judgments: Int,
    val corrections: Int,
    /** Lines in the files that could not be read when the store was opened. */
    val unreadableLines: Int,
)

/**
 * The ledger as the iPhone app uses it: opened once from its Application Support directory, read
 * into memory, appended to durably. A thin, Swift-friendly face over [LedgerStore] — no default
 * arguments, no Kotlin-only types in the signatures that Swift calls.
 *
 * Thread-safe: every call takes the store's lock, so the app can append from a background queue
 * while the Me screen reads the count.
 */
class PhoneLedger private constructor(val store: LedgerStore) {
    private val lock = StoreLock()
    private var rows: List<LedgerRow> = emptyList()
    private var corrections: List<CorrectionRecord> = emptyList()
    private var unreadable: Int = 0

    private fun load() = lock.withLock {
        val ledger = store.readLedger()
        val fixes = store.readCorrections()
        rows = ledger.values
        corrections = fixes.values
        unreadable = ledger.unreadableLines + fixes.unreadableLines
    }

    /** Appends one decision and syncs it to disk before returning. */
    @Throws(Exception::class)
    fun append(row: LedgerRow) = appendAll(listOf(row))

    /** Appends [newRows] in order, as one durable write. Throws (to Swift, an NSError) on I/O failure. */
    @Throws(Exception::class)
    fun appendAll(newRows: List<LedgerRow>) = lock.withLock {
        if (newRows.isEmpty()) return@withLock
        store.appendLedger(newRows)
        rows = rows + newRows
    }

    @Throws(Exception::class)
    fun appendCorrection(correction: CorrectionRecord) = lock.withLock {
        store.appendCorrection(correction)
        corrections = corrections + correction
    }

    /** All rows, in append order. */
    fun rows(): List<LedgerRow> = lock.withLock { rows }

    /** The rows one judgment logged, in append order. */
    fun rowsForJudgment(judgmentId: String): List<LedgerRow> = lock.withLock { rows.filter { it.judgmentId == judgmentId } }

    /** The latest correction per (judgment, wording, item); a retraction removes it. */
    fun correctionIndex(): Map<CorrectionKey, String> = lock.withLock { CorrectionCodec.index(corrections) }

    fun count(): Int = lock.withLock { rows.size }

    fun stats(): LedgerStats = lock.withLock {
        LedgerStats(
            decisions = rows.size,
            modelRows = rows.count { it.resolvedBy is ResolvedBy.Model },
            mechanicalRows = rows.count { it.resolvedBy is ResolvedBy.Mechanical },
            unusableRows = rows.count { it.resolvedBy is ResolvedBy.Unusable },
            judgments = rows.map { it.judgmentId }.distinct().size,
            corrections = corrections.size,
            unreadableLines = unreadable,
        )
    }

    @Throws(Exception::class)
    fun saveJudgments(judgments: List<UserJudgment>) = store.saveJudgments(judgments)

    @Throws(Exception::class)
    fun judgments(): List<UserJudgment> = store.readJudgments()

    /**
     * Writes the F4 export (the desktop's four files) into [dir] and returns their paths.
     * Judgments with rows but no saved definition — the flight priorities — are measured too.
     */
    @Throws(Exception::class)
    fun exportTo(dir: String): List<String> {
        val files = lock.withLock {
            DataExport.files(
                ledger = rows,
                corrections = CorrectionCodec.index(corrections),
                judgments = store.readJudgments(),
                correctionsText = store.correctionsText(),
                includeLedgerOnlyJudgments = true,
            )
        }
        return DataExport.write(dir, files)
    }

    companion object {
        /** Opens (creating if needed) the store in [home] and reads what it holds. */
        @Throws(Exception::class)
        fun open(home: String): PhoneLedger = PhoneLedger(LedgerStore(home)).also { it.load() }

        /** One exported ledger line back to a row (for checking an export round-trips). */
        @Throws(Exception::class)
        fun parseRow(line: String): LedgerRow = LedgerCodec.parseRow(line)
    }
}
