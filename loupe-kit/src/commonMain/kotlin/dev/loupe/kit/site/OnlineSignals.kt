package dev.loupe.kit.site

import dev.loupe.engine.OriginFacts
import dev.loupe.engine.PublicSuffix
import dev.loupe.persistence.JsonValue
import kotlinx.datetime.Instant

/*
 * The opt-in online phishing checks, as evidence for the shared formula (docs/PHISHING-FORMULA.md
 * §5, owner decisions A and C, 2026-09-24). Everything here is pure: the phone fetches (Swift,
 * `ios/Loupe/Online/`), this turns what came back into weighted reasons. PRODUCT.md §4a binds it:
 * off by default, labelled "Online", the minimum sent, fully usable offline.
 *
 * PROVENANCE: the codes, reason texts and weights follow Loupe Station's uncommitted
 * `laya_studio/online/signals.py`, `online/feeds.py` (matching, PATH_SHARED_HOSTS) and
 * `mail/phishing.py` online codes (working tree at HEAD ea7697a, 2026-09-24), minus Station's
 * `domain_expiring` (+5), which the owner's formula does not include.
 *
 * Two PSL modes, never conflated:
 *  - host control (scoring) uses the full pinned PSL, ICANN + PRIVATE ([Hosts.registrableDomain]);
 *  - the domain sent for online facts is the ICANN-only eTLD+1 ([lookupDomain]), and a host under a
 *    PRIVATE suffix or on a shared-hosting name gets no online facts at all (asking about github.io's
 *    age says nothing about x.github.io).
 */

/** Registration (RDAP) and certificate (CT) facts for one domain, as the web helper returns them. */
data class DomainFacts(
    val domain: String,
    val fetchedAt: String?,
    val sources: List<String>,
    val created: String? = null,
    val updated: String? = null,
    val expires: String? = null,
    val registrar: String? = null,
    val firstSeen: String? = null,
    val latestIssued: String? = null,
    val count90d: Int? = null,
    val issuers: List<String> = emptyList(),
) {
    /** `sources: []` means the helper found nothing: never shown as "checked". */
    val hasFacts: Boolean get() = sources.isNotEmpty()
}

/** One known-phishing list entry that matched: which list, and how (url / host / domain). */
data class FeedHit(val list: String, val how: String)

/**
 * Known-phishing lists downloaded on the user's opt-in and matched **on the phone** (nothing per
 * page leaves it). Matching, from the strongest: the exact URL (host + path + query), the host
 * (unless unrelated people publish under it at the path level: [PATH_SHARED_HOSTS]), or a listed
 * bare registrable domain the host sits under (never a shared host).
 */
class FeedIndex(entries: Map<String, List<String>>) : PhishingList {
    private val urls = HashMap<String, String>()
    private val hosts = HashMap<String, String>()
    private val domains = HashMap<String, String>()

    /** How many list entries were read, per list. */
    val counts: Map<String, Int> = entries.mapValues { it.value.size }

    override val source: String = entries.keys.firstOrNull() ?: "feeds"

    init {
        for ((list, lines) in entries) {
            for (raw in lines) {
                val (key, host, root) = ListUrls.normalize(raw) ?: continue
                if (Hosts.isPrivateHost(host)) continue
                urls.getOrPut(key) { list }
                hosts.getOrPut(host) { list }
                val reg = Hosts.registrableDomain(host)
                if (root && reg != null && (host == reg || host == "www.$reg") && !ListUrls.isSuppressed(reg)) domains.getOrPut(reg) { list }
            }
        }
    }

    /**
     * Station's `FeedIndex.match` with v1.2's shared-host list: the exact URL always counts; a
     * host or registrable-domain match never on a shared host ([ListUrls.isSuppressed]).
     */
    override fun match(url: String): FeedHit? {
        val (key, host, _) = ListUrls.normalize(url) ?: return null
        urls[key]?.let { return FeedHit(it, "url") }
        if (ListUrls.isSuppressed(host)) return null
        hosts[host]?.let { return FeedHit(it, "host") }
        val reg = Hosts.registrableDomain(host) ?: host
        if (!Hosts.isSharedHosting(host) && !ListUrls.isSuppressed(reg)) domains[reg]?.let { return FeedHit(it, "domain") }
        return null
    }

