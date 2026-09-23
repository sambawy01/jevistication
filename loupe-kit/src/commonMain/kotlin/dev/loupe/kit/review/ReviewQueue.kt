package dev.loupe.kit.review

import dev.loupe.kit.privacy.SecretRules
import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import dev.loupe.persistence.StoreLock
import kotlin.random.Random

/*
 * The Review queue: every action Loupe proposes waits here until the person approves or rejects it.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/review/{service,store,registry}.py` and `static/js/review.js`, commit
 * ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). Copied: the statuses and the state machine
 *
 *     pending --approve--> approved --action ok--> applied
 *                                   --action fails--> failed --retry--> approved -> applied | failed
 *     pending | failed --reject (reason required)--> rejected
 *
 * with every step one status change plus one row in an append-only log; approval with edits
 * (validated against the kind, `edited` set when they differ from the original); the 16-hex ids,
 * the 128 000-byte item cap, the 1 000-character note, the one-line 200-character title, the
 * 2 000-character input summary with credentials masked (`redact_text`), the event names
 * (submitted / approved / failed / retried / applied / rejected), the actor strings
 * (`feature:<id>`, `ui`), attempts, newest-first listing with a cursor, and the per-status counts.
 * Station's error codes (404 / 409 / 413 / 422) are kept as [ReviewRefusal.code].
 *
 * Loupe's changes, for a phone with one person and no server: there are no agent keys, rate
 * limits or HTTP routes (only the app's own checks submit, as Station's in-process
 * `submit_proposal` does); the features are the phone's producers (privacy check, mail triage,
 * watchers, judgments); an item carries a `source_key` and a producer never queues the same key
 * twice (the checks re-run on every scan, and a rejected proposal must not come back); running an
 * action is the app's (Swift) job between [approve] and [finish] / [fail]; and a reversible
 * action that was applied can be undone ([undo], a sixth status, `undone`). Storage is two plain
 * files beside the decision ledger instead of SQLite: `review-items.json` (replaced atomically) and
 * `review-log.jsonl` (appended and synced, never rewritten — the log's append-only triggers).
 */

object ReviewStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val APPLIED = "applied"
    const val FAILED = "failed"
    const val UNDONE = "undone"
    val ALL: List<String> = listOf(PENDING, APPROVED, REJECTED, APPLIED, FAILED, UNDONE)
}

/** One proposed action. [proposal] and [actionParams] are flat string maps (Swift-friendly). */
data class ReviewItem(
    val seq: Int,
    val id: String,
    val feature: String,
    val kind: String,
    val title: String,
    /** The producer's key for what it proposed (a finding key, an item id): never queued twice. */
    val sourceKey: String,
    val inputSummary: String,
    val proposal: Map<String, String>,
    val originalProposal: Map<String, String>,
    val actionType: String,
    val actionParams: Map<String, String>,
    val status: String,
    val edited: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val decidedAt: String?,
    val decisionNote: String?,
    /** What the action returned ({"error": ...} when it failed). */
    val applyResult: Map<String, String>,
    val attempts: Int,
    val submittedBy: String,
) {
    val reversible: Boolean get() = ReviewRegistry.action(actionType)?.reversible == true
    val isOpen: Boolean get() = status == ReviewStatus.PENDING || status == ReviewStatus.FAILED
    val error: String? get() = applyResult["error"]
}

/** One row of the append-only decision log. */
data class ReviewLogEntry(
    val seq: Int,
    val at: String,
    val itemId: String,
    val event: String,
    val actor: String,
    val status: String?,
    val note: String?,
    val details: Map<String, String>,
)

/** Why a call was refused: Station's HTTP code (404, 409, 413, 422) and the messages. */
data class ReviewRefusal(val code: Int, val messages: List<String>) {
    val message: String get() = messages.joinToString("; ")
}

