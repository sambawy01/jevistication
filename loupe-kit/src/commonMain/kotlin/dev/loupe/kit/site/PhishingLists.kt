package dev.loupe.kit.site

import dev.loupe.persistence.PlatformFiles

/*
 * Known-phishing lists matched on the phone: URL normalisation, the shared-host suppression and
 * Phishing.Database (owner decision B, 2026-09-24).
 *
 * PROVENANCE: ported from Loupe Station (`~/laya-studio`, commit 4cb9026):
 * `laya_studio/online/feeds.py` (`normalize`), `online/phishingdb.py` (files, parsing, the sanity
 * check, the recent-entries merge, matching, `is_suppressed`) and `online/shared_hosts.json`
 * (loupe-kit/data/shared_hosts.json, copied verbatim; generated into [SharedHostsData]).
 *
 * Phishing.Database: https://github.com/Phishing-Database/Phishing.Database, MIT licence,
 * (c) 2018-2025 Mitchell Krog, Nissar Chababy and the Phishing.Database Contributors. It aggregates
 * several upstream sources it does not all name, so a hit is shown as "Phishing.Database", never as
 * a verdict of a named authority. Files from https://phish.co.za/latest/ (the phone downloads them;
 * this file only reads what came down).
 */

/** One list, matched on the phone. */
interface PhishingList {
    val source: String
    fun match(url: String): FeedHit?
}

object ListUrls {
    /**
     * Station's `feeds.normalize`: (key, host, isRoot) for an http(s) URL, key = host + path
     * (trailing "/" dropped) + "?query"; host lowercase IDNA ASCII, no port or credentials, no
     * "www." stripping. Null for anything else.
     */
    fun normalize(url: String): Triple<String, String, Boolean>? {
        var raw = url.trim()
        if (raw.isEmpty()) return null
        if ("://" !in raw) raw = "http://$raw"
        val colon = raw.indexOf("://")
        val scheme = raw.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return null
        var rest = raw.substring(colon + 3).filter { it != '\t' && it != '\r' && it != '\n' }
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val netloc = rest.substring(0, end)
        rest = rest.substring(end).substringBefore('#')
        if (('[' in netloc) != (']' in netloc)) return null
        val hostPort = netloc.substringAfterLast('@')
        val hostname = if (hostPort.startsWith("[")) hostPort.substring(1).substringBefore(']') else hostPort.substringBefore(':')
        var host = hostname.trimEnd('.').lowercase()
        if (host.isEmpty()) return null
        if (!host.all { it.code < 128 }) host = Hosts.toAsciiDomain(host) ?: return null
        val query = if ('?' in rest) rest.substringAfter('?') else ""
        val path = rest.substringBefore('?').ifEmpty { "/" }
        val trimmed = path.trimEnd('/')
        return Triple(host + trimmed + (if (query.isNotEmpty()) "?$query" else ""), host, trimmed.isEmpty() && query.isEmpty())
    }

    /** shared_hosts.json (Station's, verbatim). */
    val SHARED_HOSTS: Set<String> = SharedHostsData.HOSTS.toSet()

    /**
     * Every name whose hosts only ever match by exact URL: shared_hosts.json, the path-shared hosts
     * and shorteners ([FeedIndex.PATH_SHARED_HOSTS]), the shared-hosting platforms and the
     * well-known brands' own domains (Station's `phishingdb.suppress_names`).
     */
    val SUPPRESS_NAMES: Set<String> by lazy {
        SHARED_HOSTS + FeedIndex.PATH_SHARED_HOSTS + Brands.SHARED_HOSTING + Brands.brandDomains(Brands.BRANDS)
    }

    /** A host or domain match on [host] must not count: it is, or is under, one of [SUPPRESS_NAMES]. */
    fun isSuppressed(host: String): Boolean {
        val h = host.trim('.').lowercase()
        if (h.isEmpty()) return true
        var i = 0
        while (true) {
            if (h.substring(i) in SUPPRESS_NAMES) return true
            val dot = h.indexOf('.', i)
            if (dot < 0) return false
            i = dot + 1
        }
    }
}

