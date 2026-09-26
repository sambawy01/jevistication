import Foundation
import LoupeKit

/// The watchers on Now (epic #7 child 5). The five watchers and their orchestration are LoupeKit's
/// shared `WatcherRun` (the desktop runs the same code); `WatcherFindings` turns a run into Now's
/// findings list and subscriptions census. This holds the latest run, runs it off the main thread,
/// writes the user's verdicts to the corrections log, and remembers which findings were already seen
/// so the mascot says "found" only for new ones.
@MainActor
final class WatchersService: ObservableObject {
    static let shared = WatchersService(ledger: LedgerService.shared,
                                        items: { SourcesService.shared.items() },
                                        model: LayaModel.shared,
                                        seen: UserDefaults.standard)

    @Published private(set) var summary: WatcherSummary?
    @Published private(set) var running = false
    /// Findings in the latest run that no earlier run showed: the mascot's "found".
    @Published private(set) var newCount = 0
    @Published var notice: String?
    @Published private(set) var lastSetAside: WatcherFinding?
    /// A subscription the user just answered "not a subscription" or set aside, for Undo (Guard).
    @Published private(set) var lastSetAsideRow: CensusRow?
    /// Keys of the findings the latest run raised for the first time: Now shows these first.
    @Published private(set) var newKeys: Set<String> = []
    /// When the latest run finished.
    @Published private(set) var lastRun: Date?
    /// The running run's per-watcher progress. Its own object, so a progress tick redraws only the strip
    /// that shows it, never the screens that read the findings.
    let progress = WatcherProgressFeed()

