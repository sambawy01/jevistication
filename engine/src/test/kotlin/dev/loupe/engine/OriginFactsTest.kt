package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginFactsTest {

    @Test
    fun `extracts the host from a url with or without a scheme`() {
        assertEquals("example.com", OriginFacts.host("https://example.com/path?q=1"))
        assertEquals("example.com", OriginFacts.host("example.com"))
        assertEquals("example.com", OriginFacts.host("HTTPS://Example.COM"))
    }

    @Test
    fun `returns null for something with no host`() {
        assertNull(OriginFacts.host("not a url at all"))
        assertNull(OriginFacts.host(""))
    }

    @Test
    fun `finds the registrable domain including multi-label suffixes`() {
        assertEquals("paypal.com", OriginFacts.registrableDomain("www.paypal.com"))
        assertEquals("paypal.com", OriginFacts.registrableDomain("paypal.com"))
        assertEquals("hsbc.co.uk", OriginFacts.registrableDomain("secure.hsbc.co.uk"))
        assertNull(OriginFacts.registrableDomain("localhost"))
    }

    @Test
    fun `a brand on its own domain matches`() {
        assertTrue(OriginFacts.brandMatchesOrigin("PayPal", "paypal.com"))
        assertTrue(OriginFacts.brandMatchesOrigin("PayPal", "www.paypal.com"))
        assertTrue(OriginFacts.brandMatchesOrigin("HSBC", "secure.hsbc.co.uk"))
    }

    @Test
    fun `a brand parked on someone else's domain does not match`() {
        // The whole attack: the brand is present, but the domain belongs to someone else.
        assertFalse(OriginFacts.brandMatchesOrigin("PayPal", "paypal.secure-login.com"))
        assertFalse(OriginFacts.brandMatchesOrigin("PayPal", "paypal-login.com"))
        assertFalse(OriginFacts.brandMatchesOrigin("HSBC", "hsbc.fake.co.uk"))
    }

    @Test
    fun `an empty brand never matches`() {
        assertFalse(OriginFacts.brandMatchesOrigin("", "paypal.com"))
        assertFalse(OriginFacts.brandMatchesOrigin("   ", "paypal.com"))
    }

    @Test
    fun `detects punycode labels`() {
        assertTrue(OriginFacts.isPunycode("xn--pypal-4ve.com"))
        assertFalse(OriginFacts.isPunycode("paypal.com"))
    }

    @Test
    fun `detects a non-ascii host`() {
        assertTrue(OriginFacts.hasNonAsciiHost("pаypal.com"))
        assertFalse(OriginFacts.hasNonAsciiHost("paypal.com"))
    }

    @Test
    fun `detects a label mixing writing systems`() {
        // Cyrillic а among Latin letters -- indistinguishable by eye.
        assertTrue(OriginFacts.hasMixedScripts("pаypal.com"))
        assertFalse(OriginFacts.hasMixedScripts("paypal.com"))
        // An all-Cyrillic label is not mixed; it is simply another script.
        assertFalse(OriginFacts.hasMixedScripts("пример.com"))
    }

    @Test
    fun `detects a form posting to another registrable domain`() {
        assertTrue(
            OriginFacts.postsCrossOrigin("https://paypal.com/login", "https://evil.example/collect"),
        )
        assertFalse(
            OriginFacts.postsCrossOrigin("https://paypal.com/login", "https://api.paypal.com/auth"),
        )
    }

    @Test
    fun `a supplied public suffix list overrides the built-in approximation`() {
        // With "example.com" treated as a public suffix, shop.example.com is the registrable name.
        assertEquals(
            "shop.example.com",
            OriginFacts.registrableDomain("a.shop.example.com", setOf("example.com")),
        )
    }
}
