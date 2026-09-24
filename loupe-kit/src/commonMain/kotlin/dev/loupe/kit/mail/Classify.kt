package dev.loupe.kit.mail

import dev.loupe.engine.Contact
import dev.loupe.kit.site.OnlineContext

import dev.loupe.templates.Baseline

/*
 * Mail triage labels for one email: the category, reply and urgency answers, the evidence-based
 * phishing verdict, the provider's spam / bulk verdicts, and the `Laya/...` labels they map to.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`, commit
 * ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e): `laya_studio/mail/classify.py` (triage_flags,
 * label_plan, sort_rows, choice/question label naming, the transactional pattern, WEAK, SPAM_STRONG,
 * NOUL_MIN), `mail/triage.py` (the thresholds and label names), `mail/provider.py`
 * (provider_category, BULK_CATEGORIES) and the `wf-email-triage` rules of `measure/baseline.py`
 * (EMAIL_BASELINES: category, urgency, needs_reply, is_phishing keyword rules) with the preset's
 * category options from `presets.py`. Tables, keywords, thresholds and label wording are verbatim.
 * The rules are run through the engine's own [Baseline] evaluator (Station's is a port of it).
 *
 * Not ported: Laya's own answers (the model call), Composio and the mailbox providers. On the phone
 * the keyword rules answer every question — Station's "the baseline answers" path — so Station's
 * rule that a text reading only adds weight next to real evidence is what keeps them honest.
 */

/** The mailbox provider's own sorting: spam / promotions / social / forums / updates / primary / other / focused. */
data class ProviderCategory(val key: String, val provider: String, val name: String)

/** One answer in Station's `answers_view` shape. [level] only for score questions. */
data class TriageAnswer(val label: String, val p: Double, val weak: Boolean, val level: Int? = null, val source: String = "baseline")

object MailClassify {
    const val PRESET_ID = "wf-email-triage"
    const val LABEL_PREFIX = "Laya/"
    const val PHISHING_LABEL = "Laya/Phishing"
    const val SPAM_LABEL = "Laya/Spam"
    const val URGENT_LABEL = "Laya/Urgent"
    const val NEEDS_REPLY_LABEL = "Laya/Needs Reply"
    const val PHISHING_MIN = 0.5
    const val SPAM_MIN = 0.5
    const val NEEDS_REPLY_MIN = 0.5
    const val URGENT_MIN = 0.75
    const val SPAM_STRONG = 0.9
    const val NOUL_MIN = 0.5

    /** (preset id, question id) measured below 75% on clear-cut cases (Station's WEAK, the email rows). */
    val WEAK: Set<Pair<String, String>> = setOf(
        "wf-email-reply" to "reply_kind", "wf-email-reply" to "urgency",
        "wf-email-triage" to "category", "wf-email-triage" to "is_phishing", "wf-email-triage" to "urgency",
        "laya-email" to "is_phishing", "laya-email" to "is_spam", "laya-email" to "needs_reply", "laya-email" to "urgency",
    )

    /** wf-email-triage's category options (presets.py), in order: key -> Station's description. */
    val CATEGORY_OPTIONS: Map<String, String> = linkedMapOf(
        "supplier_invoice" to "supplier bill or statement (فاتورة مورد)",
        "customer_complaint" to "a customer unhappy with an order",
        "sales_lead" to "quote, price or order request from a new customer",
        "partner_notice" to "partner, platform or marketplace notice",
        "job_application" to "CV or asking for a job",
        "government_tax" to "tax authority, e-invoice, licence (الضرائب)",
        "bank_payment" to "bank, card, transfer or payout",
        "newsletter_marketing" to "newsletter, ads, cold offers",
        "phishing" to "fake login, prize or payment scam",
        "other" to "none of these",
    )

    /** wf-email-triage's urgency levels (presets.py). */
    val URGENCY_LEVELS: List<String> = listOf("no request or no time pressure", "a request with a deadline in days", "blocking problem or due today")

    // ------------------------------------------------------------------ EMAIL_BASELINES (measure/baseline.py)
    val PHISHING_WORDS = listOf(
        "verify your account", "confirm your identity", "verify your identity", "account suspended",
        "account has been suspended", "unusual activity", "update your payment",
        "your account will be closed", "you have won", "claim your prize",
    )
    val URGENT_NOW = listOf("today", "immediately", "urgent", "within 24 hours", "as soon as possible", "ASAP", "tonight", "right away", "عاجل", "اليوم", "فوراً")
    val URGENT_SOON = listOf("this week", "tomorrow", "by friday", "by monday", "next week", "within 7 days", "within 30 days", "by the end of", "deadline", "due date", "خلال", "بكرة")

