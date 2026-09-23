package dev.loupe.kit.review

import dev.loupe.kit.packs.PackFormat
import dev.loupe.kit.packs.PackJudgments
import dev.loupe.kit.packs.PackParse
import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.watchers.EXAMPLE_PACK
import dev.loupe.kit.watchers.TEST_TMP
import dev.loupe.persistence.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from Loupe Station's `tests/test_review.py` (ea7697a): the lifecycle, validation, the size
 * cap, approve-with-edits, reject-needs-a-reason, fail -> retry -> applied, failed items can be
 * rejected, the append-only log and the list filters with a cursor. Agent keys, rate limits and
 * HTTP routes have no phone equivalent. Loupe's additions: a source key is never queued twice,
 * undo of a reversible action, and the queue reopens from its files.
 */
class ReviewQueueTest {
    private val t0 = "2026-09-24T10:00:00Z"

    private fun home(): String {
        val dir = "$TEST_TMP/review-" + Random.nextLong().toULong().toString(16)
        PlatformFiles.createDirectories(dir)
        return dir
    }

    private fun textItem(title: String = "Reply to Sara", key: String = "k-$title", summary: String = "Late order. password=hunter2hunter2") =
        ReviewProposal(
            feature = ReviewRegistry.MAIL, kind = "text", title = title, sourceKey = key, inputSummary = summary,
            proposal = mapOf("text" to "Hi Sara, sorry about the delay."), actionType = "none", actionParams = emptyMap(),
        )

    private fun done(r: ReviewResult): ReviewItem = assertIs<ReviewResult.Done>(r).item

    private fun refused(r: ReviewResult): ReviewRefusal = assertIs<ReviewResult.Refused>(r).refusal

