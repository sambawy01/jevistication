package dev.loupe.kit.site

import kotlin.random.Random

/*
 * DNS for the shared phishing formula v1.2 (docs/PHISHING-FORMULA.md §5b, §5c): the DNS /
 * email-authentication facts of a site's registrable domain and the domain blocklists (Spamhaus DBL,
 * SURBL, URIBL). Both are opt-in, off by default, and asked of the device's own system resolver only
 * (no DNS-over-HTTPS provider, no third party). Everything here is pure: the phone hands over the
 * bytes ([DnsTransport], `res_9_send` in Swift) and this builds the question and reads the answer.
 *
 * PROVENANCE: ported from Loupe Station (`~/laya-studio`, commit 4cb9026):
 * `laya_studio/online/dns.py` (wire format: RD + AD bits, EDNS0 OPT 1232, strict bounds checks, the
 * echoed question), `online/dnsfacts.py` (MX, SPF, DMARC policy, DNSSEC, BIMI; unknown is never
 * "no") and `online/dnsbl.py` (zones, codes, test points cached for an hour; an error or refusal
 * code is never a listing). Codes and thresholds are copied verbatim.
 */

/**
 * One answer. [rcode] 0 NOERROR, 2 SERVFAIL, 3 NXDOMAIN, 5 REFUSED; [records] as strings (A
 * "1.2.3.4", MX "10 mx.example.net", TXT the joined character-strings); [ad] the resolver set the
 * AD bit (it validated the answer with DNSSEC: a weak fact, only as good as the path to it).
 */
data class DnsAnswer(val rcode: Int, val records: List<String> = emptyList(), val ad: Boolean = false)

/** Asks one question. Null: no usable answer (timeout, no resolver, garbage). Tests use a fake. */
fun interface DnsQuery {
    fun query(name: String, type: String): DnsAnswer?
}

/**
 * Sends one query packet to the system resolver and returns the raw response, or null (timeout,
 * no network). The phone's implementation is `res_9_send` (libresolv) in Swift.
 */
fun interface DnsTransport {
    fun send(packet: ByteArray): ByteArray?
}

class DnsWireException(val code: String) : Exception(code)

object DnsWire {
    const val NOERROR = 0
    const val SERVFAIL = 2
    const val NXDOMAIN = 3
    const val REFUSED = 5
    const val UDP_SIZE = 1232
    const val MAX_RECORDS = 50
    val TYPES: Map<String, Int> = mapOf("A" to 1, "MX" to 15, "TXT" to 16, "CNAME" to 5)

    fun encodeName(raw: String): ByteArray {
        val name = raw.trim().trimEnd('.').lowercase()
        if (name.isEmpty() || name.length > 253 || name.any { it.code > 127 }) throw DnsWireException("bad_name")
        val out = ArrayList<Byte>()
        for (label in name.split('.')) {
            if (label.length !in 1..63 || !label.all { it.isLetterOrDigit() || it == '-' || it == '_' }) throw DnsWireException("bad_name")
            out += label.length.toByte()
            label.forEach { out += it.code.toByte() }
        }
        out += 0
        return out.toByteArray()
    }

    /** A query: RD and AD set (RFC 6840 §5.7), one question, an EDNS0 OPT record (1232 bytes). */
    fun buildQuery(qid: Int, name: String, type: String): ByteArray {
        val t = TYPES[type] ?: throw DnsWireException("bad_type")
        val flags = 0x0100 or 0x0020
        val b = ArrayList<Byte>()
        fun u16(v: Int) { b += ((v shr 8) and 0xFF).toByte(); b += (v and 0xFF).toByte() }
        u16(qid); u16(flags); u16(1); u16(0); u16(0); u16(1)
        encodeName(name).forEach { b += it }
        u16(t); u16(1)
        b += 0; u16(41); u16(UDP_SIZE); u16(0); u16(0); u16(0)     // root, OPT, size, ext-rcode/version, flags, rdlen
        return b.toByteArray()
    }

    private fun u8(m: ByteArray, i: Int): Int = m[i].toInt() and 0xFF
    private fun u16(m: ByteArray, i: Int): Int = (u8(m, i) shl 8) or u8(m, i + 1)

