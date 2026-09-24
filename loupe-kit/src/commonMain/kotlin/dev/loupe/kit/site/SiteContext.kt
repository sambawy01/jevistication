package dev.loupe.kit.site

import kotlinx.datetime.Instant

/*
 * Site context for the shared phishing formula v1.2 (docs/PHISHING-FORMULA.md §5b–§6.1): legitimacy
 * tiers, the payment-context rule and the facts a verdict shows ("Registered 11 years ago", "Card
 * payments go through Stripe"). Pure functions, no network: the facts come from the opt-in online
 * checks (the helper's registration / certificate facts, DNS, blocklists) and the page's embeds.
 *
 * PROVENANCE: ported from Loupe Station (`~/laya-studio`, commit 4cb9026),
 * `laya_studio/browser/context.py` and the fact / why texts of `browser/scoring.py`, verbatim.
 */

/**
 * The lookup domain's cached facts for one verdict (Station's `site`): registration and
 * certificates from the web helper, [dns] (§5b) and [dnsbl] (§5c). Any part may be missing.
 */
data class SiteFacts(
    val domain: String,
    val registration: DomainFacts? = null,
    val dns: DnsFacts? = null,
    val dnsbl: Map<String, DnsblResult>? = null,
    val dnsblFetchedAt: String? = null,
)

/** A fact shown next to a verdict (weight 0). [tone] "good" (reassuring) or "neutral". */
data class SiteFact(val code: String, val tone: String, val text: String, val params: Map<String, String> = emptyMap())

/** A Laya cue that did not count, and why. */
data class NotCounted(val code: String, val text: String, val wouldAdd: Int, val why: String, val whyText: String)

/** The v1.2 context of a verdict. */
data class SiteContextInfo(val tier: String, val paymentExpected: Boolean, val processor: String?)

object SiteContext {
    const val FORMULA_VERSION = "1.2"
    const val ESTABLISHED_DAYS = 365
    const val WEAK_MIN_DAYS = 180
    const val CERT_SETTLED_DAYS = 90
    const val MAX_EMBEDS = 40

    /** Registrable domain (host control, full PSL) -> the processor's name. */
    val PAYMENT_PROCESSORS: Map<String, String> = linkedMapOf(
        "stripe.com" to "Stripe", "stripe.network" to "Stripe",
        "paypal.com" to "PayPal", "paypalobjects.com" to "PayPal",
        "braintreegateway.com" to "Braintree", "braintree-api.com" to "Braintree", "braintreepayments.com" to "Braintree",
        "adyen.com" to "Adyen", "adyenpayments.com" to "Adyen",
        "checkout.com" to "Checkout.com",
        "paymob.com" to "Paymob",
        "atfawry.com" to "Fawry", "fawry.com" to "Fawry",
        "squareup.com" to "Square", "squarecdn.com" to "Square",
        "shopify.com" to "Shopify", "shopifyinc.com" to "Shopify",
        "kashier.io" to "Kashier", "geidea.net" to "Geidea", "paytabs.com" to "PayTabs",
    )

    val IMPOSTOR_CODES: Set<String> = setOf(
        "homograph_brand", "lookalike_brand", "brand_domain_in_subdomain", "brand_in_subdomain",
        "brand_in_domain_bait", "brand_other_tld", "mixed_script", "userinfo_in_url",
    )
    val DISQUALIFY_CODES: Set<String> = IMPOSTOR_CODES + setOf(
        "brand_mismatch", "brand_mismatch_login", "brand_in_domain", "brand_in_path", "impostor_login",
        "shared_hosting", "shared_hosting_login", "data_url", "ip_host",
        "http_password", "http_card", "password_posts_elsewhere", "password_posts_http",
        "online_cert_new", "online_domain_new_week", "online_domain_new_month",
        "online_phish_list_url", "online_phish_list_host", "online_phish_list_domain", "online_safe_browsing",
        "online_dnsbl_phish", "online_dnsbl_spam",
    )

    val FACT_TEXT: Map<String, String> = linkedMapOf(
        "fact_domain_age_years" to "Registered {years} years ago ({date}).",
        "fact_domain_age_months" to "Registered {months} months ago ({date}).",
        "fact_cert_issuer" to "Security certificate from {issuer}, first seen in {year}.",
        "fact_cert_first_seen" to "First security certificate seen in {year}.",
        "fact_payment_processor" to "Card payments on this page go through {provider}.",
        "fact_mail_setup" to "The domain is set up for email (MX and SPF records).",
        "fact_dmarc_enforced" to "The domain tells mail servers to {policy} forged email (DMARC).",
        "fact_dmarc_monitor" to "The domain has a DMARC record that only monitors forged email.",
        "fact_dnssec" to "Your DNS resolver validated this domain with DNSSEC.",
        "fact_bimi" to "The domain publishes a brand logo record for email (BIMI).",
        "fact_dnsbl_clean" to "Not on these blocklists: {lists}.",
        "fact_dnsbl_unavailable" to "These blocklists could not be checked from this network: {lists}.",
    )
    val WHY_TEXT: Map<String, String> = linkedMapOf(
        "payment_expected_age" to "Normal for a shop on a {years}-year-old domain.",
        "payment_expected_processor" to "Normal on a checkout that uses {provider}.",
        "sign_in_expected_age" to "Normal for a sign-in page on a {years}-year-old domain.",
        "uncorroborated" to "Only Laya's reading of the text; nothing else about this site backs it up.",
        "uncorroborated_established" to "Only Laya's reading of the text; the domain is {years} years old and nothing else backs it up.",
    )