/** Phishing.Database's files and checks (Station's `phishingdb.py`). */
object PhishingDb {
    const val SOURCE = "phishingdb"
    const val NAME = "Phishing.Database"
    const val BASE_URL = "https://phish.co.za/latest/"
    const val LICENCE = "MIT"
    const val COPYRIGHT = "(c) 2018-2025 Mitchell Krog, Nissar Chababy and the Phishing.Database Contributors"
    const val MIN_ACTIVE_LINES = 1000
    const val MIN_VALID_RATIO = 0.9
    const val NEW_EVERY_S = 3600L
    const val FILE_GAP_S = 3.0
    const val RECENT_MAX_LINES = 200_000
    const val DEFAULT_REFRESH_HOURS = 6
    const val MAX_ENTRIES = 1_000_000                  // per kind on a phone (Station's "low" memory mode)
    private const val MIB = 1024L * 1024L

    /** A file on the server. [tier] "full" (the refresh setting) or "new" (hourly). */
    data class ListFile(val key: String, val name: String, val kind: String, val tier: String, val maxBytes: Long)

    val FILES: List<ListFile> = listOf(
        ListFile("links_active", "phishing-links-ACTIVE.txt", "links", "full", 48 * MIB),
        ListFile("domains_active", "phishing-domains-ACTIVE.txt", "domains", "full", 64 * MIB),
        ListFile("links_new", "phishing-links-NEW-today.txt", "links", "new", 8 * MIB),
        ListFile("domains_new", "phishing-domains-NEW-today.txt", "domains", "new", 8 * MIB),
    )
    val RECENT: Map<String, String> = mapOf("links" to "recent-links.txt", "domains" to "recent-domains.txt")

    /** The refresh setting, clamped to 1–168 hours. */
    fun clampRefreshHours(h: Int): Int = h.coerceIn(1, 168)

