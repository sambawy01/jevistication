package dev.loupe.agent

/**
 * One record of something leaving the device.
 *
 * The agent tier changes the character of the privacy claim, and this type is how that change is
 * made checkable rather than argued about. Until now docs/PRODUCT.md §4 could say *"for local
 * sources nothing leaves the device"* without qualification. With a hosted provider configured,
 * that is no longer true of the handful of items the gate lets through — so the honest answer is
 * not softer wording, it is a per-item record the user can read: what went, where, when, how much,
 * and what came back.
 *
 * What is deliberately **not** here: the item's text, the prompt, the draft, and the key. A record
 * that quoted what it was recording would be a second copy of the thing the user was worried
 * about, and one that held the key would put it somewhere the key must never be.
 */
data class EgressRecord(
    /** When the call was made, as the ledger writes timestamps. */
    val atIso: String,
    /** Which item it was about, so the record can be read from the item and the item from it. */
    val itemId: String,
    val judgmentId: String,
    /** The display name shown on the Online label. */
    val provider: String,
    /** The host the request went to. Named, so "which server" is answerable. */
    val host: String,
    val model: String,
    /** How many characters of the item's own text the request carried. */
    val sentCharacters: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val outcome: Outcome,
    /** How many prepared actions survived the guard, and how many it refused. */
    val actionsPrepared: Int = 0,
    val actionsRefused: Int = 0,
    /** The refusals, by never-list rule code. Never the text that tripped them. */
    val refusedRules: List<String> = emptyList(),
    /** Why it failed, already run through [AgentWire.redact]. Empty on success. */
    val problem: String = "",
) {
    enum class Outcome(val code: String) {
        OK("ok"),
        FAILED("failed"),

        /** The tier is off, or not set up: nothing was sent. */
        NOT_SENT("not_sent"),
        ;
    }

    /** The one line the agent's log shows for this call. */
    val line: String
        get() = when (outcome) {
            Outcome.NOT_SENT -> "$atIso · nothing sent · $itemId"
            Outcome.FAILED -> "$atIso · $provider ($host) · $itemId · failed: $problem"
            Outcome.OK -> "$atIso · $provider ($host) · $model · $itemId · " +
                "$sentCharacters characters sent, $tokensIn/$tokensOut tokens · " +
                "$actionsPrepared prepared" + if (actionsRefused > 0) ", $actionsRefused refused" else ""
        }

    companion object {
        /** The record for a call that was never made, because the tier is off or unconfigured. */
        fun notSent(atIso: String, itemId: String, judgmentId: String): EgressRecord = EgressRecord(
            atIso = atIso,
            itemId = itemId,
            judgmentId = judgmentId,
            provider = "",
            host = "",
            model = "",
            sentCharacters = 0,
            tokensIn = 0,
            tokensOut = 0,
            outcome = Outcome.NOT_SENT,
        )
    }
}
