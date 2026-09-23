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
class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    /**
     * For a model that scores each candidate at its own position in the sequence (Laya scores
     * each option at a `<mask>` marker), the index of each candidate's marker, in candidate
     * order. Null for a model that emits one logit per candidate from the sequence as a whole.
     */
    val markerPositions: LongArray? = null,
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
        val encoded = tokenizer.encode(judgment.question, state.text, judgment.candidates)
        // A tokenizer and a graph that disagree about markers are a wiring mistake, not a model
        // answer: feeding markers to a graph that ignores them, or omitting them from one that
        // needs them, would still produce numbers. Refuse before running anything.
        require((names.markerPositions == null) == (encoded.markerPositions == null)) {
            if (names.markerPositions == null) {
                "tokenizer produced marker positions but the model takes no marker input"
            } else {
                "model takes marker input '${names.markerPositions}' but the tokenizer produced none"
            }
        }
        encoded.markerPositions?.let { markers ->
            require(markers.size == judgment.candidates.size) {
                "tokenizer produced ${markers.size} marker positions but judgment " +
                    "'${judgment.id}' declares ${judgment.candidates.size} candidates"
            }
        }
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs[names.inputIds] = OnnxTensor.createTensor(environment, arrayOf(encoded.inputIds))
            names.attentionMask?.let { maskName ->
                inputs[maskName] = OnnxTensor.createTensor(environment, arrayOf(encoded.attentionMask))
            }
            names.markerPositions?.let { markerName ->
                inputs[markerName] = OnnxTensor.createTensor(environment, arrayOf(encoded.markerPositions))
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
