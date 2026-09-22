package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteFraudTest {

    private fun names(assessment: FraudAssessment) = assessment.signals.map { it.name }.toSet()

    @Test
    fun `flags a brand parked on someone else's domain`() {
        val found = SiteFraud.assess("https://paypal.secure-login.com/signin", claimedBrand = "PayPal")
        assertTrue("brand-origin-mismatch" in names(found))
        assertTrue(found.hasWarnings())
    }

    @Test
    fun `the brand on its own domain raises no brand signal`() {
        val found = SiteFraud.assess("https://www.paypal.com/signin", claimedBrand = "PayPal")
        assertFalse("brand-origin-mismatch" in names(found))
    }

    @Test
    fun `flags punycode and mixed-script hosts`() {
        assertTrue("punycode-host" in names(SiteFraud.assess("https://xn--pypal-4ve.com")))
        assertTrue("mixed-script-host" in names(SiteFraud.assess("https://pаypal.com")))
    }

    @Test
    fun `flags a form posting to another registrable domain`() {
        val found = SiteFraud.assess(
            "https://mybank.example/login",
            formActionUrl = "https://collector.evil.example/post",
        )
        assertTrue("cross-origin-form-post" in names(found))
    }

    @Test
    fun `a form posting within the same site is not flagged`() {
        val found = SiteFraud.assess(
            "https://mybank.example/login",
            formActionUrl = "https://api.mybank.example/auth",
        )
        assertFalse("cross-origin-form-post" in names(found))
    }

    @Test
    fun `flags a known-bad host`() {
        val found = SiteFraud.assess("https://bad.example/x", knownBadHosts = setOf("bad.example"))
        assertTrue("known-bad" in names(found))
    }

    @Test
    fun `reports an unparseable url rather than passing it`() {
        assertTrue("unparseable-url" in names(SiteFraud.assess("nonsense !!")))
    }

    @Test
    fun `never blesses a page that raised no signal`() {
        val found = SiteFraud.assess("https://www.paypal.com/signin", claimedBrand = "PayPal")
        assertFalse(found.hasWarnings())
        // The wording must not imply safety -- the spec forbids an all-clear.
        val summary = found.summary().lowercase()
        assertTrue(summary.contains("not an all-clear"))
        assertFalse(summary.contains("is safe"))
        assertFalse(summary.contains("legitimate"))
        assertFalse(summary.contains("trusted"))
    }

    @Test
    fun `accumulates several signals on one page`() {
        val found = SiteFraud.assess(
            "https://pаypal.secure-login.com/signin",
            claimedBrand = "PayPal",
            formActionUrl = "https://collector.evil.example/post",
        )
        assertTrue(found.signals.size >= 3, "expected several signals, got ${found.signals}")
        assertEquals("https://pаypal.secure-login.com/signin", found.url)
    }
}
