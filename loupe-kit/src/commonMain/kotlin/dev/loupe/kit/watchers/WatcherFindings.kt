package dev.loupe.kit.watchers

import dev.loupe.engine.Cadence
import dev.loupe.persistence.CorrectionKey
import dev.loupe.persistence.CorrectionRecord
import dev.loupe.sources.common.SourceItem
import kotlin.math.abs
import kotlin.math.roundToLong

/** The five watchers, in the order their findings rank on Now. */
enum class WatcherKind(val id: String, val title: String) {
    IMPERSONATION("impersonation", "Person impersonation"),
    SITE_FRAUD("site-fraud", "Site fraud"),
    EXPIRY("expiry", "Expiry radar"),
    TERM_CHANGE("term-change", "Term change"),
    RECURRING("recurring", "Recurring money"),
}

/** How the user answered a finding. Written to the corrections log, never rewritten. */
enum class FindingVerdict(val label: String, val title: String) {
    CONFIRMED("confirmed", "Confirm"),
    DISMISSED("dismissed", "Dismiss"),
    NOT_RELEVANT("not relevant", "Not relevant"),
}

/**
 * One thing a watcher raised, ready to show: the watcher, the evidence (the actual lines and fields
 * from the items), why it was raised and how sure that is, and the item to open. Warnings only —
 * a finding never says anything is fine.
 */
data class WatcherFinding(
    /** Stable across runs: the corrections log keys verdicts by it. */
    val key: String,
    val watcher: WatcherKind,
    val title: String,
    val evidence: List<String>,
    /** Why it was raised and how sure that is, in plain words. */
    val why: String,
    /** The item to open (the later version, for a term change). */
    val itemId: String,
    val itemName: String,
    /** The earlier version, for a term change. */
    val otherItemId: String?,
    /** True when every item behind it is from the synthetic sample: shown as "Sample". */
    val sample: Boolean,
    /** The user's latest verdict, or null. */
    val verdict: FindingVerdict? = null,
) {
    val watcherTitle: String get() = watcher.title
    val watcherId: String get() = watcher.id
}

/** One merchant in the subscriptions census. */
data class CensusRow(
    val merchant: String,
    val cadence: String,
    val occurrences: Int,
    val typicalMinor: Long,
    val lastChargedIso: String,
    val daysSinceLastCharge: Long,
    /** The typical charge brought to a month (weekly × 52/12, quarterly ÷ 3, annual ÷ 12); null when irregular. */
    val monthlyMinor: Long?,
    val sample: Boolean,
)

/** The recurring-money census, as Now shows it. */
data class SubscriptionCensus(
    val rows: List<CensusRow>,
    /** Sum of [CensusRow.monthlyMinor] over rows with a regular cadence. */
    val monthlyTotalMinor: Long,
    val chargesFound: Int,
    val sample: Boolean,
)

/** What Now shows from one watcher run. */
data class WatcherSummary(
    /** Findings not dismissed or marked not relevant, most urgent first. */
    val findings: List<WatcherFinding>,
    /** How many the user has set aside (dismissed or not relevant). */
    val setAside: Int,
    val census: SubscriptionCensus,
    val modelRan: Boolean,
    val itemsChecked: Int,
    val emailsChecked: Int,
    val linksChecked: Int,
    val todayIso: String,
    val ruleName: String,
)

/**
 * Turns a [WatcherReport] into Now's findings list and census, and the user's verdicts into
 * correction records. Presentation only: no watcher rule or threshold lives here.
 */
object WatcherFindings {
    /** The corrections log's judgment id for a watcher's verdicts: `watcher:<id>`. */
    fun judgmentId(kind: WatcherKind): String = "watcher:" + kind.id

    /** Verdicts are keyed by this, not by a judgment's criteria hash: the watchers have no wording. */
    const val CRITERIA = "watcher-v1"

    private val EXPIRY_LINE = Regex("""\b(expir\w*|valid until|valid to|valid thru|renewal date|4b\.)""", RegexOption.IGNORE_CASE)
    private val MONEY = Regex("""([£$€])\s?(\d[\d,]*(?:\.\d{1,2})?)""")

    fun summarise(
        report: WatcherReport,
        items: List<SourceItem>,
        sampleSourceIds: Set<String>,
        corrections: Map<CorrectionKey, String> = emptyMap(),
    ): WatcherSummary {
        fun isSample(item: SourceItem) = item.sourceId in sampleSourceIds
        val census = census(report, items, ::isSample)
        val sampleMerchants = census.rows.filter { it.sample }.map { it.merchant }.toSet()
        val all = findings(report, ::isSample).map { f ->
            if (f.watcher == WatcherKind.RECURRING) f.copy(sample = f.itemName in sampleMerchants) else f
        }.map { f ->
            val label = corrections[CorrectionKey(judgmentId(f.watcher), CRITERIA, f.key)]
            f.copy(verdict = FindingVerdict.entries.firstOrNull { it.label == label })
        }
        val shown = all.filter { it.verdict == null || it.verdict == FindingVerdict.CONFIRMED }
        return WatcherSummary(
            findings = shown,
            setAside = all.size - shown.size,
            census = census,
            modelRan = report.expiryAlerts != null,
            itemsChecked = items.count { it.hasText && it.duplicateOf == null },
            emailsChecked = report.emailsChecked,
            linksChecked = report.linksChecked,
            todayIso = report.today.toString(),
            ruleName = report.rule.name,
        )
    }

