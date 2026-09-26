package dev.loupe.agent

import dev.loupe.engine.Cadence
import dev.loupe.engine.ExpiryAlert
import dev.loupe.engine.RecurringCharge
import dev.loupe.engine.ValidityRule
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalPlannerTest {
    private val today = LocalDate(2026, 9, 25)
    private val schengen = ValidityRule("Schengen", monthsRequired = 6)

    private fun alert(
        expiry: LocalDate = LocalDate(2026, 11, 3),
        ambiguous: Boolean = false,
        type: String = "passport",
    ) = ExpiryAlert(
        itemId = "doc-1",
        documentType = type,
        expiry = expiry,
        daysRemaining = 39,
        rule = schengen,
        dateWasAmbiguous = ambiguous,
    )

    // ------------------------------------------------------------------ free, on-device actions

    @Test
    fun `an expiry becomes a reminder that never went anywhere`() {
        val a = LocalPlanner.fromExpiry(alert(), today) as PreparedAction.Remind
        assertEquals(ActionOrigin.OnDevice, a.origin)
        assertTrue(!a.origin.isOnline, "nothing left the device, so nothing may say Online")
        assertEquals("2026-10-04", a.whenIso, "30 days before 3 November")
        assertTrue(a.text.contains("passport"), a.text)
        assertTrue(a.because.contains("Schengen"), a.because)
    }

    @Test
    fun `the evidence says the date is a fact of the item - not a model answer`() {
        val a = LocalPlanner.fromExpiry(alert(), today)
        assertTrue(a.evidence.mechanical)
        assertEquals("expiry-radar", a.evidence.judgmentId)
        assertEquals(
            "expiry-radar found \"passport expires 2026-11-03\" in the item itself, not from a model answer",
            a.evidence.line,
        )
    }

    @Test
    fun `a reminder is never set in the past`() {
        // The lead time has already gone: remind today, not in August.
        val a = LocalPlanner.fromExpiry(alert(expiry = LocalDate(2026, 10, 1)), today) as PreparedAction.Remind
        assertEquals("2026-09-25", a.whenIso)
    }

    @Test
    fun `an ambiguous date produces a note and never a reminder on the wrong day`() {
        // "Never acts on something it is unsure about" does not stop being true because the
        // uncertainty came from a date format instead of a distribution.
        val a = LocalPlanner.fromExpiry(alert(ambiguous = true), today)
        assertTrue(a is PreparedAction.NoteFinding, "got ${a::class.simpleName}")
        assertTrue(a.detail.contains("could be read two ways"), a.detail)
        assertTrue(a.detail.contains("no reminder was set"), a.detail)
    }

    @Test
    fun `a dormant subscription becomes a note - never a cancellation`() {
        val charge = RecurringCharge(
            merchant = "Streaming Co",
            cadence = Cadence.MONTHLY,
            occurrences = 14,
            typicalAmountMinor = 1_299,
            lastCharged = LocalDate(2026, 6, 1),
            daysSinceLastCharge = 116,
        )
        val a = LocalPlanner.fromDormantSubscription(charge, "stmt-1")
        assertNotNull(a)
        assertTrue(a is PreparedAction.NoteFinding, "cancelling is the person's call, not ours")
        assertEquals(ActionOrigin.OnDevice, a.origin)
        assertTrue(a.headline.contains("12.99"), a.headline)
        assertTrue(a.detail.contains("181.86"), a.detail)
        assertTrue(a.detail.contains("116 days"), a.detail)
    }

    @Test
    fun `an irregular charge is not called a subscription`() {
        val charge = RecurringCharge(
            merchant = "Corner Shop",
            cadence = Cadence.IRREGULAR,
            occurrences = 9,
            typicalAmountMinor = 450,
            lastCharged = LocalDate(2026, 9, 1),
            daysSinceLastCharge = 24,
        )
        assertEquals(null, LocalPlanner.fromDormantSubscription(charge, "stmt-1"))
    }

    @Test
    fun `money is formatted from minor units without inventing a currency`() {
        assertEquals("12.99", LocalPlanner.money(1_299))
        assertEquals("0.05", LocalPlanner.money(5))
        assertEquals("100.00", LocalPlanner.money(10_000))
        assertEquals("-3.40", LocalPlanner.money(-340))
    }

    @Test
    fun `a plan covers the expiries and only the dormant subscriptions`() {
        val active = RecurringCharge("Gym", Cadence.MONTHLY, 6, 5_000, LocalDate(2026, 9, 20), 5)
        val dormant = RecurringCharge("Old App", Cadence.ANNUAL, 3, 9_900, LocalDate(2026, 1, 2), 266)
        val plan = LocalPlanner.plan(
            expiries = listOf(alert()),
            subscriptions = listOf(active to "s-1", dormant to "s-2"),
            today = today,
        )
        assertEquals(2, plan.size)
        assertTrue(plan[0] is PreparedAction.Remind)
        assertTrue((plan[1] as PreparedAction.NoteFinding).headline.contains("Old App"))
        assertTrue(plan.all { it.origin == ActionOrigin.OnDevice })
    }

    // ------------------------------------------------------------------- the tier gates it, and the
    //                                                                     never list still runs

    @Test
    fun `the free tier acts on the device and needs no provider at all`() {
        val runner = AgentRunner(AgentConfig(), hasKey = false, tier = AgentTier.FREE)
        assertTrue(runner.canActOnDevice)
        assertTrue(!runner.isReady, "it cannot ask a provider, and does not need to")
        val report = runner.planLocally(LocalPlanner.plan(expiries = listOf(alert()), today = today))
        assertEquals(1, report.allowed.size)
        assertTrue(report.clean)
    }

    @Test
    fun `the shipped default prepares local actions too`() {
        // Local is free, so the runner that can never send anything still plans on the device.
        val report = AgentRunner.off().planLocally(LocalPlanner.plan(expiries = listOf(alert()), today = today))
        assertEquals(1, report.allowed.size)
        assertEquals(0, report.blocked.size)
    }

    @Test
    fun `the never list runs over a local plan too`() {
        // The rules are about what Loupe shows a person, not about who wrote it: a locally prepared
        // note that quoted a card number found in a document would be just as wrong.
        val runner = AgentRunner(AgentConfig(), hasKey = false, tier = AgentTier.FREE)
        val report = runner.planLocally(
            listOf(
                note(detail = "The document shows 4012 8888 8888 1881.", origin = ActionOrigin.OnDevice),
                remind(origin = ActionOrigin.OnDevice),
            ),
        )
        assertEquals(1, report.allowed.size)
        assertEquals(NeverRule.CREDENTIALS, report.blocked.single().rule)
    }

    @Test
    fun `a local plan cannot smuggle in something a provider wrote`() {
        val runner = AgentRunner(AgentConfig(), hasKey = false, tier = AgentTier.FREE)
        val report = runner.planLocally(listOf(remind(origin = DEEPSEEK)))
        assertEquals(0, report.allowed.size)
        assertEquals(NeverRule.LABELLED, report.blocked.single().rule)
        assertTrue(report.blocked.single().detail.contains("DeepSeek (Online)"))
    }

    @Test
    fun `the tiers allow exactly what they say they do`() {
        assertEquals(
            setOf(AgentCapability.EXPLAIN, AgentCapability.ACT_ON_DEVICE),
            AgentTier.FREE.allowed,
        )
        assertEquals(AgentCapability.entries.toSet(), AgentTier.ASSISTANT.allowed)
        assertTrue(!AgentTier.FREE.allows(AgentCapability.ASK_PROVIDER))
        assertTrue(!AgentTier.FREE.isPaid)
        assertTrue(AgentTier.ASSISTANT.isPaid)
    }

    @Test
    fun `every local capability is free and only provider-backed ones are paid`() {
        // The pricing rule itself (docs/AGENT.md §4), checked over every capability, so one added
        // later cannot slip into the wrong tier.
        for (capability in AgentCapability.entries) {
            assertEquals(capability.needsProvider, capability.isPaid, "$capability")
            assertEquals(!capability.needsProvider, AgentTier.FREE.allows(capability), "$capability")
            assertTrue(AgentTier.ASSISTANT.allows(capability), "$capability")
        }
        assertEquals(setOf(AgentCapability.ASK_PROVIDER), AgentCapability.entries.filter { it.isPaid }.toSet())
        // Exactly one paid tier, and it is the assistant.
        assertEquals(listOf(AgentTier.ASSISTANT), AgentTier.entries.filter { it.isPaid })
    }

    @Test
    fun `a stored code parses - and a retired one never grants the paid tier`() {
        assertEquals(AgentTier.FREE, AgentTier.parse("free"))
        assertEquals(AgentTier.ASSISTANT, AgentTier.parse("assistant"))
        assertEquals(null, AgentTier.parse("local"))
        assertEquals(null, AgentTier.parse("connected"))
        assertEquals(null, AgentTier.parse("enterprise"))
    }
}
