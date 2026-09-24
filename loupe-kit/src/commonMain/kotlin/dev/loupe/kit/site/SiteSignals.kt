package dev.loupe.kit.site

import dev.loupe.engine.OriginFacts

/*
 * Deterministic page signals: no network, no model.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/browser/signals.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). The WEIGHTS
 * table (codes, weights, reason wording), RISK_CODES, PHISHY_WORDS, the confusables table, the
 * multi-letter folds, the look-alike rule (OSA distance, indels only for 5-7 letter tokens) and every
 * check's order and conditions are copied verbatim; only the language changed. Mechanical
 * differences: registrable domains come from the engine's pinned Mozilla PSL (see [Hosts]); "does
 * this label mix scripts" is the engine's `OriginFacts.hasMixedScripts` (Unicode script property)
 * where Station used the first word of each character's Unicode name; NFKC/NFKD come from the
 * platform (`java.text.Normalizer`, `NSString`).
 *
 * What is checked:
 *   host      raw IP address, punycode / mixed-script labels (IDN homographs), look-alikes of
 *             well-known brands (confusable characters and small edits), a brand name in the
 *             subdomain of an unrelated domain or glued to other words in the domain, a brand name on
 *             a TLD the brand does not use, many subdomains, TLDs favoured by phishing kits, shared
 *             hosting / free site builders
 *   url       data: / blob: top-level pages, `user@` before the host, URL shorteners, very long URLs,
 *             many percent-encoded characters, a brand's domain inside the path
 *   forms     a password field on http://, a password form that posts to another domain or over
 *             http://, a card form on http://
 *   branding  the page names a brand (title, og:site_name, or a favicon loaded from the brand's own
 *             domain) while it is not on that brand's domain
 */

/** One deterministic signal: a stable [code], its weight from [SiteSignals.WEIGHTS] and the reason's [params]. */
data class SiteSignal(val code: String, val params: Map<String, String> = emptyMap(), val source: String = "url") {
    val weight: Int get() = SiteSignals.WEIGHTS.getValue(code).first
    val text: String get() = fillTemplate(SiteSignals.WEIGHTS.getValue(code).second, params)
}

/** A form on the page, as the checks need it. */
data class PageForm(val action: String? = null, val password: Boolean = false, val card: Boolean = false)

/** The facts about a page the checks read (Station's `page` dict). Only [url] is needed for a link. */
data class PageFacts(
    val url: String,
    val title: String? = null,
    val siteName: String? = null,
    val faviconHost: String? = null,
    val passwordFields: Int = 0,
    val cardFields: Int = 0,
    val forms: List<PageForm> = emptyList(),
    /**
     * A brand the caller knows the page (or message) claims to be, as free text — e.g. the first
     * word of an institutional sender's name. Rule 1 of docs/PHISHING-FORMULA.md: a brand on the
     * list is checked against its official domains; any other name with the engine's name-vs-domain
     * check (`OriginFacts.brandMatchesOrigin`).
     */
    val claimedBrand: String? = null,
)

/** What scoring needs besides the signals. */
data class PageVerdictFacts(
    val host: String,
    val unicodeHost: String,
    val registrable: String?,
    val scheme: String,
    val path: String,
    val knownGood: Boolean,
    val brandClaim: String?,
    val password: Boolean,
    val card: Boolean,
    val sharedHosting: Boolean,
)

