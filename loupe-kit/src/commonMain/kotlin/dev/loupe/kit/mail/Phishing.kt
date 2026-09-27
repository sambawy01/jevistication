package dev.loupe.kit.mail

import dev.loupe.engine.BoundedRegex
import dev.loupe.engine.PortableText
import dev.loupe.engine.PublicSuffix
import dev.loupe.engine.Rx
import dev.loupe.kit.site.Brand
import dev.loupe.kit.site.Brands
import dev.loupe.engine.Contact
import dev.loupe.engine.Impersonation
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.OnlineContext
import dev.loupe.kit.site.OnlineSignals
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.kit.site.Punycode
import dev.loupe.kit.site.SiteConfig
import dev.loupe.kit.site.SiteContext
import dev.loupe.kit.site.SiteSignals
import dev.loupe.kit.site.fillTemplate
import dev.loupe.sources.common.MimeParser

/*
 * Evidence-based phishing checks for one email: no network, no model.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/mail/phishing.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). Thresholds
 * (PHISHING_AT 50, REVIEW_AT 25, RISK_MIN 25, LAYA_* points), the WEIGHTS table (codes, weights,
 * plain-language reasons), INFO_TEXT, MAIL_BRANDS, EXTRA_SENDER_DOMAINS, FREEMAIL, LINK_TRACKERS,
 * SERVICE_WORDS, the regular expressions and the order of every check are copied verbatim; only the
 * language changed. The host checks are site protection's ([SiteSignals], child 12), as in Station.
 * Mechanical differences: addresses are parsed with the phone's own `MimeParser.mailboxes` (Station:
 * `email.utils`), registrable domains come from the engine's pinned PSL, and IDNA uses NFKC +
 * punycode (Station: Python's IDNA 2003 codec).
 *
 * Formula v1.3 (docs/PHISHING-FORMULA.md changelog; Station commit 0ff886d,
 * `docs/formula/phishing-formula-v1.3.md`): the `self_vouching` signal, 15 points, from
 * [PhishingOwnWords] — Station's `VOUCHING_RE`, `_SENTENCE_SPLIT`, `self_vouching()` and
 * `_is_contact_address()` in `mail/phishing.py`, and `own_text()` of `baselines.py`, verbatim.
 *
 * Real security alerts, receipts and bank notices read exactly like phishing, so the text alone
 * never decides. [assess] looks at facts a scam cannot hide: sender (the From domain imitates a
 * brand; the display name claims a brand the address is not; the display name shows another
 * address), reply-to (another domain, a personal address, a look-alike), auth (the receiving
 * server's Authentication-Results), links (look-alike domains, bare IPs, data: URLs, a link whose
 * text shows a well-known address but goes elsewhere, shorteners).
 *
 * Guard rails: a sender on a well-known brand's own domain or on the trusted list is never flagged
 * unless DMARC failed; Laya's reading (a probability, when a caller has one) adds at most 20 points
 * and only next to deterministic evidence; `flag` needs score >= PHISHING_AT **and** one
 * deterministic risk signal (weight >= 25); unknown is not suspicious.
 */

/** One reason in a phishing verdict. */
data class PhishReason(val code: String, val text: String, val weight: Int, val params: Map<String, String>, val source: String)

/** Station's `assess()` result: the email profile of the shared formula (docs/PHISHING-FORMULA.md). */
data class PhishVerdict(
    val flag: Boolean,
    val score: Int,
    val reasons: List<PhishReason>,
    val known: Boolean,
    val trusted: Boolean,
    val domain: String,
    /** Gates that capped the score (`online_age_only_cap`). */
    val gates: List<String> = emptyList(),
    /** The shared formula's version this verdict was computed with (Station's `formula`, v1.3). */
    val formula: String = Phishing.FORMULA_VERSION,
) {
    val codes: Set<String> get() = reasons.map { it.code }.toSet()

    /** The formula's level: danger when flagged, caution from [Phishing.REVIEW_AT], else safe. */
    val level: String get() = if (flag) "danger" else if (score >= Phishing.REVIEW_AT) "caution" else "safe"
}

object Phishing {
    const val PHISHING_AT = 50
    const val REVIEW_AT = 25
    const val RISK_MIN = 25
    const val LAYA_FULL_P = 0.8
    const val LAYA_STRONG = 20
    const val LAYA_WEAK = 10
    const val MAX_LINKS = 60
    /** Distinct hosts judged per message, and links judged in all (fix loop 11). */
    const val MAX_LINK_HOSTS = 300
    const val MAX_JUDGED_LINKS = 1000
    const val MAX_TRUSTED = 500

    /** The shared Loupe phishing formula's version: one number for the email and page profiles (Station's two FORMULA_VERSIONs). */
    const val FORMULA_VERSION = SiteContext.FORMULA_VERSION

    /**
     * Formula v1.3: a sentence in the sender's own words vouches for the message ("verified by our
     * security team", "Verified sender ✓", "تم التحقق", "متأكد منها"), from a sender that is not a
     * known brand, not a trusted sender and not one of your contacts. Below [RISK_MIN] and
     * [REVIEW_AT]: not a risk code, so alone it never flags a message and never makes it "caution".
     */
    const val W_SELF_VOUCHING = 15

