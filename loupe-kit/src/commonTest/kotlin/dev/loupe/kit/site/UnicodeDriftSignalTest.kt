package dev.loupe.kit.site

import dev.loupe.kit.mail.Phishing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a host name is WRITTEN (docs/PHISHING-FORMULA.md §4):
 * - `disguised_host`: stand-in letters (full-width, mathematical, enclosed, ligatures) or invisible
 *   characters that IDNA maps away; `ｐａｙｐａｌ.com` reaches paypal.com, but no real link or sender
 *   writes it that way. Checked before the known-good short-circuit.
 * - `unicode_drift_host`: a character that Unicode 3.2 nameprep (IDNA 2003) and the pinned 16.0
 *   mapping map differently (unassigned characters pass through 3.2 unchanged, so new scripts and
 *   emoji that map to themselves are not drift). Its own risk signal, not an impostor code.
 * Mail: a sender whose domain is written with anything but ASCII is never known, trusted or free-mail.
 */
class UnicodeDriftSignalTest {
    private fun s(cp: Int): String = buildString { append(Char(0xD800 + ((cp - 0x10000) shr 10))); append(Char(0xDC00 + ((cp - 0x10000) and 0x3FF))) }

    private fun codes(url: String): List<String> = SiteSignals.hostSignals(ParsedUrl.parse(url)!!, SiteConfig.DEFAULT).map { it.code }

    private val fullwidth = "ｐａｙｐａｌ"
    private val mathSans = s(0x1D5FD) + s(0x1D5EE) + s(0x1D606) + s(0x1D5FD) + s(0x1D5EE) + s(0x1D5F9)
    private val enclosed = "p${s(0x1F130)}yp${s(0x1F130)}l"
    private val phishingBody = "We noticed unusual activity on your PayPal account and have limited it. " +
        "Verify your information within 24 hours or your account will be permanently suspended."

    private fun mail(sender: String, trusted: List<String> = emptyList()) = Phishing.assess(sender, phishingBody, trusted = trusted)

    @Test
    fun `a brand's domain written with stand-in letters is a disguised sender never the brand`() {
        for (label in listOf(fullwidth, mathSans, enclosed)) {
            val v = mail("\"PayPal\" <service@$label.com>")
            assertEquals("danger", v.level, label)
            assertTrue(v.score >= 85, "$label: ${v.score}")
            assertEquals(setOf("sender_disguised_domain", "display_brand_mismatch"), v.reasons.map { it.code }.toSet(), label)
        }
        val upper = mail("x@ＰＡＹＰＡＬ.ＣＯＭ")
        assertEquals("caution", upper.level)
        assertEquals(listOf("sender_disguised_domain"), upper.reasons.map { it.code })
        // a trusted domain does not cover the same name written with stand-ins
        val trusted = mail("\"PayPal\" <service@$fullwidth.com>", trusted = listOf("paypal.com"))
        assertEquals("danger", trusted.level)
        assertFalse(trusted.trusted)
        // the real address is still known, and trusted when the user said so
        assertEquals(listOf("known_sender"), mail("\"PayPal\" <service@paypal.com>").reasons.map { it.code })
        assertEquals(listOf("trusted_sender"), mail("\"PayPal\" <service@paypal.com>", listOf("paypal.com")).reasons.map { it.code })
        // an ordinary international sender is not known, and not flagged either
        assertEquals("safe", Phishing.assess("\"Info\" <info@مثال.مصر>", "Hello").level)
    }

    @Test
    fun `a link written with stand-in letters is at least caution even to the real site`() {
        for (label in listOf(fullwidth, mathSans, enclosed)) {
            val url = "https://$label.com/"
            assertEquals("paypal.com", ParsedUrl.parse(url)!!.host)
            assertEquals(listOf("disguised_host"), codes(url), label)
            val v = SiteCheck.checkUrl(url).verdict
            assertEquals("caution", v.level, label)
        }
        assertEquals(emptyList(), codes("https://paypal.com/"))
        assertEquals("safe", SiteCheck.checkUrl("https://paypal.com/").verdict.level)
        val inMail = Phishing.assess("\"Friend\" <a@gmail.com>", "Log in here: https://$fullwidth.com/signin")
        assertTrue("link_disguised" in inMail.reasons.map { it.code }, inMail.reasons.map { it.code }.toString())
    }

