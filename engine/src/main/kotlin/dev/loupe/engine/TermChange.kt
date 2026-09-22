package dev.loupe.engine

/** A labelled amount whose value moved between two versions of the same document. */
data class TermChange(
    val label: String,
    val beforeMinor: Long,
    val afterMinor: Long,
) {
    val deltaMinor: Long get() = afterMinor - beforeMinor

    val increased: Boolean get() = deltaMinor > 0

    /** Percentage move, or null when the previous value was zero and a ratio means nothing. */
    val percentChange: Double?
        get() = if (beforeMinor == 0L) null else deltaMinor * 100.0 / beforeMinor
}

/** What changed between two versions of a document. */
data class TermComparison(
    val changed: List<TermChange>,
    /** Labelled amounts present only in the new version. */
    val added: Map<String, Long>,
    /** Labelled amounts present only in the old version. */
    val removed: Map<String, Long>,
) {
    fun hasChanges(): Boolean = changed.isNotEmpty() || added.isNotEmpty() || removed.isNotEmpty()
}

/**
 * Term-change detection (C3).
 *
 * Banks, insurers, ISPs and landlords write when terms change, and nobody reads those emails. The
 * comparison is against the previous version of the same document, and it is arithmetic: extract
 * the labelled amounts from both, and report what moved. One catch — a renewal premium quietly up
 * 23% — pays for the product for years.
 *
 * A currency marker is required, deliberately. Matching bare numbers would turn every reference
 * number and date in a statement into a "term".
 */
object TermChangeDetector {

    private val LABELLED_AMOUNT = Regex(
        """([A-Za-z][A-Za-z ]{0,29}?)\s*[:=]?\s*[£$€]\s*(\d[\d,]*(?:\.\d{1,2})?)""",
    )

    /**
     * Extracts labelled amounts from [text], in minor units.
     *
     * The key is the last two words of the label, lowercased, so "Monthly premium" and
     * "Your monthly premium" agree. A label reworded beyond that reads as a removal plus an
     * addition rather than a change — which is the honest reading, since we cannot know they are
     * the same term.
     */
    fun amounts(text: String): Map<String, Long> {
        val found = LinkedHashMap<String, Long>()
        for (match in LABELLED_AMOUNT.findAll(text)) {
            val label = normalise(match.groupValues[1]) ?: continue
            found[label] = toMinorUnits(match.groupValues[2])
        }
        return found
    }

    /** Compares two versions of the same document. */
    fun compare(previous: String, current: String): TermComparison {
        val before = amounts(previous)
        val after = amounts(current)

        val changed = before.keys.intersect(after.keys).mapNotNull { label ->
            val from = before.getValue(label)
            val to = after.getValue(label)
            if (from == to) null else TermChange(label, from, to)
        }.sortedByDescending { it.percentChange?.let { p -> kotlin.math.abs(p) } ?: 0.0 }

        return TermComparison(
            changed = changed,
            added = after.filterKeys { it !in before },
            removed = before.filterKeys { it !in after },
        )
    }

    /** Lowercased last two words of a label, or null when nothing usable is left. */
    private fun normalise(raw: String): String? {
        val words = raw.trim().lowercase().split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return words.takeLast(2).joinToString(" ")
    }

    /** "1,234.56" to 123456; "450" to 45000. */
    private fun toMinorUnits(raw: String): Long {
        val cleaned = raw.replace(",", "")
        val parts = cleaned.split(".")
        val major = parts[0].toLong()
        val minor = when {
            parts.size < 2 -> 0L
            parts[1].length == 1 -> parts[1].toLong() * 10
            else -> parts[1].take(2).toLong()
        }
        return major * 100 + minor
    }
}
