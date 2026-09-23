package dev.loupe.templates

import dev.loupe.engine.AuthorResult
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Judgment
import dev.loupe.engine.JudgmentAuthor
import dev.loupe.engine.JudgmentDefinition
import dev.loupe.engine.JudgmentLint
import dev.loupe.engine.LintFinding

/**
 * A judgment the user owns: made from a template or written from scratch, and editable.
 *
 * It compiles to one `Judgment.Choice` ([choice]), whose criteria hash covers the question and the
 * options. Editing either produces a different hash, and every calibration number the app shows is
 * computed only over decisions made under the **current** hash — so a reworded judgment starts its
 * calibration again rather than borrowing numbers earned by different wording. The invariant, what
 * breaks it and the lookalikes are not in the hash, because the model does not read them (see
 * [Template]); editing them changes no answer.
 */
data class UserJudgment(
    val id: String,
    val title: String,
    val question: String,
    val shape: Shape,
    val invariant: String,
    val breaks: String,
    val lookalikes: String,
    val onFailure: FailurePosture,
    val sources: Set<SourceKind>,
    val baseline: Baseline?,
    /** The template it came from, or null when written from scratch. */
    val templateId: String?,
    val parameters: Map<String, String> = emptyMap(),
    val warnOnly: Boolean = false,
    val mechanical: MechanicalCheck? = null,
    val desktopNote: String? = null,
    /**
     * The top-label mass at or above which the engine acts rather than queuing. The desktop app
     * runs the identity recalibrator, so today this is read against the model's **raw** mass. The default is a starting point nobody has validated — the research found
     * fourteen of fourteen projects shipping exactly that — and the threshold slider, reading the
     * user's own corrections, is how it gets validated.
     */
    val threshold: Double = defaultThreshold(shape),
) {
    init {
        require(threshold in 0.0..1.0) { "threshold must be in [0,1], was $threshold" }
    }

    /** What the engine runs. Construction validates the id and options. */
    val choice: Judgment.Choice by lazy { Judgment.Choice(id, question, shape.candidates, onFailure) }

    val criteriaHash: String get() = choice.criteriaHash

    /** The label a warning or a threshold is about, where there is one. */
    val positiveLabel: String? get() = if (shape is Shape.YesNo) "yes" else null

    /** The form `Export.judgmentsToJson` writes. */
    fun toDefinition(): JudgmentDefinition = JudgmentDefinition(choice, invariant, breaks, lookalikes)

    /**
     * This judgment with new wording, or the lint's reasons it cannot be.
     *
     * Rewording goes back through `JudgmentAuthor.compile`, so an edit is held to exactly the
     * standard the original was. [options] replaces a choice's options; a yes/no or a score keeps
     * its own.
     */
    fun reword(
        newQuestion: String,
        options: List<String>? = null,
        newTitle: String = title,
        newInvariant: String = invariant,
        newBreaks: String = breaks,
        newLookalikes: String = lookalikes,
    ): EditResult {
        val newShape = when (val s = shape) {
            is Shape.Pick -> if (options == null) s else runCatching {
                Shape.Pick(options, s.noOp?.takeIf { it in options })
            }.getOrElse { return EditResult.Rejected(listOf(LintFinding("invalid-options", it.message ?: "invalid options"))) }
            else -> s
        }
        return when (val compiled = JudgmentAuthor.compile(id, newQuestion, newShape.candidates, onFailure)) {
            is AuthorResult.Rejected -> EditResult.Rejected(compiled.findings)
            is AuthorResult.Compiled -> {
                val keptBaseline = baseline?.takeIf { b -> b.labels.all { it in newShape.candidates } }
                EditResult.Edited(
                    copy(
                        title = newTitle.ifBlank { title },
                        question = compiled.judgment.question,
                        shape = newShape,
                        invariant = newInvariant,
                        breaks = newBreaks,
                        lookalikes = newLookalikes,
                        baseline = keptBaseline,
                    ),
                )
            }
        }
    }

    /** The outcome of [reword]. */
    sealed interface EditResult {
        data class Edited(val judgment: UserJudgment) : EditResult
        data class Rejected(val findings: List<LintFinding>) : EditResult
    }

    companion object {
        /** Starting thresholds on the raw top mass; unvalidated until the user's corrections say. */
        fun defaultThreshold(shape: Shape): Double = when (shape) {
            Shape.YesNo -> 0.80
            is Shape.Pick -> 0.60
            is Shape.Ordinal -> 0.60
        }
    }
}

/**
 * A judgment being written from scratch (C2's "write your own"), checked live as the user types.
 *
 * [options] is empty for a yes/no question, whose options are inferred; otherwise the user lists
 * them, because guessing an option set is the silent decision that makes a judgment score badly for
 * reasons nobody can see.
 */
data class JudgmentDraft(
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val invariant: String = "",
    val breaks: String = "",
    val lookalikes: String = "",
    /** Words that make the dumb baseline say `yes` (yes/no questions only). */
    val baselineKeywords: List<String> = emptyList(),
    val onFailure: FailurePosture = FailurePosture.NULL_ACTION,
) {
    /** Every problem with the draft right now; empty means it will compile. Cheap: run per keystroke. */
    fun findings(): List<LintFinding> {
        val findings = JudgmentLint.check(question).toMutableList()
        val compiled = JudgmentAuthor.compile("draft", question, options.ifEmpty { null }, onFailure)
        if (compiled is AuthorResult.Rejected) {
            findings += compiled.findings.filter { it !in findings }
        }
        if (options.isNotEmpty() && options.size > 12) {
            findings += LintFinding("too-many-options", "keep to 12 options; Laya's card advises under ~20, and fewer is sharper")
        }
        if (options.any { it.isBlank() }) findings += LintFinding("blank-option", "an option is blank")
        return findings
    }

    /** Compiles the draft into a judgment the user owns, or returns why it cannot. */
    fun compile(id: String): UserJudgment.EditResult {
        val problems = findings()
        if (problems.isNotEmpty()) return UserJudgment.EditResult.Rejected(problems)
        val compiled = JudgmentAuthor.compile(id, question, options.ifEmpty { null }, onFailure)
        if (compiled is AuthorResult.Rejected) return UserJudgment.EditResult.Rejected(compiled.findings)
        val judgment = (compiled as AuthorResult.Compiled).judgment
        val shape: Shape = if (judgment.candidates == JudgmentAuthor.YES_NO) {
            Shape.YesNo
        } else {
            Shape.Pick(judgment.candidates, judgment.candidates.firstOrNull { it.lowercase() in NO_OP_WORDS })
        }
        val keywords = baselineKeywords.map { it.trim() }.filter { it.isNotEmpty() }
        val baseline = when {
            shape == Shape.YesNo && keywords.isNotEmpty() -> Baseline.Keyword(keywords, "yes", "no")
            shape == Shape.YesNo -> Baseline.Constant("no")
            else -> Baseline.Constant((shape as Shape.Pick).noOp ?: shape.candidates.last())
        }
        return UserJudgment.EditResult.Edited(
            UserJudgment(
                id = id,
                title = title.ifBlank { judgment.question },
                question = judgment.question,
                shape = shape,
                invariant = invariant.ifBlank { "(not written yet)" },
                breaks = breaks.ifBlank { "(not written yet)" },
                lookalikes = lookalikes.ifBlank { "(not written yet)" },
                onFailure = onFailure,
                sources = SourceKind.entries.toSet(),
                baseline = baseline,
                templateId = null,
            ),
        )
    }

    companion object {
        private val NO_OP_WORDS = setOf("not sure", "none", "none of these", "neither", "unsure", "other")
    }
}
