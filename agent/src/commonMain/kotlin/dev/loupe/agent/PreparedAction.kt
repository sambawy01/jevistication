package dev.loupe.agent

/**
 * Something the agent has prepared for a person to approve.
 *
 * A prepared action is **data and nothing else**. It holds no handle, no callback and no capability
 * to do the thing it describes, and this module contains no code that executes one. The agent's
 * only output channel is the Review queue ([AgentReview]), whose state machine runs an action only
 * after a person approves it. So *"never presses the last button"* and *"never sends, posts or
 * files what it writes without your approval"* (docs/PRODUCT.md §4) are not policy the agent is
 * asked to observe — they are the shape of the type it is allowed to return.
 *
 * Every variant carries:
 * - [evidence] — the on-device decision that justified spending a call on this item at all;
 * - [provider] — who wrote it, so every result can be labelled **Online** with its source (§4a).
 */
sealed interface PreparedAction {
    val evidence: AgentEvidence

    /** The provider's display name, for the Online label. */
    val provider: String

    /** The one-line title the Review queue shows. */
    val title: String

    /** What the Approve button should say. */
    val verb: String

    /**
     * A reminder at a time, on the device. The cheapest and safest thing the tier can do, and the
     * one most people would actually pay for.
     *
     * Note what it is *not*: the agent does not schedule it. Approving it hands the reminder to the
     * platform (local notifications on iOS, the desktop's scheduler), which is why the time is kept
     * as text exactly as it was proposed — this module never resolves "next Tuesday" into a moment
     * and so can never be wrong about which Tuesday.
     */
    data class Remind(
        override val evidence: AgentEvidence,
        /** The moment, as an ISO-8601 local date-time or date: `2026-10-02T09:00` or `2026-10-02`. */
        val whenIso: String,
        /** What to say when it fires, in the user's own language. */
        val text: String,
        /** Why the agent thinks this date, quoted from the item. */
        val because: String,
        override val provider: String,
    ) : PreparedAction {
        override val title: String get() = "Reminder $whenIso: ${text.take(80)}"
        override val verb: String get() = "Add reminder"
    }

    /**
     * A calendar entry for a date the item carries — a renewal, a deadline, an appointment.
     *
     * Same restraint as [Remind]: the date is kept as proposed and resolved by the platform.
     */
    data class CalendarEvent(
        override val evidence: AgentEvidence,
        val startIso: String,
        val endIso: String?,
        val subject: String,
        val location: String?,
        val because: String,
        override val provider: String,
    ) : PreparedAction {
        override val title: String get() = "Calendar $startIso: ${subject.take(80)}"
        override val verb: String get() = "Add to calendar"
    }

    /**
     * A draft reply to one message. The same contract as the iPhone's existing reply drafts: it
     * waits in the queue, and approving it records it — Loupe never sends it, and never files it
     * into a mailbox. The person copies it, or opens it in Mail and taps Send there.
     */
    data class DraftReply(
        override val evidence: AgentEvidence,
        val to: String,
        val subject: String,
        val body: String,
        /** The language the body is in, named in English, so the review screen can say so. */
        val language: String,
        /** Questions the reviewer must answer before sending. */
        val needsInfo: List<String>,
        val notes: String,
        override val provider: String,
    ) : PreparedAction {
        override val title: String get() = "Reply draft: ${subject.ifBlank { "(no subject)" }.take(80)}"
        override val verb: String get() = "Keep draft"
    }

    /**
     * No action — just a sentence worth putting in front of the person.
     *
     * This exists so the agent has an honest way to say "this matters, and there is nothing to
     * prepare". Without it, a model under pressure to return an action invents one.
     */
    data class NoteFinding(
        override val evidence: AgentEvidence,
        val headline: String,
        val detail: String,
        override val provider: String,
    ) : PreparedAction {
        override val title: String get() = headline.take(120)
        override val verb: String get() = "Keep"
    }
}
