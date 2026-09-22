package dev.loupe.engine

import java.net.URI

/**
 * Public suffixes made of more than one label.
 *
 * **This is an approximation.** Production must load the real Public Suffix List: getting eTLD+1
 * wrong is a correctness bug in the fraud check, not a cosmetic one — it decides whether
 * `paypal.secure-login.com` reads as PayPal or as `secure-login.com`. The list is a parameter
 * everywhere it is used so the real one can be supplied without touching call sites.
 */
object PublicSuffix {
    val COMMON: Set<String> = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk",
        "co.jp", "or.jp", "ne.jp",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz",
        "com.br", "com.mx", "com.ar", "com.sg", "com.hk", "com.tr", "com.cn",
        "co.za", "co.in", "co.kr", "co.il",
    )
}

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
    private val HOSTNAME = Regex("""^[\p{L}\p{N}][\p{L}\p{N}.\-_]*$""")

    /** The lowercase host of [url], or null if it cannot be parsed or names no host. */
    fun host(url: String): String? {
        val candidate = if (url.contains("://")) url else "https://$url"
        val parsed = runCatching { URI(candidate) }.getOrNull()

        // java.net.URI returns a null host for a non-ASCII authority, since those characters are
        // not legal in a URI. Falling through to manual extraction is not a nicety: without it a
        // homograph host -- the whole point of the check -- would be discarded as unparseable.
        val fromUri = parsed?.host
        val raw = fromUri ?: candidate
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

    /** True when the host contains characters outside ASCII. */
    fun hasNonAsciiHost(host: String): Boolean = host.any { it.code > 127 }

    /**
     * True when a single label mixes writing systems — the homograph signal. `pаypal.com` with a
     * Cyrillic `а` among Latin letters is the canonical case, and it is indistinguishable by eye.
     */
    fun hasMixedScripts(host: String): Boolean =
        labels(host).any { label ->
            val scripts = label.codePoints().toArray()
                .filter { Character.isLetter(it) }
                .map { Character.UnicodeScript.of(it) }
                .filterNot {
                    it == Character.UnicodeScript.COMMON || it == Character.UnicodeScript.INHERITED
                }
                .toSet()
            scripts.size > 1
        }

    /**
     * The registrable domain (eTLD+1) of [host], or null if [host] has too few labels to have one.
     *
     * @param publicSuffixes multi-label public suffixes; see [PublicSuffix] on why the real list
     *   matters in production.
     */
    fun registrableDomain(
        host: String,
        publicSuffixes: Set<String> = PublicSuffix.COMMON,
    ): String? {
        val parts = labels(host)
        if (parts.size < 2) return null
        val lastTwo = parts.takeLast(2).joinToString(".")
        val take = if (lastTwo in publicSuffixes) 3 else 2
        if (parts.size < take) return null
        return parts.takeLast(take).joinToString(".")
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
        publicSuffixes: Set<String> = PublicSuffix.COMMON,
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
        publicSuffixes: Set<String> = PublicSuffix.COMMON,
    ): Boolean {
        val pageHost = host(pageUrl) ?: return false
        val actionHost = host(formActionUrl) ?: return false
        val pageDomain = registrableDomain(pageHost, publicSuffixes) ?: pageHost
        val actionDomain = registrableDomain(actionHost, publicSuffixes) ?: actionHost
        return pageDomain != actionDomain
    }
}
