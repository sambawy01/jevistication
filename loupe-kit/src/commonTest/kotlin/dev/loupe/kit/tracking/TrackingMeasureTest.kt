package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.WatcherRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measured bar (spec 2026-09-28 §7.3): recall ≥ 90 % for charges, subscriptions and expiring documents on the
 * labelled set, in every language with at least three positives; 0 false positives on the everyday items. Each
 * run prints its numbers per language ("tracking recall · charges · ar: 6/6").
 */
class TrackingMeasureTest {
    private val today = TrackingFixtures.TODAY
    private val fixtures by lazy { TrackingFixtures.load() }
    private val statement by lazy { TrackingFixtures.statementRows() }
    private val items by lazy { fixtures.map { it.item } + statement }
    private val charges by lazy { WatcherRun.trackedCharges(items) }
    private val report by lazy { WatcherRun.run(items, today, null) }

    /** The subscriptions the set holds: the census's merchant name, its currency, and the language of its items. */
    private val subscriptions = listOf(
        Triple("Netflix", "EGP", "en"), Triple("Spotify", "EGP", "ar"), Triple("WE", "EGP", "eg"), Triple("Vodafone", "EGP", "en"),
        Triple("نادي الجزيرة", "EGP", "ar"), Triple("Orange", "EGP", "eg"), Triple("Anghami", "EGP", "franco"),
        Triple("Shahid", "EGP", "en"), Triple("Gym membership", "EGP", "en"), Triple("iCloud", "USD", "en"),
    )

    /** Prints and returns hits / total per language, plus "all". */
    private fun tally(tracker: String, rows: List<Pair<String, Boolean>>): Map<String, Pair<Int, Int>> {
        val byLang = rows.groupBy({ it.first }, { it.second }).mapValues { (_, hits) -> hits.count { it } to hits.size }
        for ((lang, r) in byLang.entries.sortedBy { it.key }) println("tracking recall · $tracker · $lang: ${r.first}/${r.second}")
        val all = rows.count { it.second } to rows.size
        println("tracking recall · $tracker · all: ${all.first}/${all.second}")
        return byLang + ("all" to all)
    }

    private fun assertBar(tracker: String, tally: Map<String, Pair<Int, Int>>) {
        for ((lang, r) in tally) {
            if (r.second >= 3) assertTrue(r.first * 10 >= r.second * 9, "$tracker recall ($lang): ${r.first}/${r.second}")
        }
    }

    @Test
    fun theSetCoversWhatTheSpecAsksFor() {
        val negatives = fixtures.filter { it.expect == "none" }
        assertTrue(negatives.size >= 30, "30+ everyday items, have ${negatives.size}")
        val merchants = fixtures.mapNotNull { it.merchant }.toSet()
        for (m in listOf("Netflix", "Spotify", "WE", "Vodafone", "Orange", "نادي الجزيرة")) assertTrue(m in merchants, m)
        assertTrue(fixtures.any { it.id.startsWith("instapay-") } && fixtures.any { it.id.startsWith("fawry-") }, "InstaPay and Fawry receipts")
        val docs = fixtures.mapNotNull { it.doc }.toSet()
        for (d in listOf("national_id", "car_licence", "passport", "insurance")) assertTrue(d in docs, d)
        assertTrue(statement.size >= 8, "the bank statement's rows, have ${statement.size}")
        assertEquals(setOf("en", "ar", "eg", "franco"), fixtures.map { it.lang }.toSet())
    }

    @Test
    fun chargeRecallIsAtLeastNinetyPercentInEveryLanguage() {
        val rows = fixtures.filter { it.expect == "charge" }.map { f ->
            f.lang to charges.any { c ->
                (c.itemId == f.item.id || f.item.id in c.alsoSeenIn) && c.merchant == f.merchant &&
                    c.currency == f.currency && c.amountMinor == f.amountMinor
            }
        }
        assertBar("charges", tally("charges", rows))
    }

    @Test
    fun subscriptionRecallIsAtLeastNinetyPercent() {
        val found = report.recurring.map { it.merchant to report.currencyOf[it.merchant] }.toSet()
        val t = tally("subscriptions", subscriptions.map { (m, currency, lang) -> lang to ((m to currency) in found) })
        val all = t.getValue("all")
        assertTrue(all.first * 10 >= all.second * 9, "subscriptions ${all.first}/${all.second}; the census found $found")
    }

    @Test
    fun expiryRecallIsAtLeastNinetyPercentInEveryLanguage() {
        val positives = fixtures.filter { it.expect == "expiry" }
        assertBar("expiry", tally("expiry", positives.map { f ->
            f.lang to report.expiryCandidates.any { it.item.id == f.item.id && it.expiry == f.expiry }
        }))
        assertBar("document kind", tally("document kind", positives.filter { it.doc != null }.map { f ->
            f.lang to report.expiryCandidates.any { it.item.id == f.item.id && it.documentKind == f.doc }
        }))
    }

    @Test
    fun everydayItemsRaiseNothing() {
        val negatives = fixtures.filter { it.expect == "none" }.map { it.item.id }.toSet()
        val credits = statement.filter { it.facts["direction"] == "credit" }.map { it.id }.toSet()
        val chargeFp = charges.filter { c -> c.itemId in negatives || c.itemId in credits || c.alsoSeenIn.any { it in negatives } }
        val expiryFp = report.expiryCandidates.filter { it.item.id in negatives }
        val censusFp = report.recurring.filter { rc -> charges.any { it.merchant == rc.merchant && it.itemId in negatives } }
        println("tracking false positives · charges ${chargeFp.size} · expiry ${expiryFp.size} · census ${censusFp.size} · of ${negatives.size} everyday items")
        assertEquals(emptyList(), chargeFp.map { it.itemId })
        assertEquals(emptyList(), expiryFp.map { it.item.id })
        assertEquals(emptyList(), censusFp.map { it.merchant })
    }
}
