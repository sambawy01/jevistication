package dev.loupe.kit.site

import kotlinx.datetime.Clock

/*
 * Combine deterministic signals (and, when present, Laya's reading of the page) into one score,
 * level and reasons.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/browser/scoring.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). Thresholds
 * (SAFE_BELOW 30, DANGER_AT 60, LAYA_CAP 40, LAYA_ONLY_CAP 25), the Laya points table, the
 * impostor-login and pressure-login rules, the gates and the reason wording are copied verbatim.
 * No model is called here: Laya's answers, when a caller has them, are data.
 *
 *     score = min(100, deterministic points + combination bonus + Laya points)
 *
 * Since 2026-09-24 this is the page profile of the ONE phishing / site formula shared with Loupe
 * Station (docs/PHISHING-FORMULA.md, owner decision A): the engine's `SiteFraud` rules are merged in
 * (see [SiteSignals]) and the opt-in online facts ([OnlineSignals]) are scored here as deterministic
 * evidence, except that a young domain or certificate alone is capped at "caution".
 *
 * Guard rails: a known-good domain ignores Laya and the brand checks; Laya alone never makes a page
 * "danger" (without a deterministic risk signal the score is capped at 59, unless the page has a
 * password or card field, Laya is confident it asks to sign in or pay, and the domain is unknown);
 * Laya alone reaches "caution" only with two confident scam cues; private-network addresses are
 * never flagged for being an IP or for http://.
 */

/** One reason in a verdict. */
data class SiteReason(val code: String, val text: String, val weight: Int, val source: String, val params: Map<String, String> = emptyMap())

/** A Laya answer to one `wf-page-risk` question, as data: a yes/no ([yes]) or a choice ([choice]). */
data class LayaPageAnswer(val p: Double, val yes: Boolean? = null, val choice: String? = null, val weak: Boolean = false)

/**
 * Station's verdict for one page: score 0-100, level safe / caution / danger, reasons, gates, and
 * (formula v1.2) the [facts] shown next to it, the Laya cues that were [notCounted] and the [context].
 */
data class SiteVerdict(
    val score: Int,
    val level: String,
    val reasons: List<SiteReason>,
    val gates: List<String>,
    val facts: List<SiteFact> = emptyList(),
    val notCounted: List<NotCounted> = emptyList(),
    val context: SiteContextInfo = SiteContextInfo("none", false, null),
    val formula: String = SiteContext.FORMULA_VERSION,
)

object SiteScoring {
    const val SAFE_BELOW = 30
    const val DANGER_AT = 60
    const val LAYA_CAP = 40
    const val LAYA_ONLY_CAP = 25
    const val NO_RISK_CAP = DANGER_AT - 1
    const val NOUL_FULL = 0.8
    const val CHOICE_FULL = 0.6

    /** Questions of wf-page-risk measured as unreliable: shown, never scored. */
    val PAGE_RISK_WEAK: Set<String> = setOf("claims_to_be")

    /** Laya answer -> (code, points, reason). Choice answers are keyed "qid=label". */
    val LAYA_POINTS: Map<String, Triple<String, Int, String>> = linkedMapOf(
        "asks_visitor_to=sign_in" to Triple("laya_asks_sign_in", 10, "Laya's reading: the page asks you to sign in."),
        "asks_visitor_to=pay" to Triple("laya_asks_payment", 10, "Laya's reading: the page asks for card details or a payment."),
        "asks_visitor_to=install" to Triple("laya_asks_install", 10, "Laya's reading: the page asks you to download or install something."),
        "asks_visitor_to=claim" to Triple("laya_asks_claim", 10, "Laya's reading: the page asks you to claim a prize, gift or refund."),
        "urgency_or_threat" to Triple("laya_urgency", 15, "Laya's reading: the page pressures you to act fast or threatens you."),
        "offers_prize_or_refund" to Triple("laya_prize", 15, "Laya's reading: the page says you won something or will get money back."),
    )
    val SCAM_CUES = setOf("laya_urgency", "laya_prize", "laya_asks_payment", "laya_asks_install", "laya_asks_claim", "pressure_login")
    val CREDENTIAL_ASKS = setOf("laya_asks_sign_in", "laya_asks_payment")
    val IMPOSTOR_CODES = setOf(
        "homograph_brand", "lookalike_brand", "brand_domain_in_subdomain", "brand_in_subdomain",
        "brand_in_domain_bait", "brand_other_tld", "mixed_script", "userinfo_in_url",
    )
    val IMPOSTOR_LOGIN = Triple("impostor_login", 20, "It asks for a password or card details on that look-alike address.")
    val PRESSURE_LOGIN = Triple("pressure_login", 10, "It asks for a password or card details while pressuring you to act fast.")

