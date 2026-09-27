package dev.loupe.kit.parity

import dev.loupe.engine.DateFacts
import dev.loupe.engine.JudgmentLint
import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.privacy.SecretRules
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.templates.Baseline
import dev.loupe.templates.TransactionEvidence
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The portable patterns stay linear on long text on every engine. Kotlin/Native evaluates a
 * lookbehind in O(position), so a pattern that led with one was quadratic on the iPhone (a
 * 24,000-character text took 45 s in DateFacts, 12,000 characters a minute in the transaction
 * gate); the leading `\b` is now BoundedRegex's, checked in code. The bound is loose on purpose: it
 * catches a blow-up, not a constant (measured on the iOS simulator: well under a second each).
 */
class PortableRegexPerformanceTest {
    private fun linear(name: String, block: () -> Unit) {
        val t0 = TimeSource.Monotonic.markNow()
        block()
        val ms = t0.elapsedNow().inWholeMilliseconds
        println("portable regex performance: $name $ms ms")
        assertTrue(ms < 5_000, "$name: $ms ms")
    }

    @Test
    fun `boundary patterns are linear on 50000-character texts`() {
        linear("dates in digits") { DateFacts.find("12345 ".repeat(8_000)) }
        linear("dates") { DateFacts.find("on 2026-01-05 and 3 March 2026, ".repeat(1_500)) }
        linear("judgment lint") { JudgmentLint.check("why rate ".repeat(5_000)) }
        linear("secret detectors") { SecretRules.findSecrets("sk-".repeat(15_000)) }
        linear("baseline \\b pattern") { Baseline.Pattern("\\bcopy\\b", "yes", "no", "copy").answer("abcde ".repeat(8_000)) }
        linear("transaction gate") { TransactionEvidence.assess("lorem ipsum ".repeat(4_000)) }
        linear("mail anchors") { MailMessage.anchors("<a class=x title=y href=\"https://e.com\">t</a> ".repeat(1_000)) }
        linear("institutional sender") { WatcherRun.claimedBrand("security ".repeat(6_000), "billing@x.com") }
    }
}
