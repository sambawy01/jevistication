package dev.loupe.kit.site

import dev.loupe.engine.OriginFacts
import dev.loupe.engine.PortableText
import dev.loupe.engine.Uts46

/*
 * Host and URL helpers for site protection and mail phishing checks.
 *
 * PROVENANCE: the IP / private-host rules and the URL split follow the owner's Loupe Station
 * repository (`~/laya-studio`, `laya_studio/browser/psl.py` `is_ip` / `is_private_host` /
 * `public_suffix` / `registrable_domain` / `subdomain_part`, and Python's `urllib.parse.urlsplit`
 * as `browser/signals.py` uses it), commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e.
 *
 * One deliberate difference: **registrable domains come from the engine's pinned Mozilla Public
 * Suffix List** (`OriginFacts.registrableDomain`, ICANN + PRIVATE sections), not from Station's
 * trimmed `psl.py` snapshot. Station's SHARED_HOSTING list is kept (in [Brands]) only to name
 * shared-hosting suffixes. See docs/BUILD.md (epic #7 child 12) for where the two disagree.
 */
object Hosts {

    /** Python's `ipaddress.ip_address` plus the browsers' decimal / hex / short dotted forms. */
    fun isIp(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (ipv4(h) != null || isIpv6(h)) return true
        val labels = host.split(".")
        if (labels.size !in 1..4 || labels.any { it.isEmpty() }) return false
        return labels.all { l ->
            if (l.lowercase().startsWith("0x")) l.substring(2).let { it.isNotEmpty() && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
            else l.all { it in '0'..'9' }
        }
    }

    /**
     * Loopback, private-network or link-local IPs, `localhost` and `.local` / `.localhost` names: a
     * home router's admin page, a NAS, a dev server. Never flagged for http:// or for being an IP.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (h == "localhost" || listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal").any { h.endsWith(it) }) return true
        val v4: Long? = if (h.isNotEmpty() && h.all { it in '0'..'9' }) h.toLongOrNull()?.takeIf { it < (1L shl 32) } else ipv4(h)
        if (v4 != null) return privateV4(v4)
        if (isIpv6(h)) {
            if (h.startsWith("::ffff:")) ipv4(h.removePrefix("::ffff:"))?.let { return privateV4(it) }
            return h == "::1" || h == "::" || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") ||
                h.startsWith("feb") || h.startsWith("fc") || h.startsWith("fd")
        }
        return false
    }

    /** The public suffix of [host] (lowercase, no trailing dot), or null for an IP or an empty host. */
    fun publicSuffix(host: String): String? {
        val h = host.trim('.').lowercase()
        if (h.isEmpty() || isIp(h)) return null
        val reg = OriginFacts.registrableDomain(h) ?: return h
        return reg.substringAfter('.')
    }

    /** The public suffix plus one label ("eTLD+1"), or null when [host] is an IP, empty, or itself a suffix. */
    fun registrableDomain(host: String): String? {
        val h = host.trim('.').lowercase()
        val suffix = publicSuffix(h) ?: return null
        if (h == suffix) return null
        val rest = h.dropLast(suffix.length + 1)
        return rest.substringAfterLast('.') + "." + suffix
    }

    /** Everything left of the registrable domain ("" when there is none). */
    fun subdomainPart(host: String): String {
        val h = host.trim('.').lowercase()
        val reg = registrableDomain(h)
        if (reg == null || h == reg) return ""
        return h.dropLast(reg.length + 1)
    }

    /**
     * On one of Station's shared-hosting suffixes. Twelve of them (wordpress.com, weebly.com,
     * glitch.me, ...) are not suffixes in the Mozilla PSL, so the host is matched against the list
     * directly as well: a customer site there is still "a login form worth a second look".
     */
    fun isSharedHosting(host: String): Boolean {
        val h = host.trim('.').lowercase()
        return publicSuffix(h) in Brands.SHARED_HOSTING || Brands.SHARED_HOSTING.any { h.endsWith(".$it") }
    }

    /**
     * The shared-hosting name [host] sits under when that name is **not** a suffix in the pinned PSL
     * (Station's twelve: wordpress.com, weebly.com, glitch.me, ...), for a customer's site on it (not
     * the service's own bare domain or `www`). Rule 5: the PSL says the service controls the host,
     * which it does not, so the formula adds its own "shared hosting" caution. Null otherwise.
     */
    fun sharedHostingNotInPsl(host: String): String? {
        val h = host.trim('.').lowercase()
        for (name in SHARED_NOT_IN_PSL) {
            if (h.endsWith(".$name") && h != "www.$name") return name
        }
        return null
    }

    /**
     * Station's shared-hosting names that are not suffixes in the pinned Mozilla PSL (listed in
     * docs/PHISHING-FORMULA.md; a test pins that each is still absent). `run.app` is not among them:
     * the PSL lists Cloud Run's real host suffixes under it (`a.run.app`).
     */
    val SHARED_NOT_IN_PSL: List<String> = listOf(
        "000webhostapp.com", "godaddysites.com", "strikingly.com", "jimdosite.com", "wordpress.com", "railway.app",
        "serveo.net", "weebly.com", "site123.me", "glitch.me", "tilda.ws", "loca.lt",
    )

    // ------------------------------------------------------------------------ IP details
    private fun ipv4(h: String): Long? {
        val parts = h.split(".")
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            val n = p.toInt()
            if (n > 255) return null
            v = v * 256 + n
        }
        return v
    }

