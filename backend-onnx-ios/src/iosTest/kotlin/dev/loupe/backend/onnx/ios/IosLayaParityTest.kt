package dev.loupe.backend.onnx.ios

import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.TensorNames
import dev.loupe.engine.Decision
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.engine.Probability
import dev.loupe.engine.TextState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Laya on the iOS simulator — the Rust tokenizer, the shared prompt and ONNX Runtime iOS — against
 * the JVM's golden fixtures.
 *
 * **Gated** like `backend-onnx`'s `LayaModelTest`: without the (gitignored) tokenizer and INT8
 * graph under models/ each test prints why and returns, so a clean checkout stays green. Kotlin/Native
 * has no assumption-skip, so a skipped test reports as passed; the "SKIPPED" line is the record.
 */
class IosLayaParityTest {

    private fun tokenizerOrNull(): RustSubwordEncoder? {
        val path = IosLayaFixture.tokenizerJson()
        if (!IosLayaFixture.present(path)) {
            println("SKIPPED: Laya tokenizer.json not present at $path")
            return null
        }
        return RustSubwordEncoder.openLayaMultilingual(path!!)
    }

    private fun graphOrNull(variant: String): String? {
        val path = IosLayaFixture.graph(variant)
        if (!IosLayaFixture.present(path)) {
            println("SKIPPED: Laya $variant graph not present at $path")
            return null
        }
        return path
    }

    @Test
    fun theRustTokenizerReproducesEveryStringUpstreamEncoded() {
        val encoder = tokenizerOrNull() ?: return
        encoder.use {
            var checked = 0
            for (fixture in listOf(IosLayaFixture.golden(), IosLayaFixture.criteria())) {
                for (case in fixture.cases) {
                    for ((text, ids) in case.segments) {
                        assertContentEquals(ids, encoder.encode(text), "${case.id}: \"${text.take(60)}\"")
                        checked++
                    }
                }
            }
            println("iOS Rust tokenizer: $checked segments identical to upstream")
        }
    }

    @Test
    fun theRustTokenizerMatchesLaya0320OnSixLanguageStrings() {
        val encoder = tokenizerOrNull() ?: return
        encoder.use {
            val cases = IosLayaFixture.tokenizerStrings()
            for ((lang, text, ids) in cases) {
                assertContentEquals(ids, encoder.encode(text), "$lang: \"${text.take(60)}\"")
            }
            println("iOS Rust tokenizer: ${cases.size} multilingual strings identical to laya 0.3.20")
        }
    }

    @Test
    fun theSharedPromptRebuildsUpstreamSequencesOnIos() {
        val encoder = tokenizerOrNull() ?: return
        encoder.use {
            for (fixture in listOf(IosLayaFixture.golden(), IosLayaFixture.criteria())) {
                val prompt = LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL, fixture.maxLen, fixture.headMaxLen)
                for (case in fixture.cases) {
                    val built = prompt.build(case.question, case.state, case.candidates, case.descriptions)
                    assertContentEquals(case.inputIds, built.inputIds, case.id)
                    assertContentEquals(case.markerPositions, built.markerPositions, case.id)
                }
            }
        }
    }

    @Test
    fun int8OnIosSelectsTheSameAnswersAsTheJvmOnGolden() = assertParity("golden", IosLayaFixture.golden())

    @Test
    fun int8OnIosSelectsTheSameAnswersAsTheJvmOnCriteria() = assertParity("criteria", IosLayaFixture.criteria())

    /** Station's REVERSE_SCORE_ON: levels sent highest first, answers mapped back (score-reversed.json). */
    @Test
    fun int8OnIosSelectsTheSameAnswersAsTheJvmOnReversedScores() =
        assertParity("score-reversed", IosLayaFixture.load("score-reversed.json"), ordinal = true)

    private fun assertParity(name: String, fixture: IosLayaFixture, ordinal: Boolean = false) {
        val graph = graphOrNull("int8") ?: return
        val encoder = tokenizerOrNull() ?: return
        encoder.use {
            val tokenizer = LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL, fixture.maxLen, fixture.headMaxLen))
            OrtLayaBackend.open(graph, tokenizer, TensorNames.LAYA).use { backend ->
                var worstVsInt8 = 0.0
                var worstVsTorch = 0.0
                val differentFromJvm = mutableListOf<String>()
                val flippedVsTorch = mutableListOf<String>()
                var elapsedMs = 0.0
                for (case in fixture.cases) {
                    val judgment = Judgment.Choice(case.id, case.question, case.candidates, descriptions = case.descriptionMap, ordinal = ordinal)
                    val state = TextState.build(listOf(case.id to case.state), 1_000_000)
                    val start = TimeSource.Monotonic.markNow()
                    val masses = backend.score(judgment, state).masses
                    elapsedMs += start.elapsedNow().inWholeMicroseconds / 1000.0
                    val probs = case.candidates.map { masses.getValue(it) }
                    val argmax = probs.indices.maxBy { probs[it] }
                    if (argmax != case.int8Argmax) differentFromJvm += case.id
                    if (argmax != case.torchArgmax) flippedVsTorch += case.id
                    worstVsInt8 = maxOf(worstVsInt8, probs.indices.maxOf { abs(probs[it] - case.int8[it]) })
                    worstVsTorch = maxOf(worstVsTorch, probs.indices.maxOf { abs(probs[it] - case.torch[it]) })
                }
                val n = fixture.cases.size
                println(
                    "iOS-sim INT8 $name: same answer as JVM INT8 ${n - differentFromJvm.size}/$n, " +
                        "max |p - p_int8| = $worstVsInt8; vs torch argmax ${n - flippedVsTorch.size}/$n, " +
                        "max |p - p_torch| = $worstVsTorch, flipped $flippedVsTorch; " +
                        "mean ${elapsedMs / n} ms/item (simulator, not a phone)",
                )
                assertTrue(differentFromJvm.isEmpty(), "iOS picked a different answer than JVM INT8 on $differentFromJvm")
                assertTrue(worstVsInt8 < 1e-3, "max |p - p_int8| = $worstVsInt8")
            }
        }
    }

    @Test
    fun aRealLayaDecisionRunsThroughTheEngineOnIos() {
        val graph = graphOrNull("int8") ?: return
        val tokenizer = IosLayaFixture.tokenizerJson()!!
        if (!IosLayaFixture.present(tokenizer)) return
        LayaFiles(tokenizer = tokenizer, graph = graph).open().use { laya ->
            val engine = DecisionEngine(laya.backend, threshold = Probability.of(0.5))
            val judgment = Judgment.Choice("team", "Which team should handle this message?", listOf("billing", "technical", "sales"))
            val outcome = engine.decide(judgment, Item("m1", "I was charged twice for invoice 4411. Please refund."))
            assertEquals("billing", assertIs<Decision.Act>(outcome.decision).label)
            assertEquals(null, engine.ledger.rows().single().failure)
        }
    }

    @Test
    fun aMismatchedVocabularyIsRefused() {
        val encoder = tokenizerOrNull() ?: return
        encoder.use { assertFailsWith<IllegalArgumentException> { it.verify(mapOf("<mask>" to 99L)) } }
    }
}
