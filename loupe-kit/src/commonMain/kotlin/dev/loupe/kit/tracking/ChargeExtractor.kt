package dev.loupe.kit.tracking

import dev.loupe.engine.Charge
import dev.loupe.sources.common.CsvRows
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.abs

/** One charge, with where it was read: the item, the line, and any other item the same charge was seen in. */
data class TrackedCharge(
    val itemId: String,
    val kind: ItemKind,
    val merchant: String,
    val date: LocalDate,
    val amountMinor: Long,
    /** ISO 4217, or "" when the text named no currency. */
    val currency: String,
    /** The line the amount was read from, verbatim (the reference's "Read"). */
    val line: String,
    /** The text named a subscription or a recurring bill. */
    val saysSubscription: Boolean,
    /** Other items the same charge was seen in (a receipt email and its statement row). */
    val alsoSeenIn: List<String> = emptyList(),
) {
    fun toCharge(): Charge = Charge(merchant, date, amountMinor)
}

/**
 * Charges from every source (spec §7.2): email receipts, files (PDF, photo receipts, HTML, text), statement rows
 * (the Inbox's CSV rows, and whole CSV files read by `CsvRows`) and calendar events with an amount. Mechanical:
 * a charge needs an amount with its currency marker and a charge word, and no "not a charge" word on the amount's
 * line or the first line. The same charge seen in two items is kept once, with both references.
 */
object ChargeExtractor {
    /** Two sightings are one charge when merchant, currency and amount match and the dates are at most this far apart. */
    const val SAME_CHARGE_DAYS: Int = 3

    /** Which sighting is kept when a charge is seen twice: the richest evidence first. */
    private val PRIORITY = listOf(ItemKind.EMAIL, ItemKind.PDF, ItemKind.IMAGE, ItemKind.HTML, ItemKind.TEXT, ItemKind.MARKDOWN,
                                  ItemKind.JSON, ItemKind.EVENT, ItemKind.CSV)

    fun extract(items: List<SourceItem>): List<TrackedCharge> = dedupe(items.flatMap { of(it) })

    fun of(item: SourceItem): List<TrackedCharge> {
        if (item.duplicateOf != null) return emptyList()
        return when (item.kind) {
            ItemKind.CSV -> csv(item)
            ItemKind.CONTACT -> emptyList()
            ItemKind.EMAIL -> if (item.hasText) listOfNotNull(email(item)) else emptyList()
            else -> if (item.hasText) listOfNotNull(document(item)) else emptyList()
        }
    }

    private fun email(item: SourceItem): TrackedCharge? {
        val email = item.email ?: return null
        val body = item.text.substringAfter("\n\n", item.text)
        val read = email.subject.orEmpty() + "\n" + body
        val money = MoneyReader.best(body) ?: return null
        if (!isCharge(read, body, money)) return null
        val date = email.date ?: item.date ?: return null
        val merchant = MerchantHints.merchant(read)
            ?: email.fromName?.let { MerchantHints.canonical(it) }
            ?: email.fromAddress?.substringAfter('@')
            ?: return null
        return TrackedCharge(item.id, item.kind, merchant, date, money.minor, money.currency,
                             MoneyReader.lineAt(body, money.start).trim(), says(read))
    }

    private fun document(item: SourceItem): TrackedCharge? {
        val body = if (item.kind == ItemKind.IMAGE) item.text.substringAfter("\n\n", item.text) else item.text
        val money = MoneyReader.best(body) ?: return null
        if (!isCharge(body, body, money)) return null
        val date = (if (item.kind == ItemKind.EVENT) item.date else chargeDate(body, money)) ?: item.date ?: return null
        val merchant = MerchantHints.merchant(body)
            ?: (if (item.kind == ItemKind.EVENT) item.name.takeIf { it.isNotBlank() } else null)
            ?: MerchantHints.fromFirstLine(body)
            ?: return null
        return TrackedCharge(item.id, item.kind, merchant, date, money.minor, money.currency,
                             MoneyReader.lineAt(body, money.start).trim(), says(body))
    }