/** A call's outcome: the item as it now stands, or why nothing changed. */
sealed class ReviewResult {
    class Done(val item: ReviewItem) : ReviewResult()

    /** [submit] only: the producer already queued this source key; nothing was added. */
    class Duplicate(val item: ReviewItem) : ReviewResult()

    class Refused(val refusal: ReviewRefusal) : ReviewResult()
}

/** What a producer proposes; [ReviewQueue.submit] validates it. */
data class ReviewProposal(
    val feature: String,
    val kind: String,
    val title: String,
    val sourceKey: String,
    val inputSummary: String,
    val proposal: Map<String, String>,
    val actionType: String,
    val actionParams: Map<String, String>,
)

/** A page of items, newest first; [nextCursor] is the `seq` to pass for the next page. */
data class ReviewPage(val items: List<ReviewItem>, val nextCursor: Int?)

class ReviewQueue private constructor(private val home: String?) {
    private val lock = StoreLock()
    private var items: List<ReviewItem> = emptyList()
    private var log: List<ReviewLogEntry> = emptyList()
    private var nextSeq = 1
    private var nextLogSeq = 1

    /** Lines of the files that could not be read when the queue was opened. */
    var unreadableLines: Int = 0
        private set

    private val itemsPath get() = "$home/$ITEMS_FILE"
    private val logPath get() = "$home/$LOG_FILE"

    // ------------------------------------------------------------------ submit
    /** Queues [p] as `pending`, or says why not. Never runs anything. */
    fun submit(p: ReviewProposal, at: String): ReviewResult = lock.withLock {
        val errors = mutableListOf<String>()
        if (p.feature !in ReviewRegistry.FEATURES) errors += "feature: must be one of ${ReviewRegistry.FEATURES.joinToString(", ")}"
        val kind = ReviewRegistry.kind(p.kind)
        val action = ReviewRegistry.action(p.actionType)
        if (kind == null) errors += "kind: unknown kind; registered: ${ReviewRegistry.kindNames().joinToString(", ")}"
        if (action == null) errors += "action.type: unknown action; registered: ${ReviewRegistry.actionNames().joinToString(", ")}"
        if (errors.isNotEmpty()) return@withLock refused(422, errors)
        if (action!!.kinds.isNotEmpty() && kind!!.name !in action.kinds) {
            return@withLock refused(422, listOf("action.type: ${action.type} cannot apply a ${kind.name} proposal (only ${action.kinds.joinToString(", ")})"))
        }
        val title = p.title.trim()
        if (title.isEmpty() || title.length > MAX_TITLE_CHARS || hasControl(title) || '\n' in title) errors += "title: must be one line of text"
        if (p.inputSummary.length > MAX_SUMMARY_CHARS) errors += "input_summary: must be at most $MAX_SUMMARY_CHARS characters"
        if (p.sourceKey.isBlank() || p.sourceKey.length > 300) errors += "source_key: must be 1-300 characters"
        errors += kind!!.check(p.proposal).map { "proposal.$it" }
        errors += (p.actionParams.keys - action.params).map { "action.params.$it: unknown field" }
        if (errors.isNotEmpty()) return@withLock refused(422, errors)
        val size = canon(p.proposal).encodeToByteArray().size + canon(p.actionParams).encodeToByteArray().size +
            title.encodeToByteArray().size + p.inputSummary.encodeToByteArray().size
        if (size > MAX_ITEM_BYTES) return@withLock refused(413, listOf("The proposal is too large ($size bytes, limit $MAX_ITEM_BYTES)."))
        items.firstOrNull { it.feature == p.feature && it.sourceKey == p.sourceKey }?.let { return@withLock ReviewResult.Duplicate(it) }
        val actor = "feature:${p.feature}"
        val item = ReviewItem(
            seq = nextSeq, id = newId(), feature = p.feature, kind = kind.name, title = title, sourceKey = p.sourceKey,
            // Summaries are shown to a person; any credential-looking value in them is masked first.
            inputSummary = SecretRules.redactText(p.inputSummary), proposal = p.proposal, originalProposal = p.proposal,
            actionType = action.type, actionParams = p.actionParams, status = ReviewStatus.PENDING, edited = false,
            createdAt = at, updatedAt = at, decidedAt = null, decisionNote = null, applyResult = emptyMap(), attempts = 0,
            submittedBy = actor,
        )
        commit(item, isNew = true, entry(at, item.id, "submitted", actor, ReviewStatus.PENDING, null,
            mapOf("feature" to item.feature, "kind" to item.kind, "action" to item.actionType)))
        ReviewResult.Done(item)
    }

