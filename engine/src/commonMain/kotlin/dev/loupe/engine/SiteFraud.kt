package dev.loupe.engine

/** A mechanical signal against a page. */
data class FraudSignal(val name: String, val detail: String)

/**
 * What the mechanical layer found about a page.
 *
 * There is deliberately **no `isSafe`**. §4 of the spec: the product warns, and never displays an
 * all-clear implying safety. An empty [signals] list means these particular checks found nothing —
 * not that the page is trustworthy — and [summary] says exactly that.
 */
data class FraudAssessment(val url: String, val signals: List<FraudSignal>) {
    /** True when at least one mechanical signal fired. */
    fun hasWarnings(): Boolean = signals.isNotEmpty()

    /** Wording that never blesses. */
    fun summary(): String =
        if (signals.isEmpty()) {
            "No mechanical signal fired for $url. This is not an all-clear: these checks cover " +
                "origin only, and a page nobody has reported yet looks like any other."
        } else {
            "${signals.size} signal(s) for $url: " + signals.joinToString("; ") { it.detail }
        }
}

/**
 * Site fraud and identity mismatch (C3), mechanical layer: the engine's **thin** API.
 *
 * The apps do not show this as a verdict. Since 2026-09-24 (owner decision A) one phishing / site
 * formula serves both Loupe and Loupe Station: `dev.loupe.kit.site.PhishingFormula` in loupe-kit,
 * written down in docs/PHISHING-FORMULA.md. It reuses the facts here — [OriginFacts] (hosts, the
 * pinned PSL, scripts, where a form posts) — and adds what the engine cannot know (brand lists,
 * confusables, field types, online facts). This object stays for engine-only callers and follows the
 * same rules where the engine can apply them:
 *  - a punycode host is **not** a signal by itself (rule 2); only mixed scripts in one label are,
 *    checked on the decoded label;
 *  - [OriginFacts.brandMatchesOrigin] is the name-vs-domain check the formula uses only for brands
 *    that are not on its brand list (rule 1);
 *  - a cross-domain form post is reported as a fact; the formula weighs it by the fields it carries
 *    (rule 4).
 *
 * Every check here reads the page's *origin*, never its content, because origin facts are
 * unforgeable by the page and content is written by whoever is attacking. Content may be fed to a
 * judgment afterwards, where §4 allows it only to add suspicion — never to remove a signal raised
 * here.
 *
 * Stated limit, published rather than hidden: a small local model is not adversarially robust, and
 * a well-crafted novel phishing page is a hard case. This is supplementary to browser protections,
 * not a replacement.
 */
object SiteFraud {

    /**
     * Assesses [pageUrl], optionally against the brand the page presents itself as and the
     * address its form posts to.
     */
    fun assess(
        pageUrl: String,
        claimedBrand: String? = null,
        formActionUrl: String? = null,
        knownBadHosts: Set<String> = emptySet(),
        publicSuffixes: Set<String> = PublicSuffix.DEFAULT,
    ): FraudAssessment {
        val signals = mutableListOf<FraudSignal>()
        val host = OriginFacts.host(pageUrl)
            ?: return FraudAssessment(
                pageUrl,
                listOf(FraudSignal("unparseable-url", "$pageUrl names no host")),
            )

        if (host in knownBadHosts) {
            signals += FraudSignal("known-bad", "$host is on a known-bad list")
        }
        if (OriginFacts.hasMixedScripts(host)) {
            signals += FraudSignal("mixed-script-host", "$host mixes writing systems in one label")
        }
        if (claimedBrand != null &&
            !OriginFacts.brandMatchesOrigin(claimedBrand, host, publicSuffixes)
        ) {
            val registrable = OriginFacts.registrableDomain(host, publicSuffixes) ?: host
            signals += FraudSignal(
                "brand-origin-mismatch",
                "page presents itself as '$claimedBrand' but belongs to $registrable",
            )
        }
        if (formActionUrl != null &&
            OriginFacts.postsCrossOrigin(pageUrl, formActionUrl, publicSuffixes)
        ) {
            signals += FraudSignal(
                "cross-origin-form-post",
                "the form posts to ${OriginFacts.host(formActionUrl)}, a different registrable domain",
            )
        }
        return FraudAssessment(pageUrl, signals)
    }
}
