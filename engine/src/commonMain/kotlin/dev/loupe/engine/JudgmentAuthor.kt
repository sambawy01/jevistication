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

    // Matched on PortableText.matchForm (case and digits folded, the same on every platform) instead
    // of IGNORE_CASE, whose Unicode folding differs between regex engines; the leading `\b` is
    // BoundedRegex's (no lookbehind: Kotlin/Native pays O(position) for one). The `\b` before the
    // number is the last of the up to 30 characters being a non-word character (the word before
    // them ends at a boundary, so at least one is there): no lookbehind either.
    private val RATING_SCALE = BoundedRegex(
        """(rate|rating|score|rank)${Rx.WB_END}${Rx.IN_LINE}{0,29}[^A-Za-z0-9_\n\u000B\u000C\r\u0085\u2028\u2029]""" +
            """([0-9]+${Rx.SP}*(?:-|–|to)${Rx.SP}*[0-9]+|out of [0-9]+)${Rx.WB_END}|on a scale${Rx.WB_END}""",
    )
    private val ASKS_FOR_PROSE = BoundedRegex(
        """(explain|describe|summari[sz]e|elaborate|justify|why)${Rx.WB_END}""",
    )
    private val ASKS_TO_WRITE = BoundedRegex(
        """(write|draft|compose|generate|reply to|tell me about)${Rx.WB_END}""",
    )

    /** Every problem with [question]; empty means it compiles. */
    fun check(question: String): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        val trimmed = question.trim()
        val folded = PortableText.matchForm(trimmed)

        if (trimmed.length < 3) {
            findings += LintFinding("empty", "a judgment needs an actual question")
            return findings
        }
        if (RATING_SCALE.containsMatchIn(folded)) {
            findings += LintFinding(
                "rating-scale",
                "asks for a free-form number; use a Score judgment with a declared range, " +
                    "or a Choice over named bands",
            )
        }
        if (ASKS_FOR_PROSE.containsMatchIn(folded)) {
            findings += LintFinding(
                "asks-for-prose",
                "asks for an explanation; judgments are decided by a classifier, which answers with a choice, not prose",
            )
        }
        if (ASKS_TO_WRITE.containsMatchIn(folded)) {
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

    // --------------------------------------------------------------- absence phrasing (owner, 2026-09-27)

    private val ABSENCE_EN = Regex(
        """\b(?:missing|absent|absence|lacks?|lacking|lacked|omits?|omitted|omitting|devoid|without|nothing|none)\b""" +
            """|(?<!or\s)\bno\s+(?!longer\b)\p{L}""" +
            """|\bnot\s+(?:have|has|contain|contains|include|includes|mention|mentions|show|shows|carry|list|state|name|give|provide|specify)\b""" +
            """|n['’]t\s+(?:(?:it|this|that|they|there)\s+)?(?:have|has|contain|include|mention|show|carry|list|state|name|give|provide|specify|there)\b""" +
            """|\bthere\s+(?:is\s+|are\s+)?not\b""" +
            """|\bfails?\s+to\s+(?:include|mention|show|state|provide|list|give)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Egyptian Arabic in Latin letters ("Franco"): mafeesh, bedoon, men gheir, mesh mawgood, na2es. */
    private val ABSENCE_FRANCO = Regex(
        """\b(?:ma?fee?sh|mafish|mafesh|mfeesh|mafihoo?sh|bedo+n|bdo+n|(?:men|min|mn)\s+(?:gh?|8)[ei]+r|m[ei]?sh\s+mawgo+u?d[ae]?|na2e?s|na2sa|maf[qk]oo?d)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Arabic, MSA and Egyptian. No `\b` (it does not see Arabic letters as word characters on every
     * engine): a letter boundary is spelled out where a word could sit inside a longer one (بلا in
     * بلاغ, "a report").
     */
    private val ABSENCE_AR = Regex(
        """ناقص|نواقص|نقص(?!د)|مفقود|يخلو|تخلو|يفتقر|تفتقر|يفتقد|تفتقد|غائب|غياب""" +
            """|لا\s+(?:يوجد|توجد|يُوجد|تُوجد|يتوفر|تتوفر|يحتوي|تحتوي|يتضمن|تتضمن|يذكر|تذكر|يظهر|تظهر|يشمل|تشمل)""" +
            """|ليست?\s+(?:هناك|فيه|فيها|به|بها|لديه|لديها)""" +
            """|(?:غير|مش|مو)\s+موجود""" +
            """|عدم\s+(?:وجود|توفر|ذكر)""" +
            """|خال(?:ي|ية|ٍ)?\s+من""" +
            """|(?<![\p{L}\p{M}])[وف]?(?:بدون|بلا)(?![\p{L}\p{M}])""" +
            """|من\s+(?:دون|غير)""" +
            """|(?:م|ما\s?)في(?:ش|هوش|هاش)""",
    )

    /**
     * Rejects a question that asks whether something is **absent** ("Is the signature missing?",
     * "Does it lack a date?", "no due date", "without a receipt", ناقص / لا يوجد / بدون / مفقود).
     *
     * Why: the model answers absence questions far worse than the presence question they negate.
     * Loupe Station measured "Is X missing?" at 23/60 on the multilingual model (1/24 in Arabic)
     * against 47/60 (18/24) for "Does it contain X?" on the same texts, with opposite verdicts on
     * 50 of 60 (docs/BACKLOG.md, Station lab result 13). Ask the presence question and let the
     * negative option carry the absence. Applied to templates, judgments people write, and packs —
     * not to the web and flight questions, whose free text is a list of wishes ("no layovers").
     */
    fun absence(question: String): List<LintFinding> {
        val q = question.trim()
        if (!ABSENCE_EN.containsMatchIn(q) && !ABSENCE_AR.containsMatchIn(q) && !ABSENCE_FRANCO.containsMatchIn(q)) return emptyList()
        return listOf(
            LintFinding(
                "absence-phrasing",
                "asks whether something is absent (missing, lacking, \"no …\", \"without …\"), which the model answers " +
                    "unreliably; ask the presence question instead — \"Does it show a signature?\", not \"Is the signature " +
                    "missing?\" (هل يظهر توقيع؟ بدلاً من هل التوقيع ناقص؟) — and let the \"no\" option carry the absence",
            ),
        )
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

    /** Matched on [PortableText.matchForm], as the lint's patterns are. */
    private val YES_NO_OPENER = Regex(
        """^(is|are|was|were|does|do|did|has|have|had|will|would|should|can|could|must)${Rx.WB_END}""",
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
            ?: if (YES_NO_OPENER.containsMatchIn(PortableText.matchForm(trimmed))) YES_NO else null

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
