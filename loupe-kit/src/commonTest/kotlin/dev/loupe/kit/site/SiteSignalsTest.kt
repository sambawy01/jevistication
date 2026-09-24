package dev.loupe.kit.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * PROVENANCE: the cases are ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `tests/test_browser.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e): the signal and scoring
 * tests, with the same URLs, pages, scripted Laya answers and expectations. The /api/browser route
 * tests (auth, rate limit, cache, history) have no counterpart on the phone and are not ported.
 * Where the engine's pinned PSL differs from Station's snapshot the case says so.
 */
class SiteSignalsTest {
    private val cfg = SiteConfig.DEFAULT

    private fun codes(url: String, page: PageFacts = PageFacts(url)): Set<String> =
        SiteSignals.urlAndPageSignals(page.copy(url = url), cfg).first.map { it.code }.toSet()

    private fun idn(unicodeHost: String): String = unicodeHost.split('.').joinToString(".") { Hosts.toAsciiLabel(it)!! }

    // ------------------------------------------------------------------ registrable domains
    @Test
    fun registrableDomain() {
        val cases = listOf(
            "www.paypal.com" to "paypal.com", "login.paypal.com.secure-check.co.uk" to "secure-check.co.uk",
            "news.bbc.co.uk" to "bbc.co.uk", "shop.example.com.eg" to "example.com.eg", "my-site.github.io" to "my-site.github.io",
            "a.b.my-site.netlify.app" to "my-site.netlify.app", "example.xyz" to "example.xyz", "co.uk" to null,
            "192.168.1.1" to null, "x.localhost" to "x.localhost",
        )
        for ((host, reg) in cases) assertEquals(reg, Hosts.registrableDomain(host), host)
    }

    @Test
    fun privateHosts() {
        for (h in listOf("localhost", "laya.localhost", "192.168.1.1", "10.0.0.8", "127.0.0.1", "nas.local", "3232235777")) {
            assertTrue(Hosts.isPrivateHost(h), h)
        }
        for (h in listOf("45.33.32.156", "8.8.8.8", "example.com")) assertFalse(Hosts.isPrivateHost(h), h)
    }

    // ------------------------------------------------------------------ signals
    @Test
    fun homographHosts() {
        assertTrue("homograph_brand" in codes("https://${idn("pаypal.com")}/signin"))          // Cyrillic а
        assertTrue("homograph_brand" in codes("https://${idn("аррlе.com")}/"))                  // Cyrillic а р р е
        val mixed = codes("https://${idn("gооgle-news.com")}/")                                 // Latin + Cyrillic о
        assertTrue("mixed_script" in mixed || "homograph_brand" in mixed)
        // Formula rule 2: a genuine single-script IDN (Arabic, Chinese, German) is not suspicious by itself
        assertEquals(emptySet(), codes("https://${idn("مثال.مصر")}/"))
        assertEquals(emptySet(), codes("https://${idn("例子.中国")}/"))
        assertEquals(emptySet(), codes("https://${idn("bücher.de")}/"))
        assertFalse("punycode" in SiteSignals.WEIGHTS)
        // a host typed in Unicode is read in its ASCII form, so the homograph is still caught
        assertTrue("homograph_brand" in codes("https://pаypal.com/signin"))
    }

    @Test
    fun lookalikeHosts() {
        for (url in listOf("https://paypa1.com/login", "https://rnicrosoft.com/", "https://linkedln.com/", "https://paypall.com/", "https://arnazon.co.uk/")) {
            assertTrue("lookalike_brand" in codes(url), url)
        }
        for (url in listOf(
            "https://www.paypal.com/signin", "https://login.microsoftonline.com/", "https://www.google.com.eg/",
            "https://amazon.de/", "https://phase.com/", "https://visit.chasebay.org/", "https://staples.com/",
        )) {
            assertTrue((codes(url) intersect setOf("lookalike_brand", "homograph_brand", "brand_other_tld")).isEmpty(), url)
        }
    }

    @Test
    fun brandInSubdomainAndDomain() {
        assertTrue("brand_domain_in_subdomain" in codes("https://paypal.com.account-verify.xyz/"))
        assertTrue("brand_in_subdomain" in codes("https://paypal.secure-login.net/"))
        assertTrue("brand_in_domain_bait" in codes("https://paypal-secure-login.com/"))
        assertTrue("brand_in_domain_bait" in codes("https://amazon-prime-support.help/"))
        assertEquals(setOf("brand_in_domain"), codes("https://apple-farm.com/"))                // no bait words: low weight
        assertTrue("brand_other_tld" in codes("https://google.xyz/"))
        assertTrue("many_subdomains" in codes("https://a.b.c.d.e.example.com/"))
    }

    @Test
    fun ipHosts() {
        assertTrue("ip_host" in codes("http://45.33.32.156/login"))
        assertTrue("ip_host" in codes("http://757145756/"))                                      // decimal form
        assertEquals(emptySet(), codes("http://192.168.1.1/login", PageFacts("", passwordFields = 1))) // a home router
    }

    @Test
    fun passwordForms() {
        assertTrue("http_password" in codes("http://shop.example.com/login", PageFacts("", passwordFields = 1)))
        assertTrue("http_card" in codes("http://shop.example.com/pay", PageFacts("", cardFields = 1)))
        assertFalse("http_password" in codes("https://shop.example.com/login", PageFacts("", passwordFields = 1)))
        val cross = codes("https://example.com/login", PageFacts("", forms = listOf(PageForm("https://collector.evil.ru/p.php", password = true))))
        assertTrue("password_posts_elsewhere" in cross)
        val same = codes("https://www.example.com/login", PageFacts("", forms = listOf(PageForm("https://auth.example.com/session", password = true))))
        assertFalse("password_posts_elsewhere" in same)
        val insecure = codes("https://example.com/login", PageFacts("", forms = listOf(PageForm("http://example.com/session", password = true))))
        assertTrue("password_posts_http" in insecure)
        // formula rule 4: a card form posting elsewhere is as strong as a password form ...
        assertTrue("password_posts_elsewhere" in codes("https://example.com/pay", PageFacts("", forms = listOf(PageForm("https://collector.evil.ru/c.php", card = true)))))
        // ... a search form posting elsewhere is only a weak signal
        val search = codes("https://example.com/", PageFacts("", forms = listOf(PageForm("https://search.other.com/"))))
        assertFalse("password_posts_elsewhere" in search)
        assertTrue("form_posts_elsewhere" in search)
        assertTrue(SiteSignals.WEIGHTS.getValue("form_posts_elsewhere").first < 15)
    }

    @Test
    fun urlTricks() {
        assertEquals(setOf("data_url"), codes("data:text/html;base64,PGh0bWw+"))
        assertTrue("userinfo_in_url" in codes("https://www.paypal.com@evil.example/login"))
        assertTrue("url_shortener" in codes("https://bit.ly/abc"))
        assertTrue("brand_in_path" in codes("https://evil.example/www.paypal.com/login"))
        assertTrue("long_url" in codes("https://example.com/" + "a".repeat(250)))
        assertTrue("encoded_url" in codes("https://example.com/" + "%41".repeat(20)))
        assertTrue("suspicious_tld" in codes("https://example.zip/"))
    }

    @Test
    fun brandClaimFromTitleOrFavicon() {
        assertTrue("brand_mismatch_login" in codes("https://secure-check.example/", PageFacts("", title = "PayPal: Log in", passwordFields = 1)))
        assertTrue("brand_mismatch" in codes("https://secure-check.example/", PageFacts("", faviconHost = "www.paypalobjects.com")))
        // the real site, and body text that merely mentions a brand, are not claims
        assertEquals(emptySet(), codes("https://www.paypal.com/signin", PageFacts("", title = "PayPal: Log in", passwordFields = 1)))
        assertEquals(emptySet(), codes("https://blog.example.org/", PageFacts("", title = "How to accept payments")))
    }

    @Test
    fun sharedHostingLogin() {
        assertTrue("shared_hosting_login" in codes("https://my-bank-login.web.app/", PageFacts("", passwordFields = 1)))
        assertFalse("shared_hosting_login" in codes("https://my-blog.github.io/"))
        // formula rule 5: the twelve shared-hosting names the PSL does not list add their own caution
        assertEquals(12, Hosts.SHARED_NOT_IN_PSL.size, Hosts.SHARED_NOT_IN_PSL.toString())
        for (name in Hosts.SHARED_NOT_IN_PSL) {
            assertTrue(name in Brands.SHARED_HOSTING, name)
            assertEquals(name, Hosts.registrableDomain("x.$name"), "$name became a PSL suffix: update the formula")
        }
        assertEquals(setOf("shared_hosting"), codes("https://my-shop.wordpress.com/"))
        assertEquals(emptySet(), codes("https://wordpress.com/"))
        assertFalse("shared_hosting" in codes("https://my-blog.github.io/"))                   // a PSL suffix: host control says enough
    }

    @Test
    fun brandOwnershipListFirstThenName() {
        // formula rule 1: the brand list decides for listed brands (Microsoft owns live.com) ...
        assertEquals(emptySet(), codes("https://login.live.com/", PageFacts("", title = "Sign in to your Microsoft account", passwordFields = 1)))
        assertEquals(emptySet(), codes("https://www.youtube.com/", PageFacts("", claimedBrand = "Google")))
        assertTrue("brand_mismatch" in codes("https://paypal-help.example/", PageFacts("", claimedBrand = "PayPal")))
        // ... and the engine's name-vs-domain check only for brands not on it
        assertEquals(emptySet(), codes("https://www.streamflix.example/", PageFacts("", claimedBrand = "Streamflix")))
        assertTrue("brand_mismatch" in codes("https://streamflix-billing.example/", PageFacts("", claimedBrand = "Streamflix")))
    }

    @Test
    fun everyCodeHasAReasonAndWeight() {
        val placeholder = Regex("\\{(\\w+)\\}")
        for ((code, wt) in SiteSignals.WEIGHTS) {
            assertTrue(wt.first in 1..60 && wt.second.isNotBlank(), code)
            assertTrue(placeholder.findAll(wt.second).map { it.groupValues[1] }.toSet().all { it in SiteScoring.REASON_PARAMS }, code)
        }
        assertTrue(SiteSignals.WEIGHTS.keys.all { it in SiteScoring.REASON_CODES })
    }

    // ------------------------------------------------------------------ scoring
    private fun laya(ask: String = "other", askP: Double = 0.9, urgency: Double = 0.1, prize: Double = 0.1, claims: String = "other") = mapOf(
        "asks_visitor_to" to LayaPageAnswer(askP, choice = ask),
        "urgency_or_threat" to LayaPageAnswer(urgency, yes = urgency >= 0.5),
        "offers_prize_or_refund" to LayaPageAnswer(prize, yes = prize >= 0.5),
        "claims_to_be" to LayaPageAnswer(0.9, choice = claims, weak = true),
    )

    private fun verdict(url: String, answers: Map<String, LayaPageAnswer>?, page: PageFacts = PageFacts(url), allowlisted: Boolean = false) =
        SiteScoring.verdict(page.copy(url = url), answers, allowlisted, cfg)

    @Test
    fun realBrandLoginIsSafe() {
        val v = verdict(
            "https://accounts.google.com/signin", laya("sign_in", urgency = 0.95),
            PageFacts("", title = "Sign in - Google Accounts", passwordFields = 1, forms = listOf(PageForm("https://accounts.google.com/v3/signin", password = true))),
        )
        assertEquals("safe", v.level)
        assertEquals(0, v.score)
        assertEquals(listOf("known_good"), v.reasons.map { it.code })
    }

    @Test
    fun lookalikeLoginIsDanger() {
        assertEquals("danger", verdict("https://paypa1.com/signin", laya("sign_in"), PageFacts("", title = "Log in to your account", passwordFields = 1)).level)
        // even without Laya (model still loading) and without a brand in the title
        assertEquals("danger", verdict("https://paypa1.com/signin", null, PageFacts("", title = "Log in", passwordFields = 1)).level)
        assertEquals("danger", verdict("https://secure-check.example/", laya("sign_in"), PageFacts("", title = "PayPal: Log in", passwordFields = 1)).level)
    }

    @Test
    fun normalArticleIsSafe() {
        val v = verdict("https://news.example.com/2026/city-bike-lanes", laya("other"), PageFacts("", title = "City approves bike lanes"))
        assertEquals("safe", v.level)
        assertEquals(0, v.score)
    }

    @Test
    fun unknownSiteLoginIsSafe() {
        assertEquals("safe", verdict("https://portal.smallclinic.example/login", laya("sign_in"), PageFacts("", title = "Patient portal", passwordFields = 1)).level)
    }

    @Test
    fun layaAloneNeverMakesDanger() {
        val loud = laya("pay", urgency = 0.99, prize = 0.99)
        val v = verdict("https://deals.example.com/", loud)
        assertTrue(v.level != "danger" && v.score <= SiteScoring.LAYA_CAP)
        // weak deterministic signals plus a loud Laya are still capped below danger ...
        val w = verdict("https://a.b.c.d.e.deals.xyz/" + "a".repeat(230), loud)
        assertEquals(SiteScoring.NO_RISK_CAP, w.score)
        assertTrue("no_deterministic_risk" in w.gates)
        // ... unless the page really asks for a password or card on an unknown domain
        assertEquals("danger", verdict("https://a.b.c.d.e.deals.xyz/" + "a".repeat(230), laya("pay", urgency = 0.99, prize = 0.99), PageFacts("", cardFields = 1)).level)
    }

    @Test
    fun layaOnlyNeedsTwoScamCuesForCaution() {
        assertEquals("safe", verdict("https://blog.example.com/", laya("other", urgency = 0.95)).level)
        assertEquals("safe", verdict("https://blog.example.com/", laya("pay", askP = 0.4, urgency = 0.6, prize = 0.6)).level)
        assertEquals("caution", verdict("https://win.example.com/", laya("claim", urgency = 0.95, prize = 0.95)).level)
    }

    @Test
    fun weakQuestionsNeverScore() {
        assertEquals(0, verdict("https://blog.example.com/", laya("other", claims = "bank")).score)
        assertEquals(setOf("claims_to_be"), SiteScoring.PAGE_RISK_WEAK)
    }

    @Test
    fun allowlistedIsSafeWhateverTheSignals() {
        val v = verdict("https://paypa1.com/", laya("sign_in", urgency = 0.99), PageFacts("", passwordFields = 1), allowlisted = true)
        assertEquals(SiteVerdict(0, "safe", listOf(SiteReason("user_trusted", SiteScoring.REASON_TEXT.getValue("user_trusted"), 0, "user")), listOf("user_trusted")), v)
    }

    @Test
    fun urgentLoginOnAnUnknownDomainIsCautionNeverDanger() {
        val v = verdict("https://bank-verify.example/login", laya("sign_in", urgency = 0.95), PageFacts("", passwordFields = 1))
        assertEquals("caution", v.level)
        assertTrue("pressure_login" in v.reasons.map { it.code })
        val w = verdict("https://bank-verify.example/login", laya("sign_in", urgency = 0.95, prize = 0.95), PageFacts("", passwordFields = 1))
        assertTrue(w.level == "caution" && w.score <= SiteScoring.NO_RISK_CAP)
        assertFalse("pressure_login" in verdict("https://blog.example.com/", laya("other", urgency = 0.95)).reasons.map { it.code })
    }

    // ------------------------------------------------------------------ one verdict (formula, 2026-09-24)
    @Test
    fun siteCheckShowsOneVerdict() {
        val c = SiteCheck.checkUrl("http://paypal.account-verify.example/login")
        assertTrue("brand_in_subdomain" in c.verdict.reasons.map { it.code })
        assertTrue(c.warn)
        assertEquals("Caution", c.levelTitle)
        assertTrue(c.lines.none { it.startsWith("Engine: ") })
        val homograph = SiteCheck.checkUrl("https://${idn("pаypal.com")}/signin")
        assertEquals("danger", homograph.verdict.level)
        assertTrue("homograph_brand" in homograph.verdict.reasons.map { it.code })
        val real = SiteCheck.checkUrl("https://www.paypal.com/signin")
        assertFalse(real.warn)
        assertEquals("No warning signs found", real.levelTitle)                                            // never "safe"
    }

    @Test
    fun punycodeRoundTrips() {
        assertEquals("xn--mgbh0fb", Hosts.toAsciiLabel("مثال"))
        assertEquals("مثال", Hosts.decodeLabel("xn--mgbh0fb"))
        assertEquals("xn--pypal-4ve", Hosts.toAsciiLabel("pаypal"))
        assertEquals("xn--broken!", Hosts.decodeLabel("xn--broken!"))
        assertNull(ParsedUrl.parse("http://[::1/")) // unbalanced bracket: urlsplit raises
    }
}