    val CATEGORY_RULES: List<Pair<List<String>, String>> = listOf(
        PHISHING_WORDS to "phishing",
        listOf("unsubscribe", "newsletter", "view in browser", "special offer", "limited time") to "newsletter_marketing",
        listOf("CV", "resume", "curriculum vitae", "job application", "vacancy", "السيرة الذاتية") to "job_application",
        listOf("tax", "VAT", "e-invoice", "IRS", "HMRC", "الضرائب") to "government_tax",
        listOf("marketplace", "partner", "seller") to "partner_notice",
        listOf("payout", "bank", "transfer", "card") to "bank_payment",
        listOf("invoice", "amount due", "payment terms", "فاتورة", "facture", "factura") to "supplier_invoice",
        listOf("refund", "complaint", "damaged", "disappointed", "broken", "not happy") to "customer_complaint",
        listOf("quote", "quotation", "price list", "pricing") to "sales_lead",
    )
    /** The rules as the engine's declarative [Baseline]s (what they are, for showing and storing). */
    val CATEGORY: Baseline = Baseline.KeywordMap(CATEGORY_RULES.map { Baseline.KeywordMap.Rule(it.first, it.second) }, "other")
    val URGENCY: Baseline = Baseline.KeywordMap(listOf(Baseline.KeywordMap.Rule(URGENT_NOW, "2"), Baseline.KeywordMap.Rule(URGENT_SOON, "1")), "0")
    val NEEDS_REPLY: Baseline = Baseline.Pattern("[?؟]\\s*$|[?؟]\\s*\\n", "yes", "no", "a line ending in a question mark")
    val IS_PHISHING: Baseline = Baseline.Keyword(PHISHING_WORDS, "yes", "no")

    /**
     * The keyword rules answered without a lookbehind. [Baseline]'s word regex starts each keyword
     * with a negative lookbehind, which Kotlin/Native runs in O(n) per position (a 300-character
     * email took ~0.5 s per question on the simulator). This finds each keyword case-insensitively
     * and checks the characters on either side against the same class (`\p{L}\p{Nd}\p{Nl}\p{No}`),
     * the GuardedRegex approach of the privacy port. MailClassifyParityTest pins it to
     * `Baseline.answer` on every case and sample email.
     */
    internal object Keywords {
        // Built once, read-only afterwards: safe to share across threads.
        private val compiled: Map<String, Regex> by lazy {
            (CATEGORY_RULES.flatMap { it.first } + URGENT_NOW + URGENT_SOON + PHISHING_WORDS).distinct()
                .associateWith { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
        }

        private fun wordish(c: Char): Boolean = c.isLetter() || c.category == CharCategory.DECIMAL_DIGIT_NUMBER ||
            c.category == CharCategory.LETTER_NUMBER || c.category == CharCategory.OTHER_NUMBER

        fun found(keyword: String, text: String): Boolean {
            val re = compiled[keyword] ?: Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE)
            var from = 0
            while (from <= text.length) {
                val m = re.find(text, from) ?: return false
                val start = m.range.first
                val end = m.range.last + 1
                if ((start == 0 || !wordish(text[start - 1])) && (end >= text.length || !wordish(text[end]))) return true
                from = start + 1
            }
            return false
        }

        fun any(keywords: List<String>, text: String): Boolean = keywords.any { found(it, text) }
    }

    private fun category(text: String): String = CATEGORY_RULES.firstOrNull { Keywords.any(it.first, text) }?.second ?: "other"

    private fun urgencyLevel(text: String): Int = when {
        Keywords.any(URGENT_NOW, text) -> 2
        Keywords.any(URGENT_SOON, text) -> 1
        else -> 0
    }

    /** The first keyword of [keywords] found in [text] as the rules find it (for showing *why*). */
    fun firstHit(keywords: List<String>, text: String): String? = keywords.firstOrNull { Keywords.found(it, text) }