    // Formula v1.2 (docs/PHISHING-FORMULA.md §5, §5c, §6.1).
    val DNSBL_WEIGHTS: Map<String, Pair<Int, String>> = linkedMapOf(
        "online_dnsbl_phish" to (Dnsbl.W_DNSBL_PHISH to "The domain {domain} is on a blocklist of phishing or malware domains."),
        "online_dnsbl_spam" to (Dnsbl.W_DNSBL_SPAM to "The domain {domain} is on a blocklist of spam and abuse domains."),
    )
    val DNSBL_RISK_CODES: Set<String> = DNSBL_WEIGHTS.keys
    val REASON_TEXT: Map<String, String> = LinkedHashMap<String, String>().apply {
        SiteSignals.WEIGHTS.forEach { (code, wt) -> put(code, wt.second) }
        LAYA_POINTS.values.forEach { (code, _, text) -> put(code, text) }
        put(IMPOSTOR_LOGIN.first, IMPOSTOR_LOGIN.third)
        put(PRESSURE_LOGIN.first, PRESSURE_LOGIN.third)
        OnlineSignals.PAGE_WEIGHTS.forEach { (code, wt) -> put(code, wt.second) }
        DNSBL_WEIGHTS.forEach { (code, wt) -> put(code, wt.second) }
        put("user_trusted", "You marked this site as trusted.")
        put("known_good", "This is the real website of a well-known company.")
    }
    val REASON_PARAMS = listOf("brand", "domain", "target", "host", "tld", "suffix", "list")
    val REASON_CODES: List<String> get() = REASON_TEXT.keys.toList()

    fun levelFor(score: Int): String = when {
        score >= DANGER_AT -> "danger"
        score >= SAFE_BELOW -> "caution"
        else -> "safe"
    }

    private data class LayaReason(val reason: SiteReason, val strong: Boolean)

    private fun layaPoints(summary: Map<String, LayaPageAnswer>): List<LayaReason> {
        val out = mutableListOf<LayaReason>()
        for ((qid, ans) in summary) {
            if (ans.weak || qid in PAGE_RISK_WEAK) continue
            val (entry, full) = if (ans.yes != null) {
                if (!ans.yes) continue
                (LAYA_POINTS[qid] ?: continue) to (ans.p >= NOUL_FULL)
            } else {
                (LAYA_POINTS["$qid=${ans.choice}"] ?: continue) to (ans.p >= CHOICE_FULL)
            }
            val (code, pts, text) = entry
            out += LayaReason(SiteReason(code, text, if (full) pts else pts / 2, "laya"), full)
        }
        return out
    }

    const val LIST_HOST_W = 45
    const val LIST_DOMAIN_W = 30
    val LIST_CORROBORATORS: Set<String> = IMPOSTOR_CODES + setOf(
        "brand_mismatch", "brand_mismatch_login", "brand_in_domain", "brand_in_path", "online_domain_new_week",
        "online_domain_new_month", "online_cert_new", "online_safe_browsing",
    ) + DNSBL_RISK_CODES