    // ------------------------------------------------------------------ decide
    /**
     * Approves a pending item, optionally with [edited] replacing its proposal (validated first; an
     * invalid edit keeps it pending). The item is then `approved`: the caller runs the action and
     * reports [finish] or [fail]. The action's own validation (Station's `validate`) runs on the
     * final proposal; its messages refuse the approval (422).
     */
    fun approve(id: String, actor: String, edited: Map<String, String>?, note: String?, at: String): ReviewResult =
        lock.withLock {
            val item = find(id) ?: return@withLock notFound()
            if (item.status != ReviewStatus.PENDING) {
                return@withLock refused(409, listOf("This item is ${item.status}; only pending items can be approved."))
            }
            val cleanNote = when (val n = note(note, required = false)) {
                is NoteCheck.Bad -> return@withLock refused(422, listOf(n.message))
                is NoteCheck.Ok -> n.note
            }
            val final = edited ?: item.proposal
            if (edited != null && canon(edited).encodeToByteArray().size > MAX_ITEM_BYTES) {
                return@withLock refused(413, listOf("The edited proposal is too large."))
            }
            val errors = checkProposal(item, final)
            if (errors.isNotEmpty()) return@withLock refused(422, errors)
            val wasEdited = canon(final) != canon(item.originalProposal)
            val next = item.copy(
                status = ReviewStatus.APPROVED, proposal = final, edited = wasEdited, decidedAt = at, decisionNote = cleanNote, updatedAt = at,
            )
            commit(next, isNew = false, entry(at, id, "approved", actor, ReviewStatus.APPROVED, cleanNote, mapOf("edited" to wasEdited.toString())))
            ReviewResult.Done(next)
        }

    /** A failed item goes back to `approved` for its action to run again (Station's retry). */
    fun retry(id: String, actor: String, at: String): ReviewResult = lock.withLock {
        val item = find(id) ?: return@withLock notFound()
        if (item.status != ReviewStatus.FAILED) {
            return@withLock refused(409, listOf("Only failed items can be retried (this one is ${item.status})."))
        }
        val errors = checkProposal(item, item.proposal)
        if (errors.isNotEmpty()) return@withLock refused(422, errors)
        val next = item.copy(status = ReviewStatus.APPROVED, updatedAt = at)
        commit(next, isNew = false, entry(at, id, "retried", actor, ReviewStatus.APPROVED, null, emptyMap()))
        ReviewResult.Done(next)
    }

    /** Rejects a pending or failed item; a reason is required. */
    fun reject(id: String, actor: String, note: String?, at: String): ReviewResult = lock.withLock {
        val item = find(id) ?: return@withLock notFound()
        if (item.status != ReviewStatus.PENDING && item.status != ReviewStatus.FAILED) {
            return@withLock refused(409, listOf("This item is ${item.status}; only pending or failed items can be rejected."))
        }
        val cleanNote = when (val n = note(note, required = true)) {
            is NoteCheck.Bad -> return@withLock refused(422, listOf(n.message))
            is NoteCheck.Ok -> n.note
        }
        val next = item.copy(status = ReviewStatus.REJECTED, decidedAt = at, decisionNote = cleanNote, updatedAt = at)
        commit(next, isNew = false, entry(at, id, "rejected", actor, ReviewStatus.REJECTED, cleanNote, emptyMap()))
        ReviewResult.Done(next)
    }

