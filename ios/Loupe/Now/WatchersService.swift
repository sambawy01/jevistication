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

    private let ledger: LedgerService
    private let items: () -> [SourceItem]
    private let model: JudgmentModelProvider
    private let seen: UserDefaults
    private let settings: ModelSettingsSource
    private static let seenKey = "watchers.seenKeys"

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
        let backend: Backend? = policy.useLaya && model.isInstalled ? await model.backend() : nil
        let todayIso = Self.isoDay(today)
        let corrections = ledger.correctionIndex()
        // On the one model thread (the expiry radar may ask Laya); foreground, so a sort yields.
        let result: WatcherSummary = await ModelWork.run(.foreground) {
            let report = WatcherRun.shared.runIsoWith(items: all, todayIso: todayIso, backend: backend, policy: policy)
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
            summary = s.with(findings: s.findings.filter { $0.key != finding.key }, setAside: Int(s.setAside) + 1)
            lastSetAside = finding
            notice = verdict == .dismissed ? "Dismissed." : "Marked not relevant."
        }
    }

    /// Undoes the last Dismiss / Not relevant: appends a retraction and puts the finding back.
    func undoSetAside() {
        guard let f = lastSetAside, let s = summary else { return }
        ledger.recordCorrection(WatcherFindings.shared.retraction(finding: f, at: JudgmentsService.now()))
        let restored = (s.findings + [f.withVerdict(nil)]).sorted { $0.watcher.ordinal < $1.watcher.ordinal }
        summary = s.with(findings: restored, setAside: max(0, Int(s.setAside) - 1))
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
                       todayIso: todayIso, ruleName: ruleName)
    }
}
