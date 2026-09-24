import Foundation
import SwiftUI
import LoupeKit

/// The phone's activity registry (LoupeKit's `ActivityRegistry`, Loupe Station's job model) on the main actor,
/// published for the live run views and the Activity dock. Jobs report through `LiveJob`, which may be called
/// from any thread: it hops to the main actor before touching the registry.
///
/// Redraws are coalesced to at most 4 a second (Station's stream rate); while a job runs a 1 s tick keeps its
/// elapsed time and rate current. Nothing ticks when nothing runs.
@MainActor
final class ActivityCenter: ObservableObject {
    static let shared = ActivityCenter()

    let registry: ActivityRegistry
    @Published private(set) var snapshot: ActivitySnapshot
    /// The views on screen now (a live run section registers its view while it is shown).
    @Published private(set) var visibleViews: [String: Int] = [:]

    private var scheduled = false
    private var tick: Timer?
    private let settings: () -> CostReference

    /// DEBUG `-LoupeSlowJobs`: jobs pace their items (150 ms each) so a UI test can watch counters move.
    nonisolated static let slowPace: TimeInterval = {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-LoupeSlowJobs") ? 0.15 : 0
        #else
        return 0
        #endif
    }()

    init(clock: ActivityClock = SystemActivityClock(), reference: @escaping () -> CostReference = { ModelSettingsService.sharedStore.current.costReference }) {
        registry = ActivityRegistry(clock: clock, history: Int32(ActivityNames.shared.HISTORY))
        settings = reference
        registry.reference = reference()
        snapshot = registry.snapshot()
        registry.addListener(l: Listener { [weak self] in
            // Called on the main actor (the registry is confined there).
            MainActor.assumeIsolated { self?.changed() }
        })
    }

    private final class Listener: ActivityListener {
        let fire: () -> Void
        init(_ fire: @escaping () -> Void) { self.fire = fire }
        func changed(version: Int64) { fire() }
    }

    private func changed() {
        guard !scheduled else { return }
        scheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    /// Takes a fresh snapshot now (tests call it to skip the coalescing delay).
    func refresh() {
        scheduled = false
        registry.reference = settings()
        snapshot = registry.snapshot()
        if snapshot.running.isEmpty {
            tick?.invalidate(); tick = nil
        } else if tick == nil {
            tick = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.refresh() }
            }
        }
    }

    // MARK: Jobs

    /// Starts a job. `kind` must be one of `ActivityNames.KINDS`; `title` is a catalogue key.
    @discardableResult
    func start(_ kind: String, title: String, params: [String: Any] = [:], view: String?, ref: String? = nil,
               total: Int? = nil, stage: String? = nil, expected: Double? = nil, cancel: (() -> Void)? = nil) -> LiveJob {
        let job = registry.start(kind: kind, title: title, params: ActParams.bridge(params), view: view, ref: ref,
                                 total: total.map { KotlinDouble(value: Double($0)) }, stage: stage,
                                 cancel: cancel.map { CancelBox($0) }, expectedS: expected.map { KotlinDouble(value: $0) })
        return LiveJob(job)
    }

    func cancel(_ id: String) { _ = registry.cancel(id: id) }

    private final class CancelBox: ActivityCancel {
        let fn: () -> Void
        init(_ fn: @escaping () -> Void) { self.fn = fn }
        func cancel() { fn() }
    }

    // MARK: Screens

    func show(_ view: String) { visibleViews[view, default: 0] += 1 }
    func hide(_ view: String) {
        let n = (visibleViews[view] ?? 1) - 1
        if n <= 0 { visibleViews[view] = nil } else { visibleViews[view] = n }
    }

    /// Running jobs whose screen is not on screen (the dock).
    var offScreen: [JobSnapshot] { snapshot.running.filter { visibleViews[$0.view ?? ""] == nil } }

    /// The model load running now, for the banner.
    var modelLoad: JobSnapshot? { snapshot.running.first { $0.kind == "model_load" && $0.stage?.key == "act.stage.loadingModel" } }

    /// The newest job a screen owns (running, else the last finished).
    func latest(_ view: String) -> JobSnapshot? { snapshot.latest(view: view) }
}

/// Unix and monotonic seconds for the registry.
final class SystemActivityClock: ActivityClock {
    func unix() -> Double { Date().timeIntervalSince1970 }
    func mono() -> Double { ProcessInfo.processInfo.systemUptime }
}