    private fun isIpv6(h: String): Boolean =
        // ASCII hex only: Char.isDigit() takes every script's digits (Arabic-Indic ones would pass).
        ':' in h && h.count { it == ':' } >= 2 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    private fun inNet(v: Long, net: String, bits: Int): Boolean {
        val base = ipv4(net) ?: return false
        val mask = if (bits == 0) 0L else ((1L shl 32) - 1) xor ((1L shl (32 - bits)) - 1)
        return (v and mask) == (base and mask)
    }

    // Python's ipaddress `is_private or is_loopback or is_link_local` for IPv4.
    private val PRIVATE_V4 = listOf(
        "0.0.0.0" to 8, "10.0.0.0" to 8, "127.0.0.0" to 8, "169.254.0.0" to 16, "172.16.0.0" to 12,
        "192.0.0.0" to 29, "192.0.0.170" to 31, "192.0.2.0" to 24, "192.168.0.0" to 16, "198.18.0.0" to 15,
        "198.51.100.0" to 24, "203.0.113.0" to 24, "240.0.0.0" to 4, "255.255.255.255" to 32,
    )

    private fun privateV4(v: Long): Boolean = PRIVATE_V4.any { (net, bits) -> inNet(v, net, bits) }

    // ------------------------------------------------------------------------ IDNA
    /**
     * One label to the ASCII form a browser resolves: UTS #46 non-transitional ([Uts46], the WHATWG
     * URL mapping Chrome and Safari use), so `ß`, `ς`, ZWJ and ZWNJ are kept (`faß` is `xn--fa-hia`,
     * never `fass`). ASCII labels are only lowercased; a label the mapping makes ASCII (`ｅｘａｍｐｌｅ`)
     * is that ASCII. Every host-keyed decision (known, trusted, owns, online lookups) reads this form;
     * the transitional (IDNA 2003) reading is only compared with it ([transitionalAscii]).
     */
    fun toAsciiLabel(label: String): String? {
        if (label.all { it.code < 128 }) return label.lowercase()
        return Uts46.toAscii(label).ascii
    }

    /** [domain] as IDNA 2003 / transitional software reads it (`faß.de` -> `fass.de`). For comparison only. */
    fun transitionalAscii(domain: String): String = Uts46.toAscii(domain, transitional = true).ascii

    /** An `xn--` label decoded to Unicode, or the label unchanged when it is not valid punycode. */
    fun decodeLabel(label: String): String {
        if (!label.startsWith("xn--")) return label
        return Punycode.decode(label.substring(4)) ?: label
    }

    /** A domain with every `xn--` label decoded to Unicode. */
    fun decodeDomain(domain: String): String = domain.split('.').joinToString(".") { decodeLabel(it) }

    /**
     * A domain to the ASCII form a browser resolves (UTS #46 non-transitional; the ideographic,
     * full-width and halfwidth full stops separate labels as `.` does).
     */
    fun toAsciiDomain(domain: String): String? {
        if (domain.all { it.code < 128 }) return domain.lowercase()
        return Uts46.toAscii(domain).ascii
    }