    private let ledger: LedgerService
    private let items: () -> [SourceItem]
    private let model: JudgmentModelProvider
    private let seen: UserDefaults
    private let settings: ModelSettingsSource
    private static let seenKey = "watchers.seenKeys"
    /// What the last set-aside took with it, for Undo: an expiry finding's timeline row, a merchant's "no charge" warning.
    private var removedExpiry: ExpiryRow?
    private var removedQuiet: [WatcherFinding] = []

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], model: JudgmentModelProvider, seen: UserDefaults,
         settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.settings = settings
        self.ledger = ledger
        self.items = items
        self.model = model
        self.seen = seen
    }

    var findings: [WatcherFinding] { summary?.findings ?? [] }
    var top: WatcherFinding? { findings.first }

    /// The one or two findings Now shows: the newest (raised first by the latest run) ahead of the rest, each
    /// group in the watchers' own urgency order.
    func nowFindings(limit: Int = 2) -> [WatcherFinding] {
        let fresh = findings.filter { newKeys.contains($0.key) }
        let rest = findings.filter { !newKeys.contains($0.key) }
        return Array((fresh + rest).prefix(limit))
    }

    /// Runs the five watchers over every item of every source that is on. The expiry radar's model
    /// half runs only when Laya is installed and opens; otherwise the mechanical half runs alone and
    /// the findings say so.
    func run(today: Date = Date()) async {
        guard !running else { return }
        running = true
        defer { running = false }
        let all = items()
        let job = ActivityCenter.shared.start("watchers", title: "act.title.watchers", view: "watchers", total: all.count,
                                              stage: "act.stage.starting")
        // Model settings: with the watchers' Laya off the mechanical half runs alone.
        let policy = settings.policy(Features.shared.WATCHERS)
        // DEBUG `-LoupeModelState missing` reads the model as missing whatever the files (the Guard's locked state).
        let forcedMissing = LaunchOptions.current.modelState == .missing
        let backend: Backend? = policy.useLaya && model.isInstalled && !forcedMissing ? await model.backend() : nil
        let todayIso = Self.isoDay(today)
        let corrections = ledger.correctionIndex()
        let feed = progress
        feed.begin()
        // On the one model thread (the expiry radar may ask Laya); foreground, so a sort yields. Each watcher reports
        // as it starts and ends, and the model half per document: the Guard's strip and the live run show it.
        let result: WatcherSummary = await ModelWork.run(.foreground) {
            let report = WatcherRun.shared.runIsoWithProgress(items: all, todayIso: todayIso, backend: backend, policy: policy) { watcher, done, total in
                let d = Int(truncating: done), t = Int(truncating: total)
                feed.report(watcher, done: d, total: t)
                let step = watcher.replacingOccurrences(of: "-", with: "_")
                if d == 0 { job.stage("act.stage.watcher", ["watcher": step]) }
                job.step(step, key: "act.stage.watcher", state: d >= t && t > 0 ? "done" : "running", done: d, total: t)
            }
            return WatcherFindings.shared.summarise(report: report, items: all,
                                                    sampleSourceIds: [SourcesService.sampleId],
                                                    corrections: corrections)
        }
        Self.report(job, result: result, items: all.count, laya: backend != nil)
        let keys = Set(result.findings.map(\.key))
        let before = Set(seen.stringArray(forKey: Self.seenKey) ?? [])
        newCount = keys.subtracting(before).count
        seen.set(Array(before.union(keys)).sorted(), forKey: Self.seenKey)
        settings.recordRun(Features.shared.WATCHERS, layaOff: !policy.useLaya)
        newKeys = keys.subtracting(before)
        lastRun = Date()
        feed.end()
        summary = result
    }

    /// Confirm, Dismiss or Not relevant: a correction record in the log (never rewritten). Dismissed
    /// and not-relevant findings leave the list; a confirmed one stays, marked.
    func answer(_ finding: WatcherFinding, _ verdict: FindingVerdict) {
        ledger.recordCorrection(WatcherFindings.shared.correction(finding: finding, verdict: verdict, at: JudgmentsService.now()))
        guard let s = summary else { return }
        if verdict == .confirmed {
            let marked = finding.withVerdict(.confirmed)
            summary = s.with(findings: s.findings.map { $0.key == finding.key ? marked : $0 }, setAside: Int(s.setAside))
            notice = "Confirmed. Kept on Now."
            lastSetAside = nil
        } else {
            removedExpiry = s.expiries.first { $0.findingKey == finding.key }
            summary = s.with(findings: s.findings.filter { $0.key != finding.key }, setAside: Int(s.setAside) + 1)
                .with(expiries: s.expiries.filter { $0.findingKey != finding.key })
            lastSetAside = finding
            lastSetAsideRow = nil
            notice = verdict == .dismissed ? "Dismissed." : "Marked not relevant."
        }
    }

    /// Confirm, Not a subscription (not relevant) or Set aside (dismissed) on a census merchant (Guard): a correction
    /// under its own key. A merchant set aside leaves the census and the monthly total, and takes its "no charge for
    /// N days" warning with it (as the next run would).
    func answer(_ row: CensusRow, _ verdict: FindingVerdict) {
        ledger.recordCorrection(WatcherFindings.shared.correction(finding: WatcherFindings.shared.censusFinding(row: row), verdict: verdict,
                                                                   at: JudgmentsService.now()))
        guard let s = summary else { return }
        if verdict == .confirmed {
            summary = s.with(census: GuardModel.census(s.census, replacing: row.merchant, with: row.withVerdict(.confirmed)))
            notice = "Confirmed as a subscription."
            lastSetAsideRow = nil
        } else {
            removedQuiet = s.findings.filter { $0.watcher == .recurring && $0.itemName == row.merchant }
            let findings = s.findings.filter { !($0.watcher == .recurring && $0.itemName == row.merchant) }
            summary = s.with(census: GuardModel.census(s.census, replacing: row.merchant, with: nil), findings: findings)
            lastSetAsideRow = row
            lastSetAside = nil
            notice = verdict == .notRelevant ? "Marked not a subscription. Left out of the total." : "Set aside. Left out of the total."
        }
    }

    /// Undoes the last Dismiss / Not relevant: appends a retraction and puts the finding back.
    func undoSetAside() {
        if let row = lastSetAsideRow, let s = summary {
            ledger.recordCorrection(WatcherFindings.shared.retraction(finding: WatcherFindings.shared.censusFinding(row: row), at: JudgmentsService.now()))
            let findings = (s.findings + removedQuiet).sorted { $0.watcher.ordinal < $1.watcher.ordinal }
            summary = s.with(census: GuardModel.census(s.census, restoring: row.withVerdict(nil)), findings: findings)
            removedQuiet = []
            lastSetAsideRow = nil
            notice = "Put back."
            return
        }
        guard let f = lastSetAside, let s = summary else { return }
        ledger.recordCorrection(WatcherFindings.shared.retraction(finding: f, at: JudgmentsService.now()))
        let restored = (s.findings + [f.withVerdict(nil)]).sorted { $0.watcher.ordinal < $1.watcher.ordinal }
        var back = s.with(findings: restored, setAside: max(0, Int(s.setAside) - 1))
        if let row = removedExpiry, row.findingKey == f.key {
            back = back.with(expiries: (back.expiries + [row]).sorted { $0.daysRemaining < $1.daysRemaining })
        }
        summary = back
        removedExpiry = nil
        lastSetAside = nil
        notice = "Put back."
    }

    func item(_ id: String) -> SourceItem? { items().first { $0.id == id } }

    /// The run's live view: one step per watcher (its id), items read, each finding flagged for you. Ids and
    /// numbers only.
    static func report(_ job: LiveJob, result: WatcherSummary, items: Int, laya: Bool) {
        let byWatcher = Dictionary(grouping: result.findings, by: { "\($0.watcher)".lowercased() })
        for (id, fs) in byWatcher.sorted(by: { $0.key < $1.key }) {
            job.stage("act.stage.watcher", ["watcher": id])
            job.step(id, key: "act.stage.watcher", state: "done", done: fs.count, total: fs.count)
        }
        let checked = Int(result.itemsChecked)
        job.count("read", checked)
        job.meta(["reads_budget": 40, "reads_used": laya ? min(40, checked) : 0])
        job.gate("flagged", result.findings.count)
        job.count("to_you", result.findings.count)
        job.gate("accepted", max(0, checked - result.findings.count))
        job.gate("skipped", max(0, items - checked))
        job.progress(items, of: items)
        job.finish("done", "act.res.watchers", ["new": result.findings.count, "found": result.findings.count])
    }

    nonisolated static func isoDay(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    /// "£9.99"-style money from minor units, currency-less (the census mixes none today).
    nonisolated static func money(_ minor: Int64) -> String { WatcherFindings.shared.money(minor: minor) }
}