    /** code -> (weight, plain-language reason). `{placeholders}` come from the reason's params. */
    val WEIGHTS: Map<String, Pair<Int, String>> = linkedMapOf(
        // sender domain (site protection's host checks, applied to the From domain)
        "sender_homograph_brand" to (60 to "The sender's domain imitates {brand} with look-alike letters ({domain})."),
        "sender_mixed_script" to (35 to "The sender's domain mixes letters from different alphabets ({domain})."),
        "sender_disguised_domain" to (45 to "The sender's domain is written with stand-in letters (such as full-width or mathematical letters) for {domain}; real senders never write their address this way."),
        "sender_deviation_domain" to (10 to "The sender's domain contains ß, ς or an invisible joiner, which older software reads as a different domain ({domain})."),
        "sender_deviation_known" to (45 to "The sender's domain {domain} reads as a known domain in older software, but it is a different domain."),
        "sender_unicode_drift" to (30 to "The sender's domain uses characters that older and newer software read differently ({domain})."),
        "sender_lookalike_brand" to (45 to "The sender's domain {domain} looks like {brand} but is not {brand}'s."),
        "sender_brand_domain_in_subdomain" to (45 to "{brand}'s address is placed in front of an unrelated sender domain ({domain})."),
        "sender_brand_in_subdomain" to (30 to "The name {brand} is placed in front of an unrelated sender domain ({domain})."),
        "sender_brand_in_domain_bait" to (40 to "The sender's domain {domain} glues {brand} to words like \"secure\" or \"verify\"; {brand} does not use it."),
        "sender_brand_other_tld" to (20 to "The sender uses the name {brand} on a domain {brand} does not use ({domain})."),
        "sender_brand_in_domain" to (10 to "The sender's domain {domain} contains the name {brand}, but it is not {brand}'s."),
        "sender_suspicious_tld" to (8 to "The sender's domain ends in .{tld}, an ending often used by throw-away scam sites."),
        "sender_ip" to (25 to "The sender's address uses a bare IP number instead of a domain."),
        // display name
        "display_brand_freemail" to (50 to "The sender's name says {brand}, but the message comes from a free personal address ({domain})."),
        "display_brand_mismatch" to (40 to "The sender's name says {brand}, but the message comes from {domain}, which is not {brand}'s."),
        "display_address_mismatch" to (40 to "The sender's name shows the address {shown}, but the message really comes from {domain}."),
        // reply-to
        "reply_to_impostor" to (40 to "Replies would go to {target}, a domain that imitates {brand}."),
        "reply_to_freemail" to (25 to "Replies would go to a free personal address ({target}), not to the sender's domain ({domain})."),
        "reply_to_mismatch" to (15 to "Replies would go to another domain ({target}) than the sender's ({domain})."),
        // the receiving server's authentication results
        "spoofed_known_sender" to (60 to "The message claims to come from {domain}, but your mail provider says {domain} did not send it (DMARC failed)."),
        "auth_dmarc_fail" to (45 to "Your mail provider could not confirm that this really comes from {domain} (DMARC failed)."),
        "auth_spf_dkim_fail" to (25 to "Your mail provider's sender checks failed (SPF and DKIM)."),
        // links
        "link_homograph_brand" to (60 to "A link goes to {domain}, which imitates {brand} with look-alike letters."),
        "link_lookalike_brand" to (45 to "A link goes to {domain}, which looks like {brand} but is not {brand}'s website."),
        "link_brand_domain_in_subdomain" to (45 to "A link puts {brand}'s address in front of an unrelated website ({domain})."),
        "link_brand_in_subdomain" to (30 to "A link puts the name {brand} in front of an unrelated website ({domain})."),
        "link_brand_in_domain_bait" to (35 to "A link goes to {domain}, which glues {brand} to words like \"login\" or \"secure\"."),
        "link_mixed_script" to (35 to "A link's website name mixes letters from different alphabets ({domain})."),
        "link_disguised" to (45 to "A link's website name is written with stand-in letters for {domain}; real links are not written this way."),
        "link_deviation" to (10 to "A link's website name contains ß, ς or an invisible joiner, which older software reads as a different name ({domain})."),
        "link_deviation_known" to (45 to "A link goes to {domain}, which older software reads as a known site, but browsers open a different website."),
        "link_unicode_drift" to (30 to "A link's website name uses characters that older and newer software read differently ({domain})."),
        "link_text_mismatch" to (40 to "A link shows {shown} but really goes to {domain}."),
        "link_brand_text" to (30 to "A link asks you to sign in or verify with {brand}, but goes to {domain}."),
        "link_stitched" to (30 to "The text runs the address of {brand} straight into another one ({domain}), so the link shown is not the one that opens."),
        "link_unreadable" to (30 to "A link's address does not read as a normal web address; where it leads cannot be told for sure."),
        "link_data" to (40 to "A link opens a data: address, which has no real website behind it."),
        "link_userinfo" to (30 to "A link hides its real website behind text and an @ sign ({domain})."),
        "link_ip" to (25 to "A link goes to a bare IP number ({host}) instead of a website name."),
        "link_suspicious_tld" to (8 to "A link goes to a domain ending in .{tld}, often used by throw-away scam sites."),
        "link_shortener" to (5 to "A link uses a link shortener, so its real destination is hidden."),
        // your contacts (rule 6: contact impersonation feeds the same score as the brand checks)
        "contact_homograph_domain" to (60 to "The sender's name is your contact {name}, and {domain} imitates their domain {target} with look-alike letters."),
        "contact_lookalike_domain" to (45 to "The sender's name is your contact {name}, but {domain} is a near miss of {target}, the domain they write from."),
        "contact_name_other_address" to (30 to "The sender's name is your contact {name}, but {address} is not an address they write from."),
        // formula v1.3: a sentence in the message vouching for itself, in mail from an unknown sender (weak: never flags alone)
        "self_vouching" to (W_SELF_VOUCHING to "The message vouches for itself (\"{phrase}\"): a real sender rarely needs to say its own mail is verified, approved or safe."),
        // Laya (weight shown is the maximum; 0.5-0.8 counts half)
        "laya_phishing" to (LAYA_STRONG to "The decision model's reading of the text: it looks like a phishing or scam attempt."),
    ).also { m -> m.putAll(OnlineSignals.MAIL_WEIGHTS) }
    val INFO_TEXT: Map<String, String> = linkedMapOf(
        "known_sender" to "The sender's domain {domain} belongs to {brand}.",
        "trusted_sender" to "You marked {domain} as a trusted sender.",
    )
    val REASON_CODES: List<String> = WEIGHTS.keys.toList() + INFO_TEXT.keys
    val REASON_PARAMS = listOf("brand", "domain", "shown", "target", "host", "tld", "name", "address", "phrase")
    val RISK_CODES: Set<String> = WEIGHTS.filter { (c, w) -> w.first >= RISK_MIN && c != "laya_phishing" }.keys

    private val SENDER_CODES = mapOf(
        "homograph_brand" to "sender_homograph_brand", "mixed_script" to "sender_mixed_script",
        "lookalike_brand" to "sender_lookalike_brand", "brand_domain_in_subdomain" to "sender_brand_domain_in_subdomain",
        "brand_in_subdomain" to "sender_brand_in_subdomain", "brand_in_domain_bait" to "sender_brand_in_domain_bait",
        "brand_other_tld" to "sender_brand_other_tld", "brand_in_domain" to "sender_brand_in_domain",
        "suspicious_tld" to "sender_suspicious_tld", "ip_host" to "sender_ip", "unicode_drift_host" to "sender_unicode_drift",
        "disguised_host" to "sender_disguised_domain", "deviation_host" to "sender_deviation_domain",
        "deviation_known_host" to "sender_deviation_known",
    )
    private val LINK_CODES = mapOf(
        "homograph_brand" to "link_homograph_brand", "lookalike_brand" to "link_lookalike_brand",
        "brand_domain_in_subdomain" to "link_brand_domain_in_subdomain", "brand_in_subdomain" to "link_brand_in_subdomain",
        "brand_in_domain_bait" to "link_brand_in_domain_bait", "mixed_script" to "link_mixed_script",
        "ip_host" to "link_ip", "suspicious_tld" to "link_suspicious_tld", "data_url" to "link_data",
        "userinfo_in_url" to "link_userinfo", "url_shortener" to "link_shortener", "unicode_drift_host" to "link_unicode_drift",
        "disguised_host" to "link_disguised", "deviation_host" to "link_deviation",
        "deviation_known_host" to "link_deviation_known",
    )
    private val SUBDOMAIN_CODES = setOf("brand_domain_in_subdomain", "brand_in_subdomain")
    private val IMPOSTOR = setOf("homograph_brand", "lookalike_brand", "brand_domain_in_subdomain", "brand_in_subdomain", "brand_in_domain_bait", "mixed_script")

    /** Brands phishers imitate in Station's region, and extra domains well-known brands send mail from. */
    val MAIL_BRANDS: List<Brand> = listOf(
        Brand("CIB", listOf("cibeg.com"), listOf("cib", "cibeg")),
        Brand("National Bank of Egypt", listOf("nbe.com.eg"), listOf("nbe")),
        Brand("Banque Misr", listOf("banquemisr.com"), listOf("banquemisr")),
        Brand("QNB", listOf("qnb.com", "qnbalahli.com"), listOf("qnb", "qnbalahli")),
        Brand("Emirates NBD", listOf("emiratesnbd.com"), listOf("emiratesnbd")),
        Brand("Al Rajhi Bank", listOf("alrajhibank.com.sa"), listOf("alrajhi", "alrajhibank")),
        Brand("Fawry", listOf("fawry.com"), listOf("fawry")),
        Brand("InstaPay", listOf("instapay.eg", "ipn.eg"), listOf("instapay")),
        Brand("GoDaddy", listOf("godaddy.com", "secureserver.net"), listOf("godaddy")),
    )
    val EXTRA_SENDER_DOMAINS: Map<String, List<String>> = mapOf(
        "Facebook" to listOf("facebookmail.com"),
        "Microsoft" to listOf("sharepointonline.com", "microsoftstoreemail.com"),
        "Adobe" to listOf("adobesign.com", "echosign.com"),
        "Google" to listOf("googlemail.com"),
    )

