package dev.loupe.backend.onnx

/**
 * Text to subword ids, with **no** special tokens added and **no** truncation.
 *
 * The Laya sequence is assembled from several separately-encoded pieces with special tokens placed
 * between them by [LayaPrompt], exactly as upstream's `build_sequence` does; an encoder that added
 * its own `<bos>`/`<eos>` or cut long text at some default length would shift every marker and
 * silently change the answer.
 */
fun interface SubwordEncoder {
    fun encode(text: String): LongArray
}

/**
 * The special tokens [LayaPrompt] places, as ids in the checkpoint's vocabulary and the literal
 * mask string upstream scrubs from user text.
 */
data class LayaSpecialTokens(
    /** `tok.cls_token_id`. For the mmBERT tokenizer that is `<bos>`, not a `[CLS]`. */
    val cls: Long,
    /** `tok.sep_token_id`. For the mmBERT tokenizer that is `<eos>`. */
    val sep: Long,
    /** `tok.mask_token_id`: the option marker every candidate is scored at. */
    val mask: Long,
    /** `tok.mask_token`: occurrences in the question, options or state are replaced by a space. */
    val maskText: String,
) {
    companion object {
        /**
         * `convaiinnovations/laya-multilingual` at the revision `tools/export-laya-onnx.py` pins,
         * read from its `tokenizer_config.json` (`cls_token` `<bos>`, `sep_token` `<eos>`,
         * `mask_token` `<mask>`) and the ids upstream's tokenizer resolves them to.
         * [HuggingFaceSubwordEncoder.verify] checks them against the real tokenizer at load time.
         */
        val MULTILINGUAL: LayaSpecialTokens = LayaSpecialTokens(cls = 2, sep = 1, mask = 4, maskText = "<mask>")

        /** The token strings behind [MULTILINGUAL]'s ids, for verification against a tokenizer. */
        val MULTILINGUAL_TOKEN_TEXT: Map<String, Long> = mapOf("<bos>" to 2L, "<eos>" to 1L, "<mask>" to 4L)
    }
}

/** One assembled Laya sequence for a single Choice question. */
class LayaSequence(
    val inputIds: LongArray,
    /** Index of each candidate's `<mask>` marker in [inputIds], in candidate order. */
    val markerPositions: LongArray,
    /** How many tokens the state encoded to before fitting. */
    val stateTokens: Int,
    /** How many of those survived into [inputIds]; fewer than [stateTokens] means the tail was cut. */
    val stateTokensKept: Int,
    /** Tokens the rendered options (label, or `label: description`) encoded to, summed, markers excluded. */
    val optionTokens: Int = 0,
    /** How many of those survived the per-option cap and the head budget. */
    val optionTokensKept: Int = optionTokens,
) {
    /**
     * True when an option's text was cut to fit — upstream's 48-token per-option cap, or the
     * shrink every option takes when they overflow the head budget. Upstream does this silently.
     */
    val optionsTruncated: Boolean get() = optionTokensKept < optionTokens

    /**
     * True when the state's tail was dropped to fit the context. Upstream does this silently; it is
     * surfaced here so a caller can see it, because Loupe's text-state contract is that a judgment
     * is told when its input was cut (see `TextState`).
     */
    val stateTruncated: Boolean get() = stateTokensKept < stateTokens
}

/**
 * Builds Laya's input for one Choice question — a byte-for-byte mirror of upstream's
 * `laya.common.build_sequence` (github.com/NandhaKishorM/laya, commit `23a17522`, v0.3.20; unchanged from `c7527708`), as called by
 * `Agent.system_one` for a `choice` question whose criteria are a bare label list:
 *
 * ```
 * <cls> "choice question: {question}" <sep> <mask> " {option 0}" <mask> " {option 1}" ... <sep> state <sep>
 * ```
 *
 * The rules, each copied rather than improved, because the checkpoint was trained on exactly this
 * and a "better" layout is a different input distribution:
 *
 * - The literal mask string is replaced by a space in the question, each option and the state,
 *   so user text can never forge a marker.
 * - Each option is encoded with a leading space and capped at **48** tokens (plus its marker).
 * - Options share a **[headMaxLen]** budget with the question. If fewer than 16 tokens would be
 *   left for the question, every option is cut to `max(4, (headMaxLen - 16) / options)` tokens
 *   (marker included). The question then keeps `max(8, remaining)` tokens.
 * - The state fills whatever room is left before the final `<sep>`, keeping its **prefix**
 *   (upstream's default `truncate_left=False`), and the whole sequence is capped at [maxLen].
 *
 * **Over-length handling, deliberately split.** A long *state* is truncated, as upstream does and
 * as the model was trained — and [LayaSequence.stateTruncated] reports it rather than hiding it.
 * Too many *options* to fit is a failure: upstream raises when any marker would fall beyond
 * [maxLen], and so does this, by exception, which `DecisionEngine` turns into the judgment's
 * declared failure posture. A dropped candidate would otherwise be scored as if it did not exist.
 *
 * **Descriptive options.** With no descriptions each option is the label alone — upstream's
 * `{label: None}` case, and what every judgment gets by default. Given a description, an option is
 * rendered exactly as upstream's `render_options` does for `{label: description}`:
 * `"label: description"`, with `None` and `""` meaning "no description". The description is part of
 * the option's text, so it shares the option's budget: the whole `" label: description"` is capped
 * at 48 tokens, and shrinks with the rest when the options overflow [headMaxLen]. Upstream gives a
 * description no budget of its own and neither does this. What was cut is reported as
 * [LayaSequence.optionTokens]/[LayaSequence.optionTokensKept] rather than hidden.
 */
