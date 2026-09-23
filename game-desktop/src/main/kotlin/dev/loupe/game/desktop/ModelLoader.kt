package dev.loupe.game.desktop

import dev.loupe.backend.onnx.HuggingFaceSubwordEncoder
import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.OnnxBackend
import dev.loupe.backend.onnx.TensorNames
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The Laya INT8 graph and its tokenizer, open. Close it to release ~400 MB of native memory. */
class LoadedModel(val backend: OnnxBackend, private val encoder: HuggingFaceSubwordEncoder, val graph: Path) : AutoCloseable {
    override fun close() {
        backend.close()
        encoder.close()
    }
}

/** Where the model is, and whether it could be loaded. */
sealed interface ModelStatus {
    data object Loading : ModelStatus
    data class Ready(val model: LoadedModel) : ModelStatus
    data class Unavailable(val message: String) : ModelStatus
}

/**
 * Loads Laya exactly as `LayaModelTest` does: the Hugging Face tokenizer through
 * [HuggingFaceSubwordEncoder.openLayaMultilingual] (DJL offline mode enforced, vocabulary verified),
 * [LayaPrompt] with the multilingual special tokens, and [OnnxBackend] over the INT8 graph with
 * [TensorNames.LAYA].
 *
 * Both files are gitignored (`tools/export-laya-onnx.py` produces them). Missing files are not an
 * error: the game still runs, flown by the baseline, and says why.
 */
object ModelLoader {
    fun modelsDir(): Path = Paths.get(System.getProperty("loupe.models.dir") ?: "models")

    fun tokenizerPath(dir: Path = modelsDir()): Path = dir.resolve("laya-multilingual/tokenizer/tokenizer.json")

    fun graphPath(dir: Path = modelsDir()): Path = dir.resolve("laya-multilingual-onnx/laya-multilingual-choice.int8.onnx")

    fun load(dir: Path = modelsDir()): ModelStatus {
        val tokenizer = tokenizerPath(dir)
        val graph = graphPath(dir)
        val missing = listOf(tokenizer, graph).filterNot { Files.isRegularFile(it) }
        if (missing.isNotEmpty()) {
            return ModelStatus.Unavailable(
                "Laya model not found (${missing.joinToString { dir.relativize(it).toString() }} missing under " +
                    "$dir). Flying the baseline autopilot. Run tools/export-laya-onnx.py to produce the model.",
            )
        }
        return try {
            val encoder = HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizer)
            try {
                val backend = OnnxBackend.open(graph, LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL)), TensorNames.LAYA)
                ModelStatus.Ready(LoadedModel(backend, encoder, graph))
            } catch (e: Exception) {
                encoder.close()
                throw e
            }
        } catch (e: Exception) {
            ModelStatus.Unavailable("Laya model failed to load (${e.message ?: e::class.simpleName}). Flying the baseline autopilot.")
        }
    }
}
