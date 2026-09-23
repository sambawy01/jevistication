package dev.loupe.backend.onnx

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [SubwordEncoder] over a Hugging Face `tokenizer.json`, run by the Hugging Face `tokenizers`
 * Rust library through DJL's JNI binding (`ai.djl.huggingface:tokenizers`).
 *
 * **Not hand-rolled, on purpose.** A `tokenizer.json` carries a normalizer, pre-tokenizer, model
 * and post-processor; a subtly wrong reimplementation does not fail, it shifts every prediction.
 * This delegates to the same Rust library the Python reference uses, and the gated parity test
 * checks it reproduces the reference ids exactly.
 *
 * Three DJL defaults are overridden because each would silently corrupt a Laya sequence:
 *
 * - `addSpecialTokens` is **off**: [LayaPrompt] places `<bos>`/`<eos>` itself.
 * - `truncation` is **off**: DJL otherwise truncates to a default 512 tokens, cutting the state
 *   before [LayaPrompt] applies upstream's own budget.
 * - `padding` is **off**: one sequence, no batch.
 *
 * **Offline, enforced.** DJL's `HuggingFaceTokenizer.newInstance` calls `Ec2Utils.callHome`, which
 * probes the EC2 metadata endpoint (169.254.169.254) and may send a telemetry request; its native
 * loader can also download a JNI library for GPU "flavors". Both are skipped only in DJL offline
 * mode. The product rests on there being no network call anywhere, so [open] switches offline mode
 * on and **refuses to continue if it did not take effect** (an environment variable
 * `DJL_OFFLINE=false` outranks the system property) rather than hoping.
 */
class HuggingFaceSubwordEncoder private constructor(
    private val tokenizer: HuggingFaceTokenizer,
) : SubwordEncoder, AutoCloseable {

    override fun encode(text: String): LongArray =
        tokenizer.encode(text, false, false).ids

    /**
     * Checks that each of [tokens] encodes to exactly its expected single id, and throws if not.
     * This is what catches a `tokenizer.json` from a different checkpoint than the graph.
     */
    fun verify(tokens: Map<String, Long>) {
        for ((text, expected) in tokens) {
            val ids = encode(text)
            require(ids.size == 1 && ids[0] == expected) {
                "tokenizer maps '$text' to ${ids.contentToString()}, expected [$expected]: " +
                    "this tokenizer.json does not belong to the exported model"
            }
        }
    }

    override fun close() {
        tokenizer.close()
    }

    companion object {
        /**
         * Loads `tokenizer.json` from [path] (the file, or a directory containing it), with DJL in
         * offline mode. The caller owns the result and must [close] it.
         *
         * @throws IllegalStateException if DJL offline mode cannot be established.
         */
        fun open(path: Path): HuggingFaceSubwordEncoder {
            enforceOffline()
            val file = if (Files.isDirectory(path)) path.resolve("tokenizer.json") else path
            require(Files.isRegularFile(file)) { "no tokenizer.json at $file" }
            val tokenizer = HuggingFaceTokenizer.newInstance(
                file,
                mapOf(
                    "addSpecialTokens" to "false",
                    "truncation" to "false",
                    "padding" to "false",
                ),
            )
            return HuggingFaceSubwordEncoder(tokenizer)
        }

        /** Loads the tokenizer and checks it carries [LayaSpecialTokens.MULTILINGUAL]'s ids. */
        fun openLayaMultilingual(path: Path): HuggingFaceSubwordEncoder =
            open(path).also { encoder ->
                try {
                    encoder.verify(LayaSpecialTokens.MULTILINGUAL_TOKEN_TEXT)
                } catch (e: IllegalArgumentException) {
                    encoder.close()
                    throw e
                }
            }

        private fun enforceOffline() {
            System.setProperty("ai.djl.offline", "true")
            System.setProperty("OPT_OUT_TRACKING", "true")
            check(ai.djl.util.Utils.isOfflineMode()) {
                "DJL offline mode is not in effect (is DJL_OFFLINE set to false in the " +
                    "environment?); refusing to load a tokenizer that may make network calls"
            }
        }
    }
}
