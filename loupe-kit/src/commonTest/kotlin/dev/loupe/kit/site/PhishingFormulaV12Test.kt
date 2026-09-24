package dev.loupe.kit.site

import dev.loupe.kit.watchers.PHISHING_VECTORS
import dev.loupe.kit.watchers.PHISHING_VECTORS_V12
import dev.loupe.kit.watchers.SHARED_HOSTS_JSON
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared phishing formula v1.2 (site facts, docs/PHISHING-FORMULA.md §5b–§6.1) against its 51
 * vectors (docs/phishing-vectors-v1.2.json, byte-identical to Loupe Station's
 * tests/fixtures/phishing-vectors-v1.2.json), run the way Station's tests/test_formula_v12.py runs
 * them: the raw resolver answers go through the production DNS-fact and blocklist parsing with a
 * fake resolver (no network, no real DNS), then through [SiteScoring.combine]. JVM and iOS sim.
 */
class PhishingFormulaV12Test {
    private val data: JsonValue.Obj by lazy { JsonValue.parse(assertNotNull(PlatformFiles.readText(PHISHING_VECTORS_V12), PHISHING_VECTORS_V12)).asObj }

    private fun JsonValue.Obj.s(k: String): String? = (this[k] as? JsonValue.Str)?.value
    private fun JsonValue.Obj.b(k: String): Boolean = (this[k] as? JsonValue.Bool)?.value ?: false
    private fun strings(v: JsonValue?): List<String> = (v as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()

    /** Station's FakeResolver: a question not listed is NXDOMAIN; TIMEOUT is no answer. */
    private class FakeResolver(answers: Map<String, JsonValue.Obj>) : DnsQuery {
        val answers = answers.mapKeys { it.key.lowercase() }
        val asked = mutableListOf<String>()
        override fun query(name: String, type: String): DnsAnswer? {
            val key = "$name $type".lowercase()
            asked += key
            val a = answers[key] ?: return DnsAnswer(DnsWire.NXDOMAIN)
            val rcode = (a["rcode"] as? JsonValue.Str)?.value ?: "NOERROR"
            if (rcode == "TIMEOUT") return null
            val rc = mapOf("NOERROR" to 0, "SERVFAIL" to 2, "NXDOMAIN" to 3, "REFUSED" to 5).getValue(rcode)
            val recs = (a["answers"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
            return DnsAnswer(rc, recs, (a["ad"] as? JsonValue.Bool)?.value ?: false)
        }
    }

    /** Laya cue -> the wf-page-risk answer (Station's CUES / laya_summary; one answer per question). */
    private fun laya(cues: JsonValue.Obj?): Map<String, LayaPageAnswer>? {
        if (cues == null) return null
        val out = LinkedHashMap<String, LayaPageAnswer>()
        for ((code, v) in cues.fields) {
            val strong = (v as JsonValue.Str).value == "strong"
            when (code) {
                "laya_asks_sign_in" -> out["asks_visitor_to"] = LayaPageAnswer(if (strong) 0.9 else 0.55, choice = "sign_in")
                "laya_asks_payment" -> out["asks_visitor_to"] = LayaPageAnswer(if (strong) 0.9 else 0.55, choice = "pay")
                "laya_asks_install" -> out["asks_visitor_to"] = LayaPageAnswer(if (strong) 0.9 else 0.55, choice = "install")
                "laya_asks_claim" -> out["asks_visitor_to"] = LayaPageAnswer(if (strong) 0.9 else 0.55, choice = "claim")
                "laya_urgency" -> out["urgency_or_threat"] = LayaPageAnswer(if (strong) 0.9 else 0.6, yes = true)
                "laya_prize" -> out["offers_prize_or_refund"] = LayaPageAnswer(if (strong) 0.9 else 0.6, yes = true)
                else -> error("unknown cue $code")
            }
        }
        return out
    }

    data class Got(
        val level: String, val score: Int, val signals: List<String>, val notCounted: List<String>, val facts: List<String>,
        val reassuring: List<String>, val tier: String, val paymentExpected: Boolean, val hostControl: String?, val onlineDomain: String?,
    )

    private fun run(v: JsonValue.Obj): Got {
        val now = data.s("now")!!
        val url = v.s("url")!!
        val forms = (v["forms"] as? JsonValue.Arr)?.items?.map { f -> val o = f.asObj; PageForm(o.s("action"), o.b("password"), o.b("card")) }.orEmpty()
        val page = PageFacts(
            url = url, title = v.s("title"), passwordFields = if (v.b("password")) 1 else 0, cardFields = if (v.b("card")) 1 else 0,
            forms = forms, claimedBrand = v.s("claimedBrand"), embeds = strings(v["embeds"]),
        )
        val (det, facts) = SiteSignals.urlAndPageSignals(page)
        val host = facts.host
        var online: List<SiteReason> = emptyList()
        var site: SiteFacts? = null
        val spec = v["online"] as? JsonValue.Obj
        if (spec != null && host.isNotEmpty() && facts.scheme in setOf("http", "https") && !facts.knownGood) {
            val domain = OnlineSignals.lookupDomain(host)
            val factMap = (spec["facts"] as? JsonValue.Obj)?.fields?.mapValues { (d, f) -> OnlineSignals.parseDomainFacts(JsonValue.compactOf(f, d), d) }.orEmpty()
            val feedSpecs = (spec["feeds"] as? JsonValue.Obj)?.fields?.mapValues { strings(it.value) }.orEmpty()
            val feeds = feedSpecs.filterKeys { it != PhishingDb.SOURCE }.takeIf { it.isNotEmpty() }?.let(::FeedIndex)
            // Phishing.Database vectors go through the phone's own index (hash arrays), not FeedIndex.
            val pdb = feedSpecs[PhishingDb.SOURCE]?.let { PhishingDb.build(listOf(it.joinToString("\n")), emptyList(), now) }
            val answers = LinkedHashMap<String, JsonValue.Obj>()
            (spec["answers"] as? JsonValue.Obj)?.fields?.forEach { (k, a) -> answers[k] = a.asObj }
            for ((source, tp) in (data["testPoints"] as JsonValue.Obj).fields) {
                val z = Dnsbl.zone(source)!!
                answers.getOrPut("${z.test}.${z.zone} A") { tp.asObj }
            }
            val resolver = FakeResolver(answers)
            val dns = if (spec.b("dns") && domain != null) mapOf(domain to DnsFactsReader.read(resolver, domain, now)) else emptyMap()
            val lists = strings(spec["dnsbl"])
            val bl = if (lists.isNotEmpty() && domain != null) mapOf(domain to DnsblClient(resolver) { 0L }.check(domain, lists)) else emptyMap()
            val ctx = OnlineContext(
                now, factMap, feeds, strings(spec["safeBrowsing"]).toSet(), now, now,
                phishingDb = pdb, dns = dns, dnsbl = bl, dnsblFetchedAt = now,
            )
            online = OnlineSignals.pageReasons(url, ctx)
            site = ctx.site(domain)
        }
        val verdict = SiteScoring.combine(det, facts, laya(v["laya"] as? JsonValue.Obj), online = online, site = site, nowIso = now)
        return Got(
            verdict.level, verdict.score, verdict.reasons.filter { it.weight > 0 }.map { it.code }.sorted(),
            verdict.notCounted.map { it.code }.sorted(), verdict.facts.map { it.code }.sorted(),
            verdict.facts.filter { it.tone == "good" }.map { it.code }.sorted(), verdict.context.tier, verdict.context.paymentExpected,
            if (host.isNotEmpty()) Hosts.registrableDomain(host) else null, if (host.isNotEmpty()) OnlineSignals.lookupDomain(host) else null,
        )
    }

    private fun differences(v: JsonValue.Obj, got: Got): List<String> {
        val e = v["expect"]!!.asObj
        val id = v.s("id")
        val out = mutableListOf<String>()
        fun cmp(key: String, actual: Any?) {
            if (!e.fields.containsKey(key)) return
            val want: Any? = when (val x = e[key]) {
                is JsonValue.Str -> x.value
                is JsonValue.Num -> x.text.toInt()
                is JsonValue.Bool -> x.value
                is JsonValue.Arr -> strings(x).sorted()
                else -> null
            }
            if (want != actual) out += "$id: $key $actual, expected $want"
        }
        cmp("level", got.level); cmp("score", got.score); cmp("tier", got.tier); cmp("paymentExpected", got.paymentExpected)
        cmp("hostControl", got.hostControl); cmp("onlineDomain", got.onlineDomain)
        cmp("signals", got.signals); cmp("notCounted", got.notCounted); cmp("facts", got.facts); cmp("reassuring", got.reassuring)
        return out
    }

    private val pages: List<JsonValue.Obj> get() = (data["pages"] as JsonValue.Arr).items.map { it.asObj }

    @Test
    fun everyV12VectorAgrees() {
        assertEquals(51, pages.size)
        val problems = pages.flatMap { differences(it, run(it)) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun fileShape() {
        assertEquals("loupe-phishing-formula", data.s("formula"))
        assertEquals("1.2", data.s("version"))
        val v11 = JsonValue.parse(PlatformFiles.readText(PHISHING_VECTORS)!!).asObj
        val ext = data["extends"]!!.asObj
        assertEquals("1.1", ext.s("version"))
        assertEquals(v11.s("psl"), data.s("psl"))
        val n11 = (v11["pages"] as JsonValue.Arr).items.size + (v11["emails"] as JsonValue.Arr).items.size
        assertEquals(57, n11)
        assertEquals("57", (ext["vectors"] as JsonValue.Num).text)
        val ids = pages.map { it.s("id") }
        assertEquals(ids.size, ids.toSet().size)
        val old = (v11["pages"] as JsonValue.Arr).items.map { it.asObj.s("id") }.toSet()
        assertTrue(ids.none { it in old })
        assertEquals(Dnsbl.IDS.toSet(), (data["testPoints"] as JsonValue.Obj).fields.keys)
    }

    @Test
    fun processorTableIsRegistrableDomains() {
        for (d in SiteContext.PAYMENT_PROCESSORS.keys) assertEquals(d, Hosts.registrableDomain(d), d)
    }

    @Test
    fun sharedHostsAreStationsFileVerbatim() {
        val json = JsonValue.parse(PlatformFiles.readText(SHARED_HOSTS_JSON)!!).asObj
        val hosts = strings(json["hosts"])
        assertEquals(74, hosts.size)
        assertEquals(hosts.map { it.lowercase() }, SharedHostsData.HOSTS)
        assertEquals("loupe-list-shared-hosts", json.s("name"))
        assertTrue(ListUrls.isSuppressed("mailchi.mp") && ListUrls.isSuppressed("shop.weebly.com") && ListUrls.isSuppressed("x.blogspot.com"))
        assertFalse(ListUrls.isSuppressed("login-check.example"))
    }

    // ------------------------------------------------------------------ DNS wire format
    private fun response(qid: Int, name: String, type: Int, rcode: Int, ad: Boolean, answers: List<Pair<Int, ByteArray>>): ByteArray {
        val b = ArrayList<Byte>()
        fun u16(v: Int) { b += ((v shr 8) and 0xFF).toByte(); b += (v and 0xFF).toByte() }
        u16(qid); u16(0x8000 or 0x0100 or 0x0080 or (if (ad) 0x0020 else 0) or rcode); u16(1); u16(answers.size); u16(0); u16(0)
        DnsWire.encodeName(name).forEach { b += it }
        u16(type); u16(1)
        for ((t, rdata) in answers) {
            b += 0xC0.toByte(); b += 12                     // pointer to the question name
            u16(t); u16(1); u16(0); u16(300); u16(rdata.size)
            rdata.forEach { b += it }
        }
        return b.toByteArray()
    }

    @Test
    fun wireQueryAndAnswers() {
        val q = DnsWire.buildQuery(0x1234, "Example.com.", "TXT")
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(0x01, q[2].toInt()); assertEquals(0x20, q[3].toInt())          // RD + AD
        assertEquals(41, q[q.size - 9].toInt())                                    // the EDNS0 OPT record
        val txt = byteArrayOf(5) + "v=spf".encodeToByteArray() + byteArrayOf(3) + "1 a".encodeToByteArray()
        val a = DnsWire.parseResponse(response(0x1234, "example.com", 16, 0, true, listOf(16 to txt)), 0x1234, "example.com", "TXT")
        assertEquals(DnsAnswer(0, listOf("v=spf1 a"), true), a)
        val mx = byteArrayOf(0, 10) + DnsWire.encodeName("mx.example.com")
        assertEquals(listOf("10 mx.example.com"), DnsWire.parseResponse(response(7, "example.com", 15, 0, false, listOf(15 to mx)), 7, "example.com", "MX").records)
        assertEquals(listOf("127.0.1.4"), DnsWire.parseResponse(response(9, "x.dbl.spamhaus.org", 1, 0, false, listOf(1 to byteArrayOf(127, 0, 1, 4))), 9, "x.dbl.spamhaus.org", "A").records)
        assertEquals(3, DnsWire.parseResponse(response(9, "x.example", 1, 3, false, emptyList()), 9, "x.example", "A").rcode)
        // wrong ID, wrong question, truncated, a pointer loop: no answer
        val ok = response(9, "x.example", 1, 0, false, listOf(1 to byteArrayOf(1, 2, 3, 4)))
        for ((bytes, name) in listOf(ok to "y.example", ok.copyOf(ok.size - 2) to "x.example", ok.copyOf(10) to "x.example")) {
            assertTrue(runCatching { DnsWire.parseResponse(bytes, 9, name, "A") }.isFailure, name)
        }
        assertTrue(runCatching { DnsWire.parseResponse(ok, 10, "x.example", "A") }.isFailure)
        val loop = ok.copyOf().also { it[12] = 0xC0.toByte(); it[13] = 12 }
        assertTrue(runCatching { DnsWire.parseResponse(loop, 9, "x.example", "A") }.isFailure)
        assertTrue(runCatching { DnsWire.encodeName("bad name.example") }.isFailure)
        // the resolver over a transport: the answer must echo the query's random ID
        val r = WireResolver({ packet ->
            val id = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
            response(id, "x.example", 1, 0, true, listOf(1 to byteArrayOf(192.toByte(), 0, 2, 1)))
        })
        assertEquals(DnsAnswer(0, listOf("192.0.2.1"), true), r.query("x.example", "A"))
        assertNull(WireResolver({ null }).query("x.example", "A"))
        assertNull(WireResolver({ ByteArray(3) }).query("x.example", "A"))
    }

    @Test
    fun dnsFactsNeverTurnUnknownIntoNo() {
        val fails = DnsQuery { _, _ -> null }
        val f = DnsFactsReader.read(fails, "x.example")
        assertNull(f.mx); assertNull(f.spf); assertNull(f.dmarc); assertNull(f.dnssec)
        assertEquals(listOf("a", "mx", "spf", "dmarc", "bimi"), f.errors)
        assertEquals("invalid", DnsFactsReader.dmarcPolicy(listOf("v=DMARC1; p=reject", "v=DMARC1; p=none")))
        assertEquals("absent", DnsFactsReader.dmarcPolicy(listOf("v=spf1 -all")))
        assertEquals("quarantine", DnsFactsReader.dmarcPolicy(listOf("v=DMARC1; p=quarantine; rua=mailto:x@x")))
        val nx = DnsFactsReader.read({ _, _ -> DnsAnswer(DnsWire.NXDOMAIN) }, "gone.example")
        assertEquals(false, nx.exists); assertEquals(false, nx.mx); assertEquals("absent", nx.dmarc)
    }

    @Test
    fun blocklistCodesAndTestPointCache() {
        assertEquals(DnsblResult("listed", "phish"), Dnsbl.interpret("spamhaus_dbl", 0, listOf("127.0.1.4")))
        assertEquals(DnsblResult("listed", "spam"), Dnsbl.interpret("spamhaus_dbl", 0, listOf("127.0.1.2")))
        for (bad in listOf("127.255.255.254", "127.0.1.255", "203.0.113.7", "127.0.0.1")) {
            assertEquals("unavailable", Dnsbl.interpret("spamhaus_dbl", 0, listOf(bad)).status, bad)
        }
        assertEquals("unavailable", Dnsbl.interpret("surbl", 0, listOf("127.0.0.1")).status)
        assertEquals("unavailable", Dnsbl.interpret("uribl", 0, listOf("127.0.0.1")).status)
        assertEquals("clean", Dnsbl.interpret("uribl", 0, listOf("127.0.0.4")).status)          // grey alone
        assertEquals("unavailable", Dnsbl.interpret("surbl", 2, emptyList()).status)            // SERVFAIL
        assertEquals("unavailable", Dnsbl.interpret("surbl", null, emptyList()).status)         // timeout
        assertEquals("clean", Dnsbl.interpret("surbl", 3, emptyList()).status)
        var clock = 0L
        var asked = 0
        val r = DnsQuery { name, _ ->
            asked++
            if (name == "test.uribl.com.multi.uribl.com") DnsAnswer(0, listOf("127.0.0.14")) else DnsAnswer(DnsWire.NXDOMAIN)
        }
        val c = DnsblClient(r) { clock }
        assertEquals(mapOf("uribl" to DnsblResult("clean")), c.check("a.example", listOf("uribl", "nope")))
        assertEquals(2, asked)
        c.check("b.example", listOf("uribl"))
        assertEquals(3, asked)                                  // the test point is cached for an hour
        clock += Dnsbl.HEALTH_TTL_S + 1
        c.check("b.example", listOf("uribl"))
        assertEquals(5, asked)
        // a refused test point: the domain is never sent
        val sent = mutableListOf<String>()
        val refused = DnsblClient({ name, _ -> sent += name; DnsAnswer(0, listOf("127.0.0.1")) }) { 0L }
        assertEquals(mapOf("uribl" to DnsblResult("unavailable")), refused.check("secret.example", listOf("uribl")))
        assertEquals(listOf("test.uribl.com.multi.uribl.com"), sent)
    }

    @Test
    fun displayGroupsWarningsFactsAndNotCounted() {
        val now = "2026-09-24T12:00:00Z"
        val ctx = OnlineContext(
            now, mapOf("oldstore.example" to DomainFacts("oldstore.example", now, listOf("rdap"), created = "2015-02-10T00:00:00Z")),
            dns = mapOf("oldstore.example" to DnsFacts(mx = true, spf = true, dmarc = "reject", dnssec = false)),
        )
        val c = SiteCheck.checkUrl("https://shop.oldstore.example/", ctx)
        assertFalse(c.warn)
        assertEquals("established", c.verdict.context.tier)
        assertTrue(c.reassuringFacts.any { it.startsWith("Registered 11 years ago") }, c.reassuringFacts.toString())
        assertTrue(c.reassuringFacts.contains("The domain tells mail servers to reject forged email (DMARC)."))
        val cues = SiteScoring.verdict(PageFacts("https://resort.example/"), laya = mapOf("offers_prize_or_refund" to LayaPageAnswer(0.9, yes = true)))
        assertEquals(listOf("laya_prize"), cues.notCounted.map { it.code })
        assertEquals("Laya's reading: the page says you won something or will get money back. Only Laya's reading of the text; nothing else about this site backs it up.",
            SiteCheckResult("https://resort.example/", cues).notCountedLines.single())
    }
}