    private val PRIVATE_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    private fun labelOk(l: String): Boolean {
        if (l.isEmpty() || l.length > 63) return false
        fun ok(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_'
        if (!ok(l.first()) || !ok(l.last())) return false
        return l.all { ok(it) || it == '-' }
    }

    /** One line of a domains file -> a lowercase ASCII host name, or null (comments, IPs, junk). */
    fun parseDomain(line: String): String? {
        var h = line.trim().trimEnd('.').lowercase()
        if (h.isEmpty() || h.startsWith("#")) return null
        if (!h.all { it.code < 128 }) h = Hosts.toAsciiDomain(h) ?: return null
        if (h.length > 253) return null
        val labels = h.split('.')
        if (labels.size < 2) return null
        val tld = labels.last()
        val tldOk = (tld.length in 2..63 && tld.all { it in 'a'..'z' }) ||
            (tld.startsWith("xn--") && tld.length in 5..63 && tld.drop(4).all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
        return if (tldOk && labels.dropLast(1).all(::labelOk)) h else null
    }

    /** One line of a links file -> [ListUrls.normalize], or null. Only http(s) URLs. */
    fun parseLink(line: String): Triple<String, String, Boolean>? {
        val s = line.trim()
        val head = s.take(8).lowercase()
        if (!head.startsWith("http://") && !head.startsWith("https://")) return null
        return ListUrls.normalize(s)
    }

    /** The non-empty, non-comment lines of [text], without building a list. */
    inline fun forEachLine(text: String, action: (String) -> Unit) {
        var i = 0
        val n = text.length
        while (i < n) {
            var j = text.indexOf('\n', i)
            if (j < 0) j = n
            val line = text.substring(i, j).trim()
            if (line.isNotEmpty() && !line.startsWith("#")) action(line)
            i = j + 1
        }
    }

    /**
     * The sanity check before a download replaces the last good copy (Station's `check_file`):
     * [contentType] is not HTML, the body does not start like HTML, at least [minLines] entries, and
     * at least 90 % of them parse. The number that parse, or null with [reason] set.
     */
    data class Checked(val valid: Int?, val reason: String?)

    fun check(text: String, kind: String, minLines: Int, contentType: String? = null): Checked {
        if (contentType?.lowercase()?.contains("html") == true) return Checked(null, "html")
        val head = text.take(1024).trimStart()
        if (head.startsWith("<") || head.take(9).lowercase() == "<!doctype") return Checked(null, "html")
        var total = 0
        var valid = 0
        forEachLine(text) { line ->
            total++
            if ((if (kind == "links") parseLink(line) else parseDomain(line)) != null) valid++
        }
        if (total < minLines) return Checked(null, "too_few_lines")
        if (total > 0 && valid < MIN_VALID_RATIO * total) return Checked(null, "unparsable")
        return Checked(valid, null)
    }

    /** [check] with the minimum for [tier] ("full": 1000 lines; "new": none). */
    fun checkFile(text: String, kind: String, tier: String, contentType: String? = null): Checked =
        check(text, kind, if (tier == "full") MIN_ACTIVE_LINES else 0, contentType)

    /**
     * The recent file after adding a NEW file's lines (Station's `merge_recent`): deduplicated,
     * newest kept, at most [RECENT_MAX_LINES], only lines that parse.
     */
    fun mergeRecent(existing: String?, newText: String, kind: String): String {
        val merged = LinkedHashSet<String>()
        for (t in listOfNotNull(existing, newText)) {
            forEachLine(t) { line ->
                if ((if (kind == "links") parseLink(line) else parseDomain(line)) != null) {
                    merged.remove(line)
                    merged.add(line)
                }
            }
        }
        val keep = merged.toList().takeLast(RECENT_MAX_LINES)
        return keep.joinToString("") { "$it\n" }
    }

    /** Builds the index from the texts of the links and domains files that exist. */
    fun build(linkTexts: List<String>, domainTexts: List<String>, listDate: String?, maxEntries: Int = MAX_ENTRIES): PhishingDbIndex {
        val urls = LongList()
        val hosts = LongList()
        val domains = LongList()
        var truncated = false
        fun addHost(host: String, isRoot: Boolean) {
            hosts.add(hash64(host))
            if (isRoot) {
                // Station adds the registrable domain when the root entry is it or www.<it>; the
                // index keeps both spellings and matching checks the page's registrable domain.
                domains.add(hash64(host))
                if (host.startsWith("www.")) domains.add(hash64(host.removePrefix("www.")))
            }
        }
        var n = 0
        for (t in linkTexts) {
            forEachLine(t) { line ->
                if (truncated) return@forEachLine
                val p = parseLink(line) ?: return@forEachLine
                if (Hosts.isPrivateHost(p.second)) return@forEachLine
                if (n >= maxEntries) { truncated = true; return@forEachLine }
                n++
                urls.add(hash64(p.first))
                addHost(p.second, p.third)
            }
        }
        n = 0
        var domainsTruncated = false
        for (t in domainTexts) {
            forEachLine(t) { line ->
                if (domainsTruncated) return@forEachLine
                val h = parseDomain(line) ?: return@forEachLine
                if (h == "localhost" || PRIVATE_SUFFIXES.any { h.endsWith(it) }) return@forEachLine
                if (n >= maxEntries) { domainsTruncated = true; return@forEachLine }
                n++
                addHost(h, true)
            }
        }
        val u = urls.sortedUnique()
        val h = hosts.sortedUnique()
        return PhishingDbIndex(u, h, domains.sortedUnique(), listDate, u.size, h.size, truncated || domainsTruncated)
    }

    /** [build] from files on disk (the ACTIVE copies and the recent-entries files that exist). */
    fun buildFromFiles(linkPaths: List<String>, domainPaths: List<String>, listDate: String?, maxEntries: Int = MAX_ENTRIES): PhishingDbIndex =
        build(linkPaths.mapNotNull { PlatformFiles.readText(it) }, domainPaths.mapNotNull { PlatformFiles.readText(it) }, listDate, maxEntries)

    /** FNV-1a, 64-bit, over the UTF-16 code units (the keys are almost always ASCII). */
    fun hash64(s: String): Long {
        var h = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325
        for (c in s) {
            h = h xor (c.code.toLong() and 0xFF)
            h *= 0x100000001b3L
            val hi = c.code shr 8
            if (hi != 0) {
                h = h xor hi.toLong()
                h *= 0x100000001b3L
            }
        }
        return h
    }
}

/** A growable LongArray. */
class LongList {
    private var a = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun sortedUnique(): LongArray {
        val s = a.copyOf(size)
        s.sort()
        var w = 0
        for (i in s.indices) if (i == 0 || s[i] != s[i - 1]) s[w++] = s[i]
        return s.copyOf(w)
    }
}

/**
 * Phishing.Database, loaded: three sorted arrays of 64-bit hashes (about 8 MB for today's whole
 * list). Matching as Station's `PhishingDbIndex.match`: the exact URL, then (unless suppressed) the
 * host, then the page's registrable domain listed bare.
 */
class PhishingDbIndex(
    internal val urls: LongArray,
    internal val hosts: LongArray,
    internal val domains: LongArray,
    val listDate: String?,
    val linkCount: Int,
    val hostCount: Int,
    val truncated: Boolean,
) : PhishingList {
    override val source: String get() = PhishingDb.SOURCE

    private fun has(a: LongArray, v: Long): Boolean {
        var lo = 0
        var hi = a.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val x = a[mid]
            if (x < v) lo = mid + 1 else if (x > v) hi = mid - 1 else return true
        }
        return false
    }

    override fun match(url: String): FeedHit? {
        val (key, host, _) = ListUrls.normalize(url) ?: return null
        if (has(urls, PhishingDb.hash64(key))) return FeedHit(source, "url")
        if (ListUrls.isSuppressed(host)) return null
        if (has(hosts, PhishingDb.hash64(host))) return FeedHit(source, "host")
        val reg = Hosts.registrableDomain(host) ?: host
        if (has(domains, PhishingDb.hash64(reg)) && !ListUrls.isSuppressed(reg)) return FeedHit(source, "domain")
        return null
    }
}

/**
 * Phishing.Database's index as one flat file (2026-09-26, the Safari extension): the app writes it
 * into the App Group next to the lists it downloaded, and an extension maps it into memory and
 * binary-searches it instead of re-reading the 100 MB of text (which would not fit an extension's
 * memory). Little-endian:
 *
 *     0   "LPDBIDX1"                       8 bytes
 *     8   urls, hosts, domains             3 x Int64 (entry counts)
 *     32  linkCount, hostCount, truncated  3 x Int64
 *     56  dateBytes                        Int64, then the list date (UTF-8), zero-padded to 8
 *     ..  urls[], hosts[], domains[]       Int64 each, sorted ascending (signed), unique
 *
 * The hashes are [PhishingDb.hash64] of [ListUrls.normalize]'s key, host and registrable domain, the
 * same values [PhishingDbIndex] holds, so a reader matches exactly as [PhishingDbIndex.match] does.
 */
object PhishingDbBinary {
    const val MAGIC = "LPDBIDX1"
    const val HEADER_BYTES = 64

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun getLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    fun encode(index: PhishingDbIndex): ByteArray {
        val date = (index.listDate ?: "").encodeToByteArray()
        val datePadded = (date.size + 7) / 8 * 8
        val n = index.urls.size + index.hosts.size + index.domains.size
        val out = ByteArray(HEADER_BYTES + datePadded + 8 * n)
        MAGIC.encodeToByteArray().copyInto(out, 0)
        putLong(out, 8, index.urls.size.toLong())
        putLong(out, 16, index.hosts.size.toLong())
        putLong(out, 24, index.domains.size.toLong())
        putLong(out, 32, index.linkCount.toLong())
        putLong(out, 40, index.hostCount.toLong())
        putLong(out, 48, if (index.truncated) 1L else 0L)
        putLong(out, 56, date.size.toLong())
        date.copyInto(out, HEADER_BYTES)
        var at = HEADER_BYTES + datePadded
        for (a in listOf(index.urls, index.hosts, index.domains)) for (v in a) { putLong(out, at, v); at += 8 }
        return out
    }

    /** The index back from [encode]'s bytes, or null when they are not a whole, valid file. */
    fun decode(bytes: ByteArray): PhishingDbIndex? {
        if (bytes.size < HEADER_BYTES || bytes.copyOfRange(0, 8).decodeToString() != MAGIC) return null
        val counts = (0 until 7).map { getLong(bytes, 8 + 8 * it) }
        if (counts.any { it < 0 || it > Int.MAX_VALUE / 8 }) return null
        val (nu, nh, nd) = Triple(counts[0].toInt(), counts[1].toInt(), counts[2].toInt())
        val dateLen = counts[6].toInt()
        val start = HEADER_BYTES + (dateLen + 7) / 8 * 8
        if (bytes.size.toLong() != start.toLong() + 8L * (nu.toLong() + nh + nd)) return null
        fun arr(from: Int, n: Int) = LongArray(n) { getLong(bytes, from + 8 * it) }
        val date = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + dateLen).decodeToString().ifEmpty { null }
        return PhishingDbIndex(arr(start, nu), arr(start + 8 * nu, nh), arr(start + 8 * (nu + nh), nd), date,
            counts[3].toInt(), counts[4].toInt(), counts[5] != 0L)
    }
}
