package dev.loupe.kit.flights

import dev.loupe.engine.Backend
import dev.loupe.engine.Extent
import dev.loupe.engine.Fit
import dev.loupe.engine.Policy
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.Truncation
import dev.loupe.engine.Scored
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlightJudgeTest {
    private fun offer(id: String, price: String = "142.30", stops: Int = 0, refundable: Term = Term.NO) = FlightFacts(
        id = id, price = price, currency = "GBP", airline = "TAP Air Portugal",
        legs = listOf(
            FlightLeg("LIS", "LHR", "07:35", "10:15", stops, 160, "TP1350"),
            FlightLeg("LHR", "LIS", "18:05", "20:45", 0, 160, "TP1363"),
        ),
        checkedBags = 1, carryOnBags = 1, refundable = refundable, changeable = Term.NOT_STATED,
    )

    @Test
    fun `offer state is compact - ordered and whole`() {
        val state = FlightState.of(offer("a"))
        assertTrue(state.isComplete)
        assertEquals(listOf("price", "airline", "leg0", "leg1", "bags", "terms"), state.items.map { it.id })
        assertEquals(
            """
            Price: GBP 142.30
            Airline: TAP Air Portugal
            Outbound LIS-LHR: departs 07:35, arrives 10:15, nonstop, 2h 40m (TP1350)
            Return LHR-LIS: departs 18:05, arrives 20:45, nonstop, 2h 40m (TP1363)
            Bags: 1 checked, 1 carry-on
            Refundable: no; changes: not stated
            """.trimIndent(),
            state.text,
        )
        assertTrue(state.text.length < FlightState.BUDGET)
    }

    @Test
    fun `a tight budget cuts the least important facts first and says so`() {
        val state = FlightState.of(offer("a", stops = 1), budget = 100)
        assertEquals(Fit.VERBATIM, state.items.first().fit)
        assertEquals(Fit.REMOVED, state.items.last().fit)
        assertNotNull(state.budgetCut)
        assertTrue(state.text.contains("[...truncated]"))
    }

    @Test
    fun `unknown duration and a one-way leg read plainly`() {
        val one = offer("a").copy(legs = listOf(FlightLeg("LIS", "LHR", "--:--", "--:--", 2, -1, "X1+X2+X3")), checkedBags = 0)
        val text = FlightState.of(one).text
        assertTrue(text.contains("Leg 1 LIS-LHR: departs --:--, arrives --:--, 2 stops (X1+X2+X3)"))
        assertTrue(text.contains("no checked bag listed"))
    }

    @Test
    fun `priorities compile through C2 to a two-option judgment with descriptions`() {
        val j = assertIs<FlightJudgment.Ready>(FlightPriorities.compile("nonstop, under £200, not before 7am")).judgment
        assertEquals(listOf(FlightPriorities.FITS, FlightPriorities.MISSES), j.candidates)
        assertEquals("Does this flight offer fit these priorities: nonstop, under £200, not before 7am?", j.question)
        assertEquals(2, j.descriptions.size)
    }

    @Test
    fun `empty priorities ask a default question - question marks do not become two questions`() {
        assertEquals("Does this flight offer fit these priorities: cheap and convenient?", FlightPriorities.question("  "))
        assertIs<FlightJudgment.Ready>(FlightPriorities.compile("nonstop? cheap?"))
    }

    @Test
    fun `priorities the lint refuses are refused with reasons`() {
        val r = assertIs<FlightJudgment.Refused>(FlightPriorities.compile("explain which is best"))
        assertTrue(r.reasons.isNotEmpty())
    }

    private fun fake(fits: Map<String, Double>, cut: Boolean = false) = Backend { _, state ->
        val id = fits.keys.first { state.text.contains("price-$it") }
        val p = fits.getValue(id)
        Scored(
            mapOf(FlightPriorities.FITS to p, FlightPriorities.MISSES to 1 - p),
            modelContext = if (cut) Extent(10, 20, Extent.Measure.TOKENS) else null,
        )
    }

    private fun tagged(id: String) = offer(id).copy(price = "price-$id")

    @Test
    fun `ranks by calibrated fit and marks what the policy would not act on`() {
        val j = (FlightPriorities.compile("nonstop") as FlightJudgment.Ready).judgment
        val judge = FlightJudge(fake(mapOf("a" to 0.30, "b" to 0.95, "c" to 0.60, "d" to 0.05)))
        val verdicts = listOf("a", "b", "c", "d").map { judge.judge(j, tagged(it)) }
        val ordered = FlightJudge.order(verdicts)
        assertEquals(listOf("b", "c", "a", "d"), ordered.map { it.id })
        assertEquals(listOf(false, true, true, false), ordered.map { it.unsure })
        assertEquals(0.9, ordered[0].margin, 1e-9)
    }

    @Test
    fun `a cut input is unsure however confident the model is`() {
        val j = (FlightPriorities.compile("nonstop") as FlightJudgment.Ready).judgment
        val v = FlightJudge(fake(mapOf("a" to 0.99), cut = true)).judge(j, tagged("a"))
        assertTrue(v.unsure)
        assertTrue(v.truncated)
    }

    @Test
    fun `a failing or malformed backend is unusable - last - never thrown`() {
        val j = (FlightPriorities.compile("nonstop") as FlightJudgment.Ready).judgment
        val broken = FlightJudge(Backend { _, _ -> error("model file truncated") }).judge(j, tagged("x"))
        val malformed = FlightJudge(Backend.ofMasses { _, _ -> mapOf("fits" to 0.7) }).judge(j, tagged("y"))
        val fine = FlightJudge(fake(mapOf("z" to 0.1))).judge(j, tagged("z"))
        assertEquals("model file truncated", broken.failure)
        assertNotNull(malformed.failure)
        assertEquals(listOf("z", "x", "y"), FlightJudge.order(listOf(broken, malformed, fine)).map { it.id })
        assertTrue(broken.unsure && malformed.unsure)
        assertFalse(fine.truncated)
    }

    @Test
    fun `ties keep the order given`() {
        val j = (FlightPriorities.compile("nonstop") as FlightJudgment.Ready).judgment
        val judge = FlightJudge(fake(mapOf("p" to 0.5, "q" to 0.5, "r" to 0.5)))
        val order = FlightJudge.order(listOf("q", "r", "p").map { judge.judge(j, tagged(it)) })
        assertEquals(listOf("q", "r", "p"), order.map { it.id })
    }

    @Test
    fun `every Laya decision is a model ledger row with the source and the full distribution`() {
        val j = (FlightPriorities.compile("nonstop") as FlightJudgment.Ready).judgment
        val judge = FlightJudge(fake(mapOf("a" to 0.95, "b" to 0.60)))
        val sure = judge.decide(j, tagged("a"))
        val unsure = judge.decide(j, tagged("b"))
        assertEquals(sure.verdict, judge.judge(j, tagged("a")))
        for (d in listOf(sure, unsure)) {
            assertEquals(ResolvedBy.Model, d.row.resolvedBy)
            assertTrue(d.row.isModelPrediction)
            assertEquals(FlightPriorities.ID, d.row.judgmentId)
            assertEquals(j.criteriaHash, d.row.criteriaHash)
            assertEquals("web:duffel:${d.verdict.id}", d.row.itemId)
            assertEquals(1.0, d.row.propensity.value)
            assertEquals(Truncation.NONE, d.row.truncation)
        }
        assertEquals(FlightPriorities.FITS, sure.row.action)
        assertEquals(0.95, sure.row.distribution.getValue(FlightPriorities.FITS).value, 1e-12)
        assertEquals(Policy.ABSTAIN, unsure.row.action)
        assertEquals(0.4, unsure.row.distribution.getValue(FlightPriorities.MISSES).value, 1e-12)
    }

    @Test
    fun `a cut or unusable decision is recorded as such`() {
        val j = (FlightPriorities.compile("nonstop") as FlightJudgment.Ready).judgment
        val cut = FlightJudge(fake(mapOf("a" to 0.99), cut = true)).decide(j, tagged("a")).row
        assertEquals(Policy.ABSTAIN, cut.action)
        assertTrue(cut.truncated)
        val broken = FlightJudge(Backend { _, _ -> error("model file truncated") }).decide(j, tagged("x")).row
        assertEquals(ResolvedBy.Unusable, broken.resolvedBy)
        assertEquals(Policy.UNUSABLE, broken.action)
        assertEquals("model file truncated", broken.failure)
        assertEquals(0.5, broken.distribution.getValue(FlightPriorities.FITS).value, 1e-12)
    }
}
