package dev.loupe.kit.packs

import dev.loupe.kit.judgments.BookResult
import dev.loupe.kit.judgments.DraftShape
import dev.loupe.kit.judgments.EditorInput
import dev.loupe.kit.judgments.JudgmentBook
import dev.loupe.persistence.JsonValue
import dev.loupe.templates.Shape
import dev.loupe.templates.UserJudgment

/*
 * Pack questions as the phone's judgments: the preview, the C2 lint on each, conflict handling
 * (same id), and exporting the user's judgments back out as a pack.
 *
 * PROVENANCE: the pack side follows Loupe Station (`~/laya-studio`, `laya_studio/packs.py`,
 * `laya_studio/static/js/packs.js`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e): import is
 * validate-then-install, a pack with a slug that is already installed is refused unless replaced,
 * and "Export my presets" writes a pack that imports again anywhere. The mapping onto judgments
 * and the per-question C2 lint are Loupe's: Station stores presets (question sets) and runs them
 * as they are, while every judgment on the phone must pass the same lint as "Write your own".
 *
 * One Station question becomes one judgment:
 *   noul   -> Yes / no, the two options being the pack's "true" / "false" descriptions. A noul with
 *             no descriptions would be bare yes/no, which the lint refuses (the Trap in BUILD.md).
 *   choice -> Pick one, the labels as options (their descriptions kept, for the person, in the
 *             judgment's "what stays true" text: a pick has no per-option criteria for the model).
 *   score  -> Score, the levels as its bands (the lint keeps a score to 10 bands; Station allows 20).
 * Ids are deterministic, `j-<preset>` for a one-question preset and `j-<preset>-<question>`
 * otherwise, so importing the same pack twice (or an export of your own judgments) meets the
 * existing judgments by id, and conflict handling decides.
 */

/** One pack question on its way to becoming a judgment. */
data class PackJudgmentPlan(
    val presetId: String,
    val presetName: String,
    val questionId: String,
    val type: String,
    val judgmentId: String,
    val title: String,
    val question: String,
    val shape: DraftShape,
    val options: List<String>,
    val input: EditorInput,
    /** The C2 lint's reasons; empty means it can be added. */
    val problems: List<String>,
    /** A judgment with [judgmentId] already exists. */
    val conflict: Boolean,
) {
    val addable: Boolean get() = problems.isEmpty()
}

/** What to do when a pack judgment has the id of one you already have. */
enum class ConflictChoice { REPLACE, KEEP_BOTH, SKIP }

/** The outcome of adding a pack: the new list to save, and what happened to each question. */
data class PackAddResult(
    val judgments: List<UserJudgment>,
    val added: List<String>,
    val replaced: List<String>,
    val skipped: List<String>,
    val refused: List<String>,
) {
    val summary: String
        get() = buildList {
            add("${added.size} added")
            if (replaced.isNotEmpty()) add("${replaced.size} replaced")
            if (skipped.isNotEmpty()) add("${skipped.size} skipped (already there)")
            if (refused.isNotEmpty()) add("${refused.size} not added (the lint refused them)")
        }.joinToString(", ")
}

/** An export: the pack's text, and the judgments that could not go in (with why). */
data class PackExport(val text: String, val pack: Pack, val exported: Int, val left: List<String>)

object PackJudgments {
    const val EXPORT_NAME: String = "My judgments"
    const val EXPORT_SLUG: String = "my-judgments"

