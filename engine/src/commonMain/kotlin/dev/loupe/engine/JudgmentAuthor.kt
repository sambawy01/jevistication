package dev.loupe.engine

/** A problem found in a written judgment. */
data class LintFinding(val rule: String, val message: String)

/**
 * The lint that stands between a written question and a compiled judgment (C2).
 *
 * Every rule here enforces one thing: **this is a classifier, not a text model.** The engine has
 * no generative model: judgments never depend on one (PRODUCT.md never list), so a question
 * that asks for an explanation or a free-form number describes a judgment we cannot make. Catching it at authoring time is far
 * kinder than discovering it as a judgment that scores badly for reasons nobody can see.
 */
object JudgmentLint {

    private val RATING_SCALE = Regex(
        """\b(rate|rating|score|rank)\b.{0,30}\b(\d+\s*(?:-|–|to)\s*\d+|out of \d+)\b|\bon a scale\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ASKS_FOR_PROSE = Regex(
        """\b(explain|describe|summari[sz]e|elaborate|justify|why)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ASKS_TO_WRITE = Regex(
        """\b(write|draft|compose|generate|reply to|tell me about)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Every problem with [question]; empty means it compiles. */
    fun check(question: String): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        val trimmed = question.trim()

        if (trimmed.length < 3) {
            findings += LintFinding("empty", "a judgment needs an actual question")
            return findings
        }
        if (RATING_SCALE.containsMatchIn(trimmed)) {
            findings += LintFinding(
                "rating-scale",
                "asks for a free-form number; use a Score judgment with a declared range, " +
                    "or a Choice over named bands",
            )
        }
        if (ASKS_FOR_PROSE.containsMatchIn(trimmed)) {
            findings += LintFinding(
                "asks-for-prose",
                "asks for an explanation; judgments are decided by a classifier, which answers with a choice, not prose",
            )
        }
        if (ASKS_TO_WRITE.containsMatchIn(trimmed)) {
            findings += LintFinding(
                "asks-to-write",
                "asks the engine to produce text; it decides, it does not compose",
            )
        }
        if (trimmed.count { it == '?' } > 1) {
            findings += LintFinding(
                "multiple-questions",
                "asks more than one thing; split it, and let code combine the answers",
            )
        }
        return findings
    }
}

/** The outcome of compiling a written question. */
sealed interface AuthorResult {
    data class Compiled(val judgment: Judgment.Choice) : AuthorResult
    data class Rejected(val findings: List<LintFinding>) : AuthorResult
}

/**
 * Plain-language authoring (C2): a written question becomes a typed judgment, or is refused with
 * reasons.
 *
 * Candidates are inferred for a yes/no question and must be given otherwise, because guessing the
 * option set is exactly the kind of silent decision that makes a judgment score badly for reasons
 * nobody can see.
 */
object JudgmentAuthor {

    private val YES_NO_OPENER = Regex(
        """^(is|are|was|were|does|do|did|has|have|had|will|would|should|can|could|must)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The candidates inferred for a yes/no question. */
    val YES_NO: List<String> = listOf("yes", "no")

    /**
     * Compiles [question] into a [Judgment.Choice].
     *
     * @param candidates explicit options; inferred as [YES_NO] when the question opens like a
     *   yes/no question and none are given.
     */
    fun compile(
        id: String,
        question: String,
        candidates: List<String>? = null,
        onFailure: FailurePosture = FailurePosture.NULL_ACTION,
    ): AuthorResult {
        val findings = JudgmentLint.check(question).toMutableList()
        val trimmed = question.trim()

        val resolved = candidates
            ?: if (YES_NO_OPENER.containsMatchIn(trimmed)) YES_NO else null

        if (resolved == null) {
            findings += LintFinding(
                "no-candidates",
                "not a yes/no question, so it needs an explicit list of candidate answers",
            )
        }
        if (findings.isNotEmpty()) return AuthorResult.Rejected(findings)

        return runCatching { Judgment.Choice(id, trimmed, resolved!!, onFailure) }
            .fold(
                onSuccess = { AuthorResult.Compiled(it) },
                onFailure = {
                    AuthorResult.Rejected(
                        listOf(LintFinding("invalid-candidates", it.message ?: "invalid judgment")),
                    )
                },
            )
    }
}
