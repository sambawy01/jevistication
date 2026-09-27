package dev.loupe.kit.site

import dev.loupe.kit.mail.Phishing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host Loupe judges is the host the browser opens (the review's R-1, R-2 and follow-ups; the
 * expected hosts were checked in Chrome 153, `new URL(...).host`, recorded in
 * loupe-android-evidence/parity-fix2/browser/). UTS #46 non-transitional: `ß`, `ς` and joiners are
 * kept; the transitional (IDNA 2003) reading is only compared, as `deviation_host`.
 */
class HostReadingTest {
    private fun host(url: String) = ParsedUrl.parse(url)!!.host
    private fun withPassword(url: String, config: SiteConfig = SiteConfig.DEFAULT) =
        SiteCheck.check(PageFacts(url, passwordFields = 1, forms = listOf(PageForm(password = true))), config).verdict

    private val body = "Please verify your account now or it will be suspended: https://example.org/x"

    @Test
    fun `hosts are the ones Chrome opens`() {
        assertEquals("xn--fa-hia.de", host("https://faß.de/"))
        assertEquals("xn--americanexpre-ndb.com", host("https://americanexpreß.com/login"))
        assertEquals("xn--americanexpre-ndb.com", host("https://AMERICANEXPREẞ.COM/"))
        assertEquals("xn--meenger-1va.com", host("https://meßenger.com/"))
        assertEquals("xn--mgb3dcbfe14gp19l.xn--mgba3a4f16a", host("https://نمونه‌ای.ایران/"))
        assertEquals("xn--pxavbm.gr", host("https://οδος.gr/"))
        assertEquals("paypal.com.evil.com", host("https://paypal.com。evil.com/"))
        assertEquals("www.paypal.com.evil.xyz", host("https://www.paypal.com｡evil.xyz/"))
        assertEquals("xn--pypal-4ve.com", host("https://p%D0%B0ypal.com/login"))
        assertEquals("paypal.com", host("https://%EF%BD%90aypal.com/"))
        assertEquals("evil.com", host("https://evil.com\\.paypal.com/login"))
        assertEquals("evil.com", host("https://evil.com\\@paypal.com/login"))
        assertEquals("evil.com", host("https:\\\\evil.com/"))
        assertEquals("evil.com", host("https:///evil.com/"))
        assertEquals("facebook.com", host("https://facebooK.com/"))
    }

    @Test
    fun `a deviation host is never the known site it would be under IDNA 2003`() {
        for (h in listOf("americanexpreß.com", "AMERICANEXPREẞ.COM", "meßenger.com", "pay‍pal.com", "pay‌pal.com")) {
            val v = withPassword("https://$h/login")
            assertEquals("danger", v.level, "$h: ${v.reasons.map { it.code }}")
            assertFalse("known_good" in v.reasons.map { it.code }, h)
        }
        val trustsFass = SiteConfig(Brands.BRANDS, listOf("fass.de"), Brands.SUSPICIOUS_TLDS, Brands.SHORTENERS)
        assertEquals("safe", withPassword("https://fass.de/login", trustsFass).level)
        // faß.de reads as the trusted fass.de in IDNA 2003 but is another site: an impostor
        val sharp = withPassword("https://faß.de/login", trustsFass)
        assertEquals("danger", sharp.level)
        assertTrue("deviation_known_host" in sharp.reasons.map { it.code }, sharp.reasons.map { it.code }.toString())
        assertFalse("known_good" in sharp.reasons.map { it.code })
        // with nothing known behind the other reading it is a small note (straße.de is a real name)
        val street = SiteCheck.checkUrl("https://straße.de/").verdict
        assertEquals("safe", street.level)
        assertEquals(listOf("deviation_host"), street.reasons.filter { it.weight > 0 }.map { it.code })
        assertTrue("deviation_host" in SiteContext.DISQUALIFY_CODES && "deviation_known_host" in SiteContext.DISQUALIFY_CODES)
        assertTrue("deviation_known_host" in SiteScoring.IMPOSTOR_CODES)
    }

    @Test
    fun `mail with a deviation or a stand-in is flagged and never trusted through the IDNA 2003 form`() {
        val trusted = Phishing.assess("\"Fass Billing\" <billing@faß.de>", body, trusted = listOf("fass.de"))
        assertFalse(trusted.trusted)
        assertTrue("sender_deviation_domain" in trusted.reasons.map { it.code })
        val link = Phishing.assess("\"Fass\" <a@gmail.com>", "Log in: https://faß.de/login")
        assertTrue("link_deviation" in link.reasons.map { it.code }, link.reasons.map { it.code }.toString())
        val replyTo = Phishing.assess("\"Billing\" <billing@example.org>", "Please reply with your details", replyTo = "x@americanexpreß.com")
        assertTrue("reply_to_impostor" in replyTo.reasons.map { it.code }, replyTo.reasons.map { it.code }.toString())
        for (s in listOf("security@facebooK.com", "service@pay‍pal.com", "service@americanexpreß.com")) {
            val v = Phishing.assess(s, body)
            assertEquals("danger", v.level, "$s: ${v.reasons.map { it.code }}")
        }
        val amex = Phishing.assess("\"American Express\" <service@americanexpreß.com>", body)
        assertTrue(amex.score >= 85, "${amex.score}")
    }

