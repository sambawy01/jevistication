import Combine
import Foundation
import LoupeKit

/// What one stage did.
struct RunStageResult: Equatable {
    /// What the stage counted (items read, items checked, emails, items sorted).
    var count: Int
    /// Findings not in the previous saved results.
    var newFindings: Int = 0
    /// False when it stopped before its end (cancelled, expired, heat): the run does not go on.
    var completed: Bool = true
    /// Why it did nothing or stopped, in words.
    var note: String?
}

/// The stages' real work, behind a seam so the coordinator's order, checkpoints and cancellation are tested with fakes
/// (`LoupeTests/RunCoordinatorTests.swift`). `LiveRunWork` is the app's.
@MainActor
protocol RunWork: AnyObject {
    /// The sources the sources stage reads, in order: every source that is on and readable, or only [only].
    func sourceIds(only: String?) -> [String]
    /// A source's name for the panel ("Photos").
    func sourceTitle(_ id: String) -> String
    func scan(source: String, cancel: RunCancel, report: RunReporter) async -> RunStageResult
    func privacy(cancel: RunCancel, report: RunReporter) async -> RunStageResult
    func mail(cancel: RunCancel, report: RunReporter) async -> RunStageResult
    func watchers(cancel: RunCancel, report: RunReporter) async -> RunStageResult
    func sort(trigger: SortTrigger, cancel: RunCancel, report: RunReporter) async -> RunStageResult
    /// Stops work that does not poll the cancel flag (the sort's coordinator) at its next item.
    func stop(_ reason: RunOutcome)
    /// Why a background run must stop now (heat, Low Power Mode), or nil.
    var blocker: StopReason? { get }
}

/// The one observable coordinator of Loupe's runs (owner decisions 2026-09-28). Every run of the checks goes through
/// it: the first check after onboarding, Run now, Scan again, the re-check after the items changed, and the nightly
/// run while charging. Stages always run in order — sources, privacy check, mail triage, watchers, sort — each off the
/// main thread; `current` publishes the live progress (throttled, ~8 Hz) for any panel to draw; `cancel()` stops the
/// running stage at its next item and skips the rest, leaving every saved result and cache consistent (partial scans
/// keep what they read, a cancelled check keeps the previous results).
///
/// STABLE API for the shell (HomeScanPanel) and tracking-engine branches:
///   `RunCoordinator.shared`, `@Published current: RunProgress?`, `@Published last: RunRecord?`,
///   `@Published lastNightly: RunRecord?`, `isRunning`, `runNow(reason:)`, `run(_:) async -> RunRecord?`, `cancel()`,
///   `firstCheckAfterOnboarding()`, `firstCheckDone`.
@MainActor
final class RunCoordinator: ObservableObject {
    static let shared: RunCoordinator = {
        let c = RunCoordinator(work: LiveRunWork(), store: RunStateStore(home: LedgerService.defaultHome()))
        c.connect(SourcesService.shared)
        return c
    }()

    /// The run going now, with its live progress; nil when idle.
    @Published private(set) var current: RunProgress?
    /// The last run that did anything (saved; survives a relaunch).
    @Published private(set) var last: RunRecord?
    /// The last nightly run (saved).
    @Published private(set) var lastNightly: RunRecord?

    var isRunning: Bool { current != nil }
    /// Runs started since launch (diagnostics; the no-launch-work UI test reads it through a DEBUG label).
    @Published private(set) var runsStarted = 0
    /// Whether a run is going, as its own small object: it changes only when a run starts or ends, so a screen that
    /// only needs to know that (Now's mascot, Guard's Run now) does not redraw with every progress tick.
    let status = RunStatus()
    /// The first check after onboarding has run (or started).
    var firstCheckDone: Bool { store.load().firstCheckDone }

    let work: RunWork
    let store: RunStateStore
    private let clock: () -> Date
    private var token: RunCancel?
    /// A run asked for while one was going; it runs after it, in order.
    private struct Queued { let id: UUID; let reason: RunReason }
    /// Runs asked for while one was going, run in order after it (a run that another covers is dropped).
    private var queue: [Queued] = []
    /// Callers awaiting a run, by run id.
    private var waiters: [UUID: [CheckedContinuation<RunRecord?, Never>]] = [:]
    private var draining = false
    /// The latest report of the running stage, flushed to `current` at most every `publishInterval`.
    private var pendingReport: RunReport?
    private var flushScheduled = false
    private let publishInterval: TimeInterval
    private var lastPublish: TimeInterval = 0

    init(work: RunWork, store: RunStateStore, clock: @escaping () -> Date = Date.init, publishInterval: TimeInterval = 0.125) {
        self.work = work
        self.store = store
        self.clock = clock
        self.publishInterval = publishInterval
        let state = store.load()
        last = state.last
        lastNightly = state.lastNightly
    }

    /// Routes the sources' user-asked scans and item changes through runs (visible, cancellable, followed by the checks).
    func connect(_ sources: SourcesService) {
        sources.userScan = { [weak self] id in await self?.run(.scanAgain(id)) }
        sources.itemsChanged = { [weak self] in self?.runNow(reason: .itemsChanged) }
    }

