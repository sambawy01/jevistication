package dev.loupe.backend.onnx

import dev.loupe.engine.Judgment
import dev.loupe.engine.Scored
import dev.loupe.engine.TextState
import kotlin.math.exp

/**
 * The runtime-independent halves of scoring a Choice judgment with an exported graph: building the
 * inputs before the graph runs and turning its logits into masses after. The JVM `OnnxBackend` and
 * the iOS backend both go through these, so the two platforms cannot drift apart on what they feed
 * a graph or how they read it back; only "run the graph" differs.
 */
object ChoiceScoring {

    /**
     * Encodes [state] for [judgment] and checks the result against the graph's [names] before
     * anything runs: a tokenizer and a graph that disagree about markers are a wiring mistake, not
     * a model answer, and feeding them anyway would still produce numbers.
     */
    fun encode(tokenizer: Tokenizer, names: TensorNames, judgment: Judgment.Choice, state: TextState): TokenizedInput {
        // A score is sent highest level first where LayaRuntimeRules says so; [scored] maps back.
        val reverse = LayaRuntimeRules.reversesScore(judgment)
        val candidates = if (reverse) judgment.candidates.reversed() else judgment.candidates
        val encoded = if (judgment.descriptions.isEmpty()) {
            tokenizer.encode(judgment.question, state.text, candidates)
        } else {
            val descriptions = judgment.descriptionList
            tokenizer.encodeDescribed(judgment.question, state.text, candidates,
                if (reverse) descriptions.reversed() else descriptions)
        }
        require((names.markerPositions == null) == (encoded.markerPositions == null)) {
            if (names.markerPositions == null) {
                "tokenizer produced marker positions but the model takes no marker input"
            } else {
                "model takes marker input '${names.markerPositions}' but the tokenizer produced none"
            }
        }
        encoded.markerPositions?.let { markers ->
            require(markers.size == judgment.candidates.size) {
                "tokenizer produced ${markers.size} marker positions but judgment " +
                    "'${judgment.id}' declares ${judgment.candidates.size} candidates"
            }
        }
        return encoded
    }

    /**
     * The graph's one row of [logits] as a [Scored], with the tokenizer's report of any cuts. The
     * logits are in the order [encode] sent the options; a reversed score is mapped back to the
     * written order here, so masses are always keyed by the judgment's own labels.
     */
    fun scored(logits: FloatArray, judgment: Judgment.Choice, encoded: TokenizedInput): Scored {
        require(logits.size == judgment.candidates.size) {
            "model produced ${logits.size} logits but judgment '${judgment.id}' declares " +
                "${judgment.candidates.size} candidates"
        }
        val written = if (LayaRuntimeRules.reversesScore(judgment)) logits.reversedArray() else logits
        return Scored(
            softmax(written, judgment.candidates),
            modelContext = encoded.stateCut,
            optionCriteria = encoded.optionCut,
        )
    }

    /**
     * Softmax over the logits, computed in [Double] and shifted by the maximum.
     *
     * The shift is not cosmetic: `exp` of a large logit overflows to infinity in [Float], and the
     * masses this produces are fed straight into a distribution that must normalise.
     */
    fun softmax(logits: FloatArray, candidates: List<String>): Map<String, Double> {
        val highest = logits.max().toDouble()
        val exponentials = logits.map { exp(it.toDouble() - highest) }
        val total = exponentials.sum()
        check(total > 0.0 && total.isFinite()) { "logits produced a degenerate softmax: $total" }
        return candidates.indices.associate { i -> candidates[i] to exponentials[i] / total }
    }
}