    /** (name, offset after it). Compression pointers followed with bounds and a hop limit. */
    private fun readName(m: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var off = start
        var end: Int? = null
        var hops = 0
        var total = 0
        while (true) {
            if (off >= m.size) throw DnsWireException("bad_response")
            val n = u8(m, off)
            if (n and 0xC0 == 0xC0) {
                if (off + 1 >= m.size) throw DnsWireException("bad_response")
                val ptr = ((n and 0x3F) shl 8) or u8(m, off + 1)
                if (end == null) end = off + 2
                hops++
                if (hops > 20 || ptr >= m.size) throw DnsWireException("bad_response")
                off = ptr
                continue
            }
            if (n and 0xC0 != 0) throw DnsWireException("bad_response")
            off += 1
            if (n == 0) break
            if (off + n > m.size) throw DnsWireException("bad_response")
            labels += (off until off + n).map { i -> val c = u8(m, i); if (c < 128) c.toChar() else '?' }.joinToString("").lowercase()
            total += n + 1
            if (total > 255) throw DnsWireException("bad_response")
            off += n
        }
        return labels.joinToString(".") to (end ?: off)
    }

    private fun txt(m: ByteArray, from: Int, to: Int): String {
        val parts = StringBuilder()
        var i = from
        while (i < to) {
            val n = u8(m, i)
            if (i + 1 + n > to) throw DnsWireException("bad_response")
            parts.append(m.copyOfRange(i + 1, i + 1 + n).decodeToString())
            i += 1 + n
        }
        return parts.toString()
    }

    /** The answer to [qid]'s question. Checks the ID, QR, the echoed question and every length. */
    fun parseResponse(m: ByteArray, qid: Int, name: String, type: String): DnsAnswer {
        if (m.size < 12) throw DnsWireException("bad_response")
        val rid = u16(m, 0)
        val flags = u16(m, 2)
        val qd = u16(m, 4)
        val an = u16(m, 6)
        if (rid != qid || flags and 0x8000 == 0 || qd != 1) throw DnsWireException("bad_response")
        var (qname, off) = readName(m, 12)
        if (off + 4 > m.size) throw DnsWireException("bad_response")
        val want = TYPES[type] ?: throw DnsWireException("bad_type")
        if (qname != name.trim().trimEnd('.').lowercase() || u16(m, off) != want || u16(m, off + 2) != 1) throw DnsWireException("bad_response")
        off += 4
        val records = mutableListOf<String>()
        repeat(minOf(an, 200)) {
            off = readName(m, off).second
            if (off + 10 > m.size) throw DnsWireException("bad_response")
            val rt = u16(m, off)
            val rc = u16(m, off + 2)
            val rdlen = u16(m, off + 8)
            off += 10
            if (off + rdlen > m.size) throw DnsWireException("bad_response")
            val rdata = off
            off += rdlen
            if (rt != want || rc != 1 || records.size >= MAX_RECORDS) return@repeat        // CNAMEs of the chain: skipped
            when (rt) {
                1 -> {
                    if (rdlen != 4) throw DnsWireException("bad_response")
                    records += (0 until 4).joinToString(".") { u8(m, rdata + it).toString() }
                }
                15 -> {
                    if (rdlen < 3) throw DnsWireException("bad_response")
                    val exch = readName(m, rdata + 2).first
                    records += "${u16(m, rdata)} ${exch.ifEmpty { "." }}"
                }
                16 -> records += txt(m, rdata, off)
            }
        }
        return DnsAnswer(flags and 0x000F, records, flags and 0x0020 != 0)
    }
}

/**
 * A resolver over a [DnsTransport]: builds the query (a fresh random ID each time), sends it,
 * checks and reads the answer. A malformed or mismatched answer is "no answer" (null).
 */
class WireResolver(private val transport: DnsTransport, private val random: Random) : DnsQuery {
    constructor(transport: DnsTransport) : this(transport, Random.Default)

    override fun query(name: String, type: String): DnsAnswer? {
        val qid = random.nextInt(0, 65536)
        val packet = try { DnsWire.buildQuery(qid, name, type) } catch (e: DnsWireException) { return null }
        val raw = transport.send(packet) ?: return null
        return try { DnsWire.parseResponse(raw, qid, name, type) } catch (e: DnsWireException) { null }
    }
}

/**
 * DNS and email-authentication facts about one registrable domain (Station's `dns_facts`). Null
 * always means "could not tell" (the question failed), never "no".
 */
data class DnsFacts(
    val mx: Boolean? = null,
    val nullMx: Boolean = false,
    val spf: Boolean? = null,
    /** "reject" | "quarantine" | "none" | "absent" | "invalid", or null. */
    val dmarc: String? = null,
    val dnssec: Boolean? = null,
    val bimi: Boolean? = null,
    val exists: Boolean? = null,
    val errors: List<String> = emptyList(),
    val fetchedAt: String? = null,
) {
    /** Formula v1.2 weak legitimacy: MX, one SPF record, DMARC quarantine or reject. */
    val mailReady: Boolean get() = mx == true && spf == true && (dmarc == "quarantine" || dmarc == "reject")
}

