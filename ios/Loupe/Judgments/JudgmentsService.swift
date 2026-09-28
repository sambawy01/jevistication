import Foundation
import LoupeKit

/// Where the Judgments tab gets its model. `LayaModel` in the app; a fake in tests.
@MainActor
protocol JudgmentModelProvider: AnyObject {
    /// The files are on the phone (not yet verified).
    var isInstalled: Bool { get }
    /// Verifies and opens the model off the main thread; nil when not installed or not usable.
    func backend() async -> Backend?
    /// Why the last open failed, if it did.
    var failure: String? { get }
}

extension LayaModel: JudgmentModelProvider {
    var failure: String? {
        if case .failed(let m) = status { return m }
        return nil
    }
}

/// The Judgments tab's state and actions (epic #7 child 3) — the iPhone twin of the desktop
/// controller's judgment half. The rules (ids, template and C2 authoring, lint, sweep, result
/// selection) are LoupeKit's `JudgmentBook` / `JudgmentSweep` / `JudgmentResults`; this only holds
/// the list, persists it through the ledger store, and runs sweeps on a background queue so Laya
/// never runs on the main thread.
@MainActor
final class JudgmentsService: ObservableObject {
    static let shared = JudgmentsService(ledger: LedgerService.shared,
                                         items: { SourcesService.shared.judgeableItems() },
                                         model: JudgmentsService.defaultModel(),
                                         itemsReader: { SourcesService.shared.itemsReader(judgeable: true) })

    /// The decision model, or in DEBUG under `-LoupeStandInModel` the sort demo's stand-in scorer.
    static func defaultModel() -> JudgmentModelProvider {
        #if DEBUG
        if LaunchOptions.current.standInModel { return SortDemoModel() }
        #endif
        return LayaModel.shared
    }

    enum ModelGate: Equatable {
        case unknown
        case ready
        case notInstalled
        case failed(String)
    }

    @Published private(set) var judgments: [UserJudgment] = []
    @Published private(set) var sweep: SweepProgress?
    @Published private(set) var gate: ModelGate = .unknown
    /// One line: what just happened, or what went wrong.
    @Published var notice: String?
    /// Every ledger row, refreshed after a sweep or a load (the tab's results and counts read it).
    @Published private(set) var rows: [LedgerRow] = []
    @Published private(set) var corrections: [CorrectionKey: String] = [:]
    /// Answers given in the queue this session, newest last (what Undo retracts).
    @Published fileprivate(set) var answered: [UnsureEntry] = []
    /// The Unsure queue's current batch, and the ledger/judgments stamp it was drawn from. Drawn in the
    /// background (`scheduleQueueDraw`), never in a view body: over a big ledger the draw reads every
    /// row's item (2026-09-28: drawn in Now's body, it hung the launch until iOS killed the app).
    @Published fileprivate(set) var batch: [UnsureEntry] = [] {
        didSet { if batchDrawn { needsYou = batch.count } }
    }
    fileprivate var batchStamp = ""
    fileprivate var batchDrawn = false
    /// "Needs you: N" — how many items the current batch holds; nil until the first draw has finished
    /// (Now shows "—" and a small spinner, never a made-up zero).
    @Published private(set) var needsYou: Int?
    /// A draw of the Unsure queue is running in the background.
    @Published private(set) var drawingQueue = false
    /// The queue opened from one judgment's results: that judgment's own batch, drawn the same way.
    @Published fileprivate var judgmentBatches: [String: (stamp: String, entries: [UnsureEntry])] = [:]
    fileprivate var queueGeneration = 0
    fileprivate var drawingStamp = ""
    fileprivate var queueTask: Task<Void, Never>?
    fileprivate var judgmentDraws: [String: Task<Void, Never>] = [:]
    /// The evidence gate's verdicts across draws; touched only on `drawQueue` (it is not thread-safe).
    fileprivate let evidenceMemo = EvidenceMemo(capacity: 50_000)
    /// `counts(_:)` per judgment wording, until the ledger is read again (corrections do not change them).
    private var countsMemo: [String: JudgmentCounts] = [:]
    private var countingKeys: Set<String> = []
    /// Bumped when background counts land (My judgments repaints its cards).
    @Published private(set) var countsVersion = 0

