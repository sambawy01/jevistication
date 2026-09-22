package dev.loupe.engine

import java.time.LocalDate

/** A validity rule, such as "Schengen requires six months of remaining passport validity". */
data class ValidityRule(val name: String, val monthsRequired: Long) {
    init {
        require(monthsRequired >= 0) { "monthsRequired must not be negative" }
    }
}

/** A document whose expiry breaches a rule. */
data class ExpiryAlert(
    val itemId: String,
    /** The document type the judgment chose. */
    val documentType: String,
    val expiry: LocalDate,
    val daysRemaining: Long,
    val rule: ValidityRule,
    /**
     * True when the date on the document could be read two ways and the earlier reading was taken.
     * The alert is still raised — it is simply less certain about the exact day.
     */
    val dateWasAmbiguous: Boolean,
)

/**
 * The obligation and expiry radar (C3).
 *
 * The split is the point: deciding *what a document is* is a judgment, and the model does that;
 * finding the date on it and comparing it to a rule is arithmetic, and the model never touches
 * that. Missing a visa renewal is catastrophic and no product covers it today, so the arithmetic
 * half must not be able to go wrong.
 */
object ExpiryRadar {

    /**
     * Scans [items], asking [judgment] what each document is and raising an alert when a document
     * of an [expiringTypes] type breaches [rule].
     *
     * Where a document carries several dates the **latest** is taken as the expiry, since issue
     * dates precede expiry dates. Where a date is ambiguous the **earlier** reading is used: for
     * an expiry, warning too early is recoverable and warning too late is not.
     *
     * Items the engine abstains on raise nothing — an uncertain document type is not a basis for
     * telling someone their passport is about to lapse.
     */
    fun scan(
        items: List<Item>,
        judgment: Judgment.Choice,
        engine: DecisionEngine,
        rule: ValidityRule,
        today: LocalDate,
        expiringTypes: Set<String> = judgment.candidates.toSet() - "none",
    ): List<ExpiryAlert> {
        val alerts = mutableListOf<ExpiryAlert>()

        for (item in items) {
            val decision = engine.decide(judgment, item).decision
            if (decision !is Decision.Act) continue
            if (decision.label !in expiringTypes) continue

            val dates = DateFacts.find(item.text)
            if (dates.isEmpty()) continue

            val latest = dates.maxBy { it.date }
            // Conservative reading: for an expiry, earlier is the safe error.
            val expiry = listOfNotNull(latest.date, latest.alternate).min()

            if (!DateFacts.expiresWithin(expiry, today, rule.monthsRequired)) continue

            alerts += ExpiryAlert(
                itemId = item.id,
                documentType = decision.label,
                expiry = expiry,
                daysRemaining = DateFacts.daysUntil(expiry, today),
                rule = rule,
                dateWasAmbiguous = latest.ambiguous,
            )
        }
        return alerts.sortedBy { it.daysRemaining }
    }
}