    /** The payment processor that controls [host], or null. */
    fun processorForHost(host: String): String? {
        val raw = host.trim().trim('.').lowercase()
        if (raw.isEmpty()) return null
        val h = if (raw.all { it.code < 128 }) raw else Hosts.toAsciiDomain(raw) ?: return null
        return PAYMENT_PROCESSORS[Hosts.registrableDomain(h) ?: return null]
    }

    /** The first known processor among the page's frame / script hosts, then its card forms' targets. */
    fun pageProcessor(embeds: List<String>, forms: List<PageForm>): String? {
        for (h in embeds.take(MAX_EMBEDS)) processorForHost(h)?.let { return it }
        for (f in forms) {
            if (!f.card) continue
            val host = ParsedUrl.parse(f.action ?: "")?.host.orEmpty()
            processorForHost(host)?.let { return it }
        }
        return null
    }

    internal fun instant(value: String?): Instant? = OnlineSignals.instant(value)

    private fun ageDays(value: String?, now: Instant): Double? {
        val dt = instant(value) ?: return null
        if (dt > now) return null
        return (now - dt).inWholeSeconds / 86400.0
    }

    /** Station's `evaluate`. */
    internal data class Ctx(
        val tier: String, val disqualified: Boolean, val tainted: Boolean, val processor: String?,
        val ageDays: Double?, val paymentExpected: Boolean, val signInExpected: Boolean,
    )

    internal fun evaluate(codes: Set<String>, facts: PageVerdictFacts, site: SiteFacts?, now: Instant): Ctx {
        val reg = site?.registration?.takeIf { it.hasFacts }
        val age = reg?.let { ageDays(it.created, now) }
        val hasDisq = codes.any { it in DISQUALIFY_CODES }
        val https = facts.scheme == "https"
        val disq = hasDisq || !https || site?.domain.isNullOrEmpty()
        val processor = if (https && !hasDisq) facts.paymentProcessor else null
        var tier = "none"
        if (!disq && age != null && age >= ESTABLISHED_DAYS) {
            tier = "established"
        } else if (!disq && (age == null || age >= WEAK_MIN_DAYS)) {
            val dns = site?.dns
            if (dns != null && (dns.mailReady || dns.dnssec == true)) tier = "weak"
        }
        return Ctx(
            tier = tier, disqualified = disq, tainted = hasDisq || !https, processor = processor, ageDays = age,
            paymentExpected = !hasDisq && https && (tier == "established" || processor != null),
            signInExpected = tier == "established",
        )
    }

    private fun years(days: Double): Int = if (days >= 365) (days / 365.25).toInt() else 1

    /** Station's `site_facts`: the facts shown next to the verdict. Weight 0. */
    internal fun siteFacts(site: SiteFacts?, ctx: Ctx, now: Instant): List<SiteFact> {
        val out = mutableListOf<SiteFact>()
        fun add(code: String, tone: String, params: Map<String, String?>) {
            val p = params.filterValues { it != null }.mapValues { it.value!! }
            out += SiteFact(code, if (ctx.tainted) "neutral" else tone, fillTemplate(FACT_TEXT.getValue(code), p), p)
        }
        val domain = site?.domain.orEmpty()
        val reg = site?.registration?.takeIf { it.hasFacts }
        val age = ctx.ageDays
        if (reg != null && age != null && age >= WEAK_MIN_DAYS) {
            val date = reg.created.orEmpty().take(10)
            if (age >= ESTABLISHED_DAYS) add("fact_domain_age_years", "good", mapOf("years" to years(age).toString(), "date" to date, "domain" to domain))
            else add("fact_domain_age_months", "neutral", mapOf("months" to (age / 30.44).toInt().toString(), "date" to date, "domain" to domain))
        }
        val first = reg?.let { instant(it.firstSeen) }
        if (first != null && first <= now) {
            val tone = if ((now - first).inWholeDays >= CERT_SETTLED_DAYS) "good" else "neutral"
            val year = first.toString().take(4)
            val issuer = reg.issuers.firstOrNull()
            if (issuer != null) add("fact_cert_issuer", tone, mapOf("issuer" to issuer.take(60), "year" to year))
            else add("fact_cert_first_seen", tone, mapOf("year" to year))
        }
        ctx.processor?.let { add("fact_payment_processor", "good", mapOf("provider" to it)) }
        site?.dns?.let { dns ->
            if (dns.mx == true && dns.spf == true) add("fact_mail_setup", "good", mapOf("domain" to domain))
            when (dns.dmarc) {
                "quarantine", "reject" -> add("fact_dmarc_enforced", "good", mapOf("policy" to dns.dmarc))
                "none" -> add("fact_dmarc_monitor", "neutral", emptyMap())
            }
            if (dns.dnssec == true) add("fact_dnssec", "good", emptyMap())
            if (dns.bimi == true) add("fact_bimi", "good", emptyMap())
        }
        site?.dnsbl?.takeIf { it.isNotEmpty() }?.let { bl ->
            val clean = Dnsbl.ZONES.filter { bl[it.id]?.status == "clean" }.map { it.name }
            val down = Dnsbl.ZONES.filter { bl[it.id]?.status == "unavailable" }.map { it.name }
            if (clean.isNotEmpty()) add("fact_dnsbl_clean", "good", mapOf("lists" to clean.joinToString(", ")))
            if (down.isNotEmpty()) add("fact_dnsbl_unavailable", "neutral", mapOf("lists" to down.joinToString(", ")))
        }
        return out
    }
}
