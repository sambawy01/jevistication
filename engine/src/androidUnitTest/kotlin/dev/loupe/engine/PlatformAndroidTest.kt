package dev.loupe.engine

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Android actuals (Platform.android.kt), on the host JVM. The commonTest suite also runs here
 * against them; these add the Android-specific promises: the embedded snapshot is the recorded one,
 * and nothing depends on the phone's locale.
 */
class PlatformAndroidTest {
    private val savedLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(savedLocale)

    @Test
    fun `sha256 matches the FIPS 180-2 vectors`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
    }

    @Test
    fun `embedded public suffix list hashes to the recorded SHA-256`() {
        val recorded = bundledPublicSuffixListSha256().trim().substringBefore(' ')
        assertEquals(64, recorded.length)
        assertEquals(recorded, hex(sha256(bundledPublicSuffixList().encodeToByteArray())))
    }

    @Test
    fun `idna gives the punycode the JVM gives`() {
        assertEquals("xn--bcher-kva", idnaToAscii("bücher"))
        assertEquals("xn--pypal-4ve", idnaToAscii("pаypal"))
        assertEquals("fass", idnaToAscii("faß"))
    }

    @Test
    fun `letter scripts separate Latin from Cyrillic and skip non-letters`() {
        assertNotEquals(letterScript('a'.code), letterScript('а'.code))
        assertEquals(letterScript('a'.code), letterScript('Z'.code))
        assertEquals(-1, letterScript('1'.code))
        assertEquals(-1, letterScript('-'.code))
    }

    @Test
    fun `formatFixed prints ASCII digits under an Arabic locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("0.50", formatFixed(0.5, 2))
        assertEquals("1234.568", formatFixed(1234.5678, 3))
        assertTrue(formatFixed(12.25, 1).all { it.code < 128 })
    }
}
