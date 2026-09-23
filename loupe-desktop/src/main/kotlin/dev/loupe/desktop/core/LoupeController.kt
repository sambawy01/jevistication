package dev.loupe.desktop.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.loupe.engine.Backend
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Export
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Mechanical
import dev.loupe.engine.Probability
import dev.loupe.engine.VisibleCalibration
import dev.loupe.game.desktop.LoadedModel
import dev.loupe.sources.SampleData
import dev.loupe.sources.ScanProgress
import dev.loupe.sources.ScanResult
import dev.loupe.sources.Scanner
import dev.loupe.sources.SourceItem
import dev.loupe.sources.SourceSpec
import dev.loupe.sources.SourceType
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.MechanicalCheck
import dev.loupe.templates.Template
import dev.loupe.templates.UserJudgment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The decision model, as far as the app is concerned. */
sealed interface ModelState {
    data object Loading : ModelState

    /** [loaded] is the real Laya model (also lent to the game); null for a stub in tests. */
    data class Ready(val backend: Backend, val description: String, val loaded: LoadedModel?) : ModelState

    data class Unavailable(val message: String) : ModelState
}

/** The app's screens, in navigation order. */
enum class Screen(val title: String, val perJudgment: Boolean) {
    SOURCES("Sources", false),
    LIBRARY("Library", false),
    JUDGMENTS("Judgments", false),
    RESULTS("Results", true),
    QUEUE("Uncertain queue", true),
    CALIBRATION("Calibration", true),
    THRESHOLD("Threshold", true),
    BASELINE("Baseline", true),
    CENSUS("Census", false),
    WATCHERS("Watchers", false),
    EXPORT("Export", false),
    GAME("Watch it think", false),
}

/** A retroactive sweep (F2) in progress or just finished. */
data class SweepState(
    val judgmentId: String,
    val total: Int,
    val done: Int,
    /** Items not sent to the model because they carry no text (images, scans). */
    val withoutText: Int,
    /** Items already decided under this wording, not re-run. */
    val alreadyDecided: Int,
    val mechanical: Int,
    val unusable: Int,
    val elapsedMillis: Long,
    /** Median model latency per item, in ms, over the model calls so far. */
    val medianMillis: Double?,
    val running: Boolean,
    val cancelled: Boolean,
    val error: String? = null,
) {
    val itemsPerSecond: Double get() = if (elapsedMillis <= 0) 0.0 else done * 1000.0 / elapsedMillis

    /** Seconds left at the current rate, or null before there is a rate. */
    val etaSeconds: Double? get() = if (done == 0 || !running) null else (total - done) / itemsPerSecond
}

/**
 * The desktop app's state and every action it can take — with no UI in it, so the whole flow
 * (sources → judgment → sweep → queue → corrections → calibration) runs headless in tests.
 *
 * Threading: scans run on the IO pool; every model call (sweeps, the watchers' model half) runs on
 * one dedicated thread, so the model is never entered twice at once and the UI thread never waits
 * on it. State is Compose snapshot state, written from those threads, read by the screens.
 *
 * Nothing here writes to a source: the scanner is read-only, and the only files written are in
 * [Store.home] and in a directory the user picks for an export.
 */
