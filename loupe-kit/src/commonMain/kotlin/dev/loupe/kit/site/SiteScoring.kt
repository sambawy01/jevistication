package dev.loupe.kit.site

import dev.loupe.engine.FraudAssessment
import dev.loupe.engine.SiteFraud

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

/** Station's verdict for one page: score 0-100, level safe / caution / danger, reasons, gates. */
data class SiteVerdict(val score: Int, val level: String, val reasons: List<SiteReason>, val gates: List<String>)

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

    val REASON_TEXT: Map<String, String> = LinkedHashMap<String, String>().apply {
        SiteSignals.WEIGHTS.forEach { (code, wt) -> put(code, wt.second) }
        LAYA_POINTS.values.forEach { (code, _, text) -> put(code, text) }
        put(IMPOSTOR_LOGIN.first, IMPOSTOR_LOGIN.third)
        put(PRESSURE_LOGIN.first, PRESSURE_LOGIN.third)
        put("user_trusted", "You marked this site as trusted.")
        put("known_good", "This is the real website of a well-known company.")
    }
    val REASON_PARAMS = listOf("brand", "domain", "target", "host", "tld", "suffix")
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

    /** The verdict for one page. [laya] is null when Laya did not run (always, on the phone today). */
    fun combine(det: List<SiteSignal>, facts: PageVerdictFacts, laya: Map<String, LayaPageAnswer>? = null, allowlisted: Boolean = false): SiteVerdict {
        if (allowlisted) {
            return SiteVerdict(0, "safe", listOf(SiteReason("user_trusted", REASON_TEXT.getValue("user_trusted"), 0, "user")), listOf("user_trusted"))
        }
        val reasons = det.map { SiteReason(it.code, it.text, it.weight, it.source, it.params) }.toMutableList()
        val codes = det.map { it.code }.toSet()
        val gates = mutableListOf<String>()
        if ((facts.password || facts.card) && (codes intersect IMPOSTOR_CODES).isNotEmpty()) {
            reasons += SiteReason(IMPOSTOR_LOGIN.first, IMPOSTOR_LOGIN.third, IMPOSTOR_LOGIN.second, "page")
        }
        val detPoints = reasons.sumOf { it.weight }

        val layaReasons = mutableListOf<LayaReason>()
        if (laya != null && !facts.knownGood) {
            layaReasons += layaPoints(laya)
            if ((facts.password || facts.card) && layaReasons.any { it.reason.code == "laya_urgency" && it.strong }) {
                layaReasons += LayaReason(SiteReason(PRESSURE_LOGIN.first, PRESSURE_LOGIN.third, PRESSURE_LOGIN.second, "laya"), true)
            }
        }
        var layaTotal = minOf(LAYA_CAP, layaReasons.sumOf { it.reason.weight })
        val strong = layaReasons.filter { it.strong }.map { it.reason.code }.toSet()
        if (codes.isEmpty() && (strong intersect SCAM_CUES).size < 2 && layaTotal > LAYA_ONLY_CAP) {
            layaTotal = LAYA_ONLY_CAP
            gates += "laya_only_cap"
        }
        var score = minOf(100, detPoints + layaTotal)
        val hasRisk = (codes intersect SiteSignals.RISK_CODES).isNotEmpty()
        val strongAsk = (facts.password || facts.card) && (strong intersect CREDENTIAL_ASKS).isNotEmpty() && !facts.knownGood
        if (score >= DANGER_AT && !hasRisk && !strongAsk) {
            score = NO_RISK_CAP
            gates += "no_deterministic_risk"
        }
        if (facts.knownGood && reasons.isEmpty()) {
            reasons += SiteReason("known_good", REASON_TEXT.getValue("known_good"), 0, "url")
        }
        val all = (reasons + layaReasons.map { it.reason }).sortedByDescending { it.weight }
        return SiteVerdict(score, levelFor(score), all, gates)
    }

    /** Signals then verdict for [page]: Station's `url_and_page_signals` + `combine`. */
    fun verdict(page: PageFacts, laya: Map<String, LayaPageAnswer>? = null, allowlisted: Boolean = false, config: SiteConfig = SiteConfig.DEFAULT): SiteVerdict {
        val (sig, facts) = SiteSignals.urlAndPageSignals(page, config)
        return combine(sig, facts, laya, allowlisted)
    }
}

/**
 * One site check with both results side by side: Loupe Station's brand / look-alike scoring
 * ([station]) and the engine's mechanical site-fraud watcher ([engine]). Neither changes the other:
 * the engine's thresholds and signals are untouched, and Station's extra evidence is shown next to
 * them. [warn] is true when either one raised something.
 */
data class SiteCheckResult(val url: String, val station: SiteVerdict, val engine: FraudAssessment) {
    val warn: Boolean get() = station.level != "safe" || engine.hasWarnings()

    /** Station's reasons that carry weight, then the engine's signals, as plain lines. */
    val lines: List<String>
        get() = station.reasons.filter { it.weight > 0 }.map { it.text } + engine.signals.map { "Engine: ${it.detail}" }

    val levelTitle: String
        get() = when (station.level) {
            "danger" -> "Danger"
            "caution" -> "Caution"
            else -> if (engine.hasWarnings()) "Caution" else "No signal"
        }
}

object SiteCheck {
    /** Station's brand-lookalike scoring merged with the engine's `SiteFraud.assess` for one URL. */
    fun check(page: PageFacts, config: SiteConfig = SiteConfig.DEFAULT): SiteCheckResult {
        val (sig, facts) = SiteSignals.urlAndPageSignals(page, config)
        val station = SiteScoring.combine(sig, facts)
        val formAction = page.forms.firstOrNull { it.password && !it.action.isNullOrEmpty() }?.action
        val engine = SiteFraud.assess(page.url, claimedBrand = facts.brandClaim, formActionUrl = formAction)
        return SiteCheckResult(page.url, station, engine)
    }

    fun checkUrl(url: String): SiteCheckResult = check(PageFacts(url))

    private val URL_RE = Regex("""(?:https?://|www\.)[^\s<>"'()\[\]{}]{3,2000}""", RegexOption.IGNORE_CASE)

    /** The web links in a text (Station's mail `_urls` rule), deduplicated, at most [max]. */
    fun linksIn(text: String, max: Int = 60): List<String> =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"') }
            .map { if (it.lowercase().startsWith("http")) it else "http://$it" }.distinct().take(max).toList()
}
