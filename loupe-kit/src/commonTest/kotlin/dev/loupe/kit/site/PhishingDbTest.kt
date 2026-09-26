package dev.loupe.kit.site

import dev.loupe.kit.watchers.PHISHINGDB_FIXTURES
import dev.loupe.kit.watchers.PHISHING_VECTORS
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phishing.Database on the phone (owner decision B): parsing, the sanity check, the recent-entries
 * merge, matching and the shared-host suppression. Ported from Loupe Station's
 * tests/test_phishingdb.py (commit 4cb9026); the samples in src/commonTest/fixtures/phishingdb are
 * Station's, with their NOTICE (MIT, Phishing.Database). No network: the phone's downloader is
 * tested in Swift with a fake URL protocol.
 */
class PhishingDbTest {
    private fun fixture(name: String): String = assertNotNull(PlatformFiles.readText("$PHISHINGDB_FIXTURES/$name"), name)

    @Test
    fun parseLines() {
        assertEquals("login-check.example", PhishingDb.parseDomain("Login-Check.Example."))
        assertEquals("xn--mgbh0fb.xn--wgbh1c", PhishingDb.parseDomain("مثال.مصر"))
        for (junk in listOf("", "# comment", "1.2.3.4", "not a host", "-bad-.com", "a..b.com", "<html>")) {
            assertNull(PhishingDb.parseDomain(junk), junk)
        }
        assertEquals(Triple("evil.example/Path?q=1", "evil.example", false), PhishingDb.parseLink("HTTPS://Evil.EXAMPLE/Path/?q=1#frag"))
        assertEquals(true, PhishingDb.parseLink("http://00000000000000000update.emy.ba")?.third)
        assertNull(PhishingDb.parseLink("ftp://x.example/"))
        assertNull(PhishingDb.parseLink("evil.example/path"))
    }

    @Test
    fun theFixturesAreRealSamplesThatPassTheCheck() {
        assertEquals(40, PhishingDb.check(fixture("phishing-links-ACTIVE.txt"), "links", 20).valid)
        assertEquals(40, PhishingDb.check(fixture("phishing-domains-ACTIVE.txt"), "domains", 20).valid)
        assertTrue(fixture("NOTICE").contains("MIT License"))
        // an ACTIVE file needs 1,000 lines: a 40-line sample is refused as a full list
        assertEquals("too_few_lines", PhishingDb.checkFile(fixture("phishing-links-ACTIVE.txt"), "links", "full").reason)
    }

    @Test
    fun sanityCheckRefusesBadFiles() {
        val cases = listOf(
            Triple("<!DOCTYPE html>\n<html><head><title>Redirecting...</title>", "links", "html"),   // phish.co.za's soft 404
            Triple("<html><body><h1>403 Forbidden</h1>", "domains", "html"),
            Triple("http://a.example/x\nhttp://b.example/y\n", "links", "too_few_lines"),
            Triple((0 until 50).joinToString("") { "some words $it\n" }, "links", "unparsable"),
            Triple((0 until 50).joinToString("") { "not/a host $it\n" }, "domains", "unparsable"),
        )
        for ((body, kind, why) in cases) assertEquals(PhishingDb.Checked(null, why), PhishingDb.check(body, kind, 20), body.take(20))
        // an unknown name answers 200 text/html: the Content-Type alone refuses it
        assertEquals("html", PhishingDb.check(fixture("phishing-domains-ACTIVE.txt"), "domains", 0, "text/html; charset=utf-8").reason)
        assertEquals(0, PhishingDb.check("", "links", 0).valid)                                   // a NEW file may be empty
    }

    @Test
    fun recentMergeDedupsAndKeepsTheNewest() {
        val merged = PhishingDb.mergeRecent("a.example\nb.example\n", "b.example\nc.example\nnot a host\n", "domains")
        assertEquals("a.example\nb.example\nc.example\n", merged)
        assertEquals("x.example\n", PhishingDb.mergeRecent(null, "x.example", "domains"))
    }

    private val idx: PhishingDbIndex by lazy {
        PhishingDb.build(
            listOf(
                listOf(
                    "https://login-check.example/verify?id=7", "http://bare-listed.example/",
                    "https://sites.google.com/view/paypal-verify-now", "https://evil.weebly.com/login",
                    "https://docs.google.com/forms/d/phish/viewform",
                ).joinToString("\n"),
            ),
            listOf(
                listOf(
                    "acct-verify.example", "mail.host-only.example", "user123.github.io", "shop.wixsite.com",
                    "paypal-login.web.app", "bad.000webhostapp.com", "x.pages.dev", "docs.google.com", "accounts.google.com",
                ).joinToString("\n"),
            ),
            "2026-09-24T09:30:22Z",
        )
    }

    @Test
    fun exactUrlMatchesAfterTheSharedNormalisation() {
        for (u in listOf("https://login-check.example/verify?id=7", "http://LOGIN-CHECK.example/verify/?id=7#top")) {
            assertEquals("url", idx.match(u)?.how, u)
        }
        assertEquals("host", idx.match("https://login-check.example/verify?id=8")?.how)
        assertEquals("url", idx.match("https://sites.google.com/view/paypal-verify-now")?.how)
        assertEquals("url", idx.match("https://evil.weebly.com/login/")?.how)
        assertEquals(PhishingDb.SOURCE, idx.match("https://evil.weebly.com/login/")?.list)
    }