object DnsFactsReader {
    private val DMARC_P = Regex("""(?:^|;)\s*p\s*=\s*([a-z]+)\s*(?:;|$)""", RegexOption.IGNORE_CASE)

    /** The policy of the one `v=DMARC1` record; "absent" without one; "invalid" for several or a bad `p=`. */
    fun dmarcPolicy(records: List<String>): String {
        val recs = records.map { it.trim() }.filter { it.lowercase().startsWith("v=dmarc1") }
        if (recs.isEmpty()) return "absent"
        if (recs.size > 1) return "invalid"
        val p = DMARC_P.find(recs[0])?.groupValues?.get(1)?.lowercase().orEmpty()
        return if (p in setOf("none", "quarantine", "reject")) p else "invalid"
    }

    private fun ask(r: DnsQuery, name: String, type: String, errors: MutableList<String>, kind: String): DnsAnswer? {
        val a = r.query(name, type)
        if (a == null || (a.rcode != DnsWire.NOERROR && a.rcode != DnsWire.NXDOMAIN)) {
            errors += kind
            return null
        }
        return a
    }

    /** Five questions: A (with AD), MX, TXT (SPF), `_dmarc.` TXT, `default._bimi.` TXT. */
    fun read(r: DnsQuery, domain: String, fetchedAt: String? = null): DnsFacts {
        val errors = mutableListOf<String>()
        var f = DnsFacts(fetchedAt = fetchedAt)
        ask(r, domain, "A", errors, "a")?.let { a ->
            f = f.copy(exists = a.rcode != DnsWire.NXDOMAIN, dnssec = if (a.rcode == DnsWire.NOERROR) a.ad else null)
        }
        if (f.exists == false) return f.copy(mx = false, spf = false, dmarc = "absent", bimi = false, errors = errors)
        ask(r, domain, "MX", errors, "mx")?.let { mx ->
            val hosts = mx.records.map { it.substringAfter(' ', it) }
            val nullMx = hosts == listOf(".")
            f = f.copy(nullMx = nullMx, mx = hosts.isNotEmpty() && !nullMx)
        }
        ask(r, domain, "TXT", errors, "spf")?.let { t ->
            f = f.copy(spf = t.records.count { it.trim().lowercase().startsWith("v=spf1") } == 1)
        }
        ask(r, "_dmarc.$domain", "TXT", errors, "dmarc")?.let { f = f.copy(dmarc = dmarcPolicy(it.records)) }
        ask(r, "default._bimi.$domain", "TXT", errors, "bimi")?.let { b ->
            f = f.copy(bimi = b.records.any { it.trim().lowercase().startsWith("v=bimi1") })
        }
        return f.copy(errors = errors)
    }
}

/** One blocklist's answer for a domain: status clean | listed | unavailable, category phish | spam. */
data class DnsblResult(val status: String, val category: String? = null)

/** A domain blocklist zone. */
data class DnsblZone(val id: String, val name: String, val zone: String, val test: String, val terms: String)

object Dnsbl {
    const val HEALTH_TTL_S = 3600L
    const val W_DNSBL_PHISH = 60
    const val W_DNSBL_SPAM = 35

    /** In order (phish before spam, then this order, for the strongest). */
    val ZONES: List<DnsblZone> = listOf(
        DnsblZone("spamhaus_dbl", "Spamhaus DBL", "dbl.spamhaus.org", "dbltest.com", "https://www.spamhaus.org/blocklists/dnsbl-fair-use-policy/"),
        DnsblZone("surbl", "SURBL", "multi.surbl.org", "test.surbl.org", "https://www.surbl.org/usage-policy"),
        DnsblZone("uribl", "URIBL", "multi.uribl.com", "test.uribl.com", "https://uribl.com/about.shtml"),
    )
    val IDS: List<String> = ZONES.map { it.id }
    fun zone(id: String): DnsblZone? = ZONES.firstOrNull { it.id == id }

    private val DBL_PHISH = setOf(4, 5, 6, 104, 105, 106)
    private val DBL_SPAM = setOf(2, 102, 103)
    private const val SURBL_PHISH = 8 or 16
    private const val SURBL_SPAM = 64 or 128
    private const val SURBL_INFO = 4 or 32
    private const val URIBL_SPAM = 2 or 8
    private const val URIBL_INFO = 4

    private val UNAVAILABLE = DnsblResult("unavailable")

