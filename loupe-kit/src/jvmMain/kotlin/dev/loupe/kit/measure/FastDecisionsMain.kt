package dev.loupe.kit.measure

import java.io.File

/**
 * Scores the Fast Decisions development split, and prints the floor every published figure sits on.
 *
 * ```
 * tools/fetch-fast-decisions.sh
 * ./gradlew :loupe-kit:fastDecisions
 * ```
 *
 * With no backend it runs the two reference predictors, which need no model at all:
 *
 * - the **oracle majority prior**, which reads this split's own labels and ignores the text, so it
 *   is a ceiling for a predictor that reads nothing rather than anything shippable;
 * - the **label-name baseline**, which is the dumb baseline §7 requires every judgment to carry.
 *
 * Those two are the point of running it before any model is wired in. A published score means
 * nothing without them: on this split the label names alone answer about a third of the suite, so a
 * model at 46% is a much smaller achievement than a model at 46% would be on a suite with a 5%
 * floor. Print the floor first, then argue about models.
 *
 * To score a real backend, build the judgments with [FastDecisions.judgment] and run
 * `Harness.evaluate` per head — that gives coverage, ECE, Brier and the baseline comparison, which
 * this suite-level number deliberately does not.
 */
object FastDecisionsMain {
    private const val DEFAULT_DIR = "third-party/fast-decisions"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: DEFAULT_DIR)
        val missing = FastDecisions.DOMAINS.filterNot { File(dir, "$it.jsonl").isFile }
        if (missing.isNotEmpty()) {
            println("Fast Decisions is not in ${dir.path}: ${missing.size} of 17 files missing.")
            println("Run tools/fetch-fast-decisions.sh first (it verifies every file's sha256).")
            return
        }

        val domains = FastDecisions.DOMAINS.map { FastDecisions.parse(it, File(dir, "$it.jsonl").readText()) }
        val rows = domains.sumOf { it.rowCount }
        val heads = domains.sumOf { it.heads.size }
        val instances = domains.sumOf { it.cases.size }
        println("Fast Decisions development split: $rows rows, $heads heads, $instances head-instances")
        if (instances != FastDecisions.HEAD_INSTANCES) {
            println("  NOTE: expected ${FastDecisions.HEAD_INSTANCES} head-instances; the upstream files may have moved.")
        }
        println("This is the DEVELOPMENT split. The published table is the held-out test split,")
        println("which is not distributed. Never call a number from here \"the benchmark\".")
        println()

        // The majority prior is per head and reads this split's labels, hence "oracle".
        val majority = HashMap<String, List<String>>()
        for (d in domains) for (h in d.heads) majority[h.id] = FastDecisions.majorityAnswer(d, h)

        val oracle = FastDecisions.score(domains) { majority.getValue(it.head.id) }
        val lexical = FastDecisions.score(domains) { FastDecisions.lexicalPredict(it.head, it.text) }

        println(pad("domain", 22) + pad("heads", 7) + pad("majority", 11) + "label-names")
        for (i in domains.indices) {
            println(
                pad(domains[i].domain, 22) +
                    pad(domains[i].heads.size.toString(), 7) +
                    pad(pct(oracle.perDomain[i].accuracy), 11) +
                    pct(lexical.perDomain[i].accuracy),
            )
        }
        println()
        println("AVERAGE (mean of ${oracle.perDomain.size})   majority ${pct(oracle.average)}   label-names ${pct(lexical.average)}")
        println("POOLED  (${oracle.heads} heads)      majority ${pct(oracle.pooled)}   label-names ${pct(lexical.pooled)}")
        println()
        println("For reference, from https://huggingface.co/fastino/GLiNER2.5-Decide (held-out test split):")
        println("  GLiNER2.5-Decide 340M 60.2%  |  multi-Decide 287M 56.7%  |  Laya Router 46.6%")
        println()
        println("The two heads that are already Loupe features:")
        for (id in FastDecisions.LOUPE_HEADS) {
            val d = domains.first { it.domain == id.substringBefore('.') }
            val h = d.heads.firstOrNull { it.task == id.substringAfter('.') }
            if (h == null) {
                println("  $id: not in this revision of the dataset")
                continue
            }
            val cases = d.cases(h.task)
            val dist = cases.groupingBy { it.trueLabels.joinToString("+") }.eachCount().toList().sortedByDescending { it.second }
            println("  $id: ${cases.size} cases, labels ${h.labels}, gold " + dist.joinToString(", ") { "${it.first}=${it.second}" })
        }
    }

    private fun pct(v: Double): String {
        val tenths = ((v * 1000) + 0.5).toInt()
        return "${tenths / 10}.${tenths % 10}%"
    }

    private fun pad(s: String, n: Int): String = if (s.length >= n) "$s " else s + " ".repeat(n - s.length)
}
