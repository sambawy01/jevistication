@file:OptIn(ExperimentalForeignApi::class)

package dev.loupe.backend.onnx.ios

import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.SubwordEncoder
import cnames.structs.LoupeTokenizer
import dev.loupe.backend.onnx.ios.tok.loupe_tok_encode
import dev.loupe.backend.onnx.ios.tok.loupe_tok_free
import dev.loupe.backend.onnx.ios.tok.loupe_tok_from_file
import dev.loupe.backend.onnx.ios.tok.loupe_tok_ids_free
import dev.loupe.backend.onnx.ios.tok.loupe_tok_string_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * The iOS twin of `backend-onnx`'s `HuggingFaceSubwordEncoder`: a Hugging Face `tokenizer.json` run
 * by the Hugging Face `tokenizers` Rust crate — the same version (0.21.4) DJL 0.38.0 wraps on the
 * JVM — through the C ABI in `ios-native/tokenizers-ffi`.
 *
 * Same three settings as the JVM encoder, for the same reasons: no special tokens (`LayaPrompt`
 * places `<bos>`/`<eos>` itself), truncation off and padding off (the Rust side clears both on
 * load, so a `tokenizer.json` that declares either cannot cut a Laya sequence). The crate is built
 * without its `http` feature: there is no code path to the network.
 *
 * Text crosses the boundary as UTF-8 via [String.encodeToByteArray]; a lone surrogate (not valid
 * text) becomes U+FFFD there. Not thread-safe to [close] while encoding; encoding itself is.
 */
class RustSubwordEncoder private constructor(
    private var handle: CPointer<LoupeTokenizer>?,
) : SubwordEncoder, AutoCloseable {

    override fun encode(text: String): LongArray {
        val tokenizer = checkNotNull(handle) { "tokenizer is closed" }
        val bytes = text.encodeToByteArray()
        return memScoped {
            val ids = alloc<CPointerVar<LongVar>>()
            val count = alloc<ULongVar>()
            val error = alloc<CPointerVar<ByteVar>>()
            val rc = if (bytes.isEmpty()) {
                loupe_tok_encode(tokenizer, null, 0.convert(), ids.ptr, count.ptr, error.ptr)
            } else {
                bytes.usePinned { pinned ->
                    loupe_tok_encode(
                        tokenizer,
                        pinned.addressOf(0).reinterpret<UByteVar>(),
                        bytes.size.convert(),
                        ids.ptr,
                        count.ptr,
                        error.ptr,
                    )
                }
            }
            if (rc != 0) throw IllegalStateException("tokenizer: ${takeMessage(error.value)} (code $rc)")
            val pointer = ids.value
            val n = count.value.toInt()
            try {
                LongArray(n) { pointer!![it] }
            } finally {
                loupe_tok_ids_free(pointer, count.value.convert())
            }
        }
    }

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
        handle?.let { loupe_tok_free(it) }
        handle = null
    }

    companion object {
        /** Loads `tokenizer.json` from [path] (a file path). The caller owns the result. */
        fun open(path: String): RustSubwordEncoder = memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            val handle = loupe_tok_from_file(path, error.ptr)
                ?: throw IllegalArgumentException("tokenizer: ${takeMessage(error.value)}")
            RustSubwordEncoder(handle)
        }

        /** Loads the tokenizer and checks it carries [LayaSpecialTokens.MULTILINGUAL]'s ids. */
        fun openLayaMultilingual(path: String): RustSubwordEncoder =
            open(path).also { encoder ->
                try {
                    encoder.verify(LayaSpecialTokens.MULTILINGUAL_TOKEN_TEXT)
                } catch (e: IllegalArgumentException) {
                    encoder.close()
                    throw e
                }
            }

        private fun takeMessage(message: CPointer<ByteVar>?): String {
            val text = message?.toKString() ?: "unknown error"
            loupe_tok_string_free(message)
            return text
        }
    }
}
