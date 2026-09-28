package dev.loupe.kit.measure

import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem

/**
 * A large, deterministic set of long documents for the perf tests and the phone's DEBUG
 * `-LoupeBigLedger` launch (2026-09-28 launch hang: 1,226 files and 289 ledger rows over long texts
 * froze Now). Never used by the app's own paths.
 *
 * Item `i` is 20–50 KB of text, one of four kinds so the evidence gate gives every verdict: plain
 * minutes (no evidence: answered "no" by rule), a till receipt (evidence first), a shop listing
 * (answered "no" by rule), and a bare "Total 3.20" note (weak: the model decides). The texts are
 * shared between items of the same kind and length, so 3,000 items cost a few megabytes, not hundreds.
 */
object LargeLedgerFixture {
    private const val FILLER =
        "Minutes of the residents' meeting on 3 March 2026. Present: A. Byrne, C. Dale, E. Fox. " +
            "The lift on the east stair is fixed; the bins move to Thursdays; the garden rota is on the board. " +
            "محضر اجتماع السكان في ٣ مارس، والحديقة مفتوحة يوم السبت.\n"

    private val HEADS = listOf(
        "",
        "John Lewis & Partners\nReceipt No. 8841-2210-77\nTOTAL £1,199.00\nPaid by VISA ************4412\n",
        "Bugaboo Donkey 5 Mono stroller\n4.8 (212 reviews)\nAdd to basket\nIn stock – Free delivery\n",
        "Cafe Luna\nFlat white 3.20\nTotal 3.20\n",
    )

    private val texts: Map<Pair<Int, Int>, String> by lazy {
        buildMap {
            for (kind in HEADS.indices) for (step in 0..6) {
                val target = 20_000 + step * 5_000
                val body = StringBuilder(HEADS[kind])
                while (body.length < target) body.append(FILLER)
                put(kind to step, body.toString())
            }
        }
    }

    /** [count] items, ids `big:<i>`, of 20–50 KB each. */
    fun items(count: Int): List<SourceItem> = List(count) { i ->
        val text = texts.getValue((i % HEADS.size) to (i / HEADS.size % 7))
        SourceItem(
            id = "big:$i", sourceId = "big", kind = ItemKind.TEXT, path = "/big/$i.txt", messageIndex = null, name = "big-$i.txt",
            text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "big-$i",
            mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = null,
        )
    }
}
