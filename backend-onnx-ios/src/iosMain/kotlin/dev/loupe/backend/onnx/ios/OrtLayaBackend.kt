@file:OptIn(ExperimentalForeignApi::class)

package dev.loupe.backend.onnx.ios

import cnames.structs.OrtEnv
import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtStatus
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtValue
import dev.loupe.backend.onnx.ChoiceScoring
import dev.loupe.backend.onnx.TensorNames
import dev.loupe.backend.onnx.Tokenizer
import dev.loupe.backend.onnx.ios.ort.ONNXTensorElementDataType
import dev.loupe.backend.onnx.ios.ort.ORT_API_VERSION
import dev.loupe.backend.onnx.ios.ort.OrtArenaAllocator
import dev.loupe.backend.onnx.ios.ort.OrtApi
import dev.loupe.backend.onnx.ios.ort.OrtGetApiBase
import dev.loupe.backend.onnx.ios.ort.OrtLoggingLevel
import dev.loupe.backend.onnx.ios.ort.OrtMemTypeDefault
import dev.loupe.engine.Backend
import dev.loupe.engine.Judgment
import dev.loupe.engine.Scored
import dev.loupe.engine.TextState
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/**
 * The iOS [Backend] for an exported Laya graph: ONNX Runtime 1.20.0 through its C API (the official
 * iOS package, CPU execution provider, default session options — the same as the JVM
 * `OnnxBackend`), fed by the shared [ChoiceScoring]/`LayaTokenizer` path so both platforms build
 * identical inputs and read logits back identically.
 *
 * As on the JVM it returns the raw masses (the engine validates them, A4) and fails by exception
 * (the engine turns that into the judgment's failure posture). It never touches the network: the
 * graph is a local file the caller hands in (see [LayaModelStore]).
 *
 * `score` may be called from several threads (ORT's `Run` is thread-safe); [close] must not race it.
 */
class OrtLayaBackend private constructor(
    private var session: CPointer<OrtSession>?,
    private val tokenizer: Tokenizer,
    private val names: TensorNames,
) : Backend, AutoCloseable {

    override fun score(judgment: Judgment.Choice, state: TextState): Scored {
        val encoded = ChoiceScoring.encode(tokenizer, names, judgment, state)
        val live = checkNotNull(session) { "backend is closed" }
        val feeds = ArrayList<Pair<String, LongArray>>(3)
        feeds += names.inputIds to encoded.inputIds
        names.attentionMask?.let { feeds += it to encoded.attentionMask }
        names.markerPositions?.let { feeds += it to encoded.markerPositions!! }
        val logits = Ort.run(live, feeds, names.logits)
        return ChoiceScoring.scored(logits, judgment, encoded)
    }

    /** Releases the session. The process-wide ORT environment is left alone. */
    override fun close() {
        session?.let { Ort.api.ReleaseSession!!(it) }
        session = null
    }

    companion object {
        /**
         * Opens the graph at [modelPath] (a local file path) and returns a backend over it. The
         * caller owns the result and must [close] it.
         */
        fun open(modelPath: String, tokenizer: Tokenizer, names: TensorNames = TensorNames.LAYA): OrtLayaBackend =
            OrtLayaBackend(Ort.createSession(modelPath), tokenizer, names)
    }
}

/** The ORT C API: one environment and one CPU memory-info for the process, as ORT recommends. */
internal object Ort {
    val api: OrtApi = run {
        val base = checkNotNull(OrtGetApiBase()) { "OrtGetApiBase returned null" }
        val table = base.pointed.GetApi!!(ORT_API_VERSION.convert())
            ?: error("this ONNX Runtime does not support API version $ORT_API_VERSION")
        table.pointed
    }

    private val env: CPointer<OrtEnv> = memScoped {
        val out = alloc<CPointerVar<OrtEnv>>()
        check(api.CreateEnv!!(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "loupe".cstr.ptr, out.ptr))
        out.value!!
    }

