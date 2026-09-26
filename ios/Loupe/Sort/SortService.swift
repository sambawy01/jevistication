import Foundation
import LoupeKit

/// What the phone's state allows right now (risk 3: battery and thermal cost). Read from the model
/// thread between items, so it must be thread-safe; `ProcessInfo` is.
protocol DeviceConditions: Sendable {
    var isLowPowerMode: Bool { get }
    var thermalState: ProcessInfo.ThermalState { get }
}

struct SystemConditions: DeviceConditions {
    var isLowPowerMode: Bool { ProcessInfo.processInfo.isLowPowerModeEnabled }
    var thermalState: ProcessInfo.ThermalState { ProcessInfo.processInfo.thermalState }
}

extension DeviceConditions {
    /// Why sorting must not run (or must stop) now: Low Power Mode, or a phone at `.serious` heat or worse.
    var blocker: StopReason? {
        if thermalState == .serious || thermalState == .critical { return .thermal }
        if isLowPowerMode { return .lowPower }
        return nil
    }
}

/// Where a run was started from.
enum SortTrigger: String, Codable { case manual, background }

/// The last run's real counts, kept for Now's card and the notification.
struct SortRecord: Codable, Equatable {
    var sorted: Int
    var needYou: Int
    var findings: Int
    var finished: Bool
    var stop: String?
    var at: Date
    var trigger: SortTrigger

    /// "412 sorted, 9 need you".
    var line: String { "\(sorted) sorted, \(needYou) need\(needYou == 1 ? "s" : "") you" }
}

/// F1 + F2 on the iPhone (epic #7 child 6): sweeps every judgment over every enabled source with
/// LoupeKit's `SweepCoordinator` on the one model thread, then the watchers. Runs from Me → Run now
/// or from the BGProcessingTask ("Sort while charging", off by default). Stops, checkpointed in the
/// ledger, on Cancel, BGTask expiry, heat at `.serious`+, Low Power Mode, or foreground model work —
/// and a run stopped by foreground work resumes by itself once the lane is free.
@MainActor
final class SortService: ObservableObject {
    static let shared: SortService = { let s = SortService(
        ledger: LedgerService.shared,
        judgments: {
            JudgmentsService.shared.load()
            #if DEBUG
            if LaunchOptions.current.sortDemo, JudgmentsService.shared.judgments.isEmpty {
                JudgmentsService.shared.useTemplate("is-receipt")
            }
            #endif
            return JudgmentsService.shared.judgments
        },
        items: { SourcesService.shared.items() },
        model: SortService.defaultModel(),
        conditions: SystemConditions(),
        defaults: .standard,
        lane: ModelWork.lane)
        // The Judgments tab, Now's "Needs you" and Me read the new rows.
        s.afterRun = { JudgmentsService.shared.refreshLedger() }
        return s
    }()

    /// Called on the main actor after every run that reached the model.
    var afterRun: (() -> Void)?

    enum Outcome: Equatable {
        case finished(SortRecord)
        case stopped(StopReason, SortRecord)
        /// Nothing ran: the model is missing, there is nothing to sort, or the phone said no.
        case skipped(String)
        case busy
    }

    @Published private(set) var progress: CoordinatorProgress?
    @Published private(set) var last: SortRecord?
    @Published var notice: String?
    @Published var enabled: Bool {
        didSet { defaults.set(enabled, forKey: Self.enabledKey) }
    }

    var running: Bool { progress?.running == true }

    private let ledger: LedgerService
    private let judgments: () -> [UserJudgment]
    private let items: () -> [SourceItem]
    private let model: JudgmentModelProvider
    let conditions: DeviceConditions
    private let defaults: UserDefaults
    private let lane: ModelLane
    private let settings: ModelSettingsSource
    private var bridge: CoordinatorBridge?
    /// Counts carried across a preempted run and its resumption, so the card shows the whole run.
    private var carried: (sorted: Int, needYou: Int)?
    private var resumeTrigger: SortTrigger?

    static let enabledKey = "sort.whileCharging"
    static let enabledByDefault = true
    static let lastKey = "sort.last"