    let ledger: LedgerService
    private let sourceItems: () -> [SourceItem]
    /// The same items, read off the main thread (the app's sources parse their cache files to answer).
    fileprivate let itemsReader: () -> ItemsReader
    private let model: JudgmentModelProvider
    fileprivate let settings: ModelSettingsSource
    private var bridge: SweepBridge?
    private var loaded = false
    #if DEBUG
    /// `-LoupeBigLedger` and the perf tests: generated items beside the sources' own.
    var debugItems: [SourceItem] = []
    #endif

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], model: JudgmentModelProvider,
         settings: ModelSettingsSource = ModelSettingsService.shared, itemsReader: (() -> ItemsReader)? = nil) {
        self.ledger = ledger
        self.sourceItems = items
        self.model = model
        self.settings = settings
        self.itemsReader = itemsReader ?? { let list = items(); return ItemsReader { list } }
    }

    private func items() -> [SourceItem] {
        #if DEBUG
        if !debugItems.isEmpty { return sourceItems() + debugItems }
        #endif
        return sourceItems()
    }

    /// `itemsReader` plus the DEBUG items.
    fileprivate func offMainItems() -> ItemsReader {
        let reader = itemsReader()
        #if DEBUG
        let extra = debugItems
        if !extra.isEmpty { return ItemsReader { reader() + extra } }
        #endif
        return reader
    }

    var running: Bool { sweep?.running == true }

    /// Loads the saved judgments and the ledger once.
    func load() {
        guard !loaded else { return }
        loaded = true
        do {
            judgments = try ledger.judgments()
        } catch {
            notice = "Your judgments could not be read: \(error.localizedDescription)"
        }
        refreshLedger()
        gate = model.isInstalled ? .unknown : .notInstalled
    }

    func refreshLedger() {
        rows = ledger.allRows()
        corrections = ledger.correctionIndex()
        countsMemo.removeAll()
        drawQueueIfStale()
    }

    /// `load()` then `refreshLedger()`, with the ledger read off the main thread (Now's first appearance:
    /// the ledger may still be opening, and over thousands of rows that is not work for the main thread).
    func loadInBackground() async {
        let ledger = self.ledger
        let first = !loaded
        loaded = true
        let read = await Task.detached(priority: .userInitiated) { () -> (Result<[UserJudgment], Error>?, [LedgerRow], [CorrectionKey: String]) in
            (first ? Result { try ledger.judgments() } : nil, ledger.allRows(), ledger.correctionIndex())
        }.value
        if let js = read.0 {
            switch js {
            case .success(let list): judgments = list
            case .failure(let error): notice = "Your judgments could not be read: \(error.localizedDescription)"
            }
            gate = model.isInstalled ? .unknown : .notInstalled
        }
        rows = read.1
        corrections = read.2
        countsMemo.removeAll()
        drawQueueIfStale()
    }

    func judgment(_ id: String) -> UserJudgment? { judgments.first { $0.id == id } }

    func sampleItems() -> [SourceItem] { items() }

    // MARK: Making judgments

    /// C1's "Use this". Returns the refusal reasons, or the new judgment's id.
    @discardableResult
    func useTemplate(_ templateId: String, values: [String: String] = [:]) -> Result<String, Refusal> {
        add(JudgmentBook.shared.fromTemplate(templateId: templateId, values: values, existing: judgments))
    }

    /// C2's "Write your own".
    @discardableResult
    func create(_ input: EditorInput) -> Result<String, Refusal> {
        add(JudgmentBook.shared.fromEditor(input: input, existing: judgments))
    }

    struct Refusal: Error, Equatable { let reasons: [String] }

    private func add(_ result: BookResult) -> Result<String, Refusal> {
        if let refused = result as? BookResult.Refused { return .failure(Refusal(reasons: refused.reasons)) }
        guard let created = (result as? BookResult.Created)?.judgment else { return .failure(Refusal(reasons: ["could not create"])) }
        let next = judgments + [created]
        guard save(next) else { return .failure(Refusal(reasons: [notice ?? "could not save"])) }
        notice = "Added \"\(created.title)\" to My judgments."
        return .success(created.id)
    }

    /// Shows (or stops showing) the criteria to the model. Calibration restarts either way.
    func setCriteriaInPrompt(_ id: String, on: Bool) {
        guard let j = judgment(id), j.criteriaInPrompt != on else { return }
        let changed = JudgmentBook.shared.withCriteriaInPrompt(judgment: j, on: on)
        if save(judgments.map { $0.id == id ? changed : $0 }) {
            notice = JudgmentBook.shared.criteriaNotice(on: on)
        }
    }

    /// The Edit sheet's Save (audit P1-3, 2026-09-27): a new title, question, options and criteria text, held to the
    /// same lint as when it was made (`JudgmentBook.reword`). It keeps its id, threshold, baseline mode and history;
    /// new wording restarts calibration (earlier decisions stay in the ledger, not counted). Refused while it runs.
    @discardableResult
    func update(_ id: String, title: String, question: String, optionsText: String?, invariant: String, breaks: String,
                lookalikes: String) -> Result<Void, Refusal> {
        guard let j = judgment(id) else { return .failure(Refusal(reasons: ["This judgment was deleted."])) }
        if running && sweep?.judgmentId == id {
            return .failure(Refusal(reasons: ["It is running. Wait for the run to finish, or cancel it, then edit."]))
        }
        let result = JudgmentBook.shared.reword(judgment: j, title: title, question: question, optionsText: optionsText,
                                                invariant: invariant, breaks: breaks, lookalikes: lookalikes)
        if let refused = result as? BookResult.Refused { return .failure(Refusal(reasons: refused.reasons)) }
        guard let edited = (result as? BookResult.Created)?.judgment else { return .failure(Refusal(reasons: ["could not edit"])) }
        guard replace(edited) else { return .failure(Refusal(reasons: [notice ?? "could not save"])) }
        notice = JudgmentBook.shared.editNotice(before: j, edited: edited)
        return .success(())
    }

    /// After "Delete all my Loupe data": forgets everything held here and reads the (now empty) store again.
    func reloadAfterErase() {
        bridge?.cancel()
        bridge = nil
        sweep = nil
        answered = []
        batchDrawn = false
        batch = []
        batchStamp = ""
        needsYou = nil
        judgmentBatches = [:]
        countsMemo.removeAll()
        judgments = []
        notice = nil
        loaded = false
        load()
    }

    /// Removes the judgment. Its ledger rows stay: the ledger is append-only.
    @discardableResult
    func delete(_ id: String) -> Bool {
        guard !running || sweep?.judgmentId != id else {
            notice = "It is running. Cancel the run first, then delete it."
            return false
        }
        guard let j = judgment(id) else { return false }
        guard save(judgments.filter { $0.id != id }) else { return false }
        notice = "Deleted \"\(j.title)\". Its decisions stay in the ledger."
        return true
    }

    fileprivate func setCorrection(_ key: CorrectionKey, _ label: String?) {
        var next = corrections
        next[key] = label
        corrections = next
    }

    /// Replaces the whole list (a pack import, an approved Review proposal). False when it could not be saved.
    func replaceAll(_ next: [UserJudgment]) -> Bool { save(next) }

    fileprivate func replace(_ j: UserJudgment) -> Bool { save(judgments.map { $0.id == j.id ? j : $0 }) }

    private func save(_ next: [UserJudgment]) -> Bool {
        do {
            try ledger.saveJudgments(next)
            judgments = next
            drawQueueIfStale()
            return true
        } catch {
            notice = "Could not save your judgments: \(error.localizedDescription)"
            return false
        }
    }

    // MARK: Reading results

    /// Memoised per wording and threshold until the ledger is read again: My judgments recomputes its cards on
    /// every change here (a correction included), and over 10,000 rows each count is a full pass.
    func counts(_ j: UserJudgment) -> JudgmentCounts {
        let key = countsKey(j)
        if let hit = countsMemo[key] { return hit }
        let c = JudgmentResults.shared.counts(all: rows, judgment: j, corrections: corrections)
        countsMemo[key] = c
        return c
    }

    private func countsKey(_ j: UserJudgment) -> String {
        "\(j.id)|\(j.criteriaHash)|\(j.threshold)|\(j.onFailure.name)|\(rows.count)"
    }

    /// My judgments' card numbers for a view body: the memo, or nil while they are counted in the background
    /// (the card shows "—"). Over thousands of rows a count is a full pass, not work for a render (2026-09-28).
    func cardCounts(_ j: UserJudgment) -> JudgmentCounts? {
        if let hit = countsMemo[countsKey(j)] { return hit }
        let todo = judgments.filter { countsMemo[countsKey($0)] == nil && !countingKeys.contains(countsKey($0)) }
        guard !todo.isEmpty else { return nil }
        let keys = todo.map(countsKey)
        countingKeys.formUnion(keys)
        let input = CountsInput(rows: rows, judgments: todo, corrections: corrections)
        Task { [weak self] in
            let counted = await JudgmentsService.offMain {
                CountsOutput(counts: input.judgments.map { JudgmentResults.shared.counts(all: input.rows, judgment: $0, corrections: input.corrections) })
            }
            guard let self else { return }
            for (k, c) in zip(keys, counted.counts) { countsMemo[k] = c }
            countingKeys.subtract(keys)
            countsVersion += 1
        }
        return nil
    }

    /// Runs `work` on the draw queue (off the main thread) and returns its result.
    nonisolated static func offMain<T>(_ work: @escaping () -> T) async -> T {
        let box = WorkBox(work)
        return await withCheckedContinuation { (c: CheckedContinuation<ResultBox<T>, Never>) in
            drawQueue.async { c.resume(returning: ResultBox(box.work())) }
        }.value
    }

    func results(_ j: UserJudgment) -> [ResultRow] {
        JudgmentResults.shared.rows(all: rows, judgment: j, corrections: corrections, items: items())
    }

    // MARK: Sweeping (F2)

    /// Checks the model without running anything (the results screen's first look).
    func checkModel() async {
        if !model.isInstalled { gate = .notInstalled; return }
        if await model.backend() != nil { gate = .ready } else { gate = model.failure.map { .failed($0) } ?? .notInstalled }
    }

    /// Runs [id] over the scanned items with text, skipping ones already judged under this wording
    /// unless `rerunAll`. Laya runs on a background queue; rows go to the ledger as it goes.
    func startSweep(_ id: String, rerunAll: Bool = false) async {
        guard let j = judgment(id), !running else { return }
        // Model settings, read now: a change applies to the next run, with no restart.
        let policy = settings.policy(Features.shared.JUDGMENTS)
        var backend: Backend?
        if policy.useLaya {
            guard model.isInstalled else {
                gate = .notInstalled
                notice = "The model is not installed, so nothing was judged."
                return
            }
            guard let b = await model.backend() else {
                gate = model.failure.map { .failed($0) } ?? .notInstalled
                return
            }
            backend = b
            gate = .ready
        }
        let all = items()
        if all.isEmpty {
            notice = "No items read yet. Turn on a source in Sources, then run the check."
            return
        }
        let plan = JudgmentResults.shared.planFor(all: ledger.allRows(), judgment: j, items: all, rerunAll: rerunAll, layaOn: policy.useLaya)
        // Decision B: under Auto the baseline answers once it wins on the user's corrections.
        let auto = AutoBaseline.shared.verdict(all: ledger.allRows(), judgment: j, corrections: corrections, items: all).automatic
        let ledger = self.ledger
        // The live run on the results screen: one particle per judged item (ids and numbers only).
        var cancelBridge: (() -> Void)?
        let job = ActivityCenter.shared.start("judgments", title: "act.title.judgments", view: "judgments",
                                              total: plan.toJudge.count, stage: "act.stage.deciding", cancel: { cancelBridge?() })
        let threshold = policy.thresholdFor(judgment: j)
        let bridge = SweepBridge(
            progress: { p in
                job.progress(Int(p.done), of: Int(p.total))
                Task { @MainActor [weak self] in self?.publish(p) }
            },
            rows: { rows in
                ledger.record(rows)
                job.rows(rows, threshold: threshold, model: policy.useLaya ? "multilingual" : nil)
            })
        cancelBridge = { [weak bridge] in bridge?.cancel() }
        self.bridge = bridge
        sweep = SweepProgress(judgmentId: j.id, total: Int32(plan.toJudge.count), done: 0, withoutText: plan.withoutText,
                              alreadyDecided: plan.alreadyDecided, mechanical: 0, unusable: 0, elapsedMillis: 0,
                              medianMillis: nil, running: true, cancelled: false, error: nil, layaOff: !policy.useLaya, noRule: 0)
        // On the one model thread, as foreground work: a passive sort in progress yields to it.
        let end: SweepProgress = await ModelWork.run(.foreground) {
            JudgmentSweep(backend: backend).runWith(judgment: j, plan: plan, observer: bridge, autoBaseline: auto, policy: policy)
        }
        job.finish(end.error != nil ? "error" : end.cancelled ? "cancelled" : "done",
                   end.error != nil ? "act.res.failed" : end.cancelled ? "act.res.stopped" : "act.res.judgments",
                   ["done": Int(end.done), "uncertain": 0])
        settings.recordRun(Features.shared.JUDGMENTS, layaOff: end.layaOff)
        if end.done > 0 { JudgmentRunLog.stamp([j.id]) }
        ledger.flush()
        self.bridge = nil
        sweep = end
        refreshLedger()
        if let e = end.error {
            notice = "The run stopped: \(e). What ran is saved."
        } else if end.cancelled {
            notice = "Cancelled after \(end.done) of \(end.total); what ran is saved."
        } else if end.layaOff {
            notice = "Finished with the decision model off: \(end.done - end.noRule) item(s) answered by rules" +
                (end.noRule > 0 ? ", \(end.noRule) left for a run with the decision model (no rule covers them)." : ".")
        } else {
            notice = "Finished: \(end.done) item(s) judged."
        }
    }

    func cancelSweep() { bridge?.cancel() }

    private func publish(_ p: SweepProgress) {
        // A late progress callback must not overwrite the final state.
        guard let current = sweep, current.running, p.judgmentId == current.judgmentId else { return }
        sweep = p
    }

    /// Waits for work queued on the model queue (tests).
    nonisolated func flush() { ModelWork.queue.sync {} }
}

