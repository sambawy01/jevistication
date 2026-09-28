package dev.loupe.kit.measure

import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.TransactionEvidence

/**
 * The transaction-evidence gate's verdict per item text, kept across Unsure-queue draws
 * (2026-09-28 launch hang): the queue is drawn again whenever the ledger or the judgments change, and
 * re-reading every row's text through the gate each time is what made the draw slow. The verdict
 * depends only on the text and the budget read, never on the judgment, so one entry serves every
 * gated judgment. Keyed by item id, content hash, text length and budget: a re-read file with new
 * content is a new key.
 *
 * **Not thread-safe:** one owner, used from one thread at a time (the phone draws on one serial queue).
 */
class EvidenceMemo(private val capacity: Int = 50_000) {
    private val verdicts = HashMap<String, TransactionEvidence.Verdict>()

    /** How many verdicts are held (tests). */
    val size: Int get() = verdicts.size

    /** How many times the gate actually read a text (tests: a second draw reads none). */
    var assessed: Int = 0
        private set

    fun verdict(item: SourceItem, maxChars: Int): TransactionEvidence.Verdict {
        val key = "${item.id}\u0000${item.contentHash}\u0000${item.text.length}\u0000$maxChars"
        verdicts[key]?.let { return it }
        if (verdicts.size >= capacity) verdicts.clear()
        assessed++
        return TransactionEvidence.assess(item.text, maxChars).also { verdicts[key] = it }
    }

    fun clear() = verdicts.clear()
}
