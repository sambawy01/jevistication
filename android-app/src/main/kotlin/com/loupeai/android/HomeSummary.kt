package com.loupeai.android

import dev.loupe.engine.OriginFacts
import dev.loupe.engine.PublicSuffix
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.SiteCheck
import dev.loupe.templates.TemplateLibrary

/**
 * What the A0 home screen shows: proof that the shared Kotlin (engine, templates, loupe-kit) runs on
 * the phone. Everything here is a pure, deterministic call: no model, no network, no files, so the
 * same answers come out on the phone, in the unit tests, on the iPhone and on Loupe Station.
 *
 * The calls are chosen to cross each Android `actual` the app depends on: the embedded Public Suffix
 * List and IDNA (engine), and NFKC through the homograph check (loupe-kit).
 */
data class HomeSummary(
    val templateCount: Int,
    val categoryCount: Int,
    val sampleTemplates: List<String>,
    val pslVersion: String,
    val domains: List<DomainRow>,
    val links: List<LinkRow>,
) {
    /** A host and the registrable domain the engine gives it (null: none, e.g. a bare suffix). */
    data class DomainRow(val host: String, val registrable: String?)

    /** A link, shown as the person would read it, and the shared phishing formula's verdict. */
    data class LinkRow(val shown: String, val level: String, val levelTitle: String, val reasons: List<String>)

    companion object {
        /** Hosts for the registrable-domain row: a multi-label suffix, a private suffix, and IDN. */
        val SAMPLE_HOSTS: List<String> = listOf("news.bbc.co.uk", "someone.github.io", "食狮.公司.cn")

        /** The formula's three levels on one screen: a real site, a subdomain brand, a homograph. */
        private val SAMPLE_LINKS: List<String> = listOf(
            "https://www.paypal.com/signin",
            "http://paypal.account-verify.example/login",
            // The first "а" is Cyrillic: the homograph the formula calls Danger.
            "https://pаypal.com/signin",
        )

        fun compute(): HomeSummary {
            val templates = TemplateLibrary.ALL
            return HomeSummary(
                templateCount = templates.size,
                categoryCount = templates.map { it.category }.distinct().size,
                sampleTemplates = templates.take(4).map { it.title },
                pslVersion = PublicSuffix.VERSION,
                domains = SAMPLE_HOSTS.map { DomainRow(it, OriginFacts.registrableDomain(it)) },
                links = SAMPLE_LINKS.map { shown ->
                    val result = SiteCheck.checkUrl(asciiUrl(shown))
                    LinkRow(shown, result.verdict.level, result.levelTitle, result.lines)
                },
            )
        }

        /** The link with its host in IDNA ASCII form, as a browser would request it. */
        private fun asciiUrl(url: String): String {
            val scheme = url.substringBefore("://")
            val rest = url.substringAfter("://")
            val host = rest.substringBefore('/')
            val ascii = host.split('.').joinToString(".") { Hosts.toAsciiLabel(it) ?: it }
            return "$scheme://$ascii" + rest.removePrefix(host)
        }
    }
}