/// Kotlin's `SweepObserver`, called on the sweep queue. The cancel flag is read there and set from
/// the main thread, so it is behind a lock.
final class SweepBridge: NSObject, SweepObserver, @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private let progress: (SweepProgress) -> Void
    private let rows: ([LedgerRow]) -> Void

    init(progress: @escaping (SweepProgress) -> Void, rows: @escaping ([LedgerRow]) -> Void) {
        self.progress = progress
        self.rows = rows
    }

    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    func isCancelled() -> Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    func onSweepProgress(progress: SweepProgress) { self.progress(progress) }
    func onSweepRows(rows: [LedgerRow]) { self.rows(rows) }
}

extension UserJudgment {
    /// The warn-only / band-aware way an answer is shown (LoupeKit's rule, shared with the desktop).
    func shown(_ label: String) -> String { JudgmentResults.shared.shownAnswer(judgment: self, label: label) }
    var shapeName: String { JudgmentResults.shared.shapeName(shape: shape) }
}

extension SourceItem {
    /// Where the item came from, for a result row.
    var sourceLabel: String {
        if sourceId == SourcesService.sampleId { return "Test fixture" }
        if let imported = facts["imported"] { return imported }   // an Inbox item: where it came from (child 15)
        return sourceId
    }
}

// MARK: - Unsure queue and measurement (epic #7 child 4: D1-D4 on the phone)

