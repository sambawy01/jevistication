package dev.loupe.kit.watchers

import dev.loupe.engine.Backend
import dev.loupe.engine.Charge
import dev.loupe.engine.Contact
import dev.loupe.engine.DateFacts
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.ExpiryAlert
import dev.loupe.engine.ExpiryRadar
import dev.loupe.engine.Impersonation
import dev.loupe.engine.ImpersonationReason
import dev.loupe.engine.ImpersonationSignal
import dev.loupe.engine.Message
import dev.loupe.engine.OriginFacts
import dev.loupe.engine.Probability
import dev.loupe.engine.RecurringCharge
import dev.loupe.engine.RecurringMoney
import dev.loupe.kit.settings.Features
import dev.loupe.kit.settings.RunPolicy
import dev.loupe.kit.site.PageFacts
import dev.loupe.kit.site.SiteScoring
import dev.loupe.kit.site.SiteVerdict
import dev.loupe.engine.TermChange
import dev.loupe.engine.TermChangeDetector
import dev.loupe.engine.ValidityRule
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import kotlinx.datetime.LocalDate

/** A document with an expiry-like date, found mechanically. */
data class ExpiryCandidate(val item: SourceItem, val expiry: LocalDate, val daysRemaining: Long, val ambiguous: Boolean, val breachesRule: Boolean)

/** Two versions of one document whose labelled amounts moved. */
data class TermChangeFinding(val earlier: SourceItem, val later: SourceItem, val changes: List<TermChange>)

/** A message whose sender does not add up. */
data class ImpersonationFinding(val item: SourceItem, val signals: List<ImpersonationSignal>)

/**
 * A link, or a sender domain, that the shared phishing / site formula (docs/PHISHING-FORMULA.md)
 * rates caution or danger: its one [verdict], with the signals behind it.
 */
data class FraudFinding(val item: SourceItem, val what: String, val verdict: SiteVerdict)

/** Everything the watchers raised. Warnings only: there is deliberately no "all clear" here. */
data class WatcherReport(
    val today: LocalDate,
    val rule: ValidityRule,
    /** Mechanical: an expiry word near a date that breaches or nears the rule. */
    val expiryCandidates: List<ExpiryCandidate>,
    /** Model + arithmetic (`ExpiryRadar.scan`): null when the model is not loaded. */
    val expiryAlerts: List<ExpiryAlert>?,
    val recurring: List<RecurringCharge>,
    val chargesFound: Int,
    val termChanges: List<TermChangeFinding>,
    val impersonation: List<ImpersonationFinding>,
    val fraud: List<FraudFinding>,
    val emailsChecked: Int,
    val linksChecked: Int,
    /** `features.watchers.use_laya` was off: the expiry radar's Laya half did not run (the banner). */
    val layaOff: Boolean = false,
)

/**
 * Runs the five watchers over scanned items (C3), in common code so the desktop and the iPhone run
 * the same orchestration. Moved here from `loupe-desktop`'s `core/Watchers.kt` (which now adapts its
 * items to these and delegates) without changing any rule or threshold. Each watcher is the engine's
 * own, unchanged; this layer only turns scanned items into the inputs those watchers take.
 *
 * - **Expiry radar** — the date arithmetic is mechanical and always runs; deciding *what a
 *   document is* needs the model, so the full radar runs only when a backend is passed.
 * - **Recurring money** — charges are read from emails that say something was charged or paid,
 *   and from CSV files with merchant, date and amount columns. The merchant is the sender's name.
 * - **Term change** — two versions of one document: files whose names differ only by digits
 *   (`renewal-2025.pdf`, `renewal-2026.pdf`), or successive emails from one address.
 * - **Impersonation** — a contact is inferred from history (a display name used at least twice
 *   from the same address), and, when the Contacts source is on, the address book's cards (their
 *   names and email addresses) are known contacts too (epic #7 child 7).
 * - **Site fraud** — the links in emails, and each sender's own domain, through the shared phishing
 *   / site formula (docs/PHISHING-FORMULA.md) with the brand the sender claims; origin facts only,
 *   never page content. A finding is a caution or danger verdict.
 */
