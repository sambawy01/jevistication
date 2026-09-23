package dev.loupe.kit.sweep

import dev.loupe.engine.Backend
import dev.loupe.engine.LedgerRow
import dev.loupe.kit.judgments.JudgmentResults
import dev.loupe.kit.judgments.JudgmentSweep
import dev.loupe.kit.judgments.SweepObserver
import dev.loupe.kit.judgments.SweepPlan
import dev.loupe.kit.judgments.SweepProgress
import dev.loupe.kit.watchers.WatcherReport
import dev.loupe.kit.watchers.WatcherRun
import dev.loupe.sources.common.SourceItem
import dev.loupe.templates.UserJudgment
import kotlin.time.TimeSource

/** Why a coordinated sweep stopped before the end. Everything decided before it is already handed over. */
enum class StopReason {
    /** The user pressed Cancel. */
    CANCELLED,

    /** Foreground model work (or the game) wanted the model: resume when the lane is free. */
    PREEMPTED,

    /** The system is taking the background time back (BGTask expiration). */
    EXPIRED,

    /** The phone is too hot (thermal state serious or critical). */
    THERMAL,

    /** Low Power Mode came on. */
    LOW_POWER,
}

/** How far the whole run is, across every judgment. */
data class CoordinatorProgress(
    /** Items decided so far in this run (model and rule), across judgments. */
    val done: Int,
    /** Items this run set out to decide (already-judged items are not in it). */
    val total: Int,
    /** Items skipped because they were already decided under the judgment's current wording. */
    val alreadyDecided: Int,
    val judgments: Int,
    /** The judgment being swept now; null before the first and once the sweeps are over. */
    val currentJudgmentId: String?,
    val elapsedMillis: Long,
    /** Median model latency per call, ms, over this run; null before any model call. */
    val medianMillis: Double?,
    val running: Boolean,
    /** True while the watchers run, after the sweeps. */
    val watching: Boolean,
) {
    val fraction: Double get() = if (total == 0) 1.0 else done.toDouble() / total
    val itemsPerSecond: Double get() = if (elapsedMillis <= 0) 0.0 else done * 1000.0 / elapsedMillis
}

/** The line Now and the notification show: "412 sorted, 9 need you". Real counts from this run. */
data class SortSummary(
    /** Items decided in this run, by the model or by rule. */
    val sorted: Int,
    /** Of those, answers below the judgment's threshold or unusable: they wait for the user. */
    val needYou: Int,
    /** Items already decided under the current wording, not re-run. */
    val alreadyDecided: Int,
    /** Watcher findings from the run, when the watchers ran. */
    val findings: Int,
) {
    val line: String get() = "$sorted sorted, $needYou need${if (needYou == 1) "s" else ""} you"
}

/** The end of a coordinated run. */
data class CoordinatorResult(
    val progress: CoordinatorProgress,
    val summary: SortSummary,
    /** Null when the run finished; otherwise why it stopped (safe to run again: it resumes). */
    val stopped: StopReason?,
    /** The watchers' report, when the sweeps finished and the watchers ran. */
    val watchers: WatcherReport?,
    /** The first sweep error, if any judgment's sweep failed (the others still ran). */
    val error: String?,
) {
    val finished: Boolean get() = stopped == null
}

/** What a coordinated run reports to its host, on the model thread. */
interface CoordinatorObserver {
    fun onCoordinatorProgress(progress: CoordinatorProgress)

    /** Rows to append to the ledger, in order. They are handed over before any stop returns. */
    fun onCoordinatorRows(rows: List<LedgerRow>)

    /** Checked before every item: null to go on, or why to stop (cancel, expiry, heat, power). */
    fun stopReason(): StopReason?
}