    /** The URL schemes WHATWG calls special: they always have an authority, and `\\` is `/` in them. */
    private val SPECIAL = setOf("http", "https", "ws", "wss", "ftp", "file")

    /** [url] without the leading and trailing C0 controls and spaces WHATWG strips (not only `trim()`'s). */
    internal fun stripC0(url: String): String = url.trim { it.code <= 0x20 }

    /** A URL's parts as a browser splits them. [backslashAt]: a `\\` before an `@` in the authority. */
    internal class Split(val scheme: String, val authority: String?, val rest: String, val backslashAt: Boolean)

    /**
     * [url] split as a browser splits it for a special scheme (WHATWG URL): C0 controls and spaces
     * stripped from both ends, tabs and newlines dropped, `\\` read as `/`, and an authority always
     * (`https:evil.com`, `https:/evil.com`, `https:\\evil.com` and `https:///evil.com` all have host
     * evil.com); the userinfo ends at the authority's last `@`. Otherwise as Python's `urlsplit`.
     */
    internal fun splitUrl(url: String): Split {
        var rest = stripC0(url).filter { it != '\t' && it != '\r' && it != '\n' }
        var scheme = ""
        val colon = rest.indexOf(':')
        if (colon > 0 && SCHEME.matches(rest.substring(0, colon))) {
            scheme = rest.substring(0, colon).lowercase()
            rest = rest.substring(colon + 1)
        }
        var backslashAt = false
        if (scheme == "file") {
            // WHATWG: a file URL has a host only after exactly `//` (or `\\`); `file:///x` has an
            // empty one and `file:evil.com/x` none (fix loop 4)
            rest = rest.replace('\\', '/')
            if (!rest.startsWith("//")) return Split(scheme, null, rest, false)
        } else if (scheme in SPECIAL) {
            val body = rest.trimStart('/', '\\')
            val end = body.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) body.length else it }
            // `https://evil.com\@paypal.com`: the browser opens evil.com, but the text still reads as
            // userinfo in front of paypal.com, so it keeps the userinfo warning.
            backslashAt = end < body.length && body[end] == '\\' && '@' in body.substring(end).substringBefore('/').substringBefore('?').substringBefore('#')
            rest = "//" + body.replace('\\', '/')
        }
        if (!rest.startsWith("//")) return Split(scheme, null, rest, false)
        val after = rest.substring(2)
        val end = after.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) after.length else it }
        return Split(scheme, after.substring(0, end), after.substring(end), backslashAt)
    }

    /**
     * The host in an authority, as written: after the authority's last `@` (brackets in the
     * userinfo are ordinary characters), `[...]` for an IPv6 address, else up to the port. Null when
     * a bracket does not pair in the host itself.
     */
    internal fun hostOfAuthority(netloc: String): String? {
        val hostPort = netloc.substringAfterLast('@')
        if (hostPort.startsWith("[")) {
            if (']' !in hostPort) return null
            return hostPort.substring(1).substringBefore(']')
        }
        val host = hostPort.substringBefore(':')
        return if ('[' in host || ']' in host) null else host
    }

    /** The host of [url] exactly as written, percent escapes included ("" when it has none). */
    fun rawHost(url: String): String = splitUrl(url).authority?.let { hostOfAuthority(it) } ?: ""

    /** The host of [url] as written: percent-decoded (WHATWG), before IDNA mapping and lower-casing. */
    fun writtenHost(url: String): String {
        val netloc = splitUrl(url).authority ?: return ""
        val host = hostOfAuthority(netloc) ?: return ""
        return SiteSignals.percentDecode(host).trimEnd('.', '\u3002', '\uFF0E', '\uFF61')
    }

    /**
     * The URL a mail link opens, for the checks: [href] as it is when it has a scheme (a special
     * scheme always has an authority, so `https:\\evil.com` is evil.com), `http://` + [href] for a
     * bare host (`www.example.com/x`). `www.example.com:8080/x` is a bare host, not a scheme.
     */
    fun linkUrl(href: String): String {
        val t = stripC0(href)
        val colon = t.indexOf(':')
        val scheme = if (colon > 0 && SCHEME.matches(t.substring(0, colon))) t.substring(0, colon).lowercase() else null
        if (scheme != null && (scheme in SPECIAL || t.startsWith("$scheme://", ignoreCase = true))) return t
        // A relative href (`/x`, `./x`, `?utm=1`, `//host/x`), a template tag (`*|UNSUB|*`,
        // `%%unsubscribe%%`, `{{url}}`) or one behind a no-break space is relative to the message:
        // a browser opens no host of its own, so it stays as written (no host). Only what starts like
        // a host name (a letter or digit: `www.example.com/x`, `例え.jp`) is read as http://.
        val first = when {
            t.isEmpty() -> -1
            t[0].isHighSurrogate() && t.length > 1 && t[1].isLowSurrogate() -> 0x10000 + ((t[0].code - 0xD800) shl 10) + (t[1].code - 0xDC00)
            else -> t[0].code
        }
        return if (first >= 0 && PortableText.isLetterOrNumber(first)) "http://$t" else t
    }

    /** True when [url]'s scheme is http or https (after stripping, in any case). */
    fun isWebUrl(url: String): Boolean = splitUrl(url).scheme.let { it == "http" || it == "https" }

    /**
     * WHATWG's forbidden host code points: a host containing one does not parse in a browser.
     */
    private val WEB_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")
    private const val FORBIDDEN_HOST = " #%/:<>?@[\\]^|"

    private fun forbiddenInHost(c: Char): Boolean = c in FORBIDDEN_HOST || c.code <= 0x20 || c.code == 0x7F

    /**
     * True when [url] names a web page no browser can open: it starts with an explicit web scheme
     * (http, https, ws, wss, ftp; after C0 stripping) and its host is not empty, not only dots, and
     * holds a forbidden host code point after percent-decoding (`https://paypal.com%40evil.com`,
     * `%00`, `%09`, `%20`, DEL, `https://evil|com`), or a bracket that does not pair.
     *
     * Never for a relative or scheme-less href (`/`, `?utm=1`, `./x.html`, a template tag such as
     * `*|UNSUB|*` or `%%unsubscribe%%`, a URL behind a no-break space, which browsers read as
     * relative), nor for an empty or dots-only host (`https://`, `https:///`, `https://./`,
     * `https://%2e/`): those open nothing, and scoring them would flag ordinary mail (fix loop 4).
     */
    fun unreadableUrl(url: String): Boolean {
        val split = splitUrl(url)
        if (split.scheme !in WEB_SCHEMES) return false
        // the scheme is written out (a browser drops tabs and newlines anywhere in a URL)
        val t = stripC0(url).filter { it != '\t' && it != '\r' && it != '\n' }
        if (!t.startsWith(split.scheme, ignoreCase = true)) return false
        val authority = split.authority ?: return false
        val hostPort = authority.substringAfterLast('@')
        // `[...]` holds an IPv6 address or nothing a browser opens (`https://[evil.com]/`)
        if (hostPort.startsWith("[")) return ']' !in hostPort || !IPV6_CHARS.matches(hostPort.substring(1).substringBefore(']'))
        val raw = hostPort.substringBefore(':')
        if ('[' in raw || ']' in raw) return true
        val host = SiteSignals.percentDecode(raw)
        if (host.all { it == '.' || it == '\u3002' || it == '\uFF0E' || it == '\uFF61' }) return false
        return host.any(::forbiddenInHost)
    }

    private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+$")

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")
}

