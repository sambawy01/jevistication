import Foundation
import LoupeKit

/// Privacy check (epic #7 child 10): LoupeKit's shared `PrivacyCheck` (Loupe Station's personal-data,
/// secret and duplicate rules) over every enabled source. Mechanical — no model — but it runs on the
/// one model queue at sweep priority, like the sort, so it never competes with Laya for the CPU.
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
            rescan: { await SourcesService.shared.scanPhone(.files) })
        service.files.commit()   // anything held from a previous session is deleted for good
        return service
    }()

    @Published private(set) var summary: PrivacySummary?
    @Published private(set) var running = false
    @Published var notice: String?
    @Published private(set) var lastSafe: PrivacyFinding?
    @Published private(set) var pendingUndo: PrivacyUndo?

    private let ledger: LedgerService
    private let items: () -> [SourceItem]
    let locator: PrivacyLocating
    let files: PrivacyFileActions
    private let photos: PhotoDeleting
    private let rescan: () async -> Void
    private let settings: ModelSettingsSource

    init(ledger: LedgerService, items: @escaping () -> [SourceItem], locator: PrivacyLocating,
         files: PrivacyFileActions, photos: PhotoDeleting, rescan: @escaping () async -> Void = {},
         settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.settings = settings
        self.ledger = ledger
        self.items = items
        self.locator = locator
        self.files = files
        self.photos = photos
        self.rescan = rescan
    }

    var findings: [PrivacyFinding] { summary?.findings ?? [] }

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
        // Model settings (`features.scan`): read_content off checks names, folders and duplicates only.
        let readContent = settings.current.readContent
        summary = await ModelWork.run(.sweep) {
            PrivacyCheck.shared.summariseWith(items: all, sampleSourceIds: [SourcesService.sampleId], corrections: corrections, readContent: readContent)
        }
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
    }

    func undoSafe() {
        guard let f = lastSafe, let s = summary else { return }
        ledger.recordCorrection(PrivacyCheck.shared.retraction(finding: f, at: JudgmentsService.now()))
        summary = PrivacySummary(findings: (s.findings + [f]).sorted { a, b in
            a.severity != b.severity ? a.severity > b.severity : a.group.ordinal < b.group.ordinal
        }, markedSafe: max(0, s.markedSafe - 1), itemsChecked: s.itemsChecked)
        lastSafe = nil
        notice = "Put back."
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
    }
}