/** Reference lists for one assessment: brands, known-good domains, suspicious TLDs, shorteners. */
class SiteConfig(
    val brands: List<Brand>,
    knownGood: Collection<String>,
    suspiciousTlds: Collection<String>,
    shorteners: Collection<String>,
) {
    val suspiciousTlds: Set<String> = suspiciousTlds.map { it.lowercase().trimStart('.') }.toSet()
    val shorteners: Set<String> = shorteners.map { it.lowercase() }.toSet()
    val brandDomains: Set<String> = brands.flatMap { b -> b.domains.map { it.lowercase() } }.toSet()
    val knownGood: Set<String> = knownGood.map { it.lowercase() }.toSet() + brandDomains
    val tokenToBrand: Map<String, Brand> = LinkedHashMap<String, Brand>().also { m ->
        for (b in brands) for (t in b.tokens) m.getOrPut(t.lowercase()) { b }
    }
    val tokenSkeleton: Map<String, String> = tokenToBrand.keys.associateWith { SiteSignals.skeleton(it) }
    val skeletonToBrand: Map<String, Brand> = LinkedHashMap<String, Brand>().also { m ->
        for ((t, b) in tokenToBrand) m.getOrPut(tokenSkeleton.getValue(t)) { b }
    }
    val domainToBrand: Map<String, Brand> = LinkedHashMap<String, Brand>().also { m ->
        for (b in brands) for (d in b.domains) m[d.lowercase()] = b
    }

    /** Brand display names and tokens (3+ letters) for spotting a claim in a title or display name. */
    val namePatterns: List<Pair<String, Brand>> =
        brands.flatMap { b -> (listOf(b.name) + b.tokens).distinct().filter { it.length >= 3 }.map { it to b } }

    /** Is [registrable] one of the brand's domains (or, for cctld brands, token + a country suffix)? */
    fun owns(brand: Brand, registrable: String?, suffix: String?): Boolean {
        if (registrable.isNullOrEmpty()) return false
        if (registrable in brand.domains.map { it.lowercase() }) return true
        if (brand.cctld && suffix != null && SiteSignals.isCctld(suffix)) {
            val label = registrable.dropLast(suffix.length + 1)
            return label in brand.tokens.map { it.lowercase() }
        }
        return false
    }

    /** A known-good domain: listed, or a brand's own country domain (google.com.eg, amazon.de). */
    fun known(registrable: String?, suffix: String?): Boolean {
        if (registrable.isNullOrEmpty()) return false
        return registrable in knownGood || brands.any { owns(it, registrable, suffix) }
    }

    companion object {
        /** Station's browser defaults: its brand list, no extra known-good, its TLDs and shorteners. */
        val DEFAULT: SiteConfig by lazy { SiteConfig(Brands.BRANDS, emptyList(), Brands.SUSPICIOUS_TLDS, Brands.SHORTENERS) }
    }
}

object SiteSignals {
    /** code -> (weight, plain-language reason). `{placeholders}` come from the signal's params. */
    val WEIGHTS: Map<String, Pair<Int, String>> = linkedMapOf(
        // host
        "ip_host" to (25 to "The address is a bare IP number instead of a website name."),
        "mixed_script" to (35 to "The website name mixes letters from different alphabets, a trick to imitate another name."),
        "homograph_brand" to (60 to "The website name imitates {brand} with look-alike letters from another alphabet."),
        "lookalike_brand" to (45 to "The website name looks like {brand} but is not {brand}'s website."),
        "brand_domain_in_subdomain" to (45 to "{brand}'s address is placed at the start of an unrelated website name ({domain})."),
        "brand_in_subdomain" to (30 to "The name {brand} appears in front of an unrelated website ({domain})."),
        "brand_in_domain" to (20 to "The website name glues {brand} to other words; {brand} does not use this domain."),
        "brand_in_domain_bait" to (40 to "The website name glues {brand} to words like \"login\" or \"secure\"; {brand} does not use this domain."),
        "brand_other_tld" to (20 to "The website uses the name {brand} on a domain {brand} does not use."),
        "many_subdomains" to (10 to "The website name has an unusually long chain of subdomains."),
        "suspicious_tld" to (8 to "The domain ends in .{tld}, an ending often used by throw-away scam sites."),
        "shared_hosting_login" to (15 to "A sign-in or payment form on a free hosting or site-builder address ({suffix})."),
        "shared_hosting" to (10 to "This is a customer's site on {suffix}, a shared hosting service; {suffix} does not vouch for it."),
        // url
        "data_url" to (40 to "The page was opened from a data: or blob: address, which has no real website behind it."),
        "userinfo_in_url" to (30 to "The address has text before an @ sign, which hides the real website name."),
        "url_shortener" to (10 to "This is a link shortener; the real destination is hidden."),
        "long_url" to (5 to "The address is unusually long."),
        "encoded_url" to (5 to "The address hides many encoded characters."),
        "brand_in_path" to (15 to "{brand}'s address appears in the page path, but the website is {domain}."),
        // forms
        "http_password" to (30 to "The page asks for a password over an unencrypted connection (http://)."),
        "http_card" to (30 to "The page asks for card details over an unencrypted connection (http://)."),
        "password_posts_elsewhere" to (30 to "The password or card form sends what you type to another website ({target})."),
        "password_posts_http" to (25 to "The password or card form sends what you type over an unencrypted connection."),
        "form_posts_elsewhere" to (5 to "A form on the page sends what you type to another website ({target})."),
        // branding
        "brand_mismatch_login" to (50 to "The page presents itself as {brand} and asks you to sign in or pay, but it is not on {brand}'s website."),
        "brand_mismatch" to (15 to "The page presents itself as {brand}, but it is not on {brand}'s website."),
    )