extension WatcherFinding {
    func withVerdict(_ verdict: FindingVerdict?) -> WatcherFinding {
        WatcherFinding(key: key, watcher: watcher, title: title, evidence: evidence, why: why, itemId: itemId,
                       itemName: itemName, otherItemId: otherItemId, sample: sample, verdict: verdict)
    }
}

extension WatcherSummary {
    func with(findings: [WatcherFinding], setAside: Int) -> WatcherSummary {
        WatcherSummary(findings: findings, setAside: Int32(setAside), census: census, modelRan: modelRan,
                       itemsChecked: itemsChecked, emailsChecked: emailsChecked, linksChecked: linksChecked,
                       todayIso: todayIso, ruleName: ruleName, expiries: expiries)
    }

    func with(census: SubscriptionCensus, findings: [WatcherFinding]? = nil) -> WatcherSummary {
        WatcherSummary(findings: findings ?? self.findings, setAside: setAside, census: census, modelRan: modelRan,
                       itemsChecked: itemsChecked, emailsChecked: emailsChecked, linksChecked: linksChecked,
                       todayIso: todayIso, ruleName: ruleName, expiries: expiries)
    }

    func with(expiries: [ExpiryRow]) -> WatcherSummary {
        WatcherSummary(findings: findings, setAside: setAside, census: census, modelRan: modelRan,
                       itemsChecked: itemsChecked, emailsChecked: emailsChecked, linksChecked: linksChecked,
                       todayIso: todayIso, ruleName: ruleName, expiries: expiries)
    }
}

extension CensusRow {
    func withVerdict(_ verdict: FindingVerdict?) -> CensusRow {
        CensusRow(merchant: merchant, cadence: cadence, occurrences: occurrences, typicalMinor: typicalMinor,
                  lastChargedIso: lastChargedIso, daysSinceLastCharge: daysSinceLastCharge, monthlyMinor: monthlyMinor,
                  sample: sample, itemIds: itemIds, nextExpectedIso: nextExpectedIso, verdict: verdict)
    }
}

/// The running watcher run, watcher by watcher (`WatcherRun`'s real progress): which of the five is running, and how
/// far the expiry radar's model half is through its documents. Reports arrive on the model thread and hop to main.
@MainActor
final class WatcherProgressFeed: ObservableObject {
    struct State: Equatable {
        /// Watcher ids (`WatcherKind.id`) finished so far.
        var done: Set<String> = []
        /// The watcher running now and its units (documents for the model half, else 0 of 1).
        var current: String?
        var unitDone = 0
        var unitTotal = 1
    }

    @Published private(set) var state: State?

    func begin() { state = State() }
    func end() { state = nil }

    nonisolated func report(_ watcher: String, done: Int, total: Int) {
        DispatchQueue.main.async { MainActor.assumeIsolated { self.apply(watcher, done: done, total: total) } }
    }

    func apply(_ watcher: String, done: Int, total: Int) {
        guard var s = state else { return }
        if let c = s.current, c != watcher { s.done.insert(c) }
        if total > 0 && done >= total { s.done.insert(watcher) }
        s.current = watcher
        s.unitDone = done
        s.unitTotal = max(total, 1)
        if s != state { state = s }
    }
}
