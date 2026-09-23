package dev.loupe.backend.onnx

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The golden fixture `tools/export-laya-onnx.py` writes: questions run through the Laya authors'
 * own PyTorch code, with the token ids upstream built and the probabilities it produced.
 */
class LayaFixture private constructor(root: JsonObject) {

    class Case(
        val id: String,
        val state: String,
        val question: String,
        val candidates: List<String>,
        /** Each exact string upstream encoded, and the ids its tokenizer produced for it. */
        val segments: Map<String, LongArray>,
        val inputIds: LongArray,
        val markerPositions: LongArray,
        /** Upstream PyTorch probabilities: the reference. */
        val torch: DoubleArray,
    ) {
        /** Top-1 minus top-2 in the reference; small means a near-tie. */
        val referenceMargin: Double
            get() = torch.sortedDescending().let { it[0] - it[1] }

        val referenceArgmax: Int get() = torch.indices.maxBy { torch[it] }
    }

    val maxLen: Int = root["maxLen"].asInt
    val headMaxLen: Int = root["headMaxLen"].asInt
    val special: LayaSpecialTokens = root["specialTokens"].asJsonObject.let {
        LayaSpecialTokens(it["cls"].asLong, it["sep"].asLong, it["mask"].asLong, maskText = "<mask>")
    }
    val cases: List<Case> = root["cases"].asJsonArray.map { element ->
        val c = element.asJsonObject
        Case(
            id = c["id"].asString,
            state = c["state"].asString,
            question = c["question"].asString,
            candidates = c["candidates"].asJsonArray.map { it.asString },
            segments = c["segments"].asJsonArray.associate { s ->
                val o = s.asJsonObject
                o["text"].asString to o["ids"].asJsonArray.map { it.asLong }.toLongArray()
            },
            inputIds = c["inputIds"].asJsonArray.map { it.asLong }.toLongArray(),
            markerPositions = c["markerPositions"].asJsonArray.map { it.asLong }.toLongArray(),
            torch = c["expected"].asJsonObject["torch"].asJsonArray.map { it.asDouble }.toDoubleArray(),
        )
    }

    /**
     * An encoder that answers only for the exact strings upstream encoded. Asking it for anything
     * else throws, which is the point: the Kotlin prompt must request byte-identical text.
     */
    fun recordedEncoder(): SubwordEncoder {
        val all = cases.flatMap { it.segments.entries }.associate { it.key to it.value }
        return SubwordEncoder { text ->
            all[text] ?: throw AssertionError("upstream never encoded this string: \"$text\"")
        }
    }

    companion object {
        fun load(): LayaFixture {
            val stream = LayaFixture::class.java.getResourceAsStream("/laya/golden.json")
                ?: error("golden fixture missing from test resources")
            return stream.bufferedReader(Charsets.UTF_8).use { LayaFixture(JsonParser.parseReader(it).asJsonObject) }
        }

        /** The gitignored models directory; see backend-onnx/build.gradle.kts. */
        fun modelsDir(): Path = Paths.get(System.getProperty("loupe.models.dir") ?: "../models")

        fun tokenizerJson(): Path = modelsDir().resolve("laya-multilingual/tokenizer/tokenizer.json")

        fun graph(variant: String): Path =
            modelsDir().resolve("laya-multilingual-onnx/laya-multilingual-choice.$variant.onnx")

        fun present(path: Path): Boolean = Files.isRegularFile(path)
    }
}