    private fun words(s: String): Set<String> = PortableText.splitSpaces(s).toSet()

    /** Free personal mailboxes: anyone can have an address there, so "from gmail.com" is not "from Google". */
    val FREEMAIL: Set<String> = words(
        """
        gmail.com googlemail.com outlook.com hotmail.com hotmail.co.uk hotmail.fr live.com msn.com yahoo.com yahoo.co.uk
        yahoo.fr ymail.com rocketmail.com icloud.com me.com mac.com aol.com proton.me protonmail.com pm.me gmx.com gmx.de
        gmx.net web.de mail.com yandex.com yandex.ru mail.ru zoho.com zohomail.com tutanota.com tuta.io fastmail.com
        hey.com qq.com 163.com 126.com
        """,
    )
    val FREEMAIL_LABELS: Set<String> = words("gmail googlemail outlook hotmail live msn yahoo ymail aol gmx web mail icloud yandex zoho proton protonmail")

    /** Click-tracking and mailing services: a newsletter's links go through these. */
    val LINK_TRACKERS: Set<String> = words(
        """
        sendgrid.net list-manage.com mailchimp.com mailchi.mp mandrillapp.com mcsv.net rs6.net constantcontact.com
        hubspotlinks.com hubspotemail.net hs-sites.com hubspot.com mailgun.org mailgun.net sparkpostmail.com
        exacttarget.com sfmc-content.com mktomail.com marketo.com klaviyo.com klclick.com klclick1.com klclick2.com
        awstrack.me amazonses.com createsend.com cmail19.com cmail20.com sendibt3.com sendinblue.com brevo.com
        mlsend.com mailerlite.com substack.com beehiiv.com convertkit-mail.com convertkit-mail2.com ck.page
        intercom-mail.com customeriomail.com postmarkapp.com pstmrk.it emltrk.com pardot.com iterable.com
        braze.com sailthru.com responsys.net mjt.lu mailjet.com e2ma.net myemma.com aweber.com getresponse.com
        safelinks.protection.outlook.com urldefense.com mimecast.com
        """,
    )

    /** Words that may stand next to a brand in a display name that *claims* to be that brand. */
    val SERVICE_WORDS: Set<String> = words(
        """
        support service services team security secure account accounts billing customer customers care help center centre
        alert alerts notification notifications notice no reply noreply donotreply do not official verify verification
        update updates department dept online bank banking inc llc ltd co com corp payments payment pay store id info
        mail email messages message system systems admin administrator desk services safety fraud protection prevention
        refund refunds shipping delivery express the from via of and global international intl egypt eg uk us usa ksa uae
        mena europe arabia misr
        بنك خدمة خدمات العملاء عملاء الدعم دعم فريق الأمن الأمان أمن حساب الحساب تنبيه تنبيهات مصر الفني
        """,
    )

    // Spelled out for every regex engine (PortableText, Rx): no \s \b \p{..} or IGNORE_CASE.
    // BAIT_WORDS and AUTH_RE run on PortableText.fold(text); URL_RE and DOMAINISH_RE spell the ASCII
    // case out so the captured text keeps its case; ADDR_IN_TEXT_RE runs on PortableText.shadow(text),
    // where Rx.LN stands for \p{L}\p{Nd}\p{Nl}\p{No}.
    private val BAIT_WORDS = Regex(
        "sign[${Rx.SPACE}-]?in|log[${Rx.SPACE}-]?in|log[${Rx.SPACE}-]?on|verify|verification|confirm|unlock|update|" +
            "password|account|secure|تسجيل|الدخول|تحقق|تأكيد|تحديث|كلمة المرور|حسابك",
    )
    private val URL_RE = Regex("""(?:[hH][tT][tT][pP][sS]?://|[wW][wW][wW]\.)[^${Rx.SPACE}<>"'()\[\]{}]{3,2000}""")
    private val DOMAINISH_RE = Regex("""^(?:[hH][tT][tT][pP][sS]?://)?(?:[wW][wW][wW]\.)?((?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,24})(?:[/:?#]${Rx.NSP}*)?$""")
    private val ADDR_IN_TEXT_RE = Regex("""[${Rx.LN}_.+-]+@(?:[${Rx.LN}_-]+\.)+[a-zA-Z]{2,24}""")
    private val AUTH_RE = BoundedRegex("""(spf|dkim|dmarc|compauth)${Rx.SP}*=${Rx.SP}*([a-z]+)""")

    fun isFreemail(reg: String?): Boolean {
        if (reg.isNullOrEmpty()) return false
        if (reg in FREEMAIL) return true
        val label = reg.substringBefore('.')
        val suffix = if ('.' in reg) reg.substringAfter('.') else ""
        val last = suffix.substringAfterLast('.')
        return label in FREEMAIL_LABELS && last.length == 2 && last.all { PortableText.isLetter(it.code) } && suffix.count { it == '.' } <= 1
    }

    // ------------------------------------------------------------------------ config
    fun mailBrands(): List<Brand> =
        Brands.BRANDS.map { b -> b.copy(domains = b.domains + (EXTRA_SENDER_DOMAINS[b.name] ?: emptyList())) } + MAIL_BRANDS

    val DEFAULT_CONFIG: SiteConfig by lazy { SiteConfig(mailBrands(), emptyList(), Brands.SUSPICIOUS_TLDS, Brands.SHORTENERS) }

    // ------------------------------------------------------------------------ small parsers
    /** Lowercase ASCII (IDNA) domain of an address, "" when there is none. */
    internal fun domainOf(address: String): String {
        if ('@' !in address) return ""
        val dom = address.substringAfterLast('@').trim().trim('.', '>', '\u3002', '\uFF0E', '\uFF61').lowercase()
        if (dom.startsWith("[") && dom.endsWith("]")) return dom.substring(1, dom.length - 1)
        return Hosts.toAsciiDomain(dom) ?: dom
    }

    internal fun reg(domain: String): Pair<String?, String?> =
        if (domain.isEmpty()) null to null else Hosts.registrableDomain(domain) to Hosts.publicSuffix(domain)

