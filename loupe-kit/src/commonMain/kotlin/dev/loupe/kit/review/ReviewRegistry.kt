package dev.loupe.kit.review

import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.DraftShape
import dev.loupe.kit.judgments.EditorInput
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.templates.UserJudgment

/*
 * Proposal kinds and approval actions.
 *
 * PROVENANCE: follows Loupe Station's registry (`~/laya-studio`, `laya_studio/review/registry.py`,
 * commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e): a *kind* declares the shape of a proposal (here
 * a flat map of named text fields with limits, required or not, and allowed values, instead of a
 * JSON schema — every phone proposal is flat text), an *action* declares what approval does, which
 * kinds it accepts, its params and its own `validate` (run before approval; its messages refuse it).
 * Station's built-ins `text` / `none` are kept; `judgment.add` is the phone's `save_preset` (a
 * proposed judgment passes the same lint as "Write your own" before it is added). Loupe adds
 * `reversible`: whether an applied action can be undone. The handlers themselves run in the app.
 */

/** One field of a proposal kind. */
data class ReviewField(val name: String, val required: Boolean, val maxChars: Int, val allowed: List<String> = emptyList())

data class ReviewKind(val name: String, val fields: List<ReviewField>) {
    /** Problems with [proposal] as `field: message`, like Station's schema errors. */
    fun check(proposal: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        val known = fields.associateBy { it.name }
        (proposal.keys - known.keys).sorted().forEach { out += "$it: unknown field" }
        for (f in fields) {
            val v = proposal[f.name]
            when {
                v == null -> if (f.required) out += "${f.name}: required"
                f.required && v.isBlank() -> out += "${f.name}: must not be empty"
                v.length > f.maxChars -> out += "${f.name}: must be at most ${f.maxChars} characters"
                f.allowed.isNotEmpty() && v !in f.allowed -> out += "${f.name}: must be one of ${f.allowed.joinToString(", ")}"
            }
        }
        return out
    }
}

data class ReviewAction(
    val type: String,
    /** The kinds it accepts (empty = any). */
    val kinds: List<String>,
    val params: Set<String>,
    val reversible: Boolean,
    /** What the Review screen's Approve button says. */
    val verb: String,
    private val validator: (Map<String, String>) -> List<String> = { emptyList() },
) {
    fun validate(proposal: Map<String, String>): List<String> = validator(proposal)
}

object ReviewRegistry {
    /** The phone's producers (Station: email_reply, extraction, ..., watcher). */
    const val PRIVACY = "privacy_check"
    const val MAIL = "mail_triage"
    const val WATCHER = "watcher"
    const val JUDGMENT = "judgment"
    /** Epic #7 child 16: a reply draft the opt-in writing assistant wrote (Station's `email_reply`). */
    const val EMAIL_REPLY = "email_reply"

    /**
     * The agent tier (docs/AGENT.md): actions prepared from an item the on-device engine flagged.
     *
     * Its own feature, and its own kinds, rather than borrowing [EMAIL_REPLY]'s: the shipped
     * writing-assistant path must keep the exact shape it has, and an agent proposal carries one
     * field the assistant's does not -- `evidence`, the on-device decision that justified spending
     * a call on the item at all. The queue is the agent's only output: it prepares, a person
     * approves, and the handler runs in the app as for every other action.
     */
    const val AGENT = "agent"
    val FEATURES: List<String> = listOf(PRIVACY, MAIL, WATCHER, JUDGMENT, EMAIL_REPLY, AGENT)

    fun featureTitle(feature: String): String = when (feature) {
        PRIVACY -> "Privacy check"
        MAIL -> "Mail triage"
        WATCHER -> "Watchers"
        JUDGMENT -> "Judgments"
        EMAIL_REPLY -> "Reply drafts"
        AGENT -> "Agent"
        else -> feature
    }