    /** The action ran: `approved` -> `applied`, with what it returned. */
    fun finish(id: String, actor: String, result: Map<String, String>, at: String): ReviewResult =
        outcome(id, actor, ReviewStatus.APPLIED, "applied", result, at)

    /** The action failed: `approved` -> `failed`, with the reason the person sees. */
    fun fail(id: String, actor: String, error: String, at: String): ReviewResult =
        outcome(id, actor, ReviewStatus.FAILED, "failed", mapOf("error" to error), at)

    private fun outcome(id: String, actor: String, status: String, event: String, result: Map<String, String>, at: String): ReviewResult =
        lock.withLock {
            val item = find(id) ?: return@withLock notFound()
            if (item.status != ReviewStatus.APPROVED) {
                return@withLock refused(409, listOf("This item is ${item.status}; only an approved item's action can finish."))
            }
            val next = item.copy(status = status, applyResult = result, attempts = item.attempts + 1, updatedAt = at)
            commit(next, isNew = false, entry(at, id, event, actor, status, null, result))
            ReviewResult.Done(next)
        }

    /** A reversible, applied item was undone by the app: `applied` -> `undone`. */
    fun undo(id: String, actor: String, at: String): ReviewResult = lock.withLock {
        val item = find(id) ?: return@withLock notFound()
        if (item.status != ReviewStatus.APPLIED) return@withLock refused(409, listOf("Only applied items can be undone (this one is ${item.status})."))
        if (!item.reversible) return@withLock refused(409, listOf("This action cannot be undone."))
        val next = item.copy(status = ReviewStatus.UNDONE, updatedAt = at)
        commit(next, isNew = false, entry(at, id, "undone", actor, ReviewStatus.UNDONE, null, emptyMap()))
        ReviewResult.Done(next)
    }

    // ------------------------------------------------------------------ read
    fun get(id: String): ReviewItem? = lock.withLock { find(id) }

    fun all(): List<ReviewItem> = lock.withLock { items.sortedByDescending { it.seq } }

    /** Newest first, filtered by [statuses] (empty = all) and [feature] (null = all). */
    fun list(statuses: List<String>, feature: String?, limit: Int, cursor: Int?): ReviewPage = lock.withLock {
        val rows = items.asSequence()
            .filter { statuses.isEmpty() || it.status in statuses }
            .filter { feature == null || it.feature == feature }
            .filter { cursor == null || it.seq < cursor }
            .sortedByDescending { it.seq }
            .take(limit + 1).toList()
        val more = rows.size > limit
        val page = rows.take(limit)
        ReviewPage(page, if (more && page.isNotEmpty()) page.last().seq else null)
    }

    /** Items per status (every status present, zero when none). */
    fun counts(): Map<String, Int> = lock.withLock {
        ReviewStatus.ALL.associateWith { s -> items.count { it.status == s } }
    }

    /** What Now's card shows: pending plus failed (both need the person). */
    fun toReview(): Int = lock.withLock { items.count { it.isOpen } }

    /** The decision log, newest first, for one item or all. */
    fun log(itemId: String?): List<ReviewLogEntry> = lock.withLock {
        log.filter { itemId == null || it.itemId == itemId }.sortedByDescending { it.seq }
    }

    // ------------------------------------------------------------------ internals
    private fun find(id: String): ReviewItem? = items.firstOrNull { it.id == id }

    private fun checkProposal(item: ReviewItem, proposal: Map<String, String>): List<String> {
        val kind = ReviewRegistry.kind(item.kind)
        val action = ReviewRegistry.action(item.actionType)
        if (kind == null || action == null) return listOf("action: this item's kind or action is no longer available in this version")
        val errors = kind.check(proposal).map { "proposal.$it" }
        return errors.ifEmpty { action.validate(proposal) }
    }

