package dev.loupe.agent

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EarlyUserPolicyTest {
    private val cutoff = LocalDate(2027, 3, 1)
    private val before = LocalDate(2027, 2, 28)
    private val after = LocalDate(2027, 3, 2)

    @Test
    fun `no cutoff has been chosen - so everyone is an early user today`() {
        // Local features have never been charged for; the promise costs nothing until a cutoff is set.
        assertEquals(null, EarlyUserPolicy.CUTOFF)
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.StoreVerified(after)))
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.Recorded(after)))
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.Unknown))
    }

    @Test
    fun `a first install before the cutoff keeps local free - on either platform`() {
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.StoreVerified(before), cutoff))
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.Recorded(before), cutoff))
    }

    @Test
    fun `the cutoff day itself is the first day that is not early`() {
        assertFalse(EarlyUserPolicy.keepsLocalFree(FirstInstall.StoreVerified(cutoff), cutoff))
        assertFalse(EarlyUserPolicy.keepsLocalFree(FirstInstall.Recorded(cutoff), cutoff))
    }

    @Test
    fun `a first install after the cutoff does not`() {
        assertFalse(EarlyUserPolicy.keepsLocalFree(FirstInstall.StoreVerified(after), cutoff))
        assertFalse(EarlyUserPolicy.keepsLocalFree(FirstInstall.Recorded(after), cutoff))
    }

    @Test
    fun `when the platform cannot say - the benefit of the doubt goes to the person`() {
        // Wrongly granting a free local feature costs nothing to run; wrongly taking one from an
        // early user breaks a promise.
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.Unknown, cutoff))
    }

    @Test
    fun `the paid tier is not decided here`() {
        // Early status is about local features only. The AI assistant is paid for everyone,
        // grandfathered or not, because every call costs money.
        assertTrue(EarlyUserPolicy.keepsLocalFree(FirstInstall.StoreVerified(before), cutoff))
        assertFalse(AgentTier.FREE.allows(AgentCapability.ASK_PROVIDER))
    }
}