class LayaPrompt(
    private val encoder: SubwordEncoder,
    private val special: LayaSpecialTokens,
    /** `rl_agent_config.json` `max_len`: 1024 for laya-multilingual. */
    val maxLen: Int = 1024,
    /** `rl_agent_config.json` `head_max_len`: 256 for laya-multilingual. */
    val headMaxLen: Int = 256,
) {
    init {
        require(headMaxLen > 16) { "headMaxLen must exceed the 16-token question floor, was $headMaxLen" }
        require(maxLen > headMaxLen + 3) { "maxLen ($maxLen) must leave room beyond headMaxLen ($headMaxLen)" }
    }

    fun build(
        question: String,
        state: String,
        candidates: List<String>,
        /** One per candidate, or empty for none; null or `""` shows that label bare (upstream's rule). */
        descriptions: List<String?> = emptyList(),
    ): LayaSequence {
        require(candidates.isNotEmpty()) { "a Laya question needs at least one option" }
        require(descriptions.isEmpty() || descriptions.size == candidates.size) {
            "${descriptions.size} descriptions for ${candidates.size} options"
        }
        val mask = special.maskText

        val headIds = encoder.encode("choice question: " + question.replace(mask, " "))
        val rendered = candidates.mapIndexed { i, label -> renderOption(label, descriptions.getOrNull(i)) }
        val encodedOptions = rendered.map { option -> encoder.encode(" " + option.replace(mask, " ")) }
        var optionIds: List<LongArray> = encodedOptions.map { encoded ->
            longArrayOf(special.mask) + encoded.take(OPTION_CAP)
        }
        var optionBudget = headMaxLen - optionIds.sumOf { it.size }
        if (optionBudget < QUESTION_FLOOR) {
            val perOption = maxOf(4, (headMaxLen - QUESTION_FLOOR) / maxOf(1, optionIds.size))
            optionIds = optionIds.map { it.take(perOption) }
            optionBudget = headMaxLen - optionIds.sumOf { it.size }
        }

        val ids = ArrayList<Long>(maxLen)
        ids += special.cls
        headIds.take(maxOf(8, optionBudget)).forEach { ids += it }
        ids += special.sep
        val markers = ArrayList<Long>(candidates.size)
        for (option in optionIds) {
            markers += ids.size.toLong()
            option.forEach { ids += it }
        }
        ids += special.sep

        val room = maxOf(0, maxLen - ids.size - 1)
        val stateIds = encoder.encode(state.replace(mask, " "))
        val kept = minOf(room, stateIds.size)
        for (i in 0 until kept) ids += stateIds[i]
        ids += special.sep

        val sequence = if (ids.size > maxLen) ids.subList(0, maxLen) else ids
        val inRange = markers.filter { it < maxLen }
        if (inRange.size != candidates.size) {
            throw IllegalArgumentException(
                "${candidates.size} options do not fit Laya's $maxLen-token context: only " +
                    "${inRange.size} markers fall inside it (upstream raises here too)",
            )
        }
        return LayaSequence(
            inputIds = sequence.toLongArray(),
            markerPositions = inRange.toLongArray(),
            stateTokens = stateIds.size,
            stateTokensKept = kept,
            optionTokens = encodedOptions.sumOf { it.size },
            // Each option kept its ids minus its one marker; upstream's cuts never drop the marker.
            optionTokensKept = optionIds.sumOf { it.size - 1 },
        )
    }

    private fun LongArray.take(n: Int): LongArray = if (size <= n) this else copyOf(n)

    companion object {
        /**
         * Upstream `render_options` for a choice option: the label alone when there is no
         * description (`None` or `""`), otherwise `"label: description"`.
         */
        fun renderOption(label: String, description: String?): String =
            if (description.isNullOrEmpty()) label else "$label: $description"

        /** Upstream's per-option token cap (`[:48]`), not counting the marker. */
        const val OPTION_CAP: Int = 48

        /** Below this many tokens left for the question, upstream shrinks every option. */
        const val QUESTION_FLOOR: Int = 16
    }
}

/**
 * The [Tokenizer] `OnnxBackend` uses for a Laya graph: [LayaPrompt] plus an all-ones attention
 * mask (batch of one, no padding) and the marker positions the graph gathers at.
 * Pair it with [TensorNames.LAYA].
 */
class LayaTokenizer(private val prompt: LayaPrompt) : Tokenizer {
    override fun encode(question: String, text: String, candidates: List<String>): TokenizedInput =
        encodeDescribed(question, text, candidates, emptyList())

    override fun encodeDescribed(
        question: String,
        text: String,
        candidates: List<String>,
        descriptions: List<String?>,
    ): TokenizedInput {
        val sequence = prompt.build(question, text, candidates, descriptions)
        return TokenizedInput(
            inputIds = sequence.inputIds,
            attentionMask = LongArray(sequence.inputIds.size) { 1L },
            markerPositions = sequence.markerPositions,
            stateTokens = sequence.stateTokens,
            stateTokensKept = sequence.stateTokensKept,
            optionTokens = sequence.optionTokens,
            optionTokensKept = sequence.optionTokensKept,
        )
    }
}