    /** The correction record for a verdict (`at` is an ISO instant from the caller's clock). */
    fun correction(finding: WatcherFinding, verdict: FindingVerdict, at: String): CorrectionRecord =
        CorrectionRecord(judgmentId(finding.watcher), CRITERIA, finding.key, verdict.label, at, verdict == FindingVerdict.CONFIRMED)

    /** Undo: an appended retraction. */
    fun retraction(finding: WatcherFinding, at: String): CorrectionRecord =
        CorrectionRecord(judgmentId(finding.watcher), CRITERIA, finding.key, null, at, false)

    fun findings(report: WatcherReport, isSample: (SourceItem) -> Boolean): List<WatcherFinding> {
        val out = mutableListOf<WatcherFinding>()

        for (f in report.impersonation) {
            val email = f.item.email!!
            out += WatcherFinding(
                key = "impersonation:" + f.item.id,
                watcher = WatcherKind.IMPERSONATION,
                title = "“${email.fromName}” wrote from an address they have not used before",
                evidence = listOf("From: “${email.fromName}” <${email.fromAddress}>") +
                    (email.subject?.let { listOf("Subject: $it") } ?: emptyList()) +
                    f.signals.map { it.detail },
                why = "Mechanical: the name is matched against addresses seen in your mail (a name used twice from one address counts as known). " +
                    "A signal, not a verdict — check with them another way before acting on it.",
                itemId = f.item.id,
                itemName = f.item.name,
                otherItemId = null,
                sample = isSample(f.item),
            )
        }

        for ((item, group) in report.fraud.groupBy { it.item.id }.map { (_, g) -> g.first().item to g }) {
            val email = item.email!!
            out += WatcherFinding(
                key = "site-fraud:" + item.id,
                watcher = WatcherKind.SITE_FRAUD,
                title = "“${email.fromName ?: email.fromAddress}”: " +
                    (if (group.any { it.verdict.level == "danger" }) "danger" else "caution") + " — a link or the sender's domain looks like phishing",
                evidence = listOf("From: ${email.fromName?.let { "“$it” " } ?: ""}<${email.fromAddress}>") +
                    group.flatMap { g -> g.verdict.reasons.filter { it.weight > 0 }.map { r -> "${g.what.substringBefore(' ')} ${g.what.substringAfter(' ')}: ${r.text}" } },
                why = "Mechanical: origin facts only, scored by the one phishing formula shared with Loupe Station " +
                    "(${group.joinToString(", ") { "${it.verdict.level} ${it.verdict.score}" }}), never the page's content. Nothing here can clear a link.",
                itemId = item.id,
                itemName = item.name,
                otherItemId = null,
                sample = isSample(item),
            )
        }

        val alerts = report.expiryAlerts?.associateBy { it.itemId }
        for (c in report.expiryCandidates.filter { it.breachesRule }) {
            val alert = alerts?.get(c.item.id)
            val line = c.item.text.lines().firstOrNull { EXPIRY_LINE.containsMatchIn(it) }?.trim()
            val days = c.daysRemaining
            val when_ = if (days < 0) "expired ${-days} days ago" else "expires in $days days"
            val why = when {
                alerts == null -> "The date is arithmetic, so it is certain; what the document is has not been judged (the model is not loaded)."
                alert != null -> "Laya judged this a ${alert.documentType}; the date and the rule are arithmetic."
                else -> "Laya did not judge this an expiring document of a listed type. Shown anyway: a missed expiry costs more than a false alarm."
            }
            out += WatcherFinding(
                key = "expiry:" + c.item.id,
                watcher = WatcherKind.EXPIRY,
                title = "${alert?.documentType?.replaceFirstChar { it.uppercase() } ?: c.item.name} $when_",
                evidence = listOfNotNull(
                    line,
                    "Expiry ${c.expiry} — $days days from ${report.today}",
                    "Inside the rule: ${report.rule.name}",
                    if (c.ambiguous) "The date could be read two ways; the earlier reading was used." else null,
                ),
                why = why,
                itemId = c.item.id,
                itemName = c.item.name,
                otherItemId = null,
                sample = isSample(c.item),
            )
        }

        for (t in report.termChanges) {
            for (ch in t.changes) {
                val symbol = symbolFor(t.later.text, ch.afterMinor) ?: symbolFor(t.earlier.text, ch.beforeMinor) ?: ""
                val pct = ch.percentChange?.let { " (${signed(it)}%)" } ?: ""
                val label = ch.label.replaceFirstChar { it.uppercase() }
                out += WatcherFinding(
                    key = "term-change:${t.earlier.id}>${t.later.id}:${ch.label}",
                    watcher = WatcherKind.TERM_CHANGE,
                    title = "$label ${if (ch.increased) "up" else "down"}${ch.percentChange?.let { " ${signed(abs(it)).removePrefix("+")}%" } ?: ""}",
                    evidence = listOf(
                        "$label: $symbol${money(ch.beforeMinor)} → $symbol${money(ch.afterMinor)}$pct",
                        "${t.earlier.name} → ${t.later.name}",
                    ),
                    why = "Arithmetic on the labelled amounts in two versions of one document. No model involved.",
                    itemId = t.later.id,
                    itemName = t.later.name,
                    otherItemId = t.earlier.id,
                    sample = isSample(t.earlier) && isSample(t.later),
                )
            }
        }
        // Increases first within the watcher: a price rise is the one that costs money.
        val (termUp, termOther) = out.filter { it.watcher == WatcherKind.TERM_CHANGE }.partition { " up" in it.title }
        val rest = out.filter { it.watcher != WatcherKind.TERM_CHANGE }

        val stale = report.recurring.filter { it.cadence == Cadence.MONTHLY && it.daysSinceLastCharge > 45 }.map { rc ->
            WatcherFinding(
                key = "recurring:" + rc.merchant,
                watcher = WatcherKind.RECURRING,
                title = "${rc.merchant}: no charge for ${rc.daysSinceLastCharge} days",
                evidence = listOf("Monthly, ${rc.occurrences} charges, typically ${money(rc.typicalAmountMinor)}, last ${rc.lastCharged}"),
                why = "Arithmetic on the charges found. It may have been cancelled — or be billed somewhere Loupe cannot see.",
                itemId = "",
                itemName = rc.merchant,
                otherItemId = null,
                // Set from the census's own items in [summarise].
                sample = false,
            )
        }
        return (rest + termUp + termOther + stale).sortedBy { it.watcher.ordinal }
    }

