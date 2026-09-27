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

    // Portable spelling (fix loop 7, the parity rules: no `\b` `\s` `\p{..}` or case-insensitive flag).
    // The English and Franco patterns run on [PortableText.lowercase]d text; a `\b` before a word is
    // BoundedRegex's check, one after it [Rx.WB_END]; `\s` is [Rx.SP].

    private const val EN_VERBS = "have|has|contain|contains|include|includes|mention|mentions|show|shows|carry|list|state|name|give|provide|specify"

    private val ABSENCE_EN = BoundedRegex(
        "(?:missing|absent|absence|lacks?|lacking|lacked|omits?|omitted|omitting|devoid|without|nothing|none)${Rx.WB_END}" +
            "|not${Rx.SP}+(?:$EN_VERBS)${Rx.WB_END}" +
            "|there${Rx.SP}+(?:is${Rx.SP}+|are${Rx.SP}+)?not${Rx.WB_END}" +
            "|fails?${Rx.SP}+to${Rx.SP}+(?:include|mention|show|state|provide|list|give)${Rx.WB_END}",
    )

    /** `\bno\s+(?!longer\b)` then a letter (checked in code, from the pinned data), not after "or ". */
    private val ABSENCE_EN_NO = BoundedRegex("no${Rx.SP}+(?!longer${Rx.WB_END})")

    /** `n't` (straight or curly) after a word: no boundary before it ("doesn't have"). */
    private val ABSENCE_EN_NT = Regex(
        "n['’]t${Rx.SP}+(?:(?:it|this|that|they|there)${Rx.SP}+)?(?:have|has|contain|include|mention|show|carry|list|state|name|give|provide|specify|there)${Rx.WB_END}",
    )

    /** Egyptian Arabic in Latin letters ("Franco"): mafeesh, bedoon, men gheir, mesh mawgood, na2es. */
    private val ABSENCE_FRANCO = BoundedRegex(
        "(?:ma?fee?sh|mafish|mafesh|mfeesh|mafihoo?sh|bedo+n|bdo+n|(?:men|min|mn)${Rx.SP}+(?:gh?|8)[ei]+r|m[ei]?sh${Rx.SP}+mawgo+u?d[ae]?|na2e?s|na2sa|maf[qk]oo?d)${Rx.WB_END}",
    )

    /**
     * Arabic, MSA and Egyptian. No `\b` (it does not see Arabic letters as word characters on every
     * engine): a letter boundary is spelled out where a word could sit inside a longer one (بلا in
     * بلاغ, "a report"), checked in code for بدون / بلا ([absenceWithout]).
     */
    private val ABSENCE_AR = Regex(
        "ناقص|نواقص|نقص(?!د)|مفقود|يخلو|تخلو|يفتقر|تفتقر|يفتقد|تفتقد|غائب|غياب" +
            "|لا${Rx.SP}+(?:يوجد|توجد|يُوجد|تُوجد|يتوفر|تتوفر|يحتوي|تحتوي|يتضمن|تتضمن|يذكر|تذكر|يظهر|تظهر|يشمل|تشمل)" +
            "|ليست?${Rx.SP}+(?:هناك|فيه|فيها|به|بها|لديه|لديها)" +
            "|(?:غير|مش|مو)${Rx.SP}+موجود" +
            "|عدم${Rx.SP}+(?:وجود|توفر|ذكر)" +
            "|خال(?:ي|ية|ٍ)?${Rx.SP}+من" +
            "|من${Rx.SP}+(?:دون|غير)" +
            "|(?:م|ما${Rx.SP}?)في(?:ش|هوش|هاش)",
    )
    private val AR_WITHOUT = Regex("[وف]?(?:بدون|بلا)")

    private fun letterOrMark(cp: Int): Boolean = UnicodeData.category(cp).let {
        it == UnicodeData.LETTER || it == UnicodeData.NONSPACING_MARK || it == UnicodeData.SPACING_MARK || it == UnicodeData.ENCLOSING_MARK
    }

    /** `(?<![\p{L}\p{M}])[وف]?(?:بدون|بلا)(?![\p{L}\p{M}])`, with the letter-or-mark test in code. */
    private fun absenceWithout(q: String): Boolean {
        for (m in AR_WITHOUT.findAll(q)) {
            val s0 = m.range.first
            val e = m.range.last + 1
            val before = if (s0 == 0) -1 else PortableText.codePointAt(q, if (s0 >= 2 && q[s0 - 1].isLowSurrogate()) s0 - 2 else s0 - 1)
            val after = if (e >= q.length) -1 else PortableText.codePointAt(q, e)
            if ((before < 0 || !letterOrMark(before)) && (after < 0 || !letterOrMark(after))) return true
        }
        return false
    }

    /** `(?<!or\s)\bno\s+(?!longer\b)\p{L}` on lower-cased [q]. */
    private fun absenceNo(q: String): Boolean {
        for (m in ABSENCE_EN_NO.findAll(q)) {
            val s0 = m.range.first
            if (s0 >= 3 && q[s0 - 3] == 'o' && q[s0 - 2] == 'r' && PortableText.isSpace(q[s0 - 1])) continue
            val e = m.range.last + 1
            if (e < q.length && PortableText.isLetter(PortableText.codePointAt(q, e))) return true
        }
        return false
    }

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
        val lower = PortableText.lowercase(q)
        val absent = ABSENCE_EN.containsMatchIn(lower) || absenceNo(lower) || ABSENCE_EN_NT.containsMatchIn(lower) ||
            ABSENCE_AR.containsMatchIn(q) || absenceWithout(q) || ABSENCE_FRANCO.containsMatchIn(lower)
        if (!absent) return emptyList()
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