/**
 * A URL split the way a browser reaches it: Station's `signals.parse` fields (Python `urlsplit` +
 * the PSL facts), with WHATWG's host handling for http(s) (a backslash is a slash, the host is
 * percent-decoded) and the host in the ASCII form a browser resolves (UTS #46 non-transitional).
 */
data class ParsedUrl(
    val scheme: String,
    val host: String,
    val unicodeHost: String,
    val labels: List<String>,
    val userinfo: Boolean,
    val path: String,
    val query: String,
    val suffix: String?,
    val registrable: String?,
    val subdomain: String,
    val raw: String,
) {
    /**
     * The host as it was written in [raw]: percent-decoded, before IDNA mapping and lower-casing
     * ([Hosts.writtenHost]). The mapping erases what [SiteSignals]' `disguised_host`,
     * `unicode_drift_host` and `deviation_host` look for (`ｐａｙｐａｌ` maps to `paypal`), so they read
     * this form. Derived from [raw], so a copied or hand-built value carries it too. Not a
     * constructor parameter: the constructor and `copy` keep their eleven parameters for Swift.
     */
    val typedHost: String get() = Hosts.writtenHost(raw)

    companion object {
        /** A URL's parts; null where the authority's brackets do not pair (where urlsplit raises). */
        fun parse(url: String): ParsedUrl? {
            val split = Hosts.splitUrl(url)
            val scheme = split.scheme
            val netloc = split.authority
            val rest = split.rest.substringBefore('#')
            val query = if ('?' in rest) rest.substringAfter('?') else ""
            val path = rest.substringBefore('?')
            val authority = netloc ?: ""
            val hostname = Hosts.hostOfAuthority(authority) ?: return null
            // The host as a browser resolves it: percent-decoded, then UTS #46 non-transitional.
            val written = SiteSignals.percentDecode(hostname)
            val host = (Hosts.toAsciiDomain(written) ?: written).trimEnd('.')
            val labels = if (host.isNotEmpty()) host.split(".") else emptyList()
            return ParsedUrl(
                scheme = scheme,
                host = host,
                unicodeHost = labels.joinToString(".") { Hosts.decodeLabel(it) },
                labels = labels,
                userinfo = '@' in authority || split.backslashAt,
                path = path.ifEmpty { "/" },
                query = query,
                suffix = Hosts.publicSuffix(host),
                registrable = Hosts.registrableDomain(host),
                subdomain = Hosts.subdomainPart(host),
                raw = url,
            )
        }
    }
}