    @Test
    fun submitIsAlwaysPendingAndRedactsSummary() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        assertEquals(ReviewStatus.PENDING, item.status)
        assertFalse(item.edited)
        assertNull(item.decidedAt)
        assertTrue("hunter2hunter2" !in item.inputSummary && "[secret]" in item.inputSummary, item.inputSummary)
        assertEquals("feature:mail_triage", item.submittedBy)
        assertTrue(ReviewQueue.ID_PATTERN.matches(item.id))
    }

    @Test
    fun submitValidation() {
        val q = ReviewQueue.inMemory()
        val cases = listOf(
            textItem().copy(feature = "nope") to "feature",
            textItem().copy(kind = "nope") to "kind",
            textItem().copy(actionType = "rm_rf") to "action.type",
            textItem().copy(title = "") to "title",
            textItem().copy(title = "two\nlines") to "title",
            textItem().copy(proposal = mapOf("text" to "")) to "proposal.text",
            textItem().copy(proposal = mapOf("text" to "x", "evil" to "1")) to "proposal.evil",
            textItem().copy(actionType = "judgment.add") to "action.type", // judgment.add needs a judgment
            textItem().copy(actionParams = mapOf("x" to "1")) to "action.params.x",
            textItem().copy(inputSummary = "s".repeat(2001)) to "input_summary",
        )
        for ((p, loc) in cases) {
            val r = refused(q.submit(p, t0))
            assertEquals(422, r.code)
            assertTrue(r.messages.any { it.startsWith(loc) }, "$loc: ${r.messages}")
        }
        assertEquals(0, q.all().size)
    }

    @Test
    fun submitSizeCap() {
        val q = ReviewQueue.inMemory()
        val huge = textItem().copy(kind = "text", proposal = mapOf("text" to "x".repeat(20_000)))
        done(q.submit(huge, t0)) // every field at its limit is still under the cap
        // The kinds' own limits keep every phone proposal under Station's 128 000-byte cap.
        val widest = ReviewProposal(ReviewRegistry.JUDGMENT, "judgment", "Big", "big", "s".repeat(2000), mapOf(
            "judgment_id" to "j-big", "title" to "t", "question" to "Is it?", "shape" to "pick", "options" to "€".repeat(20_000),
            "invariant" to "€".repeat(2_000), "pack" to "p",
        ), "judgment.add", emptyMap())
        done(q.submit(widest, t0))
    }

    @Test
    fun approveWithEditsRunsActionAndLogs() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        val approved = done(q.approve(item.id, "ui", mapOf("text" to "Hi Sara, so sorry!"), "softer", t0))
        assertEquals(ReviewStatus.APPROVED, approved.status)
        val applied = done(q.finish(item.id, "ui", mapOf("done" to "recorded"), t0))
        assertEquals(ReviewStatus.APPLIED, applied.status)
        assertTrue(applied.edited)
        assertEquals("softer", applied.decisionNote)
        assertEquals("Hi Sara, so sorry!", applied.proposal["text"])
        assertTrue(applied.originalProposal["text"]!!.startsWith("Hi Sara, sorry"))
        assertEquals(mapOf("done" to "recorded"), applied.applyResult)
        assertEquals(1, applied.attempts)
        val log = q.log(item.id)
        assertEquals(listOf("submitted", "approved", "applied"), log.reversed().map { it.event })
        assertEquals(mapOf("edited" to "true"), log[1].details)
        assertEquals("ui", log[1].actor)
        assertEquals("feature:mail_triage", log[2].actor)
        assertEquals(409, refused(q.approve(item.id, "ui", null, null, t0)).code)
    }

    @Test
    fun approveUneditedIsNotMarkedEdited() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        assertFalse(done(q.approve(item.id, "ui", HashMap(item.proposal), null, t0)).edited)
    }

    @Test
    fun invalidEditsKeepItemPending() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        assertEquals(422, refused(q.approve(item.id, "ui", mapOf("text" to ""), null, t0)).code)
        assertEquals(ReviewStatus.PENDING, q.get(item.id)!!.status)
    }

    @Test
    fun rejectRequiresReason() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        for (bad in listOf(null, "", "   ")) assertEquals(422, refused(q.reject(item.id, "ui", bad, t0)).code)
        val r = done(q.reject(item.id, "ui", "Wrong tone", t0))
        assertEquals(ReviewStatus.REJECTED, r.status)
        assertEquals("Wrong tone", r.decisionNote)
        assertIs<ReviewResult.Refused>(q.approve(item.id, "ui", null, null, t0))
        assertEquals("Wrong tone", q.log(item.id)[0].note)
    }

    @Test
    fun failedThenRetryThenApplied() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        done(q.approve(item.id, "ui", null, null, t0))
        val failed = done(q.fail(item.id, "ui", "The file is not there any more.", t0))
        assertEquals(ReviewStatus.FAILED, failed.status)
        assertEquals(mapOf("error" to "The file is not there any more."), failed.applyResult)
        assertEquals(ReviewStatus.APPROVED, done(q.retry(item.id, "ui", t0)).status)
        val ok = done(q.finish(item.id, "ui", mapOf("sent" to "true"), t0))
        assertEquals(ReviewStatus.APPLIED, ok.status)
        assertEquals(2, ok.attempts)
        assertEquals(listOf("submitted", "approved", "failed", "retried", "applied"), q.log(item.id).reversed().map { it.event })
        assertEquals(409, refused(q.retry(item.id, "ui", t0)).code)
    }

    @Test
    fun failedItemCanBeRejected() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        done(q.approve(item.id, "ui", null, null, t0))
        done(q.fail(item.id, "ui", "nope", t0))
        assertEquals(ReviewStatus.REJECTED, done(q.reject(item.id, "ui", "give up", t0)).status)
    }

    @Test
    fun listFiltersAndCursor() {
        val q = ReviewQueue.inMemory()
        val ids = (0 until 5).map { done(q.submit(textItem(title = "t$it"), t0)).id }
        done(q.submit(textItem(title = "watch me").copy(feature = ReviewRegistry.WATCHER), t0))
        done(q.reject(ids[0], "ui", "no", t0))
        val page = q.list(listOf(ReviewStatus.PENDING), null, 2, null)
        assertEquals(listOf("watch me", "t4"), page.items.map { it.title })
        val page2 = q.list(listOf(ReviewStatus.PENDING), null, 2, page.nextCursor)
        assertEquals(listOf("t3", "t2"), page2.items.map { it.title })
        assertEquals(listOf("watch me"), q.list(emptyList(), ReviewRegistry.WATCHER, 50, null).items.map { it.title })
        assertEquals(5, q.counts()[ReviewStatus.PENDING])
        assertEquals(1, q.counts()[ReviewStatus.REJECTED])
        assertEquals(5, q.toReview())
    }

    // ------------------------------------------------------------------ Loupe's additions
    @Test
    fun aSourceKeyIsNeverQueuedTwice() {
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(textItem(), t0))
        done(q.reject(item.id, "ui", "not needed", t0))
        assertIs<ReviewResult.Duplicate>(q.submit(textItem(), t0))
        assertEquals(1, q.all().size)
    }

    @Test
    fun undoOnlyForAppliedReversibleActions() {
        val q = ReviewQueue.inMemory()
        val none = done(q.submit(textItem(), t0))
        done(q.approve(none.id, "ui", null, null, t0))
        done(q.finish(none.id, "ui", emptyMap(), t0))
        assertEquals(409, refused(q.undo(none.id, "ui", t0)).code) // "none" is not reversible
        val mail = done(q.submit(ReviewProposal(ReviewRegistry.MAIL, "mail_verdict", "Confirm phishing: x", "mail:1", "",
            mapOf("item_id" to "1", "subject" to "x", "verdict" to "phishing"), "mail.confirm_phishing", emptyMap()), t0))
        assertEquals(409, refused(q.undo(mail.id, "ui", t0)).code) // not applied yet
        done(q.approve(mail.id, "ui", null, null, t0))
        done(q.finish(mail.id, "ui", emptyMap(), t0))
        assertEquals(ReviewStatus.UNDONE, done(q.undo(mail.id, "ui", t0)).status)
        assertEquals("undone", q.log(mail.id)[0].event)
    }

    @Test
    fun judgmentProposalsPassTheLintBeforeApproval() {
        val pack = assertIs<PackParse.Valid>(PackFormat.parse(PlatformFiles.readText(EXAMPLE_PACK)!!)).pack
        val plans = PackJudgments.plan(pack, emptyList())
        val proposals = ReviewProducers.judgments(plans, pack.slug)
        assertEquals(plans.count { it.addable }, proposals.size)
        val q = ReviewQueue.inMemory()
        val item = done(q.submit(proposals.first { it.proposal["judgment_id"] == "j-complaint-triage-team" }, t0))
        // an edit that breaks the lint (one option) refuses the approval and keeps it pending
        val broken = item.proposal + ("options" to "kitchen")
        assertEquals(422, refused(q.approve(item.id, "ui", broken, null, t0)).code)
        val edited = item.proposal + ("title" to "Which team?")
        val ok = done(q.approve(item.id, "ui", edited, null, t0))
        val j = assertIs<BookResult.Created>(ReviewRegistry.judgmentFor(ok.proposal, emptyList())).judgment
        assertEquals("j-complaint-triage-team", j.id)
        assertEquals("Which team?", j.title)
        // an approval never replaces one of yours: a clash gets a free id
        val clash = assertIs<BookResult.Created>(ReviewRegistry.judgmentFor(ok.proposal, listOf(j))).judgment
        assertTrue(clash.id != j.id)
    }

    @Test
    fun reopensFromItsFilesAndTheLogIsAppendOnly() {
        val h = home()
        val q = ReviewQueue.open(h)
        val a = done(q.submit(textItem(title = "a"), t0))
        val b = done(q.submit(textItem(title = "b"), t0))
        done(q.approve(a.id, "ui", null, "ok", t0))
        done(q.finish(a.id, "ui", mapOf("done" to "recorded"), t0))
        done(q.reject(b.id, "ui", "no", t0))
        val logText = PlatformFiles.readText("$h/${ReviewQueue.LOG_FILE}")!!
        val again = ReviewQueue.open(h)
        assertEquals(q.all(), again.all())
        assertEquals(q.log(null), again.log(null))
        assertEquals(0, again.unreadableLines)
        val c = done(again.submit(textItem(title = "c"), t0))
        assertTrue(c.seq > b.seq)
        // the old log lines are untouched: the file only grew
        assertTrue(PlatformFiles.readText("$h/${ReviewQueue.LOG_FILE}")!!.startsWith(logText))
        assertIs<ReviewResult.Duplicate>(again.submit(textItem(title = "a"), t0))
    }
}