    /** The rules' answers for [text], in Station's `answers_view` shape (all `source: baseline`). */
    fun answers(text: String): Map<String, TriageAnswer> {
        val cat = category(text)
        val level = urgencyLevel(text)
        return linkedMapOf(
            "category" to TriageAnswer(cat, 1.0, (PRESET_ID to "category") in WEAK),
            "urgency" to TriageAnswer(URGENCY_LEVELS[level], level / 2.0, (PRESET_ID to "urgency") in WEAK, level),
            "needs_reply" to (if (NEEDS_REPLY.answer(text) == "yes") 1.0 else 0.0).let { TriageAnswer(if (it >= NOUL_MIN) "yes" else "no", it, (PRESET_ID to "needs_reply") in WEAK) },
            "is_phishing" to (if (Keywords.any(PHISHING_WORDS, text)) 1.0 else 0.0).let { TriageAnswer(if (it >= NOUL_MIN) "yes" else "no", it, (PRESET_ID to "is_phishing") in WEAK) },
        )
    }

    // ------------------------------------------------------------------ naming
    private val ACRONYMS = mapOf("hr" to "HR", "vip" to "VIP", "it" to "IT", "vat" to "VAT", "ai" to "AI", "faq" to "FAQ", "pr" to "PR")
    private val LABEL_RE = Regex("^Laya/[^\\x00-\\x1f\\x7f\"\\\\{}\\[\\]*%]{1,59}$")
    private val REPLY_RE = Regex("reply|respon", RegexOption.IGNORE_CASE)

    fun words(key: String): String =
        key.split(Regex("[_\\-\\s]+")).filter { it.isNotEmpty() }
            .joinToString(" ") { p -> ACRONYMS[p.lowercase()] ?: (p.take(1).uppercase() + p.drop(1)) }

    fun validLabel(name: String): Boolean =
        LABEL_RE.matches(name) && name == name.trim() && name.split("/").drop(1).all { it.isNotEmpty() && it == it.trim() && it != "." && it != ".." }

    private fun label(title: String): String? {
        var name = LABEL_PREFIX + title.replace(Regex("[^\\p{L}\\p{Nd}\\p{Nl}\\p{No}_ .&+-]+"), " ").trim()
        name = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(64).trimEnd()
        return name.takeIf { validLabel(it) }
    }

    fun choiceLabel(value: String): String? = label(words(value))

    fun questionLabel(qid: String): String? = label(words(qid.replace(Regex("^is_"), "")))

    // ------------------------------------------------------------------ provider categories (provider.py)
    const val SPAM = "spam"
    val BULK_CATEGORIES = setOf("promotions", "social", "forums", "other")
    private val GMAIL_CATEGORIES = listOf(
        "SPAM" to SPAM, "CATEGORY_PROMOTIONS" to "promotions", "CATEGORY_SOCIAL" to "social",
        "CATEGORY_FORUMS" to "forums", "CATEGORY_UPDATES" to "updates", "CATEGORY_PERSONAL" to "primary",
    )
    private val PROVIDER_NAMES = mapOf("gmail" to "Gmail", "outlook" to "Outlook", "imap" to "IMAP")
    val CATEGORY_TITLES = mapOf(
        "spam" to "Spam", "promotions" to "Promotions", "social" to "Social", "forums" to "Forums",
        "updates" to "Updates", "primary" to "Primary", "other" to "Other", "focused" to "Focused",
    )

    fun providerCategory(provider: String?, labels: List<String>, folder: String = "", inference: String = ""): ProviderCategory? {
        val key: String? = when (provider) {
            "gmail" -> labels.map { it.uppercase() }.toSet().let { up -> GMAIL_CATEGORIES.firstOrNull { it.first in up }?.second }
            "outlook" -> if (folder == "junk") SPAM else inference.takeIf { it == "other" || it == "focused" }
            "imap" -> {
                val low = labels.map { it.lowercase() }.toSet()
                if ((low intersect setOf("\$junk", "junk")).isNotEmpty() && (low intersect setOf("\$notjunk", "notjunk", "nonjunk")).isEmpty()) SPAM
                else if (folder == "junk") SPAM else null
            }
            else -> null
        }
        return key?.let { ProviderCategory(it, provider!!, PROVIDER_NAMES[provider] ?: provider) }
    }

    // ------------------------------------------------------------------ flags and labels (classify.py)
    val TRANSACTIONAL_RE = Regex(
        "receipt|order|invoice|payment|statement|security alert|sign[- ]?in|password|verification code|" +
            "booking|shipped|delivery|renewal|إيصال|فاتورة|طلب|تأكيد|تنبيه|كشف حساب|recibo|pedido|factura|reçu|" +
            "commande|facture",
        RegexOption.IGNORE_CASE,
    )

