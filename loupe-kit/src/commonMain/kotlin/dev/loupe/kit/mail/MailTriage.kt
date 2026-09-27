package dev.loupe.kit.mail

import dev.loupe.engine.Contact
import dev.loupe.engine.Message
import dev.loupe.engine.PortableText
import dev.loupe.engine.Rx
import dev.loupe.kit.site.Hosts
import dev.loupe.kit.site.OnlineContext
import dev.loupe.kit.site.OnlineSignals
import dev.loupe.kit.site.ParsedUrl
import dev.loupe.kit.site.SiteCheck
import dev.loupe.kit.site.SiteConfig
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.kit.site.SiteCheckResult
import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.CorrectionRecord
import dev.loupe.sources.common.HtmlText
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.Links
import dev.loupe.sources.common.MimeParser
import dev.loupe.sources.common.SourceItem

/**
 * One email as the triage reads it: the From header, subject, body, and the facts a scam cannot
 * hide (Reply-To, the receiving server's Authentication-Results, links with their visible text).
 */
data class MailMessage(
    val id: String,
    val sender: String,
    val subject: String,
    val dateIso: String?,
    /** The body alone (what Station's phishing check reads). */
    val body: String,
    /** "From/Subject" plus body (what Station's rules read). */
    val text: String,
    val replyTo: String = "",
    val authResults: String = "",
    val links: List<Pair<String, String>> = emptyList(),
    val provider: String? = null,
    val labels: List<String> = emptyList(),
    val folder: String = "",
    val inference: String = "",
) {
    companion object {
        // HTML syntax, ASCII case spelled out so the captured href and text keep their case, and
        // ASCII whitespace (the JDK's \s and IGNORE_CASE on this pattern), the same on every engine.
        // `\bhref` is "after a non-word character" consumed, not a lookbehind (O(position) on
        // Kotlin/Native); `<a` is followed by a non-word character, so the two read alike.
        /**
         * (href, visible text) pairs from HTML ([Phishing.judgedLinks]: verified first, capped on hosts), read as a browser reads
         * them ([HtmlAnchors]: whole attribute names, quoted values, character references decoded).
         */
        fun anchors(html: String): List<Pair<String, String>> = Phishing.judgedLinks(HtmlAnchors.anchors(html, RAW_READINGS))

        /** Link readings taken from one HTML part before [Phishing.judgedLinks] orders and caps them. */
        private const val RAW_READINGS = 5000

        /**
         * A mail item as a message. With [raw] (the `.eml` source, when the phone can read it) the
         * Reply-To, Authentication-Results (topmost only: lower ones are forgeable) and HTML anchors
         * come along; without it, the item's own facts (From, subject, body links) are used.
         */
        fun fromItem(item: SourceItem, raw: String? = null, provider: String? = null): MailMessage {
            val facts = item.email
            val from = listOfNotNull(facts?.fromName?.let { "\"${it.replace("\"", "")}\"" }, facts?.fromAddress?.let { "<$it>" }).joinToString(" ")
            val body = item.text.substringAfter("\n\n", item.text)
            var replyTo = ""
            var auth = ""
            var anchors: List<Pair<String, String>> = emptyList()
            var nonNavigable: Set<String> = emptySet()
            // the mail's own links found by pattern: the source's list (as 699761a) unless the raw mail
            // is there, where they come only from what a client shows (fix loop 10)
            var patternLinks: List<String> = facts?.links ?: emptyList()
            var labels: List<String> = emptyList()
            if (raw != null) {
                val entity = MimeParser.parseEntity(raw.encodeToByteArray())
                replyTo = entity.header("reply-to")?.trim() ?: ""
                auth = entity.header("authentication-results")?.trim()?.take(2000) ?: ""
                labels = (entity.header("x-keywords") ?: entity.header("keywords") ?: "").split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                val parts = htmlParts(entity)
                anchors = Phishing.judgedLinks(parts.flatMap { anchors(it) })
                nonNavigable = parts.flatMapTo(HashSet()) { HtmlAnchors.nonNavigableUrls(it) }
                val plain = plainParts(entity)
                if (parts.isNotEmpty() || plain.isNotEmpty()) {
                    // HTML: every link-bearing tag is already read ((a) and (b)); only URLs in the visible
                    // text are added, as a client linkifies them. Plain text: its URLs.
                    patternLinks = Links.find(*(parts.map { HtmlAnchors.visibleText(it) } + plain).toTypedArray())
                }
            }
            // the source's own link list (found by pattern in the raw mail) is judged too where no anchor
            // covers its host, so one harmless anchor cannot switch the fallback off (fix loop 7)
            val covered = anchors.mapNotNull { ParsedUrl.parse(Hosts.linkUrl(it.first))?.host?.ifEmpty { null } }.toSet()
            // ...but not a URL nobody follows (a DTD, a namespace URI, an image's src; fix loop 9), unless
            // the visible text shows it
            val extra = patternLinks.filter { l -> ParsedUrl.parse(Hosts.linkUrl(l))?.host?.ifEmpty { null }.let { it == null || it !in covered } }
                .filter { l -> l.trim() !in nonNavigable || l.trim() in item.text }
            val links = Phishing.judgedLinks(anchors + extra.map { it to "" })
            return MailMessage(
                id = item.id, sender = from, subject = facts?.subject ?: "", dateIso = item.dateIso, body = body,
                text = item.text, replyTo = replyTo, authResults = auth, links = links,
                provider = provider, labels = labels,
            )
        }

        private fun plainParts(entity: MimeParser.Entity, depth: Int = 0): List<String> {
            val type = entity.header("content-type")?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
            if (type.startsWith("multipart/") && depth < 32) return MimeParser.parts(entity).flatMap { plainParts(it, depth + 1) }
            return if (type == "text/plain" && entity.header("content-disposition")?.lowercase()?.contains("attachment") != true) listOf(MimeParser.text(entity)) else emptyList()
        }

        private fun htmlParts(entity: MimeParser.Entity, depth: Int = 0): List<String> {
            val type = entity.header("content-type")?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
            if (type.startsWith("multipart/") && depth < 32) return MimeParser.parts(entity).flatMap { htmlParts(it, depth + 1) }
            return if (type == "text/html") listOf(MimeParser.text(entity)) else emptyList()
        }
    }
}