    /**
     * A sender written with anything but ASCII never scores lower than on origin/main before the
     * parity branch (7e13578; the scores were measured there with the same messages,
     * loupe-android-evidence/parity-fix2/baseline-origin-main.tsv).
     */
    @Test
    fun `non-ASCII senders never score lower than before the branch`() {
        val baseline = mapOf(
            "ｐａｙｐａｌ.com" to 0, "pay‍pal.com" to 45, "pay‌pal.com" to 45, "pay­pal.com" to 45,
            "pay​pal.com" to 45, "pay️pal.com" to 60, "pay͏pal.com" to 60, "pay᠋pal.com" to 60,
            "payㅤpal.com" to 80, "payﾠpal.com" to 80, "payᅟpal.com" to 80, "facebooK.com" to 0,
            "americanexpreß.com" to 45, "AMERICANEXPREẞ.COM" to 45, "аpple.com" to 60, "Ꭰpple.com" to 35,
            "paypal.com．evil.com" to 30, "paypal.com。evil.com" to 30, "ᴘᴀʏᴘᴀʟ.com" to 0,
        )
        for ((domain, before) in baseline) {
            val v = Phishing.assess("\"Account Team\" <service@$domain>", "Please verify your account now: https://example.org/x")
            assertTrue(v.score >= before, "$domain: ${v.score} < $before ${v.reasons.map { it.code }}")
        }
        assertTrue(Phishing.assess("\"PayPal\" <service@ᴘᴀʏᴘᴀʟ.com>", body).score >= 40)
        assertTrue(Phishing.assess("\"American Express\" <service@americanexpreß.com>", body).score >= 85)
    }

    /**
     * Fix loop 3 (B5): an ideographic, full-width or halfwidth full stop in a sender or reply address.
     * Scores on origin/main measured with the round-3 probe (the same messages and body,
     * loupe-android-evidence/parity-fix3/m-*.base).
     */
    @Test
    fun `stand-in full stops in an address never score lower than before the branch`() {
        val probeBody = "Please verify your account now to avoid suspension."
        val baseline = listOf(
            Triple("PayPal <service@paypal。com>", "", 40), Triple("PayPal <service@paypal．com>", "", 40),
            Triple("PayPal <service@paypal｡com>", "", 40), Triple("PayPal <service@paypal.com。>", "", 60),
            Triple("PayPal <service@paypal.com．>", "", 70), Triple("PayPal <service@paypal.com｡>", "", 60),
            Triple("Account Team <service@paypal。com>", "", 0), Triple("Account Team <service@paypal．com>", "", 0),
            Triple("Account Team <service@paypal｡com>", "", 0), Triple("Account Team <service@paypal.com。>", "", 20),
            Triple("Account Team <service@paypal.com．>", "", 30), Triple("service@paypal．com <x@evil.xyz>", "", 48),
            Triple("Shop <a@shop-example.com>", "support@paypal。com", 15), Triple("Shop <a@shop-example.com>", "support@paypal．com", 15),
            Triple("Shop <a@shop-example.com>", "support@paypal｡com", 15), Triple("Shop <a@shop-example.com>", "support@paypal.com。", 40),
        )
        for ((sender, reply, before) in baseline) {
            val v = Phishing.assess(sender, probeBody, replyTo = reply)
            assertTrue(v.score >= before, "$sender / $reply: ${v.score} < $before ${v.reasons.map { it.code }}")
            if ('<' in sender && sender.substringAfter('<').any { it in "。．｡" }) {
                assertTrue("sender_disguised_domain" in v.reasons.map { it.code }, "$sender: ${v.reasons.map { it.code }}")
            }
        }
        assertTrue(Phishing.assess("PayPal <service@paypal.com。>", probeBody).score >= 60)
        assertTrue(Phishing.assess("Account Team <service@paypal.com。>", probeBody).score >= 20)
        for (bank in listOf("Bank <info@mybank。com>", "Bank <info@mybank.com。>", "Bank <info@mybank．com>", "Bank <info@mybank｡com>")) {
            val v = Phishing.assess(bank, probeBody, trusted = listOf("mybank.com"))
            assertFalse(v.trusted, bank)
            assertTrue("sender_disguised_domain" in v.reasons.map { it.code }, "$bank: ${v.reasons.map { it.code }}")
        }
    }

