package dev.loupe.engine

/**
 * Mechanical facts about a web origin (A3) — the layer that runs *before* the fraud judgment.
 *
 * These are exact, free, and unforgeable by page content: a page can claim whatever it likes in
 * its text, but it cannot talk its way out of its own URL. Per §4 of the spec, content can only
 * add suspicion, never raise trust, so nothing here reads the page body.
 */
object OriginFacts {

    /**
     * A hostname: unicode letters and digits, plus dot, hyphen and underscore.
     *
     * Non-ASCII is explicitly allowed, because a homograph host is the attack we most need to
     * name, not one to discard as malformed.
     */
    private val HOSTNAME = Regex("""^[\p{L}\p{Nd}\p{Nl}\p{No}][\p{L}\p{Nd}\p{Nl}\p{No}.\-_]*$""")

    /** The lowercase host of [url], or null if it cannot be parsed or names no host. */
    fun host(url: String): String? {
        val candidate = if (url.contains("://")) url else "https://$url"

        // A deliberately small parser, not a URI library. Before the Kotlin Multiplatform port
        // this asked java.net.URI first and fell back to the extraction below whenever URI gave
        // no host (a non-ASCII authority, which URI cannot represent -- the homograph case this
        // check exists for). Wherever URI did give a host it was the one this extraction finds,
        // so the extraction alone is the same function; OriginFactsHostParityTest (jvmTest)
        // pins that against java.net.URI on every case we know of.
        val raw = candidate
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')

        val host = raw.lowercase().removeSuffix(".")
        return host.takeIf { it.isNotEmpty() && HOSTNAME.matches(it) }
    }

    /** The dot-separated labels of [host]. */
    fun labels(host: String): List<String> = host.split(".").filter { it.isNotEmpty() }

    /** True when any label is punycode-encoded (`xn--`), the wire form of a non-ASCII name. */
    fun isPunycode(host: String): Boolean =
        labels(host).any { it.startsWith("xn--", ignoreCase = true) }

    /**
     * [host] with every `xn--` label decoded to Unicode (a label that is not valid Punycode is kept
     * as written). The script checks read this form: `xn--pypal-4ve.com` is `pаypal.com`.
     */
    fun unicodeHost(host: String): String =
        host.split('.').joinToString(".") { label ->
            if (label.startsWith("xn--", ignoreCase = true)) Punycode.decode(label.substring(4).lowercase()) ?: label else label
        }

    /** True when the host contains characters outside ASCII. */
    fun hasNonAsciiHost(host: String): Boolean = host.any { it.code > 127 }

    /**
     * True when a single label mixes writing systems — the homograph signal. `pаypal.com` with a
     * Cyrillic `а` among Latin letters is the canonical case, and it is indistinguishable by eye.
     * Punycode labels are decoded first, so the wire form (`xn--pypal-4ve.com`) is caught too.
     */
    fun hasMixedScripts(host: String): Boolean =
        labels(unicodeHost(host)).any { label ->
            val scripts = codePoints(label)
                .map { letterScript(it) }
                .filter { it >= 0 }
                .toSet()
            scripts.size > 1
        }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * The registrable domain (eTLD+1) of [host] under the Public Suffix List algorithm, or null
     * when [host] has none: it is itself a public suffix, a bare single label (`localhost`), an
     * IP literal, or malformed (empty labels, a leading dot).
     *
     * Implements https://publicsuffix.org/list/ in full: normal rules, wildcards (`*.ck`),
     * exceptions (`!www.ck`, which win over any wildcard), the implicit `*` rule for unlisted
     * TLDs, one trailing dot, case folding and IDN — labels are matched in their IDNA ASCII form,
     * and the answer is returned in the form the host was given (unicode in, unicode out).
     *
     * @param publicSuffixes PSL rules; defaults to the bundled list, both sections. See
     *   [PublicSuffix] for why PRIVATE is included.
     */
    fun registrableDomain(
        host: String,
        publicSuffixes: Set<String> = PublicSuffix.DEFAULT,
    ): String? {
        var h = host.trim().lowercase()
        if (h.endsWith(".")) h = h.dropLast(1)
        if (h.isEmpty() || h.startsWith("[") || ':' in h || IPV4.matches(h)) return null
        val original = h.split('.')
        if (original.any { it.isEmpty() }) return null
        val ascii = original.map { PublicSuffix.toAsciiLabel(it) ?: return null }

        val n = ascii.size
        // Length, in labels, of the public suffix. The implicit "*" rule makes it at least 1.
        var suffixLength = 1
        for (i in 0 until n) {
            val candidate = ascii.subList(i, n).joinToString(".")
            if ("!$candidate" in publicSuffixes) {
                // An exception rule: the suffix is the rule minus its leftmost label.
                suffixLength = n - i - 1
                break
            }
            val wildcard = if (i + 1 < n) "*." + ascii.subList(i + 1, n).joinToString(".") else "*"
            if (candidate in publicSuffixes || (i + 1 < n && wildcard in publicSuffixes)) {
                // Scanning from the longest candidate, the first match is the prevailing rule.
                // A longer exception can only sit to the left of this point, already checked.
                suffixLength = n - i
                break
            }
        }
        if (suffixLength >= n) return null
        return original.takeLast(suffixLength + 1).joinToString(".")
    }

    /**
     * Whether a page claiming to be [brand] is served from a domain that actually belongs to it.
     *
     * The comparison is against the registrable domain's own label, not against the host as a
     * whole, because the whole attack is putting the brand somewhere else in the name:
     * `paypal.secure-login.com` contains "paypal" and belongs to `secure-login.com`.
     *
     * This answers only the mechanical half. Whether the page's *presentation* is consistent with
     * its origin is the judgment the model is asked, and it can only add suspicion.
     */
    fun brandMatchesOrigin(
        brand: String,
        host: String,
        publicSuffixes: Set<String> = PublicSuffix.DEFAULT,
    ): Boolean {
        val normalisedBrand = brand.lowercase().filter { it.isLetterOrDigit() }
        if (normalisedBrand.isEmpty()) return false
        val registrable = registrableDomain(host, publicSuffixes) ?: return false
        val mainLabel = labels(registrable).first().filter { it.isLetterOrDigit() }
        return mainLabel == normalisedBrand
    }

    /**
     * Whether a form on [pageUrl] posts to a different registrable domain than it is served from —
     * a fact about where credentials would actually go.
     */
    fun postsCrossOrigin(
        pageUrl: String,
        formActionUrl: String,
        publicSuffixes: Set<String> = PublicSuffix.DEFAULT,
    ): Boolean {
        val pageHost = host(pageUrl) ?: return false
        val actionHost = host(formActionUrl) ?: return false
        val pageDomain = registrableDomain(pageHost, publicSuffixes) ?: pageHost
        val actionDomain = registrableDomain(actionHost, publicSuffixes) ?: actionHost
        return pageDomain != actionDomain
    }
}
