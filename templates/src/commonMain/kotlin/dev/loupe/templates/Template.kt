package dev.loupe.templates

import dev.loupe.engine.AuthorResult
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.JudgmentAuthor
import dev.loupe.engine.LintFinding
import kotlinx.datetime.LocalDate

/** Where a template sits in the library. The order is the order the library shows them in. */
enum class Category(val title: String) {
    MONEY("Money & receipts"),
    DOCUMENTS("Documents & deadlines"),
    EMAIL("Email triage"),
    SUBSCRIPTIONS("Subscriptions"),
    FILES("Files & cleanup"),
    PHOTOS("Photos"),
    WORK("Work"),
    TRAVEL("Travel"),
    SAFETY("Safety & fraud"),
    PERSONAL("Personal"),
}

/** The kinds of item a judgment is written for. It runs on the others too; this is a hint. */
enum class SourceKind(val title: String) {
    DOCUMENT("documents"),
    EMAIL("email"),
    PHOTO("photos"),
    SPREADSHEET("spreadsheets"),
}

/**
 * The typed shape of a judgment's answer.
 *
 * All three compile to a `Judgment.Choice`, because that is the model's one primitive (Laya scores
 * every option at its own marker in a single forward pass). The shape is kept because it changes
 * what the screen shows and what a threshold reads.
 */
sealed interface Shape {
    val candidates: List<String>

    /** The label a warning or a threshold is about, for the two-option shapes; null otherwise. */
    val positiveOption: String?
        get() = when (this) {
            YesNo -> "yes"
            is Binary -> positive
            else -> null
        }

    /** Yes or no, as bare labels. The positive label is `yes`. What a written yes/no question compiles to. */
    data object YesNo : Shape {
        override val candidates: List<String> = JudgmentAuthor.YES_NO
    }

    /**
     * A yes/no question whose two options **say what they mean** — "a receipt or proof of
     * purchase" against "not a receipt" — rather than a bare `yes`/`no`.
     *
     * Why the library uses this: Laya scores each option's own text at its marker, and with bare
     * `yes`/`no` it largely ignored the question. On the 45 synthetic sample items, two unrelated
     * yes/no questions got near-identical answers, and switching to descriptive options raised
     * agreement with hand labels on all three judgments tried (see `docs/BUILD.md`, progress log).
     * That is a small synthetic check, not an accuracy claim; it is why the templates are shaped
     * this way, and it is a controlled variable to re-measure on real corrections.
     */
    data class Binary(val positive: String, val negative: String) : Shape {
        init {
            require(positive.isNotBlank() && negative.isNotBlank() && positive != negative) {
                "a binary shape needs two different, non-blank options"
            }
        }

        override val candidates: List<String> = listOf(positive, negative)
    }

    /**
     * One of named options. [noOp] names the "not sure / none of these" option where one fits: an
     * explicit no-op in every choice set is one of the patterns the evidence base names, because
     * without it the model is forced to pick something that does not apply.
     */
    data class Pick(override val candidates: List<String>, val noOp: String?) : Shape {
        init {
            require(candidates.size >= 2) { "a choice needs at least two options" }
            require(candidates.distinct().size == candidates.size) { "options must be distinct" }
            require(noOp == null || noOp in candidates) { "the no-op '$noOp' must be one of the options" }
            // Laya's own card advises staying under ~20 options; the library stays well under.
            require(candidates.size <= 12) { "a template choice keeps to 12 options, had ${candidates.size}" }
        }
    }

    /**
     * An ordinal score over a declared range. Each value is a band the question names in words, so
     * the model is choosing between described bands, not writing a number — the distinction the
     * lint draws against "rate 1–10".
     */
    data class Ordinal(val range: IntRange, val bands: List<String>) : Shape {
        init {
            require(range.count() >= 2) { "a score needs at least two values" }
            require(bands.size == range.count()) {
                "every value in $range needs a band description, had ${bands.size}"
            }
        }

        override val candidates: List<String> = range.map(Int::toString)
    }
}

/** What kind of value a template parameter takes. Each kind has its own validation. */
enum class ParamKind {
    /** A short phrase: a project, a person, a brand. */
    TEXT,

    /** A sender: a name, an email address or a domain. */
    SENDER,

    /** A calendar date, written `yyyy-mm-dd`. */
    DATE,
}

