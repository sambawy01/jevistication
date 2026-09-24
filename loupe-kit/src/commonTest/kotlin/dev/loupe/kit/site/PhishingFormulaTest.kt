package dev.loupe.kit.site

import dev.loupe.kit.mail.Phishing
import dev.loupe.kit.watchers.PHISHING_VECTORS
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared phishing / site formula (docs/PHISHING-FORMULA.md) against its JSON test vectors
 * (docs/phishing-vectors.json), which Loupe Station runs too, plus the online-facts plumbing.
 * Runs on the JVM and the iOS simulator.
 */
class PhishingFormulaTest {
    private val now = "2026-09-24T12:00:00Z"

    @Test
    fun everyVectorAgrees() {
        val text = assertNotNull(PlatformFiles.readText(PHISHING_VECTORS), PHISHING_VECTORS)
        val (at, vectors) = PhishingVectors.vectors(text)
        assertTrue(vectors.size >= 50, "vectors: ${vectors.size}")
        val problems = vectors.flatMap { PhishingVectors.check(it, PhishingVectors.run(it, at)) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theVectorsCoverEveryRule() {
        val (_, vectors) = PhishingVectors.vectors(PlatformFiles.readText(PHISHING_VECTORS)!!)
        val ids = vectors.map { it.id }.toSet()
        for (id in listOf(
            "microsoft-on-live-com", "idn-arabic", "homograph-paypal", "mixed-script-google", "card-posts-elsewhere",
            "search-posts-elsewhere", "github-pages", "google-storage-bucket", "wordpress-customer", "contact-homograph-domain",
            "online-young-domain", "online-no-sources", "online-private-suffix-no-facts", "online-feed-url", "online-safe-browsing",
            "online-young-sender-alone", "sample-paypal-phish",
        )) assertTrue(id in ids, id)
    }

    // ------------------------------------------------------------------ the two PSL modes
    @Test
    fun hostControlAndOnlineDomainAreDifferentQuestions() {
        assertEquals("x.github.io", Hosts.registrableDomain("x.github.io"))              // full PSL: the account owner controls it
        assertNull(OnlineSignals.lookupDomain("x.github.io"))                             // ICANN-only differs: no online facts
        assertEquals("storage.googleapis.com", Hosts.registrableDomain("storage.googleapis.com"))
        assertNull(OnlineSignals.lookupDomain("storage.googleapis.com"))
        assertNull(OnlineSignals.lookupDomain("my-shop.wordpress.com"))                   // shared hosting by name
        assertEquals("example.co.uk", OnlineSignals.lookupDomain("shop.example.co.uk"))
        assertEquals("paypal.com", OnlineSignals.lookupDomain("www.PayPal.com."))
        assertEquals("xn--mgbh0fb.xn--wgbh1c", OnlineSignals.lookupDomain("مثال.مصر"))      // sent in ASCII
        for (h in listOf("192.168.1.1", "45.33.32.156", "localhost", "nas.local", "co.uk", "")) assertNull(OnlineSignals.lookupDomain(h), h)
    }

    // ------------------------------------------------------------------ the helper's answer
    @Test
    fun parsesDomainFacts() {
        val f = OnlineSignals.parseDomainFacts(
            """{"domain":"fresh-shop.example","fetched_at":"2026-09-24T11:00:00Z","sources":["rdap","crtsh"],
               "registration":{"created":"2026-09-21T09:00:00Z","updated":null,"expires":"2027-09-21","registrar":"Example Registrar","rdap_server":"https://rdap.example"},
               "certificates":{"first_seen":"2026-09-22T00:00:00Z","latest_issued":"2026-09-23T00:00:00Z","count_90d":3,"issuers":["R10"]},"errors":[]}""",
            "fresh-shop.example",
        )
        assertTrue(f.hasFacts)
        assertEquals("Example Registrar", f.registrar)
        assertEquals(3, f.count90d)
        assertEquals(listOf("R10"), f.issuers)
        assertEquals(listOf("domain_new_week", "cert_new"), OnlineSignals.ageKinds(f, now))
        // `sources: []`: no facts, whatever else is there, and never labelled "checked"
        val none = OnlineSignals.parseDomainFacts("""{"domain":"x.example","sources":[],"registration":{"created":"2026-09-23"}}""", "x.example")
        assertFalse(none.hasFacts)
        assertNull(none.created)
        assertTrue(OnlineSignals.ageKinds(none, now).isEmpty())
        // an answer about another domain, or not JSON, is refused
        assertFailsWith<IllegalArgumentException> { OnlineSignals.parseDomainFacts("""{"domain":"other.example","sources":["rdap"]}""", "x.example") }
        assertFailsWith<IllegalArgumentException> { OnlineSignals.parseDomainFacts("<html>", "x.example") }
    }

    @Test
    fun ageBuckets() {
        fun kinds(created: String) = OnlineSignals.ageKinds(DomainFacts("d.example", now, listOf("rdap"), created = created), now)
        assertEquals(listOf("domain_new_week"), kinds("2026-09-20"))
        assertEquals(listOf("domain_new_month"), kinds("2026-09-01"))
        assertEquals(listOf("domain_new_halfyear"), kinds("2026-04-01"))
        assertTrue(kinds("2025-01-01").isEmpty())
        assertTrue(kinds("2027-01-01").isEmpty())                                            // in the future: ignored
        assertTrue(kinds("not a date").isEmpty())
    }

    // ------------------------------------------------------------------ feeds
    @Test
    fun feedMatching() {
        val feeds = FeedIndex(mapOf(
            "openphish" to FeedIndex.parseOpenPhish("https://login-check.example/verify?id=7\nnot a url\nhttps://docs.google.com/forms/d/phish\n"),
            "phishtank" to FeedIndex.parsePhishTank("""[{"url":"http://bad-domain.example/"},{"phish_id":1}]"""),
        ))
        assertEquals(mapOf("openphish" to 2, "phishtank" to 1), feeds.counts)
        assertEquals(FeedHit("openphish", "url"), feeds.match("http://LOGIN-CHECK.example/verify?id=7#x"))
        assertEquals(FeedHit("openphish", "host"), feeds.match("https://login-check.example/elsewhere"))
        assertEquals(FeedHit("phishtank", "domain"), feeds.match("https://a.bad-domain.example/x"))
        assertNull(feeds.match("https://docs.google.com/forms/other"))                        // path-shared host: exact URL only
        assertEquals(FeedHit("openphish", "url"), feeds.match("https://docs.google.com/forms/d/phish"))
        assertNull(feeds.match("https://example.com/"))
    }

    // ------------------------------------------------------------------ gates
    @Test
    fun aYoungDomainAloneNeverFlagsAMessage() {
        val ctx = OnlineContext(now, mapOf("fresh-shop.example" to DomainFacts("fresh-shop.example", now, listOf("rdap", "crtsh"), created = "2026-09-21", firstSeen = "2026-09-23")))
        val v = Phishing.assess("Deals <hello@fresh-shop.example>", "New arrivals.", online = ctx)
        assertFalse(v.flag)
        assertEquals(Phishing.PHISHING_AT - 1, v.score)
        assertEquals(listOf("online_age_only_cap"), v.gates)
        assertTrue(v.reasons.all { it.source == "online" && it.params["online_source"] != null })
        // with the online checks off nothing online is scored
        assertEquals(0, Phishing.assess("Deals <hello@fresh-shop.example>", "New arrivals.").score)
    }

    @Test
    fun onlineReasonsAreLabelled() {
        val ctx = OnlineContext(now, feeds = FeedIndex(mapOf("openphish" to listOf("https://login-check.example/verify"))), feedsFetchedAt = now)
        val c = SiteCheck.checkUrl("https://login-check.example/verify", ctx)
        assertEquals("danger", c.verdict.level)
        val r = c.verdict.reasons.single { it.code == "online_phish_list_url" }
        assertEquals("online", r.source)
        assertEquals("OpenPhish", r.params["online_source"])
        assertEquals(now, r.params["fetched_at"])
        assertEquals("Online · OpenPhish · fetched $now: " + r.text, c.lines.single())
        // the same URL without the online checks: no signal
        assertFalse(SiteCheck.checkUrl("https://login-check.example/verify").warn)
    }

    @Test
    fun everyCodeHasWordsAndAWeight() {
        val placeholder = Regex("\\{(\\w+)\\}")
        for ((code, wt) in OnlineSignals.PAGE_WEIGHTS) {
            assertTrue(placeholder.findAll(wt.second).all { it.groupValues[1] in SiteScoring.REASON_PARAMS }, code)
            assertTrue(code in SiteScoring.REASON_CODES, code)
        }
        for ((code, wt) in Phishing.WEIGHTS) {
            assertTrue(placeholder.findAll(wt.second).all { it.groupValues[1] in Phishing.REASON_PARAMS }, code)
        }
        assertFalse("sender_punycode" in Phishing.WEIGHTS)
    }
}