/// Swift values to the Kotlin map a job keeps: numbers as KotlinDouble, booleans as KotlinBoolean, strings as
/// they are (the registry still drops anything that is not a short identifier).
enum ActParams {
    static func bridge(_ d: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [:]
        for (k, v) in d {
            switch v {
            case let b as Bool: out[k] = KotlinBoolean(value: b)
            case let i as Int: out[k] = KotlinDouble(value: Double(i))
            case let i as Int32: out[k] = KotlinDouble(value: Double(i))
            case let i as Int64: out[k] = KotlinDouble(value: Double(i))
            case let x as Double: out[k] = KotlinDouble(value: x)
            case let s as String: out[k] = s
            default: break
            }
        }
        return out
    }
}

/// A handle on one running job, safe from any thread: every call hops to the main actor. A job always ends with
/// `finish`; a handle whose job was refused (unknown kind) ignores every call.
final class LiveJob: @unchecked Sendable {
    private let job: ActivityJob?
    init(_ job: ActivityJob?) { self.job = job }

    var id: String? { job?.id }

    private func on(_ f: @escaping @MainActor (ActivityJob) -> Void) {
        guard let job else { return }
        if Thread.isMainThread {
            MainActor.assumeIsolated { f(job) }
        } else {
            DispatchQueue.main.async { MainActor.assumeIsolated { f(job) } }
        }
    }

    func stage(_ key: String, _ params: [String: Any] = [:]) {
        on { $0.stage(key: key, params: ActParams.bridge(params)) }
    }

    func progress(_ done: Int?, of total: Int? = nil) {
        on { $0.progress(done: done.map { KotlinDouble(value: Double($0)) }, total: total.map { KotlinDouble(value: Double($0)) }) }
    }

    func progressFraction(_ done: Double, of total: Double) {
        on { $0.progress(done: KotlinDouble(value: done), total: KotlinDouble(value: total)) }
    }

    func indeterminate() { on { _ = $0.indeterminate() } }
    func count(_ name: String, _ n: Int = 1) { on { $0.count(name: name, n: Int32(n)) } }
    func gate(_ name: String, _ n: Int = 1) { on { $0.gate(name: name, n: Int32(n)) } }
    func model(_ m: String) { on { $0.useModel(m: m) } }
    func meta(_ values: [String: Any]) { on { $0.setMeta(values: ActParams.bridge(values)) } }

    func decision(_ q: String, _ a: String?, _ c: Double?, src: String, model: String? = nil, tokens: Int = 0) {
        on { $0.decision(question: q, answer: a, confidence: c.map { KotlinDouble(value: $0) }, src: src, model: model, tokens: Int32(tokens)) }
    }

    func step(_ id: String, key: String?, state: String?, done: Int? = nil, total: Int? = nil) {
        on { $0.stepState(id: id, key: key, state: state, done: done.map { KotlinDouble(value: Double($0)) }, total: total.map { KotlinDouble(value: Double($0)) }) }
    }

    /// Ledger rows of one judgment (a sweep, the passive sort).
    func rows(_ rows: [LedgerRow], threshold: Double, model: String? = "multilingual") {
        on { ActivityReport.shared.recordRows(job: $0, rows: rows, threshold: threshold, model: model) }
    }

    func scanned(findings: Int, skipped: Bool, readContent: Bool) {
        on { ActivityReport.shared.recordScanned(job: $0, findings: Int32(findings), skipped: skipped, readContent: readContent) }
    }

    func email(category: String?, phishing: Bool, unsure: Bool, needsReply: Bool?) {
        on { ActivityReport.shared.recordEmail(job: $0, category: category, phishing: phishing, unsure: unsure, needsReply: needsReply.map { KotlinBoolean(value: $0) }) }
    }

    /// `done`, `error` or `cancelled`, with a result catalogue key and its numbers.
    func finish(_ status: String = "done", _ result: String? = nil, _ params: [String: Any] = [:]) {
        on { $0.finish(status: status, resultKey: result, params: ActParams.bridge(params)) }
    }

    /// Whether Cancel was asked (read on the main actor; workers keep their own flag).
    @MainActor var cancelRequested: Bool { job?.cancelRequested ?? false }
}
