package dev.loupe.backend.onnx

import dev.loupe.engine.Decision
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.engine.Probability
import dev.loupe.engine.TextState
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real tokenizer and the real exported Laya graphs, against the golden fixture.
 *
 * **Gated.** The tokenizer (34 MB) and the graphs (1.3 GB FP32, ~0.37 GB INT8) are gitignored, so
 * each test is *skipped* — not failed — when its files are absent, and CI stays green without
 * them. Produce them with `tools/export-laya-onnx.py`; a local `./gradlew build` then runs these.
 */
class LayaModelTest {

    private val fixture = LayaFixture.load()

    private fun tokenizerOrSkip(): HuggingFaceSubwordEncoder {
        val path = LayaFixture.tokenizerJson()
        assumeTrue(LayaFixture.present(path), "Laya tokenizer.json not present at $path; skipping")
        return HuggingFaceSubwordEncoder.openLayaMultilingual(path)
    }

    private fun graphOrSkip(variant: String): java.nio.file.Path {
        val path = LayaFixture.graph(variant)
        assumeTrue(LayaFixture.present(path), "Laya $variant graph not present at $path; skipping")
        return path
    }

    @Test
    fun `the DJL tokenizer reproduces every string upstream encoded`() {
        tokenizerOrSkip().use { encoder ->
            var checked = 0
            for (case in fixture.cases) {
                for ((text, ids) in case.segments) {
                    assertContentEquals(ids, encoder.encode(text), "${case.id}: \"${text.take(60)}\"")
                    checked++
                }
            }
            println("DJL tokenizer: $checked segments identical to upstream")
        }
    }