/// The queue, the Measure screen and Me's line read these. The rules (ranking with the audit arm,
/// criteria-hash keys, gating thresholds, the A8 preview, the Harness baseline) are LoupeKit's
/// `JudgmentMeasure`, shared with the desktop's numbers; this only writes answers to the ledger.
extension JudgmentsService {
    /// D1 across every judgment: most torn first, then a random audit arm of confident answers.
    /// Held as a batch: answered items leave it (so the count drops), and it is drawn again — in the
    /// background — only when it runs out or the ledger or the judgments changed. Reading it is free:
    /// before the first draw has finished it is empty and `needsYou` is nil.
    func unsure() -> [UnsureEntry] { batch }

    /// Changes when a run adds rows or a judgment is added, removed or reworded.
    fileprivate var stamp: String { "\(rows.count)|" + judgments.map { "\($0.id):\($0.criteriaHash)" }.joined(separator: ",") }

    /// The serial queue every draw runs on, off the main thread and off the model's queue (a draw never needs Laya).
    nonisolated static let drawQueue = DispatchQueue(label: "com.loupe-ai.ios.unsure-draw", qos: .userInitiated)

    #if DEBUG
    /// Tests: called on the draw queue with whether the draw ran on the main thread, and how long it took.
    nonisolated(unsafe) static var drawObserver: ((_ onMain: Bool, _ seconds: Double) -> Void)?
    #endif

