package dev.loupe.engine

/**
 * How an item's text was fitted into the state budget.
 *
 * There is deliberately no "summarised" case. §8 of the spec: an item too large for the budget is
 * kept verbatim, truncated with an explicit marker, or removed — and the judgment is told which.
 * It is never rewritten, because a summary loses the exact path, error string or figure that made
 * the item worth keeping, and a summarised item cannot be verified against later.
 */
enum class Fit {
    /** The item's text was included in full. */
    VERBATIM,

    /** A prefix was included, followed by [TextState.TRUNCATION_MARKER]. */
    TRUNCATED,

    /** There was no room; the item contributes no text, and the judgment is told so. */
    REMOVED,
}

/** One item as it appears in the assembled state. */
data class StateItem(
    val id: String,
    /** The text contributed, which is always a prefix of the original (plus a marker if cut). */
    val text: String,
    val fit: Fit,
)

/**
 * An item's source content normalised to text, fitted to a character budget (A3).
 *
 * Assembly is mechanical and lossless-or-explicit: items are taken in order, each kept verbatim
 * while the budget allows, then one may be truncated with a visible marker, and the remainder are
 * removed. No content is ever rewritten.
 */
class TextState private constructor(
    val items: List<StateItem>,
    val budget: Int,
) {
    /** The assembled text handed to the model. */
    val text: String get() = items.joinToString("\n") { it.text }.trim()

    /** Items whose text was cut or dropped, so a judgment can declare its posture about them. */
    val incomplete: List<StateItem> get() = items.filter { it.fit != Fit.VERBATIM }

    /** True when every item was included in full. */
    val isComplete: Boolean get() = items.all { it.fit == Fit.VERBATIM }

    override fun toString(): String =
        "TextState(budget=$budget, items=${items.size}, complete=$isComplete)"

    companion object {
        /** Appended to a cut item so the model and the reader both see that text is missing. */
        const val TRUNCATION_MARKER: String = "[...truncated]"

        /**
         * Fits [sources] (id to text, in priority order) into [budget] characters.
         *
         * @throws IllegalArgumentException if [budget] is negative or an id is duplicated.
         */
        fun build(sources: List<Pair<String, String>>, budget: Int): TextState {
            require(budget >= 0) { "budget must not be negative, was $budget" }
            val seen = mutableSetOf<String>()
            for ((id, _) in sources) {
                require(seen.add(id)) { "duplicate item id: $id" }
            }

            var used = 0
            val items = sources.map { (id, text) ->
                val remaining = budget - used
                when {
                    text.length <= remaining -> {
                        used += text.length
                        StateItem(id, text, Fit.VERBATIM)
                    }
                    remaining > TRUNCATION_MARKER.length -> {
                        val keep = remaining - TRUNCATION_MARKER.length
                        used += remaining
                        StateItem(id, text.take(keep) + TRUNCATION_MARKER, Fit.TRUNCATED)
                    }
                    else -> StateItem(id, "", Fit.REMOVED)
                }
            }
            return TextState(items, budget)
        }
    }
}
