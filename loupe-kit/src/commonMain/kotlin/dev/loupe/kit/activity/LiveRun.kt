package dev.loupe.kit.activity

/**
 * The live run view's shape, shared with Loupe Station (`static/js/live-run.js`): the loop of stage nodes and
 * decision diamonds per kind, which node a stage key lights, where each gate's particle ends, how particles
 * are batched, and when the screen-reader line may speak. The views (SwiftUI on iPhone) draw from these.
 */
data class LiveNode(
    /** A loop stage id: `fetch`, `walk`, `items`, `read`, `q1`…`q3`, `rules`, `evidence`, `outcome`, `review`. */
    val id: String,
    val key: String?,
    val sub: String?,
    val diamond: Boolean,
    /** Where the stage sits along the loop, 0 = entry (Station's geometry). */
    val t: Double,
)

data class LivePipe(
    /** The layout: `email_run`, `scan`, `watchers` or `mail_history` (Station's four). */
    val layout: String,
    val unit: String,
    /** Question ids the diamonds show before any decision arrives. */
    val questions: List<String>,
    val nodes: List<LiveNode>,
) {
    val ids: List<String> get() = nodes.map { it.id }
    fun node(id: String): LiveNode? = nodes.firstOrNull { it.id == id }
}

/** A particle to launch: its gate (or `reading`), where it ends, and its delay in ms. */
data class LiveSpawn(val fate: String, val end: String, val delayMs: Int)

object LiveRun {
    const val MAX_BURST: Int = 6
    const val MAX_READING: Int = 4
    const val LOOP_MS: Int = 2800
    const val LIVE_EVERY_MS: Long = 12_000

    private fun diamonds() = listOf(
        LiveNode("q1", null, null, true, 0.2095), LiveNode("q2", null, null, true, 0.2804), LiveNode("q3", null, null, true, 0.3512),
    )

    private fun pipe(layout: String, unit: String, questions: List<String>, first: Pair<String, Pair<String, String>>, read: Pair<String, String>,
                     rules: Pair<String, String>, evidence: Pair<String, String>, outcome: Pair<String, String>, review: Pair<String, String>) =
        LivePipe(
            layout, unit, questions,
            listOf(LiveNode(first.first, first.second.first, first.second.second, false, 0.0), LiveNode("read", read.first, read.second, false, 0.1337)) +
                diamonds() + listOf(
                    LiveNode("rules", rules.first, rules.second, false, 0.5),
                    LiveNode("evidence", evidence.first, evidence.second, false, 0.6387),
                    LiveNode("outcome", outcome.first, outcome.second, false, 0.75),
                    LiveNode("review", review.first, review.second, false, 0.8613),
                ),
        )

    val EMAIL: LivePipe = pipe(
        "email_run", "act.unit.emails", listOf("category", "needs_reply", "urgency"),
        "fetch" to ("act.node.fetch" to "act.node.fetchSub"), "act.node.read" to "act.node.readSub",
        "act.node.rules" to "act.node.rulesSub", "act.node.evidence" to "act.node.evidenceSub",
        "act.node.outcome" to "act.node.outcomeSub", "act.node.review" to "act.node.reviewSub",
    )
    val SCAN: LivePipe = pipe(
        "scan", "act.unit.files", listOf("category", "business", "sensitive"),
        "walk" to ("act.node.walk" to "act.node.walkSub"), "act.node.readFile" to "act.node.readFileSub",
        "act.node.detectors" to "act.node.detectorsSub", "act.node.dupes" to "act.node.dupesSub",
        "act.node.outcome" to "act.node.outcomeFileSub", "act.node.reviewFile" to "act.node.reviewFileSub",
    )
    val HISTORY: LivePipe = pipe(
        "mail_history", "act.unit.emails", emptyList(),
        "fetch" to ("act.node.search" to "act.node.searchSub"), "act.node.read" to "act.node.headersSub",
        "act.node.extract" to "act.node.extractSub", "act.node.stored" to "act.node.storedSub",
        "act.node.feedWatchers" to "act.node.feedWatchersSub", "act.node.review" to "act.node.reviewSub",
    )
    val WATCHERS: LivePipe = pipe(
        "watchers", "act.unit.items", emptyList(),
        "items" to ("act.node.items" to "act.node.itemsSub"), "act.node.readItem" to "act.node.readItemSub",
        "act.node.rules" to "act.node.rulesSub", "act.node.findings" to "act.node.findingsSub",
        "act.node.outcome" to "act.node.outcomeItemSub", "act.node.reminders" to "act.node.remindersSub",
    )

    /**
     * The loop a kind draws, or null for kinds without one (model loads, list refreshes, drafts: a progress
     * line only). The phone's kinds reuse Station's layouts: an inbox import is a folder scan of what was
     * shared; judgments, the passive sort, flights and the game read items as the watchers do.
     */
    fun pipeFor(kind: String): LivePipe? = when (kind) {
        "email_run" -> EMAIL
        "scan", "inbox", "source_scan" -> SCAN
        "mail_history" -> HISTORY
        "watchers", "judgments", "sort", "flights", "game", "web_search" -> WATCHERS
        else -> null
    }

    /** Where a gate's particle ends (Station's FATE_END). */
    val FATE_END: Map<String, String> = mapOf("accepted" to "outcome", "uncertain" to "review", "flagged" to "review", "skipped" to "rules")