    /// Draws the queue again in the background; the newest request wins (an older draw's result is dropped).
    func scheduleQueueDraw() {
        queueGeneration += 1
        let generation = queueGeneration
        drawingStamp = stamp
        drawingQueue = true
        queueTask = Task { [weak self] in await self?.drawQueue(generation) }
    }

    /// Draws again only when the ledger or the judgments changed since the batch held (or being drawn):
    /// the batch is held, so an answer drops the count rather than being topped up by a redraw.
    func drawQueueIfStale() {
        let current = stamp
        if drawingQueue ? drawingStamp != current : (!batchDrawn || batchStamp != current) { scheduleQueueDraw() }
    }

    /// The old name: draws the queue again (in the background).
    func refillBatch() { scheduleQueueDraw() }

    /// Waits until the queue's latest draw has landed (tests, and the queue screen before its first draw).
    func settleQueue() async {
        if !batchDrawn && !drawingQueue { scheduleQueueDraw() }
        while drawingQueue, let task = queueTask { await task.value }
    }

    private func drawQueue(_ generation: Int) async {
        let stamp = self.stamp
        let drawn = await Self.draw(rows: rows, judgments: judgments, corrections: corrections, reader: offMainItems(),
                                    textChars: textChars(), memo: evidenceMemo)
        guard generation == queueGeneration else { return }   // a newer draw is on its way
        // Answers given while it was drawing are not asked again.
        let answeredNow = corrections
        batchStamp = stamp
        batchDrawn = true
        batch = drawn.filter { answeredNow[$0.key] == nil }
        drawingQueue = false
    }

