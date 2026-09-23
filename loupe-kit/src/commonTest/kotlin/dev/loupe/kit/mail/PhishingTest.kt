package dev.loupe.kit.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`, commit
 * ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e): `tests/email_cases.py` (the realistic synthetic
 * emails, verbatim: sender, subject, body, provider labels, reply-to, links, truth) and the
 * deterministic tests of `tests/test_phishing.py` (evidence, reply-to, links, authentication
 * results, trusted senders, reason texts, provider categories). The Gmail / Graph / IMAP / Composio
 * fetch tests and the run API tests have no counterpart on the phone and are not ported.
 */
internal data class EmailCase(
    val id: String,
    val sender: String,
    val subject: String,
    val body: String,
    val labels: List<String>,
    val phishing: Boolean,
    val replyTo: String = "",
    val links: List<Pair<String, String>> = emptyList(),
)

internal val EMAIL_CASES: List<EmailCase> = listOf(
    EmailCase("google-alert", "Google <no-reply@accounts.google.com>", "Security alert",
        "A new sign-in on Mac\n\nWe noticed a new sign-in to your Google Account on a Mac device. If this was you, you don't need to do anything. If not, we'll help you secure your account.\n\nCheck activity\n\nYou can also see security activity at https://myaccount.google.com/notifications",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("godaddy-receipt", "GoDaddy <donotreply@godaddy.com>", "Your GoDaddy order receipt #3219876543",
        "Thanks for your order, Ahmed.\nOrder number: 3219876543\nDomain registration – example-shop.com – 1 year US$ 21.99\nTotal: US$ 21.99, charged to Visa ending 4412.\nYour products are ready to use. Questions? Visit https://www.godaddy.com/help",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("cib-maintenance", "\"CIB Business Online\" <CIB.BusinessOnline@cibeg.com>", "Scheduled maintenance of CIB Business Online",
        "Dear valued client,\nPlease note that CIB Business Online will be unavailable on Friday 26 September from 01:00 to 05:00 AM Cairo time due to scheduled system maintenance. We apologize for any inconvenience.\nCommercial International Bank",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("cib-maintenance-ar", "\"CIB Business Online\" <CIB.BusinessOnline@cibeg.com>", "صيانة مجدولة لخدمة CIB Business Online",
        "عميلنا العزيز،\nنود إحاطتكم علماً بأن خدمة CIB Business Online ستكون غير متاحة يوم الجمعة ٢٦ سبتمبر من الساعة ١ حتى ٥ صباحاً بتوقيت القاهرة لأعمال الصيانة المجدولة. نعتذر عن أي إزعاج.\nالبنك التجاري الدولي",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("cloudflare-event", "Cloudflare <em@em1.cloudflare.com>", "Join us at Cloudflare Connect 2026 in London",
        "Register now for two days of keynotes, hands-on labs and customer stories. Early-bird pricing ends Friday.\nRegister: https://www.cloudflare.com/connect2026/\nYou are receiving this email because you signed up for Cloudflare updates. Unsubscribe.",
        listOf("INBOX", "CATEGORY_PROMOTIONS"), false),
    EmailCase("jomashop-sale", "Jomashop <jomashop@e.jomashop.com>", "Up to 70% off Swiss watches – 48 hours only",
        "Flash sale: TAG Heuer, Longines and more at up to 70% off. Ends Sunday midnight.\nShop now https://click.e.jomashop.com/?qs=8a1f\nYou are receiving this because you subscribed. Unsubscribe.",
        listOf("INBOX", "CATEGORY_PROMOTIONS"), false),
    EmailCase("customer-catering", "Sara Nabil <sara.nabil@gmail.com>", "Lunch for 40 people next Thursday?",
        "Hi, we're organising a team lunch for about 40 people next Thursday. Do you deliver to the marina offices, and can you send a menu with prices? Thanks, Sara",
        listOf("INBOX", "CATEGORY_PERSONAL"), false),
    EmailCase("supplier-invoice", "\"Nile Packaging Accounts\" <accounts@nilepack.com.eg>", "Invoice INV-2291 for September",
        "Dear customer,\nPlease find attached invoice INV-2291 for 3,200 food containers delivered on 14 September. Amount due: EGP 18,400, payable within 30 days to our bank account.\nRegards,\nAccounts team",
        listOf("INBOX", "CATEGORY_PERSONAL"), false),
    EmailCase("paypal-phish", "\"PayPal\" <service@paypa1-security.com>", "Your account has been limited",
        "We noticed unusual activity on your PayPal account and have limited it. Verify your information within 24 hours or your account will be permanently suspended: https://paypa1-security.com/verify?id=88213",
        listOf("INBOX", "CATEGORY_PERSONAL"), true),
    EmailCase("m365-phish", "\"Microsoft 365 Security\" <alerts@m365-account-review.top>", "Your password expires today",
        "Your Microsoft 365 password expires today. To keep your current password, confirm it here: https://login.microsoftonline.com.m365-account-review.top/keep\nMicrosoft 365 Team",
        listOf("INBOX", "CATEGORY_PERSONAL"), true,
        links = listOf("https://login.microsoftonline.com.m365-account-review.top/keep" to "Keep my password")),
    EmailCase("cib-phish-ar", "\"CIB\" <no-reply@cib-eg-secure.com>", "تنبيه: تم إيقاف بطاقتك مؤقتاً",
        "عميلنا العزيز، تم إيقاف بطاقتك مؤقتاً بسبب نشاط غير معتاد. يرجى تأكيد بياناتك خلال ١٢ ساعة عبر الرابط التالي لتجنب إغلاق الحساب: http://cib-eg-secure.com/ar/login",
        listOf("INBOX", "CATEGORY_PERSONAL"), true),
    EmailCase("dhl-phish", "\"DHL Express\" <notice@dhl-parcel-redelivery.com>", "Your parcel is on hold",
        "Your parcel could not be delivered because a customs fee of EGP 45 is unpaid. Pay now to schedule redelivery: https://dhl-parcel-redelivery.com/pay\nDHL Express",
        listOf("INBOX", "CATEGORY_UPDATES"), true),
    EmailCase("lottery-spam", "\"Lottery Claims\" <claims@intl-winners.xyz>", "You have won USD 1,500,000",
        "Congratulations! Your email address was selected in our international draw. To claim your prize, send your full name, address and a processing fee of USD 250 to our claims office.",
        listOf("SPAM"), true, replyTo = "claims.office2026@gmail.com"),
    EmailCase("customer-complaint-arz", "محمد حسن <m.hassan@yahoo.com>", "الطلب وصل ناقص",
        "السلام عليكم، طلبي رقم ٤٥٢ وصل امبارح ناقص صنفين ومحدش بيرد على التليفون. محتاج حل النهارده لو سمحتم.",
        listOf("INBOX", "CATEGORY_PERSONAL"), false),
    EmailCase("customer-order-es", "Lucía Pérez <lucia.perez@outlook.es>", "Pedido de camisetas personalizadas",
        "Hola, ¿pueden enviarme el catálogo y los precios para un pedido de 200 camisetas personalizadas? Necesitamos la entrega antes del 15 de octubre. Gracias, Lucía",
        listOf("INBOX", "CATEGORY_PERSONAL"), false),
    EmailCase("newsletter-fr", "\"Le Club Déco\" <newsletter@clubdeco.fr>", "Nos nouveautés d'automne",
        "Découvrez notre nouvelle collection d'automne : coussins, plaids et bougies. -20 % avec le code AUTOMNE jusqu'à dimanche. Voir la collection. Se désabonner.",
        listOf("INBOX", "CATEGORY_PROMOTIONS"), false),
    EmailCase("linkedin-social", "LinkedIn <messages-noreply@linkedin.com>", "You appeared in 12 searches this week",
        "See who's looking at your profile. You appeared in 12 searches this week. View all searches. Unsubscribe | Help",
        listOf("INBOX", "CATEGORY_SOCIAL"), false),
    EmailCase("aramex-shipping", "Aramex <no-reply@aramex.com>", "Your shipment 44321907 is out for delivery",
        "Good news! Your shipment 44321907 is out for delivery today between 12:00 and 18:00. Track it at https://www.aramex.com/track/44321907",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("partner-meeting", "Omar Khalil <omar@freshfarms.com.eg>", "Meeting next week about the new supply contract",
        "Hi, can we meet on Tuesday at 11 to go over the new contract terms? Please confirm which time works for you. Best, Omar",
        listOf("INBOX", "CATEGORY_PERSONAL"), false),
    EmailCase("canva-reset", "Canva <no-reply@canva.com>", "Reset your password",
        "Someone requested a password reset for your Canva account. If this was you, reset it here within 1 hour: https://www.canva.com/reset?token=a81f. If it wasn't you, you can ignore this email.",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("tax-reminder-ar", "مصلحة الضرائب المصرية <noreply@eta.gov.eg>", "تذكير: تقديم إقرار ضريبة القيمة المضافة",
        "نذكركم بأن آخر موعد لتقديم إقرار ضريبة القيمة المضافة عن شهر أغسطس هو ١٥ سبتمبر. يرجى التقديم عبر بوابة الإقرارات الإلكترونية لتجنب الغرامات.",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
    EmailCase("stripe-payout", "Stripe <notifications@stripe.com>", "Your payout of \$3,450.00 is on the way",
        "A payout of \$3,450.00 is on the way to your bank account ending in 4412 and should arrive by September 25. View payout details in your Dashboard.",
        listOf("INBOX", "CATEGORY_UPDATES"), false),
)

internal val CASES: Map<String, EmailCase> = EMAIL_CASES.associateBy { it.id }

class PhishingTest {
    private fun assessCase(c: EmailCase, layaP: Double? = null, trusted: List<String> = emptyList()) =
        Phishing.assess(c.sender, c.body, c.replyTo, "", c.links, layaP = layaP, trusted = trusted)

    // ------------------------------------------------------------------ the realistic cases
    @Test
    fun legitimateMailIsNeverFlaggedEvenWhenLayaSaysPhishing() {
        for (case in EMAIL_CASES.filter { !it.phishing }) {
            for (p in listOf(null, 0.5, 0.99, 1.0)) {
                val v = assessCase(case, layaP = p)
                assertFalse(v.flag, "${case.id} $p ${v.reasons}")
                assertFalse("laya_phishing" in v.codes)
            }
        }
    }

    @Test
    fun phishingCasesAreFlaggedOnEvidenceAlone() {
        for (cid in listOf("paypal-phish", "m365-phish", "cib-phish-ar", "dhl-phish")) {
            val v = assessCase(CASES.getValue(cid))
            assertTrue(v.flag && v.score >= Phishing.PHISHING_AT, "$cid $v")
            assertTrue(v.reasons.all { it.text.isNotEmpty() && it.weight > 0 }, cid)
            assertTrue((v.codes intersect Phishing.RISK_CODES).isNotEmpty(), cid)
        }
    }

    @Test
    fun knownSendersFromTheBugReport() {
        for (sender in listOf("Google <no-reply@accounts.google.com>", "GoDaddy <donotreply@godaddy.com>", "\"CIB Business Online\" <CIB.BusinessOnline@cibeg.com>")) {
            val v = Phishing.assess(sender, "Verify your account now or it will be suspended", layaP = 1.0)
            assertTrue(!v.flag && v.known && v.codes == setOf("known_sender"), sender)
            assertEquals(0, v.reasons[0].weight)
            assertTrue(v.reasons[0].params.getValue("domain").isNotEmpty())
        }
        // unknown is not suspicious
        for (sender in listOf("Cloudflare <em@em1.cloudflare.com>", "Jomashop <jomashop@e.jomashop.com>")) {
            val v = Phishing.assess(sender, "Sale ends tonight https://click.e.jomashop.com/x", layaP = 1.0)
            assertTrue(!v.flag && v.score == 0 && v.reasons.isEmpty(), sender)
        }
    }

    @Test
    fun displayNameBrandWithLookalikeDomain() {
        var v = Phishing.assess("\"PayPal\" <service@paypa1-verify.xyz>", "Your account is limited")
        assertTrue(v.flag)
        assertTrue(v.codes.containsAll(setOf("sender_lookalike_brand", "display_brand_mismatch", "sender_suspicious_tld")))
        val r = v.reasons.first { it.code == "sender_lookalike_brand" }
        assertEquals(mapOf("brand" to "PayPal", "domain" to "paypa1-verify.xyz"), r.params)
        assertTrue("PayPal" in r.text)
        // a brand claimed from a free personal address
        v = Phishing.assess("\"PayPal Service\" <paypal.team2026@gmail.com>", "x")
        assertTrue(v.flag && v.codes == setOf("display_brand_freemail"))
        // people and companies that merely share a word with a brand are not claims
        for (sender in listOf("\"Chase Miller\" <chase.miller@gmail.com>", "\"Apple Valley Farms\" <news@applevalley.com>", "\"Zoom Photography Studio\" <hi@zoomphoto.example>")) {
            v = Phishing.assess(sender, "hello", layaP = 1.0)
            assertTrue(!v.flag && (v.codes intersect setOf("display_brand_mismatch", "display_brand_freemail")).isEmpty(), sender)
        }
        // a claim with an unrelated domain: evidence, flagged only together with more
        v = Phishing.assess("\"Microsoft Account Team\" <alerts@secure-mailer.example>", "x")
        assertTrue(!v.flag && v.codes == setOf("display_brand_mismatch"))
        v = Phishing.assess("\"Microsoft Account Team\" <alerts@secure-mailer.example>", "x", layaP = 0.95)
        assertTrue(v.flag && v.codes == setOf("display_brand_mismatch", "laya_phishing"))
        // the display name shows one address, the mail comes from another
        v = Phishing.assess("\"billing@stripe.com\" <x@invoices-stripe-billing.top>", "x")
        assertTrue("display_address_mismatch" in v.codes && v.flag)
    }

    @Test
    fun replyToMismatch() {
        var v = Phishing.assess("Acme Supplies <billing@acme-supplies.example>", "Invoice", replyTo = "acme.billing@gmail.com")
        assertTrue(v.codes == setOf("reply_to_freemail") && !v.flag)
        assertEquals(mapOf("target" to "gmail.com", "domain" to "acme-supplies.example"), v.reasons[0].params)
        v = Phishing.assess("Acme <billing@acme.example>", "x", replyTo = "Billing <pay@paypal-refunds-center.com>")
        assertTrue("reply_to_impostor" in v.codes)
        v = Phishing.assess("Acme <billing@acme.example>", "x", replyTo = "help@acme-support.zendesk.example")
        assertTrue(v.codes == setOf("reply_to_mismatch") && v.score == 15)
        for (free in listOf("x@outlook.es", "x@hotmail.fr", "x@yahoo.co.uk", "x@gmx.de")) {
            assertTrue(Phishing.isFreemail(Phishing.reg(free.substringAfter('@')).first), free)
        }
        assertFalse(Phishing.isFreemail("outlook-secure.com"))
        assertFalse(Phishing.isFreemail("gmail.example.com"))
        // same domain (or a subdomain of it) is fine; a known brand's domain is fine
        assertTrue(Phishing.assess("Acme <a@acme.example>", "x", replyTo = "b@mail.acme.example").reasons.isEmpty())
        assertTrue(Phishing.assess("Acme <a@acme.example>", "x", replyTo = "acme@linkedin.com").reasons.isEmpty())
        // the 419 scam of the realistic set: a freemail reply-to and a throw-away TLD, plus Laya
        val lottery = CASES.getValue("lottery-spam")
        assertFalse(assessCase(lottery).flag)
        assertTrue(assessCase(lottery, layaP = 0.9).flag)
    }

    @Test
    fun links() {
        var v = Phishing.assess("Shop <news@shop.example>", "Log in at https://arnazon-login.com/account now")
        assertTrue("link_lookalike_brand" in v.codes || "link_brand_in_domain_bait" in v.codes)
        // a link whose visible text shows a well-known address but goes elsewhere
        v = Phishing.assess("Acme <billing@acme.example>", "", links = listOf("http://paypal.com.secure-check.top/login" to "https://www.paypal.com/signin"))
        assertTrue(v.codes.containsAll(setOf("link_text_mismatch", "link_brand_domain_in_subdomain")) && v.flag)
        // "Sign in to PayPal" pointing at an unrelated site
        v = Phishing.assess("Acme <billing@acme.example>", "", links = listOf("https://acme-billing.example/x" to "Sign in to PayPal"))
        assertEquals(setOf("link_brand_text"), v.codes)
        // bare IP, data:, shortener
        v = Phishing.assess("Acme <a@acme.example>", "http://185.23.4.9/verify and https://bit.ly/3xyz", links = listOf("data:text/html;base64,PHNjcmlwdD4=" to "Open invoice"))
        assertTrue(v.codes.containsAll(setOf("link_ip", "link_data", "link_shortener")))
        // newsletters: the sender's own links and click trackers are normal; brand links on the brand's domain too
        v = Phishing.assess(
            "Jomashop <jomashop@e.jomashop.com>", "",
            links = listOf(
                "https://click.e.jomashop.com/?qs=1" to "jomashop.com", "https://u123.ct.sendgrid.net/ls/click?x" to "paypal.com",
                "https://www.facebook.com/jomashop" to "Follow us on Facebook", "mailto:help@jomashop.com" to "help",
            ),
        )
        assertEquals(emptyList(), v.reasons)
        // the sender's own (look-alike) domain with a brand address in front: the link adds that evidence
        assertTrue("link_brand_domain_in_subdomain" in assessCase(CASES.getValue("m365-phish")).codes)
    }

    @Test
    fun authenticationResults() {
        val ar = "mx.google.com; dkim=fail header.i=@google.com; spf=fail (google.com: domain of x@evil.example does not " +
            "designate 1.2.3.4) smtp.mailfrom=evil.example; dmarc=fail (p=REJECT sp=REJECT dis=REJECT) header.from=google.com"
        assertEquals(mapOf("dkim" to "fail", "spf" to "fail", "dmarc" to "fail"), Phishing.authResults(ar))
        // a spoofed well-known sender: never trusted when the provider says DMARC failed
        var v = Phishing.assess("Google <no-reply@accounts.google.com>", "Security alert", authHeader = ar)
        assertTrue(v.flag && v.codes == setOf("spoofed_known_sender") && !v.known)
        val ok = "mx.google.com; dkim=pass header.i=@accounts.google.com; spf=pass; dmarc=pass (p=REJECT) header.from=accounts.google.com"
        assertTrue(Phishing.assess("Google <no-reply@accounts.google.com>", "x", authHeader = ok).known)
        // unknown sender, DMARC fail: evidence (45), flagged together with a brand claim
        v = Phishing.assess("Acme <a@acme.example>", "x", authHeader = "mx; dmarc=fail header.from=acme.example")
        assertTrue(v.codes == setOf("auth_dmarc_fail") && !v.flag)
        assertTrue(Phishing.assess("\"DHL\" <a@acme.example>", "x", authHeader = "mx; dmarc=fail header.from=acme.example").flag)
        // SPF and DKIM both failed without DMARC: 25; Microsoft's compauth counts as DMARC
        v = Phishing.assess("Acme <a@acme.example>", "x", authHeader = "spf=fail smtp.mailfrom=acme.example; dkim=none")
        assertEquals(setOf("auth_spf_dkim_fail"), v.codes)
        val ms = "spf=fail (sender IP is 1.2.3.4) smtp.mailfrom=acme.example; dkim=none; dmarc=none action=none " +
            "header.from=acme.example; compauth=fail reason=001"
        assertEquals("fail", Phishing.authResults(ms)["dmarc"])
        assertEquals("pass", Phishing.authResults("spf=pass; dkim=pass; dmarc=pass; compauth=pass reason=100")["dmarc"])
        assertEquals(mapOf("dkim" to "pass"), Phishing.authResults("dkim=fail; dkim=pass"))
    }

    @Test
    fun trustedSenders() {
        assertEquals(
            listOf("billing@supplier.com", "example.org", "acme.com", "xn--mgbh0fb.xn--wgbh1c"),
            Phishing.normalizeTrusted(listOf(" Billing@Supplier.com ", "@example.org", "*.acme.com", "example.org", "مثال.مصر")),
        )
        for (bad in listOf(listOf("not a domain"), listOf("a@b@c"), listOf("com"), listOf("x@"))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { Phishing.normalizeTrusted(bad) }
        }
        val spoof = "\"PayPal\" <service@paypa1-verify.xyz>"
        for (trusted in listOf(listOf("paypa1-verify.xyz"), listOf("service@paypa1-verify.xyz"))) {
            val v = Phishing.assess(spoof, "x", layaP = 1.0, trusted = trusted)
            assertTrue(!v.flag && v.trusted && v.codes == setOf("trusted_sender"))
        }
        assertTrue(Phishing.assess(spoof, "x", trusted = listOf("other@paypa1-verify.xyz")).flag)
        assertTrue(Phishing.assess("\"A\" <a@mail.acme.com>", "x", trusted = listOf("acme.com")).trusted)
        // a forged trusted address (DMARC failed) is not the trusted sender
        val v = Phishing.assess("Acme <a@acme.com>", "x", authHeader = "dmarc=fail", trusted = listOf("acme.com"))
        assertTrue(v.flag && v.codes == setOf("spoofed_known_sender"))
    }

    @Test
    fun everyReasonCodeHasText() {
        val placeholder = Regex("\\{(\\w+)\\}")
        for (code in Phishing.REASON_CODES) {
            assertTrue(Phishing.reasonText(code, emptyMap()).isNotEmpty(), code)
            assertFalse("{" in Phishing.reasonText(code, Phishing.REASON_PARAMS.associateWith { "x" }), code)
            val template = Phishing.WEIGHTS[code]?.second ?: Phishing.INFO_TEXT.getValue(code)
            assertTrue(placeholder.findAll(template).all { it.groupValues[1] in Phishing.REASON_PARAMS }, code)
        }
    }

    @Test
    fun providerCategoryMappingPerProvider() {
        fun cat(provider: String, labels: List<String> = emptyList(), folder: String = "", inference: String = "") =
            MailClassify.providerCategory(provider, labels, folder, inference)?.key
        assertEquals("promotions", cat("gmail", listOf("INBOX", "CATEGORY_PROMOTIONS")))
        assertEquals("social", cat("gmail", listOf("CATEGORY_SOCIAL")))
        assertEquals("forums", cat("gmail", listOf("CATEGORY_FORUMS")))
        assertEquals("updates", cat("gmail", listOf("CATEGORY_UPDATES")))
        assertEquals("primary", cat("gmail", listOf("CATEGORY_PERSONAL")))
        assertEquals("spam", cat("gmail", listOf("SPAM", "CATEGORY_PROMOTIONS")))          // spam wins
        assertEquals(null, cat("gmail", listOf("INBOX")))
        assertEquals(null, cat("", listOf("CATEGORY_PROMOTIONS")))                          // unknown provider: nothing
        assertEquals("other", cat("outlook", inference = "other"))
        assertEquals("focused", cat("outlook", inference = "focused"))
        assertEquals("spam", cat("outlook", folder = "junk", inference = "other"))
        assertEquals("spam", cat("imap", listOf("\$Junk")))
        assertEquals("spam", cat("imap", listOf("Junk")))
        assertEquals(null, cat("imap", listOf("\$Junk", "\$NotJunk")))
        assertEquals(setOf("promotions", "social", "forums", "other"), MailClassify.BULK_CATEGORIES)
        assertEquals(ProviderCategory("social", "gmail", "Gmail"), MailClassify.providerCategory("gmail", listOf("CATEGORY_SOCIAL")))
    }
}
