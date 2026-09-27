package dev.loupe.engine

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PortableText, Rx and PortableRegex on every target (JVM, iOS simulator, Android unit tests): the
 * answers the owner decided for digits, spaces and case, and Loupe's pinned Unicode data (B2). The
 * cross-module cases live in tools/parity/corpus.json (loupe-kit's ParityCorpusTest and the device run).
 */
class PortableTextTest {

    private fun s(vararg cps: Int): String = buildString { for (cp in cps) appendCodePoint(this, cp) }

    // ------------------------------------------------------------------------------ B2 parity
    @Test
    fun `the pinned Unicode version is 16`() = assertEquals("16.0.0", PortableText.UNICODE_VERSION)

    @Test
    fun `U+1F130 squared A normalises to A and is flagged as newer than Unicode 3-2`() {
        assertEquals("A", PortableText.nfkc(s(0x1F130)))
        assertEquals("a", PortableText.nfkcCasefold(s(0x1F130)))
        assertEquals("example", idnaToAscii("ex" + s(0x1F130) + "mple"))
        assertEquals(listOf(0x1F130), PortableText.unicode32Drift("ex" + s(0x1F130) + "mple"))
    }

    @Test
    fun `U+2150 one seventh normalises to 1 fraction-slash 7`() {
        assertEquals("1⁄7", PortableText.nfkc("⅐"))
        assertEquals("xn--17-c6t", idnaToAscii("⅐"))
        assertEquals(listOf(0x2150), PortableText.unicode32Drift("⅐"))
    }

    @Test
    fun `U+1FBF0 to U+1FBF9 segmented digits normalise to 0-9`() {
        for (d in 0..9) {
            assertEquals(('0' + d).toString(), PortableText.nfkc(s(0x1FBF0 + d)), "U+1FBF$d")
            assertFalse(PortableText.stableSinceUnicode32(0x1FBF0 + d))
        }
        assertEquals("0123456789", idnaToAscii((0..9).joinToString("") { s(0x1FBF0 + it) }))
    }

    @Test
    fun `U+1CCF0 outlined digits Unicode 16 normalise to 0-9`() {
        for (d in 0..9) assertEquals(('0' + d).toString(), PortableText.nfkc(s(0x1CCF0 + d)), "U+1CCF${d}")
        assertEquals("0x", idnaToAscii(s(0x1CCF0) + "x"))
        assertEquals(listOf(0x1CCF0), PortableText.unicode32Drift(s(0x1CCF0)))
    }

    @Test
    fun `U+1E030 modifier Cyrillic a normalises to Cyrillic a on every platform`() {
        assertEquals("а", PortableText.nfkc(s(0x1E030)))
        assertEquals("xn--80a", idnaToAscii(s(0x1E030)))
        assertEquals(listOf(0x1E030), PortableText.unicode32Drift(s(0x1E030)))
    }

    @Test
    fun `characters of Unicode 3-2 whose mapping never changed are not drift`() {
        for (label in listOf("bücher", "paypal", "مثال", "例子", "пример", "ελληνικά", "한국어", "а")) {
            assertEquals(emptyList(), PortableText.unicode32Drift(label), label)
        }
        // Georgian capitals gained lowercase partners in Unicode 11; the capital sharp s is new in 5.1.
        assertEquals(listOf(0x10A0), PortableText.unicode32Drift("Ⴀ"))
        assertEquals(listOf(0x1E9E), PortableText.unicode32Drift("ẞ"))
    }

    @Test
    fun `normal forms`() {
        assertEquals("é", PortableText.nfc("é"))
        assertEquals("é", PortableText.nfd("é"))
        assertEquals("fi", PortableText.nfkc("ﬁ"))
        assertEquals("각", PortableText.nfc("각"))
        assertEquals("한", PortableText.nfkd("한"))
        assertEquals("ậ", PortableText.nfc("ậ"))
        assertEquals("ậ", PortableText.nfc("ậ"))
        assertEquals("strasse", PortableText.nfkcCasefold("Straße"))
        assertEquals("ab", PortableText.nfkcCasefold("A­B"))
        assertEquals("ascii only", PortableText.nfkc("ascii only"))
    }

    // --------------------------------------------------------------------------------- digits
    @Test
    fun `Arabic-Indic and Persian digits fold to ASCII and parse other scripts do not`() {
        assertEquals("2026-03-15", PortableText.foldDigits("٢٠٢٦-٠٣-١٥"))
        assertEquals("1403", PortableText.foldDigits("۱۴۰۳"))
        assertEquals("१२", PortableText.foldDigits("१२"))
        assertEquals(450L, PortableText.toLongOrNull("٤٥٠"))
        assertEquals(-12L, PortableText.toLongOrNull("-۱۲"))
        assertNull(PortableText.toLongOrNull("१२"))
        assertNull(PortableText.toLongOrNull("١٢x"))
        assertEquals(35, PortableText.toIntOrNull("٣5"))
        assertTrue(PortableText.isDigit('٣') && PortableText.isDigit('۳') && PortableText.isDigit('3'))
        assertFalse(PortableText.isDigit('३'))
    }

    @Test
    fun `dates in Arabic-Indic and Persian digits are read and shown as written`() {
        val found = DateFacts.find("Expires ٢٠٢٧-٠٣-١٥; renew by ۱۵/۰۲/۲۰۲۷")
        assertEquals(listOf(LocalDate(2027, 3, 15), LocalDate(2027, 2, 15)), found.map { it.date })
        assertEquals(listOf("٢٠٢٧-٠٣-١٥", "۱۵/۰۲/۲۰۲۷"), found.map { it.text })
        assertEquals(emptyList(), DateFacts.find("١٢٣٤٥-٠١-٠١"))
        assertEquals(LocalDate(2026, 3, 3), DateFacts.find("3 March 2026").single().date)
    }

    @Test
    fun `labelled amounts in Arabic-Indic digits are terms`() {
        assertEquals(mapOf("annual fee" to 12000L), TermChangeDetector.amounts("Annual fee £١٢٠"))
    }

    // --------------------------------------------------------------------------------- spaces
    @Test
    fun `the space set is ASCII whitespace plus NBSP figure thin and narrow no-break spaces`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), PortableText.splitSpaces("a b c d e"))
        assertEquals(listOf("a　b"), PortableText.splitSpaces(" a　b "))
        assertEquals("x y", PortableText.collapseSpaces("\tx   y\n"))
        assertTrue(Regex(Rx.SP).matches(" "))
        assertFalse(Regex(Rx.SP).matches("　"))
        assertFalse(Regex(Rx.ASCII_SP).matches(" "))
    }

    // ----------------------------------------------------------------------------------- case
    @Test
    fun `case folding pins the Turkish and German cases`() {
        assertEquals("istanbul", PortableText.fold("İSTANBUL"))
        assertEquals("istanbul", PortableText.fold("ıstanbul"))
        assertEquals("straße strasse ß", PortableText.fold("STRAẞE STRASSE ß"))
        assertEquals("k s σ", PortableText.fold("K ſ ς"))
        assertEquals("i̇stanbul", PortableText.lowercase("İSTANBUL"))
        assertEquals("οδοσ", PortableText.lowercase("ΟΔΟΣ"))
        val text = "Ärger İ ß 𐐀"
        assertEquals(text.length, PortableText.fold(text).length)
        assertEquals(text.length, PortableText.matchForm(text).length)
    }

    @Test
    fun `whole-word keywords use the pinned letters and the fold`() {
        assertTrue(PortableText.containsWord("Your İNVOICE", "invoice"))
        assertFalse(PortableText.containsWord("invoices", "invoice"))
        assertFalse(PortableText.containsWord("STRASSE", "straße"))
        assertFalse(PortableText.containsWord("paid٤", "paid"))
        assertTrue(PortableText.containsWord("هذا إيصال", "إيصال"))
        assertFalse(PortableText.containsWord("الإيصال", "إيصال"))
        assertTrue(PortableText.containsWord("۵ days", "5 days"))
    }

    @Test
    fun `ASCII word boundaries are the JDK's`() {
        val re = Regex("${Rx.WB_START}caf${Rx.WB_END}")
        assertFalse(re.containsMatchIn("cafe"))
        assertTrue(re.containsMatchIn("café"))
        assertTrue(re.containsMatchIn("caf_".dropLast(1)))
    }

    @Test
    fun `the shadow keeps length and marks letters numbers and marks`() {
        val text = "é1٣½́-"
        val sh = PortableText.shadow(text)
        assertEquals(text.length, sh.length)
        assertEquals("${PortableText.SHADOW_LETTER}1${PortableText.SHADOW_DIGIT}${PortableText.SHADOW_NUMBER}${PortableText.SHADOW_MARK}-", sh)
        assertEquals(listOf("abc", "٣٤", "é"), PortableText.letterNumberRuns("abc-٣٤ é"))
    }

    // -------------------------------------------------------------------------- user patterns
    @Test
    fun `baseline patterns are translated to spelled-out classes`() {
        assertEquals("invoice 2026", PortableRegex.translate("(?i)Invoice ٢٠٢٦"))
        assertEquals("[${Rx.DIGITS}]", PortableRegex.translate("[\\d]"))
        assertEquals(Rx.SP + "+", PortableRegex.translate("\\s+"))
        assertEquals("(?<name>x)\\k<name>", PortableRegex.translate("(?<name>X)\\k<name>"))
        assertEquals("\\x41\\u0042", PortableRegex.translate("\\x41\\u0042"))
        assertEquals("(?m:a)", PortableRegex.translate("(?mi:A)"))
        for (bad in listOf("\\p{L}", "\\P{Lu}", "[\\D]", "[\\b]", "\\X", "\\R")) {
            assertFailsWith<IllegalArgumentException>(bad) { PortableRegex.translate(bad) }
        }
        val re = Regex(PortableRegex.translate("\\bcopy\\b"))
        assertTrue(re.containsMatchIn(PortableText.matchForm("Report (COPY).pdf")))
        assertFalse(re.containsMatchIn(PortableText.matchForm("copycat")))
    }
}