    /** Signals that count as a deterministic risk (weight >= 15). */
    val RISK_CODES: Set<String> = WEIGHTS.filter { it.value.first >= 15 }.keys

    val PHISHY_WORDS: Set<String> = """
        login logon signin sign secure security verify verification account accounts update confirm billing
        wallet auth authentication support service services help recovery recover unlock unblock suspended
        alert payment pay refund invoice id webscr session validate validation customer client portal online
        track tracking delivery parcel shipment package redelivery web prime gift bonus reward claim free
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val CONFUSABLE: Map<Char, Char> = mapOf(
        // Cyrillic
        'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'һ' to 'h', 'і' to 'l', 'ї' to 'l', 'ј' to 'j', 'к' to 'k', 'ӏ' to 'l',
        'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'ѕ' to 's', 'т' to 't', 'у' to 'y', 'х' to 'x', 'ԁ' to 'd',
        'ԛ' to 'q', 'ԝ' to 'w', 'ь' to 'b', 'г' to 'r', 'п' to 'n', 'ɡ' to 'g',
        // Greek
        'α' to 'a', 'β' to 'b', 'ε' to 'e', 'η' to 'n', 'ι' to 'l', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p', 'τ' to 't',
        'υ' to 'u', 'χ' to 'x', 'γ' to 'y', 'ω' to 'w',
        // Latin look-alikes, digits and symbols
        'i' to 'l', 'ı' to 'l', 'ł' to 'l', 'ⅼ' to 'l', 'ℓ' to 'l', '0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't',
        '8' to 'b', '$' to 's', '@' to 'a', '!' to 'l', '|' to 'l',
    )
    private val MULTI = listOf("rn" to "m", "vv" to "w", "cl" to "d", "nn" to "m")

    // ------------------------------------------------------------------------ helpers
    /** Lowercase, confusables folded to ASCII, hyphens dropped: "pаypa1" -> "paypal". */
    fun skeleton(s: String): String {
        var t = nfkc(s).lowercase().map { CONFUSABLE[it] ?: it }.joinToString("")
        t = nfkd(t).filter { it.category != CharCategory.NON_SPACING_MARK && it.category != CharCategory.ENCLOSING_MARK }
        for ((a, b) in MULTI) t = t.replace(a, b)
        return t.replace("-", "").replace("_", "")
    }

    /** Optimal string alignment distance (Levenshtein plus adjacent transposition), capped at [limit]. */
    fun osaDistance(a: String, b: String, limit: Int = 3): Int {
        if (kotlin.math.abs(a.length - b.length) >= limit) return limit
        var prev2 = IntArray(0)
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) cur[j] = minOf(cur[j], prev2[j - 2] + 1)
            }
            prev2 = prev
            prev = cur
            if (prev.min() >= limit) return limit
        }
        return minOf(prev[b.length], limit)
    }

    private fun onlyIndels(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) == 1) {
            val (short, long) = if (a.length < b.length) a to b else b to a
            return long.indices.any { long.removeRange(it, it + 1) == short }
        }
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            return diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
        }
        return false
    }

    /**
     * Is [label] (one host label, already a skeleton) a near miss of brand [token]? 4-letter-or-shorter
     * tokens: never. 5-7 letters: one inserted, deleted or swapped letter. 8+: any single edit; 10+: two.
     */
    fun lookalike(label: String, token: String): Boolean {
        if (label == token || token.length < 5) return false
        if (token.length <= 7) return onlyIndels(label, token)
        return osaDistance(label, token) <= (if (token.length >= 10) 2 else 1)
    }

    internal fun tokens(s: String): List<String> = s.lowercase().split('.', '-', '_').filter { it.isNotEmpty() }

    fun isCctld(suffix: String): Boolean {
        val last = suffix.substringAfterLast('.')
        return last.length == 2 && last.all { it.isLetter() }
    }

    // ------------------------------------------------------------------------ the checks
    fun hostSignals(u: ParsedUrl, config: SiteConfig): List<SiteSignal> {
        val out = mutableListOf<SiteSignal>()
        val host = u.host
        val reg = u.registrable
        val suffix = u.suffix
        if (host.isEmpty()) return out
        if (Hosts.isPrivateHost(host)) return out
        if (Hosts.isIp(host)) return listOf(SiteSignal("ip_host", mapOf("host" to host)))
        if (config.known(reg, suffix)) return out
        // IDN: punycode labels, mixed scripts, homographs of a brand
        val regLabel = if (reg != null && suffix != null) reg.dropLast(suffix.length + 1) else ""
        var homograph = false
        for (raw in u.labels) {
            if (!raw.startsWith("xn--")) continue
            val dec = Hosts.decodeLabel(raw)
            val brand = config.skeletonToBrand[skeleton(dec)]
            if (brand != null && dec.any { it.code >= 128 }) {
                out += SiteSignal("homograph_brand", mapOf("brand" to brand.name, "host" to u.unicodeHost))
                homograph = true
            } else if (OriginFacts.hasMixedScripts(dec)) {
                out += SiteSignal("mixed_script", mapOf("host" to u.unicodeHost))
            }
        }
        // Rule 2: an international name that is neither a brand's look-alike nor mixed-script is
        // someone's ordinary site (مثال.مصر, 例子.中国): no signal.
        // brands in the registrable label
        if (regLabel.isNotEmpty() && !homograph) {
            val dec = Hosts.decodeLabel(regLabel)
            val sk = skeleton(dec)
            val toks = tokens(dec)
            val skToks = toks.map { skeleton(it) }
            var claimed: Pair<String, Brand>? = null
            for ((token, brand) in config.tokenToBrand) {
                val tsk = config.tokenSkeleton.getValue(token)
                val code = when {
                    sk == tsk -> if (regLabel == token) "brand_other_tld" else "lookalike_brand"          // paypal.xyz / paypa1.com
                    toks.size > 1 && token in toks -> {
                        val bait = toks.any { it != token && it in PHISHY_WORDS }
                        if (bait) "brand_in_domain_bait" else "brand_in_domain"                          // paypal-secure-login.com
                    }
                    toks.size > 1 && tsk in skToks -> "lookalike_brand"                                   // paypa1-secure.com
                    lookalike(sk, tsk) || (toks.size > 1 && skToks.any { lookalike(it, tsk) }) -> "lookalike_brand" // paypall.com
                    else -> continue
                }
                if (claimed == null || WEIGHTS.getValue(code).first > WEIGHTS.getValue(claimed.first).first) claimed = code to brand
            }
            if (claimed != null) out += SiteSignal(claimed.first, mapOf("brand" to claimed.second.name, "domain" to reg!!))
        }
        // brands in the subdomain of an unrelated domain
        val sub = u.subdomain
        if (sub.isNotEmpty() && reg != null) {
            var full: Brand? = null
            for ((d, brand) in config.domainToBrand) {
                if ((sub == d || sub.startsWith("$d.") || ".$d." in ".$sub.") && !config.owns(brand, reg, suffix)) {
                    full = brand
                    break
                }
            }
            if (full != null) {
                out += SiteSignal("brand_domain_in_subdomain", mapOf("brand" to full.name, "domain" to reg))
            } else {
                for (t in tokens(sub)) {
                    val brand = if (t.length >= 3) config.skeletonToBrand[skeleton(t)] else null
                    if (brand != null && !config.owns(brand, reg, suffix)) {
                        out += SiteSignal("brand_in_subdomain", mapOf("brand" to brand.name, "domain" to reg))
                        break
                    }
                }
            }
            val n = sub.count { it == '.' } + 1
            if (n >= 4) out += SiteSignal("many_subdomains", mapOf("n" to n.toString()))
        }
        val tld = host.substringAfterLast('.')
        if (tld in config.suspiciousTlds) out += SiteSignal("suspicious_tld", mapOf("tld" to tld))
        return out
    }

    private val PERCENT = Regex("%[0-9a-fA-F]{2}")

    fun urlSignals(u: ParsedUrl, config: SiteConfig): List<SiteSignal> {
        val out = mutableListOf<SiteSignal>()
        if (u.scheme == "data" || u.scheme == "blob") return listOf(SiteSignal("data_url"))
        if (u.userinfo) out += SiteSignal("userinfo_in_url")
        if (u.host in config.shorteners || (u.registrable ?: "") in config.shorteners) out += SiteSignal("url_shortener")
        if (u.raw.length > 200) out += SiteSignal("long_url", mapOf("n" to u.raw.length.toString()))
        if (PERCENT.findAll(u.path + u.query).count() > 12) out += SiteSignal("encoded_url")
        val reg = u.registrable
        if (reg != null && !config.known(reg, u.suffix)) {
            val path = percentDecode(u.path + "?" + u.query).lowercase()
            for ((d, brand) in config.domainToBrand) {
                if (containsBounded(path, d, dashIsWord = true) && !config.owns(brand, reg, u.suffix)) {
                    out += SiteSignal("brand_in_path", mapOf("brand" to brand.name, "domain" to reg))
                    break
                }
            }
        }
        return out
    }

    fun formSignals(page: PageFacts, u: ParsedUrl): List<SiteSignal> {
        val out = mutableListOf<SiteSignal>()
        val hasPassword = page.passwordFields > 0 || page.forms.any { it.password }
        val hasCard = page.cardFields > 0 || page.forms.any { it.card }
        val local = Hosts.isPrivateHost(u.host)
        if (u.scheme == "http" && !local) {
            if (hasPassword) out += SiteSignal("http_password", source = "page")
            if (hasCard) out += SiteSignal("http_card", source = "page")
        }
        val reg = u.registrable ?: u.host
        // Rule 4: a password or card form posting to another registrable domain is strong; any
        // other form posting elsewhere (search, newsletter) is weak.
        for (f in page.forms) {
            if (!f.password && !f.card) continue
            val action = ParsedUrl.parse(f.action ?: "") ?: continue
            if (action.host.isEmpty()) continue                         // posts back to this page
            val target = Hosts.registrableDomain(action.host) ?: action.host
            if (target != reg) {
                out += SiteSignal("password_posts_elsewhere", mapOf("target" to target), source = "page")
                break
            }
            if (action.scheme == "http" && u.scheme == "https") {
                out += SiteSignal("password_posts_http", source = "page")
                break
            }
        }
        if (out.none { it.code == "password_posts_elsewhere" }) {
            for (f in page.forms) {
                if (f.password || f.card) continue
                val action = ParsedUrl.parse(f.action ?: "") ?: continue
                if (action.host.isEmpty() || action.scheme !in setOf("http", "https")) continue
                val target = Hosts.registrableDomain(action.host) ?: action.host
                if (target != reg) {
                    out += SiteSignal("form_posts_elsewhere", mapOf("target" to target), source = "page")
                    break
                }
            }
        }
        return out
    }

    /**
     * The brand the page presents itself as, from its title, og:site_name or favicon host (never from
     * body text: "Sign in with Google" on a third-party site is not a claim to be Google).
     */
    fun brandClaim(page: PageFacts, config: SiteConfig): Brand? {
        val fav = (page.faviconHost ?: "").lowercase().trim('.')
        if (fav.isNotEmpty()) config.domainToBrand[Hosts.registrableDomain(fav) ?: fav]?.let { return it }
        for (text in listOf(page.siteName ?: "", page.title ?: "")) {
            if (text.isEmpty()) continue
            for ((name, b) in config.namePatterns) if (containsWord(text, name)) return b
        }
        return null
    }

    /** A listed brand whose display name or a token is [name] (case-insensitive), or null. */
    fun listedBrand(name: String, config: SiteConfig): Brand? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        return config.brands.firstOrNull { b -> b.name.lowercase() == n || b.tokens.any { it.lowercase() == n } }
    }

    /** All deterministic signals for one page, plus the facts scoring needs. */
    fun urlAndPageSignals(page: PageFacts, config: SiteConfig = SiteConfig.DEFAULT): Pair<List<SiteSignal>, PageVerdictFacts> {
        val u = ParsedUrl.parse(page.url) ?: ParsedUrl.parse("")!!
        val signals = urlSignals(u, config).toMutableList()
        val dataPage = u.scheme == "data" || u.scheme == "blob"
        if (!dataPage) signals += hostSignals(u, config)
        signals += formSignals(page, u)
        val hasPassword = page.passwordFields > 0 || page.forms.any { it.password }
        val hasCard = page.cardFields > 0 || page.forms.any { it.card }
        val knownGood = config.known(u.registrable, u.suffix)
        // Rule 1: the brand list first (a brand owns its listed domains: Microsoft owns live.com);
        // a claimed name that is not on the list falls back to the engine's name-vs-domain check.
        val listed = brandClaim(page, config) ?: page.claimedBrand?.let { listedBrand(it, config) }
        val claimName: String? = listed?.name ?: page.claimedBrand?.trim()?.takeIf { it.isNotEmpty() }
        val mismatch = when {
            claimName == null || knownGood || dataPage || u.host.isEmpty() -> false
            listed != null -> !config.owns(listed, u.registrable, u.suffix)
            else -> !OriginFacts.brandMatchesOrigin(claimName, u.host)
        }
        if (mismatch) {
            val code = if (hasPassword || hasCard) "brand_mismatch_login" else "brand_mismatch"
            signals += SiteSignal(code, mapOf("brand" to claimName!!), source = "page")
        }
        // Rule 5: host control is the full pinned PSL; Station's shared-hosting names that are not
        // PSL suffixes (wordpress.com, weebly.com, ...) add a caution of their own.
        val sharedName = Hosts.sharedHostingNotInPsl(u.host)
        if (sharedName != null && !knownGood) {
            signals += SiteSignal("shared_hosting", mapOf("suffix" to sharedName), source = "url")
        }
        if ((hasPassword || hasCard) && Hosts.isSharedHosting(u.host) && !knownGood) {
            signals += SiteSignal("shared_hosting_login", mapOf("suffix" to (sharedName ?: u.suffix ?: "")), source = "page")
        }
        val facts = PageVerdictFacts(
            host = u.host, unicodeHost = u.unicodeHost, registrable = u.registrable, scheme = u.scheme, path = u.path,
            knownGood = knownGood, brandClaim = claimName, password = hasPassword, card = hasCard,
            sharedHosting = Hosts.isSharedHosting(u.host),
        )
        // one signal per code (the first wording wins; they share a weight)
        val seen = mutableSetOf<String>()
        return signals.filter { seen.add(it.code) } to facts
    }

    // ------------------------------------------------------------------------ text helpers
    private fun isWordChar(c: Char, dashIsWord: Boolean = false): Boolean =
        c.isLetterOrDigit() || c == '_' || (dashIsWord && c == '-')

    /** Python `(?<![\w])needle(?![\w])` with re.I, without a lookbehind (slow on Kotlin/Native). */
    fun containsWord(text: String, needle: String): Boolean = containsBounded(text, needle, dashIsWord = false, ignoreCase = true)

    internal fun containsBounded(text: String, needle: String, dashIsWord: Boolean, ignoreCase: Boolean = false): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase)
            if (at < 0) return false
            val end = at + needle.length
            val beforeOk = at == 0 || !isWordChar(text[at - 1], dashIsWord)
            val afterOk = end >= text.length || !isWordChar(text[end], dashIsWord)
            if (beforeOk && afterOk) return true
            from = at + 1
        }
    }

    /** Python `urllib.parse.unquote`: %XX sequences as UTF-8, invalid ones kept. */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>()
        val sb = StringBuilder()
        fun flush() {
            if (bytes.isNotEmpty()) {
                sb.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes += v.toByte()
                    i += 3
                    continue
                }
            }
            flush()
            sb.append(c)
            i++
        }
        flush()
        return sb.toString()
    }
}

/** Station's `template.format_map(_Missing(params))`: `{name}` from [params], "…" when missing. */
internal fun fillTemplate(template: String, params: Map<String, String>): String =
    Regex("\\{(\\w+)\\}").replace(template) { params[it.groupValues[1]] ?: "…" }
