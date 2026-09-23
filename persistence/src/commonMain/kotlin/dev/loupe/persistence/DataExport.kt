package dev.loupe.persistence

import dev.loupe.engine.Export
import dev.loupe.engine.JudgmentCalibrationView
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.VisibleCalibration
import dev.loupe.templates.UserJudgment

/**
 * F4 export: the same four files on every platform — judgments, per-judgment calibration, the
 * lossless ledger (each row carrying the user's latest correction) and the corrections log copied
 * verbatim. The desktop writes them into a folder the user picks; the iPhone shares them.
 */
object DataExport {
    const val JUDGMENTS: String = "loupe-judgments.json"
    const val CALIBRATION: String = "loupe-calibration.json"
    const val LEDGER: String = "loupe-ledger.jsonl"
    const val CORRECTIONS: String = "loupe-corrections.jsonl"

    /**
     * The rows that count for one judgment under one wording: latest per item, with the user's
     * correction merged in as `LedgerRow.correction` — the field every engine function reads.
     */
    fun effectiveRows(
        all: List<LedgerRow>,
        judgmentId: String,
        criteriaHash: String,
        corrections: Map<CorrectionKey, String>,
    ): List<LedgerRow> {
        val latest = LinkedHashMap<String, LedgerRow>()
        for (row in all) {
            if (row.judgmentId != judgmentId || row.criteriaHash != criteriaHash) continue
            val item = row.itemId ?: continue
            latest.remove(item)
            latest[item] = row
        }
        return latest.values.map { row ->
            val label = corrections[CorrectionKey(judgmentId, criteriaHash, row.itemId!!)]
            if (label != null && label in row.distribution.labels) row.copy(correction = label) else row
        }
    }

    /**
     * The export's files as (file name, contents), in the desktop's order.
     *
     * [includeLedgerOnlyJudgments] also measures judgments that have ledger rows but no saved
     * definition (on the iPhone, the Web tab's flight priorities), under the wording of their most
     * recent row. The desktop leaves it off: it measures exactly its saved judgments.
     */
    fun files(
        ledger: List<LedgerRow>,
        corrections: Map<CorrectionKey, String>,
        judgments: List<UserJudgment>,
        correctionsText: String,
        includeLedgerOnlyJudgments: Boolean = false,
    ): List<Pair<String, String>> {
        val merged = ledger.map { row ->
            val label = row.itemId?.let { corrections[CorrectionKey(row.judgmentId, row.criteriaHash, it)] }
            if (label != null && label in row.distribution.labels) row.copy(correction = label) else row
        }
        val views = ArrayList<JudgmentCalibrationView>()
        judgments.mapNotNullTo(views) { j ->
            VisibleCalibration.forJudgment(effectiveRows(ledger, j.id, j.criteriaHash, corrections), j.id)
        }
        if (includeLedgerOnlyJudgments) {
            val saved = judgments.map { it.id }.toSet()
            val latestHash = LinkedHashMap<String, String>()
            for (row in ledger) if (row.judgmentId !in saved) latestHash[row.judgmentId] = row.criteriaHash
            for ((id, hash) in latestHash) {
                VisibleCalibration.forJudgment(effectiveRows(ledger, id, hash, corrections), id)?.let(views::add)
            }
        }
        return listOf(
            JUDGMENTS to Export.judgmentsToJson(judgments.map { it.toDefinition() }),
            CALIBRATION to Export.calibrationToJson(views),
            LEDGER to Export.ledgerToJsonl(merged) + if (merged.isEmpty()) "" else "\n",
            CORRECTIONS to correctionsText,
        )
    }

    /** Writes [files] into [dir] (created if needed) and returns their paths. */
    fun write(dir: String, files: List<Pair<String, String>>): List<String> {
        PlatformFiles.createDirectories(dir)
        return files.map { (name, text) ->
            joinPath(dir, name).also { PlatformFiles.writeAtomically(it, text) }
        }
    }
}
