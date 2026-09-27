package dev.loupe.kit.parity

import dev.loupe.engine.DateFacts
import dev.loupe.engine.JudgmentLint
import dev.loupe.kit.mail.MailMessage
import dev.loupe.kit.mail.Phishing
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
 * characters, the median of five runs each. Linear work takes about 4 times as long, quadratic
 * about 16; the check is `t(4n) < 12 t(n) + 250 ms` (fix loop 6: a loaded machine failed the tighter
 * `8 t(n) + 100` once), which does not depend on the machine's speed. A ceiling of 20 s on t(4n) catches a
 * hang.
 */
class PortableRegexPerformanceTest {
    private fun ms(block: () -> Unit): Long {
        val t0 = TimeSource.Monotonic.markNow()
        block()
        return t0.elapsedNow().inWholeMilliseconds
    }

    private fun median5(block: () -> Unit): Long = List(5) { ms(block) }.sorted()[2]

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
        t1 = median5 { run(small) }
        val t4 = median5 { run(big) }
        println("portable regex performance: $name ${small.length} chars $t1 ms, ${big.length} chars $t4 ms")
        // Linear is about 4x, quadratic about 16x. The margin (12x + 250 ms, medians of five) keeps a
        // loaded machine from failing a linear case (fix loop 6); t1 is at least ~100 ms unless the
        // size cap came first, so a quadratic case (16 x 100 > 12 x 100 + 250) still fails.
        assertTrue(t4 < 12 * t1 + 250, "$name: ${small.length} chars $t1 ms, ${big.length} chars $t4 ms (not linear)")
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
        // text URLs full of stops (fix loop 6: the rescan after a cut was quadratic per token on iOS)
        // one token that grows (fix loop 7: the text above is capped by MAX_LINKS and URL_RE's 2,000
        // characters, so its time does not grow; this calls the per-token scan directly)
        linear("text URL token pieces", { "https://a.com" + "|x".repeat(2_000 * it) }) { Phishing.textUrlsOf(it) }
        linear("text URL token full-width pieces", { "https://a.com" + "，x".repeat(2_000 * it) }) { Phishing.textUrlsOf(it) }
        linear("text URL token stand-in dots", { "https://a" + "。a".repeat(2_000 * it) + "/x" }) { Phishing.textUrlsOf(it) }
        linear("text URL token userinfo cuts", { "https://www.paypal.com" + "|x".repeat(2_000 * it) + "@paypa1-secure.xyz/x" }) { Phishing.textUrlsOf(it) }
        // whole messages of such tokens (bounded by MAX_LINKS: a check that the cap holds, not of growth)
        linear("text URL pieces", { ("https://a.com" + "|x".repeat(995) + " ").repeat(8 * it) }) { Phishing.urls(it) }
        linear("html lone lt", { "<".repeat(20_000 * it) }) { MailMessage.anchors(it) }
        linear("html open attributes", { "<a x".repeat(5_000 * it) }) { MailMessage.anchors(it) }
        linear("html entity href", { "<a href=\"" + "&amp;&#x41;&notin;&copy".repeat(1_000 * it) + "\">x</a>" }) { MailMessage.anchors(it) }
        linear("mail anchors unclosed", { "<a class=x title=\"y>z\">t ".repeat(4_000 * it) }) { MailMessage.anchors(it) }
        linear("institutional sender", { "security ".repeat(1_500 * it) }) { WatcherRun.claimedBrand(it, "billing@x.com") }
        // a Visa-shaped number (Luhn-valid) about every 1,000 characters, with card and order words
        val block = "x".repeat(930) + " order ref visa card 4111 1111 1111 1111 exp 12/29 "
        linear("card cues", { block.repeat(12 * it) }) { t -> PiiCollector(ocr = false).scan(t) }
    }
}
