package dev.loupe.backend.onnx

import dev.loupe.engine.Judgment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Descriptive options (upstream's `{label: description}` criteria), checked against
 * `criteria.json` without the tokenizer or model, so it runs in CI. The recorded encoder throws for
 * any string upstream never encoded, so a match proves the Kotlin side renders `"label: description"`
 * byte for byte as upstream's `render_options` does.
 */
class LayaCriteriaPromptTest {

    private val fixture = LayaFixture.loadCriteria()
    private val prompt = LayaPrompt(fixture.recordedEncoder(), fixture.special, fixture.maxLen, fixture.headMaxLen)

    @Test
    fun `rebuilds every upstream descriptive-option sequence token for token`() {
        assertTrue(fixture.cases.size >= 8)
        for (case in fixture.cases) {
            val built = prompt.build(case.question, case.state, case.candidates, case.descriptions)
            assertContentEquals(case.inputIds, built.inputIds, "input ids differ for ${case.id}")
            assertContentEquals(case.markerPositions, built.markerPositions, "markers differ for ${case.id}")
        }
    }

    @Test
    fun `a description past the 48-token cap is cut and reported`() {
        val long = fixture.cases.single { it.id == "crit-long-desc" }
        val built = prompt.build(long.question, long.state, long.candidates, long.descriptions)
        assertTrue(built.optionsTruncated)
        val short = fixture.cases.single { it.id == "crit-receipt-yes" }
        assertFalse(prompt.build(short.question, short.state, short.candidates, short.descriptions).optionsTruncated)
    }

    @Test
    fun `overflowing the head budget shrinks every option and is reported`() {
        val case = fixture.cases.single { it.id == "crit-shrink-12" }
        val built = prompt.build(case.question, case.state, case.candidates, case.descriptions)
        assertTrue(built.optionsTruncated)
        val per = (fixture.headMaxLen - LayaPrompt.QUESTION_FLOOR) / case.candidates.size
        assertEquals(case.candidates.size * (per - 1), built.optionTokensKept)
    }

    @Test
    fun `null and empty descriptions render the bare label, as upstream`() {
        assertEquals("sales", LayaPrompt.renderOption("sales", ""))
        assertEquals("sales", LayaPrompt.renderOption("sales", null))
        assertEquals("billing: refunds", LayaPrompt.renderOption("billing", "refunds"))
        val mixed = fixture.cases.single { it.id == "crit-mixed-3" }
        assertEquals(listOf("billing", "technical", "sales"), mixed.candidates)
        assertEquals(mapOf("billing" to "charges, refunds, invoices"), mixed.descriptionMap)
    }

    @Test
    fun `no descriptions builds exactly the bare-label sequence`() {
        val golden = LayaFixture.load()
        val bare = LayaPrompt(golden.recordedEncoder(), golden.special, golden.maxLen, golden.headMaxLen)
        for (case in golden.cases) {
            val a = bare.build(case.question, case.state, case.candidates)
            val b = bare.build(case.question, case.state, case.candidates, case.candidates.map { null })
            assertContentEquals(a.inputIds, b.inputIds, case.id)
        }
    }

    @Test
    fun `a description count that does not match the options is refused`() {
        val c = fixture.cases.first()
        assertFailsWith<IllegalArgumentException> { prompt.build(c.question, c.state, c.candidates, listOf("x")) }
    }

    @Test
    fun `the backend sends a judgment's descriptions and surfaces the option cut`() {
        val long = fixture.cases.single { it.id == "crit-long-desc" }
        var seen: List<String?>? = null
        val tokenizer = object : Tokenizer {
            val inner = LayaTokenizer(prompt)
            override fun encode(question: String, text: String, candidates: List<String>) =
                inner.encode(question, text, candidates)
            override fun encodeDescribed(question: String, text: String, candidates: List<String>, descriptions: List<String?>): TokenizedInput {
                seen = descriptions
                return inner.encodeDescribed(question, text, candidates, descriptions)
            }
        }
        val input = tokenizer.encodeDescribed(long.question, long.state, long.candidates, long.descriptions)
        assertEquals(long.descriptions, seen)
        val cut = input.optionCut!!
        assertTrue(cut.kept < cut.total)
    }

    @Test
    fun `a tokenizer that cannot show descriptions refuses them rather than dropping them`() {
        val plain = Tokenizer { _, _, _ -> TokenizedInput(longArrayOf(1), longArrayOf(1)) }
        assertFailsWith<UnsupportedOperationException> { plain.encodeDescribed("q", "t", listOf("a", "b"), listOf("d", null)) }
        plain.encodeDescribed("q", "t", listOf("a", "b"), listOf(null, null))
        // And Judgment.Choice keeps descriptions out of its hash only when there are none.
        val bare = Judgment.Choice("j", "Is it?", listOf("a", "b"))
        assertEquals(bare.criteriaHash, Judgment.Choice("j", "Is it?", listOf("a", "b"), descriptions = emptyMap()).criteriaHash)
        assertTrue(bare.criteriaHash != Judgment.Choice("j", "Is it?", listOf("a", "b"), descriptions = mapOf("a" to "x")).criteriaHash)
    }
}