    init(ledger: LedgerService, judgments: @escaping () -> [UserJudgment], items: @escaping () -> [SourceItem],
         model: JudgmentModelProvider, conditions: DeviceConditions, defaults: UserDefaults, lane: ModelLane,
         settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.settings = settings
        self.ledger = ledger
        self.judgments = judgments
        self.items = items
        self.model = model
        self.conditions = conditions
        self.defaults = defaults
        self.lane = lane
        // On unless the user turned it off (owner decision 2026-09-26: every on-device feature is on by default).
        // Still only while charging, and paused by heat, Low Power Mode and the other gates below.
        enabled = defaults.object(forKey: Self.enabledKey) as? Bool ?? Self.enabledByDefault
        if let data = defaults.data(forKey: Self.lastKey) { last = try? JSONDecoder().decode(SortRecord.self, from: data) }
        lane.onIdle { [weak self] in
            Task { @MainActor [weak self] in await self?.resumeIfPreempted() }
        }
    }

    /// Runs the coordinator once. Safe to call again after any stop: it resumes where it left off.
    @discardableResult
    func run(_ trigger: SortTrigger) async -> Outcome {
        guard !running else { return .busy }
        if let b = conditions.blocker { return skip(Self.words(b) + " Sorting waits.") }
        // Model settings for this run: with judgments' Laya off the rules sort without the model
        // (the watchers still use it when it is on for them and installed).
        let snapshot = settings.current
        let sweepsUseLaya = snapshot.useLaya(feature: Features.shared.JUDGMENTS)
        var backend: Backend?
        if sweepsUseLaya {
            guard model.isInstalled, let b = await model.backend() else {
                return skip("The model is not installed, so nothing was sorted.")
            }
            backend = b
        } else if snapshot.useLaya(feature: Features.shared.WATCHERS), model.isInstalled {
            backend = await model.backend()
        }
        let js = judgments()
        if js.isEmpty { return skip("No judgments yet. Add one in Judgments, then sort.") }
        let all = items()
        if all.isEmpty { return skip("No items scanned yet. Turn on the sample in Sources.") }

        ledger.flush()
        let rows = ledger.allRows()
        // Decision B needs the user's corrections: the baseline answers automatically where it wins.
        let corrections = ledger.correctionIndex()
        let conditions = self.conditions
        let ledger = self.ledger
        // The passive sort's live run (Now): one particle per judged item; the dock shows it when Now is not on screen.
        let thresholds = Dictionary(js.map { ($0.id, snapshot.policy(feature: Features.shared.JUDGMENTS).thresholdFor(judgment: $0)) }) { a, _ in a }
        let job = ActivityCenter.shared.start("sort", title: "act.title.sort", view: "now", stage: "act.stage.deciding",
                                              cancel: { [weak self] in self?.cancel() })
        let model: String? = sweepsUseLaya ? "multilingual" : nil
        let bridge = CoordinatorBridge(
            conditions: conditions,
            progress: { p in
                job.progress(Int(p.done), of: Int(p.total))
                Task { @MainActor [weak self] in self?.publish(p) }
            },
            rows: { rows in
                ledger.record(rows)
                JudgmentRunLog.stamp(Set(rows.map(\.judgmentId)))   // the results screen's "Last run"
                for (id, group) in Dictionary(grouping: rows, by: { $0.judgmentId }) {
                    job.rows(group, threshold: thresholds[id] ?? 0.7, model: model)
                }
            })
        self.bridge = bridge
        resumeTrigger = nil
        progress = CoordinatorProgress(done: 0, total: 0, alreadyDecided: 0, judgments: Int32(js.count), currentJudgmentId: nil,
                                       elapsedMillis: 0, medianMillis: nil, running: true, watching: false)
        let today = WatchersService.isoDay(Date())
        let coordinator = SweepCoordinator(backend: backend, lane: lane)
        // Queued without a claim: a sweep is the lowest priority and never makes anything wait.
        let result: CoordinatorResult = await withCheckedContinuation { c in
            ModelWork.queue.async {
                let r = coordinator.runWith(judgments: js, items: all, ledger: rows, todayIso: today, observer: bridge,
                                            corrections: corrections, settings: snapshot)
                ModelWork.runEnded()
                c.resume(returning: r)
            }
        }
        job.finish(result.error != nil ? "error" : result.stopped != nil ? "cancelled" : "done",
                   result.error != nil ? "act.res.failed" : result.stopped != nil ? "act.res.stopped" : "act.res.sort",
                   ["done": Int(result.summary.sorted)])
        settings.recordRun(Features.shared.JUDGMENTS, layaOff: !sweepsUseLaya)
        if result.watchers != nil { settings.recordRun(Features.shared.WATCHERS, layaOff: result.layaOff.contains(Features.shared.WATCHERS)) }
        ledger.flush()
        afterRun?()
        self.bridge = nil
        progress = result.progress

        let before = carried ?? (0, 0)
        let record = SortRecord(sorted: before.sorted + Int(result.summary.sorted),
                                needYou: before.needYou + Int(result.summary.needYou),
                                findings: Int(result.summary.findings), finished: result.finished,
                                stop: result.stopped.map { "\($0)" }, at: Date(), trigger: trigger)
        save(record)
        if let stop = result.stopped {
            // Carry the counts so a resumed run reports the whole thing.
            carried = stop == .preempted ? (record.sorted, record.needYou) : nil
            if stop == .preempted { resumeTrigger = trigger }
            notice = Self.words(stop) + " \(record.sorted) sorted so far; what ran is saved."
            if stop == .preempted, lane.isFree() { Task { await resumeIfPreempted() } }
            return .stopped(stop, record)
        }
        carried = nil
        notice = result.error.map { "Sorted, with a problem: \($0)" } ?? "Done: \(record.line)."
        return .finished(record)
    }