    companion object {
        /**
         * (host + path + query, host, bare) for a URL: scheme and fragment ignored, host lowercased
         * and in ASCII, `bare` when the entry has no path beyond "/" and no query.
         */
        internal fun urlKey(url: String): Triple<String, String, Boolean>? = ListUrls.normalize(url)

        /** OpenPhish's community feed: one URL per line. */
        fun parseOpenPhish(text: String): List<String> =
            text.lineSequence().map { it.trim() }.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }.toList()

        /** PhishTank's `online-valid.json`: an array of objects with a `url`. */
        fun parsePhishTank(json: String): List<String> =
            (runCatching { JsonValue.parse(json) }.getOrNull() as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Obj)?.str("url") }.orEmpty()

        /** Hosts where a listed URL says nothing about the rest of the host (Station's `PATH_SHARED_HOSTS`). */
        val PATH_SHARED_HOSTS: Set<String> = """
            docs.google.com drive.google.com sites.google.com forms.gle storage.googleapis.com firebasestorage.googleapis.com
            script.google.com onedrive.live.com 1drv.ms forms.office.com sway.office.com sharepoint.com dropbox.com
            dl.dropboxusercontent.com ipfs.io dweb.link cloudflare-ipfs.com gateway.pinata.cloud nftstorage.link w3s.link
            t.me telegra.ph discord.com discord.gg linktr.ee notion.site canva.site webflow.io wixsite.com
            github.com raw.githubusercontent.com gist.github.com bitbucket.org gitlab.com pastebin.com archive.org
            web.archive.org s3.amazonaws.com blob.core.windows.net r2.dev pages.dev workers.dev vercel.app netlify.app
            herokuapp.com glitch.me replit.app repl.co codepen.io jsfiddle.net typeform.com jotform.com formstack.com
            wufoo.com surveymonkey.com mailchi.mp
        """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() + Brands.SHORTENERS
    }
}

/**
 * What the online checks brought back for one run, as data: [facts] per lookup domain, the
 * downloaded [feeds], and the URLs Google Safe Browsing flagged ([safeBrowsingHits]). [nowIso] is the
 * instant ages are measured against. An empty context scores nothing.
 */
class OnlineContext(
    val nowIso: String,
    val facts: Map<String, DomainFacts> = emptyMap(),
    val feeds: FeedIndex? = null,
    val safeBrowsingHits: Set<String> = emptySet(),
    val safeBrowsingFetchedAt: String? = null,
    val feedsFetchedAt: String? = null,
    /** Phishing.Database, loaded on the phone (owner decision B). */
    val phishingDb: PhishingDbIndex? = null,
    /** Formula v1.2 §5b: DNS facts per lookup domain (the DNS source on). */
    val dns: Map<String, DnsFacts> = emptyMap(),
    /** Formula v1.2 §5c: blocklist answers per lookup domain (the lists on). */
    val dnsbl: Map<String, Map<String, DnsblResult>> = emptyMap(),
    val dnsblFetchedAt: String? = null,
) {
    /** The strongest list hit for [url] (url, then host, then domain) across the lists on this phone. */
    fun listHit(url: String): FeedHit? {
        val hits = listOfNotNull(feeds?.match(url), phishingDb?.match(url))
        return hits.minByOrNull { OnlineSignals.FEED_KINDS.indexOf(it.how) }
    }

    /** The v1.2 site facts for a lookup [domain], or null when nothing is known about it. */
    fun site(domain: String?): SiteFacts? {
        if (domain == null) return null
        val reg = facts[domain]
        val d = dns[domain]
        val bl = dnsbl[domain]
        return SiteFacts(domain, reg, d, bl, dnsblFetchedAt)
    }

    /** When the list hits were fetched ([feedsFetchedAt], or Phishing.Database's list date). */
    fun listFetchedAt(hit: FeedHit): String? = if (hit.list == PhishingDb.SOURCE) phishingDb?.listDate ?: feedsFetchedAt else feedsFetchedAt
}

