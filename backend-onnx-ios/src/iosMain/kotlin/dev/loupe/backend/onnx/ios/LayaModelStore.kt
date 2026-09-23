@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.loupe.backend.onnx.ios

import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.TensorNames
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * Where Laya's files live on iOS, and the check that they are the files the parity results were
 * measured on.
 *
 * **No network here.** The weights are not in the app binary (docs/BUILD.md risk 2). Getting them
 * onto the device — a one-time, labelled download after explicit consent — is the app layer's job;
 * this store only says what it expects ([FILES], [directory]), whether it is there ([missing]), and
 * refuses to open anything whose SHA-256 is not the pinned one ([verify]). In development the files
 * are side-loaded with `ios-native/sideload-models.sh`.
 *
 * Hashing the 384 MB graph takes a few seconds on a phone; [open] does it every time on purpose (a
 * truncated or swapped file must never be run). An app that wants to skip re-hashing an unchanged
 * file can call [LayaFiles.open] directly after its own verified-once bookkeeping.
 */
class LayaModelStore(
    /** The directory holding [files]; normally [applicationSupport]'s. */
    val directory: String,
    /** Which graph: one of [VARIANTS]' keys. `int8` (the default) is [FILES]. */
    val variant: String = DEFAULT_VARIANT,
) {
    init {
        require(variant in VARIANTS) { "unknown Laya variant '$variant' (known: ${VARIANTS.keys})" }
    }

    /** The tokenizer and this [variant]'s graph, with their pinned SHA-256s. */
    val files: Map<String, String> = filesFor(variant)

    /** File name of this [variant]'s graph. */
    val graph: String get() = VARIANTS.getValue(variant).first

    /** The expected files that are not present in [directory]. Empty means "ready to verify". */
    fun missing(): List<String> =
        files.keys.filterNot { NSFileManager.defaultManager.fileExistsAtPath(path(it)) }

    /** Absolute path of one of [FILES] in [directory]. */
    fun path(name: String): String = "$directory/$name"

    /**
     * Hashes every expected file and throws [IllegalStateException] naming each missing or
     * mismatched one. Returns the verified paths.
     */
    fun verify(): LayaFiles {
        val problems = mutableListOf<String>()
        for ((name, expected) in files) {
            val file = path(name)
            if (!NSFileManager.defaultManager.fileExistsAtPath(file)) {
                problems += "$name: missing"
                continue
            }
            val actual = sha256(file)
            if (actual != expected) problems += "$name: SHA-256 $actual, expected $expected"
        }
        check(problems.isEmpty()) { "Laya model files in $directory are not usable: ${problems.joinToString("; ")}" }
        return LayaFiles(tokenizer = path(TOKENIZER), graph = path(graph))
    }

    /** [verify], then open a backend over the verified files. The caller must close the result. */
    fun open(): LayaOnDevice = verify().open()

    companion object {
        /** The Hugging Face tokenizer of `convaiinnovations/laya-multilingual` (also pinned in tools/export-laya-onnx.py). */
        const val TOKENIZER: String = "tokenizer.json"

        /** The shipped INT8 export (22 `mlp.Wo` kept FP32); parity recorded in docs/BUILD.md. */
        const val GRAPH: String = "laya-multilingual-choice.int8.onnx"

        /**
         * The pinned SHA-256 of each file. The graph hash is the export the parity results were
         * measured on (tools/laya-export-report.json) — exports are not byte-reproducible, so a
         * re-export means re-measuring parity and changing this pin together.
         */
        val FILES: Map<String, String> = mapOf(
            TOKENIZER to "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f",
            GRAPH to "8b994315135dd7684331bb58fe3a769e3ca1145a5c421a2a41e2b3c85397b2fa",
        )

        /** The default graph variant: the shipped INT8 export. */
        const val DEFAULT_VARIANT: String = "int8"

        /**
         * Every graph variant the app can use: name to (file, pinned SHA-256). `int8-partial` is the
         * opt-in 357 MB graph (11 `mlp.Wo` kept FP32; docs/BUILD.md 2026-09-23, "A better INT8"):
         * same parity result, chosen on the parity questions, so never the default.
         */
        val VARIANTS: Map<String, Pair<String, String>> = mapOf(
            DEFAULT_VARIANT to (GRAPH to "8b994315135dd7684331bb58fe3a769e3ca1145a5c421a2a41e2b3c85397b2fa"),
            "int8-partial" to (
                "laya-multilingual-choice.int8-partial.onnx" to
                    "03d732c31f7da991c6d5b1b816032431de67cc7e0080b17ed63a9053e41be973"
                ),
        )

        /** The tokenizer and [variant]'s graph, with their pins, in a fixed order. */
        fun filesFor(variant: String): Map<String, String> {
            val (graph, sha) = VARIANTS[variant] ?: throw IllegalArgumentException("unknown Laya variant '$variant'")
            return linkedMapOf(TOKENIZER to FILES.getValue(TOKENIZER), graph to sha)
        }

        /** Subdirectory of Application Support the files are expected in. */
        const val SUBDIRECTORY: String = "Loupe/laya-multilingual"

        /**
         * The app's `Library/Application Support/Loupe/laya-multilingual`, created if absent. Not
         * backed up to iCloud is the app layer's call (it owns the download); this only locates it.
         */
        fun applicationSupport(): LayaModelStore = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val base = NSFileManager.defaultManager.URLForDirectory(
                NSApplicationSupportDirectory, NSUserDomainMask, null, true, error.ptr,
            ) ?: throw IllegalStateException("no Application Support directory: ${error.value?.localizedDescription}")
            val dir = checkNotNull(base.path) { "Application Support URL has no path" } + "/" + SUBDIRECTORY
            if (!NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, error.ptr)) {
                throw IllegalStateException("could not create $dir: ${error.value?.localizedDescription}")
            }
            LayaModelStore(dir)
        }

        /** Lower-case hex SHA-256 of the file at [path], streamed in 1 MiB chunks. */
        fun sha256(path: String): String = memScoped {
            val file = fopen(path, "rb") ?: throw IllegalStateException("cannot open $path")
            try {
                val ctx = alloc<CC_SHA256_CTX>()
                CC_SHA256_Init(ctx.ptr)
                val chunk = ByteArray(1 shl 20)
                chunk.usePinned { pinned ->
                    while (true) {
                        val n = fread(pinned.addressOf(0), 1.convert(), chunk.size.convert(), file).toInt()
                        if (n <= 0) break
                        CC_SHA256_Update(ctx.ptr, pinned.addressOf(0), n.convert())
                    }
                }
                val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
                CC_SHA256_Final(digest, ctx.ptr)
                buildString {
                    for (i in 0 until CC_SHA256_DIGEST_LENGTH) append(digest[i].toString(16).padStart(2, '0'))
                }
            } finally {
                fclose(file)
            }
        }
    }
}

/** Paths of a verified tokenizer and graph. */
class LayaFiles(val tokenizer: String, val graph: String) {
    /** Opens the tokenizer (checking its special-token ids) and the graph behind a [LayaOnDevice]. */
    fun open(): LayaOnDevice {
        val encoder = RustSubwordEncoder.openLayaMultilingual(tokenizer)
        try {
            val backend = OrtLayaBackend.open(graph, LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL)), TensorNames.LAYA)
            return LayaOnDevice(backend, encoder)
        } catch (e: Throwable) {
            encoder.close()
            throw e
        }
    }
}

/** Laya ready to score: use [backend] with `DecisionEngine`, and [close] both when done. */
class LayaOnDevice internal constructor(
    val backend: OrtLayaBackend,
    private val encoder: RustSubwordEncoder,
) : AutoCloseable {
    override fun close() {
        backend.close()
        encoder.close()
    }
}