/** A `{name}` slot in a template's question. */
data class Parameter(
    val name: String,
    val kind: ParamKind,
    /** What to ask the user for. */
    val label: String,
    /** A value that makes the template read sensibly; also what the library tests substitute. */
    val example: String,
) {
    init {
        require(NAME.matches(name)) { "parameter names are lowercase identifiers, was '$name'" }
        require(validate(example) == null) { "example '$example' for {$name} is invalid: ${validate(example)}" }
    }

    /**
     * Why [value] cannot fill this slot, or null when it can.
     *
     * Strict on purpose. A parameter lands **inside the question the model reads**, so it must not
     * be able to smuggle in a second question, a forged option marker, a prompt, or a rating scale;
     * and the question it produces is linted again after substitution.
     */
    fun validate(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "must not be empty"
        if (trimmed.length > MAX_LENGTH) return "must be at most $MAX_LENGTH characters"
        if (trimmed.any { it == '\n' || it == '\r' || it == '\t' }) return "must be a single line"
        if (FORBIDDEN.any { it in trimmed }) return "must not contain any of ${FORBIDDEN.joinToString(" ")}"
        return when (kind) {
            ParamKind.TEXT ->
                if (TEXT.matches(trimmed)) null else "use letters, digits, spaces and . - ' & only"
            ParamKind.SENDER ->
                if (SENDER.matches(trimmed)) null else "use a name, an email address or a domain"
            ParamKind.DATE -> try {
                LocalDate.parse(trimmed)
                null
            } catch (_: IllegalArgumentException) {
                "write the date as yyyy-mm-dd"
            }
        }
    }

    companion object {
        const val MAX_LENGTH: Int = 60
        private val NAME = Regex("""^[a-z][a-z0-9_]*$""")
        private val TEXT = Regex("""^[\p{L}\p{Nd}\p{Nl}\p{No}][\p{L}\p{Nd}\p{Nl}\p{No} .'&\-]*$""")
        private val SENDER = Regex("""^[\p{L}\p{Nd}\p{Nl}\p{No}][\p{L}\p{Nd}\p{Nl}\p{No} .'@_+\-]*$""")

        /** Braces would forge a placeholder, `?` a second question, `<`/`>` a model marker. */
        private val FORBIDDEN = listOf("{", "}", "?", "<", ">", "\"")
    }
}

/** A worked example: a short piece of text and the answer the template means for it. */
data class TemplateExample(val text: String, val answer: String, val why: String)

/**
 * The mechanical check a template can run before the model (A3's "mechanical first"). Where it
 * answers, the model is not asked and cannot be wrong.
 */
enum class MechanicalCheck(val description: String) {
    /** A byte-identical copy of an item seen earlier is a duplicate — exact, by SHA-256. */
    EXACT_DUPLICATE("byte-identical copies are answered by SHA-256, without the model"),

    /**
     * A document with no sign that money moved or is owed (no receipt/invoice wording, no amount
     * paid or due, no order number, no payment line) — or a shop's product listing — is answered
     * with the negative option by rule. See [TransactionEvidence].
     */
    NO_TRANSACTION_EVIDENCE("documents with no sign of a payment (or shop product pages) are answered 'no' by rule, without the model"),
}

/**
 * A starting point in the template library (C1): a question with its typed shape, its three-part
 * criteria, its failure posture, a dumb baseline to measure it against, and worked examples.
 *
 * A template is never run as it stands. [instantiate] turns it into a [UserJudgment] the user owns
 * and may edit; editing the wording changes the judgment's criteria hash, so calibration fitted to
 * the old wording is honestly reset rather than silently reused.
 *
 * **What the model reads.** By default the model is given the question and the options only. The
 * invariant, what breaks it and the lookalikes are shown to the user, exported with the judgment,
 * and are what a corrector checks an answer against. A judgment can opt in
 * (`UserJudgment.criteriaInPrompt`, off by default) to show the invariant and what breaks it beside
 * its two options — or a score's bands beside its values — through upstream Laya's descriptive-option
 * channel (`LayaPrompt`). On the synthetic sample it helped one judgment and badly hurt another, so it
 * stays off until measured on real corrections; see `BUILD.md`.
 */
