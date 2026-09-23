@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.loupe.backend.onnx.ios

import dev.loupe.backend.onnx.LayaSpecialTokens
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv

/**
 * The same golden fixtures `backend-onnx`'s JVM tests read (backend-onnx/src/test/resources/laya),
 * read from the host filesystem: the simulator test process sees the Mac's files, and the Gradle
 * task passes both directories in (see build.gradle.kts).
 */
class IosLayaFixture private constructor(root: JsonObject) {

    class Case(
        val id: String,
        val state: String,
        val question: String,
        val candidates: List<String>,
        val descriptions: List<String?>,
        val segments: Map<String, LongArray>,
        val inputIds: LongArray,
        val markerPositions: LongArray,
        val torch: DoubleArray,
        /** ONNX Runtime CPU on the same INT8 graph, off-device: what the JVM backend reproduces. */
        val int8: DoubleArray,
    ) {
        val descriptionMap: Map<String, String>
            get() = candidates.indices.mapNotNull { i ->
                descriptions.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { candidates[i] to it }
            }.toMap()
        val referenceMargin: Double get() = torch.sortedDescending().let { it[0] - it[1] }
        val torchArgmax: Int get() = torch.indices.maxBy { torch[it] }
        val int8Argmax: Int get() = int8.indices.maxBy { int8[it] }
    }

    val maxLen: Int = root["maxLen"]!!.jsonPrimitive.int
    val headMaxLen: Int = root["headMaxLen"]!!.jsonPrimitive.int
    val special: LayaSpecialTokens = root["specialTokens"]!!.jsonObject.let {
        LayaSpecialTokens(it.long("cls"), it.long("sep"), it.long("mask"), maskText = "<mask>")
    }
    val cases: List<Case> = root["cases"]!!.jsonArray.map { element ->
        val c = element.jsonObject
        val expected = c["expected"]!!.jsonObject
        Case(
            id = c.string("id"),
            state = c.string("state"),
            question = c.string("question"),
            candidates = c["candidates"]!!.jsonArray.map { it.jsonPrimitive.content },
            descriptions = (c["descriptions"] as? JsonArray)?.map { if (it is JsonNull) null else it.jsonPrimitive.content }
                ?: emptyList(),
            segments = c["segments"]!!.jsonArray.associate { s ->
                val o = s.jsonObject
                o.string("text") to o["ids"]!!.jsonArray.longs()
            },
            inputIds = c["inputIds"]!!.jsonArray.longs(),
            markerPositions = c["markerPositions"]!!.jsonArray.longs(),
            torch = expected["torch"]!!.jsonArray.doubles(),
            int8 = expected["int8"]!!.jsonArray.doubles(),
        )
    }

    companion object {
        fun load(name: String): IosLayaFixture {
            val dir = env("LOUPE_FIXTURES_DIR") ?: error("LOUPE_FIXTURES_DIR is not set (run through Gradle)")
            val path = "$dir/$name"
            val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
                ?: error("cannot read fixture $path")
            return IosLayaFixture(Json.parseToJsonElement(text).jsonObject)
        }

        fun golden(): IosLayaFixture = load("golden.json")
        fun criteria(): IosLayaFixture = load("criteria.json")

        fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

        fun modelsDir(): String? = env("LOUPE_MODELS_DIR")

        fun tokenizerJson(): String? = modelsDir()?.let { "$it/laya-multilingual/tokenizer/tokenizer.json" }

        fun graph(variant: String): String? =
            modelsDir()?.let { "$it/laya-multilingual-onnx/laya-multilingual-choice.$variant.onnx" }

        fun present(path: String?): Boolean = path != null && NSFileManager.defaultManager.fileExistsAtPath(path)

        private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
        private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long
        private fun JsonArray.longs() = map { it.jsonPrimitive.long }.toLongArray()
        private fun JsonArray.doubles() = map { it.jsonPrimitive.double }.toDoubleArray()
    }
}
