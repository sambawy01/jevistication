package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin UTS #46 against the Unicode IdnaTestV2.txt answers (generated vectors), on every target. */
class Uts46Test {
    @Test
    fun `every error-free IdnaTestV2 answer is reproduced both modes`() {
        var n = 0
        for ((src, nonTransitional, transitional) in UTS46_TEST_VECTORS) {
            if (nonTransitional != null) {
                val r = Uts46.toAscii(src)
                assertEquals(nonTransitional, r.ascii, "N: $src")
                assertTrue(r.errors.isEmpty(), "N: $src ${r.errors}")
                n++
            }
            if (transitional != null) {
                val r = Uts46.toAscii(src, transitional = true)
                assertEquals(transitional, r.ascii, "T: $src")
                n++
            }
        }
        assertTrue(n > 1000, "only $n answers")
    }

    @Test
    fun `browsers keep the deviations IDNA 2003 maps them`() {
        assertEquals("xn--fa-hia.de", Uts46.toAscii("faß.de").ascii)
        assertEquals("fass.de", Uts46.toAscii("faß.de", transitional = true).ascii)
        assertEquals("xn--americanexpre-ndb.com", Uts46.toAscii("americanexpreß.com").ascii)
        assertEquals("xn--americanexpre-ndb.com", Uts46.toAscii("AMERICANEXPREẞ.COM").ascii)
        // Persian ZWNJ: allowed by CONTEXTJ and kept, as Chrome resolves it
        val persian = Uts46.toAscii("نمونه‌ای.ایران")
        assertEquals("xn--mgb3dcbfe14gp19l.xn--mgba3a4f16a", persian.ascii)
        assertTrue(persian.errors.isEmpty(), persian.errors.toString())
        // a joiner in a Latin word fails CONTEXTJ: reported, not removed
        val zwj = Uts46.toAscii("pay‍pal.com")
        assertTrue("C" in zwj.errors)
        assertTrue(zwj.ascii.startsWith("xn--"), zwj.ascii)
        // full stops of other scripts separate labels
        assertEquals("www.paypal.com.evil.xyz", Uts46.toAscii("www.paypal.com。evil.xyz").ascii)
        assertEquals("paypal.com.evil.com", Uts46.toAscii("paypal.com．evil.com").ascii)
        assertEquals("facebook.com", Uts46.toAscii("facebooK.com").ascii)
    }
}
