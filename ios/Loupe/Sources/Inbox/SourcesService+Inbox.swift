import Foundation
import LoupeKit

/// The Inbox (epic #7 child 15): where imported things land — CSVs (a row per item, with its column
/// context), `.eml` / `.mbox` mail, ZIP archives (unpacked safely by LoupeKit's `ZipReader`), and
/// text or links shared from other apps. The rules live in LoupeKit's `Inbox` (ported from Loupe
/// Station's `laya_studio/items`); this only picks files, keeps their security scope open while
/// they are copied, and publishes the batches. Items flow into judgments, the sweep, the watchers,
/// the privacy check and mail triage through `items()`, labelled with where they came from.
extension SourcesService {
    static let inboxOrigins = (files: "Files", share: "Share sheet", pasted: "Pasted", fixture: "Test fixture")

    var inboxEnabled: Bool { inbox?.isEnabled() ?? false }

    func setInboxEnabled(_ on: Bool) {
        do { try inbox?.setEnabled(on: on) } catch {
            inboxProblem = "Could not save the setting: \(error.localizedDescription)"
            return
        }
        revision += 1
        objectWillChange.send()
    }

    /// Imports files picked in the Files app (or handed over by the share sheet) as one batch.
    @discardableResult
    func importToInbox(_ urls: [URL], origin: String = SourcesService.inboxOrigins.files) async -> InboxBatch? {
        guard let inbox, !urls.isEmpty else { return nil }
        let name = urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files"
        return await runInbox {
            let scoped = urls.filter { $0.startAccessingSecurityScopedResource() }
            defer { scoped.forEach { $0.stopAccessingSecurityScopedResource() } }
            return try inbox.importFiles(paths: urls.map(\.path), name: name, origin: origin, nowEpochMillis: Self.nowMillis())
        }
    }

    /// Imports pasted or shared text; a lone link is kept as a link.
    @discardableResult
    func importTextToInbox(_ text: String, title: String = "", origin: String = SourcesService.inboxOrigins.pasted) async -> InboxBatch? {
        guard let inbox, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return await runInbox { try inbox.importText(text: text, title: title, origin: origin, nowEpochMillis: Self.nowMillis()) }
    }

    func removeInboxBatch(_ id: String) {
        guard let inbox else { return }
        do {
            _ = try inbox.remove(batchId: id)
            inboxBatches = inbox.batches()
            revision += 1
        } catch {
            inboxProblem = "Could not remove it: \(error.localizedDescription)"
        }
    }

    func inboxItems(_ batch: InboxBatch) -> [SourceItem] { inbox?.cached(batchId: batch.id)?.result.items ?? [] }

    func inboxSkipped(_ batch: InboxBatch) -> [Skipped] { inbox?.cached(batchId: batch.id)?.result.skipped ?? [] }

    /// Whatever the share sheet left in the Inbox's waiting folder becomes one batch, and the waiting
    /// copies are removed. Called when the app opens.
    func collectShared() async {
        let waiting = SharedInbox.importFolder(in: deps.inbox())
        let files = ((try? FileManager.default.contentsOfDirectory(at: waiting, includingPropertiesForKeys: nil)) ?? [])
            .filter { !$0.lastPathComponent.hasPrefix(".") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard !files.isEmpty else { return }
        if await importToInbox(files, origin: Self.inboxOrigins.share) != nil {
            files.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    #if DEBUG
    /// `-LoupeInboxDemo` (with -LoupeFixtures): imports a small card statement, as if picked in Files,
    /// so a UI test sees its rows as items.
    func seedInboxDemo() async {
        guard inboxBatches.isEmpty else { return }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("loupe-inbox-demo-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("statement-fixture.csv")
        try? Data(Self.inboxDemoCSV.utf8).write(to: url)
        await importToInbox([url], origin: Self.inboxOrigins.fixture)
        try? FileManager.default.removeItem(at: dir)
    }

    static let inboxDemoCSV = """
    Date,Description,Amount,Currency
    05/06/2026,STREAMFLIX.COM,-9.99,GBP
    05/07/2026,STREAMFLIX.COM,-9.99,GBP
    12/07/2026,Cafe Luna,"-1,234.50",GBP
    """
    #endif

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func runInbox(_ work: @escaping () throws -> InboxBatch) async -> InboxBatch? {
        inboxBusy = true
        inboxProblem = nil
        defer { inboxBusy = false }
        let outcome: Result<InboxBatch, Error> = await withCheckedContinuation { cont in
            queue.async { cont.resume(returning: Result { try work() }) }
        }
        switch outcome {
        case .success(let batch):
            inboxBatches = inbox?.batches() ?? []
            revision += 1
            return batch
        case .failure(let error):
            inboxProblem = "The import failed: \(error.localizedDescription)"
            return nil
        }
    }
}
