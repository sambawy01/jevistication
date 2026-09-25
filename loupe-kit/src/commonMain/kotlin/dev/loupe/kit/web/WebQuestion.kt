package dev.loupe.kit.web

import dev.loupe.engine.AuthorResult
import dev.loupe.engine.Backend
import dev.loupe.engine.Decision
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Judgment
import dev.loupe.engine.JudgmentAuthor
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Policy
import dev.loupe.engine.Probability
import dev.loupe.engine.Recalibrator
import dev.loupe.engine.ResolvedBy
import dev.loupe.engine.TextState
import dev.loupe.engine.Truncation

/**
 * The four decision types a Web-tab question can take (owner decision 2026-09-25). Every type is
 * asked **per fetched item**: the model is a classifier over one item's facts, never a generator,
 * and code combines the per-item answers (pick the top, sort, or show each).
 */
enum class WebDecisionType(val id: String) {
    /** Each item: does it satisfy the question? */
    YES_NO("yes_no"),

    /** The one item most likely to be the best answer. */
    PICK("pick"),

    /** Each item gets a level, shown in the items' own order (e.g. by date). */
    SCORE("score"),

    /** Items ordered by their level, best first. */
    RANK("rank"),
    ;

    companion object {
        fun of(id: String): WebDecisionType? = entries.firstOrNull { it.id == id }
    }
}

/** The outcome of compiling a Web-tab question. */
sealed interface WebQuestionResult {
    class Ready(val judgment: Judgment.Choice, val type: WebDecisionType) : WebQuestionResult

    /** C2's lint (or the Web tab's own checks) refused the text; [reasons] are shown to the user. */
    class Refused(val reasons: List<String>) : WebQuestionResult
}

/**
 * A Web-tab question — a template variant or the user's own — as a typed judgment, through C2's
 * authoring path ([JudgmentAuthor]): the same lint every user-written judgment passes.
 *
 * Option labels are **neutral** (they name the answer's strength, never a sector: no "dry", no
 * "on time") and each carries a description saying what it means, so bare yes/no never reaches the
 * model (docs/BUILD.md trap). Score levels are written lowest first with `ordinal = true`: the Laya
 * backend then sends them highest first and maps the answer back (`LayaRuntimeRules`, upstream
 * #131) — a runtime rule, so the criteria hash is the written one on every app.
 */
object WebQuestion {
    const val MATCH: String = "matches"
    const val NO_MATCH: String = "does not match"
    const val STRONG: String = "strong candidate"
    const val WEAK: String = "weak candidate"

    /** Score levels, lowest first (the written order). */
    val LEVELS: List<String> = listOf("very poor", "poor", "fair", "good", "very good")

    /** At most this many characters of the user's own question. */
    const val MAX_CHARS: Int = 240

    fun id(type: WebDecisionType): String = "web.search.${type.id}"

    /**
     * The question the model reads for one item. The user's text is kept as written, trimmed and
     * with its closing question mark normalised; a pick and a score wrap it so the per-item
     * framing is explicit. [arabic] picks the wrapper's language (the model is multilingual).
     */
    fun framed(question: String, type: WebDecisionType, arabic: Boolean = false): String {
        val core = question.trim().trimEnd('?', '؟', '.', ' ', '\n')
        val mark = if (arabic) "؟" else "?"
        return when (type) {
            WebDecisionType.YES_NO -> "$core$mark"
            WebDecisionType.PICK ->
                if (arabic) "هل هذا العنصر هو الأفضل للسؤال: $core$mark" else "Is this item the best answer to: $core?"
            WebDecisionType.SCORE, WebDecisionType.RANK ->
                if (arabic) "ما مدى ملاءمة هذا العنصر للسؤال: $core$mark" else "How well does this item fit: $core?"
        }
    }

    /** Every problem with [question] as [type]; empty means it compiles. Cheap enough per keystroke. */
    fun findings(question: String, type: WebDecisionType, arabic: Boolean = false): List<String> =
        when (val r = compile(question, type, arabic)) {
            is WebQuestionResult.Ready -> emptyList()
            is WebQuestionResult.Refused -> r.reasons
        }