    /** Fix loop 3 (B1): an href with a special scheme but no `//` is read the way browsers read it. */
    @Test
    fun `mail links with a special scheme and no double slash are read as browsers do`() {
        val config = Phishing.DEFAULT_CONFIG
        for (href in listOf("https:\\\\paypa1-secure.xyz/login", "https:\\paypa1-secure.xyz/login", "https:/paypa1-secure.xyz/login",
            "https:paypa1-secure.xyz/login", "HTTPS:paypa1-secure.xyz/login", "\u0001https://paypa1-secure.xyz/login")) {
            assertEquals("paypa1-secure.xyz", ParsedUrl.parse(Hosts.linkUrl(href))?.host, href)
            assertEquals(listOf(href.trim { it.code <= 0x20 }), Phishing.linkTargets("", listOf(href to "Sign in"), "gmail.com", config), href)
            val v = Phishing.assess("\"Friend\" <a@gmail.com>", "", links = listOf(href to "www.paypal.com"))
            val codes = v.reasons.map { it.code }
            assertTrue("link_text_mismatch" in codes && "link_lookalike_brand" in codes, "$href: $codes")
            assertEquals("danger", v.level, href)
        }
        // a javascript: or mailto: href behind a C0 control or a tab is still that scheme
        for (href in listOf("\u0001javascript:alert(1)", "java\tscript:alert(1)", " JAVASCRIPT:alert(1)")) {
            assertEquals(listOf("link_data"), Phishing.assess("\"Friend\" <a@gmail.com>", "", links = listOf(href to "Open")).reasons.map { it.code }, href)
        }
        assertTrue(Phishing.assess("\"Friend\" <a@gmail.com>", "", links = listOf("\u0001mailto:a@paypa1-secure.xyz" to "Mail us")).reasons.isEmpty())
        // a bare host is still read as http://
        assertEquals("http://paypa1-secure.xyz/login", Hosts.linkUrl("paypa1-secure.xyz/login"))
    }

    /** Fix loop 3 (B2, S1): WHATWG userinfo, C0 stripping, and a host no browser can open. */
    @Test
    fun `userinfo ends at the last at sign and an unreadable URL is never safe`() {
        for (u in listOf("https://www.paypal.com[@paypa1-secure.xyz/login", "https://x]@paypa1-secure.xyz/login",
            "https://[www.paypal.com]@paypa1-secure.xyz/login", "https://user:[x@paypa1-secure.xyz/login")) {
            val p = ParsedUrl.parse(u)
            assertEquals("paypa1-secure.xyz", p?.host, u)
            assertTrue(p!!.userinfo, u)
            assertEquals("danger", SiteCheck.checkUrl(u).verdict.level, u)
        }
        for (u in listOf("\u0001https://evil.com/login", "\u0000https://evil.com/", "  https://evil.com/  ")) {
            assertEquals("evil.com", ParsedUrl.parse(u)?.host, u)
        }
        for (u in listOf("https://paypal.com%40evil.com/login", "https://paypal.com%00.evil.com/", "https://paypal.com%09evil.com/")) {
            val v = SiteCheck.checkUrl(u).verdict
            assertTrue("unreadable_url" in v.reasons.map { it.code }, "$u: ${v.reasons.map { it.code }}")
            assertFalse(v.level == "safe", u)
        }
        // S1: a backslash before @ is a path separator, but the URL still reads as a disguise (origin/main: 30 / PW 50)
        val s1 = "https://evil.com\\@paypal.com"
        assertEquals("evil.com", ParsedUrl.parse(s1)?.host)
        val site = SiteCheck.checkUrl(s1).verdict
        assertTrue("userinfo_in_url" in site.reasons.map { it.code }, site.reasons.map { it.code }.toString())
        assertTrue(site.score >= 30)
        assertTrue(withPassword(s1).score >= 50)
        val mail = Phishing.assess("\"Friend\" <a@gmail.com>", "", links = listOf(s1 to "paypal.com"))
        assertTrue("link_userinfo" in mail.reasons.map { it.code }, mail.reasons.map { it.code }.toString())
    }

    @Test
    fun `small capitals and sharp s are homographs of the brand`() {
        assertTrue("homograph_brand" in withPassword("https://ᴘᴀʏᴘᴀʟ.com/login").reasons.map { it.code })
        assertTrue("homograph_brand" in withPassword("https://xn--meenger-1va.com/login").reasons.map { it.code })
        assertTrue("homograph_brand" in withPassword("https://p%D0%B0ypal.com/login").reasons.map { it.code })
        assertEquals("paypal", SiteSignals.skeleton("pay‍pal"))
        assertEquals("messenger", SiteSignals.skeleton("meßenger"))
    }

    @Test
    fun `a backslash and other full stops cannot hide the real host`() {
        val back = withPassword("https://evil.com\\.paypal.com/login")
        assertFalse("known_good" in back.reasons.map { it.code })
        val dot = withPassword("https://paypal.com。evil.com/login")
        assertEquals("evil.com", ParsedUrl.parse("https://paypal.com。evil.com/login")!!.registrable)
        assertTrue("brand_domain_in_subdomain" in dot.reasons.map { it.code }, dot.reasons.map { it.code }.toString())
    }

    @Test
    fun `typedHost is derived from the raw URL so a copy keeps it`() {
        val u = ParsedUrl.parse("https://ｐａｙｐａｌ.com/")!!
        assertEquals("ｐａｙｐａｌ.com", u.typedHost)
        assertEquals("ｐａｙｐａｌ.com", u.copy(path = "/x").typedHost)
        assertTrue(SiteSignals.disguise(u.copy()).isNotEmpty())
    }
}
