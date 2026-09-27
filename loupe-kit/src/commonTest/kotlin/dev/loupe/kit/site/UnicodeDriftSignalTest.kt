package dev.loupe.kit.site

import dev.loupe.kit.mail.Phishing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `unicode_drift_host` (docs/PHISHING-FORMULA.md §4): a host label with a character that Unicode 3.2
 * software (IDNA 2003, `java.net.IDN`) and current software map differently, or that did not exist in
 * 3.2, can lead to two different websites. It is phishing evidence (a risk code), on pages and links,
 * and on a mail sender's domain.
 */
class UnicodeDriftSignalTest {
    private fun s(cp: Int): String = buildString { append(Char(0xD800 + ((cp - 0x10000) shr 10))); append(Char(0xDC00 + ((cp - 0x10000) and 0x3FF))) }

    private fun codes(url: String): List<String> = SiteSignals.hostSignals(ParsedUrl.parse(url)!!, SiteConfig.DEFAULT).map { it.code }

    @Test
    fun `a newer character in the typed host is caught although IDNA maps it away`() {
        val url = "https://ex${s(0x1F130)}mple.com/login"
        assertEquals("example.com", ParsedUrl.parse(url)!!.host)
        assertEquals(listOf("unicode_drift_host"), codes(url))
        val v = SiteCheck.checkUrl(url).verdict
        assertEquals("caution", v.level)
        assertTrue("unicode_drift_host" in SiteSignals.RISK_CODES)
    }

    @Test
    fun `the parity code points are all drift`() {
        for (cp in listOf(0x1F130, 0x2150, 0x1FBF0, 0x1FBF9, 0x1CCF0, 0x1E030)) {
            val ch = if (cp > 0xFFFF) s(cp) else Char(cp).toString()
            assertTrue("unicode_drift_host" in codes("https://shop$ch.com/"), "U+${cp.toString(16)}")
        }
    }

    @Test
    fun `a crafted punycode label carrying a newer character is caught`() {
        val label = "xn--" + Punycode.encode("ex${s(0x1E030)}mple")
        assertTrue("unicode_drift_host" in codes("https://$label.com/"))
    }

    @Test
    fun `ordinary international names are not drift`() {
        for (url in listOf("https://bücher.de/", "https://مثال.مصر/", "https://例子.中国/", "https://пример.рф/", "https://paypal.com/")) {
            assertFalse("unicode_drift_host" in codes(url), url)
        }
    }

    @Test
    fun `with a password field it is an impostor login`() {
        val url = "https://ex${s(0x1F130)}mple.com/login"
        val (signals, _) = SiteSignals.urlAndPageSignals(PageFacts(url, passwordFields = 1, forms = listOf(PageForm(password = true))), SiteConfig.DEFAULT)
        assertTrue("unicode_drift_host" in signals.map { it.code })
        val v = SiteCheck.check(PageFacts(url, passwordFields = 1, forms = listOf(PageForm(password = true)))).verdict
        assertTrue("impostor_login" in v.reasons.map { it.code }, v.reasons.map { it.code }.toString())
    }

    @Test
    fun `mail senders and links carry it too`() {
        val sender = Phishing.assess("\"Shop\" <news@ex${s(0x1F130)}mple.com>", "Hello")
        assertTrue("sender_unicode_drift" in sender.reasons.map { it.code }, sender.reasons.map { it.code }.toString())
        val link = Phishing.assess("\"Friend\" <a@gmail.com>", "Look: https://ex${s(0x1E030)}mple.com/x")
        assertTrue("link_unicode_drift" in link.reasons.map { it.code }, link.reasons.map { it.code }.toString())
        assertTrue("sender_unicode_drift" in Phishing.RISK_CODES && "link_unicode_drift" in Phishing.RISK_CODES)
    }

    @Test
    fun `the wording never names the model`() {
        for (code in listOf("unicode_drift_host")) assertFalse("Laya" in SiteSignals.WEIGHTS.getValue(code).second)
        for (code in listOf("sender_unicode_drift", "link_unicode_drift")) assertFalse("Laya" in Phishing.WEIGHTS.getValue(code).second)
    }
}