    fun compile(question: String, type: WebDecisionType, arabic: Boolean = false): WebQuestionResult {
        val trimmed = question.trim()
        val own = mutableListOf<String>()
        if (inner(trimmed).length < 3) return WebQuestionResult.Refused(listOf("a judgment needs an actual question"))
        if (trimmed.length > MAX_CHARS) own += "keep the question under $MAX_CHARS characters"
        // The wrapper adds its own question mark; one inside the user's text means two questions.
        if (inner(trimmed).any { it == '?' || it == '؟' }) {
            own += "asks more than one thing; split it, and let code combine the answers"
        }
        val candidates = when (type) {
            WebDecisionType.YES_NO -> listOf(MATCH, NO_MATCH)
            WebDecisionType.PICK -> listOf(STRONG, WEAK)
            WebDecisionType.SCORE, WebDecisionType.RANK -> LEVELS
        }
        val r = JudgmentAuthor.compile(id(type), framed(trimmed, type, arabic), candidates, FailurePosture.NULL_ACTION)
        val lint = (r as? AuthorResult.Rejected)?.findings?.map { it.message }.orEmpty()
        val reasons = (own + lint).distinct()
        if (reasons.isNotEmpty() || r !is AuthorResult.Compiled) return WebQuestionResult.Refused(reasons)
        val judgment = when (type) {
            WebDecisionType.YES_NO -> r.judgment.copy(
                descriptions = mapOf(
                    MATCH to "for this item, the answer to the question is yes",
                    NO_MATCH to "for this item, the answer to the question is no",
                ),
            )
            WebDecisionType.PICK -> r.judgment.copy(
                descriptions = mapOf(
                    STRONG to "this item is a strong answer to the question",
                    WEAK to "this item is a weak answer to the question",
                ),
            )
            WebDecisionType.SCORE, WebDecisionType.RANK -> r.judgment.copy(
                descriptions = mapOf(
                    LEVELS[0] to "does not fit the question at all",
                    LEVELS[1] to "fits the question badly",
                    LEVELS[2] to "fits the question in part",
                    LEVELS[3] to "fits the question well",
                    LEVELS[4] to "fits the question fully",
                ),
                ordinal = true,
            )
        }
        return WebQuestionResult.Ready(judgment, type)
    }

    private fun inner(q: String) = q.trim().trimEnd('?', '؟', '.', ' ', '\n')

    /** The starting threshold of a type: two options as a yes/no judgment, five as a score. */
    fun startThreshold(type: WebDecisionType): Double = when (type) {
        WebDecisionType.YES_NO, WebDecisionType.PICK -> 0.80
        WebDecisionType.SCORE, WebDecisionType.RANK -> 0.60
    }
}

/** One fetched item as the model reads it: its facts, one per line, most important first. */
data class WebItem(
    /** Stable within the source, e.g. `2026-09-24:EUR:EGP` or a Darwin service id. */
    val id: String,
    val lines: List<String>,
)

/** Laya's verdict on one item. */
data class WebVerdict(
    val id: String,
    /**
     * In 0..1, higher is better: the chance of "matches" / "strong candidate", or the expected
     * level of a score scaled to 0..1 (level 1 → 0, level 5 → 1).
     */
    val value: Double,
    /** A score's most likely level, 1..5 in the written order; 0 for two-option types. */
    val level: Int,
    val margin: Double,
    /** The engine would not act on this answer: below threshold, input cut, or unusable. */
    val unsure: Boolean,
    val truncated: Boolean,
    val failure: String?,
)

class WebDecision(val verdict: WebVerdict, val row: LedgerRow)

/**
 * Judges fetched items against a compiled Web-tab question with any [Backend] — Laya on the phone,
 * a fake in tests — through the engine's path: validate (A4) → recalibrate → policy (A7) → the
 * cut-input rule (§8). Each decision writes one ledger row (A5) whose item is
 * `web:<source>:<item id>`.
 */
