import Foundation
import LoupeKit

/// Privacy check (epic #7 child 10): LoupeKit's shared `PrivacyCheck` (Loupe Station's personal-data,
/// secret and duplicate rules) over every enabled source. Mechanical — no model — so it runs on its own
/// rules queue (`RulesWork.privacy`), never behind a sort on the model queue.
/// Mark safe is an appended correction; delete / move act only on files the app can reach, with Undo.
/// Nothing leaves the phone, and no finding, preview or path is ever logged.
@MainActor
final class PrivacyService: ObservableObject {
    static let shared: PrivacyService = {
        let home = LedgerService.defaultHome()
        let service = PrivacyService(
            ledger: LedgerService.shared, items: { SourcesService.shared.items() },
            locator: LivePrivacyLocator(bookmarks: SourcesService.shared.deps.bookmarks, inbox: SourcesService.shared.deps.inbox),
            files: PrivacyFileActions(home: home), photos: PhotoKitDeleter(),
            rescan: { await SourcesService.shared.scanPhone(.files) }, results: ResultsStore.shared,
            reader: { SourcesService.shared.itemsReader() })
        service.files.commit()   // anything held from a previous session is deleted for good
        return service
    }()

    @Published private(set) var summary: PrivacySummary?
    @Published private(set) var running = false
    @Published var notice: String?
    @Published private(set) var lastSafe: PrivacyFinding?
    @Published private(set) var pendingUndo: PrivacyUndo?
    /// The saved results have been read (or there were none).
    @Published private(set) var loaded = false
    /// When the run behind `summary` finished (saved with it).
    @Published private(set) var lastRun: Date?

