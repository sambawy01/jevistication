import Foundation
import LoupeKit

/// Mail triage (epic #7 children 11 and 12): LoupeKit's shared `MailTriage` — Loupe Station's
/// classifier rules and the one phishing / site formula shared with Station (docs/PHISHING-FORMULA.md),
/// with the opt-in online checks when they are on — over every mail item of the enabled sources
/// (the sample's inbox now; IMAP mail when that source is on), plus site checks on web-link items.
/// Mechanical, no model; it runs on its own rules queue (`RulesWork.mail`), never behind a sort on
/// the model queue.
/// Mark safe / Confirm phishing are appended ledger corrections, with Undo. Nothing leaves the phone.
@MainActor
final class MailTriageService: ObservableObject {
    static let shared = MailTriageService(ledger: LedgerService.shared, items: { SourcesService.shared.items() }, online: OnlineChecksService.shared,
                                          results: ResultsStore.shared, reader: { SourcesService.shared.itemsReader() })

    @Published private(set) var summary: MailSummary?
    @Published private(set) var running = false
    @Published var notice: String?
    @Published private(set) var lastVerdict: MailRow?

    /// The online checks' status line for the last run (nil when they are off).
    @Published private(set) var onlineStatus: String?
    /// The saved results have been read (or there were none).
    @Published private(set) var loaded = false
    /// When the run behind `summary` finished (saved with it).
    @Published private(set) var lastRun: Date?

    private let ledger: LedgerService
    private let items: () -> [SourceItem]
    private let online: OnlineChecksService
    private let settings: ModelSettingsSource
    private let results: ResultsStore?
    private let reader: (() -> ItemsReader)?

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], online: OnlineChecksService = .shared,
         settings: ModelSettingsSource = ModelSettingsService.shared, results: ResultsStore? = nil,
         reader: (() -> ItemsReader)? = nil) {
        self.reader = reader
        self.settings = settings
        self.ledger = ledger
        self.items = items
        self.online = online
        self.results = results
        loadSaved()
    }

    /// Reads the latest saved results off the main thread (2026-09-28: launch loads, it never re-runs).
    private func loadSaved() {
        guard let results else { loaded = true; return }
        Task.detached(priority: .userInitiated) { [weak self] in
            let saved = results.loadMail().map { ResultsStore.Box($0) }
            await MainActor.run {
                guard let self else { return }
                if self.summary == nil, let (summary, meta) = saved?.value {
                    self.summary = summary
                    self.onlineStatus = meta?.onlineStatus
                    self.lastRun = meta?.ranAt ?? meta?.savedAt
                }
                self.loaded = true
            }
        }
    }

    private func persist() {
        guard let results, let summary else { return }
        results.saveMail(summary, meta: .init(savedAt: Date(), onlineStatus: onlineStatus, ranAt: lastRun))
    }

    /// After "Delete all my Loupe data": the results go (their file went with the home).
    func forgetAfterErase() {
        summary = nil
        onlineStatus = nil
        lastRun = nil
        notice = nil
        lastVerdict = nil
        if running { rerun = true }
    }

    var rows: [MailRow] { summary?.rows ?? [] }

    /// A run asked for while one is going: it runs again after, so new items are never missed.
    private var rerun = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Triages every email of the sources that are on. A call made mid-run waits for the follow-up run it asked for.
    /// [cancel] is looked at between the steps (the online lookups, the triage, each row's report): a cancelled
    /// triage keeps the previous results. [report] hears each email reported: counts only.
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
        // The run's live view (Station's Email loop): fetch, read, the category / reply / phishing answers, gates.
        let job = ActivityCenter.shared.start("email_run", title: "act.title.mail", view: "email", stage: "act.stage.fetching")
        let corrections = ledger.correctionIndex()
        // Each email's .eml is read from disk off the main thread.
        let box = ResultsStore.Box(all)
        let raws = await Task.detached(priority: .userInitiated) { Self.rawSources(box.value) }.value
        // Opt-in online checks (PRODUCT.md §4a): nil, and no request at all, while every switch is off.
        let context = await online.context(items: all, raws: raws)
        if cancel?.isCancelled == true { job.finish("cancelled", "act.res.stopped", [:]); return }
        job.stage("act.stage.classifying")
        let result = await RulesWork.run(on: RulesWork.mail) {
            MailTriage.shared.summariseOnline(items: all, raws: raws, corrections: corrections, online: context)
        }
        if cancel?.isCancelled == true { job.finish("cancelled", "act.res.stopped", [:]); return }
        onlineStatus = context == nil ? nil : online.status
        summary = result
        lastRun = Date()
        persist()
        // One report per email: its category key and verdicts only (never a sender or subject).
        let pace = ActivityCenter.slowPace
        let rows = result.rows
        job.progress(0, of: rows.count)
        for (i, r) in rows.enumerated() {
            job.email(category: r.categoryKey, phishing: r.phishing, unsure: r.categoryWeak, needsReply: r.needsReply)
            job.progress(i + 1, of: rows.count)
            report?.report(done: i + 1, total: rows.count)
            if pace > 0 {
                if cancel?.isCancelled == true { break }
                try? await Task.sleep(nanoseconds: UInt64(pace * 1e9))
            }
        }
        job.finish("done", "act.res.email", ["emails": rows.count, "flagged": rows.filter { $0.phishing }.count,
                                             "uncertain": rows.filter { $0.categoryWeak }.count])
        // Triage and site checks are rules on iPhone either way; Model settings' use_laya off says so on screen.
        settings.recordRun(Features.shared.EMAIL, layaOff: !settings.useLaya(Features.shared.EMAIL))
        settings.recordRun(Features.shared.BROWSER, layaOff: !settings.useLaya(Features.shared.BROWSER))
    }

    /// The `.eml` source of each mail item the phone can read (one file per message: the sample's
    /// inbox and the IMAP cache). Messages inside an mbox fall back to the item's own facts.
    nonisolated static func rawSources(_ items: [SourceItem]) -> [String: String] {
        var out: [String: String] = [:]
        for item in items where item.kind == .email && item.messageIndex == nil && item.path.lowercased().hasSuffix(".eml") {
            if let data = FileManager.default.contents(atPath: item.path), data.count <= 2_000_000 {
                out[item.id] = String(decoding: data, as: UTF8.self)
            }
        }
        return out
    }

    func item(_ id: String) -> SourceItem? { items().first { $0.id == id } }

    func markSafe(_ row: MailRow) {
        ledger.recordCorrection(MailTriage.shared.markSafe(row: row, at: JudgmentsService.now()))
        lastVerdict = row
        notice = "Marked safe. Your answer decides; the evidence stays visible."
        Task { await run() }
    }

    func confirmPhishing(_ row: MailRow) {
        ledger.recordCorrection(MailTriage.shared.confirmPhishing(row: row, at: JudgmentsService.now()))
        lastVerdict = row
        notice = "Confirmed as phishing. Do not open its links or reply."
        Task { await run() }
    }

    func undo() {
        guard let row = lastVerdict else { return }
        ledger.recordCorrection(MailTriage.shared.retraction(row: row, at: JudgmentsService.now()))
        lastVerdict = nil
        notice = "Put back."
        Task { await run() }
    }
}
