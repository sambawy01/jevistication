package dev.loupe.kit.judgments

import dev.loupe.engine.Backend
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Mechanical
import dev.loupe.engine.Probability
import dev.loupe.kit.measure.AutoBaseline
import dev.loupe.kit.settings.Features
import dev.loupe.kit.settings.RunPolicy
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.BaselineMode
import dev.loupe.templates.MechanicalCheck
import dev.loupe.templates.TransactionEvidence
import dev.loupe.templates.UserJudgment
import kotlin.time.TimeSource

/** A retroactive sweep (F2) in progress or just finished — the desktop's `SweepState`. */
data class SweepProgress(
    val judgmentId: String,
    val total: Int,
    val done: Int,
    val withoutText: Int,
    val alreadyDecided: Int,
    val mechanical: Int,
    val unusable: Int,
    val elapsedMillis: Long,
    /** Median model latency per item, in ms, over the model calls so far; null before any. */
    val medianMillis: Double?,
    val running: Boolean,
    val cancelled: Boolean,
    val error: String?,
    /** The run had `features.judgments.use_laya` off: answers came from rules only (the banner). */
    val layaOff: Boolean = false,
    /** With Laya off: items no rule could answer (no baseline, not a duplicate), left undecided. */
    val noRule: Int = 0,
) {
    val fraction: Double get() = if (total == 0) 1.0 else done.toDouble() / total
    val itemsPerSecond: Double get() = if (elapsedMillis <= 0) 0.0 else done * 1000.0 / elapsedMillis
}

/** What a sweep reports to its host. Called on the sweep's own thread. */
interface SweepObserver {
    // Named apart from ScanObserver.onProgress: one ObjC selector with two types gets mangled in Swift.
    fun onSweepProgress(progress: SweepProgress)

    /** Rows to append to the ledger, in order, at least every [JudgmentSweep.FLUSH_EVERY] items. */
    fun onSweepRows(rows: List<LedgerRow>)

    /** Checked before every item. */
    fun isCancelled(): Boolean
}

/**
 * Runs one judgment across items (F2), the desktop controller's sweep loop without its threads:
 * synchronous and CPU-bound, so the host calls it off the main thread. Mechanical first (A3: an
 * exact duplicate is answered by SHA-256 and the model is not asked); every decision is a ledger
 * row handed to [SweepObserver.onSweepRows]. It never throws: a failure is reported as the final
 * progress's [SweepProgress.error], with every row decided before it already handed over.
 */
class JudgmentSweep(private val backend: Backend?) {

    /** The defaults' policy: what a sweep did before Model settings. */
    fun run(judgment: UserJudgment, plan: SweepPlan, observer: SweepObserver, autoBaseline: Boolean = false): SweepProgress =
        runWith(judgment, plan, observer, autoBaseline, RunPolicy.defaults(Features.JUDGMENTS))

    /**
     * [autoBaseline]: the judgment is on [BaselineMode.AUTO] and its baseline currently wins on the
     * user's corrections ([dev.loupe.kit.measure.AutoBaselineVerdict.automatic]). The model is still
     * asked; the baseline's answer is logged as the decision and the model's kept alongside.
     *
     * [policy] is Model settings for this run (`features.judgments`): with `use_laya` off the model
     * is never asked (the backend may be null) — a duplicate, "Always baseline" or the judgment's
     * baseline rule answers (logged `mechanical:laya-off`), and an item no rule covers is left
     * undecided ([SweepProgress.noRule]). `accept_confidence` replaces the starting threshold (one set
     * on Measure still wins), `text_chars` the 4,000-character budget, `rules_first` off lets Laya
     * answer exact duplicates, and `baseline_switch` off keeps Laya answering under Auto.
     */
    fun runWith(judgment: UserJudgment, plan: SweepPlan, observer: SweepObserver, autoBaseline: Boolean, policy: RunPolicy): SweepProgress {
        val mark = TimeSource.Monotonic.markNow()
        val todo = plan.toJudge
        val buffer = ArrayList<LedgerRow>()
        val latencies = ArrayList<Long>()
        var done = 0
        var unusable = 0
        var byRule = 0
        var noRule = 0
        var cancelled = false
        val layaOff = !policy.useLaya
        val switchToBaseline = autoBaseline && policy.baselineSwitch

        fun flush() {
            if (buffer.isEmpty()) return
            observer.onSweepRows(buffer.toList())
            buffer.clear()
        }
        fun progress(running: Boolean, error: String? = null): SweepProgress {
            val sorted = latencies.sorted()
            return SweepProgress(
                judgment.id, todo.size, done, plan.withoutText, plan.alreadyDecided, byRule, unusable,
                mark.elapsedNow().inWholeMilliseconds,
                if (sorted.isEmpty()) null else sorted[sorted.size / 2] / 1e6,
                running, cancelled, error, layaOff, noRule,
            )
        }

        observer.onSweepProgress(progress(running = true))
        return try {
            val model = backend ?: if (layaOff) NO_MODEL else throw IllegalStateException("the model is not available")
            val engine = DecisionEngine(
                model, Probability.of(policy.thresholdFor(judgment)),
                stateBudget = policy.budget(DecisionEngine.DEFAULT_STATE_BUDGET),
            )
            for (item in todo) {
                if (observer.isCancelled()) {
                    cancelled = true
                    break
                }
                val t0 = TimeSource.Monotonic.markNow()
                val check = if (layaOff) rulesOnly(judgment, item) else mechanical(judgment, item, policy.rulesFirst)
                if (layaOff && check !is Mechanical.Resolved) {
                    noRule++
                    done++
                    observer.onSweepProgress(progress(running = true))
                    continue
                }
                val outcome = engine.decide(judgment.choice, item.toItem()) { check }
                if (outcome.resolvedMechanically) byRule++ else latencies += t0.elapsedNow().inWholeNanoseconds
                var row = outcome.row
                val baseline = judgment.baseline
                if (switchToBaseline && judgment.baselineMode == BaselineMode.AUTO && baseline != null && !outcome.resolvedMechanically) {
                    // Decision B: the baseline answers, Laya's answer is kept beside it.
                    row = AutoBaseline.resolve(row, baseline.answer(item.text))
                    byRule++
                }
                if (row.failure != null) unusable++
                buffer += row
                done++
                if (buffer.size >= FLUSH_EVERY) flush()
                observer.onSweepProgress(progress(running = true))
            }
            flush()
            progress(running = false).also(observer::onSweepProgress)
        } catch (e: Throwable) {
            runCatching { flush() }
            progress(running = false, error = e.message ?: e::class.simpleName ?: "the sweep failed").also { p ->
                runCatching { observer.onSweepProgress(p) }
            }
        }
    }

