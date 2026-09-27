package dev.loupe.kit.parity

import dev.loupe.engine.DateFacts
import dev.loupe.engine.JudgmentLint
import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.privacy.PiiCollector
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
 * 24,000-character text took 45 s in DateFacts, 12,000 characters a minute in the transaction gate,
 * 48,000 characters with a card every 1,000 took 6.6 s in the card-cue search); the leading `\b` is now
 * BoundedRegex's, and the cue search reads a short window.
 *
 * Each case grows its text until one run takes at least 100 ms (or the text reaches about 800,000
 * characters: then it is fast enough at any size that matters), then times the text of n and of 4n
 * characters, the median of three runs each. Linear work takes about 4 times as long, quadratic
 * about 16; the check is `t(4n) < 8 t(n) + 100 ms`, which does not depend on the machine's speed,
 * and timer noise is small against t(n) of about 100 ms. A ceiling of 20 s on t(4n) catches a
 * hang.
 */
class PortableRegexPerformanceTest {
    private fun ms(block: () -> Unit): Long {
        val t0 = TimeSource.Monotonic.markNow()
        block()
        return t0.elapsedNow().inWholeMilliseconds
    }

    private fun median3(block: () -> Unit): Long = listOf(ms(block), ms(block), ms(block)).sorted()[1]

    private fun linear(name: String, text: (Int) -> String, run: (String) -> Unit) {
        var k = 1
        run(text(1)) // warm up
        var t1 = ms { run(text(k)) }
        while (t1 < 100 && text(k).length < 800_000) {
            k *= 2
            t1 = ms { run(text(k)) }
        }
        val small = text(k)
        val big = text(4 * k)
        t1 = median3 { run(small) }
        val t4 = median3 { run(big) }
        println("portable regex performance: $name ${small.length} chars $t1 ms, ${big.length} chars $t4 ms")
        // (+100 ms: when the text hit the size cap first, t(n) can be short; a quadratic case is not)
        assertTrue(t4 < 8 * t1 + 100, "$name: ${small.length} chars $t1 ms, ${big.length} chars $t4 ms (not linear)")
        assertTrue(t4 < 20_000, "$name: $t4 ms")
    }

    @Test
    fun `boundary patterns are linear`() {
        linear("dates in digits", { "12345 ".repeat(2_000 * it) }) { DateFacts.find(it) }
        linear("dates", { "on 2026-01-05 and 3 March 2026, ".repeat(400 * it) }) { DateFacts.find(it) }
        linear("judgment lint", { "why rate ".repeat(1_300 * it) }) { JudgmentLint.check(it) }
        linear("secret detectors", { "sk-".repeat(4_000 * it) }) { SecretRules.findSecrets(it) }
        linear("baseline \\b pattern", { "abcde ".repeat(2_000 * it) }) { Baseline.Pattern("\\bcopy\\b", "yes", "no", "copy").answer(it) }
        linear("transaction gate", { "lorem ipsum ".repeat(1_000 * it) }) { TransactionEvidence.assess(it) }
        linear("mail anchors", { "<a class=x title=y href=\"https://e.com\">t</a> ".repeat(250 * it) }) { MailMessage.anchors(it) }
        // anchors without an href that never close: every one is read, none counts toward the cap
        linear("mail anchors unclosed", { "<a class=x title=\"y>z\">t ".repeat(4_000 * it) }) { MailMessage.anchors(it) }
        linear("institutional sender", { "security ".repeat(1_500 * it) }) { WatcherRun.claimedBrand(it, "billing@x.com") }
        // a Visa-shaped number (Luhn-valid) about every 1,000 characters, with card and order words
        val block = "x".repeat(930) + " order ref visa card 4111 1111 1111 1111 exp 12/29 "
        linear("card cues", { block.repeat(12 * it) }) { t -> PiiCollector(ocr = false).scan(t) }
    }
}
