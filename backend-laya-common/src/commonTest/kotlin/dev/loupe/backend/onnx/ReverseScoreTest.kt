package dev.loupe.backend.onnx

import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Station's REVERSE_SCORE_ON rule (issue #8, upstream laya #131) in the shared scoring path. */
class ReverseScoreTest {

    private class Recording : Tokenizer {
        var candidates: List<String> = emptyList()
        var descriptions: List<String?> = emptyList()
        override fun encode(question: String, text: String, candidates: List<String>): TokenizedInput {
            this.candidates = candidates
            this.descriptions = candidates.map { null }
            return TokenizedInput(LongArray(4), LongArray(4) { 1 }, LongArray(candidates.size) { it.toLong() })
        }
        override fun encodeDescribed(question: String, text: String, candidates: List<String>, descriptions: List<String?>): TokenizedInput {
            val t = encode(question, text, candidates)
            this.descriptions = descriptions
            return t
        }
    }

    private val names = TensorNames(markerPositions = "marker_pos")
    private val bands = listOf("no action needed", "this month", "this week", "today")
    private val score = Judgment.Choice("urgency", "How soon does it need my attention?", listOf("0", "1", "2", "3"),
        descriptions = listOf("0", "1", "2", "3").zip(bands).toMap(), ordinal = true)

    @Test
    fun matchesStationsConstant() {
        assertEquals(setOf("english", "multilingual"), LayaRuntimeRules.REVERSE_SCORE_ON)
        assertTrue(LayaRuntimeRules.reversesScore(score))
        assertTrue(!LayaRuntimeRules.reversesScore(score, checkpoint = "other"))
    }

    @Test
    fun aScoreIsSentHighestFirstAndMappedBack() {
        val tok = Recording()
        val encoded = ChoiceScoring.encode(tok, names, score, TextState.build(listOf("body" to "the boiler is leaking"), 1000))
        assertEquals(listOf("3", "2", "1", "0"), tok.candidates)
        assertEquals(bands.reversed(), tok.descriptions)
        // Sent order: "3" (today) gets the highest logit.
        val masses = ChoiceScoring.scored(floatArrayOf(4f, 1f, 0f, -1f), score, encoded).masses
        assertEquals("3", masses.maxBy { it.value }.key)
        assertTrue(masses.getValue("0") < masses.getValue("1"))
        assertEquals(listOf("0", "1", "2", "3"), masses.keys.toList())
    }

    @Test
    fun choicesAndYesNoAreUntouched() {
        val tok = Recording()
        val pick = Judgment.Choice("kind", "Which kind?", listOf("a", "b", "c"))
        val encoded = ChoiceScoring.encode(tok, names, pick, TextState.build(listOf("body" to "x"), 1000))
        assertEquals(listOf("a", "b", "c"), tok.candidates)
        assertEquals("a", ChoiceScoring.scored(floatArrayOf(3f, 0f, 0f), pick, encoded).masses.maxBy { it.value }.key)
        assertTrue(Judgment.Score("s", "How much?", 1..3).asChoice.ordinal)
    }

    @Test
    fun theFlagDoesNotChangeTheCriteriaHash() {
        assertEquals(score.copy(ordinal = false).criteriaHash, score.criteriaHash)
    }
}