/** RFC 3492 punycode, both directions (the engine's encoder is internal to it). */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    private fun adapt(deltaIn: Int, numPoints: Int, first: Boolean): Int {
        var delta = if (first) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    private fun digitChar(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun digitValue(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0' + 26
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        else -> null
    }

    private fun codePoints(s: String): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out += ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out.toIntArray()
    }

    private fun fromCodePoints(cps: List<Int>): String = buildString {
        for (cp in cps) {
            if (cp >= 0x10000) {
                val v = cp - 0x10000
                append(Char(0xD800 + (v shr 10)))
                append(Char(0xDC00 + (v and 0x3FF)))
            } else {
                append(Char(cp))
            }
        }
    }

    fun encode(input: String): String? {
        val cps = codePoints(input)
        val out = StringBuilder()
        cps.filter { it < 0x80 }.forEach { out.append(it.toChar()) }
        val b = out.length
        var h = b
        if (b > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (h < cps.size) {
            val m = cps.filter { it >= n }.minOrNull() ?: return null
            delta += (m - n).toLong() * (h + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for (c in cps) {
                if (c < n) delta++
                if (c == n) {
                    var q = delta.toInt()
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                        if (q < t) break
                        out.append(digitChar(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digitChar(q))
                    bias = adapt(delta.toInt(), h + 1, h == b)
                    delta = 0
                    h++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    fun decode(input: String): String? {
        val out = ArrayList<Int>()
        val b = input.lastIndexOf('-')
        if (b > 0) {
            for (c in input.substring(0, b)) {
                if (c.code >= 0x80) return null
                out += c.code
            }
        }
        var i = 0L
        var n = INITIAL_N
        var bias = INITIAL_BIAS
        var pos = if (b > 0) b + 1 else 0
        while (pos < input.length) {
            val oldi = i
            var w = 1L
            var k = BASE
            while (true) {
                if (pos >= input.length) return null
                val digit = digitValue(input[pos++]) ?: return null
                i += digit * w
                if (i > Int.MAX_VALUE) return null
                val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                if (digit < t) break
                w *= (BASE - t)
                if (w > Int.MAX_VALUE) return null
                k += BASE
            }
            bias = adapt((i - oldi).toInt(), out.size + 1, oldi == 0L)
            n += (i / (out.size + 1)).toInt()
            i %= (out.size + 1)
            if (n > 0x10FFFF) return null
            out.add(i.toInt(), n)
            i++
        }
        return fromCodePoints(out)
    }
}
