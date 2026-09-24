import Foundation
import LoupeKit

/// Mail triage (epic #7 children 11 and 12): LoupeKit's shared `MailTriage` — Loupe Station's
/// classifier rules and the one phishing / site formula shared with Station (docs/PHISHING-FORMULA.md),
/// with the opt-in online checks when they are on — over every mail item of the enabled sources
/// (the sample's inbox now; IMAP mail when that source is on), plus site checks on web-link items.
/// Mechanical, no model; it runs on the model queue at sweep priority like the privacy check.
/// Mark safe / Confirm phishing are appended ledger corrections, with Undo. Nothing leaves the phone.
@MainActor
final class MailTriageService: ObservableObject {
    static let shared = MailTriageService(ledger: LedgerService.shared, items: { SourcesService.shared.items() }, online: OnlineChecksService.shared)

    @Published private(set) var summary: MailSummary?
    @Published private(set) var running = false
    @Published var notice: String?
    @Published private(set) var lastVerdict: MailRow?

    /// The online checks' status line for the last run (nil when they are off).
    @Published private(set) var onlineStatus: String?

    private let ledger: LedgerService
    private let items: () -> [SourceItem]
    private let online: OnlineChecksService
    private let settings: ModelSettingsSource

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], online: OnlineChecksService = .shared,
         settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.settings = settings
        self.ledger = ledger
        self.items = items
        self.online = online
    }

    var rows: [MailRow] { summary?.rows ?? [] }

    /// A run asked for while one is going: it runs again after, so new items are never missed.
    private var rerun = false

    func run() async {
        guard !running else { rerun = true; return }
        running = true
        defer {
            running = false
            if rerun { rerun = false; Task { await run() } }
        }
        let all = items()
        let corrections = ledger.correctionIndex()
        let raws = Self.rawSources(all)
        // Opt-in online checks (PRODUCT.md §4a): nil, and no request at all, while every switch is off.
        let context = await online.context(items: all, raws: raws)
        onlineStatus = context == nil ? nil : online.status
        summary = await ModelWork.run(.sweep) {
            MailTriage.shared.summariseOnline(items: all, raws: raws, corrections: corrections, online: context)
        }
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
