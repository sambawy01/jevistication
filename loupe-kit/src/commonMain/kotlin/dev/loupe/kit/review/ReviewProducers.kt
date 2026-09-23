package dev.loupe.kit.review

import dev.loupe.kit.judgments.DraftShape
import dev.loupe.kit.mail.MailRow
import dev.loupe.kit.packs.PackJudgmentPlan
import dev.loupe.kit.privacy.PrivacyFinding
import dev.loupe.kit.watchers.WatcherFinding

/**
 * What each of the phone's checks proposes for the Review queue. Pure: the app submits the
 * results (the queue drops any source key it has already seen, so re-running a check never
 * brings back a proposal the person rejected). Nothing here runs an action.
 *
 * The Review queue is for **actions** (remove a file, record a verdict, add a judgment). The
 * Unsure queue is different: it asks the person for **labels** the model learns from. Neither
 * feeds the other.
 */
object ReviewProducers {
    /**
     * Privacy check: for each duplicate group, remove each extra copy — only copies the app can
     * reach ([reachable]: item ids in picked Files locations or the Send to Loupe inbox). The
     * sample, mail and photos are suggest-only, so they are never proposed.
     */
    fun privacy(findings: List<PrivacyFinding>, reachable: Set<String>): List<ReviewProposal> = findings.flatMap { f ->
        val group = f.duplicates ?: return@flatMap emptyList()
        val keep = group.members.firstOrNull { it.keep }
        group.members.filter { !it.keep && it.itemId in reachable }.map { m ->
            ReviewProposal(
                feature = ReviewRegistry.PRIVACY, kind = "file_action",
                title = "Remove the extra copy ${m.name}".take(ReviewQueue.MAX_TITLE_CHARS),
                sourceKey = "privacy:${f.key}:${m.itemId}",
                inputSummary = "${f.message} Keeps ${keep?.name ?: "the first copy"}${keep?.location?.let { " in $it" } ?: ""}. " +
                    "The removed copy is held so you can undo.",
                proposal = buildMap {
                    put("path", m.location.ifEmpty { m.name } + "/" + m.name)
                    put("name", m.name)
                    keep?.let { put("keep", it.name) }
                },
                actionType = "privacy.remove_copy",
                actionParams = mapOf("finding_key" to f.key, "item_id" to m.itemId),
            )
        }
    }

    /** Mail triage: a message the evidence flags as phishing that nobody has decided on yet. */
    fun mail(rows: List<MailRow>): List<ReviewProposal> = rows.filter { it.phishing && it.personVerdict == null }.map { r ->
        ReviewProposal(
            feature = ReviewRegistry.MAIL, kind = "mail_verdict",
            title = "Confirm phishing: ${r.subject.ifBlank { "(no subject)" }}".take(ReviewQueue.MAX_TITLE_CHARS),
            sourceKey = "mail:${r.itemId}",
            inputSummary = listOfNotNull("From ${r.sender}.", r.phishingCue).joinToString(" ").take(ReviewQueue.MAX_SUMMARY_CHARS),
            proposal = mapOf("item_id" to r.itemId, "subject" to r.subject.take(300).ifBlank { "(no subject)" }, "sender" to r.sender.take(300), "verdict" to "phishing"),
            actionType = "mail.confirm_phishing", actionParams = emptyMap(),
        )
    }

    /** Watchers: a finding nobody has answered — keep it on Now as confirmed. */
    fun watchers(findings: List<WatcherFinding>): List<ReviewProposal> = findings.filter { it.verdict == null }.map { f ->
        ReviewProposal(
            feature = ReviewRegistry.WATCHER, kind = "finding_verdict",
            title = "Keep watching: ${f.title}".take(ReviewQueue.MAX_TITLE_CHARS),
            sourceKey = "watcher:${f.key}",
            inputSummary = f.why.take(ReviewQueue.MAX_SUMMARY_CHARS),
            proposal = mapOf("finding_key" to f.key, "title" to f.title.take(300), "watcher" to f.watcherTitle.take(80), "verdict" to "confirmed"),
            actionType = "watcher.confirm", actionParams = emptyMap(),
        )
    }

    /** Judgments: pack questions the person chose to review one by one instead of adding at once. */
    fun judgments(plans: List<PackJudgmentPlan>, packSlug: String): List<ReviewProposal> = plans.filter { it.addable }.map { p ->
        ReviewProposal(
            feature = ReviewRegistry.JUDGMENT, kind = "judgment",
            title = "Add judgment: ${p.title}".take(ReviewQueue.MAX_TITLE_CHARS),
            sourceKey = "judgment:$packSlug:${p.judgmentId}",
            inputSummary = "From the pack \"$packSlug\": ${p.question}".take(ReviewQueue.MAX_SUMMARY_CHARS),
            proposal = mapOf(
                "judgment_id" to p.judgmentId, "title" to p.title.take(200), "question" to p.question,
                "shape" to when (p.shape) { DraftShape.YES_NO -> "yes_no"; DraftShape.PICK -> "pick"; DraftShape.SCORE -> "score" },
                "options" to when (p.shape) {
                    DraftShape.YES_NO -> listOf(p.input.positive, p.input.negative).joinToString("\n")
                    DraftShape.PICK -> p.input.options.joinToString("\n")
                    DraftShape.SCORE -> p.input.bands.joinToString("\n")
                },
                "invariant" to p.input.invariant.take(2_000), "pack" to packSlug,
            ),
            actionType = "judgment.add", actionParams = emptyMap(),
        )
    }
}
