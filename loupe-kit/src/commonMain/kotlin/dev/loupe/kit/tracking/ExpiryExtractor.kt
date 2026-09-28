package dev.loupe.kit.tracking

import dev.loupe.engine.DateFacts
import dev.loupe.engine.DateMatch
import dev.loupe.engine.ValidityRule
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate

/** A dated document on the expiry timeline, with the rules' kind and the line the date was read from. */
data class ExpiryFind(
    val item: SourceItem,
    val expiry: LocalDate,
    val daysRemaining: Long,
    val ambiguous: Boolean,
    /** Inside the validity rule: a highlight on the timeline, and a finding. */
    val breachesRule: Boolean,
    val kind: DocumentKind?,
    val line: String?,
)

/**
 * The timeline's groups: overdue (expired within the last 12 months), 0-7 days, 8-30 days, later,
 * and older (expired more than 12 months ago; shown collapsed).
 */
enum class ExpiryBucket(val id: String) {
    OVERDUE("overdue"), WEEK("week"), MONTH("month"), LATER("later"), OLDER("older");

    companion object {
        /** How long ago is "overdue" before it drops into the collapsed [OLDER] group. */
        const val OLDER_AFTER_DAYS: Long = 365

        fun of(daysRemaining: Long): ExpiryBucket = when {
            daysRemaining < -OLDER_AFTER_DAYS -> OLDER
            daysRemaining < 0 -> OVERDUE
            daysRemaining <= 7 -> WEEK
            daysRemaining <= 30 -> MONTH
            else -> LATER
        }
    }
}

/**
 * The expiry radar's mechanical half (spec §7.2): every item with an expiry word and its date, on a full timeline
 * (no one-year cut; the validity rule is a highlight, not a filter). The date is the first one within [WINDOW]
 * characters after an expiry word; without one, a document of a known kind takes its latest date, and anything
 * else is left out (a receipt's "تنتهي بـ ٧٧٢٠" is a card number). An offer's "expires" is left out unless the item
 * is a known kind of document. Ambiguous dates take the earlier reading, as `ExpiryRadar` does.
 *
 * Every candidate is kept, however long ago it expired — `ExpiryBucket.of` is what sorts an item into the
 * collapsed "older" group, not this extractor.
 */
object ExpiryExtractor {
    const val WINDOW: Int = 60

    fun find(items: List<SourceItem>, today: LocalDate, rule: ValidityRule): List<ExpiryFind> =
        items.mapNotNull { of(it, today, rule) }.sortedWith(compareBy<ExpiryFind>({ it.daysRemaining }, { it.item.id }))

    fun of(item: SourceItem, today: LocalDate, rule: ValidityRule): ExpiryFind? {
        if (!item.hasText || item.duplicateOf != null || item.kind == ItemKind.CONTACT) return null
        val text = item.text
        val form = TrackingText.matchForm(text)
        val words = ExpiryLexicon.EXPIRY.findAll(form).toList()
        if (words.isEmpty()) return null
        val kind = DocumentKinds.of(text)
        if (kind == null && ExpiryLexicon.PROMO.containsMatchIn(form)) return null
        var picked: DateMatch? = null
        var lineAt = words.first().range.first
        for (w in words) {
            val start = w.range.last + 1
            val d = TrackingDates.find(text.substring(start, minOf(text.length, start + WINDOW))).firstOrNull() ?: continue
            picked = d
            lineAt = w.range.first
            break
        }
        val chosen = picked ?: (if (kind != null) TrackingDates.find(text).maxByOrNull { it.date } else null) ?: return null
        val expiry = listOfNotNull(chosen.date, chosen.alternate).min()
        return ExpiryFind(
            item = item,
            expiry = expiry,
            daysRemaining = DateFacts.daysUntil(expiry, today),
            ambiguous = chosen.ambiguous,
            breachesRule = DateFacts.expiresWithin(expiry, today, rule.monthsRequired),
            kind = kind,
            line = MoneyReader.lineAt(text, lineAt).trim().ifEmpty { null },
        )
    }
}