    /** Station's `interpret`: an error, refusal or unknown code is never "listed". [rcode] null = failed. */
    fun interpret(source: String, rcode: Int?, records: List<String>): DnsblResult {
        if (rcode == DnsWire.NXDOMAIN) return DnsblResult("clean")
        if (rcode != DnsWire.NOERROR || records.isEmpty()) return UNAVAILABLE
        val cats = mutableSetOf<String>()
        for (r in records) {
            val parts = r.split('.')
            if (parts.size != 4 || !parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }) return UNAVAILABLE
            val (a, b, c, d) = parts.map { it.toIntOrNull() ?: return UNAVAILABLE }
            if (a != 127) return UNAVAILABLE                             // a hijacking resolver or a captive portal
            when (source) {
                "spamhaus_dbl" -> {
                    if (b != 0 || c != 1) return UNAVAILABLE              // 127.255.255.252/254/255, unknown
                    when (d) {
                        in DBL_PHISH -> cats += "phish"
                        in DBL_SPAM -> cats += "spam"
                        else -> return UNAVAILABLE                        // 127.0.1.255 "IP queries prohibited"
                    }
                }
                "surbl" -> {
                    if (b != 0 || c != 0 || d and 1 != 0 || d == 0) return UNAVAILABLE   // 127.0.0.1: access blocked
                    when {
                        d and SURBL_PHISH != 0 -> cats += "phish"
                        d and SURBL_SPAM != 0 -> cats += "spam"
                        d and SURBL_INFO == 0 -> return UNAVAILABLE
                    }
                }
                "uribl" -> {
                    if (b != 0 || c != 0 || d and 1 != 0 || d and 0x0F.inv() != 0) return UNAVAILABLE   // refused / blocked
                    when {
                        d and URIBL_SPAM != 0 -> cats += "spam"
                        d and URIBL_INFO == 0 -> return UNAVAILABLE
                    }
                }
                else -> return UNAVAILABLE
            }
        }
        return when {
            "phish" in cats -> DnsblResult("listed", "phish")
            "spam" in cats -> DnsblResult("listed", "spam")
            else -> DnsblResult("clean")                                  // only informational bits
        }
    }

    /** Does the zone's published test point answer as documented (listed)? */
    fun testPointOk(source: String, rcode: Int?, records: List<String>): Boolean {
        val r = interpret(source, rcode, records)
        if (source == "uribl") return r.status == "listed" && records == listOf("127.0.0.14")
        return r.status == "listed"
    }

    /** (category, source) of the strongest listing (phish before spam, then [ZONES] order), or null. */
    fun strongest(results: Map<String, DnsblResult>?): Pair<String, String>? {
        var best: Pair<String, String>? = null
        for (z in ZONES) {
            val r = results?.get(z.id) ?: continue
            if (r.status != "listed") continue
            if (best == null || (r.category == "phish" && best.first != "phish")) best = (r.category ?: "spam") to z.id
        }
        return best
    }
}

/**
 * Asks the blocklists about a registrable domain. Each zone's test point is asked first and the
 * answer cached for [Dnsbl.HEALTH_TTL_S]; when it does not answer as documented (a public resolver
 * being refused, a resolver that answers everything) the zone is unavailable and the domain is not
 * sent to it. [clockSeconds] is injectable for tests.
 */
class DnsblClient(private val resolver: DnsQuery, private val clockSeconds: () -> Long) {
    /** Swift: the wall clock. */
    constructor(resolver: DnsQuery) : this(resolver, { kotlinx.datetime.Clock.System.now().epochSeconds })

    private val health = HashMap<String, Pair<Long, Boolean>>()

    private fun ask(name: String): Pair<Int?, List<String>> {
        val a = resolver.query(name, "A") ?: return null to emptyList()
        return a.rcode to a.records
    }

    fun healthy(source: String): Boolean {
        val z = Dnsbl.zone(source) ?: return false
        health[source]?.let { (until, ok) -> if (until > clockSeconds()) return ok }
        val (rc, recs) = ask("${z.test}.${z.zone}")
        val ok = Dnsbl.testPointOk(source, rc, recs)
        health[source] = (clockSeconds() + Dnsbl.HEALTH_TTL_S) to ok
        return ok
    }

    /** {source: result} for the lists in [sources] (unknown ids ignored). */
    fun check(domain: String, sources: List<String>): Map<String, DnsblResult> {
        val out = LinkedHashMap<String, DnsblResult>()
        for (s in sources) {
            val z = Dnsbl.zone(s) ?: continue
            if (!healthy(s)) {
                out[s] = DnsblResult("unavailable")
                continue
            }
            val (rc, recs) = ask("$domain.${z.zone}")
            out[s] = Dnsbl.interpret(s, rc, recs)
        }
        return out
    }
}