class WebJudge(
    private val backend: Backend,
    private val source: String,
    private val threshold: Double,
    private val budget: Int = BUDGET,
    private val recalibrator: Recalibrator = Recalibrator.Identity,
) {
    /** For Swift, which does not see Kotlin default arguments. */
    constructor(backend: Backend, source: String, threshold: Double, budget: Int) :
        this(backend, source, threshold, budget, Recalibrator.Identity)

    fun state(item: WebItem): TextState =
        TextState.build(item.lines.mapIndexed { i, l -> "l$i" to l }, budget)

    fun decide(judgment: Judgment.Choice, item: WebItem): WebDecision {
        val state = state(item)
        val itemId = "web:$source:${item.id}"
        val scored = runCatching { backend.score(judgment, state) }
        val raw = scored.mapCatching { judgment.validate(it.masses) }
        val failure = raw.exceptionOrNull()
        if (failure != null) {
            val reason = failure.message ?: "unusable response"
            val s = scored.getOrNull()
            val row = LedgerRow(
                judgmentId = judgment.id,
                criteriaHash = judgment.criteriaHash,
                distribution = judgment.noInformation(),
                action = Policy.UNUSABLE,
                propensity = Probability.of(1.0),
                failure = reason,
                itemId = itemId,
                resolvedBy = ResolvedBy.Unusable,
                truncation = Truncation(state.budgetCut, s?.modelContext, s?.optionCriteria),
            )
            return WebDecision(WebVerdict(item.id, 0.0, 0, 0.0, true, state.budgetCut != null, reason), row)
        }
        val s = scored.getOrThrow()
        val truncation = Truncation(state.budgetCut, s.modelContext, s.optionCriteria)
        val calibrated = recalibrator.calibrate(raw.getOrThrow())
        val decision = Policy.onCutInput(Policy.decide(calibrated, Probability.of(threshold)), truncation, judgment.onFailure)
        val labels = judgment.candidates
        val (value, level) = if (judgment.ordinal) {
            val n = labels.size
            val expected = labels.indices.sumOf { i -> i * calibrated.getValue(labels[i]).value }
            val top = labels.indices.maxByOrNull { calibrated.getValue(labels[it]).value } ?: 0
            (expected / (n - 1)) to (top + 1)
        } else {
            calibrated.getValue(labels[0]).value to 0
        }
        val verdict = WebVerdict(
            id = item.id,
            value = value,
            level = level,
            margin = calibrated.distribution.margin,
            unsure = decision !is Decision.Act,
            truncated = truncation.isCut,
            failure = null,
        )
        val row = LedgerRow(
            judgmentId = judgment.id,
            criteriaHash = judgment.criteriaHash,
            distribution = calibrated.distribution,
            action = Policy.actionOf(decision),
            propensity = Probability.of(1.0),
            itemId = itemId,
            resolvedBy = ResolvedBy.Model,
            truncation = truncation,
        )
        return WebDecision(verdict, row)
    }

    companion object {
        /** Characters of one item's facts. */
        const val BUDGET: Int = 480

        /** The acceptance threshold under Model settings: `accept_confidence`, else the type's start. */
        fun threshold(type: WebDecisionType, policy: dev.loupe.kit.settings.RunPolicy): Double =
            policy.threshold(WebQuestion.startThreshold(type))

        /** A judge under Model settings: its threshold as [threshold], `text_chars` for the budget. */
        fun forPolicy(backend: Backend, source: String, type: WebDecisionType, policy: dev.loupe.kit.settings.RunPolicy): WebJudge =
            WebJudge(backend, source, threshold(type, policy), policy.budget(BUDGET), Recalibrator.Identity)

        /**
         * Best first: usable answers by value, then unusable ones; ties keep the order given (the
         * caller passes items in the rule order, so a tie falls back to the baseline).
         */
        fun order(verdicts: List<WebVerdict>): List<WebVerdict> =
            verdicts.withIndex()
                .sortedWith(compareBy<IndexedValue<WebVerdict>> { it.value.failure != null }
                    .thenByDescending { it.value.value }
                    .thenBy { it.index })
                .map { it.value }
    }
}
