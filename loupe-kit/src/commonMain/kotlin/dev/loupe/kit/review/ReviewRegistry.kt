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
    val FEATURES: List<String> = listOf(PRIVACY, MAIL, WATCHER, JUDGMENT)

    fun featureTitle(feature: String): String = when (feature) {
        PRIVACY -> "Privacy check"
        MAIL -> "Mail triage"
        WATCHER -> "Watchers"
        JUDGMENT -> "Judgments"
        else -> feature
    }

    private val kinds: Map<String, ReviewKind> = listOf(
        ReviewKind("text", listOf(ReviewField("text", true, 20_000))),
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
    ).associateBy { it.name }

    private val actions: Map<String, ReviewAction> = listOf(
        ReviewAction("none", emptyList(), emptySet(), reversible = false, verb = "Approve"),
        ReviewAction("privacy.remove_copy", listOf("file_action"), setOf("finding_key", "item_id"), reversible = true, verb = "Remove copy"),
        ReviewAction("mail.confirm_phishing", listOf("mail_verdict"), emptySet(), reversible = true, verb = "Confirm phishing"),
        ReviewAction("watcher.confirm", listOf("finding_verdict"), emptySet(), reversible = true, verb = "Keep watching"),
        ReviewAction("judgment.add", listOf("judgment"), emptySet(), reversible = true, verb = "Add judgment") { p ->
            JudgmentBook.findings(judgmentInput(p)).map { "proposal: ${it.message}" }.distinct()
        },
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
