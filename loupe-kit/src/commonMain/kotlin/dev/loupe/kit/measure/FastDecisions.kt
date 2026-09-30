package dev.loupe.kit.measure

import dev.loupe.engine.Fixture
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.persistence.JsonValue

/*
 * Fast Decisions: an outside eval set for the judgments, read into the shapes the harness takes.
 *
 * PROVENANCE: `fastino/fast-decisions` on Hugging Face, Apache-2.0, the development split (17
 * domains × 100 rows = 1,700 rows, 29 heads, 2,900 head-instances). The data is **not** in this
 * repository: `tools/fetch-fast-decisions.sh` downloads it and verifies every file against
 * `tools/fast-decisions.sha256`, exactly as the Phishing.Database fixtures are handled. A trimmed
 * four-row sample is committed under `src/commonTest/fixtures/fast-decisions/` so the parser is
 * tested against the real shape.
 *
 * WHY IT IS WORTH HAVING: it is the first eval set for these judgments that this project did not
 * write. Two heads land exactly on features Loupe already ships -- `email_triage.is_phishing` and
 * `ticket_route.contains_pii` -- and thirteen more are the routing and document-type decisions the
 * watchers make. §7 says per-judgment measurement, never aggregate; this gives 29 more judgments to
 * measure, authored by someone with no stake in the answer.
 *
 * WHAT IT IS NOT: the vendor's published benchmark. The dataset card says plainly, "Do not report a
 * score computed on the files in this repo as the benchmark" -- the numbers in their table are the
 * held-out 300-per-domain test split, which is not published. A figure computed here is a
 * development-split figure and must always be called one.
 */

/** One decision a row asks for: a named task over a fixed candidate set. */
data class FastHead(
    val domain: String,
    val task: String,
    /** The full candidate set, in the order the dataset gives it. Pass it as-is. */
    val labels: List<String>,
    /** True when several labels may apply at once. */
    val multiLabel: Boolean,
) {
    /** `email_triage.is_phishing` — stable, and used as the judgment id. */
    val id: String get() = "$domain.$task"

    init {
        require(labels.size >= 2) { "$id: a head needs at least two labels, had ${labels.size}" }
        require(labels.distinct().size == labels.size) { "$id: labels must be distinct" }
    }
}

/** One row's gold answer for one head. */
data class FastCase(
    val itemId: String,
    val text: String,
    val head: FastHead,
    /** The gold label(s). Always at least one; more than one only on a multi-label head. */
    val trueLabels: List<String>,
)

/** One domain file, parsed. */
data class FastDomain(
    val domain: String,
    val rowCount: Int,
    val heads: List<FastHead>,
    val cases: List<FastCase>,
) {
    fun head(task: String): FastHead =
        heads.firstOrNull { it.task == task } ?: throw IllegalArgumentException("$domain: no head '$task'")

    fun cases(task: String): List<FastCase> = cases.filter { it.head.task == task }
}

/** A domain's share of the suite score. */
data class FastDomainScore(val domain: String, val correct: Int, val total: Int) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

/**
 * The suite score, in both of the shapes the vendor's own conversion reports.
 *
 * [average] is the mean of the per-domain accuracies — the figure their table quotes. [pooled] is
 * over every head-instance, which weights a four-head domain four times as heavily. They differ by
 * a point or two, and quoting one while implying the other is how benchmark numbers drift.
 */
data class FastSuiteScore(val perDomain: List<FastDomainScore>) {
    val average: Double get() = if (perDomain.isEmpty()) 0.0 else perDomain.sumOf { it.accuracy } / perDomain.size

    val pooled: Double
        get() {
            val total = perDomain.sumOf { it.total }
            return if (total == 0) 0.0 else perDomain.sumOf { it.correct }.toDouble() / total
        }

    val heads: Int get() = perDomain.sumOf { it.total }
}

object FastDecisions {
    /** The 17 domain files, in the dataset card's order. */
    val DOMAINS: List<String> = listOf(
        "support_intent", "support_topic", "document_type", "review_sentiment", "agent_handoff",
        "email_triage", "ticket_route", "product_feedback", "banking_intent", "clinic_request",
        "travel_request", "news_topic", "paper_field", "sports_recap", "restaurant_review",
        "benefits_request", "screen_tags",
    )

    /** Rows per domain in the development split. */
    const val ROWS_PER_DOMAIN: Int = 100

    /** Head-instances in the whole development split — 29 heads across the 17 files. */
    const val HEAD_INSTANCES: Int = 2_900