    /**
     * {"spf", "dkim", "dmarc"} from one Authentication-Results header. For DKIM any pass wins;
     * otherwise the first result per method. Microsoft's compauth stands in for an absent DMARC.
     */
    fun authResults(header: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in AUTH_RE.findAll(PortableText.fold(header ?: ""))) {
            val method = m.groupValues[1].lowercase()
            val result = m.groupValues[2].lowercase()
            if (method == "compauth") {
                out.getOrPut("compauth") { result }
                continue
            }
            if (method == "dkim" && result == "pass") out["dkim"] = "pass" else out.getOrPut(method) { result }
        }
        if (out["dmarc"] in listOf(null, "none", "bestguesspass") && out["compauth"] in listOf("fail", "softfail")) out["dmarc"] = "fail"
        out.remove("compauth")
        return out
    }

    private val TRUSTED_DOMAIN = Regex("""(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9-]{2,63}""")
    private val TRUSTED_LOCAL = Regex("""[^${Rx.SPACE}@<>"(),;:]{1,64}""")

    /**
     * Trusted senders: "someone@example.com" (that address) or "example.com" (that domain and its
     * subdomains). Lowercase, deduplicated, at most MAX_TRUSTED; anything else throws.
     */
    fun normalizeTrusted(entries: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (raw in entries) {
            var e = raw.trim().lowercase().trimStart('@')
            if (e.startsWith("*.")) e = e.substring(2)
            if (e.isEmpty()) continue
            val local = if ('@' in e) e.substringBeforeLast('@') else ""
            val dom = (if ('@' in e) e.substringAfterLast('@') else e).trim('.')
            val ascii = Hosts.toAsciiDomain(dom) ?: throw IllegalArgumentException("'${raw.take(80)}' is not an email address or domain")
            if (!TRUSTED_DOMAIN.matches(ascii) || (local.isNotEmpty() && !TRUSTED_LOCAL.matches(local))) {
                throw IllegalArgumentException("'${raw.take(80)}' is not an email address or domain")
            }
            val entry = if (local.isNotEmpty()) "$local@$ascii" else ascii
            if (entry !in out) out += entry
        }
        require(out.size <= MAX_TRUSTED) { "at most $MAX_TRUSTED trusted senders" }
        return out
    }

    private fun isTrusted(address: String, domain: String, trusted: List<String>): String? {
        val addr = address.lowercase()
        for (t in trusted) {
            if ('@' in t) {
                if (addr == t) return t
            } else if (domain == t || domain.endsWith(".$t")) {
                return t
            }
        }
        return null
    }

    /** The brand a display name claims to be: the brand's name or token plus only service words. */
    private fun brandClaim(display: String, config: SiteConfig): Brand? {
        val text = display.trim()
        if (text.isEmpty()) return null
        val words = PortableText.letterNumberRuns(text).map { PortableText.lowercase(it) }
        for ((name, brand) in config.namePatterns) {
            if (!SiteSignals.containsWord(text, name)) continue
            val own = (listOf(brand.name) + brand.tokens).flatMap { n -> PortableText.letterNumberRuns(n).map { PortableText.lowercase(it) } }.toSet()
            val rest = words.filter { it !in own && it !in SERVICE_WORDS && !it.all { c -> PortableText.isDecimalDigit(c.code) } }
            if (rest.isEmpty()) return brand
        }
        return null
    }

    /**
     * A mail domain as the literal string it is: each non-ASCII label lower-cased and Punycode-encoded
     * with no IDNA mapping (nothing removed, nothing folded). A browser would map `ｐａｙｐａｌ.com` to
     * paypal.com, but an address is not resolved that way, and read literally it is a look-alike of
     * the brand (the skeleton check sees through the stand-ins). Read beside the mapped form, for
     * senders and reply addresses written with anything but ASCII.
     */
    private fun literal(domain: String): String =
        domain.split('.', '\u3002', '\uFF0E', '\uFF61').joinToString(".") { l ->
            if (l.all { it.code < 0x80 }) l.lowercase() else Punycode.encode(PortableText.lowercase(l))?.let { "xn--$it" } ?: l
        }

    /** The brand and look-alike codes of the [literal] reading (how it is written is read on the mapped form). */
    private fun literalCodes(domain: String, config: SiteConfig): List<Pair<String, Map<String, String>>> =
        hostCodes(literal(domain), config).filter { it.first !in WRITTEN_CODES }

    /** Full stops of other scripts: a dot to a browser, a stand-in in a written mail address. */
    private const val STAND_IN_DOTS = "\u3002\uFF0E\uFF61"

    private val WRITTEN_CODES = setOf("disguised_host", "unicode_drift_host", "deviation_host", "deviation_known_host")

    private fun hostCodes(domain: String, config: SiteConfig): List<Pair<String, Map<String, String>>> {
        val u = ParsedUrl.parse("http://$domain/") ?: return emptyList()
        return SiteSignals.hostSignals(u, config).map { it.code to it.params }
    }

    /** Station's `parseaddr`: (display name, address) of a From header. */
    internal fun parseAddress(header: String): Pair<String, String> {
        val mb = MimeParser.mailboxes(header).firstOrNull()
        if (mb != null) return (mb.name ?: "") to mb.address
        return "" to (if ('@' in header) header.trim() else "")
    }

    // ------------------------------------------------------------------------ the assessment
    /**
     * The verdict for one email. [sender] is the From header ("Name <addr>"), [text] what was read
     * (full text or preview), [links] (href, visible text) pairs from the HTML, [layaP] Laya's
     * phishing probability when a caller has one (never computed here).
     */
    fun assess(
        sender: String,
        text: String = "",
        replyTo: String = "",
        authHeader: String = "",
        links: List<Pair<String, String>> = emptyList(),
        layaP: Double? = null,
        trusted: List<String> = emptyList(),
        config: SiteConfig = DEFAULT_CONFIG,
        contacts: List<Contact> = emptyList(),
        online: OnlineContext? = null,
    ): PhishVerdict {
        val (display, address) = parseAddress(sender)
        val domain = domainOf(address)
        val (regRaw, suffix) = reg(domain)
        val reg: String? = regRaw ?: domain.ifEmpty { null }
        // The domain as written. When it is not ASCII it is not its IDNA form: `ｐａｙｐａｌ.com`,
        // `𝗽𝗮𝘆𝗽𝗮𝗹.com` and `p🄰yp🄰l.com` all map to paypal.com, but no real sender writes an address
        // that way, and the provider's DMARC result is for the literal domain. Such a sender is never
        // known, trusted or free-mail, and does not own the brand it names.
        val written = address.substringAfterLast('@', "").trim().trim('.', '>')
        val notAscii = written.any { it.code >= 128 }
        val freemail = !notAscii && isFreemail(reg)
        val auth = authResults(authHeader)
        val dmarcFail = auth["dmarc"] == "fail"
        val reasons = mutableListOf<PhishReason>()

        fun add(code: String, source: String, vararg params: Pair<String, String?>) {
            if (reasons.any { it.code == code }) return
            val clean = params.filter { it.second != null }.associate { it.first to it.second!! }
            reasons += PhishReason(code, reasonText(code, clean), WEIGHTS.getValue(code).first, clean, source)
        }

        // trusted senders and well-known brand domains: never flagged unless the From address was forged
        val hit = if (notAscii) null else isTrusted(address, domain, trusted)
        var knownBrand: Brand? = null
        val knownDomain = !notAscii && reg != null && !freemail && config.known(reg, suffix)
        if (knownDomain) knownBrand = config.brands.firstOrNull { config.owns(it, reg, suffix) }
        if (hit != null || knownBrand != null || knownDomain) {
            if (!dmarcFail) {
                val code = if (hit != null) "trusted_sender" else "known_sender"
                val params = mapOf("domain" to (hit ?: reg!!), "brand" to (knownBrand?.name ?: reg ?: ""))
                return PhishVerdict(
                    flag = false, score = 0, reasons = listOf(PhishReason(code, reasonText(code, params), 0, params, "sender")),
                    known = hit == null, trusted = hit != null, domain = reg ?: "",
                )
            }
            add("spoofed_known_sender", "auth", "domain" to reg)
        }

        // the sender's domain
        val hostCodes = mutableListOf<String>()
        if (domain.isNotEmpty() && !freemail) {
            // The domain as written, when it is not ASCII: its IDNA form hides what `disguised_host`
            // and `unicode_drift_host` look for (ParsedUrl.typedHost).
            for ((code, params) in hostCodes(if (notAscii) written else domain, config) + (if (notAscii) literalCodes(written, config) else emptyList())) {
                val mapped = SENDER_CODES[code]
                if (mapped != null && code != "many_subdomains") {
                    hostCodes += code
                    add(mapped, "sender", "brand" to params["brand"], "domain" to (params["domain"] ?: reg), "tld" to params["tld"])
                }
            }
        }

        // An address written with an ideographic, full-width or halfwidth full stop (`paypal.com。`,
        // `mybank。com`): browsers read it as a dot, but no real address is written so.
        if (notAscii && written.any { it in STAND_IN_DOTS }) add("sender_disguised_domain", "sender", "domain" to (reg ?: domain))

        // A domain that older (IDNA 2003) software reads as a trusted one (billing@faß.de when you
        // trust fass.de) imitates it: browsers and mail reach a different domain.
        if (notAscii && domain.isNotEmpty()) {
            val other = Hosts.transitionalAscii(written)
            if (other != domain && isTrusted(address.substringBeforeLast('@') + "@" + other, other, trusted) != null) {
                add("sender_deviation_known", "sender", "domain" to reg)
            }
        }

        // the display name
        ADDR_IN_TEXT_RE.find(PortableText.shadow(display))?.let { m ->
            val shown = display.substring(m.range)
            val shownDomain = PortableText.lowercase(shown.substringAfter('@'))
            val shownReg = reg(shownDomain).first ?: shownDomain
            if (reg != null && shownReg != reg) add("display_address_mismatch", "sender", "shown" to shown, "domain" to reg)
        }
        val claim = brandClaim(display, config)
        if (claim != null && reg != null && (notAscii || !config.owns(claim, reg, suffix))) {
            if (freemail) {
                add("display_brand_freemail", "sender", "brand" to claim.name, "domain" to reg)
            } else if ("brand_in_domain" in hostCodes && (hostCodes.toSet() intersect (IMPOSTOR + "brand_other_tld")).isEmpty()) {
                // a company named after the word ("Apple Valley" <x@applevalley.com>), not a claim
            } else {
                add("display_brand_mismatch", "sender", "brand" to claim.name, "domain" to reg)
            }
        }

        // reply-to
        if (replyTo.isNotEmpty()) {
            for (mb in MimeParser.mailboxes(replyTo).take(5)) {
                val rtDomain = domainOf(mb.address)
                val rtReg = reg(rtDomain).first ?: rtDomain
                if (rtReg.isEmpty() || reg == null || rtReg == reg) continue
                // As for the sender: a reply address written with anything but ASCII is never the
                // brand's own or a free-mail one, and its host checks read it as written.
                val rtWritten = mb.address.substringAfterLast('@', "").trim().trim('.', '>')
                val rtNotAscii = rtWritten.any { it.code >= 128 }
                val rtFree = !rtNotAscii && isFreemail(rtReg)
                val codes = if (rtFree) emptyList() else if (rtNotAscii) hostCodes(rtWritten, config) + literalCodes(rtWritten, config) else hostCodes(rtDomain, config)
                // replies to a domain that carries a brand's name the brand does not own
                val impostor = codes.firstOrNull { it.second["brand"] != null }
                val written = if (!rtNotAscii) emptySet() else (setOf("disguised_host", "deviation_known_host") intersect codes.map { it.first }.toSet()) +
                    (if (rtWritten.any { it in STAND_IN_DOTS }) setOf("disguised_host") else emptySet())
                val owner = if (rtNotAscii) config.brands.firstOrNull { config.owns(it, rtReg, reg(rtDomain).second) } else null
                if (impostor != null) {
                    add("reply_to_impostor", "reply_to", "target" to rtReg, "brand" to impostor.second["brand"])
                } else if (owner != null || written.isNotEmpty()) {
                    // ｐａｙｐａｌ.com: it maps to a brand's domain, but no real reply address is written so
                    add("reply_to_impostor", "reply_to", "target" to rtWritten, "brand" to (owner?.name ?: rtReg))
                } else if (rtFree && !freemail) {
                    add("reply_to_freemail", "reply_to", "target" to rtReg, "domain" to reg)
                } else if (!config.known(rtReg, reg(rtDomain).second)) {
                    add("reply_to_mismatch", "reply_to", "target" to rtReg, "domain" to reg)
                }
            }
        }

        // authentication results (the receiving server's own verdict)
        if (dmarcFail && reasons.none { it.code == "spoofed_known_sender" }) {
            add("auth_dmarc_fail", "auth", "domain" to (reg ?: domain))
        } else if (!dmarcFail && auth["spf"] == "fail" && auth["dkim"] != "pass" && auth["dmarc"] in listOf(null, "none", "temperror", "permerror")) {
            add("auth_spf_dkim_fail", "auth")
        }

        // links
        for ((code, params) in linkSignals(text, links, reg, config)) {
            add(code, "link", *params.map { it.key to it.value }.toTypedArray())
        }

        // your contacts: a known name from another address, a near miss or look-alike of their domain
        for ((code, params) in contactSignals(display, address, contacts)) {
            add(code, "contact", *params.map { it.key to it.value }.toTypedArray())
        }

        // formula v1.3: the message vouches for itself, from a sender that is not one of your contacts
        val phrase = PhishingOwnWords.selfVouching(text)
        if (phrase != null && !isContactAddress(address, contacts)) {
            // The phrase is the sender's own text: shown in its own direction (it may be Arabic) and
            // unable to reorder the sentence around it (bidi isolates; its own bidi controls removed).
            val params = mapOf("phrase" to phrase)
            val shown = mapOf("phrase" to FSI + phrase + PDI)
            if (reasons.none { it.code == "self_vouching" }) {
                reasons += PhishReason("self_vouching", reasonText("self_vouching", shown), W_SELF_VOUCHING, params, "text")
            }
        }

        // opt-in online checks (never for a trusted or brand sender: those returned above)
        if (online != null) {
            val evidence = runCatching { OnlineSignals.mailEvidence(reg.takeUnless { freemail }, linkTargets(text, links, reg, config), online) }
                .getOrDefault(emptyList())                      // an online failure never changes the offline verdict
            for (r in evidence) if (reasons.none { it.code == r.code }) reasons += PhishReason(r.code, r.text, r.weight, r.params, "online")
        }

        val det = reasons.sumOf { it.weight }
        var hasRisk = reasons.any { it.code in RISK_CODES }
        var layaPoints = 0
        if (layaP != null && layaP >= 0.5 && det > 0) {
            layaPoints = if (layaP >= LAYA_FULL_P) LAYA_STRONG else LAYA_WEAK
            reasons += PhishReason("laya_phishing", WEIGHTS.getValue("laya_phishing").second, layaPoints, emptyMap(), "laya")
        }
        var score = minOf(100, det + layaPoints)
        val gates = mutableListOf<String>()
        val weighed = reasons.filter { it.weight > 0 }.map { it.code }.toSet()
        if (weighed.isNotEmpty() && weighed.all { it in OnlineSignals.MAIL_AGE_CODES }) {
            if (score >= PHISHING_AT) gates += "online_age_only_cap"
            score = minOf(score, PHISHING_AT - 1)            // a young domain (or certificate) alone never flags a message
            hasRisk = false
        }
        return PhishVerdict(
            flag = score >= PHISHING_AT && hasRisk, score = score, reasons = reasons.sortedByDescending { it.weight },
            known = false, trusted = false, domain = reg ?: "", gates = gates,
        )
    }

    private const val FSI = "\u2068"
    private const val PDI = "\u2069"

    /** Station's `_is_contact_address`: the From address is one of a contact's own addresses. */
    internal fun isContactAddress(address: String, contacts: List<Contact>): Boolean {
        val a = PhishingOwnWords.pyStrip(address).lowercase()
        if (a.isEmpty()) return false
        return contacts.any { c -> c.addresses.any { PhishingOwnWords.pyStrip(it).lowercase() == a } }
    }

    /** Webmail domains: anyone can open a second address there, so a known name on one is always checked. */
    private val WEBMAIL = setOf("gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com", "live.com", "aol.com", "proton.me", "protonmail.com")

    /**
     * Rule 6 of docs/PHISHING-FORMULA.md: the sender's display name is one of your [contacts] but the
     * address is not theirs. The engine's `Impersonation` facts, scored: a look-alike of their domain
     * (an international domain whose confusable letters fold to theirs) 60, a near miss
     * (Levenshtein 1-2) 45, otherwise 30 —
     * except a second address on the contact's own, non-webmail domain (receipts@ and support@).
     */
    fun contactSignals(display: String, address: String, contacts: List<Contact>): List<Pair<String, Map<String, String>>> {
        val name = display.trim()
        if (name.isEmpty() || address.isEmpty() || contacts.isEmpty()) return emptyList()
        val contact = contacts.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return emptyList()
        val addr = address.lowercase()
        val theirs = contact.addresses.map { it.lowercase() }
        if (addr in theirs) return emptyList()
        val domain = domainOf(address)
        val known = theirs.map { domainOf(it) }.filter { it.isNotEmpty() }.toSet()
        if (domain in known && domain !in WEBMAIL) return emptyList()
        if (domain.isNotEmpty() && domain !in known) {
            // An international domain whose look-alike letters fold to a contact's domain (rule 2).
            val unicode = Hosts.decodeDomain(domain)
            val sk = SiteSignals.skeleton(unicode)
            known.firstOrNull { unicode.any { c -> c.code >= 128 } && SiteSignals.skeleton(Hosts.decodeDomain(it)) == sk }?.let { t ->
                return listOf("contact_homograph_domain" to mapOf("name" to contact.name, "domain" to Hosts.decodeDomain(domain), "target" to t))
            }
            known.minByOrNull { Impersonation.levenshtein(domain, it) }?.takeIf { Impersonation.levenshtein(domain, it) in 1..2 }?.let { t ->
                return listOf("contact_lookalike_domain" to mapOf("name" to contact.name, "domain" to domain, "target" to t))
            }
        }
        return listOf("contact_name_other_address" to mapOf("name" to contact.name, "address" to address))
    }

    /**
     * (url) of the links worth an online check: web links that are not the sender's own domain, a
     * mailing service's click tracker, a well-known brand's domain or a private address.
     */
    fun linkTargets(text: String, links: List<Pair<String, String>>, senderReg: String?, config: SiteConfig): List<String> {
        val hrefs = judgedLinks(links).map { it.first } + urls(text).map { if (it.lowercase().startsWith("http")) it else "http://$it" }
        val out = mutableListOf<String>()
        for (raw in hrefs) {
            val href = Hosts.linkUrl(raw)
            if (!Hosts.isWebUrl(href) || href in out) continue
            val u = ParsedUrl.parse(href) ?: continue
            if (u.host.isEmpty() || Hosts.isPrivateHost(u.host) || Hosts.isIp(u.host)) continue
            val reg = u.registrable ?: u.host
            if (reg == senderReg || reg in LINK_TRACKERS || LINK_TRACKERS.any { u.host.endsWith(".$it") } || config.known(u.registrable, u.suffix)) continue
            out += href
        }
        return out.take(MAX_LINKS)
    }

    internal fun urls(text: String): List<String> = textLinks(text).first

    /** The URLs one text token stands for ([textUrls]), for tests that grow a single token past URL_RE's cap. */
    internal fun textUrlsOf(token: String): List<String> = mutableListOf<String>().also { textUrls(token, it, 0, mutableListOf()) }

    /** [urls], and the (cut URL, URL right after the cut) pairs a stop joined in the text. */
    internal fun textLinks(text: String): Pair<List<String>, List<Pair<String, String>>> {
        val out = mutableListOf<String>()
        val stitches = mutableListOf<Pair<String, String>>()
        for (m in URL_RE.findAll(text)) {
            textUrls(m.value, out, 0, stitches)
            if (out.size >= MAX_LINKS) break
        }
        fun clean(u: String) = u.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
        val urls = out.map(::clean)
            .filter { u -> u.substring(u.indexOf("://").let { if (it < 0) 0 else it + 3 }.coerceAtMost(u.length)).isNotEmpty() }
            .distinct().take(MAX_LINKS)
        return urls to stitches.map { clean(it.first) to clean(it.second) }
    }

    /** A bare host name: labels of letters, digits and hyphens, a dot, a top-level domain. */
    private val BARE_HOST_RE = Regex("""[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,24}""")

    /**
     * The URL(s) one URL_RE match stands for in running text (fix loops 4–5): the address up to where
     * the sentence goes on ([textUrlEnd]); the host after an `@` that a cut left out, when it is the
     * same domain (linkifiers link it: `https://www.paypal.com|login@paypal.com`); and every address a
     * later piece of the token still holds, after any stop (`https://paypal.com，evil.com/login`,
     * `…/a|b｜evil.tk`: linkifiers link `evil.com/login` and `evil.tk` too).
     */
    private fun textUrls(token: String, out: MutableList<String>, depth: Int, stitches: MutableList<Pair<String, String>>) {
        val (url, cut, after) = textUrlEnd(token)
        if (url.isNotEmpty()) out += url
        if (after != null) out += after
        if (cut < 0 || depth >= 4) return
        // A stitch is a cut inside the first URL's host (`paypal.com，evil.com`), not after its path
        // began (`www.facebook.com/nileshoes｜www.nileshoes.com` is two links side by side), fix loop 6
        val stitchable = cut < authorityEnd(token)
        fun follow(next: String) {
            val before = out.size
            textUrls(next, out, depth + 1, stitches)
            if (stitchable && out.size > before && url.isNotEmpty()) stitches += url to out[before]
        }
        // One forward pass over the rest of the token (fix loop 6: no copy or rescan per stop): the
        // next URL start anywhere, found once, and a bare host at the start of each piece.
        val n = token.length
        val nextUrl = urlStartFrom(token, cut)
        var k = cut
        while (k < n && out.size < MAX_LINKS) {
            while (k < n && (textStop(token[k]) || token[k] == '^')) k++
            if (k >= n) return
            if (nextUrl == k) { follow(token.substring(k)); return }
            var e = k
            while (e < n && !textStop(token[e])) e++
            if (nextUrl in k until e) { textUrls(token.substring(nextUrl), out, depth + 1, stitches); return }
            if (bareHostAt(token, k, e)) { follow(token.substring(k, e)); return }
            k = e
        }
    }

    /** End of the authority of a URL token (after `scheme://` or at 0 for `www.`): the first `/ ? # \`. */
    private fun authorityEnd(token: String): Int {
        val from = token.indexOf("://").let { if (it < 0) 0 else it + 3 }
        for (i in from until token.length) if (token[i] == '/' || token[i] == '?' || token[i] == '#' || token[i] == '\\') return i
        return token.length
    }

    /** Index of the first `http://`, `https://` or `www.` (any case) at or after [from], or -1. */
    private fun urlStartFrom(s: String, from: Int): Int {
        for (i in from until s.length) {
            val c = s[i]
            if ((c == 'h' || c == 'H') && (s.regionMatches(i, "http://", 0, 7, ignoreCase = true) || s.regionMatches(i, "https://", 0, 8, ignoreCase = true))) return i
            if ((c == 'w' || c == 'W') && s.regionMatches(i, "www.", 0, 4, ignoreCase = true)) return i
        }
        return -1
    }

    /** A bare host with a listed top-level domain at [k] of the piece [k, end): `evil.com/login`, `evil.tk`. */
    private fun bareHostAt(s: String, k: Int, end: Int): Boolean {
        var j = k
        while (j < end && (s[j] in 'a'..'z' || s[j] in 'A'..'Z' || s[j] in '0'..'9' || s[j] == '-' || s[j] == '.')) j++
        if (j < end && s[j] !in ":/?#") return false
        val host = s.substring(k, j).trimEnd('.')
        if (!BARE_HOST_RE.matches(host)) return false
        return Hosts.toAsciiLabel(host.substringAfterLast('.'))?.let { it in PublicSuffix.DEFAULT } == true
    }

    /**
     * Where a URL written in running text ends, and where it was cut (-1 when it was not). URL_RE
     * takes everything up to a space, but CJK and Arabic text runs straight on after an address:
     * `访问www.example.com，了解更多`, `网址：https://www.example.com；电话`. The URL ends at the first
     * [textStop] (ideographic and full-width punctuation U+3000–303F, U+FF01–FF0F, U+FF1A–FF20,
     * U+FF3B–FF40, U+FF5B–FF65; Arabic ، ؛ ؟; `|`) or, in the host, `^`.
     *
     * - The ideographic, full-width and halfwidth full stops (`。．｡`) are dots in a host (UTS #46); in
     *   text they usually end a sentence. One is kept as a dot when the labels after it end with a
     *   top-level domain on the Public Suffix List (`日本語。jp`, `www.paypal.com．evil．xyz`), or, for
     *   `．` and `｡`, when an ASCII letter or digit follows (no sentence goes on so).
     * - A cut before an `@` of the authority does not hide the host a browser opens: when the host
     *   after the last `@` (or a full-width `＠`, which NSDataDetector links through) is another
     *   registrable domain than the one before the cut, the URL keeps its userinfo
     *   (`https://www.paypal.com|login@paypa1-secure.xyz/login` is paypa1-secure.xyz), fix loop 5.
     *   `访问 https://www.example.com，邮箱：info@example.com` names one domain and is cut.
     */
    internal fun textUrlEnd(url: String): Triple<String, Int, String?> {
        val schemeEnd = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        var authEnd = url.length
        for (i in schemeEnd until url.length) if (url[i] == '/' || url[i] == '?' || url[i] == '#' || url[i] == '\\') { authEnd = i; break }
        // the authority's last @ (or ＠)
        var at = -1
        for (i in schemeEnd until authEnd) if (url[i] == '@' || url[i] == '\uFF20') at = i
        var after: String? = null
        if (at >= 0) {
            val preCut = firstStop(url, schemeEnd, at, at)
            if (preCut >= 0 || url[at] == '\uFF20') {
                val pre = url.substring(schemeEnd, if (preCut >= 0) preCut else at).substringBefore(':')
                val (postEnd, postCut) = scanEnd(url, at + 1, authEnd)
                val post = url.substring(at + 1, minOf(postEnd, authEnd)).substringBefore(':')
                val preReg = if (HOSTISH.matches(pre)) Hosts.registrableDomain(pre) ?: pre else null
                val postReg = Hosts.toAsciiDomain(post)?.let { Hosts.registrableDomain(it) ?: it }
                if (preReg != null && postReg != null && post.isNotEmpty()) {
                    if (postReg != preReg) {
                        // the browser opens the host after the @; the text before it is userinfo
                        return Triple(url.substring(0, at) + "@" + url.substring(at + 1, postEnd), postCut, null)
                    }
                    // the same domain: judged too, as linkifiers link it, without the userinfo
                    after = (if (schemeEnd > 0) url.substring(0, schemeEnd) else "http://") + url.substring(at + 1, postEnd)
                }
            }
        }
        val (end, cut) = scanEnd(url, schemeEnd, authEnd)
        return Triple(url.substring(0, end), cut, after)
    }

    /** A host as written before a cut: labels of letters, digits and hyphens with a dot. */
    private val HOSTISH = Regex("""^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+$""")

    /** (end, cut) of the URL scanning from [from]; [hostEnd] ends the host part. */
    private fun scanEnd(url: String, from: Int, hostEnd: Int): Pair<Int, Int> {
        val stop = firstStop(url, from, url.length, hostEnd)
        return if (stop < 0) url.length to -1 else stop to stop
    }

    /** The first stop in [from, until), with host rules before [hostEnd]; -1 for none. */
    private fun firstStop(url: String, from: Int, until: Int, hostEnd: Int): Int {
        // the TLD verdict of a run of stand-in dots is the same for every dot in it: computed once
        // per run, so a host of many dots stays linear (fix loop 6)
        var runEnd = -1
        var runVerdict = false
        for (i in from until until) {
            val c = url[i]
            val inHost = i < hostEnd
            if (inHost && c in TEXT_DOTS) {
                val ascii = c != '\u3002' && i + 1 < hostEnd && url[i + 1].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                if (!ascii && i >= runEnd) {
                    runEnd = dotRunEnd(url, i, hostEnd)
                    runVerdict = tldAt(url, i, runEnd)
                }
                if (ascii || runVerdict) continue
            }
            if (textStop(c) || (inHost && c == '^')) return i
        }
        return -1
    }

    private const val TEXT_DOTS = "\u3002\uFF0E\uFF61"

    private fun textStop(c: Char): Boolean {
        val x = c.code
        return c == '|' || c == '\u060C' || c == '\u061B' || c == '\u061F' || x in 0x3000..0x303F || x in 0xFF01..0xFF0F ||
            x in 0xFF1A..0xFF20 || x in 0xFF3B..0xFF40 || x in 0xFF5B..0xFF65
    }

    /**
     * Where the host after the stand-in dot at [i] ends: through further stand-in dots, up to the
     * port, an `@`, the end of the host or another stop.
     */
    private fun dotRunEnd(url: String, i: Int, hostEnd: Int): Int {
        var j = i + 1
        while (j < hostEnd && url[j] != ':' && url[j] != '@' && url[j] != '\uFF20' && (url[j] in TEXT_DOTS || !textStop(url[j]))) j++
        return j
    }

    /** Whether the host run (i, j) ends with a top-level domain on the Public Suffix List. */
    private fun tldAt(url: String, i: Int, j: Int): Boolean {
        var k = j
        while (k > i + 1 && url[k - 1] != '.' && url[k - 1] !in TEXT_DOTS) k--
        val tld = url.substring(k, j)
        if (tld.isEmpty()) return false
        val ascii = Hosts.toAsciiLabel(tld) ?: return false
        return ascii in PublicSuffix.DEFAULT
    }

    /**
     * The links judged for one message (fix loop 11): verified links (an anchor, a form or a frame the
     * tree-aware reading places) before unverified ones, exact duplicates and an unverified link whose
     * host is already judged dropped, and only then the cap: [MAX_LINK_HOSTS] distinct hosts and
     * [MAX_JUDGED_LINKS] links. Seventy anchors in a comment, an MSO block or a hidden <div> cannot push
     * the real link out.
     */
    fun judgedLinks(links: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<Pair<String, String>>()
        val perHost = HashMap<String, Int>()
        for (verified in listOf(true, false)) for (p in links) {
            if ((p.second != HtmlAnchors.UNVERIFIED) != verified || !seen.add(p)) continue
            val key = ParsedUrl.parse(Hosts.linkUrl(p.first))?.host?.ifEmpty { null } ?: ("\u0000" + p.first.take(80))
            val c = perHost[key]
            if (c == null) {
                if (perHost.size >= MAX_LINK_HOSTS) continue
            } else if (!verified) continue
            if (out.size >= MAX_JUDGED_LINKS) break
            perHost[key] = (c ?: 0) + 1
            out += p
        }
        return out
    }

    /** (code, params) for the links of one message: anchors from the HTML plus URLs in the text. */
    fun linkSignals(text: String, links: List<Pair<String, String>>, senderReg: String?, config: SiteConfig): List<Pair<String, Map<String, String>>> {
        val pairs = judgedLinks(links).toMutableList()
        val seen = pairs.map { it.first }.toMutableSet()
        val (textUrls, stitches) = textLinks(text)
        for (url in textUrls) {
            // `www.example.com` stays as written (linkUrl reads it as http://): a bare host is
            // never an explicit-scheme URL for link_unreadable
            val full = if (url.lowercase().startsWith("http")) url else "http://$url"
            val fresh = seen.add(full)
            seen.add(url)
            if (fresh) pairs += url to ""
        }
        val out = mutableListOf<Pair<String, Map<String, String>>>()
        val got = mutableSetOf<String>()
        // A known or brand address run straight into another domain by a stop (`https://paypal.com，evil.com/login`,
        // `www.paypal.com｜evil.tk`): the text reads as one address, linkifiers open two (fix loop 5;
        // origin/main read it as one host with the brand in front: brand_in_subdomain 30)
        for ((a, b) in stitches) {
            val ua = ParsedUrl.parse(Hosts.linkUrl(a)) ?: continue
            val ub = ParsedUrl.parse(Hosts.linkUrl(b)) ?: continue
            val ra = ua.registrable ?: ua.host
            val rb = ub.registrable ?: ub.host
            if (ra.isEmpty() || rb.isEmpty() || ra == rb || config.known(ub.registrable, ub.suffix)) continue
            val brand = config.brands.firstOrNull { config.owns(it, ua.registrable, ua.suffix) }
            if (brand != null || config.known(ua.registrable, ua.suffix)) {
                if (got.add("link_stitched")) out += "link_stitched" to mapOf("brand" to (brand?.name ?: ra), "domain" to rb)
            }
        }
        // a link only the no-skip reading found (HtmlAnchors.UNVERIFIED text: it may be code a client
        // shows as text) raises risk-level signals only, never a note such as a TLD or a shortener
        var unverified = false
        fun add(code: String, vararg params: Pair<String, String?>) {
            if (unverified && (WEIGHTS[code]?.first ?: 0) < RISK_MIN) return
            if (got.add(code)) out += code to params.filter { it.second != null }.associate { it.first to it.second!! }
        }

        for ((hrefRaw, shownText) in pairs) {
            unverified = shownText == HtmlAnchors.UNVERIFIED
            val visible = if (unverified) "" else shownText
            // as a browser reads the href: C0 controls and spaces stripped, tabs and newlines dropped
            val href = Hosts.stripC0(hrefRaw)
            val low = href.filter { it != '\t' && it != '\r' && it != '\n' }.lowercase()
            if (low.isEmpty() || listOf("mailto:", "tel:", "cid:", "#", "sms:").any { low.startsWith(it) }) continue
            if (listOf("data:", "blob:", "javascript:").any { low.startsWith(it) }) {
                add("link_data")
                continue
            }
            // WHATWG: a special scheme always has an authority (`https:\\evil.com`), never "http://" + it
            val parsed = ParsedUrl.parse(Hosts.linkUrl(href))
            // only an href with an explicit web scheme and a host no browser opens (Hosts.unreadableUrl)
            // the text shows a well-known address this link does not reach
            fun shownKnownDomain(target: String) {
                DOMAINISH_RE.matchEntire(visible.trim())?.let { m ->
                    val visDomain = m.groupValues[1].lowercase()
                    val (visReg, visSuffix) = reg(visDomain)
                    if (visReg != null && config.known(visReg, visSuffix) && !isFreemail(visReg)) add("link_text_mismatch", "shown" to visDomain, "domain" to target)
                }
            }
            if (Hosts.unreadableUrl(href)) {
                add("link_unreadable")
                shownKnownDomain(parsed?.host ?: href)
                // the host as written is still judged below (brand words, TLD), as the site check does
            }
            val u = parsed ?: continue
            val host = u.host
            val reg = u.registrable ?: u.host
            if (host.isEmpty()) {
                // a relative href (no scheme, no host: `/x`, a template tag, one behind a no-break
                // space) opens no website of its own; it is not scored, unless its text names one
                if (u.scheme.isEmpty()) shownKnownDomain(href)
                continue
            }
            val tracker = reg in LINK_TRACKERS || LINK_TRACKERS.any { host.endsWith(".$it") }
            val own = !senderReg.isNullOrEmpty() && reg == senderReg
            val known = config.known(u.registrable, u.suffix)
            // How the link is WRITTEN, wherever it goes (a known site, the sender's own, a tracker).
            if (SiteSignals.disguise(u, config).isNotEmpty()) add("link_disguised", "domain" to reg)
            if (SiteSignals.unicodeDrift(u).isNotEmpty()) add("link_unicode_drift", "domain" to reg)
            if (SiteSignals.deviation(u)) {
                val other = Hosts.transitionalAscii(u.unicodeHost)
                add(if (config.known(Hosts.registrableDomain(other), Hosts.publicSuffix(other))) "link_deviation_known" else "link_deviation", "domain" to reg)
            }
            if (!known && !tracker) {
                if (!own) {
                    for (s in SiteSignals.urlSignals(u, config)) {
                        if (s.code in setOf("userinfo_in_url", "url_shortener", "data_url")) add(LINK_CODES.getValue(s.code), "domain" to reg)
                    }
                }
                for (s in SiteSignals.hostSignals(u, config)) {
                    // a link to the sender's own domain only adds a brand in the link's subdomain
                    if (own && s.code !in SUBDOMAIN_CODES) continue
                    val mapped = LINK_CODES[s.code] ?: continue
                    add(mapped, "brand" to s.params["brand"], "domain" to (s.params["domain"] ?: reg), "host" to (s.params["host"] ?: host), "tld" to s.params["tld"])
                }
            }
            if (visible.isEmpty() || own || tracker || known) continue
            // the visible text is itself a well-known address, but the link goes elsewhere
            val m = DOMAINISH_RE.matchEntire(visible.trim())
            if (m != null) {
                val visDomain = m.groupValues[1].lowercase()
                val (visReg, visSuffix) = reg(visDomain)
                if (visReg != null && visReg != reg && config.known(visReg, visSuffix) && !isFreemail(visReg)) {
                    add("link_text_mismatch", "shown" to visDomain, "domain" to reg)
                    continue
                }
            }
            // "Sign in to PayPal" / "Verify your Apple ID" pointing at an unrelated domain
            if (BAIT_WORDS.containsMatchIn(PortableText.fold(visible))) {
                for ((name, brand) in config.namePatterns) {
                    if (SiteSignals.containsWord(visible, name) && !config.owns(brand, u.registrable, u.suffix)) {
                        add("link_brand_text", "brand" to brand.name, "domain" to reg)
                        break
                    }
                }
            }
        }
        return out
    }

    /** The English reason for [code] with its params (a missing param shows as "…"). */
    fun reasonText(code: String, params: Map<String, String>): String =
        fillTemplate(WEIGHTS[code]?.second ?: INFO_TEXT[code] ?: code, params)
}