/** Where a row sits on the Mail triage screen. */
enum class MailSection(val title: String) {
    PHISHING("Phishing suspected"),
    SPAM("Spam"),
    NEEDS_REPLY("Needs reply"),
    URGENT("Urgent"),
    CATEGORY("By category"),
}

/** A concrete reason shown on a row: a phishing signal, urgent wording, a link check. */
data class MailSignal(val code: String, val text: String, val weight: Int, val kind: String)

/** One triaged email. */
data class MailRow(
    val itemId: String,
    val sender: String,
    val subject: String,
    val dateIso: String?,
    val categoryKey: String,
    val categoryTitle: String,
    val categoryWeak: Boolean,
    val labels: List<String>,
    val weakLabels: List<String>,
    val needsReply: Boolean,
    val urgencyLevel: Int,
    val urgencyTitle: String,
    val urgentCue: String?,
    val phishingCue: String?,
    val verdict: PhishVerdict,
    /** The evidence flag, unless a person decided ([personVerdict]). */
    val phishing: Boolean,
    val personVerdict: String?,
    val spam: Boolean,
    val transactional: Boolean,
    val providerCategory: ProviderCategory?,
    val linkChecks: List<SiteCheckResult>,
) {
    val section: MailSection
        get() = when {
            phishing -> MailSection.PHISHING
            spam -> MailSection.SPAM
            MailClassify.NEEDS_REPLY_LABEL in labels -> MailSection.NEEDS_REPLY
            MailClassify.URGENT_LABEL in labels -> MailSection.URGENT
            else -> MailSection.CATEGORY
        }

    /** The concrete signals, strongest first: phishing reasons, then urgent / scam wording, then link checks. */
    val signals: List<MailSignal>
        get() = buildList {
            verdict.reasons.filter { it.weight > 0 }.forEach { r ->
                if (r.source == "online") add(MailSignal(r.code, "${OnlineSignals.label(r.params)}: ${r.text}", r.weight, "online"))
                else add(MailSignal(r.code, r.text, r.weight, "phishing"))
            }
            phishingCue?.let { add(MailSignal("scam_wording", "The text uses a classic scam line: “$it”.", 0, "wording")) }
            urgentCue?.let { add(MailSignal("urgent_language", "Urgent language: “$it”.", 0, "wording")) }
            linkChecks.filter { it.warn }.forEach { c ->
                add(MailSignal("site_check", "Site check (${c.levelTitle}): ${c.url} — ${c.lines.firstOrNull() ?: ""}", c.verdict.score, "site"))
            }
        }

    val scoreLine: String
        get() = "Evidence ${verdict.score} of 100; flagged from ${Phishing.PHISHING_AT} with at least one strong sign."
}

/** A web link found in a non-mail item (a link shared to Loupe, a document), with its site check. */
data class WebLinkCheck(val itemId: String, val itemName: String, val check: SiteCheckResult)