    /** Which stage node a stage key lights (Station's STAGE_NODE, then the phone's stage keys). */
    val STAGE_NODE: Map<String, List<String>> = mapOf(
        "act.stage.connecting" to listOf("fetch", "items", "walk"), "act.stage.fetching" to listOf("fetch"),
        "act.stage.history.searching" to listOf("fetch"), "act.stage.history.waiting" to listOf("fetch"),
        "act.stage.history.headers" to listOf("read"), "act.stage.history.candidates" to listOf("read"),
        "act.stage.history.extracting" to listOf("rules"), "act.stage.history.feeding" to listOf("outcome"),
        "act.stage.walking" to listOf("walk"), "act.stage.starting" to listOf("items", "walk", "fetch"),
        "act.stage.watcher" to listOf("read"), "act.stage.loadingModel" to listOf("read"),
        "act.stage.waiting_models" to listOf("read"), "act.stage.classifying" to listOf("read"),
        "act.stage.deciding" to listOf("read"), "act.stage.asking" to listOf("read"), "act.stage.hashing" to listOf("evidence"),
        "act.stage.refining" to listOf("review"), "act.stage.finishing" to listOf("outcome"),
        // the phone's own stage keys (docs/LIVE-RUN-VIEW.md)
        "act.stage.ranking" to listOf("read"), "act.stage.playing" to listOf("read"),
        "act.stage.importing" to listOf("walk"), "act.stage.reading" to listOf("read"),
    )

    /** The lit node for a running job, or null (a finished job lights none). */
    fun activeNode(job: JobSnapshot): String? {
        if (!job.running) return null
        val pipe = pipeFor(job.kind) ?: return null
        val key = job.stage?.key ?: return null
        return STAGE_NODE[key]?.firstOrNull { pipe.node(it) != null }
    }

    /**
     * The three question ids the diamonds show: the most recent distinct ones in the decision log, newest last
     * (q1 = oldest of the three), topped up with the pipe's catalogue questions while fewer have been asked.
     */
    fun diamondQuestions(job: JobSnapshot): List<String> {
        val seen = mutableListOf<String>()
        for (d in job.decisions.asReversed()) {
            val q = d.q ?: continue
            if (q !in seen) seen += q
            if (seen.size == 3) break
        }
        val recent = seen.reversed()
        if (recent.size >= 3) return recent
        val fill = (pipeFor(job.kind)?.questions ?: emptyList()).filter { it !in recent }
        return (fill.take(3 - recent.size) + recent).take(3)
    }

    /** The latest answer to [question] in the log, or null. */
    fun latestAnswer(job: JobSnapshot, question: String): ActDecision? = job.decisions.lastOrNull { it.q == question }

    /** The questions answered since [previous] (their diamonds pulse). */
    fun freshQuestions(previous: JobSnapshot?, job: JobSnapshot): List<String> {
        if (previous == null || previous.id != job.id || job.seq <= previous.seq) return emptyList()
        return job.decisions.filter { it.seq > previous.seq }.mapNotNull { it.q }.distinct()
    }

    /**
     * The particles for what really moved between two snapshots of one job: one per gated item (at most
     * [MAX_BURST] per gate per update, batched above that), then up to [MAX_READING] for items read but not yet
     * gated. Nothing moved, nothing spawns.
     */
    fun spawns(previous: JobSnapshot?, job: JobSnapshot): List<LiveSpawn> {
        if (previous == null || previous.id != job.id) return emptyList()
        val pipe = pipeFor(job.kind) ?: return emptyList()
        val out = mutableListOf<LiveSpawn>()
        var delay = 0
        var gated = 0
        for (fate in ActivityNames.GATES) {
            val n = maxOf(0, job.gate(fate) - previous.gate(fate))
            gated += n
            val end = FATE_END[fate] ?: "outcome"
            repeat(minOf(n, MAX_BURST)) {
                out += LiveSpawn(fate, end, delay)
                delay += 120
            }
        }
        val read = maxOf(0, job.counter("read") - previous.counter("read"))
        repeat(minOf(maxOf(0, read - gated), MAX_READING)) {
            out += LiveSpawn("reading", pipe.node("read")?.id ?: "read", delay)
            delay += 90
        }
        return out
    }

    /** How many items one particle stands for when a gate moved more than a burst: shown as "×n". */
    fun batchSize(previous: JobSnapshot?, job: JobSnapshot, fate: String): Int {
        if (previous == null || previous.id != job.id) return 1
        val n = job.gate(fate) - previous.gate(fate)
        return if (n > MAX_BURST) (n + MAX_BURST - 1) / MAX_BURST else 1
    }

    /** The share bars: each source's (or model's) fraction of the answers, 0 when none. */
    fun shares(m: Map<String, Int>): Map<String, Double> {
        val total = m.values.sum()
        return m.mapValues { if (total == 0) 0.0 else it.value.toDouble() / total }
    }
}

/**
 * The screen-reader status line: at most once every [LiveRun.LIVE_EVERY_MS], sooner on a stage change or when
 * the job changes or ends. [text] is what the line would say now; [offer] returns it when it may be spoken.
 */
class LiveAnnouncer(private val everyMs: Long = LiveRun.LIVE_EVERY_MS) {
    private var lastAt: Long = Long.MIN_VALUE / 2
    private var lastStage: String? = null
    private var lastJob: String? = null
    private var lastState: String? = null

    fun offer(nowMs: Long, job: JobSnapshot, text: String): String? {
        val stage = job.stage?.key
        val force = job.id != lastJob || job.state != lastState || stage != lastStage
        if (!force && nowMs - lastAt < everyMs) return null
        lastAt = nowMs
        lastStage = stage
        lastJob = job.id
        lastState = job.state
        return text
    }
}
