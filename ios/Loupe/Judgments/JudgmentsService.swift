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
                                         model: LayaModel.shared)

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
    /// The Unsure queue's current batch, and the ledger size it was drawn from.
    /// Not @Published: it is drawn lazily while a view reads it; answers publish through `corrections`.
    fileprivate(set) var batch: [UnsureEntry] = []
    fileprivate var batchStamp = ""

    let ledger: LedgerService
    private let items: () -> [SourceItem]
    private let model: JudgmentModelProvider
    private var bridge: SweepBridge?
    private var loaded = false

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], model: JudgmentModelProvider) {
        self.ledger = ledger
        self.items = items
        self.model = model
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

    /// Removes the judgment. Its ledger rows stay: the ledger is append-only.
    func delete(_ id: String) {
        guard !running || sweep?.judgmentId != id else { return }
        _ = save(judgments.filter { $0.id != id })
    }

    fileprivate func setCorrection(_ key: CorrectionKey, _ label: String?) {
        var next = corrections
        next[key] = label
        corrections = next
    }

    fileprivate func replace(_ j: UserJudgment) -> Bool { save(judgments.map { $0.id == j.id ? j : $0 }) }

    private func save(_ next: [UserJudgment]) -> Bool {
        do {
            try ledger.saveJudgments(next)
            judgments = next
            return true
        } catch {
            notice = "Could not save your judgments: \(error.localizedDescription)"
            return false
        }
    }

    // MARK: Reading results

    func counts(_ j: UserJudgment) -> JudgmentCounts {
        JudgmentResults.shared.counts(all: rows, judgment: j, corrections: corrections)
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
        guard model.isInstalled else {
            gate = .notInstalled
            notice = "The model is not installed, so nothing was judged."
            return
        }
        guard let backend = await model.backend() else {
            gate = model.failure.map { .failed($0) } ?? .notInstalled
            return
        }
        gate = .ready
        let all = items()
        if all.isEmpty {
            notice = "No items scanned yet. Turn on the sample in Sources."
            return
        }
        let plan = JudgmentResults.shared.plan(all: ledger.allRows(), judgment: j, items: all, rerunAll: rerunAll)
        let ledger = self.ledger
        let bridge = SweepBridge(
            progress: { p in Task { @MainActor [weak self] in self?.publish(p) } },
            rows: { rows in ledger.record(rows) })
        self.bridge = bridge
        sweep = SweepProgress(judgmentId: j.id, total: Int32(plan.toJudge.count), done: 0, withoutText: plan.withoutText,
                              alreadyDecided: plan.alreadyDecided, mechanical: 0, unusable: 0, elapsedMillis: 0,
                              medianMillis: nil, running: true, cancelled: false, error: nil)
        // On the one model thread, as foreground work: a passive sort in progress yields to it.
        let end: SweepProgress = await ModelWork.run(.foreground) {
            JudgmentSweep(backend: backend).run(judgment: j, plan: plan, observer: bridge)
        }
        ledger.flush()
        self.bridge = nil
        sweep = end
        refreshLedger()
        if let e = end.error {
            notice = "The run stopped: \(e). What ran is saved."
        } else if end.cancelled {
            notice = "Cancelled after \(end.done) of \(end.total); what ran is saved."
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
    var sourceLabel: String { sourceId == SourcesService.sampleId ? "Sample data" : sourceId }
}

// MARK: - Unsure queue and measurement (epic #7 child 4: D1-D4 on the phone)

/// The queue, the Measure screen and Me's line read these. The rules (ranking with the audit arm,
/// criteria-hash keys, gating thresholds, the A8 preview, the Harness baseline) are LoupeKit's
/// `JudgmentMeasure`, shared with the desktop's numbers; this only writes answers to the ledger.
extension JudgmentsService {
    /// D1 across every judgment: most torn first, then a random audit arm of confident answers.
    /// Held as a batch: answered items leave it (so the count drops), and it is drawn again only
    /// when it runs out or the ledger gained rows (a new run).
    func unsure() -> [UnsureEntry] {
        if batch.isEmpty || batchStamp != stamp { refillBatch() }
        return batch
    }

    /// Changes when a run adds rows or a judgment is added, removed or reworded.
    private var stamp: String { "\(rows.count)|" + judgments.map { "\($0.id):\($0.criteriaHash)" }.joined(separator: ",") }

    func refillBatch() {
        batchStamp = stamp
        batch = JudgmentMeasure.shared.queue(all: rows, judgments: judgments, corrections: corrections,
                                             items: items(), size: JudgmentMeasure.shared.QUEUE_SIZE)
    }

    /// "Needs you: N" — how many items the current batch still holds.
    var needsYou: Int { unsure().count }

    /// A one-tap answer: a correction keyed by item + the judgment's current criteria hash.
    func answer(_ entry: UnsureEntry, label: String) {
        let record = JudgmentMeasure.shared.correction(judgment: entry.judgment, itemId: entry.itemId, label: label,
                                                       confirmed: label == entry.modelPick, at: Self.now())
        ledger.recordCorrection(record)
        setCorrection(entry.key, label)
        answered.append(entry)
        batch.removeAll { $0.key == entry.key }
    }

    var canUndo: Bool { !answered.isEmpty }

    /// Retracts the last answer given here (appends a retraction; the log is never rewritten).
    func undoLastAnswer() {
        guard let entry = answered.popLast() else { return }
        ledger.recordCorrection(JudgmentMeasure.shared.retraction(key: entry.key, at: Self.now()))
        setCorrection(entry.key, nil)
        batch.insert(entry, at: 0)
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

    /// D4's "use the baseline": from the next run the baseline answers (logged as a rule, not the model).
    func setUseBaseline(_ id: String, _ on: Bool) {
        guard let j = judgment(id) else { return }
        if replace(JudgmentMeasure.shared.withBaseline(judgment: j, on: on)) {
            notice = on ? "\"\(j.title)\" now answers by its baseline rule; the model is not asked. Re-run to apply."
                        : "\"\(j.title)\" is back on the model."
        }
    }

    static func now() -> String { ISO8601DateFormatter().string(from: Date()) }
}

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
        _ = JudgmentSweep(backend: FixtureQueueBackend()).run(judgment: j, plan: plan, observer: bridge)
        ledger.flush()
        refreshLedger()
    }
}
#endif