data class MailSummary(val rows: List<MailRow>, val webLinks: List<WebLinkCheck>) {
    val phishingCount: Int get() = rows.count { it.phishing }
    val needsReplyCount: Int get() = rows.count { MailClassify.NEEDS_REPLY_LABEL in it.labels && !it.phishing }

    fun inSection(section: MailSection): List<MailRow> = rows.filter { it.section == section }

    val sections: List<MailSection> get() = MailSection.entries.filter { s -> rows.any { it.section == s } }

    fun row(itemId: String): MailRow? = rows.firstOrNull { it.itemId == itemId }
}

/**
 * Mail triage on the phone (epic #7 child 11): Loupe Station's classifier and phishing evidence
 * over mail items, with the site checks of child 12 on every link. Mechanical — no model.
 * "Mark safe" and "Confirm phishing" are appended corrections: a person's answer decides, as in
 * Station's `relabel`.
 */
object MailTriage {
    const val JUDGMENT_ID = "mail-phishing"
    const val CRITERIA = "mail-phishing-v1"
    const val SAFE = "safe"
    const val PHISHING = "phishing"

    fun triage(
        m: MailMessage,
        personVerdict: String? = null,
        trusted: List<String> = emptyList(),
        contacts: List<Contact> = emptyList(),
        online: OnlineContext? = null,
    ): MailRow {
        val view = MailClassify.answers(m.text)
        val flags = MailClassify.triageFlags(m, view, trusted, contacts, online)
        val phishing = when (personVerdict) {
            SAFE -> false
            PHISHING -> true
            else -> flags.phishing.flag
        }
        val spamWeak = flags.spamSource == "provider+laya"
        val (labels, weak, _) = MailClassify.labelPlan(view, phishing, flags.spam, flags.bulk, spamWeak)
        val cat = view.getValue("category")
        val urg = view.getValue("urgency")
        val level = urg.level ?: 0
        val urgentCue = when (level) {
            2 -> MailClassify.firstHit(MailClassify.URGENT_NOW, m.text)
            1 -> MailClassify.firstHit(MailClassify.URGENT_SOON, m.text)
            else -> null
        }
        val checks = m.links.map { it.first }
            .filter { it.startsWith("http", ignoreCase = true) || it.startsWith("www.", ignoreCase = true) }
            .distinct().take(10).map { SiteCheck.checkUrl(if (it.startsWith("www.", ignoreCase = true)) "http://$it" else it, online) }
        return MailRow(
            itemId = m.id, sender = m.sender, subject = m.subject, dateIso = m.dateIso,
            categoryKey = cat.label, categoryTitle = MailClassify.words(cat.label), categoryWeak = cat.weak,
            labels = labels, weakLabels = weak, needsReply = view.getValue("needs_reply").label == "yes",
            urgencyLevel = level, urgencyTitle = MailClassify.URGENCY_LEVELS[level], urgentCue = urgentCue,
            phishingCue = MailClassify.firstHit(MailClassify.PHISHING_WORDS, m.text),
            verdict = flags.phishing, phishing = phishing, personVerdict = personVerdict,
            spam = flags.spam, transactional = flags.transactional, providerCategory = flags.providerCategory,
            linkChecks = checks,
        )
    }

    /** Station's `sort_rows`: phishing first, then urgency (highest first); stable otherwise. */
    fun sortRows(rows: List<MailRow>): List<MailRow> =
        rows.sortedWith(compareBy<MailRow> { !it.phishing }.thenByDescending { it.urgencyLevel })

    /**
     * Every mail item triaged. [raw] gives an item's `.eml` source when the phone can read it
     * (null otherwise). Corrections recorded with [markSafe] / [confirmPhishing] decide.
     */
    /**
     * Every mail item triaged. [raw] gives an item's `.eml` source when the phone can read it
     * (null otherwise). Corrections recorded with [markSafe] / [confirmPhishing] decide. Your
     * contacts (the address book and names seen twice from one address) feed the same score
     * (formula rule 6); [online] is the opt-in online checks' data, null when they are off.
     */
    fun summarise(
        items: List<SourceItem>,
        raw: (SourceItem) -> String?,
        corrections: Map<CorrectionKey, String>,
        online: OnlineContext? = null,
    ): MailSummary {
        val book = WatcherRun.addressBook(items)
        val mails = items.filter { it.kind == ItemKind.EMAIL }
        val messages = mails.mapNotNull { item -> item.email?.fromAddress?.let { item.id to Message(item.email?.fromName ?: "", it, item.text) } }
        val rows = mails.map { item ->
            val msg = MailMessage.fromItem(item, raw(item))
            val contacts = WatcherRun.contactsFrom(messages.filter { it.first != item.id }.map { it.second }, book)
            triage(msg, corrections[CorrectionKey(JUDGMENT_ID, CRITERIA, item.id)], contacts = contacts, online = online)
        }
        return MailSummary(sortRows(rows), webLinks(items, online = online))
    }