class LoupeController(
    val store: Store,
    private val scanner: Scanner = Scanner(),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val modelLoader: () -> ModelState,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelThread = Executors.newSingleThreadExecutor { r -> Thread(r, "loupe-model").apply { isDaemon = true } }
    private val modelDispatcher = modelThread.asCoroutineDispatcher()
    private var sweepCancel: AtomicBoolean? = null
    private var scanJob: Job? = null

    var screen: Screen by mutableStateOf(Screen.SOURCES)
    var selectedJudgmentId: String? by mutableStateOf(null)

    var sources: List<SourceSpec> by mutableStateOf(emptyList())
        private set
    var scan: ScanResult by mutableStateOf(ScanResult.EMPTY)
        private set
    var itemsById: Map<String, SourceItem> by mutableStateOf(emptyMap())
        private set
    var scanning: Boolean by mutableStateOf(false)
        private set
    var scanProgress: ScanProgress? by mutableStateOf(null)
        private set

    var judgments: List<UserJudgment> by mutableStateOf(emptyList())
        private set
    var ledger: List<LedgerRow> by mutableStateOf(emptyList())
        private set
    var corrections: List<Correction> by mutableStateOf(emptyList())
        private set
    var correctionIndex: Map<CorrectionKey, String> by mutableStateOf(emptyMap())
        private set
    var loadReport: LoadReport? by mutableStateOf(null)
        private set

    var model: ModelState by mutableStateOf(ModelState.Loading)
        private set
    var sweep: SweepState? by mutableStateOf(null)
        private set

    var watcherReport: WatcherReport? by mutableStateOf(null)
        private set
    var watchersRunning: Boolean by mutableStateOf(false)
        private set

    /** One line for the status bar: what just happened, or what went wrong. */
    var notice: String? by mutableStateOf(null)

    var gameOpen: Boolean by mutableStateOf(false)
        private set

    val backend: Backend? get() = (model as? ModelState.Ready)?.backend

    val selectedJudgment: UserJudgment? get() = judgments.firstOrNull { it.id == selectedJudgmentId }

    fun judgment(id: String): UserJudgment? = judgments.firstOrNull { it.id == id }

    /**
     * Loads what was saved, starts loading the model and rescans the sources. Returns the scan job
     * (the model loads independently; model-dependent actions are disabled until it is ready).
     */
    fun start(loadModel: Boolean = true): Job {
        val (rows, badRows) = store.loadLedger()
        val (fixes, badFixes) = store.loadCorrections()
        ledger = rows
        corrections = fixes
        correctionIndex = Analysis.correctionIndex(fixes)
        judgments = store.loadJudgments()
        sources = store.loadSources()
        selectedJudgmentId = judgments.firstOrNull()?.id
        loadReport = LoadReport(rows.size, fixes.size, badRows + badFixes)
        if (badRows + badFixes > 0) {
            notice = "${badRows + badFixes} saved line(s) could not be read and were skipped (see Export for the files)."
        }
        if (loadModel) {
            scope.launch(modelDispatcher) {
                val state = runCatching(modelLoader).getOrElse { ModelState.Unavailable("Model failed to load: ${it.message}") }
                model = state
            }
        } else {
            model = ModelState.Unavailable("Model loading switched off.")
        }
        return rescan()
    }

    // ------------------------------------------------------------------ sources

    /** Adds a folder or mail export. Returns an error message, or null when added. */
    fun addSource(type: SourceType, path: Path): String? {
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.exists(absolute)) return "$absolute does not exist."
        if (type == SourceType.FOLDER && !Files.isDirectory(absolute)) return "$absolute is not a folder."
        if (sources.any { it.path == absolute }) return "$absolute is already a source."
        val id = "src-" + (sources.maxOfOrNull { it.id.removePrefix("src-").toIntOrNull() ?: 0 }?.plus(1) ?: 1)
        sources = sources + SourceSpec(id, type, absolute)
        store.saveSources(sources)
        rescan()
        return null
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
        store.saveSources(sources)
        rescan()
    }

    /** Copies the synthetic sample dataset into the app's home and adds it as two sources. */
    fun loadSampleData(): Job {
        val root = SampleData.materialize(store.home.resolve("sample-data"))
        val docs = root.resolve(SampleData.DOCUMENTS).toAbsolutePath().normalize()
        val mail = root.resolve(SampleData.MAIL).toAbsolutePath().normalize()
        var next = sources
        var n = next.maxOfOrNull { it.id.removePrefix("src-").toIntOrNull() ?: 0 } ?: 0
        if (next.none { it.path == docs }) next = next + SourceSpec("src-${++n}", SourceType.FOLDER, docs)
        if (next.none { it.path == mail }) next = next + SourceSpec("src-${++n}", SourceType.MAIL_EXPORT, mail)
        sources = next
        store.saveSources(sources)
        notice = "Sample data copied to $root — synthetic, invented for the demo."
        return rescan()
    }

    /** Rescans every source in the background. A scan already running is cancelled first. */
    fun rescan(): Job {
        scanJob?.cancel()
        val cancelled = AtomicBoolean(false)
        val specs = sources
        scanning = true
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO) {
            try {
                val result = scanner.scan(specs, progress = { scanProgress = it }, isCancelled = { cancelled.get() || !isActive })
                if (!cancelled.get()) {
                    scan = result
                    itemsById = result.items.associateBy { it.id }
                    // What the watchers found was about the previous scan.
                    watcherReport = null
                }
            } finally {
                // Only the latest scan clears the flag; a superseded one must not.
                if (scanJob === job) scanning = false
            }
        }
        job.invokeOnCompletion { if (it != null) cancelled.set(true) }
        scanJob = job
        return job
    }

    val sourceNames: Map<String, String>
        get() = sources.associate { it.id to (it.path.fileName?.toString() ?: it.path.toString()) }

    // ------------------------------------------------------------------ judgments

    private fun newJudgmentId(base: String): String {
        val stem = "j-" + base.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "judgment" }
        var id = stem
        var n = 2
        while (judgments.any { it.id == id }) id = "$stem-${n++}"
        return id
    }

    /** Creates a judgment from [template]. Returns the lint's findings, or empty when created. */
    fun useTemplate(template: Template, values: Map<String, String> = emptyMap()): List<String> =
        when (val result = template.instantiate(newJudgmentId(template.id), values)) {
            is Template.InstantiateResult.Rejected -> result.findings.map { it.message }
            is Template.InstantiateResult.Created -> {
                addJudgment(result.judgment)
                emptyList()
            }
        }

    fun createFromDraft(draft: JudgmentDraft): List<String> =
        when (val result = draft.compile(newJudgmentId(draft.title.ifBlank { draft.question }))) {
            is UserJudgment.EditResult.Rejected -> result.findings.map { it.message }
            is UserJudgment.EditResult.Edited -> {
                addJudgment(result.judgment)
                emptyList()
            }
        }

    private fun addJudgment(judgment: UserJudgment) {
        judgments = judgments + judgment
        store.saveJudgments(judgments)
        selectedJudgmentId = judgment.id
        notice = "Created \"${judgment.title}\". Run it across your items from Judgments."
    }

    /** Rewords a judgment. Its calibration restarts, because the criteria hash changes. */
    fun reword(
        id: String,
        question: String,
        options: List<String>?,
        title: String,
        invariant: String,
        breaks: String,
        lookalikes: String,
    ): List<String> {
        val current = judgment(id) ?: return listOf("no such judgment")
        return when (val result = current.reword(question, options, title, invariant, breaks, lookalikes)) {
            is UserJudgment.EditResult.Rejected -> result.findings.map { it.message }
            is UserJudgment.EditResult.Edited -> {
                replace(result.judgment)
                notice = if (result.judgment.criteriaHash != current.criteriaHash) {
                    "Reworded. Its calibration starts again: earlier decisions were made under different wording."
                } else {
                    "Saved. The wording the model reads is unchanged, so calibration carries over."
                }
                emptyList()
            }
        }
    }

    fun setThreshold(id: String, value: Double) {
        val current = judgment(id) ?: return
        replace(current.copy(threshold = value.coerceIn(0.0, 1.0)))
        notice = "Threshold for \"${current.title}\" set to %.2f.".format(value)
    }

    /** Removes the judgment. Its ledger rows stay: the ledger is append-only, and exports keep them. */
    fun deleteJudgment(id: String) {
        judgments = judgments.filterNot { it.id == id }
        store.saveJudgments(judgments)
        if (selectedJudgmentId == id) selectedJudgmentId = judgments.firstOrNull()?.id
    }

    private fun replace(judgment: UserJudgment) {
        judgments = judgments.map { if (it.id == judgment.id) judgment else it }
        store.saveJudgments(judgments)
    }

    // ------------------------------------------------------------------ sweep (F2)

    /**
     * Runs [judgmentId] across every scanned item with text, in the background, appending a ledger
     * row per decision. Items already decided under the current wording are skipped unless
     * [rerunAll]. Returns null (with a [notice]) when it cannot start.
     */
    fun sweep(judgmentId: String, rerunAll: Boolean = false): Job? {
        val judgment = judgment(judgmentId) ?: return null
        val backend = backend ?: run {
            notice = "The model is not loaded, so judgments cannot run. Mechanical features still work."
            return null
        }
        if (sweep?.running == true) {
            notice = "A sweep is already running."
            return null
        }
        val items = scan.items
        val decided = if (rerunAll) emptySet() else Analysis.effectiveRows(ledger, judgment, correctionIndex).mapNotNull { it.itemId }.toSet()
        val withText = items.filter { it.hasText }
        val todo = withText.filter { it.id !in decided }
        val cancel = AtomicBoolean(false)
        sweepCancel = cancel
        val started = System.nanoTime()
        sweep = SweepState(judgmentId, todo.size, 0, items.size - withText.size, withText.size - todo.size, 0, 0, 0L, null, running = true, cancelled = false)

        return scope.launch(modelDispatcher) {
            val engine = DecisionEngine(backend, Probability.of(judgment.threshold))
            val buffer = ArrayList<LedgerRow>()
            val latencies = ArrayList<Long>()
            var done = 0
            var unusable = 0
            fun flush() {
                if (buffer.isEmpty()) return
                store.appendLedger(buffer)
                ledger = ledger + buffer
                buffer.clear()
            }
            fun publish(running: Boolean, error: String? = null) {
                val sorted = latencies.sorted()
                sweep = SweepState(
                    judgmentId, todo.size, done, items.size - withText.size, withText.size - todo.size,
                    engine.stats.resolved, unusable, (System.nanoTime() - started) / 1_000_000,
                    if (sorted.isEmpty()) null else sorted[sorted.size / 2] / 1e6,
                    running, cancel.get(), error,
                )
            }
            try {
                for (item in todo) {
                    if (cancel.get()) break
                    val t0 = System.nanoTime()
                    val outcome = engine.decide(judgment.choice, item.toItem()) { mechanical(judgment, item) }
                    if (!outcome.resolvedMechanically) latencies += System.nanoTime() - t0
                    if (outcome.row.failure != null) unusable++
                    buffer += outcome.row
                    done++
                    if (buffer.size >= FLUSH_EVERY) flush()
                    publish(running = true)
                }
                flush()
                publish(running = false)
                notice = if (cancel.get()) "Sweep cancelled after $done of ${todo.size}; what ran is saved." else "Sweep finished: $done item(s) judged."
            } catch (e: Throwable) {
                flush()
                publish(running = false, error = e.message ?: e::class.simpleName)
                throw e
            }
        }
    }

    fun cancelSweep() {
        sweepCancel?.set(true)
    }

    /** A judgment's mechanical check, where it has one (A3: the model is not asked). */
    private fun mechanical(judgment: UserJudgment, item: SourceItem): Mechanical<String> {
        val positive = judgment.positiveLabel
        return if (judgment.mechanical == MechanicalCheck.EXACT_DUPLICATE && item.duplicateOf != null && positive != null) {
            Mechanical.Resolved(positive, "exact-duplicate")
        } else {
            Mechanical.Deferred
        }
    }

    // ------------------------------------------------------------------ corrections (D1)

    /**
     * Records the user's answer for one item under [judgmentId]'s current wording. [confirmed] marks
     * agreeing with the model rather than changing it; both are outcomes the calibration reads.
     */
    fun correct(judgmentId: String, itemId: String, label: String, confirmed: Boolean = false) {
        val judgment = judgment(judgmentId) ?: return
        require(label in judgment.shape.candidates) { "'$label' is not an option of ${judgment.id}" }
        record(Correction(judgmentId, judgment.criteriaHash, itemId, label, Instant.now(), confirmed))
    }

    /** Retracts the most recent correction for [judgmentId] that is still in force (undo). */
    fun undoLastCorrection(judgmentId: String): Boolean {
        val judgment = judgment(judgmentId) ?: return false
        val last = corrections.lastOrNull {
            it.judgmentId == judgmentId && it.criteriaHash == judgment.criteriaHash && it.label != null &&
                correctionIndex[CorrectionKey(judgmentId, judgment.criteriaHash, it.itemId)] == it.label
        } ?: return false
        record(Correction(judgmentId, judgment.criteriaHash, last.itemId, null, Instant.now(), false))
        notice = "Undid the correction on ${itemsById[last.itemId]?.name ?: last.itemId}."
        return true
    }

    private fun record(correction: Correction) {
        store.appendCorrection(correction)
        corrections = corrections + correction
        correctionIndex = Analysis.correctionIndex(corrections)
    }

    // ------------------------------------------------------------------ watchers (C3)

    /** Runs the watchers in the background; the model half only when the model is loaded. */
    fun runWatchers(): Job {
        watchersRunning = true
        val items = scan.items
        val backend = backend
        return scope.launch(modelDispatcher) {
            try {
                watcherReport = Watchers.run(items, today(), backend)
            } finally {
                watchersRunning = false
            }
        }
    }

    // ------------------------------------------------------------------ export (F4)

    /** Writes judgments, per-judgment calibration, the ledger and corrections into [dir]. */
    fun export(dir: Path): List<Path> {
        Files.createDirectories(dir)
        val merged = ledger.map { row ->
            val label = row.itemId?.let { correctionIndex[CorrectionKey(row.judgmentId, row.criteriaHash, it)] }
            if (label != null && label in row.distribution.labels) row.copy(correction = label) else row
        }
        val views = judgments.mapNotNull { j ->
            VisibleCalibration.forJudgment(Analysis.effectiveRows(ledger, j, correctionIndex), j.id)
        }
        val files = listOf(
            dir.resolve("loupe-judgments.json") to Export.judgmentsToJson(judgments.map { it.toDefinition() }),
            dir.resolve("loupe-calibration.json") to Export.calibrationToJson(views),
            dir.resolve("loupe-ledger.jsonl") to Export.ledgerToJsonl(merged) + if (merged.isEmpty()) "" else "\n",
        )
        for ((path, text) in files) Files.writeString(path, text)
        val correctionsCopy = dir.resolve("loupe-corrections.jsonl")
        val source = store.home.resolve("corrections.jsonl")
        if (Files.exists(source)) Files.copy(source, correctionsCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING) else Files.writeString(correctionsCopy, "")
        notice = "Exported ${files.size + 1} files to $dir."
        return files.map { it.first } + listOf(correctionsCopy)
    }

    // ------------------------------------------------------------------ the game

    fun openGame() {
        gameOpen = true
    }

    fun closeGame() {
        gameOpen = false
    }

    override fun close() {
        cancelSweep()
        scope.cancel()
        modelThread.shutdown()
        modelThread.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
        (model as? ModelState.Ready)?.loaded?.close()
    }

    companion object {
        /** Ledger rows are forced to disk at least this often during a sweep. */
        const val FLUSH_EVERY: Int = 16
    }
}
