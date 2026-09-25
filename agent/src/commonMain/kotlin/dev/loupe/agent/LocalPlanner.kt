package dev.loupe.agent

import dev.loupe.engine.Cadence
import dev.loupe.engine.ExpiryAlert
import dev.loupe.engine.RecurringCharge
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * The middle tier: prepared actions from what the engine already knows, with **no network at all**.
 *
 * Every action it returns carries [ActionOrigin.OnDevice], so none of them is labelled Online,
 * because none of them went anywhere. Nothing here builds an [AgentCall]; nothing here can.
 *
 * It runs the same never list as the provider-backed tier — `AgentRunner.planLocally` hands its
 * output to [ActionGuard] exactly as `AgentSession` does — because the rules are about what Loupe
 * puts in front of a person, not about who wrote it. A reminder that quoted a card number found in
 * a document would be just as wrong for having been prepared locally.
 */
object LocalPlanner {
    /** How far ahead of an expiry to remind, by default. */
    const val REMIND_DAYS_BEFORE: Int = 30

    /** A subscription untouched for this long is worth mentioning. */
    const val DORMANT_DAYS: Long = 90

    /**
     * A reminder for a document about to lapse, or a note when the date could be read two ways.
     *
     * The ambiguity branch is the never list applied to arithmetic rather than to a model:
     * `ExpiryAlert.dateWasAmbiguous` means the day is not certain, and *"never acts on something it
     * is unsure about"* does not stop being true because the uncertainty came from a date format
     * instead of a distribution. So an ambiguous date produces a note saying so, and never a
     * reminder on a day that might be the wrong one.
     *
     * The document *type* behind the alert already came from a `Decision.Act` — `ExpiryRadar.scan`
     * raises nothing on an abstention — so the judgment half is as gated as the agent tier's own
     * bar. The date half is arithmetic on the item's own text, which is why the evidence says so.
     */
    fun fromExpiry(
        alert: ExpiryAlert,
        today: LocalDate,
        remindDaysBefore: Int = REMIND_DAYS_BEFORE,
    ): PreparedAction {
        val evidence = AgentEvidence.mechanical(
            itemId = alert.itemId,
            source = "expiry-radar",
            fact = "${alert.documentType} expires ${alert.expiry}",
        )
        if (alert.dateWasAmbiguous) {
            return PreparedAction.NoteFinding(
                evidence = evidence,
                headline = "Your ${alert.documentType} may expire around ${alert.expiry}",
                detail = "The date on it could be read two ways, so no reminder was set — check the " +
                    "document and set one yourself. ${alert.rule.name} needs " +
                    "${alert.rule.monthsRequired} months of validity, and this reading leaves " +
                    "${alert.daysRemaining} days.",
                origin = ActionOrigin.OnDevice,
            )
        }
        val wanted = alert.expiry.minus(DatePeriod(days = remindDaysBefore.coerceAtLeast(0)))
        // Never a reminder in the past: if the lead time has already gone, remind today.
        val on = if (wanted < today) today else wanted
        return PreparedAction.Remind(
            evidence = evidence,
            whenIso = on.toString(),
            text = "Renew your ${alert.documentType} — it expires ${alert.expiry}",
            because = "${alert.rule.name} needs ${alert.rule.monthsRequired} months of validity, and " +
                "this one has ${alert.daysRemaining} days left.",
            origin = ActionOrigin.OnDevice,
        )
    }

    /**
     * A note about a subscription that has been billing without being used.
     *
     * A note and not a reminder, and certainly not a cancellation: Loupe never spends money, and it
     * does not un-spend it either — cancelling is the person's call, and the last button is theirs.
     */
    fun fromDormantSubscription(
        charge: RecurringCharge,
        itemId: String,
        dormantDays: Long = DORMANT_DAYS,
    ): PreparedAction? {
        if (charge.daysSinceLastCharge < 0) return null
        if (charge.cadence == Cadence.IRREGULAR) return null
        val total = money(charge.totalMinor())
        return PreparedAction.NoteFinding(
            evidence = AgentEvidence.mechanical(
                itemId = itemId,
                source = "recurring-money",
                fact = "${charge.merchant} bills ${charge.cadence.name.lowercase()}",
            ),
            headline = "${charge.merchant} has billed ${charge.occurrences} times — ${money(charge.typicalAmountMinor)} each",
            detail = "Last charge ${charge.lastCharged} (${charge.daysSinceLastCharge} days ago), " +
                "${charge.cadence.name.lowercase()}, $total in total so far. " +
                if (charge.daysSinceLastCharge >= dormantDays) {
                    "Nothing has moved for ${charge.daysSinceLastCharge} days."
                } else {
                    "Still active."
                },
            origin = ActionOrigin.OnDevice,
        )
    }

    /**
     * Everything the device can prepare on its own from one scan's findings.
     *
     * Unguarded: the caller runs [ActionGuard], and `AgentRunner.planLocally` does.
     */
    fun plan(
        expiries: List<ExpiryAlert> = emptyList(),
        subscriptions: List<Pair<RecurringCharge, String>> = emptyList(),
        today: LocalDate,
        remindDaysBefore: Int = REMIND_DAYS_BEFORE,
        dormantDays: Long = DORMANT_DAYS,
    ): List<PreparedAction> {
        val out = mutableListOf<PreparedAction>()
        for (alert in expiries) out += fromExpiry(alert, today, remindDaysBefore)
        for ((charge, itemId) in subscriptions) {
            if (charge.daysSinceLastCharge >= dormantDays) {
                fromDormantSubscription(charge, itemId, dormantDays)?.let { out += it }
            }
        }
        return out
    }

    /** Minor units as major.minor, with no currency symbol — the charges do not carry one. */
    internal fun money(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val abs = if (minor < 0) -minor else minor
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
