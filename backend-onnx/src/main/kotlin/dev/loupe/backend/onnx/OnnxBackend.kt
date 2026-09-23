package dev.loupe.backend.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.loupe.engine.Backend
import dev.loupe.engine.Judgment
import dev.loupe.engine.Scored
import dev.loupe.engine.TextState
import java.nio.file.Path

/**
 * A [Backend] backed by ONNX Runtime.
 *
 * It returns the **raw** label-to-mass map (plus the tokenizer's report of any state tokens the
 * context cut), never a validated `Distribution`. That is deliberate:
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

    override fun score(judgment: Judgment.Choice, state: TextState): Scored {
        val encoded = ChoiceScoring.encode(tokenizer, names, judgment, state)
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
                return ChoiceScoring.scored(firstRow(output.value), judgment, encoded)
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