    /** `j-<preset>` or `j-<preset>-<question>`, in the phone's id alphabet. */
    fun judgmentId(presetId: String, questionId: String, single: Boolean): String {
        val base = if (single) presetId else "$presetId-$questionId"
        return "j-" + base.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).trim('-').ifEmpty { "judgment" }
    }

    /** The preview: every question of [pack] as a judgment, linted, with conflicts marked. */
    fun plan(pack: Pack, existing: List<UserJudgment>): List<PackJudgmentPlan> = pack.presets.flatMap { p ->
        p.questions.map { q -> plan(p, q, p.questions.size == 1, existing) }
    }

    private fun plan(p: PackPreset, q: PackQuestion, single: Boolean, existing: List<UserJudgment>): PackJudgmentPlan {
        val id = judgmentId(p.id, q.id, single)
        val title = if (single) p.name else "${p.name}: ${humanise(q.id)}"
        val input = editorInput(title, q)
        val problems = JudgmentBook.findings(input).map { it.message }.distinct()
        return PackJudgmentPlan(
            p.id, p.name, q.id, q.type, id, title, q.instructions, input.shape, input.candidatesForDisplay(), input,
            problems, existing.any { it.id == id },
        )
    }

    private fun EditorInput.candidatesForDisplay(): List<String> = when (shape) {
        DraftShape.SCORE -> bands
        else -> candidates
    }

    fun humanise(id: String): String = id.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

    /** The editor input a pack question stands for (the same input "Write your own" would hold). */
    fun editorInput(title: String, q: PackQuestion): EditorInput {
        val empty = EditorInput.empty()
        return when (q.type) {
            "noul" -> empty.copy(
                title = title, question = q.instructions, shape = DraftShape.YES_NO,
                // Without both descriptions the options are what Laya would score: bare yes / no.
                positive = q.descriptions["true"]?.takeIf { q.descriptions["false"] != null } ?: "yes",
                negative = q.descriptions["false"]?.takeIf { q.descriptions["true"] != null } ?: "no",
            )
            "choice" -> empty.copy(
                title = title, question = q.instructions, shape = DraftShape.PICK,
                optionsText = q.options.joinToString("\n"),
                invariant = q.descriptions.filterValues { !it.isNullOrBlank() }.entries.joinToString("; ") { "${it.key}: ${it.value}" },
            )
            else -> empty.copy(title = title, question = q.instructions, shape = DraftShape.SCORE, bandsText = q.options.joinToString("\n"))
        }
    }

    /** The judgment for one plan, with its pack id, or the lint's reasons. */
    fun judgmentFor(plan: PackJudgmentPlan, id: String, existing: List<UserJudgment>): BookResult =
        when (val r = JudgmentBook.fromEditor(plan.input, existing)) {
            is BookResult.Created -> BookResult.Created(r.judgment.copy(id = id))
            is BookResult.Refused -> r
        }

    /**
     * Adds the addable questions in [plans] (all, or only [only] when not empty) to [existing].
     * A conflict is settled by [choice]: replace the existing judgment in place, keep both (the
     * new one gets a free id), or skip it.
     */
    fun add(plans: List<PackJudgmentPlan>, existing: List<UserJudgment>, choice: ConflictChoice, only: List<String>): PackAddResult {
        val out = existing.toMutableList()
        val added = mutableListOf<String>()
        val replaced = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val refused = mutableListOf<String>()
        for (plan in plans) {
            if (only.isNotEmpty() && plan.judgmentId !in only) continue
            if (!plan.addable) { refused += plan.judgmentId; continue }
            val clash = out.indexOfFirst { it.id == plan.judgmentId }
            val id = when {
                clash < 0 -> plan.judgmentId
                choice == ConflictChoice.SKIP -> { skipped += plan.judgmentId; continue }
                choice == ConflictChoice.REPLACE -> plan.judgmentId
                else -> JudgmentBook.newId(plan.judgmentId.removePrefix("j-"), out)
            }
            when (val r = judgmentFor(plan, id, out)) {
                is BookResult.Refused -> refused += plan.judgmentId
                is BookResult.Created -> if (clash >= 0 && choice == ConflictChoice.REPLACE) {
                    out[clash] = r.judgment
                    replaced += id
                } else {
                    out += r.judgment
                    added += id
                }
            }
        }
        return PackAddResult(out, added, replaced, skipped, refused)
    }

    /**
     * The user's judgments as a pack (Station's "Export my presets"): one preset per judgment, its
     * one question `q`. A yes/no whose options are bare yes/no exports as a noul without criteria
     * (a valid pack; importing it again is refused by the lint, as writing it would be). Judgments
     * the format cannot carry (a question over 600 characters, more than 20 options) are left out
     * and named in [PackExport.left]. The result is validated before it is returned.
     */
    fun export(judgments: List<UserJudgment>): PackExport {
        val presets = mutableListOf<JsonValue>()
        val left = mutableListOf<String>()
        for (j in judgments) {
            val pid = j.id.removePrefix("j-").lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(64)
            val question: JsonValue.Obj = when (val s = j.shape) {
                is Shape.Binary -> JsonValue.obj(
                    "type" to JsonValue.Str("noul"), "instructions" to JsonValue.Str(j.question),
                    "criteria" to JsonValue.obj("true" to JsonValue.Str(s.positive), "false" to JsonValue.Str(s.negative)),
                )
                Shape.YesNo -> JsonValue.obj("type" to JsonValue.Str("noul"), "instructions" to JsonValue.Str(j.question))
                is Shape.Pick -> JsonValue.obj(
                    "type" to JsonValue.Str("choice"), "instructions" to JsonValue.Str(j.question),
                    "criteria" to JsonValue.strings(s.candidates),
                )
                is Shape.Ordinal -> JsonValue.obj(
                    "type" to JsonValue.Str("score"), "instructions" to JsonValue.Str(j.question),
                    "criteria" to JsonValue.strings(s.bands),
                )
            }
            val preset = JsonValue.obj(
                "id" to JsonValue.Str(pid),
                "name" to JsonValue.Str(j.title.trim().take(PackFormat.MAX_PRESET_NAME_CHARS).ifBlank { pid }),
                "description" to JsonValue.Str(""),
                "state_key" to JsonValue.Null,
                "sample_text" to JsonValue.Str(""),
                "model" to JsonValue.Str("auto"),
                "questions" to JsonValue.obj("q" to question),
            )
            val alone = JsonValue.obj(
                "format" to JsonValue.Str(PackFormat.FORMAT), "version" to JsonValue.num(PackFormat.VERSION),
                "name" to JsonValue.Str(EXPORT_NAME), "slug" to JsonValue.Str(EXPORT_SLUG),
                "presets" to JsonValue.Arr(listOf(preset)),
            )
            when (val v = PackFormat.validate(alone)) {
                is PackParse.Invalid -> left += "${j.title}: ${v.problems.first().msg}"
                is PackParse.Valid -> if (presets.size < PackFormat.MAX_PRESETS) presets += preset else left += "${j.title}: a pack holds at most ${PackFormat.MAX_PRESETS}"
            }
        }
        val raw = JsonValue.obj(
            "format" to JsonValue.Str(PackFormat.FORMAT), "version" to JsonValue.num(PackFormat.VERSION),
            "name" to JsonValue.Str(EXPORT_NAME), "slug" to JsonValue.Str(EXPORT_SLUG),
            "description" to JsonValue.Str("Judgments exported from Loupe."),
            "presets" to JsonValue.Arr(presets),
        )
        val pack = when (val v = PackFormat.validate(raw)) {
            is PackParse.Valid -> v.pack
            // Only an empty list gets here (Station refuses an empty pack): export an honest empty pack.
            is PackParse.Invalid -> Pack(EXPORT_NAME, EXPORT_SLUG, "Judgments exported from Loupe.", emptyList())
        }
        return PackExport(pack.toText(), pack, pack.presets.size, left)
    }
}
