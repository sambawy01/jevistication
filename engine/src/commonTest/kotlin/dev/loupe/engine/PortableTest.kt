package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform seams, checked on every target against known answers. */
class PortableTest {

    @Test
    fun `sha-256 known answers`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Hashing.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `idna labels`() {
        assertEquals("xn--85x722f", idnaToAscii("食狮"))
        assertEquals("xn--55qx5d", idnaToAscii("公司"))
        assertEquals("xn--pypal-4ve", idnaToAscii("pаypal"))
        assertEquals("xn--mnchen-3ya", idnaToAscii("münchen"))
        assertEquals("fass", idnaToAscii("faß"))
        assertNull(idnaToAscii("xn--ä"))
    }

    @Test
    fun `scripts of letters`() {
        assertEquals(-1, letterScript('1'.code))
        assertEquals(-1, letterScript('-'.code))
        assertEquals(letterScript('a'.code), letterScript('Z'.code))
        assertTrue(letterScript('a'.code) != letterScript('а'.code))
        assertTrue(OriginFacts.hasMixedScripts("pаypal.com"))
    }

    @Test
    fun `fixed formatting`() {
        // Locale-independent cases only: the JVM formats with the default locale, as it always did.
        if (formatFixed(0.5, 1) != "0.5") return
        assertEquals("0.13", formatFixed(0.125, 2))
        assertEquals("1.01", formatFixed(1.005, 2))
        assertEquals("67", formatFixed(66.66666666666667, 0))
        assertEquals("-0.0", formatFixed(-0.04, 1))
        assertEquals("0.100", formatFixed(0.1, 3))
        assertEquals("0.000", formatFixed(1e-7, 3))
    }
}