object OnlineSignals {
    const val W_DOMAIN_7D = 35
    const val W_DOMAIN_30D = 20
    const val W_DOMAIN_180D = 8
    const val W_CERT_NEW = 20
    const val W_LISTED = 60
    const val W_SAFE_BROWSING = 60
    const val CERT_NEW_DAYS = 7
    /** List hit kinds, strongest first. */
    val FEED_KINDS: List<String> = listOf("url", "host", "domain")

    /** Page (browser / link) codes. */
    val PAGE_WEIGHTS: Map<String, Pair<Int, String>> = linkedMapOf(
        "online_domain_new_week" to (W_DOMAIN_7D to "The domain {domain} was registered less than a week ago."),
        "online_domain_new_month" to (W_DOMAIN_30D to "The domain {domain} was registered less than a month ago."),
        "online_domain_new_halfyear" to (W_DOMAIN_180D to "The domain {domain} was registered less than six months ago."),
        "online_cert_new" to (W_CERT_NEW to "The first security certificate for {domain} was issued less than a week ago."),
        "online_phish_list_url" to (W_LISTED to "This page is on a public list of known phishing pages ({list})."),
        "online_phish_list_host" to (W_LISTED to "This website ({host}) is on a public list of known phishing sites ({list})."),
        "online_phish_list_domain" to (W_LISTED to "The domain {domain} is on a public list of known phishing sites ({list})."),
        "online_safe_browsing" to (W_SAFE_BROWSING to "Google Safe Browsing lists this page as dangerous."),
    )
    val PAGE_RISK_CODES: Set<String> = PAGE_WEIGHTS.filter { it.value.first >= 15 }.keys
    val PAGE_AGE_CODES: Set<String> = setOf("online_domain_new_week", "online_domain_new_month", "online_domain_new_halfyear", "online_cert_new")

    /** Email codes (the same numbers under mail's own names). */
    val MAIL_WEIGHTS: Map<String, Pair<Int, String>> = linkedMapOf(
        "sender_domain_new_week" to (W_DOMAIN_7D to "The sender's domain {domain} was registered less than a week ago."),
        "sender_domain_new_month" to (W_DOMAIN_30D to "The sender's domain {domain} was registered less than a month ago."),
        "sender_domain_new_halfyear" to (W_DOMAIN_180D to "The sender's domain {domain} was registered less than six months ago."),
        "sender_cert_new" to (W_CERT_NEW to "The first security certificate for the sender's domain {domain} was issued less than a week ago."),
        "sender_phish_list" to (W_LISTED to "The sender's domain {domain} is on a public list of known phishing sites."),
        "link_domain_new_week" to (W_DOMAIN_7D to "A link goes to {domain}, a domain registered less than a week ago."),
        "link_domain_new_month" to (W_DOMAIN_30D to "A link goes to {domain}, a domain registered less than a month ago."),
        "link_domain_new_halfyear" to (W_DOMAIN_180D to "A link goes to {domain}, a domain registered less than six months ago."),
        "link_cert_new" to (W_CERT_NEW to "A link goes to {domain}, whose first security certificate was issued less than a week ago."),
        "link_phish_list" to (W_LISTED to "A link goes to a page on a public list of known phishing sites ({domain})."),
        "link_safe_browsing" to (W_SAFE_BROWSING to "Google Safe Browsing lists a link in this message as dangerous."),
    )
    val MAIL_AGE_CODES: Set<String> = setOf(
        "sender_domain_new_week", "sender_domain_new_month", "sender_domain_new_halfyear", "sender_cert_new",
        "link_domain_new_week", "link_domain_new_month", "link_domain_new_halfyear", "link_cert_new",
    )

    /** Where a fact came from, in words, for the "Online" label. */
    val SOURCE_NAMES: Map<String, String> = mapOf(
        "helper" to "Loupe web helper (RDAP, certificate logs)", "openphish" to "OpenPhish", "phishtank" to "PhishTank",
        "safe_browsing" to "Google Safe Browsing", "phishingdb" to "Phishing.Database", "dns" to "DNS",
        "dnsbl" to "Domain blocklists", "spamhaus_dbl" to "Spamhaus DBL", "surbl" to "SURBL", "uribl" to "URIBL",
    )