    private val kinds: Map<String, ReviewKind> = listOf(
        ReviewKind("text", listOf(ReviewField("text", true, 20_000))),
        // Station's email_reply kind, trimmed: approving records it; Loupe never sends or saves it into a mailbox.
        ReviewKind("email_reply", listOf(
            ReviewField("item_id", true, 1_000), ReviewField("to", false, 300), ReviewField("subject", true, 300),
            ReviewField("body", true, 10_000), ReviewField("provider", true, 200), ReviewField("warnings", false, 1_000),
        )),
        ReviewKind("file_action", listOf(
            ReviewField("path", true, 1_000), ReviewField("name", true, 300), ReviewField("keep", false, 1_000),
        )),
        ReviewKind("mail_verdict", listOf(
            ReviewField("item_id", true, 1_000), ReviewField("subject", true, 300), ReviewField("sender", false, 300),
            ReviewField("verdict", true, 20, listOf("phishing")),
        )),
        ReviewKind("finding_verdict", listOf(
            ReviewField("finding_key", true, 300), ReviewField("title", true, 300), ReviewField("watcher", false, 80),
            ReviewField("verdict", true, 20, listOf("confirmed")),
        )),
        ReviewKind("judgment", listOf(
            ReviewField("judgment_id", true, 80), ReviewField("title", true, 200), ReviewField("question", true, 600),
            ReviewField("shape", true, 10, listOf("yes_no", "pick", "score")), ReviewField("options", true, 20_000),
            ReviewField("invariant", false, 2_000), ReviewField("pack", false, 80),
        )),
        // The agent tier's four kinds. Every one carries `origin` (who prepared it -- "Loupe, on
        // this device" or "<provider> (Online)") and `evidence` (the on-device decision behind it),
        // because an agent proposal with neither cannot be shown honestly and so must not be queued.
        ReviewKind("agent_reminder", listOf(
            ReviewField("item_id", true, 1_000), ReviewField("when", true, 30), ReviewField("text", true, 200),
            ReviewField("because", false, 400), ReviewField("origin", true, 200), ReviewField("evidence", true, 300),
        )),
        ReviewKind("agent_event", listOf(
            ReviewField("item_id", true, 1_000), ReviewField("start", true, 30), ReviewField("end", false, 30),
            ReviewField("subject", true, 300), ReviewField("location", false, 300), ReviewField("because", false, 400),
            ReviewField("origin", true, 200), ReviewField("evidence", true, 300),
        )),
        ReviewKind("agent_note", listOf(
            ReviewField("item_id", true, 1_000), ReviewField("headline", true, 300), ReviewField("detail", false, 2_000),
            ReviewField("origin", true, 200), ReviewField("evidence", true, 300),
        )),
        ReviewKind("agent_reply", listOf(
            ReviewField("item_id", true, 1_000), ReviewField("to", false, 300), ReviewField("subject", true, 300),
            ReviewField("body", true, 10_000), ReviewField("language", false, 60), ReviewField("warnings", false, 1_000),
            ReviewField("notes", false, 1_000), ReviewField("origin", true, 200), ReviewField("evidence", true, 300),
        )),
    ).associateBy { it.name }

    private val actions: Map<String, ReviewAction> = listOf(
        ReviewAction("none", emptyList(), emptySet(), reversible = false, verb = "Approve"),
        ReviewAction("privacy.remove_copy", listOf("file_action"), setOf("finding_key", "item_id"), reversible = true, verb = "Remove copy"),
        ReviewAction("mail.confirm_phishing", listOf("mail_verdict"), emptySet(), reversible = true, verb = "Confirm phishing"),
        ReviewAction("watcher.confirm", listOf("finding_verdict"), emptySet(), reversible = true, verb = "Keep watching"),
        ReviewAction("judgment.add", listOf("judgment"), emptySet(), reversible = true, verb = "Add judgment") { p ->
            JudgmentBook.findings(judgmentInput(p)).map { "proposal: ${it.message}" }.distinct()
        },
        // The agent tier. `agent.draft` runs nothing: approving records the draft, exactly as the
        // writing assistant's does -- Loupe never sends it or files it into a mailbox. The other
        // three hand the approved item to the platform (local notifications, the calendar).
        ReviewAction("agent.draft", listOf("agent_reply"), emptySet(), reversible = false, verb = "Keep draft"),
        ReviewAction("agent.remind", listOf("agent_reminder"), emptySet(), reversible = true, verb = "Add reminder"),
        ReviewAction("agent.calendar", listOf("agent_event"), emptySet(), reversible = true, verb = "Add to calendar"),
        ReviewAction("agent.note", listOf("agent_note"), emptySet(), reversible = true, verb = "Keep"),
    ).associateBy { it.type }

    fun kind(name: String): ReviewKind? = kinds[name]

    fun action(type: String): ReviewAction? = actions[type]

    fun kindNames(): List<String> = kinds.keys.sorted()

    fun actionNames(): List<String> = actions.keys.sorted()

    /** The editor input a `judgment` proposal stands for (options one per line; yes/no: two lines). */
    fun judgmentInput(p: Map<String, String>): EditorInput {
        val options = p["options"].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val base = EditorInput.empty().copy(title = p["title"].orEmpty(), question = p["question"].orEmpty(), invariant = p["invariant"].orEmpty())
        return when (p["shape"]) {
            "pick" -> base.copy(shape = DraftShape.PICK, optionsText = options.joinToString("\n"))
            "score" -> base.copy(shape = DraftShape.SCORE, bandsText = options.joinToString("\n"))
            else -> base.copy(shape = DraftShape.YES_NO, positive = options.getOrElse(0) { "" }, negative = options.getOrElse(1) { "" })
        }
    }

    /**
     * The judgment an approved `judgment` proposal adds: its proposed id, or a free one when a
     * judgment with that id already exists (an approval never replaces one of yours).
     */
    fun judgmentFor(p: Map<String, String>, existing: List<UserJudgment>): BookResult =
        when (val r = JudgmentBook.fromEditor(judgmentInput(p), existing)) {
            is BookResult.Refused -> r
            is BookResult.Created -> {
                val wanted = p["judgment_id"].orEmpty().ifBlank { r.judgment.id }
                val id = if (existing.any { it.id == wanted }) JudgmentBook.newId(wanted.removePrefix("j-"), existing) else wanted
                BookResult.Created(r.judgment.copy(id = id))
            }
        }
}
