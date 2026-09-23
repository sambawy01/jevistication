package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicSuffixTest {

    /**
     * The official PSL test vectors (`tests/tests.txt` from github.com/publicsuffix/list,
     * dedicated to the public domain under CC0), bundled unmodified as `psl_tests.txt` and
     * compiled into the tests as [PslTestVectors] so they run on iOS too.
     */
    @Test
    fun `passes the official public suffix list test vectors`() {
        val lines = PslTestVectors.TEXT.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
        var checked = 0
        for (line in lines) {
            val (input, expected) = line.split(Regex("\\s+")).let { it[0] to it[1] }
            if (input == "null") continue // no null String in Kotlin; the null-input case
            val want = expected.takeUnless { it == "null" }
            assertEquals(want, OriginFacts.registrableDomain(input), "registrableDomain($input)")
            checked++
        }
        assertTrue(checked >= 70, "only $checked vectors were read")
    }

    @Test
    fun `the bundled list matches its recorded sha-256`() {
        // Runs on every target over the bytes that target actually loads: the classpath resource
        // on the JVM, the build-time embedded constant on iOS.
        val recorded = bundledPublicSuffixListSha256().trim().substringBefore(' ')
        val actual = Hashing.sha256Hex(bundledPublicSuffixList())
        assertEquals(recorded, actual)
    }

    @Test
    fun `loads both sections and records the snapshot version`() {
        assertTrue(PublicSuffix.ICANN.size > 5_000)
        assertTrue("com" in PublicSuffix.ICANN)
        assertTrue("github.io" in PublicSuffix.ALL)
        assertFalse("github.io" in PublicSuffix.ICANN)
        assertTrue("*.ck" in PublicSuffix.ALL && "!www.ck" in PublicSuffix.ALL)
        // Unicode rules are stored in their ASCII form.
        assertTrue("xn--55qx5d.cn" in PublicSuffix.ALL)
        assertTrue(PublicSuffix.VERSION.matches(Regex("""\d{4}-\d{2}-\d{2}_.*""")))
    }

    @Test
    fun `fraud cases resolve to the domain that actually owns them`() {
        assertEquals("secure-login.com", OriginFacts.registrableDomain("paypal.secure-login.com"))
        assertEquals("fake.co.uk", OriginFacts.registrableDomain("hsbc.fake.co.uk"))
        assertEquals("hsbc.co.uk", OriginFacts.registrableDomain("secure.hsbc.co.uk"))
        assertFalse(OriginFacts.brandMatchesOrigin("PayPal", "paypal.secure-login.com"))
        // Hosting under a PRIVATE suffix belongs to the tenant, not the host.
        assertEquals("paypal.github.io", OriginFacts.registrableDomain("paypal.github.io"))
        assertFalse(OriginFacts.brandMatchesOrigin("GitHub", "paypal.github.io"))
        assertEquals("github.io", OriginFacts.registrableDomain("paypal.github.io", PublicSuffix.ICANN))
        assertTrue(
            OriginFacts.postsCrossOrigin("https://alice.github.io/login", "https://mallory.github.io/x"),
        )
        // A homograph host keeps its own unicode form and never matches the brand.
        assertEquals("pаypal.com", OriginFacts.registrableDomain("www.pаypal.com"))
        assertFalse(OriginFacts.brandMatchesOrigin("PayPal", "pаypal.com"))
    }

    @Test
    fun `trailing dots - ip literals and bare hosts`() {
        assertEquals("paypal.com", OriginFacts.registrableDomain("www.paypal.com."))
        assertNull(OriginFacts.registrableDomain("com."))
        assertNull(OriginFacts.registrableDomain("192.168.0.1"))
        assertNull(OriginFacts.registrableDomain("[::1]"))
        assertNull(OriginFacts.registrableDomain("::1"))
        assertNull(OriginFacts.registrableDomain("localhost"))
        assertNull(OriginFacts.registrableDomain("a..b.com"))
        assertNull(OriginFacts.registrableDomain(""))
    }
}