    /** The blocklist reason for a page (§5c), or null: the strongest listing of the lookup domain. */
    fun dnsblReason(site: SiteFacts?): SiteReason? {
        if (site == null || site.domain.isEmpty()) return null
        val (cat, source) = Dnsbl.strongest(site.dnsbl) ?: return null
        val code = if (cat == "phish") "online_dnsbl_phish" else "online_dnsbl_spam"
        val (w, text) = DNSBL_WEIGHTS.getValue(code)
        val p = linkedMapOf("domain" to site.domain, "online_source" to (OnlineSignals.SOURCE_NAMES[source] ?: source))
        site.dnsblFetchedAt?.let { p["fetched_at"] = it }
        return SiteReason(code, fillTemplate(text, p), w, "online", p)
    }

    /**
     * The verdict for one page. [laya] is null when Laya did not run (always, on the phone today);
     * [online] the reasons from the opt-in online checks ([OnlineSignals.pageReasons]; empty when
     * they are off), ignored on a known-good domain. [site] (formula v1.2, opt-in) is the lookup
     * domain's cached facts; [nowIso] the instant ages are measured against.
     */
    fun combine(
        det: List<SiteSignal>,
        facts: PageVerdictFacts,
        laya: Map<String, LayaPageAnswer>? = null,
        allowlisted: Boolean = false,
        online: List<SiteReason> = emptyList(),
        site: SiteFacts? = null,
        nowIso: String? = null,
    ): SiteVerdict {
        if (allowlisted) {
            return SiteVerdict(0, "safe", listOf(SiteReason("user_trusted", REASON_TEXT.getValue("user_trusted"), 0, "user")), listOf("user_trusted"))
        }
        val now = OnlineSignals.instant(nowIso) ?: Clock.System.now()
        val known = facts.knownGood
        val siteFacts = if (known) null else site
        val reasons = det.map { SiteReason(it.code, it.text, it.weight, it.source, it.params) }.toMutableList()
        val codes = det.map { it.code }.toMutableSet()
        val gates = mutableListOf<String>()
        if ((facts.password || facts.card) && (codes intersect IMPOSTOR_CODES).isNotEmpty()) {
            reasons += SiteReason(IMPOSTOR_LOGIN.first, IMPOSTOR_LOGIN.third, IMPOSTOR_LOGIN.second, "page")
            codes += IMPOSTOR_LOGIN.first
        }
        // Online facts count as deterministic evidence (never on a well-known brand's own domain).
        val onlineReasons = (if (known) emptyList() else online.distinctBy { it.code }).toMutableList()
        dnsblReason(siteFacts)?.let { onlineReasons += it }
        val onlineCodes = onlineReasons.map { it.code }.toSet()
        // v1.2 list strength: only an exact URL is "danger" alone.
        val listCorroborated = ((codes + onlineCodes) intersect LIST_CORROBORATORS).isNotEmpty() || facts.password || facts.card
        var uncorroboratedDomainHit = false
        for (i in onlineReasons.indices) {
            val r = onlineReasons[i]
            if ((r.code == "online_phish_list_host" || r.code == "online_phish_list_domain") && !listCorroborated) {
                onlineReasons[i] = r.copy(weight = if (r.code == "online_phish_list_host") LIST_HOST_W else LIST_DOMAIN_W,
                    params = r.params + ("list_strength" to "uncorroborated"))
                if (r.code == "online_phish_list_domain") uncorroboratedDomainHit = true
            }
        }
        reasons += onlineReasons
        codes += onlineCodes
        val detPoints = reasons.sumOf { it.weight }
        val riskCodes = SiteSignals.RISK_CODES + OnlineSignals.PAGE_RISK_CODES + DNSBL_RISK_CODES
        val hasRisk = (codes intersect riskCodes).isNotEmpty()
        val ctx = SiteContext.evaluate(codes, facts, siteFacts, now)
        val years: String? = ctx.ageDays?.takeIf { it >= 365 }?.let { (it / 365.25).toInt().toString() }

        var layaReasons = mutableListOf<LayaReason>()
        val notCounted = mutableListOf<NotCounted>()
        if (laya != null && !known) {
            layaReasons += layaPoints(laya)
            if ((facts.password || facts.card) && layaReasons.any { it.reason.code == "laya_urgency" && it.strong }) {
                layaReasons += LayaReason(SiteReason(PRESSURE_LOGIN.first, PRESSURE_LOGIN.third, PRESSURE_LOGIN.second, "laya"), true)
            }
        }
        fun drop(r: SiteReason, why: String, whyParams: Map<String, String?>) {
            val wp = whyParams.filterValues { it != null }.mapValues { it.value!! }
            notCounted += NotCounted(r.code, r.text, r.weight, why, fillTemplate(SiteContext.WHY_TEXT.getValue(why), wp))
        }
        // v1.2 payment context: a card request (and, on an established domain, a sign-in) is expected
        val kept = mutableListOf<LayaReason>()
        for (lr in layaReasons) {
            val r = lr.reason
            when {
                r.code == "laya_asks_payment" && ctx.paymentExpected ->
                    if (ctx.tier == "established") drop(r, "payment_expected_age", mapOf("years" to years))
                    else drop(r, "payment_expected_processor", mapOf("provider" to ctx.processor))
                r.code == "laya_asks_sign_in" && ctx.signInExpected -> drop(r, "sign_in_expected_age", mapOf("years" to years))
                else -> kept += lr
            }
        }
        // v1.2 corroboration: content cues count only when something else backs them
        if (kept.isNotEmpty()) {
            val strongScam = kept.filter { it.strong }.map { it.reason.code }.toSet() intersect SCAM_CUES
            val weighted = reasons.any { it.weight > 0 }
            val corroborated = when {
                hasRisk -> true
                ctx.tier == "established" -> false
                ctx.tier == "weak" -> strongScam.size >= 2
                else -> weighted || strongScam.size >= 2
            }
            if (!corroborated) {
                for (lr in kept) {
                    if (ctx.tier == "established") drop(lr.reason, "uncorroborated_established", mapOf("years" to years))
                    else drop(lr.reason, "uncorroborated", emptyMap())
                }
                kept.clear()
                gates += "laya_uncorroborated"
            }
        }
        layaReasons = kept
        val layaTotal = minOf(LAYA_CAP, layaReasons.sumOf { it.reason.weight })
        val strong = layaReasons.filter { it.strong }.map { it.reason.code }.toSet()

        var score = minOf(100, detPoints + layaTotal)
        val strongAsk = (facts.password || facts.card) && (strong intersect CREDENTIAL_ASKS).isNotEmpty() && !known
        if (score >= DANGER_AT && !hasRisk && !strongAsk) {
            score = NO_RISK_CAP
            gates += "no_deterministic_risk"
        }
        if (score >= DANGER_AT && uncorroboratedDomainHit && (codes intersect (riskCodes - "online_phish_list_domain")).isEmpty()) {
            score = NO_RISK_CAP                  // v1.2: a registrable-domain list match alone is caution at most
            gates += "list_domain_uncorroborated_cap"
        }
        val weighed = (reasons + layaReasons.map { it.reason }).filter { it.weight > 0 }.map { it.code }.toSet()
        if (score >= DANGER_AT && weighed.isNotEmpty() && weighed.all { it in OnlineSignals.PAGE_AGE_CODES }) {
            score = NO_RISK_CAP                  // a young domain (or certificate) alone is never "danger"
            gates += "online_age_only_cap"
        }
        if (known && reasons.isEmpty()) {
            reasons += SiteReason("known_good", REASON_TEXT.getValue("known_good"), 0, "url")
        }
        val all = (reasons + layaReasons.map { it.reason }).sortedByDescending { it.weight }
        val shown = if (known) emptyList() else SiteContext.siteFacts(siteFacts, ctx, now)
        return SiteVerdict(score, levelFor(score), all, gates, shown, notCounted, SiteContextInfo(ctx.tier, ctx.paymentExpected, ctx.processor))
    }