    fun census(report: WatcherReport, items: List<SourceItem>, isSample: (SourceItem) -> Boolean): SubscriptionCensus {
        val texty = items.filter { it.hasText && it.duplicateOf == null }
        val byMerchant = WatcherRun.charges(texty).groupBy({ it.second.merchant }, { it.first })
        val rows = report.recurring.map { rc ->
            CensusRow(
                merchant = rc.merchant,
                cadence = rc.cadence.name.lowercase(),
                occurrences = rc.occurrences,
                typicalMinor = rc.typicalAmountMinor,
                lastChargedIso = rc.lastCharged.toString(),
                daysSinceLastCharge = rc.daysSinceLastCharge,
                monthlyMinor = monthly(rc.cadence, rc.typicalAmountMinor),
                sample = byMerchant[rc.merchant].orEmpty().let { it.isNotEmpty() && it.all(isSample) },
            )
        }
        return SubscriptionCensus(
            rows = rows,
            monthlyTotalMinor = rows.sumOf { it.monthlyMinor ?: 0L },
            chargesFound = report.chargesFound,
            sample = rows.isNotEmpty() && rows.all { it.sample },
        )
    }

    fun monthly(cadence: Cadence, typicalMinor: Long): Long? = when (cadence) {
        Cadence.WEEKLY -> (typicalMinor * 52.0 / 12.0).roundToLong()
        Cadence.MONTHLY -> typicalMinor
        Cadence.QUARTERLY -> (typicalMinor / 3.0).roundToLong()
        Cadence.ANNUAL -> (typicalMinor / 12.0).roundToLong()
        Cadence.IRREGULAR -> null
    }

    /** `553.50` from 55350. */
    fun money(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val m = abs(minor)
        return "$sign${m / 100}.${(m % 100).toString().padStart(2, '0')}"
    }

    /** `+23`, `-4.5`: whole when whole, else one decimal. */
    fun signed(pct: Double): String {
        val tenths = (pct * 10).roundToLong()
        val body = if (tenths % 10 == 0L) "${abs(tenths) / 10}" else "${abs(tenths) / 10}.${abs(tenths) % 10}"
        return (if (tenths > 0) "+" else if (tenths < 0) "-" else "") + body
    }

    private fun symbolFor(text: String, minor: Long): String? =
        MONEY.findAll(text).firstOrNull { m ->
            val parts = m.groupValues[2].replace(",", "").split(".")
            val value = parts[0].toLongOrNull()?.let { it * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L) }
            value == minor
        }?.groupValues?.get(1)
}
