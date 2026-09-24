package dev.loupe.kit.activity

import dev.loupe.engine.LedgerRow
import dev.loupe.engine.ResolvedBy

/**
 * How the phone's jobs turn what they did into job numbers, in one place so the rules are tested once:
 * ledger rows (a judgment sweep, the passive sort), one privacy-checked item, one mail verdict, one watcher.
 * Only ids, labels and numbers go in; see [Activity.clean].
 */
object ActivityReport {
    /** Who answered a ledger row: the model (Laya), a baseline switched in automatically, or a rule. */
    fun source(row: LedgerRow): String = when (val r = row.resolvedBy) {
        is ResolvedBy.Mechanical -> if (r.check.contains("baseline")) "baseline" else "rule"
        else -> "laya"
    }

    /** Where a ledger row went: unusable is skipped, below [threshold] is uncertain (sent to you), else accepted. */
    fun gate(row: LedgerRow, threshold: Double): String = when {
        row.resolvedBy is ResolvedBy.Unusable -> "skipped"
        row.propensity.value < threshold -> "uncertain"
        else -> "accepted"
    }

    /**
     * Records [rows] of one judgment on [job]: each is an item read, a decision on the judgment's opaque
     * question id ([Activity.questionId]), and a gate. [model] is the checkpoint that answered model rows.
     */
    fun recordRows(job: ActivityJob, rows: List<LedgerRow>, threshold: Double, model: String?) {
        for (row in rows) {
            val src = source(row)
            val g = gate(row, threshold)
            job.count("read", 1)
            if (g != "skipped") {
                job.decision(Activity.questionId(row.judgmentId), row.action, row.propensity.value, src, if (src == "laya") model else null, 0)
            }
            job.gate(g, 1)
            if (g == "uncertain") job.count("to_you", 1)
        }
    }

    /**
     * One item of a privacy check (the phone's Folder Scan): read unless skipped (a contact card is the
     * address book itself), one rules decision on `sensitive`, flagged when a rule found something.
     */
    fun recordScanned(job: ActivityJob, findings: Int, skipped: Boolean, readContent: Boolean) {
        if (skipped) {
            job.gate("skipped", 1)
            return
        }
        job.count("read", 1)
        job.decision("sensitive", if (findings > 0) "yes" else "no", 1.0, "rule", null, 0)
        if (findings > 0) {
            job.gate("flagged", 1)
            job.count("to_you", 1)
        } else {
            job.gate("accepted", 1)
        }
        val seen = (job.metaNumber("files_seen") ?: 0.0) + 1
        job.setMeta(mapOf("files_seen" to seen, "laya_files" to 0, "rule_only" to seen, "ocr_files" to 0, "phase_unit" to (if (readContent) "files" else "names")))
    }

    /**
     * One email of a triage run: its category and phishing verdicts as rule decisions; flagged when it looks
     * like phishing, uncertain when the rules were unsure, else accepted.
     */
    fun recordEmail(job: ActivityJob, category: String?, phishing: Boolean, unsure: Boolean, needsReply: Boolean?) {
        job.count("read", 1)
        if (category != null) job.decision("category", category, if (unsure) 0.5 else 1.0, "rule", null, 0)
        if (needsReply != null) job.decision("needs_reply", if (needsReply) "yes" else "no", 1.0, "rule", null, 0)
        job.decision("is_phishing", if (phishing) "yes" else "no", 1.0, "rule", null, 0)
        when {
            phishing -> { job.gate("flagged", 1); job.count("to_you", 1) }
            unsure -> { job.gate("uncertain", 1); job.count("to_you", 1) }
            else -> job.gate("accepted", 1)
        }
    }
}