    // MARK: Starting and stopping

    /// Starts a run now, or queues it after the one going (dropped when that one, or one already queued, covers it).
    func runNow(reason: RunReason) {
        Task { await run(reason) }
    }

    /// Runs [reason] and returns its record once it has run (nil when a queued run covered it and ran instead, or it
    /// was dropped). Awaiting callers of a queued run wait until it has run.
    @discardableResult
    func run(_ reason: RunReason) async -> RunRecord? {
        if let cur = current {
            // A full run still reading the sources (or before them) covers what is asked.
            if cur.reason.covers(reason), !cur.stagesPast(.sources), cur.reason != .nightly { return await wait(for: cur.id) }
            if let q = queue.first(where: { $0.reason.covers(reason) }) { return await wait(for: q.id) }
            let q = Queued(id: UUID(), reason: reason)
            // Queued runs this one covers go; their callers wait for this one instead.
            for old in queue where reason.covers(old.reason) {
                waiters[q.id, default: []] += waiters.removeValue(forKey: old.id) ?? []
            }
            queue.removeAll { reason.covers($0.reason) }
            queue.append(q)
            return await wait(for: q.id)
        }
        let record = await perform(reason, id: UUID(), resume: nil, trigger: .manual)
        await drainQueue()
        return record
    }

    private func wait(for id: UUID) async -> RunRecord? {
        await withCheckedContinuation { waiters[id, default: []].append($0) }
    }

    private func drainQueue() async {
        guard !draining else { return }
        draining = true
        while !queue.isEmpty {
            let q = queue.removeFirst()
            _ = await perform(q.reason, id: q.id, resume: nil, trigger: .manual)
        }
        draining = false
    }

    /// Stops the running stage at its next item; later stages do not run, nor do runs queued after it. What ran is
    /// saved.
    func cancel() {
        guard let t = token, current != nil else { return }
        t.cancel(.cancelled)
        work.stop(.cancelled)
        for q in queue { release(q.id, nil) }
        queue.removeAll()
        current?.cancelling = true
    }

    /// iOS ended the background time: stop at the next item, checkpointed. Safe from any thread.
    nonisolated func expire() {
        Task { @MainActor in
            self.token?.cancel(.expired)
            self.work.stop(.expired)
        }
    }

    /// The one full check after onboarding (owner decision C): runs once, ever, per install (and again only after
    /// "Delete all my Loupe data" wipes the state with the rest). Visible and cancellable like any run.
    func firstCheckAfterOnboarding() {
        guard !store.load().firstCheckDone else { return }
        store.update { $0.firstCheckDone = true }
        runNow(reason: .firstCheck)
    }

    /// After "Delete all my Loupe data": the state file went with the home; forget what is in memory.
    func forgetAfterErase() {
        cancel()
        last = nil
        lastNightly = nil
    }

    // MARK: The nightly run

    /// The nightly run: every stage, resuming [resume] (a checkpoint of a run iOS ended) from its first stage not
    /// done. Checkpointed after each source and each stage; a stop leaves the checkpoint for the next night.
    func runNightly(resume: RunCheckpoint?) async -> RunRecord {
        guard !isRunning else {
            return RunRecord(id: UUID(), reason: .nightly, startedAt: clock(), endedAt: clock(), stagesRun: [], counts: [:],
                             newFindings: RunFindings(), outcome: .skipped, note: "Another run was going.")
        }
        let record = await perform(.nightly, id: resume?.runId ?? UUID(), resume: resume, trigger: .background)
        await drainQueue()
        return record
    }

    // MARK: The run itself