    @Test
    fun domainMatchesExactHostAndRegistrableDomain() {
        assertEquals("host", idx.match("https://acct-verify.example/anything")?.how)
        assertEquals("domain", idx.match("https://www.acct-verify.example/")?.how)
        assertEquals("domain", idx.match("https://deep.sub.acct-verify.example/x")?.how)
        assertEquals("host", idx.match("https://mail.host-only.example/")?.how)
        assertNull(idx.match("https://other.host-only.example/"))                // a listed host is not its domain
        assertNull(idx.match("https://host-only.example/"))
        assertEquals("domain", idx.match("https://a.bare-listed.example/")?.how)  // a root link lists its domain
        assertNull(idx.match("https://example.com/"))
    }

    @Test
    fun sharedHostsOnlyEverMatchByExactUrl() {
        for (u in listOf(
            "https://sites.google.com/view/my-wedding", "https://other.weebly.com/", "https://evil.weebly.com/other",
            "https://user123.github.io/", "https://shop.wixsite.com/", "https://paypal-login.web.app/",
            "https://bad.000webhostapp.com/", "https://x.pages.dev/", "https://docs.google.com/document/d/1",
            "https://accounts.google.com/", "https://www.google.com/",
        )) assertNull(idx.match(u), u)
        assertEquals("url", idx.match("https://docs.google.com/forms/d/phish/viewform")?.how)
        for (h in listOf("my-shop.wordpress.com", "bit.ly", "t.me", "paypal.com", "login.microsoftonline.com")) assertTrue(ListUrls.isSuppressed(h), h)
        for (h in listOf("login-check.example", "google.com.evil.example", "weebly.com.evil.example")) assertTrue(!ListUrls.isSuppressed(h), h)
        for (h in SharedHostsData.HOSTS) {
            assertEquals(h, PhishingDb.parseDomain(h), h)
            assertTrue(ListUrls.isSuppressed(h) && ListUrls.isSuppressed("anyone.$h"), h)
        }
    }

    @Test
    fun theRealSamplesBuildAndMatch() {
        val real = PhishingDb.build(listOf(fixture("phishing-links-ACTIVE.txt")), listOf(fixture("phishing-domains-ACTIVE.txt")), null)
        assertEquals(40, real.linkCount)
        assertEquals("url", real.match("http://00000000000000000update.emy.ba")?.how)
        assertEquals("host", real.match("https://00000000000000000000000000000000000000000.xyz/login")?.how)
        // a sample under 000webhostapp.com (a shared host) never matches by host
        assertNull(real.match("https://000000000000000000gg.000webhostapp.com/other"))
        // the cap: entries beyond it are dropped and the index says so
        val capped = PhishingDb.build(listOf(fixture("phishing-links-ACTIVE.txt")), emptyList(), null, maxEntries = 10)
        assertTrue(capped.truncated)
        assertEquals(10, capped.linkCount)
    }

    @Test
    fun theV11VectorsPassWithPhishingDbAsTheList() {
        // Station's test_the_57_vectors_pass_also_with_phishingdb_as_the_list: every list vector,
        // with its feed loaded as Phishing.Database instead, gives the same verdict.
        val (at, vectors) = PhishingVectors.vectors(PlatformFiles.readText(PHISHING_VECTORS)!!)
        val problems = mutableListOf<String>()
        for (v in vectors) {
            val online = v.input["online"] as? JsonValue.Obj ?: continue
            val feeds = online["feeds"] as? JsonValue.Obj ?: continue
            val swapped = JsonValue.Obj(LinkedHashMap(online.fields).also {
                it["feeds"] = JsonValue.obj(PhishingDb.SOURCE to JsonValue.Arr(feeds.fields.values.flatMap { (it as JsonValue.Arr).items }))
            })
            val input = JsonValue.Obj(LinkedHashMap(v.input.fields).also { it["online"] = swapped })
            problems += PhishingVectors.check(v, PhishingVectors.run(v.copy(input = input), at))
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}

/** The flat index file the Safari extension maps (2026-09-26): a round trip matches as the index does. */
class PhishingDbBinaryTest {
    @Test
    fun roundTripMatchesLikeTheIndex() {
        val index = PhishingDb.build(
            listOf("https://evil.example/login?x=1\nhttp://www.bad-host.example/\nhttps://sub.phish.example/a"),
            listOf("listed-domain.example\nwww.other.example"),
            "2026-09-26T00:00:00Z",
        )
        val bytes = PhishingDbBinary.encode(index)
        assertEquals(PhishingDbBinary.MAGIC, bytes.copyOfRange(0, 8).decodeToString())
        assertEquals(0, bytes.size % 8)
        val back = assertNotNull(PhishingDbBinary.decode(bytes))
        assertEquals("2026-09-26T00:00:00Z", back.listDate)
        assertEquals(index.linkCount, back.linkCount)
        assertEquals(index.hostCount, back.hostCount)
        for (u in listOf(
            "https://evil.example/login?x=1", "https://evil.example/other", "https://bad-host.example/",
            "https://x.listed-domain.example/", "https://sub.phish.example/b", "https://example.com/", "https://other.example/",
        )) assertEquals(index.match(u), back.match(u), u)
        assertEquals(FeedHit(PhishingDb.SOURCE, "url"), back.match("https://evil.example/login?x=1"))
        assertEquals(FeedHit(PhishingDb.SOURCE, "domain"), back.match("https://deep.listed-domain.example/page"))
    }

    @Test
    fun emptyAndBrokenFiles() {
        val empty = PhishingDbBinary.encode(PhishingDb.build(emptyList(), emptyList(), null))
        assertEquals(PhishingDbBinary.HEADER_BYTES, empty.size)
        assertNull(assertNotNull(PhishingDbBinary.decode(empty)).listDate)
        assertNull(PhishingDbBinary.decode(ByteArray(10)))
        assertNull(PhishingDbBinary.decode(empty.copyOf(empty.size + 8)))
        val bad = empty.copyOf()
        bad[0] = 'X'.code.toByte()
        assertNull(PhishingDbBinary.decode(bad))
    }
}
