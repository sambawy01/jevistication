package dev.loupe.engine

/**
 * The decision model, behind one interface (A2).
 *
 * It returns the **raw** response as label-to-mass rather than a validated [Distribution], because
 * validation is the engine's job (A4): a backend must not be able to bypass the boundary by
 * handing over something already well-formed. Whatever the backend returns is treated as untrusted
 * until [Judgment.Choice.validate] has passed it.
 *
 * Alongside the masses it reports whether the model actually read all of the state it was handed
 * ([Scored.modelContext]). A model with a fixed context — Laya keeps ~760 state tokens — cuts the
 * tail of a long state, and the text-state contract (§8) is that the judgment is told when its
 * input was cut. A backend that could not say so would make that contract unenforceable.
 *
 * There is deliberately no hosted implementation. A network call breaks the offline guarantee the
 * product rests on, so the interface has no place to put one.
 */
fun interface Backend {
    /** Scores [judgment]'s candidate labels against [state]. */
    fun score(judgment: Judgment.Choice, state: TextState): Scored

    companion object {
        /**
         * A backend that reads its whole input and reports only masses — fakes, replays, and any
         * model with no context limit to speak of.
         */
        fun ofMasses(masses: (Judgment.Choice, TextState) -> Map<String, Double>): Backend =
            Backend { judgment, state -> Scored(masses(judgment, state)) }
    }
}

/**
 * What a [Backend] returns: the raw [masses] (untrusted, validated by the engine) and the
 * input-coverage fact the engine cannot know on its own.
 */
data class Scored(
    val masses: Map<String, Double>,
    /**
     * Set when the model's context cut the state: how many state tokens it read of how many the
     * state encoded to. Null when it read all of it.
     */
    val modelContext: Extent? = null,
    /**
     * Set when the model's option budget cut the options' text — the descriptions shown beside
     * the labels (see [Judgment.Choice.descriptions]): tokens kept of tokens the options encoded
     * to, summed over the options. Null when every option reached the model whole.
     */
    val optionCriteria: Extent? = null,
)

/** How much of an input survived a cut, in [unit]s. */
data class Extent(val kept: Int, val total: Int, val unit: Measure) {
    enum class Measure(val code: String) {
        CHARACTERS("chars"),
        TOKENS("tokens"),
        ;

        companion object {
            fun parse(code: String): Measure =
                entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("unknown unit '$code'")
        }
    }

    init {
        require(kept >= 0) { "kept must not be negative, was $kept" }
        require(kept < total) { "an extent records a cut: kept ($kept) must be less than total ($total)" }
    }
}

/**
 * Whether a decision's input reached the model whole (§8, A3): the two places it can be cut.
 *
 * [textBudget] is [TextState]'s character budget — the engine's own cut, with a visible marker.
 * [modelContext] is the model's token limit, reported by the backend. Either, both or neither can
 * be set. A row with no truncation record at all (logged before this existed) is *unknown*, which
 * is `LedgerRow.truncation == null`, not [NONE].
 */
data class Truncation(
    val textBudget: Extent? = null,
    val modelContext: Extent? = null,
    /**
     * The judgment's own option text (label plus description) cut by the model's option budget.
     * This is not a cut of the *item*: the item was read as [textBudget]/[modelContext] say, so
     * it does not count toward [isCut] or the cut-input policy. It is recorded so a reader can
     * see that the criteria the model read were shorter than the ones written.
     */
    val optionCriteria: Extent? = null,
) {
    init {
        textBudget?.let { require(it.unit == Extent.Measure.CHARACTERS) { "text budget is counted in characters" } }
        modelContext?.let { require(it.unit == Extent.Measure.TOKENS) { "model context is counted in tokens" } }
        optionCriteria?.let { require(it.unit == Extent.Measure.TOKENS) { "option criteria are counted in tokens" } }
    }

    /** True when the model saw less than the item's whole text. */
    val isCut: Boolean get() = textBudget != null || modelContext != null

    /** True when the options' descriptions reached the model shortened. */
    val criteriaCut: Boolean get() = optionCriteria != null

    companion object {
        /** Known to be whole: nothing was cut. */
        val NONE: Truncation = Truncation()
    }
}
