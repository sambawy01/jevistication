package dev.loupe.backend.onnx

import dev.loupe.engine.Decision
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Extent
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.engine.Probability
import dev.loupe.engine.TextState
import dev.loupe.engine.Truncation
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs real ONNX Runtime inference against the synthetic graph in test resources.
 *
 * The graph is not a model of anything — the real weights cannot be fetched in this environment —
 * but it exercises the whole path that matters: session loading, tensor marshalling, output
 * reading, softmax and the candidate-count check.
 */
class OnnxBackendTest {

    private val receipt = Judgment.Choice("is-receipt", "Is this a receipt?", listOf("yes", "no"))

    /** Characters as token ids. Enough to make the synthetic graph's output depend on the text. */
    private val tokenizer = Tokenizer { _, text, _ ->
        val ids = text.take(64).map { it.code.toLong() }.toLongArray()
        val safe = if (ids.isEmpty()) longArrayOf(1L) else ids
        TokenizedInput(safe, LongArray(safe.size) { 1L })
    }

    /** [tokenizer], but reporting its 64-character cap as a context cut, as `LayaTokenizer` does. */
    private val reporting = Tokenizer { q, text, c ->
        val base = tokenizer.encode(q, text, c)
        TokenizedInput(base.inputIds, base.attentionMask, stateTokens = text.length, stateTokensKept = minOf(text.length, 64))
    }

    private fun modelPath(): Path =
        Paths.get(javaClass.getResource("/synthetic-classifier.onnx")!!.toURI())

    private var backend: OnnxBackend? = null

    private fun open(names: TensorNames = TensorNames()): OnnxBackend =
        OnnxBackend.open(modelPath(), tokenizer, names).also { backend = it }

    @AfterTest
    fun tearDown() {
        backend?.close()
        backend = null
    }

    private fun state(text: String) = TextState.build(listOf("i" to text), budget = 4_000)

    @Test
    fun `scores a judgment and returns a mass for every candidate`() {
        val scores = open().score(receipt, state("TOTAL 12.40 VAT 2.07")).masses

        assertEquals(setOf("yes", "no"), scores.keys)
        assertTrue(scores.values.all { it in 0.0..1.0 }, "masses out of range: $scores")
        assertEquals(1.0, scores.values.sum(), 1e-9)
    }

    @Test
    fun `the output actually depends on the input`() {
        val b = open()
        val short = b.score(receipt, state("a")).masses
        val long = b.score(receipt, state("a much longer receipt with many more characters")).masses
        assertTrue(
            short["yes"] != long["yes"],
            "the graph should respond to its input; got $short and $long",
        )
    }

    @Test
    fun `scoring the same text twice is deterministic`() {
        val b = open()
        val first = b.score(receipt, state("TOTAL 12.40")).masses
        val second = b.score(receipt, state("TOTAL 12.40")).masses
        assertEquals(first, second)
    }

    @Test
    fun `rejects a judgment whose candidate count the model cannot produce`() {
        val threeWay = Judgment.Choice("kind", "Which is it?", listOf("bill", "receipt", "statement"))
        val failure = assertFailsWith<IllegalArgumentException> {
            open().score(threeWay, state("something"))
        }
        assertTrue(failure.message!!.contains("2 logits"), failure.message!!)
        assertTrue(failure.message!!.contains("3 candidates"), failure.message!!)
    }

    @Test
    fun `reports a model whose output is named differently`() {
        val wrong = open(TensorNames(logits = "not_the_output"))
        val failure = assertFailsWith<IllegalStateException> { wrong.score(receipt, state("x")) }
        assertTrue(failure.message!!.contains("not_the_output"), failure.message!!)
    }

    @Test
    fun `drives a real decision through the engine, ledger row included`() {
        val engine = DecisionEngine(open(), threshold = Probability.of(0.5))
        val outcome = engine.decide(receipt, Item("photo-1", "TOTAL 12.40 VAT 2.07"))

        // Whatever the synthetic graph says, this is a genuine end-to-end decision.
        assertTrue(outcome.decision is Decision.Act || outcome.decision is Decision.Abstain)
        assertEquals(1, engine.ledger.size)

        val row = engine.ledger.rows().single()
        assertEquals("is-receipt", row.judgmentId)
        assertEquals(receipt.criteriaHash, row.criteriaHash)
        assertEquals(setOf("yes", "no"), row.distribution.labels)
        assertEquals(1.0, row.propensity.value)
        assertEquals(null, row.failure)
    }

    @Test
    fun `a model mismatched to the judgment degrades one item instead of aborting`() {
        // The backend throws; the engine contains it as an unusable decision honouring the
        // judgment's declared posture. This is the property that lets a sweep survive a bad export.
        val threeWay = Judgment.Choice("kind", "Which is it?", listOf("bill", "receipt", "statement"))
        val engine = DecisionEngine(open(), threshold = Probability.of(0.5))

        val outcome = engine.decide(threeWay, Item("a", "x"))
        val unusable = assertIs<Decision.Unusable>(outcome.decision)
        assertTrue(unusable.reason.contains("candidates"), unusable.reason)
        assertEquals(1, engine.ledger.size)
        assertTrue(engine.ledger.rows().single().failure != null)
    }

    @Test
    fun `a sweep continues past a bad item`() {
        val threeWay = Judgment.Choice("kind", "Which is it?", listOf("bill", "receipt", "statement"))
        val engine = DecisionEngine(open(), threshold = Probability.of(0.5))

        // Alternate a workable judgment with one the model cannot answer.
        repeat(5) { i ->
            engine.decide(receipt, Item("ok-$i", "TOTAL $i"))
            engine.decide(threeWay, Item("bad-$i", "TOTAL $i"))
        }
        assertEquals(10, engine.ledger.size)
        assertEquals(5, engine.ledger.rows().count { it.failure != null })
    }

    @Test
    fun `tokenized input rejects a mismatched mask`() {
        assertFailsWith<IllegalArgumentException> {
            TokenizedInput(longArrayOf(1, 2, 3), longArrayOf(1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            TokenizedInput(longArrayOf(), longArrayOf())
        }
    }

    @Test
    fun `tokenized input exposes what it holds`() {
        val input = TokenizedInput(longArrayOf(5, 6), longArrayOf(1, 1))
        assertEquals(2, input.length)
        assertContentEquals(longArrayOf(5, 6), input.inputIds)
    }

    @Test
    fun `a state the context cut reaches the ledger marked truncated, a short one does not`() {
        OnnxBackend.open(modelPath(), reporting).use { b ->
            val engine = DecisionEngine(b, threshold = Probability.of(0.0))
            val long = engine.decide(receipt, Item("long", "x".repeat(200)))
            val short = engine.decide(receipt, Item("short", "TOTAL 12.40"))

            assertEquals(Truncation(modelContext = Extent(64, 200, Extent.Measure.TOKENS)), long.row.truncation)
            assertTrue(long.row.truncated)
            // NULL_ACTION: an answer about part of the item is not acted on; it queues.
            assertIs<Decision.Abstain>(long.decision)
            assertEquals(Truncation.NONE, short.row.truncation)
            assertIs<Decision.Act>(short.decision)
        }
    }
}