/**
 * F1 + F2 on one model thread: sweeps **every** judgment over every item of every enabled source,
 * skipping items already judged under each judgment's current criteria hash, then runs the
 * watchers. Synchronous; the host calls it on its single model queue.
 *
 * **Resumable by construction.** Rows reach the host at least every [JudgmentSweep.FLUSH_EVERY]
 * items and always before a stop returns, and the plan skips what the ledger already holds, so a
 * run that stops (cancel, preemption, BGTask expiry, heat) and runs again picks up where it left
 * off with no item judged twice.
 *
 * **Priority.** Between items it asks the [lane] whether anything above [ModelPriority.SWEEP] holds a
 * claim, and if so stops with [StopReason.PREEMPTED]: foreground work and the game never wait more
 * than one item for the model.
 */
class SweepCoordinator(private val backend: Backend, private val lane: ModelLane) {

    /** The per-judgment plans for a run, skipping what is already decided under the current wording. */
    fun plans(judgments: List<UserJudgment>, items: List<SourceItem>, ledger: List<LedgerRow>): List<Pair<UserJudgment, SweepPlan>> =
        judgments.map { it to JudgmentResults.plan(ledger, it, items, rerunAll = false) }

    fun run(
        judgments: List<UserJudgment>,
        items: List<SourceItem>,
        ledger: List<LedgerRow>,
        todayIso: String,
        observer: CoordinatorObserver,
    ): CoordinatorResult {
        val mark = TimeSource.Monotonic.markNow()
        val plans = plans(judgments, items, ledger)
        val total = plans.sumOf { it.second.toJudge.size }
        val skipped = plans.sumOf { it.second.alreadyDecided }
        val latencies = ArrayList<Long>()
        val timed = Backend { j, state ->
            val t0 = TimeSource.Monotonic.markNow()
            try {
                backend.score(j, state)
            } finally {
                latencies += t0.elapsedNow().inWholeNanoseconds
            }
        }
        var doneBefore = 0
        var sorted = 0
        var needYou = 0
        var stopped: StopReason? = null
        var error: String? = null

        fun progress(current: String?, doneNow: Int, running: Boolean, watching: Boolean = false): CoordinatorProgress {
            val s = latencies.sorted()
            return CoordinatorProgress(
                doneNow, total, skipped, judgments.size, current, mark.elapsedNow().inWholeMilliseconds,
                if (s.isEmpty()) null else s[s.size / 2] / 1e6, running, watching,
            )
        }
        fun reason(): StopReason? =
            observer.stopReason() ?: if (lane.shouldYield(ModelPriority.SWEEP)) StopReason.PREEMPTED else null

        observer.onCoordinatorProgress(progress(null, 0, running = true))
        for ((judgment, plan) in plans) {
            stopped = reason()
            if (stopped != null) break
            if (plan.toJudge.isEmpty()) continue
            val base = doneBefore
            val end: SweepProgress = JudgmentSweep(timed).run(judgment, plan, object : SweepObserver {
                override fun onSweepProgress(progress: SweepProgress) {
                    observer.onCoordinatorProgress(progress(judgment.id, base + progress.done, running = true))
                }
                override fun onSweepRows(rows: List<LedgerRow>) {
                    val shown = JudgmentResults.rows(rows, judgment, emptyMap(), emptyList())
                    sorted += shown.size
                    needYou += shown.count { !it.acted }
                    observer.onCoordinatorRows(rows)
                }
                override fun isCancelled(): Boolean {
                    if (stopped == null) stopped = reason()
                    return stopped != null
                }
            })
            doneBefore += end.done
            if (end.error != null && error == null) error = "${judgment.title}: ${end.error}"
            if (stopped != null) break
        }

        var report: WatcherReport? = null
        if (stopped == null) {
            observer.onCoordinatorProgress(progress(null, doneBefore, running = true, watching = true))
            report = runCatching { WatcherRun.runIso(items, todayIso, timed) }
                .onFailure { if (error == null) error = "watchers: ${it.message ?: it::class.simpleName}" }
                .getOrNull()
        }
        val findings = report?.let {
            it.expiryCandidates.count { c -> c.breachesRule } + it.termChanges.size + it.impersonation.size + it.fraud.size
        } ?: 0
        val last = progress(null, doneBefore, running = false)
        observer.onCoordinatorProgress(last)
        return CoordinatorResult(last, SortSummary(sorted, needYou, skipped, findings), stopped, report, error)
    }
}
