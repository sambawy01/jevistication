package dev.loupe.engine

/**
 * A typed judgment: a question the engine answers over an item's text state (A4 in the build
 * plan). This increment implements [Choice]; Bool and Score judgments follow.
 */
sealed interface Judgment {
    /** Stable identifier; calibration and ledger rows are keyed on it. */
    val id: String

    /** The plain-language question, recorded so a wording change invalidates fitted calibration. */
    val question: String

    /**
     * Pick among a fixed set of candidate labels. The model's answer is a [Distribution] over
     * exactly these candidates — the GLiClass primitive, labels scored in one forward pass.
     */
    data class Choice(
        override val id: String,
        override val question: String,
        val candidates: List<String>,
    ) : Judgment {
        init {
            require(id.isNotBlank()) { "judgment id must not be blank" }
            require(candidates.size >= 2) {
                "a Choice needs at least two candidates, had ${candidates.size}"
            }
            require(candidates.distinct().size == candidates.size) {
                "Choice candidates must be distinct, were $candidates"
            }
        }

        /**
         * Validates a raw model response (label to mass) into a [Distribution] over exactly this
         * Choice's candidates.
         *
         * @throws IllegalArgumentException if the response names a label that is not a candidate
         *   (unknown key), omits a candidate (key-count mismatch), or carries a mass that is not a
         *   valid probability or does not normalise — the last two delegated to [Distribution].
         */
        fun validate(response: Map<String, Double>): Distribution {
            val expected = candidates.toSet()
            val unknown = response.keys - expected
            require(unknown.isEmpty()) { "response names unknown candidate(s): $unknown" }
            val missing = expected - response.keys
            require(missing.isEmpty()) { "response omits candidate(s): $missing" }
            // Rebuild in candidate order so Distribution.argmax tie-breaking is deterministic.
            return Distribution.of(candidates.associateWith { response.getValue(it) })
        }
    }
}