    private fun entry(at: String, itemId: String, event: String, actor: String, status: String?, note: String?, details: Map<String, String>) =
        ReviewLogEntry(nextLogSeq, at, itemId, event, actor, status, note, details)

    /** Log line first (synced), then the items snapshot (atomic): a crash never loses a decision. */
    private fun commit(item: ReviewItem, isNew: Boolean, e: ReviewLogEntry) {
        val nextItems = if (isNew) items + item else items.map { if (it.id == item.id) item else it }
        if (home != null) {
            PlatformFiles.appendDurably(logPath, JsonText.compact(ReviewCodec.encodeLog(e)) + "\n")
            PlatformFiles.writeAtomically(itemsPath, JsonText.pretty(ReviewCodec.encodeItems(nextItems)))
        }
        items = nextItems
        log = log + e
        nextLogSeq++
        if (isNew) nextSeq++
    }

    private fun load() {
        val h = home ?: return
        PlatformFiles.createDirectories(h)
        PlatformFiles.readText(itemsPath)?.takeIf { it.isNotBlank() }?.let { text ->
            try {
                items = ReviewCodec.decodeItems(JsonValue.parse(text))
            } catch (e: Exception) {
                unreadableLines++
            }
        }
        PlatformFiles.readText(logPath)?.lineSequence()?.filter { it.isNotBlank() }?.forEach { line ->
            try {
                log = log + ReviewCodec.decodeLog(JsonValue.parse(line))
            } catch (e: Exception) {
                unreadableLines++
            }
        }
        nextSeq = (items.maxOfOrNull { it.seq } ?: 0) + 1
        nextLogSeq = (log.maxOfOrNull { it.seq } ?: 0) + 1
    }

    private sealed class NoteCheck {
        class Ok(val note: String?) : NoteCheck()

        class Bad(val message: String) : NoteCheck()
    }

    private fun note(note: String?, required: Boolean): NoteCheck {
        if (note == null || note.isBlank()) return if (required) NoteCheck.Bad("note: a reason is required to reject") else NoteCheck.Ok(null)
        if (note.length > MAX_NOTE_CHARS || hasControl(note)) return NoteCheck.Bad("note: must be text of at most $MAX_NOTE_CHARS characters")
        return NoteCheck.Ok(note.trim())
    }

    private fun refused(code: Int, messages: List<String>) = ReviewResult.Refused(ReviewRefusal(code, messages))

    private fun notFound() = refused(404, listOf("No review item with that id."))

    private fun newId(): String {
        while (true) {
            val id = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (items.none { it.id == id }) return id
        }
    }

    companion object {
        const val ITEMS_FILE: String = "review-items.json"
        const val LOG_FILE: String = "review-log.jsonl"
        const val MAX_ITEM_BYTES: Int = 128_000
        const val MAX_NOTE_CHARS: Int = 1000
        const val MAX_TITLE_CHARS: Int = 200
        const val MAX_SUMMARY_CHARS: Int = 2000
        val ID_PATTERN: Regex = Regex("^[a-f0-9]{16}$")

        /** Opens (creating if needed) the queue in [home], beside the decision ledger. */
        @Throws(Exception::class)
        fun open(home: String): ReviewQueue = ReviewQueue(home).also { q -> q.lock.withLock { q.load() } }

        /** A queue that keeps nothing on disk (previews, tests). */
        fun inMemory(): ReviewQueue = ReviewQueue(null)

        internal fun hasControl(s: String): Boolean = s.any { (it.code < 32 && it != '\n' && it != '\t') || it.code == 127 }

        internal fun canon(m: Map<String, String>): String =
            JsonText.compact(JsonValue.Obj(LinkedHashMap(m.entries.sortedBy { it.key }.associate { it.key to (JsonValue.Str(it.value) as JsonValue) })))
    }
}
