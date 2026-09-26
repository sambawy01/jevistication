package dev.loupe.agent

import kotlinx.datetime.LocalDate

/**
 * What the app knows about when this person first got Loupe.
 *
 * Each platform fills it in its own way (docs/AGENT.md §4); the decision about what it means lives
 * here, once, so the iPhone and Android cannot disagree about who is an early user.
 */
sealed interface FirstInstall {
    /**
     * iOS: `AppTransaction.shared`, **verified**, `originalPurchaseDate` converted to a calendar day
     * in UTC. The App Store's own record, so it survives reinstalls and new phones.
     */
    data class StoreVerified(val day: LocalDate) : FirstInstall

    /**
     * Android: the day Loupe recorded on its first run (Play has no equivalent of `AppTransaction`),
     * or the day stored against the person's account if one is tied to it. Weaker than
     * [StoreVerified]: it trusts the device clock on that first run and is lost with the app's data.
     */
    data class Recorded(val day: LocalDate) : FirstInstall

    /**
     * The platform could not say: `AppTransaction` failed verification or could not be fetched
     * (offline, a store outage), or there is no first-run record yet.
     */
    data object Unknown : FirstInstall
}

/**
 * Who keeps the local features free for life.
 *
 * Today every local feature is free for everyone (`AgentTier.FREE`), so nothing depends on this
 * yet. It exists so the promise to early users is written down as code before it is ever needed: if
 * local features are one day charged for, anyone who first got Loupe **before** [CUTOFF] keeps them
 * free, on every device, without doing anything.
 *
 * One constant, one pure function, no server.
 */
object EarlyUserPolicy {
    /**
     * The first day on which a *new* install would no longer count as an early user, or null while no
     * such day has been chosen.
     *
     * Null means everyone is an early user, which is simply true today: local features have never
     * been charged for. Setting it is an owner decision, and it must never be earlier than the day the
     * release carrying it reaches the stores, or people who installed in between would lose something
     * they already had.
     *
     * A date rather than `originalAppVersion`, because the date means the same thing on both
     * platforms, and because in the App Store sandbox `originalAppVersion` is always `"1.0"`, so a
     * version cut-off could not be tested before release.
     */
    val CUTOFF: LocalDate? = null

    /**
     * Whether this person keeps the local features free for life.
     *
     * - With no cutoff set, yes.
     * - With a known first-install day, yes exactly when it falls before [cutoff].
     * - When the platform cannot say ([FirstInstall.Unknown]), yes. Local features cost nothing to
     *   run, so wrongly granting one costs nothing, while wrongly taking one away from an early user
     *   breaks a promise. The app should still retry `AppTransaction` later rather than treat Unknown
     *   as final.
     */
    fun keepsLocalFree(firstInstall: FirstInstall, cutoff: LocalDate? = CUTOFF): Boolean {
        if (cutoff == null) return true
        return when (firstInstall) {
            is FirstInstall.StoreVerified -> firstInstall.day < cutoff
            is FirstInstall.Recorded -> firstInstall.day < cutoff
            FirstInstall.Unknown -> true
        }
    }
}