    /// The characters the evidence gate reads: the judgments' model text budget under Model settings.
    fileprivate func textChars() -> Int32 {
        settings.policy(Features.shared.JUDGMENTS).budget(builtIn: DecisionEngine.companion.DEFAULT_STATE_BUDGET)
    }

    /// One draw of `JudgmentMeasure.queue`, on `drawQueue`: the items are read there too.
    nonisolated fileprivate static func draw(rows: [LedgerRow], judgments: [UserJudgment], corrections: [CorrectionKey: String],
                                             reader: ItemsReader, textChars: Int32, memo: EvidenceMemo) async -> [UnsureEntry] {
        let input = DrawInput(rows: rows, judgments: judgments, corrections: corrections, reader: reader, memo: memo)
        return await withCheckedContinuation { (c: CheckedContinuation<DrawOutput, Never>) in
            drawQueue.async {
                #if DEBUG
                dispatchPrecondition(condition: .notOnQueue(.main))
                let start = Date()
                #endif
                let entries = JudgmentMeasure.shared.queue(all: input.rows, judgments: input.judgments, corrections: input.corrections,
                                                           items: input.reader(), size: JudgmentMeasure.shared.QUEUE_SIZE,
                                                           textChars: textChars, memo: input.memo)
                #if DEBUG
                drawObserver?(Thread.isMainThread, Date().timeIntervalSince(start))
                #endif
                c.resume(returning: DrawOutput(entries: entries))
            }
        }.entries
    }

    /// The queue for one judgment (opened from its results), or across all of them for nil: the batch held
    /// for it, empty until `prepareQueue` has drawn it. Drawn by the same rule (most torn first plus the
    /// audit arm) over that judgment alone, and held the same way.
    func unsure(judgmentId: String?) -> [UnsureEntry] {
        guard let id = judgmentId else { return unsure() }
        guard judgment(id) != nil, let held = judgmentBatches[id], held.stamp == "\(stamp)|\(id)" else { return [] }
        return held.entries
    }

    /// Whether `unsure(judgmentId:)` holds a drawn batch (the queue screen shows a spinner until it does).
    func queueReady(judgmentId: String?) -> Bool {
        guard let id = judgmentId else { return batchDrawn && !(drawingQueue && batch.isEmpty) }
        guard judgment(id) != nil else { return true }
        return judgmentBatches[id]?.stamp == "\(stamp)|\(id)"
    }

    /// Draws the batch `unsure(judgmentId:)` returns, in the background, unless it is already held.
    func prepareQueue(judgmentId: String?) async {
        guard let id = judgmentId else { await settleQueue(); return }
        guard let j = judgment(id) else { return }
        let s = "\(stamp)|\(id)"
        if let held = judgmentBatches[id], held.stamp == s, !held.entries.isEmpty { return }
        if let running = judgmentDraws[s] { await running.value; return }
        let task = Task { [weak self] in
            guard let self else { return }
            let drawn = await Self.draw(rows: rows, judgments: [j], corrections: corrections, reader: offMainItems(),
                                        textChars: textChars(), memo: evidenceMemo)
            guard "\(stamp)|\(id)" == s else { return }   // the ledger or the judgment changed meanwhile
            let answeredNow = corrections
            judgmentBatches[id] = (s, drawn.filter { answeredNow[$0.key] == nil })
        }
        judgmentDraws[s] = task
        await task.value
        judgmentDraws[s] = nil
    }

    /// The old batch ran out: draw the next one (answers leave the batch; an empty one is drawn again).
    fileprivate func redrawIfEmpty() {
        if batchDrawn && batch.isEmpty && !drawingQueue { scheduleQueueDraw() }
    }