data class Template(
    val id: String,
    val category: Category,
    val title: String,
    /** The question, possibly with `{name}` slots for [parameters]. */
    val question: String,
    val shape: Shape,
    /** What must be true for the positive answer. */
    val invariant: String,
    /** What makes it false. */
    val breaks: String,
    /** What looks like it but is not — the near misses that decide the hard cases. */
    val lookalikes: String,
    val onFailure: FailurePosture,
    val sources: Set<SourceKind>,
    val baseline: Baseline?,
    val examples: List<TemplateExample>,
    val parameters: List<Parameter> = emptyList(),
    /**
     * True for safety judgments: a negative answer is shown as "no signal found", **never** as
     * safe. §4: the product warns; it never blesses.
     */
    val warnOnly: Boolean = false,
    val mechanical: MechanicalCheck? = null,
    /** Something the user should know about running this on a desktop, shown beside it. */
    val desktopNote: String? = null,
) {
    init {
        require(ID.matches(id)) { "template ids are lowercase words joined by hyphens, was '$id'" }
        require(title.isNotBlank()) { "template '$id' needs a title" }
        require(invariant.isNotBlank() && breaks.isNotBlank() && lookalikes.isNotBlank()) {
            "template '$id' must be authored to the three-part template"
        }
        require(examples.size in 2..3) { "template '$id' needs two or three examples, had ${examples.size}" }
        require(examples.all { it.answer in shape.candidates }) {
            "template '$id' has an example whose answer is not an option"
        }
        require(parameters.map { it.name }.distinct().size == parameters.size) {
            "template '$id' declares a parameter twice"
        }
        val slots = PLACEHOLDER.findAll(question).map { it.groupValues[1] }.toSet()
        require(slots.all { slot -> parameters.any { it.name == slot } }) {
            "template '$id' question has slots $slots but declares ${parameters.map { it.name }}"
        }
        // A parameter need not reach the question: "expires before {date}" keeps the date out of
        // what the model reads (the model does not do date arithmetic) and uses it in the title and
        // the mechanical baseline. But it must be used somewhere the user can see.
        val titleSlots = PLACEHOLDER.findAll(title).map { it.groupValues[1] }.toSet()
        require(parameters.all { it.name in slots || it.name in titleSlots }) {
            "template '$id' declares a parameter it never uses"
        }
        baseline?.let { b ->
            require(b.labels.all { it in shape.candidates }) {
                "template '$id' baseline can answer ${b.labels - shape.candidates.toSet()}, which are not options"
            }
        }
    }

    /** The label a threshold and a warning are about: `yes` for yes/no, otherwise null. */
    val positiveLabel: String? get() = shape.positiveOption

    /** The question with [values] substituted — or the reasons it cannot be. */
    fun render(values: Map<String, String>): Rendered {
        val problems = mutableListOf<LintFinding>()
        for (parameter in parameters) {
            val value = values[parameter.name]
            if (value == null) {
                problems += LintFinding("missing-parameter", "{${parameter.name}} needs a value")
                continue
            }
            parameter.validate(value)?.let { problems += LintFinding("invalid-parameter", "{${parameter.name}} $it") }
        }
        if (problems.isNotEmpty()) return Rendered.Rejected(problems)
        val trimmed = values.mapValues { it.value.trim() }
        return Rendered.Ok(Baseline.fill(question, trimmed), trimmed)
    }

    /** The result of substituting parameters into a template's question. */
    sealed interface Rendered {
        data class Ok(val question: String, val values: Map<String, String>) : Rendered
        data class Rejected(val findings: List<LintFinding>) : Rendered
    }

    /**
     * Turns this template into a judgment the user owns.
     *
     * The rendered question goes through `JudgmentAuthor.compile` — the same path, and the same
     * lint, as a question the user writes from scratch. Nothing in the library is exempt.
     */
    fun instantiate(judgmentId: String, values: Map<String, String> = emptyMap()): InstantiateResult {
        val rendered = when (val r = render(values)) {
            is Rendered.Rejected -> return InstantiateResult.Rejected(r.findings)
            is Rendered.Ok -> r
        }
        return when (val compiled = JudgmentAuthor.compile(judgmentId, rendered.question, shape.candidates, onFailure)) {
            is AuthorResult.Rejected -> InstantiateResult.Rejected(compiled.findings)
            is AuthorResult.Compiled -> InstantiateResult.Created(
                UserJudgment(
                    id = judgmentId,
                    title = Baseline.fill(title, rendered.values),
                    question = compiled.judgment.question,
                    shape = shape,
                    invariant = invariant,
                    breaks = breaks,
                    lookalikes = lookalikes,
                    onFailure = onFailure,
                    sources = sources,
                    baseline = baseline?.substitute(rendered.values),
                    templateId = id,
                    parameters = rendered.values,
                    warnOnly = warnOnly,
                    mechanical = mechanical,
                    desktopNote = desktopNote,
                ),
            )
        }
    }

    /** The outcome of [instantiate]. */
    sealed interface InstantiateResult {
        data class Created(val judgment: UserJudgment) : InstantiateResult
        data class Rejected(val findings: List<LintFinding>) : InstantiateResult
    }

    companion object {
        private val ID = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)*$""")
        private val PLACEHOLDER = Regex("""\{([a-z][a-z0-9_]*)}""")
    }
}