    /**
     * The label every online reason carries (PRODUCT.md §4a): "Online · <source> · fetched <when>".
     */
    fun label(params: Map<String, String>): String =
        listOfNotNull("Online", params["online_source"], params["fetched_at"]?.let { "fetched $it" }).joinToString(" · ")

    // ------------------------------------------------------------------ what may be looked up
    /**
     * The domain to ask the web helper about for [host]: its **ICANN** registrable domain (eTLD+1
     * with the PSL's PRIVATE section off), or null — IPs, private names, shared-hosting names and
     * any host under a PRIVATE suffix (x.github.io, storage.googleapis.com) get no online facts.
     */
    fun lookupDomain(host: String): String? {
        val raw = host.trim().trim('.').lowercase()
        val h = if (raw.all { it.code < 128 }) raw else Hosts.toAsciiDomain(raw) ?: return null
        if (h.isEmpty() || Hosts.isIp(h) || Hosts.isPrivateHost(h) || Hosts.isSharedHosting(h)) return null
        val full = OriginFacts.registrableDomain(h) ?: return null
        val icann = OriginFacts.registrableDomain(h, PublicSuffix.ICANN) ?: return null
        return if (full == icann) icann else null
    }

    /** [lookupDomain] for a URL's host. */
    fun lookupDomainOfUrl(url: String): String? = ParsedUrl.parse(url)?.host?.let(::lookupDomain)