    /// Answers from the results screen (one item, or many at once): exactly the records the queue writes —
    /// `JudgmentMeasure.correction` keyed by item + criteria hash, `confirmed` when it agrees with the model's
    /// pick, and a retraction for nil — appended to the corrections log, with one publish for the batch.
    func recordCorrections(_ j: UserJudgment, _ changes: [(itemId: String, label: String?, modelPick: String)]) {
        guard !changes.isEmpty else { return }
        let at = Self.now()
        var next = corrections
        var keys = Set<CorrectionKey>()
        for c in changes {
            let key = CorrectionKey(judgmentId: j.id, criteriaHash: j.criteriaHash, itemId: c.itemId)
            if let label = c.label {
                ledger.recordCorrection(JudgmentMeasure.shared.correction(judgment: j, itemId: c.itemId, label: label,
                                                                          confirmed: label == c.modelPick, at: at))
            } else {
                ledger.recordCorrection(JudgmentMeasure.shared.retraction(key: key, at: at))
            }
            next[key] = c.label
            keys.insert(key)
        }
        corrections = next
        // An item answered here leaves the queue's batches, as an answer given in the queue does.
        batch.removeAll { keys.contains($0.key) }
        if var held = judgmentBatches[j.id] {
            held.entries.removeAll { keys.contains($0.key) }
            judgmentBatches[j.id] = held
        }
        redrawIfEmpty()
    }

    /// A one-tap answer: a correction keyed by item + the judgment's current criteria hash.
    func answer(_ entry: UnsureEntry, label: String) {
        let record = JudgmentMeasure.shared.correction(judgment: entry.judgment, itemId: entry.itemId, label: label,
                                                       confirmed: label == entry.modelPick, at: Self.now())
        ledger.recordCorrection(record)
        setCorrection(entry.key, label)
        answered.append(entry)
        batch.removeAll { $0.key == entry.key }
        judgmentBatches[entry.judgment.id]?.entries.removeAll { $0.key == entry.key }
        redrawIfEmpty()
    }

    var canUndo: Bool { !answered.isEmpty }

    /// Retracts the last answer given here (appends a retraction; the log is never rewritten).
    func undoLastAnswer() {
        guard let entry = answered.popLast() else { return }
        ledger.recordCorrection(JudgmentMeasure.shared.retraction(key: entry.key, at: Self.now()))
        setCorrection(entry.key, nil)
        batch.insert(entry, at: 0)
        judgmentBatches[entry.judgment.id]?.entries.insert(entry, at: 0)
        notice = "Undid your last answer."
    }

    func measure(_ j: UserJudgment) -> MeasureSummary {
        JudgmentMeasure.shared.summary(all: rows, judgment: j, corrections: corrections)
    }

    func preview(_ j: UserJudgment, candidate: Double) -> PreviewText {
        JudgmentMeasure.shared.preview(all: rows, judgment: j, corrections: corrections, candidate: candidate)
    }

    func baseline(_ j: UserJudgment) -> BaselineVerdict? {
        JudgmentMeasure.shared.baseline(all: rows, judgment: j, corrections: corrections, items: items())
    }

    func overall() -> OverallAgreement {
        JudgmentMeasure.shared.overall(all: rows, judgments: judgments, corrections: corrections)
    }

    /// D3's apply: writes the judgment's threshold.
    func setThreshold(_ id: String, _ value: Double) {
        guard let j = judgment(id) else { return }
        let next = JudgmentMeasure.shared.withThreshold(judgment: j, value: value)
        if replace(next) { notice = String(format: "Threshold for \"%@\" set to %.2f.", j.title, next.threshold) }
    }

    /// The old switch: "Always baseline" on, or back to Auto.
    func setUseBaseline(_ id: String, _ on: Bool) {
        setBaselineMode(id, on ? .alwaysBaseline : .auto_)
    }

    /// Decision B: who answers from the next run on — Auto, Always baseline or Always Laya.
    func setBaselineMode(_ id: String, _ mode: BaselineMode) {
        guard let j = judgment(id), j.baselineMode != mode else { return }
        if replace(JudgmentMeasure.shared.withBaselineMode(judgment: j, mode: mode)) {
            switch mode {
            case .alwaysBaseline: notice = "\"\(j.title)\" now always answers by its baseline rule; the decision model is not asked. Re-run to apply."
            case .alwaysLaya: notice = "\"\(j.title)\" now always answers with the decision model."
            default: notice = "\"\(j.title)\": Auto — the baseline answers only while it beats the decision model on your corrections."
            }
        }
    }

    /// Decision B's verdict: who answers now, and on what evidence.
    func autoBaseline(_ j: UserJudgment) -> AutoBaselineVerdict {
        AutoBaseline.shared.verdict(all: rows, judgment: j, corrections: corrections, items: items())
    }

