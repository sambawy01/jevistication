package dev.loupe.backend.onnx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Laya prompt assembly, checked without the 34 MB tokenizer or the model, so it runs in CI.
 *
 * The fixture half is the strong check: every sequence upstream's own `build_sequence` produced
 * is rebuilt here from the per-segment token ids it recorded, and must match to the token. The
 * recorded encoder throws for any string upstream never encoded, so this also proves the Kotlin
 * side asks for byte-identical text (the prefix, the leading space, the mask scrubbing).
 */
class LayaPromptTest {

    private val fixture = LayaFixture.load()

    @Test
    fun `the fixture covers what it claims to`() {
        assertTrue(fixture.cases.size >= 30, "only ${fixture.cases.size} cases")
        val optionCounts = fixture.cases.map { it.candidates.size }.toSet()
        assertTrue((2..10).all { it in optionCounts }, "option counts covered: $optionCounts")
        assertTrue(fixture.cases.any { it.inputIds.size == fixture.maxLen }, "no over-length case")
        assertEquals(LayaSpecialTokens.MULTILINGUAL, fixture.special)
    }

    @Test
    fun `rebuilds every upstream sequence token for token`() {
        val prompt = LayaPrompt(fixture.recordedEncoder(), fixture.special, fixture.maxLen, fixture.headMaxLen)
        for (case in fixture.cases) {
            val built = prompt.build(case.question, case.state, case.candidates)
            assertContentEquals(case.inputIds, built.inputIds, "input ids differ for ${case.id}")
            assertContentEquals(case.markerPositions, built.markerPositions, "markers differ for ${case.id}")
        }
    }

    @Test
    fun `the over-length state is cut to its prefix and says so`() {
        val prompt = LayaPrompt(fixture.recordedEncoder(), fixture.special, fixture.maxLen, fixture.headMaxLen)
        val long = fixture.cases.single { it.id == "en-long-4" }
        val built = prompt.build(long.question, long.state, long.candidates)
        assertEquals(fixture.maxLen, built.inputIds.size)
        assertTrue(built.stateTruncated)
        assertTrue(built.stateTokensKept < built.stateTokens)
        // The kept tokens are the state's first ones, then the closing separator.
        val stateIds = long.segments.getValue(long.state)
        val start = built.inputIds.size - 1 - built.stateTokensKept
        assertContentEquals(stateIds.copyOf(built.stateTokensKept), built.inputIds.copyOfRange(start, start + built.stateTokensKept))
        assertEquals(fixture.special.sep, built.inputIds.last())

        val short = fixture.cases.single { it.id == "en-receipt-2" }
        assertFalse(prompt.build(short.question, short.state, short.candidates).stateTruncated)
    }

    // ---- the budget rules, against a synthetic encoder: one id per character ------------------

    private val special = LayaSpecialTokens(cls = 2, sep = 1, mask = 4, maskText = "<mask>")

    /** Each character becomes one token (its code point), so lengths are easy to reason about. */
    private val perChar = SubwordEncoder { text -> text.map { it.code.toLong() + 100 }.toLongArray() }

    @Test
    fun `layout is cls, question, sep, marked options, sep, state, sep`() {
        val built = LayaPrompt(perChar, special).build("Q?", "st", listOf("a", "bc"))
        val head = "choice question: Q?".map { it.code.toLong() + 100 }
        val expected = listOf(2L) + head + listOf(1L) +
            listOf(4L, ' '.code + 100L, 'a'.code + 100L) +
            listOf(4L, ' '.code + 100L, 'b'.code + 100L, 'c'.code + 100L) +
            listOf(1L, 's'.code + 100L, 't'.code + 100L, 1L)
        assertContentEquals(expected.toLongArray(), built.inputIds)
        val markerAt = 1 + head.size + 1
        assertContentEquals(longArrayOf(markerAt.toLong(), markerAt + 3L), built.markerPositions)
        built.markerPositions.forEach { assertEquals(4L, built.inputIds[it.toInt()]) }
    }

    @Test
    fun `an option is capped at 48 tokens plus its marker`() {
        val built = LayaPrompt(perChar, special).build("Q", "", listOf("x".repeat(200), "y"))
        val m = built.markerPositions
        assertEquals(1 + LayaPrompt.OPTION_CAP, (m[1] - m[0]).toInt())
    }

    @Test
    fun `many options shrink to the per-option floor and the question keeps at least 8 tokens`() {
        // 63 options of ~23 tokens blow the 256 budget: each is cut to max(4, 240 / 63) = 4,
        // leaving 256 - 252 = 4 for the question, which is then held at its floor of 8.
        val options = (1..63).map { "option number %02d here".format(it) }
        val built = LayaPrompt(perChar, special).build("a long question text here", "state", options)
        val m = built.markerPositions
        assertEquals(63, m.size)
        (1 until m.size).forEach { assertEquals(4L, m[it] - m[it - 1]) }
        assertEquals(1L + 8 + 1, m[0], "question should be cut to its 8-token floor")
    }

    @Test
    fun `too many options to fit the context fail loudly rather than drop candidates`() {
        // 300 options at the 4-token floor need 1,200 tokens: markers fall past 1024.
        val options = (1..300).map { "o$it" }
        val failure = assertFailsWith<IllegalArgumentException> {
            LayaPrompt(perChar, special).build("Q", "", options)
        }
        assertTrue(failure.message!!.contains("300 options"), failure.message)
    }

    @Test
    fun `the literal mask string cannot forge a marker`() {
        val built = LayaPrompt(perChar, special).build("is <mask> here", "a <mask> b", listOf("<mask>", "no"))
        assertEquals(2, built.inputIds.count { it == 4L }, "only the two real option markers may be mask ids")
    }

    @Test
    fun `an empty state still closes the sequence`() {
        val built = LayaPrompt(perChar, special).build("Q", "", listOf("a", "b"))
        assertEquals(0, built.stateTokens)
        assertFalse(built.stateTruncated)
        assertEquals(1L, built.inputIds[built.inputIds.size - 1])
        assertEquals(1L, built.inputIds[built.inputIds.size - 2])
    }

    @Test
    fun `the tokenizer adapter feeds markers and a full attention mask`() {
        val input = LayaTokenizer(LayaPrompt(perChar, special)).encode("Q", "s", listOf("a", "b", "c"))
        assertEquals(3, input.markerPositions!!.size)
        assertTrue(input.attentionMask.all { it == 1L })
        assertEquals(input.inputIds.size, input.attentionMask.size)
    }

    @Test
    fun `tokenized input rejects markers that are out of range or out of order`() {
        assertFailsWith<IllegalArgumentException> {
            TokenizedInput(longArrayOf(1, 2, 3), longArrayOf(1, 1, 1), longArrayOf(1, 3))
        }
        assertFailsWith<IllegalArgumentException> {
            TokenizedInput(longArrayOf(1, 2, 3), longArrayOf(1, 1, 1), longArrayOf(2, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            TokenizedInput(longArrayOf(1, 2, 3), longArrayOf(1, 1, 1), longArrayOf(1, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            TokenizedInput(longArrayOf(1, 2, 3), longArrayOf(1, 1, 1), longArrayOf())
        }
    }
}
