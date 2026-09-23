package dev.loupe.engine

/**
 * What a judgment does when the model's response cannot be used.
 *
 * The engine never acts on an unusable response, whatever the posture; the posture decides who
 * hears about it. Declaring it per judgment is the point: a failed junk check should be silent and
 * change nothing, while a failed expiry check must be noisy, because silence there is
 * indistinguishable from "your passport is fine".
 */
enum class FailurePosture {
    /** Let it through unremarked: the judgment simply finds nothing. */
    OPEN,

    /** Surface the failure — for judgments where quiet failure is itself the danger. */
    LOUD,

    /** Take nothing on it and queue it for review. The safe default. */
    NULL_ACTION,
}

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
     * A stable fingerprint of the wording that defines this judgment. A5 writes it into every
     * ledger row and A6 keys calibration on it, so changing the wording invalidates calibration
     * fitted against the old text rather than silently reusing it.
     */
    val criteriaHash: String

    /** What this judgment does when the model's answer cannot be used. */
    val onFailure: FailurePosture

    /**
     * Pick among a fixed set of candidate labels. The model's answer is a [Distribution] over
     * exactly these candidates — the decision model's primitive, every label scored in one
     * forward pass (Laya scores each at its own marker and softmaxes over them).
     */
    data class Choice(
        override val id: String,
        override val question: String,
        val candidates: List<String>,
        override val onFailure: FailurePosture = FailurePosture.NULL_ACTION,
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

        override val criteriaHash: String
            get() = Hashing.sha256Hex(question + "\u0000" + candidates.joinToString("\u0000"))

        /**
         * A flat distribution over this Choice's candidates: maximum entropy, which is the honest
         * shape of "the engine has no view at all". Used to record an unusable response.
         */
        fun noInformation(): Distribution =
            Distribution.of(candidates.associateWith { 1.0 / candidates.size })

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

    /**
     * A yes/no judgment.
     *
     * This is a named shape over a two-candidate [Choice], not a separate mechanism: it validates
     * and scores through exactly the same path, and only spares every caller from spelling out
     * `listOf("yes", "no")` and from inventing its own label for "yes".
     */
    data class Bool(
        override val id: String,
        override val question: String,
        override val onFailure: FailurePosture = FailurePosture.NULL_ACTION,
    ) : Judgment {

        /** The equivalent [Choice]; everything defers to it. */
        val asChoice: Choice = Choice(id, question, listOf(YES, NO), onFailure)

        override val criteriaHash: String get() = asChoice.criteriaHash

        /** @see Choice.validate */
        fun validate(response: Map<String, Double>): Distribution = asChoice.validate(response)

        /** The mass on "yes" — the number a threshold reads. */
        fun probabilityOfYes(distribution: Distribution): Probability = distribution.getValue(YES)

        companion object {
            const val YES: String = "yes"
            const val NO: String = "no"
        }
    }

    /**
     * A score over a declared, bounded range of whole numbers.
     *
     * The backend is a classifier, so a score is a **distribution over ordinal bins**, never a
     * free-form number the model writes out. That is exactly why the authoring lint refuses
     * "rate 1–10" while this type is fine: the difference is a declared range the answer must land
     * inside, which can be validated, calibrated and scored like any other judgment.
     */
    data class Score(
        override val id: String,
        override val question: String,
        val range: IntRange,
        override val onFailure: FailurePosture = FailurePosture.NULL_ACTION,
    ) : Judgment {

        init {
            require(range.count() >= 2) {
                "a Score needs a range of at least two values, was $range"
            }
        }

        /** The range as candidate labels, low to high. */
        val candidates: List<String> = range.map(Int::toString)

        /** The equivalent [Choice]; everything defers to it. */
        val asChoice: Choice = Choice(id, question, candidates, onFailure)

        override val criteriaHash: String get() = asChoice.criteriaHash

        /** @see Choice.validate */
        fun validate(response: Map<String, Double>): Distribution = asChoice.validate(response)

        /**
         * The probability-weighted mean of the range — the point estimate that uses the whole
         * distribution rather than only its peak, which is what makes an ordinal score worth
         * having over a bare label.
         */
        fun expectedValue(distribution: Distribution): Double =
            range.sumOf { value -> value * distribution.getValue(value.toString()).value }

        /** The single most likely value. */
        fun mostLikely(distribution: Distribution): Int = distribution.argmax.toInt()
    }
}