    /** Signals then verdict for [page]: Station's `url_and_page_signals` + `combine`. */
    fun verdict(
        page: PageFacts,
        laya: Map<String, LayaPageAnswer>? = null,
        allowlisted: Boolean = false,
        config: SiteConfig = SiteConfig.DEFAULT,
        online: List<SiteReason> = emptyList(),
        site: SiteFacts? = null,
        nowIso: String? = null,
    ): SiteVerdict {
        val (sig, facts) = SiteSignals.urlAndPageSignals(page, config)
        return combine(sig, facts, laya, allowlisted, online, site, nowIso)
    }
}

/**
 * One site check: the ONE verdict of the shared phishing / site formula (docs/PHISHING-FORMULA.md)
 * with its signals. There is no second verdict beside it any more: the engine's `SiteFraud` rules are
 * part of the formula. [warn] is true when the level is caution or danger. Nothing here blesses: a
 * "safe" level is shown as [LOWEST_LEVEL_TITLE] ("No warning signs found", formula v1.1).
 */
data class SiteCheckResult(val url: String, val verdict: SiteVerdict) {
    val warn: Boolean get() = verdict.level != "safe"

    /** The reasons that carry weight, strongest first, as plain lines (online ones say so). */
    val lines: List<String>
        get() = verdict.reasons.filter { it.weight > 0 }.map { r -> if (r.source == "online") "${OnlineSignals.label(r.params)}: ${r.text}" else r.text }

