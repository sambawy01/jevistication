package com.loupeai.android

import dev.loupe.templates.TemplateLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's smoke path on the host JVM, compiled against the Android variants of the shared modules
 * (their androidMain actuals, not the desktop's). The expected answers are the shared tests' own
 * (loupe-kit SiteSignalsTest, engine PublicSuffixTest), so the phone agrees with iOS and Station.
 */
class HomeSummaryTest {
    private val summary = HomeSummary.compute()

    @Test
    fun `lists the shared template library`() {
        assertEquals(TemplateLibrary.ALL.size, summary.templateCount)
        assertTrue(summary.templateCount >= 20, "only ${summary.templateCount} templates")
        assertEquals(TemplateLibrary.ALL.take(4).map { it.title }, summary.sampleTemplates)
        assertTrue(summary.categoryCount in 2..TemplateLibrary.ALL.size)
    }

    @Test
    fun `reads the embedded public suffix list`() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}_.*UTC""").matches(summary.pslVersion), summary.pslVersion)
        assertEquals(
            listOf("bbc.co.uk", "someone.github.io", "食狮.公司.cn"),
            summary.domains.map { it.registrable },
        )
    }

    @Test
    fun `gives the formula's three levels and never says safe`() {
        assertEquals(listOf("safe", "caution", "danger"), summary.links.map { it.level })
        assertEquals(listOf("No warning signs found", "Caution", "Danger"), summary.links.map { it.levelTitle })
        assertTrue(summary.links.none { it.levelTitle.contains("safe", ignoreCase = true) })
        assertTrue(summary.links[2].reasons.isNotEmpty())
    }

    @Test
    fun `is deterministic`() {
        assertEquals(summary, HomeSummary.compute())
    }
}