    /**
     * The two heads that are already Loupe features, so a number on them is worth more than a
     * number on the other twenty-seven.
     *
     * Both are near-balanced binaries on the development split — `is_phishing` is 53 no / 47 yes,
     * `contains_pii` is 52 yes / 48 no — which is what makes them usable. A coin flip scores about
     * 50%, the majority answer 53% and 52%, and the label-name baseline 57% and 53%. So anything the
     * phishing formula or the privacy check scores here is a real reading against a known floor,
     * on data this project did not write.
     */
    val LOUPE_HEADS: List<String> = listOf("email_triage.is_phishing", "ticket_route.contains_pii")

    /**
     * The question text a head is asked with: the task name, and a question mark.
     *
     * The dataset carries a task *name* and a label set, no question, and `Judgment.Choice`
     * requires one. So this wording is **ours, not the vendor's**, which matters more than it
     * looks: §7 makes prompt wording a controlled variable and pins it into `criteriaHash`, and a
     * comparison where one arm got a better-written question is not a comparison of models.
     *
     * Hence the deliberate terseness — "is phishing?", "intent?", "doc type?". A fuller sentence
     * would read better and would be our writing rather than the dataset's, and any gain from it
     * would show up as the model's. One rule, every head, every arm, no per-head tuning possible.
     */
    fun questionFor(head: FastHead): String = head.task.replace('_', ' ') + "?"