    /** For Swift: [summarise] with a map of raw sources by item id. */
    fun summariseWithRaw(items: List<SourceItem>, raws: Map<String, String>, corrections: Map<CorrectionKey, String>): MailSummary =
        summarise(items, { raws[it.id] }, corrections)

    /** For Swift: [summarise] with raw sources and the online checks' data (null when off). */
    fun summariseOnline(items: List<SourceItem>, raws: Map<String, String>, corrections: Map<CorrectionKey, String>, online: OnlineContext?): MailSummary =
        summarise(items, { raws[it.id] }, corrections, online)

    /**
     * The domains the online checks may look up for these items (sender and link domains in their
     * ICANN form; never a free-mail provider, a brand's own domain, a hosting platform's customer
     * site, a private address), at most [max]. What Swift asks the web helper about.
     */
    fun onlineLookups(items: List<SourceItem>, raws: Map<String, String>, max: Int = 40): List<String> {
        val out = LinkedHashSet<String>()
        for (item in items) {
            if (item.kind == ItemKind.EMAIL) {
                val m = MailMessage.fromItem(item, raws[item.id])
                val (_, address) = Phishing.parseAddress(m.sender)
                val domain = Phishing.domainOf(address)
                val reg = Hosts.registrableDomain(domain)
                if (reg != null && !Phishing.isFreemail(reg) && !Phishing.DEFAULT_CONFIG.known(reg, Hosts.publicSuffix(domain))) {
                    OnlineSignals.lookupDomain(domain)?.let(out::add)
                }
                for (u in Phishing.linkTargets(m.body, m.links, reg, Phishing.DEFAULT_CONFIG)) OnlineSignals.lookupDomainOfUrl(u)?.let(out::add)
            } else if (item.kind != ItemKind.CONTACT && item.hasText) {
                for (u in SiteCheck.linksIn(item.text, 5)) {
                    val p = ParsedUrl.parse(u) ?: continue
                    if (!SiteConfig.DEFAULT.known(p.registrable, p.suffix)) OnlineSignals.lookupDomain(p.host)?.let(out::add)
                }
            }
            if (out.size >= max) break
        }
        return out.take(max)
    }

    /**
     * The web links Google Safe Browsing may be asked about (on a local hash-prefix match only):
     * the mail links worth an online check and web links in other items, at most [max].
     */
    fun onlineUrls(items: List<SourceItem>, raws: Map<String, String>, max: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        for (item in items) {
            if (item.kind == ItemKind.EMAIL) {
                val m = MailMessage.fromItem(item, raws[item.id])
                val reg = Hosts.registrableDomain(Phishing.domainOf(Phishing.parseAddress(m.sender).second))
                out += Phishing.linkTargets(m.body, m.links, reg, Phishing.DEFAULT_CONFIG)
            } else if (item.kind != ItemKind.CONTACT && item.hasText) {
                out += SiteCheck.linksIn(item.text, 5)
            }
            if (out.size >= max) break
        }
        return out.take(max)
    }

    /** Web links in items that are not mail (a link shared to Loupe, a document), each with its site check. */
    fun webLinks(items: List<SourceItem>, maxItems: Int = 50, online: OnlineContext? = null): List<WebLinkCheck> =
        items.asSequence().filter { it.kind != ItemKind.EMAIL && it.kind != ItemKind.CONTACT && it.hasText }
            .flatMap { item -> SiteCheck.linksIn(item.text, 5).map { WebLinkCheck(item.id, item.name, SiteCheck.checkUrl(it, online)) } }
            .take(maxItems).toList()

    fun markSafe(row: MailRow, at: String): CorrectionRecord = CorrectionRecord(JUDGMENT_ID, CRITERIA, row.itemId, SAFE, at, false)

    fun confirmPhishing(row: MailRow, at: String): CorrectionRecord = CorrectionRecord(JUDGMENT_ID, CRITERIA, row.itemId, PHISHING, at, true)

    /** Undo: an appended retraction. */
    fun retraction(row: MailRow, at: String): CorrectionRecord = CorrectionRecord(JUDGMENT_ID, CRITERIA, row.itemId, null, at, false)
}