    func cancel() { bridge?.stop(.cancelled) }

    /// The BGTask's expiration handler: stop at the next item; rows so far are already saved.
    nonisolated func expire() { Task { @MainActor in self.bridge?.stop(.expired) } }

    private func resumeIfPreempted() async {
        guard let trigger = resumeTrigger, !running, lane.isFree() else { return }
        resumeTrigger = nil
        await run(trigger)
    }

    private func skip(_ why: String) -> Outcome {
        notice = why
        return .skipped(why)
    }

    private func publish(_ p: CoordinatorProgress) {
        guard running else { return }
        progress = p
    }

    private func save(_ r: SortRecord) {
        last = r
        if let data = try? JSONEncoder().encode(r) { defaults.set(data, forKey: Self.lastKey) }
    }

    nonisolated static func words(_ stop: StopReason) -> String {
        switch stop {
        case .cancelled: return "Cancelled."
        case .preempted: return "Paused while the model does something you asked for; it carries on after."
        case .expired: return "iOS ended the background time."
        case .thermal: return "The phone is hot, so sorting stopped."
        case .lowPower: return "Low Power Mode is on, so sorting stopped."
        default: return "Stopped."
        }
    }

    /// "12 of 40 · 3.1 items/s · median 82 ms".
    static func progressLine(_ p: CoordinatorProgress) -> String {
        if p.watching { return "Sweeps done (\(p.done) sorted). Running the watchers…" }
        var s = "\(p.done) of \(p.total)"
        if p.alreadyDecided > 0 { s += " · \(p.alreadyDecided) already sorted" }
        s += String(format: " · %.1f items/s", p.itemsPerSecond)
        if let m = p.medianMillis { s += String(format: " · median %.0f ms", m.doubleValue) }
        return s
    }
}

/// Kotlin's `CoordinatorObserver`, called on the model queue.
final class CoordinatorBridge: NSObject, CoordinatorObserver, @unchecked Sendable {
    private let lock = NSLock()
    private var requested: StopReason?
    private let conditions: DeviceConditions
    private let progress: (CoordinatorProgress) -> Void
    private let rows: ([LedgerRow]) -> Void

    init(conditions: DeviceConditions, progress: @escaping (CoordinatorProgress) -> Void, rows: @escaping ([LedgerRow]) -> Void) {
        self.conditions = conditions
        self.progress = progress
        self.rows = rows
    }

    func stop(_ reason: StopReason) { lock.lock(); if requested == nil { requested = reason }; lock.unlock() }

    func stopReason() -> StopReason? {
        lock.lock(); let r = requested; lock.unlock()
        return r ?? conditions.blocker
    }

    func onCoordinatorProgress(progress: CoordinatorProgress) { self.progress(progress) }
    func onCoordinatorRows(rows: [LedgerRow]) { self.rows(rows) }
}

extension SortService {
    static func defaultModel() -> JudgmentModelProvider {
        #if DEBUG
        if LaunchOptions.current.sortDemo { return SortDemoModel() }
        #endif
        return LayaModel.shared
    }
}

#if DEBUG
/// `-LoupeSortDemo` (with `-LoupeFixtures`, so the ledger is throwaway): the queue demo's stand-in
/// scorer, slowed a little so a UI test can see progress. Never used outside that flag.
@MainActor
final class SortDemoModel: JudgmentModelProvider {
    var isInstalled: Bool { true }
    var failure: String? { nil }
    func backend() async -> Backend? { SlowBackend() }

    final class SlowBackend: NSObject, Backend {
        private let inner = FixtureQueueBackend()
        func score(judgment: JudgmentChoice, state: TextState) -> Scored {
            Thread.sleep(forTimeInterval: 0.08)
            return inner.score(judgment: judgment, state: state)
        }
    }
}
#endif