    /** Station's `laya_readings`: (phishing p, spam p, urgency 0..1) from the answers; null: not asked. */
    fun readings(view: Map<String, TriageAnswer>, kinds: Map<String, String> = KINDS): Triple<Double?, Double?, Double> {
        var phishing: Double? = null
        var spam: Double? = null
        var urgency = 0.0
        for ((qid, a) in view) {
            val low = qid.lowercase()
            when (kinds[qid]) {
                "noul" -> {
                    if ("phish" in low) phishing = maxOf(phishing ?: 0.0, a.p)
                    if ("spam" in low) spam = maxOf(spam ?: 0.0, a.p)
                }
                "choice" -> {
                    val v = a.label.lowercase()
                    if ("phish" in v) phishing = maxOf(phishing ?: 0.0, a.p)
                    if ("spam" in v) spam = maxOf(spam ?: 0.0, a.p)
                }
                "score" -> if ("urgen" in low) urgency = maxOf(urgency, a.p)
            }
        }
        return Triple(phishing, spam, urgency)
    }

    /** wf-email-triage's question kinds. */
    val KINDS: Map<String, String> = mapOf("category" to "choice", "urgency" to "score", "needs_reply" to "noul", "is_phishing" to "noul")

    data class Flags(
        val phishing: PhishVerdict,
        val spam: Boolean,
        val spamSource: String?,
        val providerCategory: ProviderCategory?,
        val bulk: Boolean,
        val transactional: Boolean,
        val urgency: Double,
    )

    /** Everything the labels need beyond the answers (Station's `triage_flags`). */
    fun triageFlags(
        m: MailMessage,
        view: Map<String, TriageAnswer>,
        trusted: List<String> = emptyList(),
        contacts: List<Contact> = emptyList(),
        online: OnlineContext? = null,
    ): Flags {
        val (phishP0, spamP, urgency) = readings(view)
        var phishP = phishP0
        // a text reading below the phishing threshold does not count at all
        for ((qid, a) in view) {
            if (KINDS[qid] == "noul" && "phish" in qid.lowercase() && phishP != null && a.p >= phishP && a.p < PHISHING_MIN) phishP = null
        }
        val evidence = Phishing.assess(m.sender, m.body, m.replyTo, m.authResults, m.links, layaP = phishP, trusted = trusted, contacts = contacts, online = online)
        val pcat = providerCategory(m.provider, m.labels, m.folder, m.inference)
        val key = pcat?.key
        val transactional = key == "updates" || evidence.known || evidence.trusted || TRANSACTIONAL_RE.containsMatchIn(m.subject)
        val spamSource = when {
            key == SPAM -> "provider"
            key == "promotions" && (spamP ?: 0.0) >= SPAM_STRONG && !transactional && !evidence.trusted -> "provider+laya"
            else -> null
        }
        return Flags(evidence, spamSource != null, spamSource, pcat, key in BULK_CATEGORIES, transactional, urgency)
    }

    /** (labels, weak labels, {label: source}) — Station's `label_plan` for wf-email-triage. */
    fun labelPlan(view: Map<String, TriageAnswer>, phishing: Boolean, spam: Boolean, bulk: Boolean, spamWeak: Boolean = false): Triple<List<String>, List<String>, Map<String, String>> {
        val junk = phishing || spam || bulk
        val out = mutableListOf<String>()
        val weak = mutableListOf<String>()
        val sources = LinkedHashMap<String, String>()
        fun add(name: String?, source: String, isWeak: Boolean) {
            if (name != null && name !in out) {
                out += name
                sources[name] = source
                if (isWeak) weak += name
            }
        }
        for ((qid, a) in view) {
            val low = qid.lowercase()
            when (KINDS[qid]) {
                "choice" -> {
                    val value = if (a.label in CATEGORY_OPTIONS) a.label else if ("other" in CATEGORY_OPTIONS) "other" else ""
                    if (value.isEmpty() || "phish" in value.lowercase() || "spam" in value.lowercase()) continue
                    add(choiceLabel(value), qid, a.weak)
                }
                "noul" -> if (a.p >= NOUL_MIN) {
                    if ("phish" in low || "spam" in low) continue
                    if (junk && REPLY_RE.containsMatchIn(qid)) continue
                    add(questionLabel(qid), qid, a.weak)
                }
                "score" -> if ("urgen" in low && !junk && a.p >= URGENT_MIN) add(URGENT_LABEL, qid, a.weak)
            }
        }
        if (phishing) add(PHISHING_LABEL, "evidence", false)
        if (spam) add(SPAM_LABEL, "provider", spamWeak)
        return Triple(out, weak, sources)
    }
}