    @Test
    fun `the whole Kotlin input path rebuilds upstream's sequences`() {
        tokenizerOrSkip().use { encoder ->
            val prompt = LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL, fixture.maxLen, fixture.headMaxLen)
            for (case in fixture.cases) {
                val built = prompt.build(case.question, case.state, case.candidates)
                assertContentEquals(case.inputIds, built.inputIds, case.id)
                assertContentEquals(case.markerPositions, built.markerPositions, case.id)
            }
        }
    }

    @Test
    fun `descriptive options - DJL and the Kotlin prompt rebuild upstream's sequences`() {
        val criteria = LayaFixture.loadCriteria()
        tokenizerOrSkip().use { encoder ->
            for (case in criteria.cases) {
                for ((text, ids) in case.segments) assertContentEquals(ids, encoder.encode(text), "${case.id}: \"${text.take(60)}\"")
            }
            val prompt = LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL, criteria.maxLen, criteria.headMaxLen)
            for (case in criteria.cases) {
                val built = prompt.build(case.question, case.state, case.candidates, case.descriptions)
                assertContentEquals(case.inputIds, built.inputIds, case.id)
                assertContentEquals(case.markerPositions, built.markerPositions, case.id)
            }
        }
    }

    @Test
    fun `descriptive options - FP32 graph matches upstream PyTorch`() {
        val report = parity(graphOrSkip("fp32"), LayaFixture.loadCriteria())
        assertEquals(0, report.disagreed.size, "argmax disagreements: ${report.disagreed}")
        assertTrue(report.maxAbsError < 1e-4, "max |p - p_torch| = ${report.maxAbsError}")
    }

    @Test
    fun `descriptive options - INT8 graph agrees wherever the reference is not a near-tie`() {
        val criteria = LayaFixture.loadCriteria()
        val report = parity(graphOrSkip("int8"), criteria)
        val confidentFlips = report.disagreed.filter { id -> criteria.cases.single { it.id == id }.referenceMargin >= NEAR_TIE }
        assertTrue(confidentFlips.isEmpty(), "INT8 flipped confident answers: $confidentFlips")
        assertTrue(report.maxAbsError < 0.15, "max |p - p_torch| = ${report.maxAbsError}")
    }

    @Test
    fun `the tokenizer is loaded with DJL offline, and a mismatched vocabulary is refused`() {
        tokenizerOrSkip().use { encoder ->
            assertTrue(ai.djl.util.Utils.isOfflineMode(), "DJL must be offline once a tokenizer is open")
            assertFailsWith<IllegalArgumentException> { encoder.verify(mapOf("<mask>" to 99L)) }
        }
    }

    @Test
    fun `FP32 graph matches upstream PyTorch on every fixture question`() {
        val report = parity(graphOrSkip("fp32"))
        assertEquals(fixture.cases.size, report.agreements, "argmax disagreements: ${report.disagreed}")
        assertTrue(report.maxAbsError < 1e-4, "max |p - p_torch| = ${report.maxAbsError}")
    }

    @Test
    fun `INT8 graph agrees wherever the reference is not a near-tie`() {
        val report = parity(graphOrSkip("int8"))
        // Quantisation moves probabilities; it must not flip a decision the reference was sure of.
        // A near-tie (top-2 gap under 0.05) can legitimately flip and is reported, not failed.
        val confidentFlips = report.disagreed.filter { id ->
            fixture.cases.single { it.id == id }.referenceMargin >= NEAR_TIE
        }
        assertTrue(confidentFlips.isEmpty(), "INT8 flipped confident answers: $confidentFlips")
        assertTrue(report.maxAbsError < 0.15, "max |p - p_torch| = ${report.maxAbsError}")
    }

    /**
     * The opt-in `int8-partial` graph (tools/quantise-laya-wo.py): 11 of 22 `mlp.Wo` quantised.
     * Its layers were chosen on these very fixtures, so this pins the recorded result rather than
     * proving generalisation — same bar as the shipped INT8, plus its measured max error.
     */
    @Test
    fun `partial-Wo INT8 graph holds the shipped INT8's parity on both fixtures`() {
        val graph = graphOrSkip("int8-partial")
        val criteria = LayaFixture.loadCriteria()
        for ((cases, report) in listOf(fixture.cases.map { it.id to it.referenceMargin } to parity(graph),
            criteria.cases.map { it.id to it.referenceMargin } to parity(graph, criteria))) {
            val margins = cases.toMap()
            val confident = report.disagreed.filter { margins.getValue(it) >= NEAR_TIE }
            assertTrue(confident.isEmpty(), "int8-partial flipped confident answers: $confident")
            assertTrue(report.disagreed.size <= 1, "int8-partial flipped ${report.disagreed}")
            assertTrue(report.maxAbsError < 0.12, "max |p - p_torch| = ${report.maxAbsError}")
        }
    }

    @Test
    fun `a real Laya decision runs through the engine and writes a ledger row`() {
        val graph = graphOrSkip("int8")
        tokenizerOrSkip().use { encoder ->
            val tokenizer = LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL))
            OnnxBackend.open(graph, tokenizer, TensorNames.LAYA).use { backend ->
                val engine = DecisionEngine(backend, threshold = Probability.of(0.5))
                val judgment = Judgment.Choice("team", "Which team should handle this message?",
                    listOf("billing", "technical", "sales"))
                val outcome = engine.decide(judgment, Item("m1", "I was charged twice for invoice 4411. Please refund."))
                val acted = assertIs<Decision.Act>(outcome.decision)
                assertEquals("billing", acted.label)
                assertEquals(null, engine.ledger.rows().single().failure)
            }
        }
    }

    @Test
    fun `a state longer than Laya's context reaches the ledger marked truncated`() {
        val graph = graphOrSkip("int8")
        tokenizerOrSkip().use { encoder ->
            val tokenizer = LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL))
            OnnxBackend.open(graph, tokenizer, TensorNames.LAYA).use { backend ->
                val engine = DecisionEngine(backend, threshold = Probability.of(0.5))
                val judgment = Judgment.Choice("receipt", "Is this a receipt?", listOf("receipt", "not a receipt"))
                // Dense text: ~3,500 characters, inside TextState's 4,000 but far past ~760 tokens.
                val dense = (1..300).joinToString(" ") { "SKU$it 9.99" }
                val long = engine.decide(judgment, Item("long", dense))
                val short = engine.decide(judgment, Item("short", "TOTAL 12.40 VAT 2.07"))

                val cut = long.row.truncation?.modelContext
                assertTrue(cut != null && cut.kept < cut.total, "expected a context cut, got ${long.row.truncation}")
                assertEquals(null, long.row.truncation?.textBudget)
                assertEquals(dev.loupe.engine.Truncation.NONE, short.row.truncation)
            }
        }
    }

    private class Parity(val agreements: Int, val disagreed: List<String>, val maxAbsError: Double)

    private fun parity(graph: java.nio.file.Path, fixture: LayaFixture = this.fixture): Parity {
        tokenizerOrSkip().use { encoder ->
            val tokenizer = LayaTokenizer(
                LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL, fixture.maxLen, fixture.headMaxLen),
            )
            OnnxBackend.open(graph, tokenizer, TensorNames.LAYA).use { backend ->
                var worst = 0.0
                val disagreed = mutableListOf<String>()
                for (case in fixture.cases) {
                    val judgment = Judgment.Choice(case.id, case.question, case.candidates, descriptions = case.descriptionMap)
                    // A budget wide enough that TextState keeps the text verbatim; the model's own
                    // token budget is what this test exercises.
                    val scores = backend.score(judgment, TextState.build(listOf(case.id to case.state), 1_000_000)).masses
                    val probs = case.candidates.map { scores.getValue(it) }
                    val argmax = probs.indices.maxBy { probs[it] }
                    if (argmax != case.referenceArgmax) disagreed += case.id
                    worst = maxOf(worst, probs.indices.maxOf { abs(probs[it] - case.torch[it]) })
                }
                val agreements = fixture.cases.size - disagreed.size
                println("${graph.fileName}: argmax ${agreements}/${fixture.cases.size}, max |dp| = $worst, flipped $disagreed")
                return Parity(agreements, disagreed, worst)
            }
        }
    }

    private companion object {
        const val NEAR_TIE = 0.05
    }
}
