package dev.loupe.kit.mail

import dev.loupe.kit.site.Brand
import dev.loupe.kit.site.Brands
import dev.loupe.engine.Contact
import dev.loupe.engine.Impersonation
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.OnlineContext
import dev.loupe.kit.site.OnlineSignals
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.kit.site.SiteConfig
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
    const val MAX_TRUSTED = 500

    /** code -> (weight, plain-language reason). `{placeholders}` come from the reason's params. */
    val WEIGHTS: Map<String, Pair<Int, String>> = linkedMapOf(
        // sender domain (site protection's host checks, applied to the From domain)
        "sender_homograph_brand" to (60 to "The sender's domain imitates {brand} with look-alike letters ({domain})."),
        "sender_mixed_script" to (35 to "The sender's domain mixes letters from different alphabets ({domain})."),
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
        "link_text_mismatch" to (40 to "A link shows {shown} but really goes to {domain}."),
        "link_brand_text" to (30 to "A link asks you to sign in or verify with {brand}, but goes to {domain}."),
        "link_data" to (40 to "A link opens a data: address, which has no real website behind it."),
        "link_userinfo" to (30 to "A link hides its real website behind text and an @ sign ({domain})."),
        "link_ip" to (25 to "A link goes to a bare IP number ({host}) instead of a website name."),
        "link_suspicious_tld" to (8 to "A link goes to a domain ending in .{tld}, often used by throw-away scam sites."),
        "link_shortener" to (5 to "A link uses a link shortener, so its real destination is hidden."),
        // your contacts (rule 6: contact impersonation feeds the same score as the brand checks)
        "contact_homograph_domain" to (60 to "The sender's name is your contact {name}, and {domain} imitates their domain {target} with look-alike letters."),
        "contact_lookalike_domain" to (45 to "The sender's name is your contact {name}, but {domain} is a near miss of {target}, the domain they write from."),
        "contact_name_other_address" to (30 to "The sender's name is your contact {name}, but {address} is not an address they write from."),
        // Laya (weight shown is the maximum; 0.5-0.8 counts half)
        "laya_phishing" to (LAYA_STRONG to "Laya's reading of the text: it looks like a phishing or scam attempt."),
    ).also { m -> m.putAll(OnlineSignals.MAIL_WEIGHTS) }
    val INFO_TEXT: Map<String, String> = linkedMapOf(
        "known_sender" to "The sender's domain {domain} belongs to {brand}.",
        "trusted_sender" to "You marked {domain} as a trusted sender.",
    )
    val REASON_CODES: List<String> = WEIGHTS.keys.toList() + INFO_TEXT.keys
    val REASON_PARAMS = listOf("brand", "domain", "shown", "target", "host", "tld", "name", "address")
    val RISK_CODES: Set<String> = WEIGHTS.filter { (c, w) -> w.first >= RISK_MIN && c != "laya_phishing" }.keys

    private val SENDER_CODES = mapOf(
        "homograph_brand" to "sender_homograph_brand", "mixed_script" to "sender_mixed_script",
        "lookalike_brand" to "sender_lookalike_brand", "brand_domain_in_subdomain" to "sender_brand_domain_in_subdomain",
        "brand_in_subdomain" to "sender_brand_in_subdomain", "brand_in_domain_bait" to "sender_brand_in_domain_bait",
        "brand_other_tld" to "sender_brand_other_tld", "brand_in_domain" to "sender_brand_in_domain",
        "suspicious_tld" to "sender_suspicious_tld", "ip_host" to "sender_ip",
    )
    private val LINK_CODES = mapOf(
        "homograph_brand" to "link_homograph_brand", "lookalike_brand" to "link_lookalike_brand",
        "brand_domain_in_subdomain" to "link_brand_domain_in_subdomain", "brand_in_subdomain" to "link_brand_in_subdomain",
        "brand_in_domain_bait" to "link_brand_in_domain_bait", "mixed_script" to "link_mixed_script",
        "ip_host" to "link_ip", "suspicious_tld" to "link_suspicious_tld", "data_url" to "link_data",
        "userinfo_in_url" to "link_userinfo", "url_shortener" to "link_shortener",
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

    private fun words(s: String): Set<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

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

    private val BAIT_WORDS = Regex(
        "sign[\\s-]?in|log[\\s-]?in|log[\\s-]?on|verify|verification|confirm|unlock|update|" +
            "password|account|secure|تسجيل|الدخول|تحقق|تأكيد|تحديث|كلمة المرور|حسابك",
        RegexOption.IGNORE_CASE,
    )
    private val URL_RE = Regex("""(?:https?://|www\.)[^\s<>"'()\[\]{}]{3,2000}""", RegexOption.IGNORE_CASE)
    private val DOMAINISH_RE = Regex("""^(?:https?://)?(?:www\.)?((?:[a-z0-9-]+\.)+[a-z]{2,24})(?:[/:?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private val ADDR_IN_TEXT_RE = Regex("""[\p{L}\p{Nd}\p{Nl}\p{No}_.+-]+@((?:[\p{L}\p{Nd}\p{Nl}\p{No}_-]+\.)+[a-z]{2,24})""", RegexOption.IGNORE_CASE)
    private val AUTH_RE = Regex("""\b(spf|dkim|dmarc|compauth)\s*=\s*([a-z]+)""", RegexOption.IGNORE_CASE)
    private val WORD_RE = Regex("""[\p{L}\p{Nd}\p{Nl}\p{No}]+""")

    fun isFreemail(reg: String?): Boolean {
        if (reg.isNullOrEmpty()) return false
        if (reg in FREEMAIL) return true
        val label = reg.substringBefore('.')
        val suffix = if ('.' in reg) reg.substringAfter('.') else ""
        val last = suffix.substringAfterLast('.')
        return label in FREEMAIL_LABELS && last.length == 2 && last.all { it.isLetter() } && suffix.count { it == '.' } <= 1
    }

    // ------------------------------------------------------------------------ config
    fun mailBrands(): List<Brand> =
        Brands.BRANDS.map { b -> b.copy(domains = b.domains + (EXTRA_SENDER_DOMAINS[b.name] ?: emptyList())) } + MAIL_BRANDS

    val DEFAULT_CONFIG: SiteConfig by lazy { SiteConfig(mailBrands(), emptyList(), Brands.SUSPICIOUS_TLDS, Brands.SHORTENERS) }

    // ------------------------------------------------------------------------ small parsers
    /** Lowercase ASCII (IDNA) domain of an address, "" when there is none. */
    internal fun domainOf(address: String): String {
        if ('@' !in address) return ""
        val dom = address.substringAfterLast('@').trim().trim('.', '>').lowercase()
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
        for (m in AUTH_RE.findAll(header ?: "")) {
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
    private val TRUSTED_LOCAL = Regex("""[^\s@<>"(),;:]{1,64}""")

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
        val words = WORD_RE.findAll(text).map { it.value.lowercase() }.toList()
        for ((name, brand) in config.namePatterns) {
            if (!SiteSignals.containsWord(text, name)) continue
            val own = (listOf(brand.name) + brand.tokens).flatMap { n -> WORD_RE.findAll(n).map { it.value.lowercase() } }.toSet()
            val rest = words.filter { it !in own && it !in SERVICE_WORDS && !it.all { c -> c.isDigit() } }
            if (rest.isEmpty()) return brand
        }
        return null
    }

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
        val freemail = isFreemail(reg)
        val auth = authResults(authHeader)
        val dmarcFail = auth["dmarc"] == "fail"
        val reasons = mutableListOf<PhishReason>()

        fun add(code: String, source: String, vararg params: Pair<String, String?>) {
            if (reasons.any { it.code == code }) return
            val clean = params.filter { it.second != null }.associate { it.first to it.second!! }
            reasons += PhishReason(code, reasonText(code, clean), WEIGHTS.getValue(code).first, clean, source)
        }

        // trusted senders and well-known brand domains: never flagged unless the From address was forged
        val hit = isTrusted(address, domain, trusted)
        var knownBrand: Brand? = null
        val knownDomain = reg != null && !freemail && config.known(reg, suffix)
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
            for ((code, params) in hostCodes(domain, config)) {
                val mapped = SENDER_CODES[code]
                if (mapped != null && code != "many_subdomains") {
                    hostCodes += code
                    add(mapped, "sender", "brand" to params["brand"], "domain" to (params["domain"] ?: reg), "tld" to params["tld"])
                }
            }
        }

        // the display name
        ADDR_IN_TEXT_RE.find(display)?.let { shown ->
            val shownDomain = shown.groupValues[1].lowercase()
            val shownReg = reg(shownDomain).first ?: shownDomain
            if (reg != null && shownReg != reg) add("display_address_mismatch", "sender", "shown" to shown.value, "domain" to reg)
        }
        val claim = brandClaim(display, config)
        if (claim != null && reg != null && !config.owns(claim, reg, suffix)) {
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
                val rtFree = isFreemail(rtReg)
                val codes = if (!rtFree) hostCodes(rtDomain, config) else emptyList()
                // replies to a domain that carries a brand's name the brand does not own
                val impostor = codes.firstOrNull { it.second["brand"] != null }
                if (impostor != null) {
                    add("reply_to_impostor", "reply_to", "target" to rtReg, "brand" to impostor.second["brand"])
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
        val hrefs = links.take(MAX_LINKS).map { it.first } + urls(text).map { if (it.lowercase().startsWith("http")) it else "http://$it" }
        val out = mutableListOf<String>()
        for (raw in hrefs) {
            val href = raw.trim()
            if (!(href.startsWith("http://", true) || href.startsWith("https://", true)) || href in out) continue
            val u = ParsedUrl.parse(href) ?: continue
            if (u.host.isEmpty() || Hosts.isPrivateHost(u.host) || Hosts.isIp(u.host)) continue
            val reg = u.registrable ?: u.host
            if (reg == senderReg || reg in LINK_TRACKERS || LINK_TRACKERS.any { u.host.endsWith(".$it") } || config.known(u.registrable, u.suffix)) continue
            out += href
        }
        return out.take(MAX_LINKS)
    }

    internal fun urls(text: String): List<String> =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"') }.take(MAX_LINKS).toList()

    /** (code, params) for the links of one message: anchors from the HTML plus URLs in the text. */
    fun linkSignals(text: String, links: List<Pair<String, String>>, senderReg: String?, config: SiteConfig): List<Pair<String, Map<String, String>>> {
        val pairs = links.take(MAX_LINKS).toMutableList()
        val seen = pairs.map { it.first }.toMutableSet()
        for (url in urls(text)) {
            val full = if (url.lowercase().startsWith("http")) url else "http://$url"
            if (seen.add(full)) pairs += full to ""
        }
        val out = mutableListOf<Pair<String, Map<String, String>>>()
        val got = mutableSetOf<String>()
        fun add(code: String, vararg params: Pair<String, String?>) {
            if (got.add(code)) out += code to params.filter { it.second != null }.associate { it.first to it.second!! }
        }

        for ((hrefRaw, visible) in pairs.take(MAX_LINKS)) {
            val href = hrefRaw.trim()
            val low = href.lowercase()
            if (low.isEmpty() || listOf("mailto:", "tel:", "cid:", "#", "sms:").any { low.startsWith(it) }) continue
            if (listOf("data:", "blob:", "javascript:").any { low.startsWith(it) }) {
                add("link_data")
                continue
            }
            val u = ParsedUrl.parse(if ("://" in href) href else "http://$href") ?: continue
            val host = u.host
            val reg = u.registrable ?: u.host
            if (host.isEmpty()) continue
            val tracker = reg in LINK_TRACKERS || LINK_TRACKERS.any { host.endsWith(".$it") }
            val own = !senderReg.isNullOrEmpty() && reg == senderReg
            val known = config.known(u.registrable, u.suffix)
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
            if (BAIT_WORDS.containsMatchIn(visible)) {
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
