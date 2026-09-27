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
 * MailMessage.fromItem's link list (fix loops 7–8): the message's anchors, plus every link found by
 * pattern whose host no anchor covers, so one harmless anchor cannot switch the fallback off. Since fix
 * loop 10 the pattern reads text/plain parts and the visible text of HTML only, never the markup. Real newsletters with a Mailchimp/list-manage footer, partner and sendgrid hosts in
 * text, a tracking link whose text names another domain, and Arabic and CJK footers stay safe
 * (round-8 and round-9 reviews, r9-work/eml: also Outlook/MSO, SVG icons, Arabic MSO, a misnested
 * shop mail and MathML); a phish that sits only in the text, behind one benign anchor,
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
    fun `URLs nobody follows are not merged in`() {
        // a DTD, namespace URIs, images' src (fix loop 9): r9-work nl6 (Outlook/MSO) has all three
        for ((name, eml) in NEWSLETTERS) {
            val m = message(eml, name)
            val nonNav = HtmlAnchors.nonNavigableUrls(eml)
            val merged = m.links.map { it.first.trim() }.filter { it in nonNav && it !in m.text }
            assertTrue(merged.isEmpty(), "$name: $merged")
        }
        val mso = message(NEWSLETTERS.first { it.first == "nl6-outlook-mso.eml" }.second, "nl6")
        assertTrue(mso.links.none { "w3.org" in it.first || it.first.startsWith("urn:") }, "${mso.links}")
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
            "nl10-substack-math.eml" to """From: The Gradient Letter <gradient@substack.com>
Subject: On attention, with equations
Content-Type: text/html; charset=utf-8

<html><body><div class="body markup"><h2>On attention</h2>
<p>The softmax is <math><mi>σ</mi><mo>(</mo><mi>x</mi><mo>)</mo></p></math> as usual.</p>
<p>An inline figure: <svg width="40" height="10"><line x1="0" y1="5" x2="40" y2="5"/></div></svg></p>
<p><a href="https://gradient.substack.com/p/on-attention">Read online</a> · <a href="https://substack.com/app">Get the app</a></p>
<p><a href="https://gradient.substack.com/action/disable_email">Unsubscribe</a></p></div></body></html>
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
""",
            "nl6-outlook-mso.eml" to """From: Northwind Traders <newsletter@northwindtraders.com>
Subject: October deals from Northwind
MIME-Version: 1.0
Content-Type: text/html; charset=utf-8

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office">
<head><meta http-equiv="Content-Type" content="text/html; charset=utf-8">
<!--[if gte mso 9]><xml><o:OfficeDocumentSettings><o:AllowPNG/><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->
<style>td{font-family:Arial} .btn a{color:#fff}</style>
<!--[if mso]><style>table{border-collapse:collapse}</style><![endif]-->
</head>
<body style="margin:0">
<center>
<table width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
<!--[if mso]><table width="600"><tr><td><![endif]-->
<table width="600" class="wrapper"><tr><td class="MsoNormal"><p class=MsoNormal><span style='font-size:11pt'><font face="Arial"><b>Hello from Northwind</font></b><o:p></o:p></span></p>
<div><!--[if mso]>
<v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" href="https://www.northwindtraders.com/deals" style="height:40px;width:200px" arcsize="10%" fillcolor="#1a73e8"><w:anchorlock/><center style="color:#fff">Shop the deals</center></v:roundrect>
<![endif]--><!--[if !mso]><!-- --><a href="https://www.northwindtraders.com/deals" class="btn" style="background:#1a73e8;color:#fff">Shop the deals</a><!--<![endif]--></div>
<p class=MsoNormal><span><i>New arrivals</span></i> <a href="https://www.northwindtraders.com/new"><span>See what's new</a></span></p>
</td></tr><tr><td><table><tr><td><img src="https://cdn.northwindtraders.com/hero.png" alt="Autumn"></td></td></tr></table>
<p>Questions? <a href="mailto:help@northwindtraders.com">help@northwindtraders.com</a></p></td></tr></tbody></table>
<!--[if mso]></td></tr></table><![endif]-->
</td></tr></table>
<p style="font-size:10px">You received this because you subscribed at northwindtraders.com. <a href="https://www.northwindtraders.com/unsubscribe?u=123">Unsubscribe</a> | <a href="https://www.northwindtraders.com/preferences">Preferences</a></p>
</center></body></html>
""",
            "nl7-svg-social.eml" to """From: Contoso Weekly <weekly@contoso.com>
Subject: Contoso Weekly #42
Content-Type: text/html; charset=utf-8

<html><body><div class="container"><h1>Contoso Weekly</h1>
<p>This week: our new app release. <a href="https://contoso.com/blog/release-42">Read the post</a></p>
<div class="social"><a href="https://twitter.com/contoso"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M0 0h16v16H0z"/></div></svg></a>
<a href="https://www.linkedin.com/company/contoso"><svg width="16" height="16"><title>LinkedIn</title><g><rect width="16" height="16"/></span></g></svg></a>
<a href="https://contoso.com/rss"><svg><use xlink:href="#rss"></use></p></svg>RSS</a></div>
<table><tr><td><p>Unclosed para<td>Next cell</table>
<p>Manage <a href="https://contoso.com/email-settings">email settings</a>.</p></div></body></html>
""",
            "nl8-arabic-mso.eml" to """From: =?UTF-8?B?2YXYt9i52YUg2KfZhNio2K3YsQ==?= <hello@elbahrcafe.com>
Subject: =?UTF-8?B?2LnYsdmI2LYg2KfZhNij2LPYqNmI2Lk=?=
Content-Type: text/html; charset=utf-8

<html dir="rtl"><body><table dir="rtl" width="100%"><tr><td>
<!--[if mso]><table><tr><td width="600"><![endif]-->
<p class=MsoNormal dir=RTL><b><span lang=AR-EG>عروض الأسبوع من مطعم البحر</span></b><o:p></o:p></p>
<p dir=rtl><font color="#333"><span>اطلب الآن</font></span> <a href="https://elbahrcafe.com/menu">القائمة</a></p>
<p><a href="https://www.facebook.com/elbahrcafe">فيسبوك</a> · <a href="https://www.instagram.com/elbahrcafe">انستغرام</a></p>
</td></tr></td></tr></table>
<!--[if mso]></td></tr></table><![endif]-->
<p>لإلغاء الاشتراك <a href="https://elbahrcafe.com/unsubscribe">اضغط هنا</a></p></body></html>
""",
            "nl9-shop-misnested.eml" to """From: Fabrikam Outdoor <deals@fabrikam.com>
Subject: 20% off tents this weekend
Content-Type: text/html; charset=utf-8

<html><head><title>Fabrikam</title></head><body>
<table class="main"><tbody><tr><td>
<div><b><a href="https://www.fabrikam.com/tents">Tents</b> on sale</a></div>
<div><i><p>Free shipping over ${'$'}50</i></p></div>
<ul><li><a href="https://www.fabrikam.com/c/sleeping-bags">Sleeping bags</a><li><a href="https://www.fabrikam.com/c/stoves">Stoves</a></ul></li>
<form action="https://www.fabrikam.com/search"><input name=q></form></form>
<p><span><a href="https://www.fabrikam.com/stores">Find a store</span></a></p>
</td></tr></tbody></table></div></body></html>
<p>Fabrikam Inc, 1 Main St. <a href="https://www.fabrikam.com/unsubscribe">Unsubscribe</a></p>
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
