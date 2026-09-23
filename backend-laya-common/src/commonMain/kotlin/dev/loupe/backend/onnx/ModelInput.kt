package dev.loupe.backend.onnx

import dev.loupe.engine.Extent

/** The tensors one scoring call feeds to the model. */
class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    /**
     * For a model that scores each candidate at its own position in the sequence (Laya scores
     * each option at a `<mask>` marker), the index of each candidate's marker, in candidate
     * order. Null for a model that emits one logit per candidate from the sequence as a whole.
     */
    val markerPositions: LongArray? = null,
    /**
     * How many tokens the state encoded to, and how many of them made it into [inputIds], when the
     * tokenizer fits the state to a context (see `LayaSequence`). Null when it does not report it,
     * which the backend reads as "read in full".
     */
    val stateTokens: Int? = null,
    val stateTokensKept: Int? = null,
    /**
     * How many tokens the options' text (labels and any descriptions) encoded to, and how many
     * survived the model's option budget. Null when the tokenizer does not report it.
     */
    val optionTokens: Int? = null,
    val optionTokensKept: Int? = null,
) {
    init {
        require(inputIds.isNotEmpty()) { "tokenizer produced no tokens" }
        require(inputIds.size == attentionMask.size) {
            "input ids and attention mask must be the same length, were " +
                "${inputIds.size} and ${attentionMask.size}"
        }
        markerPositions?.let { markers ->
            require(markers.isNotEmpty()) { "marker positions must not be empty" }
            // In range and strictly increasing: a marker outside the sequence would be clamped by
            // the graph's gather and silently score the wrong token, and two candidates sharing a
            // marker would be scored identically whatever the text says.
            require(markers.all { it >= 0 && it < inputIds.size }) {
                "marker positions must lie inside the ${inputIds.size}-token sequence, were " +
                    markers.contentToString()
            }
            require((1 until markers.size).all { markers[it] > markers[it - 1] }) {
                "marker positions must be strictly increasing, were ${markers.contentToString()}"
            }
        }
    }

    val length: Int get() = inputIds.size

    /** The context's cut of the state, in tokens, or null when none was reported. */
    val stateCut: Extent?
        get() {
            val total = stateTokens ?: return null
            val kept = stateTokensKept ?: return null
            return if (kept < total) Extent(kept, total, Extent.Measure.TOKENS) else null
        }

    /** The option budget's cut of the options' text, in tokens, or null when none was reported. */
    val optionCut: Extent?
        get() {
            val total = optionTokens ?: return null
            val kept = optionTokensKept ?: return null
            return if (kept < total) Extent(kept, total, Extent.Measure.TOKENS) else null
        }
}

/**
 * Turns a judgment's question, an item's text and the judgment's candidate labels into model
 * inputs.
 *
 * This is an interface rather than a concrete tokenizer because the exported model decides the
 * vocabulary, the special tokens and how the question and candidate labels are presented to it.
 * Binding one tokenizer here would tie the backend to a single export. The question is part of
 * the signature because a model that reads it — Laya does, as its "instructions" — answers a
 * different question without it; the original two-argument form could not express that.
 */
fun interface Tokenizer {
    fun encode(question: String, text: String, candidates: List<String>): TokenizedInput

    /**
     * As [encode], with a description per candidate (null = bare label) for a model that can read
     * them. A tokenizer that cannot must refuse rather than silently drop them — the judgment's
     * criteria hash says the model read them. The default accepts only all-null.
     */
    fun encodeDescribed(
        question: String,
        text: String,
        candidates: List<String>,
        descriptions: List<String?>,
    ): TokenizedInput {
        if (descriptions.any { it != null }) {
            throw UnsupportedOperationException("this tokenizer cannot show option descriptions to its model")
        }
        return encode(question, text, candidates)
    }
}

/** The tensor names a particular exported model uses. */
data class TensorNames(
    val inputIds: String = "input_ids",
    /** Null when the model takes no mask. */
    val attentionMask: String? = "attention_mask",
    val logits: String = "logits",
    /**
     * The per-candidate marker positions input (see [TokenizedInput.markerPositions]). Null for a
     * model that takes none; when set, the tokenizer must supply exactly one per candidate.
     */
    val markerPositions: String? = null,
) {
    companion object {
        /** The graph `tools/export-laya-onnx.py` writes; its contract is in that script. */
        val LAYA: TensorNames = TensorNames(markerPositions = "marker_pos")
    }
}
