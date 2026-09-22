package dev.loupe.backend.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.loupe.engine.Backend
import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState
import java.nio.file.Path
import kotlin.math.exp

/** The tensors one scoring call feeds to the model. */
class TokenizedInput(val inputIds: LongArray, val attentionMask: LongArray) {
    init {
        require(inputIds.isNotEmpty()) { "tokenizer produced no tokens" }
        require(inputIds.size == attentionMask.size) {
            "input ids and attention mask must be the same length, were " +
                "${inputIds.size} and ${attentionMask.size}"
        }
    }

    val length: Int get() = inputIds.size
}

/**
 * Turns an item's text and a judgment's candidate labels into model inputs.
 *
 * This is an interface rather than a concrete tokenizer because the exported model decides the
 * vocabulary, the special tokens and how candidate labels are presented to it. Binding one
 * tokenizer here would tie the backend to a single export.
 */
fun interface Tokenizer {
    fun encode(text: String, candidates: List<String>): TokenizedInput
}

/** The tensor names a particular exported model uses. */
data class TensorNames(
    val inputIds: String = "input_ids",
    /** Null when the model takes no mask. */
    val attentionMask: String? = "attention_mask",
    val logits: String = "logits",
)

/**
 * A [Backend] backed by ONNX Runtime.
 *
 * It returns the **raw** label-to-mass map, never a validated `Distribution`. That is deliberate:
 * the engine validating what a backend returns is the A4 boundary, and a backend that handed back
 * something already well-formed would quietly disable it. Everything this class produces is
 * treated as untrusted until the engine has checked it.
 *
 * Failure is by exception, and that is also deliberate. `DecisionEngine` catches anything a
 * backend throws and turns it into an unusable decision honouring the judgment's declared failure
 * posture, so a truncated model file or a mismatched export degrades one item instead of aborting
 * a sweep.
 */
class OnnxBackend(
    private val session: OrtSession,
    private val tokenizer: Tokenizer,
    private val names: TensorNames = TensorNames(),
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Backend, AutoCloseable {

    override fun score(judgment: Judgment.Choice, state: TextState): Map<String, Double> {
        val encoded = tokenizer.encode(state.text, judgment.candidates)
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs[names.inputIds] = OnnxTensor.createTensor(environment, arrayOf(encoded.inputIds))
            names.attentionMask?.let { maskName ->
                inputs[maskName] = OnnxTensor.createTensor(environment, arrayOf(encoded.attentionMask))
            }

            session.run(inputs).use { results ->
                val output = results.get(names.logits).orElseThrow {
                    IllegalStateException(
                        "model has no output named '${names.logits}'; it has ${results.map { it.key }}",
                    )
                }
                val logits = firstRow(output.value)
                require(logits.size == judgment.candidates.size) {
                    "model produced ${logits.size} logits but judgment '${judgment.id}' declares " +
                        "${judgment.candidates.size} candidates"
                }
                return softmax(logits, judgment.candidates)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Closes the session. The shared [OrtEnvironment] is process-wide and is left alone. */
    override fun close() {
        session.close()
    }

    /** The first (and only) batch row of a `[1, labels]` float output. */
    private fun firstRow(value: Any?): FloatArray {
        val rows = value as? Array<*>
            ?: throw IllegalStateException(
                "expected a batched float output, got ${value?.let { it::class.simpleName }}",
            )
        require(rows.size == 1) { "expected a batch of 1, got ${rows.size}" }
        return rows[0] as? FloatArray
            ?: throw IllegalStateException("expected float logits, got ${rows[0]?.let { it::class.simpleName }}")
    }

    /**
     * Softmax over the logits, computed in [Double] and shifted by the maximum.
     *
     * The shift is not cosmetic: `exp` of a large logit overflows to infinity in [Float], and the
     * masses this produces are fed straight into a distribution that must normalise.
     */
    private fun softmax(logits: FloatArray, candidates: List<String>): Map<String, Double> {
        val highest = logits.max().toDouble()
        val exponentials = logits.map { exp(it.toDouble() - highest) }
        val total = exponentials.sum()
        check(total > 0.0 && total.isFinite()) { "logits produced a degenerate softmax: $total" }
        return candidates.indices.associate { i -> candidates[i] to exponentials[i] / total }
    }

    companion object {
        /**
         * Opens [modelPath] and returns a backend over it.
         *
         * The caller owns the result and must [close] it.
         */
        fun open(
            modelPath: Path,
            tokenizer: Tokenizer,
            names: TensorNames = TensorNames(),
        ): OnnxBackend {
            val environment = OrtEnvironment.getEnvironment()
            val session = environment.createSession(
                modelPath.toAbsolutePath().toString(),
                OrtSession.SessionOptions(),
            )
            return OnnxBackend(session, tokenizer, names, environment)
        }
    }
}