    /**
     * Parses one domain file (JSON Lines).
     *
     * A head's label set is asserted constant across the file: the card says to pass `labels`
     * as-is, and a set that varied row to row would mean a different judgment each row, which
     * `Judgment.Choice` could not represent and a reader would never notice.
     *
     * @throws IllegalArgumentException naming the row on malformed input.
     */
    fun parse(domain: String, jsonl: String): FastDomain {
        val heads = LinkedHashMap<String, FastHead>()
        val cases = mutableListOf<FastCase>()
        var row = 0
        for (line in jsonl.lineSequence()) {
            if (line.isBlank()) continue
            val where = "$domain row $row"
            val obj = runCatching { JsonValue.parse(line).asObj }.getOrElse {
                throw IllegalArgumentException("$where: not a JSON object (${it.message})")
            }
            val text = (obj["input"] as? JsonValue.Str)?.value
                ?: throw IllegalArgumentException("$where: 'input' must be a string")
            val output = obj["output"]?.let { it as? JsonValue.Obj }
                ?: throw IllegalArgumentException("$where: 'output' must be an object")
            val classifications = (output["classifications"] as? JsonValue.Arr)?.items
                ?: throw IllegalArgumentException("$where: 'output.classifications' must be an array")
            require(classifications.isNotEmpty()) { "$where: no classifications" }

            val itemId = "$domain#$row"
            for (raw in classifications) {
                val c = raw as? JsonValue.Obj
                    ?: throw IllegalArgumentException("$where: a classification must be an object")
                val task = (c["task"] as? JsonValue.Str)?.value
                    ?: throw IllegalArgumentException("$where: 'task' must be a string")
                // Missing and mistyped are told apart on purpose: a field that is there but the
                // wrong shape is a different upstream change from one that is gone, and reporting
                // both as "no gold label" would send whoever hits it looking in the wrong place.
                val labelsRaw = c["labels"]
                    ?: throw IllegalArgumentException("$where/$task: 'labels' is required")
                require(labelsRaw is JsonValue.Arr) { "$where/$task: 'labels' must be an array of strings" }
                val labels = labelsRaw.items
                    .map { (it as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$where/$task: a label must be a string") }
                val multi = (c["multi_label"] as? JsonValue.Bool)?.value
                    ?: throw IllegalArgumentException("$where/$task: 'multi_label' must be a boolean")
                val goldRaw = c["true_label"]
                    ?: throw IllegalArgumentException("$where/$task: 'true_label' is required")
                require(goldRaw is JsonValue.Arr) { "$where/$task: 'true_label' must be an array of strings" }
                val gold = goldRaw.items
                    .map { (it as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$where/$task: 'true_label' must hold strings") }
                require(gold.isNotEmpty()) { "$where/$task: no gold label" }
                require(gold.all { it in labels }) {
                    "$where/$task: gold ${gold - labels.toSet()} is not in the candidate set"
                }
                require(multi || gold.size == 1) { "$where/$task: single-label head with ${gold.size} gold labels" }

                val head = FastHead(domain, task, labels, multi)
                val known = heads.getOrPut(task) { head }
                require(known.labels == labels) { "$where/$task: the label set differs from an earlier row's" }
                require(known.multiLabel == multi) { "$where/$task: multi_label differs from an earlier row's" }
                cases += FastCase(itemId, text, known, gold)
            }
            row++
        }
        require(row > 0) { "$domain: no rows" }
        return FastDomain(domain, row, heads.values.toList(), cases)
    }

    /**
     * The head as a `Judgment.Choice`, so the whole §7 harness applies to it — coverage, selective
     * accuracy, ECE, Brier, the dumb-baseline comparison.
     *
     * Multi-label heads have no `Choice`: a `Choice` answers with one label and `Distribution.argmax`
     * picks one, so representing "food and service both apply" would mean silently scoring a
     * different task. Those three heads are scored by [score] and excluded here, on purpose.
     */
    fun judgment(head: FastHead): Judgment.Choice {
        require(!head.multiLabel) {
            "${head.id} is multi-label; a Choice answers with one label, so score it with FastDecisions.score"
        }
        return Judgment.Choice(id = head.id, question = questionFor(head), candidates = head.labels)
    }

    /** True when [head] can go through `Harness.evaluate`. */
    fun isHarnessable(head: FastHead): Boolean = !head.multiLabel

    /**
     * The head's cases as fixtures.
     *
     * Every row is its own source group. The dataset gives no grouping — no threads, no repeated
     * documents — so there is nothing for `Fixtures.splitByGroup` to keep together, and saying so
     * here is better than letting a reader assume a grouping was honoured that never existed.
     */
    fun fixtures(domain: FastDomain, head: FastHead): List<Fixture> {
        require(!head.multiLabel) { "${head.id}: a Fixture carries one trueLabel" }
        return domain.cases(head.task).map { Fixture(Item(it.itemId, it.text), it.trueLabels.single(), it.itemId) }
    }

    /**
     * The dumb baseline: match the label's own name against the text.
     *
     * Every judgment carries a baseline and the report says which wins (§7). A zero-shot label set
     * cannot have a hand-written keyword list, so the baseline is the one thing available for free —
     * the label name itself, split into words, scored by how many of them appear in the text. Ties
     * break alphabetically, and a text matching nothing takes the first candidate; both rules are
     * fixed so the figure is reproducible.
     *
     * It is not a weak straw man. On the full development split this scores **35.2% average /
     * 36.7% pooled**, against the vendor's published 46.6% for Laya Router and 60.2% for their own
     * model — so roughly a third of the suite is answerable from the label names alone, and that is
     * how much of any published score is not the model.
     */
    fun lexicalBaseline(head: FastHead): (Item) -> String = { item -> lexicalPredict(head, item.text).first() }

    /** [lexicalBaseline]'s prediction, as a label set, so multi-label heads have one too. */
    fun lexicalPredict(head: FastHead, text: String): List<String> {
        val words = words(text).toSet()
        val scored = head.labels.associateWith { label -> words(label).count { it in words } }
        if (head.multiLabel) {
            val hits = head.labels.filter { scored.getValue(it) > 0 }
            return if (hits.isEmpty()) listOf(head.labels.first()) else hits
        }
        val best = scored.values.max()
        if (best == 0) return listOf(head.labels.first())
        return listOf(head.labels.filter { scored.getValue(it) == best }.min())
    }

    /**
     * The most frequent gold answer for [head].
     *
     * An *oracle* prior: it reads this split's own labels, so it is a ceiling for a predictor that
     * ignores the input, not a baseline anyone could ship. Recorded because it is the honest floor
     * to compare against — on the full development split it scores 25.8% average / 28.8% pooled.
     */
    fun majorityAnswer(domain: FastDomain, head: FastHead): List<String> =
        domain.cases(head.task)
            .groupingBy { it.trueLabels.sorted() }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<List<String>, Int>> { it.value }.thenBy { it.key.joinToString("\u0000") })
            .first().key

    /**
     * Scores [domains] with the dataset card's protocol: one prediction per head, the labels as
     * given, **exact set match**, and the suite figure as the mean of the per-domain accuracies.
     *
     * Exact set match is strict on purpose and is the vendor's own rule: on a multi-label head,
     * getting two of three right scores zero.
     */
    fun score(domains: List<FastDomain>, predict: (FastCase) -> List<String>): FastSuiteScore =
        FastSuiteScore(
            domains.map { d ->
                var correct = 0
                for (case in d.cases) {
                    if (predict(case).toSet() == case.trueLabels.toSet()) correct++
                }
                FastDomainScore(d.domain, correct, d.cases.size)
            },
        )

    /** Lower-cased alphanumeric words, the tokenisation the baseline and label matching share. */
    internal fun words(s: String): List<String> =
        NON_WORD.split(s.lowercase()).filter { it.isNotEmpty() }

    private val NON_WORD = Regex("[^a-z0-9]+")
}