    /** Formula v1.2 display: "Warning signs" when the level warns, else "Small things noticed". */
    val linesTitle: String get() = if (warn) "Warning signs" else "Small things noticed"

    /** "Reassuring facts" (tone good), as plain lines. */
    val reassuringFacts: List<String> get() = verdict.facts.filter { it.tone == "good" }.map { it.text }

    /** Other facts (tone neutral): true, but never reassurance. */
    val otherFacts: List<String> get() = verdict.facts.filter { it.tone != "good" }.map { it.text }

    /** "Also noticed (not counted)": each cue with why it did not count. */
    val notCountedLines: List<String> get() = verdict.notCounted.map { "${it.text} ${it.whyText}" }

    val levelTitle: String
        get() = when (verdict.level) {
            "danger" -> "Danger"
            "caution" -> "Caution"
            else -> LOWEST_LEVEL_TITLE
        }

    companion object {
        /** The owner's wording for the lowest level on every surface (formula v1.1): never "safe". */
        const val LOWEST_LEVEL_TITLE: String = "No warning signs found"
    }
}

object SiteCheck {
    /** The formula's verdict for one page (no Laya on the phone). [online] as [SiteScoring.combine]. */
    fun check(page: PageFacts, config: SiteConfig = SiteConfig.DEFAULT, online: List<SiteReason> = emptyList()): SiteCheckResult =
        SiteCheckResult(page.url, SiteScoring.verdict(page, config = config, online = online))

    fun checkUrl(url: String): SiteCheckResult = check(PageFacts(url))

    /** [checkUrl] with the opt-in online facts for its domain, when the caller has them. */
    fun checkUrl(url: String, online: OnlineContext?): SiteCheckResult {
        if (online == null) return checkUrl(url)
        val page = PageFacts(url)
        val domain = OnlineSignals.lookupDomainOfUrl(url)
        val verdict = SiteScoring.verdict(page, online = OnlineSignals.pageReasons(url, online), site = online.site(domain), nowIso = online.nowIso)
        return SiteCheckResult(url, verdict)
    }

    private val URL_RE = Regex("""(?:https?://|www\.)[^\s<>"'()\[\]{}]{3,2000}""", RegexOption.IGNORE_CASE)

    /** The web links in a text (Station's mail `_urls` rule), deduplicated, at most [max]. */
    fun linksIn(text: String, max: Int = 60): List<String> =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"') }
            .map { if (it.lowercase().startsWith("http")) it else "http://$it" }.distinct().take(max).toList()
}