    private let ledger: LedgerService
    private let items: () -> [SourceItem]
    let locator: PrivacyLocating
    let files: PrivacyFileActions
    private let photos: PhotoDeleting
    private let rescan: () async -> Void
    private let settings: ModelSettingsSource
    private let results: ResultsStore?
    private let reader: (() -> ItemsReader)?

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], locator: PrivacyLocating,
         files: PrivacyFileActions, photos: PhotoDeleting, rescan: @escaping () async -> Void = {},
         settings: ModelSettingsSource = ModelSettingsService.shared, results: ResultsStore? = nil,
         reader: (() -> ItemsReader)? = nil) {
        self.reader = reader
        self.settings = settings
        self.ledger = ledger
        self.items = items
        self.locator = locator
        self.files = files
        self.photos = photos
        self.rescan = rescan
        self.results = results
        loadSaved()
    }

    /// Reads the latest saved results off the main thread (2026-09-28: launch loads, it never re-runs).
    private func loadSaved() {
        guard let results else { loaded = true; return }
        Task.detached(priority: .userInitiated) { [weak self] in
            let saved = results.loadPrivacy().map { ResultsStore.Box($0) }
            await MainActor.run {
                guard let self else { return }
                if self.summary == nil, let (summary, meta) = saved?.value {
                    self.summary = summary
                    self.lastRun = meta?.ranAt ?? meta?.savedAt
                }
                self.loaded = true
            }
        }
    }

    private func persist() {
        guard let results, let summary else { return }
        results.savePrivacy(summary, meta: .init(savedAt: Date(), ranAt: lastRun))
    }

    var findings: [PrivacyFinding] { summary?.findings ?? [] }

    /// A run asked for while one is going: it runs again after, so new items are never missed.
    private var rerun = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Runs the check over every source that is on. A call made mid-run waits for the follow-up run it asked for.
    /// [cancel] stops it between items (LoupeKit asks before each one); a cancelled check keeps the previous results
    /// (a partial check is never shown or saved). [report] hears each item: counts only, never a name.
    func run(cancel: RunCancel? = nil, report: RunReporter? = nil) async {
        guard !running else {
            rerun = true
            await withCheckedContinuation { waiters.append($0) }
            return
        }
        running = true
        repeat {
            rerun = false
            if cancel?.isCancelled == true { break }
            await runOnce(cancel: cancel, report: report)
        } while rerun
        running = false
        let released = waiters
        waiters = []
        released.forEach { $0.resume() }
    }

    private func runOnce(cancel: RunCancel?, report: RunReporter?) async {
        let all = await ItemsReader.load(items, reader)
        let corrections = ledger.correctionIndex()
        // Model settings (`features.scan`): read_content off checks names, folders and duplicates only.
        let readContent = settings.current.readContent
        // The live run view (Folder Scan's loop): one particle per item checked, counts only.
        let job = ActivityCenter.shared.start("scan", title: "act.title.privacy", view: "scan", total: all.count, stage: "act.stage.deciding")
        job.meta(["files_total": all.count, "files_seen": 0, "laya_files": 0, "rule_only": 0, "ocr_files": 0])
        let listener = PrivacyJobListener(job: job, readContent: readContent, pace: ActivityCenter.slowPace, cancel: cancel, report: report)
        // No sample badges anywhere (2026-09-28): nothing is marked as sample data.
        let result = await RulesWork.run(on: RulesWork.privacy) {
            PrivacyCheck.shared.summariseWatching(items: all, sampleSourceIds: [], corrections: corrections,
                                                  readContent: readContent, listener: listener)
        }
        if cancel?.isCancelled == true {
            job.finish("cancelled", "act.res.stopped", [:])
            return
        }
        summary = result
        lastRun = Date()
        persist()
        job.finish("done", "act.res.scan", ["files": Int(result.itemsChecked), "flagged": result.findings.count])
        // The check is rules on iPhone either way; use_laya off says so on screen, as Station's Folder Scan.
        settings.recordRun(Features.shared.SCAN, layaOff: !settings.useLaya(Features.shared.SCAN))
    }

    func access(_ f: PrivacyFinding) -> PrivacyAccess { locator.access(for: f.itemId) }

    /// For a duplicate, the copy the station suggests removing; otherwise the item itself.
    func target(_ f: PrivacyFinding) -> String {
        f.duplicates?.members.first { !$0.keep }?.itemId ?? f.itemId
    }

    func markSafe(_ f: PrivacyFinding) {
        ledger.recordCorrection(PrivacyCheck.shared.markSafe(finding: f, at: JudgmentsService.now()))
        remove(f)
        if let s = summary {
            summary = PrivacySummary(findings: s.findings, markedSafe: s.markedSafe + 1, itemsChecked: s.itemsChecked)
        }
        lastSafe = f
        pendingUndo = nil
        notice = "Marked safe. Loupe will not raise it again."
        persist()
    }

    func undoSafe() {
        guard let f = lastSafe, let s = summary else { return }
        ledger.recordCorrection(PrivacyCheck.shared.retraction(finding: f, at: JudgmentsService.now()))
        summary = PrivacySummary(findings: (s.findings + [f]).sorted { a, b in
            a.severity != b.severity ? a.severity > b.severity : a.group.ordinal < b.group.ordinal
        }, markedSafe: max(0, s.markedSafe - 1), itemsChecked: s.itemsChecked)
        lastSafe = nil
        notice = "Put back."
        persist()
    }

    func delete(_ f: PrivacyFinding) async {
        commitPending()
        switch locator.access(for: target(f)) {
        case .file(let url, let scope):
            do {
                pendingUndo = try files.delete(url, scope: scope, key: f.key)
                remove(f)
                notice = "Deleted \(url.lastPathComponent)."
                await rescan()
            } catch { notice = "Could not delete: \(error.localizedDescription)" }
        case .photo(let id):
            do {
                try await photos.delete(localId: id)
                remove(f)
                notice = "Moved to Recently Deleted in Photos."
            } catch { notice = "The photo was not deleted." }
        case .suggestOnly(let why):
            notice = why
        }
    }

    func move(_ f: PrivacyFinding, to folder: URL) async {
        commitPending()
        guard case .file(let url, let scope) = locator.access(for: target(f)) else { return }
        do {
            pendingUndo = try files.move(url, scope: scope, to: folder, key: f.key)
            remove(f)
            notice = "Moved \(url.lastPathComponent) to \(folder.lastPathComponent)."
            await rescan()
        } catch { notice = "Could not move: \(error.localizedDescription)" }
    }

    func undoFile() async {
        guard let u = pendingUndo else { return }
        var scope: URL?
        if case .file(_, let s) = locator.access(for: itemIdFor(u)) { scope = s }
        do {
            try files.undo(u, originalScope: scope)
            notice = "Put back."
        } catch { notice = "Could not undo: \(error.localizedDescription)" }
        pendingUndo = nil
        await rescan()
        await run()
    }

    /// The Undo offer went away: held deletions become final.
    func commitPending() {
        if pendingUndo?.kind == .deleted { files.commit() }
        pendingUndo = nil
    }

    func item(_ id: String) -> SourceItem? { items().first { $0.id == id } }

    /// After an approved Review action changed a file: re-read the files, then re-check.
    func rescanAfterReview() async {
        await rescan()
        await run()
    }

    private func itemIdFor(_ u: PrivacyUndo) -> String {
        guard let f = lastRemoved[u.findingKey] else { return "" }
        return target(f)
    }

    private var lastRemoved: [String: PrivacyFinding] = [:]

    private func remove(_ f: PrivacyFinding) {
        lastRemoved[f.key] = f
        guard let s = summary else { return }
        summary = PrivacySummary(findings: s.findings.filter { $0.key != f.key }, markedSafe: s.markedSafe, itemsChecked: s.itemsChecked)
        persist()
    }

    /// After "Delete all my Loupe data": the results go (their file went with the home).
    func forgetAfterErase() {
        summary = nil
        lastRun = nil
        notice = nil
        lastSafe = nil
        pendingUndo = nil
        if running { rerun = true }
    }
}

/// Reports each checked item of a privacy check to its live run: counts only (LoupeKit's `ActivityReport`).
/// Called on the rules queue; `pace` (DEBUG `-LoupeSlowJobs`) spaces items so a UI test can watch them move.
final class PrivacyJobListener: NSObject, PrivacyItemListener, @unchecked Sendable {
    let job: LiveJob
    let readContent: Bool
    let pace: TimeInterval
    let cancel: RunCancel?
    let report: RunReporter?

    init(job: LiveJob, readContent: Bool, pace: TimeInterval, cancel: RunCancel? = nil, report: RunReporter? = nil) {
        self.job = job
        self.readContent = readContent
        self.pace = pace
        self.cancel = cancel
        self.report = report
    }

    func onItem(done: Int32, total: Int32, findings: Int32, skipped: Bool) {
        job.scanned(findings: Int(findings), skipped: skipped, readContent: readContent)
        job.progress(Int(done), of: Int(total))
        report?.report(done: Int(done), total: Int(total))
        if pace > 0 { Thread.sleep(forTimeInterval: pace) }
    }

    func onDuplicates() { job.stage("act.stage.hashing") }

    /// The run's Cancel: LoupeKit asks before each item.
    func isCancelled() -> Bool { cancel?.isCancelled ?? false }
}
