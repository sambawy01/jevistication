package dev.loupe.desktop.core

import dev.loupe.engine.Backend
import dev.loupe.engine.Charge
import dev.loupe.engine.ExpiryAlert
import dev.loupe.kit.site.SiteVerdict
import dev.loupe.engine.ImpersonationSignal
import dev.loupe.engine.RecurringCharge
import dev.loupe.engine.TermChange
import dev.loupe.engine.ValidityRule
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.sources.SourceItem
import java.time.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import dev.loupe.sources.common.DateOrigin as CommonDateOrigin
import dev.loupe.sources.common.EmailFacts as CommonEmailFacts
import dev.loupe.sources.common.ItemKind as CommonItemKind
import dev.loupe.sources.common.SourceItem as CommonSourceItem

/** A document with an expiry-like date, found mechanically. */
data class ExpiryCandidate(val item: SourceItem, val expiry: LocalDate, val daysRemaining: Long, val ambiguous: Boolean, val breachesRule: Boolean)

/** Two versions of one document whose labelled amounts moved. */
data class TermChangeFinding(val earlier: SourceItem, val later: SourceItem, val changes: List<TermChange>)

/** A message whose sender does not add up. */
data class ImpersonationFinding(val item: SourceItem, val signals: List<ImpersonationSignal>)

/** A link, or a sender domain, with mechanical fraud signals. */
/** A link or sender domain the shared phishing formula rates caution or danger (docs/PHISHING-FORMULA.md). */
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
)

/**
 * Runs the five watchers over scanned items (C3). The orchestration now lives in LoupeKit's common
 * `WatcherRun` (shared with the iPhone, epic #7 child 5); this adapts the desktop's items to the
 * common model, delegates, and maps the findings back. No rule or threshold lives here.
 */
object Watchers {
    /** "Schengen requires six months of passport validity" — the spec's own example rule. */
    val SIX_MONTHS: ValidityRule = WatcherRun.SIX_MONTHS

    fun run(items: List<SourceItem>, today: LocalDate, backend: Backend?, rule: ValidityRule = SIX_MONTHS): WatcherReport {
        val byId = items.associateBy { it.id }
        val r = WatcherRun.run(items.map { it.toCommon() }, today.toKotlinLocalDate(), backend, rule)
        fun back(c: CommonSourceItem): SourceItem = byId.getValue(c.id)
        return WatcherReport(
            today = today,
            rule = r.rule,
            expiryCandidates = r.expiryCandidates.map { ExpiryCandidate(back(it.item), it.expiry.toJavaLocalDate(), it.daysRemaining, it.ambiguous, it.breachesRule) },
            expiryAlerts = r.expiryAlerts,
            recurring = r.recurring,
            chargesFound = r.chargesFound,
            termChanges = r.termChanges.map { TermChangeFinding(back(it.earlier), back(it.later), it.changes) },
            impersonation = r.impersonation.map { ImpersonationFinding(back(it.item), it.signals) },
            fraud = r.fraud.map { FraudFinding(back(it.item), it.what, it.verdict) },
            emailsChecked = r.emailsChecked,
            linksChecked = r.linksChecked,
        )
    }

    /** (item, charge) pairs from emails that record a payment and from money CSVs. */
    fun charges(items: List<SourceItem>): List<Pair<SourceItem, Charge>> {
        val byId = items.associateBy { it.id }
        return WatcherRun.charges(items.map { it.toCommon() }).map { (c, charge) -> byId.getValue(c.id) to charge }
    }

    /** The brand a sender plainly claims, or null (see `WatcherRun.claimedBrand`). */
    fun claimedBrand(displayName: String?, address: String): String? = WatcherRun.claimedBrand(displayName, address)

    /** The desktop item as the common model sees it (the two are field-for-field twins; see ParityTest). */
    fun SourceItem.toCommon(): CommonSourceItem = CommonSourceItem(
        id = id,
        sourceId = sourceId,
        kind = CommonItemKind.valueOf(kind.name),
        path = path.toString(),
        messageIndex = messageIndex,
        name = name,
        text = text,
        hasText = hasText,
        textTruncated = textTruncated,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        mime = mime,
        date = date?.toKotlinLocalDate(),
        dateOrigin = dateOrigin?.let { CommonDateOrigin.valueOf(it.name) },
        email = email?.let { e ->
            CommonEmailFacts(e.fromName, e.fromAddress, e.to, e.subject, e.date?.toKotlinLocalDate(), e.links, e.attachmentNames)
        },
        facts = facts,
        duplicateOf = duplicateOf,
    )
}