    private func perform(_ reason: RunReason, id: UUID, resume: RunCheckpoint?, trigger: SortTrigger) async -> RunRecord {
        let cancel = RunCancel()
        token = cancel
        runsStarted += 1
        status.set(running: true, started: runsStarted)
        let started = resume?.startedAt ?? clock()
        let stages = reason.stages
        var done = resume?.stagesDone ?? []
        var sourcesDone = resume?.sourcesDone ?? []
        var counts = resume?.counts ?? [:]
        var findings = resume?.newFindings ?? RunFindings()
        var note: String?
        var outcome = RunOutcome.finished
        let nightly = reason == .nightly
        current = RunProgress(id: id, reason: reason, startedAt: started, stages: stages, stage: stages.first { !done.contains($0) } ?? stages[0],
                              part: nil, item: nil, counts: [:], rate: nil, eta: nil, cancelling: false)
        Log.run.info("run start: \(reason.title, privacy: .public) resume=\(resume != nil, privacy: .public)")

        func checkpoint() {
            guard nightly else { return }
            let cp = RunCheckpoint(runId: id, startedAt: started, stagesDone: done, sourcesDone: sourcesDone, counts: counts, newFindings: findings)
            store.update { $0.checkpoint = cp }
        }

        stageLoop: for stage in stages where !done.contains(stage) {
            if let why = cancel.reason { outcome = why; break }
            if nightly, let b = work.blocker {
                outcome = .stopped
                note = SortService.words(b) + " The rest runs next time."
                break
            }
            begin(stage)
            let reporter = reporter(for: stage)
            var result: RunStageResult
            switch stage {
            case .sources:
                let ids = work.sourceIds(only: reason.onlySource).filter { !sourcesDone.contains($0) }
                var read = counts[.sources] ?? 0
                var completed = true
                for (i, sid) in ids.enumerated() {
                    if cancel.isCancelled { completed = false; break }
                    if nightly, work.blocker != nil { completed = false; break }
                    current?.part = "\(work.sourceTitle(sid)) · \(i + 1) of \(ids.count)"
                    current?.item = nil
                    let r = await work.scan(source: sid, cancel: cancel, report: reporter)
                    read += r.count
                    if !r.completed || cancel.isCancelled { completed = false; break }
                    sourcesDone.append(sid)
                    counts[.sources] = read
                    checkpoint()
                }
                result = RunStageResult(count: read, completed: completed)
            case .privacy: result = await work.privacy(cancel: cancel, report: reporter)
            case .mail: result = await work.mail(cancel: cancel, report: reporter)
            case .watchers: result = await work.watchers(cancel: cancel, report: reporter)
            case .sort: result = await work.sort(trigger: trigger, cancel: cancel, report: reporter)
            }
            flush()
            counts[stage] = result.count
            if let n = result.note { note = n }
            guard result.completed, !cancel.isCancelled else {
                outcome = cancel.reason ?? .stopped
                break stageLoop
            }
            switch stage {
            case .privacy: findings.privacy += result.newFindings
            case .mail: findings.mail += result.newFindings
            case .watchers: findings.watchers += result.newFindings
            case .sources, .sort: break
            }
            done.append(stage)
            current?.counts[stage] = RunStageCount(done: result.count, total: result.count, finished: true)
            checkpoint()
        }

        let record = RunRecord(id: id, reason: reason, startedAt: started, endedAt: clock(),
                               stagesRun: stages.filter { done.contains($0) }, counts: counts, newFindings: findings,
                               outcome: outcome, note: note)
        store.update { s in
            s.last = record
            s.history = Array(([record] + s.history.filter { $0.id != record.id }).prefix(RunStateStore.historyLimit))
            if nightly {
                s.lastNightly = record
                s.checkpoint = outcome == .finished || outcome == .cancelled ? nil : s.checkpoint
            } else if outcome == .finished, reason.stages == RunStage.allCases {
                // A full run finished by hand did everything a stopped nightly run had left.
                s.checkpoint = nil
            }
        }
        last = record
        if nightly { lastNightly = record }
        token = nil
        current = nil
        pendingReport = nil
        status.set(running: false, started: runsStarted)
        Log.run.info("run end: \(reason.title, privacy: .public) outcome=\(outcome.rawValue, privacy: .public) new=\(findings.total, privacy: .public)")
        release(id, record)
        return record
    }

    private func release(_ id: UUID, _ record: RunRecord?) {
        (waiters.removeValue(forKey: id) ?? []).forEach { $0.resume(returning: record) }
    }

    // MARK: Progress

    private func begin(_ stage: RunStage) {
        pendingReport = nil
        current?.stage = stage
        current?.part = nil
        current?.item = nil
        current?.rate = nil
        current?.eta = nil
        current?.counts[stage] = RunStageCount(done: 0, total: nil, finished: false)
    }

    private func reporter(for stage: RunStage) -> RunReporter {
        RunReporter { [weak self] r in
            // Reports made on the main thread (the live scan's snapshots, the sort's progress) land at once; the
            // checks' queues hop over.
            if Thread.isMainThread {
                MainActor.assumeIsolated { self?.receive(r, stage: stage) }
            } else {
                Task { @MainActor [weak self] in self?.receive(r, stage: stage) }
            }
        }
    }

    private func receive(_ r: RunReport, stage: RunStage) {
        guard current?.stage == stage else { return }
        pendingReport = r
        let now = Date().timeIntervalSinceReferenceDate
        let wait = lastPublish + publishInterval - now
        if wait <= 0 { flush(); return }
        guard !flushScheduled else { return }
        flushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            MainActor.assumeIsolated {
                self?.flushScheduled = false
                self?.flush()
            }
        }
    }

    private func flush() {
        guard let r = pendingReport, var p = current else { return }
        pendingReport = nil
        lastPublish = Date().timeIntervalSinceReferenceDate
        p.counts[p.stage] = RunStageCount(done: r.done, total: r.total, finished: false)
        if let part = r.part, p.stage != .sources { p.part = part }
        if let item = r.item { p.item = item }
        p.rate = r.rate
        p.eta = r.eta
        if p != current { current = p }
    }
}

/// `RunCoordinator.status`: running or not, and how many runs started since launch. Published only at a run's start
/// and end.
@MainActor
final class RunStatus: ObservableObject {
    @Published private(set) var running = false
    @Published private(set) var runsStarted = 0

    func set(running: Bool, started: Int) {
        if self.running != running { self.running = running }
        if runsStarted != started { runsStarted = started }
    }
}

extension RunProgress {
    /// Whether this run has gone past [stage].
    func stagesPast(_ stage: RunStage) -> Bool { self.stage > stage }
}
