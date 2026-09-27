package dev.loupe.kit.mail

import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.MimeParser
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * MailMessage.fromItem's link list (fix loops 7–8): the message's anchors, plus every link the source
 * found by pattern (EmailFacts.links) whose host no anchor covers, so one harmless anchor cannot switch
 * the fallback off. Real newsletters with a Mailchimp/list-manage footer, partner and sendgrid hosts in
 * text, a tracking link whose text names another domain, and Arabic and CJK footers stay safe
 * (round-8 review, r8-work/port/eml); a phish that sits only in the text, behind one benign anchor,
 * is judged and flagged.
 */
class MailFallbackTest {
    private fun message(eml: String, name: String): MailMessage {
        val bytes = eml.encodeToByteArray()
        val parsed = MimeParser.parseEmail(bytes, TimeZone.UTC)
        val item = SourceItem(
            id = name, sourceId = "s", kind = ItemKind.EMAIL, path = name, messageIndex = null,
            name = name, text = parsed.modelText(), hasText = true, textTruncated = false,
            sizeBytes = bytes.size.toLong(), contentHash = "h", mime = "message/rfc822",
            date = null, dateOrigin = null, email = parsed.facts, facts = emptyMap(),
        )
        return MailMessage.fromItem(item, eml)
    }

    private fun verdict(m: MailMessage) = Phishing.assess(m.sender, m.body, m.replyTo, m.authResults, m.links)

    private fun hosts(m: MailMessage) = m.links.mapNotNull { ParsedUrl.parse(Hosts.linkUrl(it.first))?.host?.ifEmpty { null } }.toSet()

    @Test
    fun `newsletters stay safe with the pattern links merged in`() {
        for ((name, eml) in NEWSLETTERS) {
            val m = message(eml, name)
            val v = verdict(m)
            assertEquals(0, v.score, "$name: ${v.codes} links=${m.links}")
            assertEquals("safe", v.level, name)
        }
    }

    @Test
    fun `a phish found only by pattern is judged although a benign anchor exists`() {
        val m = message(ATTACK, "attack.eml")
        assertTrue("www.paypal.com" in hosts(m), "the anchor: ${m.links}")
        assertTrue("paypa1-secure.xyz" in hosts(m), "the pattern link must be merged in: ${m.links}")
        // the merged links alone flag it (not only the body text)
        val byLinks = Phishing.assess(m.sender, "", links = m.links)
        assertNotEquals("safe", byLinks.level, "${byLinks.codes}")
        assertNotEquals("safe", verdict(m).level)
    }

    @Test
    fun `a pattern link an anchor already covers is not added twice`() {
        val m = message(NEWSLETTERS.first { it.first == "nl2-partner.eml" }.second, "nl2-partner.eml")
        val acme = m.links.count { ParsedUrl.parse(Hosts.linkUrl(it.first))?.host == "acme.io" }
        assertEquals(1, acme, "${m.links}")
    }

    private companion object {
        val NEWSLETTERS = listOf(
            "nl1-mailchimp.eml" to """From: Green Leaf Bakery <news@greenleafbakery.com>
Subject: Your weekly bakes
Content-Type: text/html; charset=utf-8

<html><body>
<p>Fresh sourdough this week. <a href="https://greenleafbakery.com/menu">See the menu</a></p>
<p>Follow <a href="https://instagram.com/greenleafbakery">us on Instagram</a>.</p>
<hr>
<p>Sent via Mailchimp. You are receiving this because you subscribed.
Visit mailchimp.com or https://greenleafbakery.us17.list-manage.com/unsubscribe to opt out.</p>
</body></html>
""",
            "nl2-partner.eml" to """From: Acme <hello@acme.io>
Subject: Partner update
Content-Type: text/html; charset=utf-8

<html><body>
<p>Read our news: <a href="https://acme.io/blog">the blog</a>.</p>
<p>In partnership with our friends at https://partner-co.com and https://sendgrid.net tracking.</p>
<p>Manage preferences at https://email.acme.io/prefs</p>
</body></html>
""",
            "nl3-tracking-text.eml" to """From: Shop <deals@nileshoes.com>
Subject: Sale time
Content-Type: text/html; charset=utf-8

<html><body>
<p><a href="https://click.mailer.nileshoes.com/track?u=https://nileshoes.com/sale">Shop the sale at nileshoes.com</a></p>
<p>Visible domain nileshoes.com but link goes through click.mailer.nileshoes.com</p>
</body></html>
""",
            "nl4-arabic.eml" to """From: متجر <news@sooq.example>
Subject: عروض الاسبوع
Content-Type: text/html; charset=utf-8

<html><body>
<p>تسوق الآن <a href="https://sooq.example/offers">من هنا</a></p>
<p>لإلغاء الاشتراك زوروا mailchimp.com او https://sooq.us1.list-manage.com/unsub</p>
</body></html>
""",
            "nl5-cjk.eml" to """From: 商店 <news@shop.example>
Subject: 每周优惠
Content-Type: text/html; charset=utf-8

<html><body>
<p>立即购物 <a href="https://shop.example/sale">点击这里</a></p>
<p>通过 Mailchimp 发送。取消订阅请访问 mailchimp.com 或 https://shop.us2.list-manage.com/unsub</p>
</body></html>
"""
        )
        val ATTACK = """From: PayPal Service <service@paypal-accounts.example>
Subject: Your account is limited
Content-Type: text/html; charset=utf-8

<html><body>
<p><a href="https://www.paypal.com/">PayPal</a></p>
<p>We noticed unusual activity. Sign in to restore access: https://paypa1-secure.xyz/login</p>
</body></html>
"""
    }
}