    private fun csv(item: SourceItem): List<TrackedCharge> {
        val minor = item.facts["amount_minor"]?.toLongOrNull()
        if (minor != null) {
            if (item.facts["direction"] == "credit" || minor == 0L) return emptyList()
            val raw = item.facts["merchant"] ?: item.facts["description"] ?: return emptyList()
            val date = item.date ?: return emptyList()
            val amount = item.facts["amount"] ?: CsvRows.formatMinor(minor)
            return listOf(TrackedCharge(item.id, item.kind, MerchantHints.canonical(raw), date, abs(minor),
                                        item.facts["currency"] ?: "", "$raw · $amount", false))
        }
        // A whole CSV file (a statement in Files): each debit row, all from this one item.
        val table = CsvRows.read(item.text.substringAfter("\n\n", item.text))
        return table.rows.mapNotNull { r ->
            val m = r.money ?: return@mapNotNull null
            if (m.direction == "credit" || m.amountMinor == 0L) return@mapNotNull null
            val raw = m.merchant ?: m.description ?: return@mapNotNull null
            TrackedCharge(item.id, item.kind, MerchantHints.canonical(raw), m.date, abs(m.amountMinor), m.currency ?: "",
                          r.cells.joinToString(", "), false)
        }
    }

    /** A charge word, and no "not a charge" word on the amount's line or the first line. */
    private fun isCharge(read: String, text: String, money: Money): Boolean {
        if (!ChargeLexicon.CHARGE.containsMatchIn(TrackingText.matchForm(read))) return false
        val first = read.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val around = TrackingText.matchForm(MoneyReader.lineAt(text, money.start) + "\n" + first)
        return !ChargeLexicon.NOT_A_CHARGE.containsMatchIn(around)
    }

    /** The date on the amount's line, else on a line with a charge word, else the first date in the text. */
    private fun chargeDate(text: String, money: Money): LocalDate? {
        TrackingDates.find(MoneyReader.lineAt(text, money.start)).firstOrNull()?.let { return it.date }
        for (line in text.lines()) {
            if (ChargeLexicon.CHARGE.containsMatchIn(TrackingText.matchForm(line))) {
                TrackingDates.find(line).firstOrNull()?.let { return it.date }
            }
        }
        return TrackingDates.find(text).firstOrNull()?.date
    }

    private fun says(text: String): Boolean = ChargeLexicon.SUBSCRIPTION.containsMatchIn(TrackingText.matchForm(text))

    /**
     * One charge per sighting group: same merchant (case-insensitive), currency and amount, dates at most
     * [SAME_CHARGE_DAYS] apart, from different items. The richest sighting is kept (see [PRIORITY]); the others'
     * items go to [TrackedCharge.alsoSeenIn]. Rows of one statement file are never merged with each other.
     */
    fun dedupe(charges: List<TrackedCharge>): List<TrackedCharge> {
        val out = mutableListOf<TrackedCharge>()
        for ((_, group) in charges.groupBy { Triple(it.merchant.lowercase(), it.currency, it.amountMinor) }) {
            val kept = mutableListOf<TrackedCharge>()
            for (c in group.sortedWith(compareBy<TrackedCharge>({ it.date }, { PRIORITY.indexOf(it.kind) }, { it.itemId }))) {
                val i = kept.indexOfLast { k ->
                    k.itemId != c.itemId && c.itemId !in k.alsoSeenIn && abs(k.date.daysUntil(c.date)) <= SAME_CHARGE_DAYS
                }
                if (i < 0) { kept += c; continue }
                val k = kept[i]
                val (keep, other) = if (PRIORITY.indexOf(c.kind) < PRIORITY.indexOf(k.kind)) c to k else k to c
                kept[i] = keep.copy(alsoSeenIn = (keep.alsoSeenIn + other.itemId + other.alsoSeenIn).distinct())
            }
            out += kept
        }
        return out.sortedWith(compareBy<TrackedCharge>({ it.date }, { it.merchant }, { it.itemId }))
    }
}
