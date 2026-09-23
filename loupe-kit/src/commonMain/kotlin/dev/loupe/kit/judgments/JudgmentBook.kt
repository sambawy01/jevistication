package dev.loupe.kit.judgments

import dev.loupe.engine.AuthorResult
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.JudgmentAuthor
import dev.loupe.engine.LintFinding
import dev.loupe.templates.Baseline
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.Shape
import dev.loupe.templates.SourceKind
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment

/** Making a judgment the user owns: it was created, or the lint said why not. */
sealed class BookResult {
    class Created(val judgment: UserJudgment) : BookResult()

    class Refused(val reasons: List<String>) : BookResult()
}

/** The three shapes the phone's "Write your own" offers. */
enum class DraftShape(val title: String) {
    /** Two options that say what they mean ("a receipt" / "not a receipt"). */
    YES_NO("Yes / no"),

    /** An ordinal score over bands the user names in words, low to high. */
    SCORE("Score"),

    /** One of several named options. */
    PICK("Pick one"),
}

/**
 * What the phone's editor holds, as plain strings (Swift-friendly: no defaults needed beyond the
 * constructor, no Kotlin-only types). [optionsText] and [bandsText] are one per line; commas also
 * split [optionsText] and [keywordsText].
 */
data class EditorInput(
    val title: String,
    val question: String,
    val shape: DraftShape,
    /** YES_NO: what the positive answer means, e.g. "a receipt or proof of purchase". */
    val positive: String,
    /** YES_NO: what the negative answer means, e.g. "not a receipt". */
    val negative: String,
    /** PICK: the options, one per line. */
    val optionsText: String,
    /** SCORE: each value's band, lowest first, one per line. */
    val bandsText: String,
    val invariant: String,
    val breaks: String,
    val lookalikes: String,
    val keywordsText: String,
    val onFailure: FailurePosture,
    /** Off by default: turning it on changes what the model reads (and restarts calibration). */
    val criteriaInPrompt: Boolean,
) {
    val options: List<String> get() = split(optionsText, commas = true)
    val bands: List<String> get() = split(bandsText, commas = false)
    val keywords: List<String> get() = keywordsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The candidates the model would score, as they stand. */
    val candidates: List<String>
        get() = when (shape) {
            DraftShape.YES_NO -> listOf(positive.trim(), negative.trim())
            DraftShape.PICK -> options
            DraftShape.SCORE -> bands.indices.map { (it + 1).toString() }
        }

    companion object {
        /** An empty editor: yes/no, the library's default posture, criteria not in the prompt. */
        fun empty(): EditorInput = EditorInput(
            "", "", DraftShape.YES_NO, "", "", "", "", "", "", "", "", TemplateLibrary.DEFAULT_POSTURE, false,
        )

        private fun split(text: String, commas: Boolean): List<String> =
            (if (commas) text.split('\n', ',') else text.split('\n')).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * The phone's judgment book: making, naming and changing judgments, as pure functions over the
 * current list. The iOS app holds the list and persists it (through `PhoneLedger`); the rules —
 * ids, the template and C2 authoring paths, the lint, the calibration-restart notice — live here
 * so the desktop's behaviour is mirrored, not re-typed in Swift.
 */
object JudgmentBook {
    /** At most this many bands in a score: the lint's "keep to 12" applies to options. */
    const val MAX_BANDS: Int = 10

    /** The desktop's id rule: `j-` plus a slug of [base], made unique among [existing]. */
    fun newId(base: String, existing: List<UserJudgment>): String {
        val stem = "j-" + base.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "judgment" }
        var id = stem
        var n = 2
        while (existing.any { it.id == id }) id = "$stem-${n++}"
        return id
    }

    /** C1's "Use this": [template] with its parameter [values], through the same lint as C2. */
    fun fromTemplate(templateId: String, values: Map<String, String>, existing: List<UserJudgment>): BookResult {
        val template = TemplateLibrary.byId(templateId) ?: return BookResult.Refused(listOf("no template '$templateId'"))
        return when (val r = template.instantiate(newId(template.id, existing), values)) {
            is Template.InstantiateResult.Rejected -> BookResult.Refused(r.findings.map { it.message })
            is Template.InstantiateResult.Created -> BookResult.Created(r.judgment)
        }
    }

    /** Judgments in [existing] made from [templateId] (the library marks a template already in use). */
    fun usesOf(templateId: String, existing: List<UserJudgment>): Int = existing.count { it.templateId == templateId }

    /**
     * Every problem with [input] right now; empty means it will compile. Cheap enough per
     * keystroke. Beyond the engine's lint it refuses bare `yes`/`no` options — the Trap in
     * `docs/BUILD.md`: with them Laya largely ignored the question.
     */
    fun findings(input: EditorInput): List<LintFinding> {
        val out = mutableListOf<LintFinding>()
        when (input.shape) {
            DraftShape.YES_NO -> {
                out += draft(input).findings()
                val pos = input.positive.trim()
                val neg = input.negative.trim()
                if (pos.isEmpty() || neg.isEmpty()) {
                    out += LintFinding("options-say-what-they-mean", "write both options as what they mean, e.g. \"a receipt or proof of purchase\" and \"not a receipt\"")
                } else if (pos.lowercase() in BARE || neg.lowercase() in BARE) {
                    out += LintFinding("bare-yes-no", "bare yes/no options make the model ignore the question; say what each answer means instead")
                } else if (pos.equals(neg, ignoreCase = true)) {
                    out += LintFinding("same-options", "the two options must differ")
                }
            }
            DraftShape.PICK -> {
                out += draft(input).findings()
                val opts = input.options
                if (opts.size < 2) out += LintFinding("too-few-options", "a pick needs at least two options")
                if (opts.map { it.lowercase() }.distinct().size != opts.size) out += LintFinding("duplicate-options", "two options are the same")
            }
            DraftShape.SCORE -> {
                val bands = input.bands
                if (bands.size < 2) out += LintFinding("too-few-bands", "a score needs at least two bands, lowest first, one per line")
                if (bands.size > MAX_BANDS) out += LintFinding("too-many-bands", "keep a score to $MAX_BANDS bands")
                if (bands.map { it.lowercase() }.distinct().size != bands.size) out += LintFinding("duplicate-bands", "two bands are the same")
                val compiled = JudgmentAuthor.compile("draft", input.question, input.candidates.ifEmpty { listOf("1", "2") }, input.onFailure)
                if (compiled is AuthorResult.Rejected) out += compiled.findings
            }
        }
        return out.distinct()
    }

    /** Compiles [input] into a judgment the user owns, or returns why it cannot. */
    fun fromEditor(input: EditorInput, existing: List<UserJudgment>): BookResult {
        val problems = findings(input)
        if (problems.isNotEmpty()) return BookResult.Refused(problems.map { it.message })
        val id = newId(input.title.ifBlank { input.question }, existing)
        val judgment = when (input.shape) {
            DraftShape.YES_NO, DraftShape.PICK -> when (val r = draft(input).compile(id)) {
                is UserJudgment.EditResult.Rejected -> return BookResult.Refused(r.findings.map { it.message })
                is UserJudgment.EditResult.Edited -> r.judgment
            }
            DraftShape.SCORE -> {
                val compiled = JudgmentAuthor.compile(id, input.question, input.candidates, input.onFailure)
                if (compiled is AuthorResult.Rejected) return BookResult.Refused(compiled.findings.map { it.message })
                val bands = input.bands
                UserJudgment(
                    id = id,
                    title = input.title.ifBlank { input.question.trim() },
                    question = (compiled as AuthorResult.Compiled).judgment.question,
                    shape = Shape.Ordinal(1..bands.size, bands),
                    invariant = input.invariant.ifBlank { UserJudgment.UNWRITTEN },
                    breaks = input.breaks.ifBlank { UserJudgment.UNWRITTEN },
                    lookalikes = input.lookalikes.ifBlank { UserJudgment.UNWRITTEN },
                    onFailure = input.onFailure,
                    sources = SourceKind.entries.toSet(),
                    // The dumb baseline for a score: always the lowest band.
                    baseline = Baseline.Constant("1"),
                    templateId = null,
                )
            }
        }
        return BookResult.Created(judgment.copy(criteriaInPrompt = input.criteriaInPrompt))
    }

    /** [judgment] with its criteria shown to the model ([on]) or not. Changes the criteria hash. */
    fun withCriteriaInPrompt(judgment: UserJudgment, on: Boolean): UserJudgment = judgment.copy(criteriaInPrompt = on)

    /** The desktop's notice for the criteria toggle: calibration restarts either way. */
    fun criteriaNotice(on: Boolean): String = if (on) {
        "The model now reads this judgment's criteria. Its calibration starts again: earlier decisions were made without them."
    } else {
        "The model no longer reads this judgment's criteria. Calibration starts again under the bare options."
    }

    /** Shown beside the toggle before it is flipped. */
    const val CRITERIA_WARNING: String =
        "Off by default. Turning it on changes what the model reads, so this judgment's calibration starts again. " +
            "On the sample it helped one judgment and badly hurt another; it stays off until measured on your corrections."

    /** True when [judgment] has per-option criteria the toggle could show (a pick has none). */
    fun criteriaApplicable(judgment: UserJudgment): Boolean = judgment.optionCriteria().isNotEmpty()

    private fun draft(input: EditorInput): JudgmentDraft = JudgmentDraft(
        title = input.title,
        question = input.question,
        options = input.candidates,
        invariant = input.invariant,
        breaks = input.breaks,
        lookalikes = input.lookalikes,
        baselineKeywords = if (input.shape == DraftShape.YES_NO) input.keywords else emptyList(),
        onFailure = input.onFailure,
    )

    private val BARE = setOf("yes", "no", "y", "n", "true", "false")
}