    private val cpu: CPointer<OrtMemoryInfo> = memScoped {
        val out = alloc<CPointerVar<OrtMemoryInfo>>()
        check(api.CreateCpuMemoryInfo!!(OrtArenaAllocator, OrtMemTypeDefault, out.ptr))
        out.value!!
    }

    fun createSession(modelPath: String): CPointer<OrtSession> = memScoped {
        val options = alloc<CPointerVar<OrtSessionOptions>>()
        check(api.CreateSessionOptions!!(options.ptr))
        try {
            val out = alloc<CPointerVar<OrtSession>>()
            check(api.CreateSession!!(env, modelPath.cstr.ptr, options.value, out.ptr))
            out.value!!
        } finally {
            api.ReleaseSessionOptions!!(options.value)
        }
    }

    /** Runs [session] on int64 `[1, n]` [feeds] and returns the `[1, k]` float output [output]'s row. */
    fun run(session: CPointer<OrtSession>, feeds: List<Pair<String, LongArray>>, output: String): FloatArray = memScoped {
        val values = allocArray<CPointerVar<OrtValue>>(feeds.size)
        val inputNames = allocArray<CPointerVar<ByteVar>>(feeds.size)
        try {
            feeds.forEachIndexed { i, (name, data) ->
                inputNames[i] = name.cstr.ptr
                values[i] = int64Row(data)
            }
            val outputNames = allocArray<CPointerVar<ByteVar>>(1)
            outputNames[0] = output.cstr.ptr
            val outputs = allocArray<CPointerVar<OrtValue>>(1)
            check(
                api.Run!!(session, null, inputNames, values, feeds.size.convert(), outputNames, 1.convert(), outputs),
            )
            val result = outputs[0]!!
            try {
                firstRow(result)
            } finally {
                api.ReleaseValue!!(result)
            }
        } finally {
            for (i in feeds.indices) values[i]?.let { api.ReleaseValue!!(it) }
        }
    }

    /** A `[1, n]` int64 tensor over memory owned by the enclosing [MemScope]. */
    private fun MemScope.int64Row(data: LongArray): CPointer<OrtValue> {
        val buffer = allocArray<LongVar>(data.size)
        data.forEachIndexed { i, v -> buffer[i] = v }
        val shape = allocArray<LongVar>(2)
        shape[0] = 1
        shape[1] = data.size.toLong()
        val out = alloc<CPointerVar<OrtValue>>()
        check(
            api.CreateTensorWithDataAsOrtValue!!(
                cpu, buffer, (data.size * Long.SIZE_BYTES).convert(), shape, 2.convert(),
                ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, out.ptr,
            ),
        )
        return out.value!!
    }

    private fun firstRow(value: CPointer<OrtValue>): FloatArray = memScoped {
        val info = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
        check(api.GetTensorTypeAndShape!!(value, info.ptr))
        val dims: LongArray
        try {
            val type = alloc<ONNXTensorElementDataType.Var>()
            check(api.GetTensorElementType!!(info.value, type.ptr))
            check(type.value == ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
                "expected float logits, got ${type.value}"
            }
            val count = alloc<ULongVar>()
            check(api.GetDimensionsCount!!(info.value, count.ptr))
            val n = count.value.toInt()
            val raw = allocArray<LongVar>(maxOf(1, n))
            check(api.GetDimensions!!(info.value, raw, count.value))
            dims = LongArray(n) { raw[it] }
        } finally {
            api.ReleaseTensorTypeAndShapeInfo!!(info.value)
        }
        check(dims.size == 2 && dims[0] == 1L) { "expected a batched [1, k] output, got ${dims.contentToString()}" }
        val data = alloc<COpaquePointerVar>()
        check(api.GetTensorMutableData!!(value, data.ptr))
        val floats = data.value!!.reinterpret<FloatVar>()
        FloatArray(dims[1].toInt()) { floats[it] }
    }

    /** Throws with ORT's message when [status] is an error (non-null), releasing it. */
    private fun check(status: CPointer<OrtStatus>?) {
        if (status == null) return
        val message = api.GetErrorMessage!!(status)?.toKString()
        api.ReleaseStatus!!(status)
        throw IllegalStateException("ONNX Runtime: $message")
    }
}
