package dev.loupe.kit.mail

import dev.loupe.kit.watchers.SAMPLE_DIR
import dev.loupe.kit.watchers.sampleReaders
import dev.loupe.sources.common.SourceRoot
import dev.loupe.sources.common.SourceScanner
import dev.loupe.sources.common.SourceType
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** The lookbehind-free keyword rules answer exactly what the engine's [dev.loupe.templates.Baseline] answers. */
class MailClassifyParityTest {
    @Test
    fun keywordRulesMatchTheBaselineEvaluator() {
        val sample = SourceScanner(sampleReaders(), TimeZone.UTC).scan(
            listOf(
                SourceRoot("sample", SourceType.MAIL_EXPORT, "$SAMPLE_DIR/mail", "sample:mail/"),
            ),
        ).items.map { it.text } // the sample's mail (Baseline's own regex is slow on Kotlin/Native)
        val texts = EMAIL_CASES.map { "From: ${it.sender}\nSubject: ${it.subject}\n\n${it.body}" } + sample + listOf(
            "TODAY!", "xtoday", "today1", "٣today", "e-invoice attached", "the card.", "cardboard", "Resume: CV", "نحتاج عاجل",
        )
        for (t in texts) {
            val a = MailClassify.answers(t)
            assertEquals(MailClassify.CATEGORY.answer(t), a.getValue("category").label, t.take(60))
            assertEquals(MailClassify.URGENCY.answer(t).toInt(), a.getValue("urgency").level, t.take(60))
            assertEquals(MailClassify.IS_PHISHING.answer(t), a.getValue("is_phishing").label, t.take(60))
            assertEquals(MailClassify.NEEDS_REPLY.answer(t), a.getValue("needs_reply").label, t.take(60))
        }
    }
}