    @Test
    fun `the parity code points are stand-ins and count once`() {
        for (cp in listOf(0x1F130, 0x2150, 0x1FBF0, 0x1FBF9, 0x1CCF0, 0x1E030)) {
            val ch = if (cp > 0xFFFF) s(cp) else Char(cp).toString()
            val got = codes("https://shop$ch.com/")
            assertTrue("disguised_host" in got, "U+${cp.toString(16)}: $got")
            assertFalse("unicode_drift_host" in got, "U+${cp.toString(16)}: $got")
        }
        val label = "xn--" + Punycode.encode("ex${s(0x1E030)}mple")
        assertTrue("disguised_host" in codes("https://$label.com/"))
    }

    @Test
    fun `characters whose IDNA mapping changed since Unicode 3-2 are drift`() {
        // Georgian capital An: no case in 3.2, lower-cased to U+2D00 since Unicode 11
        assertEquals(listOf("unicode_drift_host"), codes("https://Ⴀა.ge/"))
        // Georgian Mtavruli (Unicode 11): 3.2 passes it through, 16.0 lower-cases it
        assertEquals(listOf("unicode_drift_host"), codes("https://Აა.ge/"))
        assertTrue("unicode_drift_host" in SiteSignals.RISK_CODES)
        assertEquals("caution", SiteCheck.checkUrl("https://Ⴀა.ge/").verdict.level)
    }

    @Test
    fun `scripts and symbols newer than Unicode 3-2 are ordinary names`() {
        for (host in listOf(
            "မြန်မာ.com", "മലയാളംൺ.com", "${s(0x1F600)}.ws", "龦鿿.com", "ݐݑࢠ.com",
            "ȷ.com", "ӏ.com", "मराठीॱ.com", "مصر.مصر", "bücher.de", "مثال.مصر", "例子.中国", "пример.рф",
        )) {
            assertEquals(emptyList(), codes("https://$host/"), host)
        }
    }

    @Test
    fun `drift is not an impostor code`() {
        assertFalse("unicode_drift_host" in SiteScoring.IMPOSTOR_CODES)
        assertFalse("disguised_host" in SiteScoring.IMPOSTOR_CODES)
        val page = PageFacts("https://မြန်မာ.com/login", passwordFields = 1, forms = listOf(PageForm(password = true)))
        val v = SiteCheck.check(page).verdict
        assertFalse("impostor_login" in v.reasons.map { it.code }, v.reasons.map { it.code }.toString())
    }

    @Test
    fun `mail senders and links carry drift too`() {
        val sender = Phishing.assess("\"Shop\" <news@Ⴀა.ge>", "Hello")
        assertTrue("sender_unicode_drift" in sender.reasons.map { it.code }, sender.reasons.map { it.code }.toString())
        val link = Phishing.assess("\"Friend\" <a@gmail.com>", "Look: https://Ⴀა.ge/x")
        assertTrue("link_unicode_drift" in link.reasons.map { it.code }, link.reasons.map { it.code }.toString())
        assertTrue(listOf("sender_unicode_drift", "link_unicode_drift", "sender_disguised_domain", "link_disguised").all { it in Phishing.RISK_CODES })
    }

    @Test
    fun `the wording never names the model`() {
        for (code in listOf("unicode_drift_host", "disguised_host")) assertFalse("Laya" in SiteSignals.WEIGHTS.getValue(code).second)
        for (code in listOf("sender_unicode_drift", "link_unicode_drift", "sender_disguised_domain", "link_disguised")) {
            assertFalse("Laya" in Phishing.WEIGHTS.getValue(code).second)
        }
    }
}
