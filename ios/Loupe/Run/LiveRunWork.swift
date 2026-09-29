import Combine
import Foundation
import LoupeKit

/// The app's `RunWork`: each stage is the service that already does it, run off the main thread by that service
/// (the sources' scan queue, the rules queues, the model queue), with the run's cancel flag passed down to the loops
/// that can stop between items and its reporter fed from the live displays that already exist (the sources' live
/// scan stream at ~12 Hz, the sort's coordinator progress, the checks' per-item listeners).
@MainActor
final class LiveRunWork: RunWork {
    private var sources: SourcesService { .shared }
    private var privacy: PrivacyService { .shared }
    private var mailTriage: MailTriageService { .shared }
    private var watchersService: WatchersService { .shared }
    private var sorter: SortService { .shared }
    private let conditions: DeviceConditions

    init(conditions: DeviceConditions = SystemConditions()) {
        self.conditions = conditions
    }

    var blocker: StopReason? { conditions.blocker }

    /// Order: the fixture sample (tests), Files (with what was sent to Loupe), Photos, Calendar, Contacts, then Mail
    /// (online; last, so a slow server holds up nothing on the phone).
    static let sourceOrder: [PhoneSource] = [.files, .photos, .calendar, .contacts, .mail]

    func sourceIds(only: String?) -> [String] {
        if let only {
            if only == SourcesService.sampleId { return sources.hasFixtureSample && sources.sampleEnabled ? [only] : [] }
            // Asked for by the user: read it even without permission, so its card says what to do.
            guard let s = PhoneSource(rawValue: only), sources.isPhoneEnabled(s) else { return [] }
            return [only]
        }
        var ids: [String] = []
        if sources.hasFixtureSample && sources.sampleEnabled { ids.append(SourcesService.sampleId) }
        ids += Self.sourceOrder.filter { sources.canScan($0) }.map(\.rawValue)
        return ids
    }

    func sourceTitle(_ id: String) -> String {
        if id == SourcesService.sampleId { return "Test fixture" }
        return PhoneSource(rawValue: id)?.title ?? id
    }

    func scan(source id: String, overnight: Bool, cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        // The live scan the source starts is the stage's progress: its masked item names, counts, rate and ETA.
        var snapshotSub: AnyCancellable?
        var attached: UUID?
        let scansSub = sources.$liveScans.sink { scans in
            guard let live = scans[id], live.id != attached else { return }
            attached = live.id
            snapshotSub = live.$snapshot.sink { snap in
                report.report(RunReport(done: snap.done, total: snap.total, part: nil, item: snap.recent.first?.name,
                                        rate: snap.rate, eta: snap.eta))
            }
        }
        defer {
            scansSub.cancel()
            snapshotSub?.cancel()
        }
        if id == SourcesService.sampleId {
            await sources.scanSample(cancel: cancel)
            return RunStageResult(count: Int(sources.sampleScan?.itemCount ?? 0), completed: !cancel.isCancelled)
        }
        guard let s = PhoneSource(rawValue: id) else { return RunStageResult(count: 0) }
        await sources.scanPhone(s, cancel: cancel, overnight: overnight)
        return RunStageResult(count: sources.state(s).itemCount, completed: !cancel.isCancelled)
    }

    func privacy(cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        let had = privacy.summary != nil
        let before = Set(privacy.findings.map(\.key))
        await privacy.run(cancel: cancel, report: report)
        let after = Set(privacy.findings.map(\.key))
        return RunStageResult(count: Int(privacy.summary?.itemsChecked ?? 0),
                              newFindings: had ? after.subtracting(before).count : after.count,
                              completed: !cancel.isCancelled)
    }

    func mail(cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        let had = mailTriage.summary != nil
        let before = Set(mailTriage.rows.filter(\.phishing).map(\.itemId))
        await mailTriage.run(cancel: cancel, report: report)
        let after = Set(mailTriage.rows.filter(\.phishing).map(\.itemId))
        return RunStageResult(count: mailTriage.rows.count, newFindings: had ? after.subtracting(before).count : after.count,
                              completed: !cancel.isCancelled)
    }

    func watchers(cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        await watchersService.run(cancel: cancel, report: report)
        return RunStageResult(count: Int(watchersService.summary?.itemsChecked ?? 0), newFindings: watchersService.newCount,
                              completed: !cancel.isCancelled)
    }

    func sort(trigger: SortTrigger, cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        if cancel.isCancelled { return RunStageResult(count: 0, completed: false) }
        let sub = sorter.$progress.sink { p in
            guard let p, p.running else { return }
            let left = Int(p.total) - Int(p.done)
            let rate = p.itemsPerSecond
            report.report(RunReport(done: Int(p.done), total: Int(p.total), part: nil, item: nil,
                                    rate: rate > 0 ? rate : nil, eta: rate > 0 ? Double(max(0, left)) / rate : nil))
        }
        defer { sub.cancel() }
        switch await sorter.run(trigger) {
        case .finished(let r):
            return RunStageResult(count: r.sorted)
        case .stopped(let why, let r):
            // Paused for foreground model work: the sort carries on by itself when the model is free.
            if why == .preempted { return RunStageResult(count: r.sorted, note: SortService.words(why)) }
            return RunStageResult(count: r.sorted, completed: false, note: SortService.words(why))
        case .skipped(let why):
            return RunStageResult(count: 0, note: why)
        case .busy:
            return RunStageResult(count: 0, note: "A sort was already running.")
        }
    }

    func stop(_ reason: RunOutcome) {
        if reason == .expired { sorter.expire() } else { sorter.cancel() }
    }
}
