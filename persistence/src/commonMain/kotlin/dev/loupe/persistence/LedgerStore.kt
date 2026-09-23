package dev.loupe.persistence

import dev.loupe.engine.LedgerRow
import dev.loupe.templates.UserJudgment

/** What reading a file found: the parsed values and the lines that could not be read. */
data class Loaded<T>(val values: List<T>, val unreadableLines: Int)

/**
 * The app's own files in [home] — plain, human-readable, append-only where the data is:
 *
 * - `ledger.jsonl` — every decision in exactly `Export.ledgerToJsonl`'s lossless format. Appended
 *   and synced, never rewritten, so a crash loses at most the last line, which loading counts as
 *   unreadable rather than hiding.
 * - `corrections.jsonl` — every correction and retraction, appended and synced.
 * - `judgments.json` — the user's judgments, rewritten atomically (synced temporary + rename).
 *
 * Shared by the desktop app (`~/Library/Application Support/Loupe`) and the iPhone (the app's
 * Application Support directory); the formats are the same byte for byte.
 */
class LedgerStore(val home: String) {
    val ledgerPath: String = joinPath(home, LEDGER_FILE)
    val correctionsPath: String = joinPath(home, CORRECTIONS_FILE)
    val judgmentsPath: String = joinPath(home, JUDGMENTS_FILE)
    private val lock = StoreLock()

    init {
        PlatformFiles.createDirectories(home)
    }

    /** Appends [rows] and forces them to disk before returning. */
    fun appendLedger(rows: List<LedgerRow>) {
        if (rows.isEmpty()) return
        val text = LedgerCodec.encode(rows) + "\n"
        lock.withLock { PlatformFiles.appendDurably(ledgerPath, text) }
    }

    fun readLedger(): Loaded<LedgerRow> = readLines(ledgerPath, LedgerCodec::parseRow)

    fun appendCorrection(correction: CorrectionRecord) {
        val text = CorrectionCodec.encode(correction) + "\n"
        lock.withLock { PlatformFiles.appendDurably(correctionsPath, text) }
    }

    fun readCorrections(): Loaded<CorrectionRecord> = readLines(correctionsPath, CorrectionCodec::decode)

    /** The corrections file's raw text ("" when absent): the export copies it verbatim. */
    fun correctionsText(): String = lock.withLock { PlatformFiles.readText(correctionsPath) } ?: ""

    fun saveJudgments(judgments: List<UserJudgment>) {
        val text = JudgmentCodec.encodeFile(judgments)
        lock.withLock { PlatformFiles.writeAtomically(judgmentsPath, text) }
    }

    /** The saved judgments; empty when none were saved. A malformed file throws, as on desktop. */
    fun readJudgments(): List<UserJudgment> {
        val text = lock.withLock { PlatformFiles.readText(judgmentsPath) } ?: return emptyList()
        return JudgmentCodec.decodeFile(text)
    }

    /** Parses each non-blank line; a line that fails is counted, not thrown. */
    private fun <T> readLines(path: String, parse: (String) -> T): Loaded<T> {
        val text = lock.withLock { PlatformFiles.readText(path) } ?: return Loaded(emptyList(), 0)
        var bad = 0
        val out = ArrayList<T>()
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank()) continue
            runCatching { parse(line) }.onSuccess { out += it }.onFailure { bad++ }
        }
        return Loaded(out, bad)
    }

    companion object {
        const val LEDGER_FILE: String = "ledger.jsonl"
        const val CORRECTIONS_FILE: String = "corrections.jsonl"
        const val JUDGMENTS_FILE: String = "judgments.json"
    }
}