    // ------------------------------------------------------------------ ages
    internal fun instant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        return runCatching { Instant.parse(if (v.length == 10) "${v}T00:00:00Z" else v) }.getOrNull()
    }

    private fun daysBetween(a: Instant, b: Instant): Double = (b - a).inWholeSeconds / 86400.0

    /** The strongest registration-age kind and the new-certificate kind for [f] at [nowIso]. */
    fun ageKinds(f: DomainFacts, nowIso: String): List<String> {
        if (!f.hasFacts) return emptyList()
        val now = instant(nowIso) ?: return emptyList()
        val out = mutableListOf<String>()
        instant(f.created)?.takeIf { it <= now }?.let { created ->
            val age = daysBetween(created, now)
            when {
                age < 7 -> out += "domain_new_week"
                age < 30 -> out += "domain_new_month"
                age < 180 -> out += "domain_new_halfyear"
            }
        }
        instant(f.firstSeen)?.takeIf { it <= now && daysBetween(it, now) < CERT_NEW_DAYS }?.let { out += "cert_new" }
        return out
    }

    private fun reason(code: String, weights: Map<String, Pair<Int, String>>, params: Map<String, String>, source: String, fetchedAt: String?): SiteReason {
        val p = LinkedHashMap(params)
        p["online_source"] = SOURCE_NAMES[source] ?: source
        fetchedAt?.let { p["fetched_at"] = it }
        return SiteReason(code, fillTemplate(weights.getValue(code).second, p), weights.getValue(code).first, "online", p)
    }

    // ------------------------------------------------------------------ page / link reasons
    /** The online reasons for one page or link [url] (empty when nothing online applies). */
    fun pageReasons(url: String, ctx: OnlineContext): List<SiteReason> {
        val u = ParsedUrl.parse(url) ?: return emptyList()
        if (u.host.isEmpty()) return emptyList()
        val out = mutableListOf<SiteReason>()
        ctx.listHit(url)?.let { hit ->
            val code = "online_phish_list_${hit.how}"
            out += reason(code, PAGE_WEIGHTS, mapOf("host" to u.host, "domain" to (u.registrable ?: u.host), "list" to (SOURCE_NAMES[hit.list] ?: hit.list)), hit.list, ctx.listFetchedAt(hit))
        }
        if (url in ctx.safeBrowsingHits) out += reason("online_safe_browsing", PAGE_WEIGHTS, emptyMap(), "safe_browsing", ctx.safeBrowsingFetchedAt)
        val domain = lookupDomain(u.host)
        val facts = domain?.let { ctx.facts[it] }
        if (domain != null && facts != null) {
            for (kind in ageKinds(facts, ctx.nowIso)) {
                out += reason("online_$kind", PAGE_WEIGHTS, mapOf("domain" to domain), "helper", facts.fetchedAt)
            }
        }
        return out
    }

    /**
     * The online evidence for one email: its sender's registrable domain [senderDomain] (null for a
     * free-mail or unknown sender) and the [linkUrls] worth checking. (code, params) pairs in mail's
     * codes, each with `online_source` / `fetched_at` params for the "Online" label.
     */
    fun mailEvidence(senderDomain: String?, linkUrls: List<String>, ctx: OnlineContext): List<SiteReason> {
        val out = mutableListOf<SiteReason>()
        val seen = mutableSetOf<String>()
        fun add(r: SiteReason) { if (seen.add(r.code)) out += r }
        if (senderDomain != null) {
            val d = lookupDomain(senderDomain)
            ctx.listHit("http://$senderDomain/")?.let { hit ->
                add(reason("sender_phish_list", MAIL_WEIGHTS, mapOf("domain" to senderDomain), hit.list, ctx.listFetchedAt(hit)))
            }
            val f = d?.let { ctx.facts[it] }
            if (d != null && f != null) for (k in ageKinds(f, ctx.nowIso)) add(reason("sender_$k", MAIL_WEIGHTS, mapOf("domain" to d), "helper", f.fetchedAt))
        }
        for (url in linkUrls) {
            val u = ParsedUrl.parse(url) ?: continue
            if (u.host.isEmpty()) continue
            val reg = u.registrable ?: u.host
            ctx.listHit(url)?.let { hit -> add(reason("link_phish_list", MAIL_WEIGHTS, mapOf("domain" to reg), hit.list, ctx.listFetchedAt(hit))) }
            if (url in ctx.safeBrowsingHits) add(reason("link_safe_browsing", MAIL_WEIGHTS, emptyMap(), "safe_browsing", ctx.safeBrowsingFetchedAt))
            val d = lookupDomain(u.host) ?: continue
            val f = ctx.facts[d] ?: continue
            for (k in ageKinds(f, ctx.nowIso)) add(reason("link_$k", MAIL_WEIGHTS, mapOf("domain" to d), "helper", f.fetchedAt))
        }
        return out
    }

    // ------------------------------------------------------------------ the helper's answer
    /**
     * Parses the web helper's `POST /v1/domain-facts` answer for [domain]. Throws
     * [IllegalArgumentException] on a malformed body or an answer about another domain.
     * `sources: []` yields facts with [DomainFacts.hasFacts] false: never labelled "checked".
     */
    @Throws(IllegalArgumentException::class)
    fun parseDomainFacts(json: String, domain: String): DomainFacts {
        val o = runCatching { JsonValue.parse(json) }.getOrNull() as? JsonValue.Obj ?: throw IllegalArgumentException("bad_response")
        val answered = o.str("domain")?.lowercase().orEmpty()
        require(answered.isEmpty() || answered == domain.lowercase()) { "bad_response" }
        val sources = (o["sources"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
        val reg = (o["registration"] as? JsonValue.Obj).takeIf { sources.isNotEmpty() }
        val certs = (o["certificates"] as? JsonValue.Obj).takeIf { sources.isNotEmpty() }
        return DomainFacts(
            domain = domain.lowercase(),
            fetchedAt = o.str("fetched_at"),
            sources = sources,
            created = reg?.str("created"),
            updated = reg?.str("updated"),
            expires = reg?.str("expires"),
            registrar = reg?.str("registrar")?.take(200),
            firstSeen = certs?.str("first_seen"),
            latestIssued = certs?.str("latest_issued"),
            count90d = (certs?.get("count_90d") as? JsonValue.Num)?.text?.toDoubleOrNull()?.toInt()?.takeIf { it >= 0 },
            issuers = (certs?.get("issuers") as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value?.take(120) }.orEmpty().take(10),
        )
    }
}

/** A string field, or null when absent / null / not a scalar. */
internal fun JsonValue.Obj.str(key: String): String? = when (val v = this[key]) {
    is JsonValue.Str -> v.value
    is JsonValue.Num -> v.text
    else -> null
}