    companion object {
        /** Rows are handed over at least this often during a sweep. */
        const val FLUSH_EVERY: Int = 16

        /** The ledger check name for a rule's answer while Laya is off: `mechanical:laya-off`. */
        const val LAYA_OFF_CHECK: String = "laya-off"

        /** Stands in for the model when Laya is off; never called (every item goes to a rule or is skipped). */
        private val NO_MODEL: Backend = Backend { _, _ -> throw IllegalStateException("The decision model is off for judgments") }

        /**
         * With Laya off, the rules alone: "Always baseline", an exact duplicate, then the judgment's
         * baseline rule (logged [LAYA_OFF_CHECK]); [Mechanical.Deferred] when none applies.
         */
        fun rulesOnly(judgment: UserJudgment, item: SourceItem): Mechanical<String> {
            val first = mechanical(judgment, item)
            if (first is Mechanical.Resolved) return first
            val baseline = judgment.baseline ?: return Mechanical.Deferred
            return Mechanical.Resolved(baseline.answer(item.text), LAYA_OFF_CHECK)
        }

        /**
         * [mechanical] under `rules_first`: off, the exact-duplicate rule and the transaction-evidence
         * gate no longer answer before Laya ("Always baseline" is the judgment's own override and still does).
         */
        fun mechanical(judgment: UserJudgment, item: SourceItem, rulesFirst: Boolean): Mechanical<String> {
            if (rulesFirst) return mechanical(judgment, item)
            val baseline = judgment.baseline
            return if (judgment.baselineMode == BaselineMode.ALWAYS_BASELINE && baseline != null) {
                Mechanical.Resolved(baseline.answer(item.text), AutoBaseline.ALWAYS_CHECK)
            } else {
                Mechanical.Deferred
            }
        }

        /** A judgment's mechanical check, where it has one (A3: the model is not asked). */
        fun mechanical(judgment: UserJudgment, item: SourceItem): Mechanical<String> {
            val positive = judgment.positiveLabel
            val baseline = judgment.baseline
            if (judgment.baselineMode == BaselineMode.ALWAYS_BASELINE && baseline != null) {
                // "Always baseline": the dumb rule answers, the model is not asked; logged as mechanical.
                return Mechanical.Resolved(baseline.answer(item.text), AutoBaseline.ALWAYS_CHECK)
            }
            if (judgment.mechanical == MechanicalCheck.EXACT_DUPLICATE && item.duplicateOf != null && positive != null) {
                return Mechanical.Resolved(positive, "exact-duplicate")
            }
            // The money judgments' evidence gate: no sign of a payment (or a shop's product page) is
            // answered "no" by rule, so it never reaches the model or the Unsure queue.
            TransactionEvidence.ruleAnswer(judgment, item.text)?.let { return Mechanical.Resolved(it, TransactionEvidence.CHECK) }
            return Mechanical.Deferred
        }
    }
}