    static func now() -> String { ISO8601DateFormatter().string(from: Date()) }
}

/// What a draw reads, handed to the draw queue in one piece (LoupeKit's objects are safe to read from any
/// thread; the memo is only ever touched on `JudgmentsService.drawQueue`).
private struct DrawInput: @unchecked Sendable {
    let rows: [LedgerRow]
    let judgments: [UserJudgment]
    let corrections: [CorrectionKey: String]
    let reader: ItemsReader
    let memo: EvidenceMemo
}

private struct DrawOutput: @unchecked Sendable { let entries: [UnsureEntry] }
private struct CountsInput: @unchecked Sendable { let rows: [LedgerRow]; let judgments: [UserJudgment]; let corrections: [CorrectionKey: String] }
private struct CountsOutput: @unchecked Sendable { let counts: [JudgmentCounts] }
private struct WorkBox<T>: @unchecked Sendable { let work: () -> T; init(_ work: @escaping () -> T) { self.work = work } }
private struct ResultBox<T>: @unchecked Sendable { let value: T; init(_ value: T) { self.value = value } }

#if DEBUG
/// `-LoupeQueueDemo` (with `-LoupeFixtures`, so the ledger is throwaway): a deterministic stand-in
/// backend so UI tests can drive the queue without the model. Never used outside that flag.
final class FixtureQueueBackend: NSObject, Backend {
    func score(judgment: JudgmentChoice, state: TextState) -> Scored {
        // A spread of confidences from the text length, stable across runs.
        let p = 0.5 + Double(state.text.count % 45) / 100
        return Scored(masses: [judgment.candidates[0]: KotlinDouble(value: p), judgment.candidates[1]: KotlinDouble(value: 1 - p)],
                      modelContext: nil, optionCriteria: nil)
    }
}

extension JudgmentsService {
    func seedFixtureQueue() async {
        load()
        if judgments.first(where: { $0.templateId == "is-receipt" }) == nil { useTemplate("is-receipt") }
        guard let j = judgments.first(where: { $0.templateId == "is-receipt" }) else { return }
        let all = items()
        let plan = JudgmentResults.shared.plan(all: ledger.allRows(), judgment: j, items: all, rerunAll: false)
        let ledger = self.ledger
        let bridge = SweepBridge(progress: { _ in }, rows: { ledger.record($0) })
        // Calibration off: the stand-in's spread is its own, not the real graph's the shipped prior is fitted to.
        let store = EngineSettingsStore(directory: nil)
        _ = store.setBool(key: "global.use_calibration", value: false)
        _ = JudgmentSweep(backend: FixtureQueueBackend()).runWith(judgment: j, plan: plan, observer: bridge, autoBaseline: false,
                                                               policy: store.current.policy(feature: Features.shared.JUDGMENTS))
        ledger.flush()
        refreshLedger()
    }

    /// `-LoupeBigLedger n` and the perf tests: n long documents (LoupeKit's `LargeLedgerFixture`, 20–50 KB each)
    /// and one model answer per item under "Is this a receipt?", logged as if before the evidence gate — so every
    /// draw of the Unsure queue re-checks each one, the owner's case (2026-09-28). All of it off the main thread.
    func seedLargeLedger(count: Int) async {
        await loadInBackground()
        if judgments.first(where: { $0.templateId == "is-receipt" }) == nil { useTemplate("is-receipt") }
        guard let j = judgments.first(where: { $0.templateId == "is-receipt" }) else { return }
        let ledger = self.ledger
        let seeded = await Task.detached(priority: .userInitiated) { () -> SeededItems in
            let items = LargeLedgerFixture.shared.items(count: Int32(count))
            let plan = JudgmentResults.shared.plan(all: ledger.allRows(), judgment: j, items: items, rerunAll: false)
            let store = EngineSettingsStore(directory: nil)
            _ = store.setBool(key: "global.use_calibration", value: false)
            _ = store.setBool(key: "global.rules_first", value: false)   // the gate stays out of the run: rows as before it
            let bridge = SweepBridge(progress: { _ in }, rows: { ledger.record($0) })
            _ = JudgmentSweep(backend: FixtureQueueBackend()).runWith(judgment: j, plan: plan, observer: bridge, autoBaseline: false,
                                                                   policy: store.current.policy(feature: Features.shared.JUDGMENTS))
            ledger.flush()
            return SeededItems(items: items)
        }.value
        debugItems = seeded.items
        await loadInBackground()
    }
}

private struct SeededItems: @unchecked Sendable { let items: [SourceItem] }
#endif
