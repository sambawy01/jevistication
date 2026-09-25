package dev.loupe.agent

import dev.loupe.kit.review.ReviewProposal
import dev.loupe.kit.review.ReviewRegistry

/**
 * Prepared actions as Review-queue proposals — the agent's **only** output.
 *
 * There is no other function in this module that hands a prepared action anywhere, and the queue
 * runs an action only after a person approves it. So the two promises that would be hardest to keep
 * by discipline alone (*"never presses the last button"*, *"never sends, posts or files what it
 * writes without your approval"*) hold because there is nowhere else for an action to go.
 *
 * Every proposal is built against kinds [ReviewRegistry] already knows, and `AgentReviewTest`
 * asserts that each one passes that kind's own field check — so the queue can never refuse what the
 * agent produces for a reason the agent could have seen coming.
 */
object AgentReview {
    /** The queue's `input_summary` limit. */
    const val MAX_SUMMARY: Int = 2_000

    /**
     * The proposal for one action.
     *
     * [PreparedAction] and its `kind` are matched here rather than in the type, because the queue's
     * vocabulary belongs to the queue: the agent describes what it prepared, and this is the one
     * place that translates.
     */
    fun proposal(action: PreparedAction, at: String = ""): ReviewProposal {
        val e = action.evidence
        val summary = summaryFor(action)
        return when (action) {
            is PreparedAction.Remind -> ReviewProposal(
                feature = ReviewRegistry.AGENT,
                kind = "agent_reminder",
                title = action.title.take(ActionGuard.MAX_TITLE),
                sourceKey = sourceKey("remind", e.itemId, action.whenIso + "|" + action.text, at),
                inputSummary = summary,
                proposal = mapOf(
                    "item_id" to e.itemId.take(1_000),
                    "when" to action.whenIso.take(30),
                    "text" to action.text.take(200),
                    "because" to action.because.take(400),
                    "provider" to action.provider.take(200),
                    "evidence" to e.line.take(300),
                ),
                actionType = "agent.remind",
                actionParams = emptyMap(),
            )

            is PreparedAction.CalendarEvent -> ReviewProposal(
                feature = ReviewRegistry.AGENT,
                kind = "agent_event",
                title = action.title.take(ActionGuard.MAX_TITLE),
                sourceKey = sourceKey("calendar", e.itemId, action.startIso + "|" + action.subject, at),
                inputSummary = summary,
                proposal = buildMap {
                    put("item_id", e.itemId.take(1_000))
                    put("start", action.startIso.take(30))
                    action.endIso?.let { put("end", it.take(30)) }
                    put("subject", action.subject.take(300))
                    action.location?.let { put("location", it.take(300)) }
                    put("because", action.because.take(400))
                    put("provider", action.provider.take(200))
                    put("evidence", e.line.take(300))
                },
                actionType = "agent.calendar",
                actionParams = emptyMap(),
            )

            is PreparedAction.DraftReply -> ReviewProposal(
                feature = ReviewRegistry.AGENT,
                kind = "agent_reply",
                title = action.title.take(ActionGuard.MAX_TITLE),
                sourceKey = sourceKey("reply", e.itemId, action.subject, at),
                inputSummary = summary,
                proposal = mapOf(
                    "item_id" to e.itemId.take(1_000),
                    "to" to action.to.take(300),
                    "subject" to action.subject.ifBlank { "(no subject)" }.take(300),
                    "body" to action.body.take(ActionGuard.MAX_BODY),
                    "language" to action.language.take(60),
                    "warnings" to action.needsInfo.joinToString("\n").take(1_000),
                    "notes" to action.notes.take(1_000),
                    "provider" to action.provider.take(200),
                    "evidence" to e.line.take(300),
                ),
                actionType = "agent.draft",
                actionParams = emptyMap(),
            )

            is PreparedAction.NoteFinding -> ReviewProposal(
                feature = ReviewRegistry.AGENT,
                kind = "agent_note",
                title = action.title.take(ActionGuard.MAX_TITLE),
                sourceKey = sourceKey("note", e.itemId, action.headline, at),
                inputSummary = summary,
                proposal = mapOf(
                    "item_id" to e.itemId.take(1_000),
                    "headline" to action.headline.take(300),
                    "detail" to action.detail.take(2_000),
                    "provider" to action.provider.take(200),
                    "evidence" to e.line.take(300),
                ),
                actionType = "agent.note",
                actionParams = emptyMap(),
            )
        }
    }

    fun proposals(actions: List<PreparedAction>, at: String = ""): List<ReviewProposal> =
        actions.map { proposal(it, at) }

    /**
     * The line above every agent proposal in the queue.
     *
     * It says three things, in this order, because they are what a person needs before deciding:
     * who wrote it and that it came from the network, what on the device decided the item was worth
     * it, and that nothing has happened yet.
     */
    internal fun summaryFor(action: PreparedAction): String {
        val what = when (action) {
            is PreparedAction.Remind -> "A reminder"
            is PreparedAction.CalendarEvent -> "A calendar entry"
            is PreparedAction.DraftReply -> "A draft reply"
            is PreparedAction.NoteFinding -> "A note"
        }
        val tail = when (action) {
            is PreparedAction.DraftReply ->
                " Loupe never sends it: approve it, then copy it or open it in Mail and send it there."

            else -> " Nothing happens until you approve it."
        }
        return ("$what prepared by ${action.provider} (Online), because ${action.evidence.line}." + tail)
            .take(MAX_SUMMARY)
    }

    /**
     * The producer key the queue de-duplicates on.
     *
     * It is deliberately derived from the action's own content and not from a random id: re-running
     * the agent over the same item must not queue the same reminder twice, and a proposal the
     * person rejected must not come back (`ReviewQueue.submit` refuses a key it has seen).
     */
    internal fun sourceKey(type: String, itemId: String, discriminator: String, at: String): String {
        val d = discriminator.trim().lowercase().replace(WHITESPACE, " ").take(120)
        val stamp = if (at.isBlank()) "" else ":" + at.take(10)
        return "agent.$type:$itemId:$d$stamp"
    }

    private val WHITESPACE = Regex("""\s+""")
}