object WatcherRun {
    /** "Schengen requires six months of passport validity" — the spec's own example rule. */
    /** The expiry radar's built-in acceptance threshold. */
    const val EXPIRY_THRESHOLD: Double = 0.5

    val SIX_MONTHS: ValidityRule = ValidityRule("six months of validity (e.g. Schengen passports)", 6)

    private val EXPIRY_WORDS = Regex("""\b(expir\w*|valid until|valid to|valid thru|renewal date|4b\.)""", RegexOption.IGNORE_CASE)
    private val CHARGE_WORDS = Regex("""\b(charged|payment received|paid|receipt for)\b""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""[£$€]\s?(\d[\d,]*(?:\.\d{2})?)""")
    private val INSTITUTIONAL_LOCAL = setOf("service", "security", "support", "noreply", "no-reply", "account", "accounts", "billing", "alerts", "info", "verify")
    /** Domains where anyone can register an address, so the domain says nothing about the sender. */
    private val WEBMAIL = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "yahoo.com", "icloud.com",
        "me.com", "aol.com", "proton.me", "protonmail.com", "gmx.com", "gmx.net", "mail.com", "yandex.com",
    )
    private val INSTITUTIONAL_NAME = Regex("""\b(security|support|billing|account|team|bank|service)\b""", RegexOption.IGNORE_CASE)

    fun run(items: List<SourceItem>, today: LocalDate, backend: Backend?, rule: ValidityRule = SIX_MONTHS): WatcherReport =
        run(items, today, backend, rule, RunPolicy.defaults(Features.WATCHERS))

    /**
     * [run] under Model settings (`features.watchers`): with `use_laya` off the backend is not used
     * (the mechanical half runs alone and [WatcherReport.layaOff] is set); `accept_confidence`
     * replaces the radar's 0.5 threshold and `text_chars` its 4,000-character budget.
     */
    fun run(items: List<SourceItem>, today: LocalDate, backend: Backend?, rule: ValidityRule, policy: RunPolicy): WatcherReport =
        run(items, today, backend, rule, policy) { _, _, _ -> }

    /**
     * [run] reporting its real progress: `progress(watcherId, done, total)` as each watcher starts and ends
     * (ids as [WatcherKind.id], in run order: expiry, recurring, term-change, impersonation, site-fraud), and per
     * document while the expiry radar's model half judges them. Called on the running thread; the result is the
     * same as [run]'s.
     */
    fun run(
        items: List<SourceItem>, today: LocalDate, backend: Backend?, rule: ValidityRule, policy: RunPolicy,
        progress: (watcher: String, done: Int, total: Int) -> Unit,
    ): WatcherReport {
        val model = backend.takeIf { policy.useLaya }
        val book = addressBook(items)
        val texty = items.filter { it.hasText && it.duplicateOf == null && it.kind != ItemKind.CONTACT }
        val emails = texty.filter { it.kind == ItemKind.EMAIL && it.email?.fromAddress != null }
        progress(WatcherKind.EXPIRY.id, 0, 1)
        val candidates = expiryCandidates(texty, today, rule)
        val alerts = model?.let { m ->
            val docs = candidates.map { c -> c.item }
            progress(WatcherKind.EXPIRY.id, 0, docs.size)
            // One document at a time, so the run can say how far the model half is (same result: each document is
            // judged on its own and the alerts are sorted by days left either way).
            docs.flatMapIndexed { i, doc ->
                expiryAlerts(listOf(doc), m, today, rule, policy).also { progress(WatcherKind.EXPIRY.id, i + 1, docs.size) }
            }.sortedBy { it.daysRemaining }
        }
        progress(WatcherKind.EXPIRY.id, 1, 1)
        progress(WatcherKind.RECURRING.id, 0, 1)
        val charges = charges(texty)
        val recurring = RecurringMoney.census(charges.map { it.second }, today)
        progress(WatcherKind.RECURRING.id, 1, 1)
        progress(WatcherKind.TERM_CHANGE.id, 0, 1)
        val terms = termChanges(texty)
        progress(WatcherKind.TERM_CHANGE.id, 1, 1)
        progress(WatcherKind.IMPERSONATION.id, 0, 1)
        val impostors = impersonation(emails, book)
        progress(WatcherKind.IMPERSONATION.id, 1, 1)
        progress(WatcherKind.SITE_FRAUD.id, 0, 1)
        val fraud = fraud(emails)
        progress(WatcherKind.SITE_FRAUD.id, 1, 1)
        return WatcherReport(
            today = today,
            rule = rule,
            expiryCandidates = candidates,
            expiryAlerts = alerts,
            recurring = recurring,
            chargesFound = charges.size,
            termChanges = terms,
            impersonation = impostors,
            fraud = fraud,
            emailsChecked = emails.size,
            linksChecked = emails.sumOf { it.email!!.links.size },
            layaOff = !policy.useLaya,
        )
    }

    /** [runIsoWith] reporting progress (see the progress [run]); for Swift. */
    fun runIsoWithProgress(
        items: List<SourceItem>, todayIso: String, backend: Backend?, policy: RunPolicy,
        progress: (watcher: String, done: Int, total: Int) -> Unit,
    ): WatcherReport = run(items, LocalDate.parse(todayIso), backend, SIX_MONTHS, policy, progress)

    /** [runIso] under Model settings. */
    fun runIsoWith(items: List<SourceItem>, todayIso: String, backend: Backend?, policy: RunPolicy): WatcherReport =
        run(items, LocalDate.parse(todayIso), backend, SIX_MONTHS, policy)

    /** For Swift, which does not see kotlinx-datetime comfortably: [todayIso] is `yyyy-MM-dd`. */
    fun runIso(items: List<SourceItem>, todayIso: String, backend: Backend?): WatcherReport =
        run(items, LocalDate.parse(todayIso), backend, SIX_MONTHS)

    /** Items with an expiry word and a date within a year (or already passed), latest date taken. */
    fun expiryCandidates(items: List<SourceItem>, today: LocalDate, rule: ValidityRule): List<ExpiryCandidate> =
        items.mapNotNull { item ->
            if (!EXPIRY_WORDS.containsMatchIn(item.text)) return@mapNotNull null
            val dates = DateFacts.find(item.text)
            if (dates.isEmpty()) return@mapNotNull null
            val latest = dates.maxBy { it.date }
            // As ExpiryRadar does: an ambiguous date on its earlier reading, the safe error.
            val expiry = listOfNotNull(latest.date, latest.alternate).min()
            val days = DateFacts.daysUntil(expiry, today)
            if (days > 366) return@mapNotNull null
            val breaches = DateFacts.expiresWithin(expiry, today, rule.monthsRequired)
            ExpiryCandidate(item, expiry, days, latest.ambiguous, breaches)
        }.sortedBy { it.daysRemaining }

    /**
     * The real radar: the document-type template's judgment decides what each candidate is, the
     * arithmetic decides whether it breaches the rule. A non-persisting re-check — these decisions
     * are not written to the user's ledger.
     */
    private fun expiryAlerts(candidates: List<SourceItem>, backend: Backend, today: LocalDate, rule: ValidityRule, policy: RunPolicy): List<ExpiryAlert> {
        if (candidates.isEmpty()) return emptyList()
        val template = TemplateLibrary.byId("document-type")!!
        val judgment = (template.instantiate("watcher-document-type") as Template.InstantiateResult.Created).judgment.choice
        val engine = DecisionEngine(
            backend, Probability.of(policy.threshold(EXPIRY_THRESHOLD)),
            stateBudget = policy.budget(DecisionEngine.DEFAULT_STATE_BUDGET),
        )
        return ExpiryRadar.scan(candidates.map { it.toItem() }, judgment, engine, rule, today, judgment.candidates.toSet() - "none of these")
    }

    /** (item, charge) pairs from emails that record a payment and from money CSVs. */
    fun charges(items: List<SourceItem>): List<Pair<SourceItem, Charge>> {
        val out = mutableListOf<Pair<SourceItem, Charge>>()
        for (item in items) {
            val email = item.email
            if (item.kind == ItemKind.EMAIL && email != null) {
                val body = item.text.substringAfter("\n\n", item.text)
                if (!CHARGE_WORDS.containsMatchIn(body)) continue
                val amount = AMOUNT.find(body)?.groupValues?.get(1) ?: continue
                val date = email.date ?: item.date ?: continue
                val merchant = email.fromName ?: email.fromAddress?.substringAfter('@') ?: continue
                out += item to Charge(merchant, date, minor(amount))
            } else if (item.kind == ItemKind.CSV && item.facts["amount_minor"] != null) {
                // An Inbox CSV row (epic #7 child 15): the statement facts were read at import.
                val minor = item.facts["amount_minor"]?.toLongOrNull() ?: continue
                val merchant = item.facts["merchant"] ?: continue
                val date = item.date ?: continue
                if (item.duplicateOf != null) continue
                out += item to Charge(merchant, date, kotlin.math.abs(minor))
            } else if (item.kind == ItemKind.CSV) {
                out += csvCharges(item)
            }
        }
        return out
    }

    private fun csvCharges(item: SourceItem): List<Pair<SourceItem, Charge>> {
        val lines = item.text.substringAfter("\n\n", item.text).lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = lines.first().split(',').map { it.trim().lowercase() }
        val m = header.indexOfFirst { it == "merchant" || it == "payee" || it == "description" }
        val d = header.indexOfFirst { it == "date" }
        val a = header.indexOfFirst { it.startsWith("amount") }
        if (m < 0 || d < 0 || a < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val cells = line.split(',').map { it.trim() }
            val date = cells.getOrNull(d)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val amount = cells.getOrNull(a)?.removePrefix("-")?.takeIf { it.matches(Regex("""\d[\d]*(\.\d{1,2})?""")) } ?: return@mapNotNull null
            val merchant = cells.getOrNull(m)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            item to Charge(merchant, date, minor(amount))
        }
    }

    private fun minor(raw: String): Long {
        val parts = raw.replace(",", "").split(".")
        val major = parts[0].toLong()
        val cents = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0L
        return major * 100 + cents
    }

    /** Pairs of versions of one document, each compared by `TermChangeDetector`. */
    fun termChanges(items: List<SourceItem>): List<TermChangeFinding> {
        val groups = items.groupBy { item ->
            if (item.kind == ItemKind.EMAIL) {
                "mail:" + item.email?.fromAddress
            } else {
                "file:" + item.path.substringBeforeLast('/', "") + "/" + item.fileName.replace(Regex("""\d+"""), "#")
            }
        }
        val out = mutableListOf<TermChangeFinding>()
        for ((_, versions) in groups) {
            if (versions.size < 2) continue
            val ordered = versions.sortedWith(compareBy<SourceItem>({ it.date }, { it.id }))
            for ((earlier, later) in ordered.zipWithNext()) {
                val comparison = TermChangeDetector.compare(earlier.text, later.text)
                if (comparison.changed.isNotEmpty()) out += TermChangeFinding(earlier, later, comparison.changed)
            }
        }
        return out.sortedByDescending { f -> f.changes.maxOf { kotlin.math.abs(it.percentChange ?: 0.0) } }
    }

    /**
     * `Impersonation.check` for each email, against contacts inferred from the other emails. The
     * "first contact from this address" signal alone fires for every new sender, so it is reported
     * only beside a stronger one.
     */
    fun impersonation(emails: List<SourceItem>): List<ImpersonationFinding> = impersonation(emails, emptyList())

    /**
     * Known contacts from the address book: each contact card (kind CONTACT) with a name and at least
     * one email address. Phone numbers are not used — nothing reads SMS on iOS.
     */
    fun addressBook(items: List<SourceItem>): List<Contact> =
        items.filter { it.kind == ItemKind.CONTACT }.mapNotNull { item ->
            val addresses = item.facts["emails"]?.split(',')?.map { it.trim().lowercase() }?.filter { '@' in it }?.toSet().orEmpty()
            if (item.name.isBlank() || addresses.isEmpty()) null else Contact(item.name, addresses)
        }

    /** As [impersonation], with [book] (the address book) merged into the contacts inferred from history. */
    fun impersonation(emails: List<SourceItem>, book: List<Contact>): List<ImpersonationFinding> {
        val messages = emails.map { it to Message(it.email!!.fromName ?: "", it.email!!.fromAddress!!, it.text) }
        return messages.mapNotNull { (item, message) ->
            if (message.displayName.isBlank()) return@mapNotNull null
            val others = messages.filter { it.first !== item }.map { it.second }
            val contacts = contactsFrom(others, book)
            val signals = Impersonation.check(message, contacts, others)
            val domain = message.address.substringAfter('@').lowercase()
            val knownDomains = contacts.firstOrNull { it.name.equals(message.displayName, ignoreCase = true) }
                ?.addresses?.map { it.substringAfter('@').lowercase() }?.toSet().orEmpty()
            // An organisation writing from a second address on its own domain (receipts@ and
            // support@) is not a mismatch worth a warning. On a shared webmail domain it can be —
            // anyone can register a second gmail.com address — so there the signal stands.
            val sameOwnDomain = domain in knownDomains && domain !in WEBMAIL
            val strong = signals.filter {
                it.reason != ImpersonationReason.FIRST_CONTACT_FROM_ADDRESS &&
                    !(sameOwnDomain && it.reason == ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS)
            }
            if (strong.isEmpty()) null else ImpersonationFinding(item, signals)
        }
    }

    /**
     * Known contacts for judging one message: a display name used at least twice from the same
     * address in [others] (history), merged with the address [book].
     */
    fun contactsFrom(others: List<Message>, book: List<Contact>): List<Contact> {
        val inferred = others.filter { it.displayName.isNotBlank() }
            .groupBy { it.displayName.lowercase() }
            .mapNotNull { (_, byName) ->
                val trusted = byName.groupingBy { it.address.lowercase() }.eachCount().filterValues { it >= 2 }.keys
                if (trusted.isEmpty()) null else Contact(byName.first().displayName, trusted)
            }
        return (inferred + book).groupBy { it.name.lowercase() }
            .map { (_, same) -> Contact(same.first().name, same.flatMap { it.addresses }.map { it.lowercase() }.toSet()) }
    }

    /**
     * The shared formula on every link in an email, and on the sender's own domain, with the brand
     * the sender claims; a finding wherever the verdict is caution or danger.
     */
    fun fraud(emails: List<SourceItem>): List<FraudFinding> {
        val out = mutableListOf<FraudFinding>()
        for (item in emails) {
            val email = item.email!!
            val address = email.fromAddress ?: continue
            val brand = claimedBrand(email.fromName, address)
            val sender = SiteScoring.verdict(PageFacts("https://" + address.substringAfter('@').lowercase(), claimedBrand = brand))
            if (sender.level != "safe") out += FraudFinding(item, "sender $address", sender)
            for (link in email.links) {
                val verdict = SiteScoring.verdict(PageFacts(link, claimedBrand = brand))
                if (verdict.level != "safe") out += FraudFinding(item, "link $link", verdict)
            }
        }
        return out
    }

    /**
     * The brand a sender claims, when it plainly claims one: the first word of its display name,
     * if the name or the address reads as an institution ("PayPal Security", `billing@…`). A person
     * ("Mum") claims no brand, so no brand check runs for them.
     */
    fun claimedBrand(displayName: String?, address: String): String? {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return null
        val local = address.substringBefore('@').lowercase()
        if (local !in INSTITUTIONAL_LOCAL && !INSTITUTIONAL_NAME.containsMatchIn(name)) return null
        val first = name.split(Regex("""\s+""")).first().lowercase().filter { it.isLetterOrDigit() }
        return first.takeIf { it.length >= 3 && OriginFacts.labels(it).isNotEmpty() }
    }
}
