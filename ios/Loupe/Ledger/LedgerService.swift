import Foundation
import LoupeKit

/// The decision ledger on this iPhone (A5, epic #7 child 1): LoupeKit's `PhoneLedger` over plain
/// files in Application Support/Loupe — the desktop's exact JSON-lines format, appended and synced,
/// never rewritten. Nothing here leaves the phone unless the user exports it (F4) and shares it.
///
/// Writes go through one serial queue, off the main thread; `count` is published on the main
/// thread for Me's "Decisions logged" line.
final class LedgerService: ObservableObject, @unchecked Sendable {
    /// The app's ledger. Under XCTest or `-LoupeFixtures` it lives in a throwaway directory, so
    /// test and fixture decisions never mix with the user's own history.
    static let shared = LedgerService(home: LedgerService.defaultHome())

    @MainActor @Published private(set) var count: Int = 0
    /// Set when the store could not be opened or written; Me shows it rather than a wrong count.
    @MainActor @Published private(set) var problem: String?

    let home: URL
    private let queue = DispatchQueue(label: "dev.loupe.ledger", qos: .utility)
    private var ledger: PhoneLedger?

    init(home: URL) {
        self.home = home
        queue.async { [self] in
            do {
                let l = try Self.open(home)
                ledger = l
                let n = Int(l.count())
                Task { @MainActor in self.count = n }
            } catch {
                let m = error.localizedDescription
                Task { @MainActor in self.problem = m }
            }
        }
    }

    /// Appends rows in order, synced to disk. Returns immediately; the write runs on the queue.
    func record(_ rows: [LedgerRow]) {
        guard !rows.isEmpty else { return }
        queue.async { [self] in
            guard let ledger else { return }
            do {
                try ledger.appendAll(newRows: rows)
                let n = Int(ledger.count())
                Task { @MainActor in self.count = n }
            } catch {
                let m = error.localizedDescription
                Task { @MainActor in self.problem = m }
            }
        }
    }

    /// Waits for every write queued so far (tests; export calls it implicitly by queueing after).
    func flush() { queue.sync {} }

    func stats() -> LedgerStats? { queue.sync { ledger?.stats() } }

    func rows(judgmentId: String) -> [LedgerRow] { queue.sync { ledger?.rowsForJudgment(judgmentId: judgmentId) ?? [] } }

    /// Every row, in append order (the Judgments tab filters by judgment and wording in LoupeKit).
    func allRows() -> [LedgerRow] { queue.sync { ledger?.rows() ?? [] } }

    /// The latest correction per (judgment, wording, item).
    func correctionIndex() -> [CorrectionKey: String] { queue.sync { ledger?.correctionIndex() ?? [:] } }

    /// The user's judgments (loupe-judgments.json beside the ledger, the desktop's format).
    func judgments() throws -> [UserJudgment] {
        try queue.sync {
            guard let ledger else { throw ExportError.storeUnavailable }
            return try ledger.judgments()
        }
    }

    /// Replaces the saved judgments (synced temp file + rename).
    func saveJudgments(_ judgments: [UserJudgment]) throws {
        try queue.sync {
            guard let ledger else { throw ExportError.storeUnavailable }
            try ledger.saveJudgments(judgments: judgments)
        }
    }

    /// F4: writes the desktop's four export files into a fresh folder, then zips it (Foundation's
    /// coordinated-read `.forUploading`, no third-party code) into one file to share.
    func export(now: Date = Date()) async throws -> URL {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<URL, Error>) in
            queue.async { [self] in
                c.resume(with: Result { try makeExport(now: now) })
            }
        }
    }

    private func makeExport(now: Date) throws -> URL {
        guard let ledger else { throw ExportError.storeUnavailable }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd-HHmmss"
        let name = "Loupe-export-\(f.string(from: now))"
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeExport", isDirectory: true)
        let dir = root.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        _ = try ledger.exportTo(dir: dir.path)
        return try Self.zip(dir, to: root.appendingPathComponent("\(name).zip"))
    }

    static func zip(_ dir: URL, to target: URL) throws -> URL {
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: dir, options: .forUploading, error: &coordinationError) { zipped in
            do {
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.copyItem(at: zipped, to: target)
            } catch { copyError = error }
        }
        if let e = coordinationError ?? copyError { throw e }
        return target
    }

    enum ExportError: LocalizedError {
        case storeUnavailable
        var errorDescription: String? { "The decision ledger could not be opened." }
    }

    private static func open(_ home: URL) throws -> PhoneLedger {
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        // PhoneLedger's calls are @Throws in Kotlin, so an I/O failure arrives as a Swift error.
        return try PhoneLedger.companion.open(home: home.path)
    }

    static func defaultHome() -> URL {
        let env = ProcessInfo.processInfo
        let throwaway = env.environment["XCTestConfigurationFilePath"] != nil || LaunchOptions.current.fixtureMode
        if throwaway {
            return FileManager.default.temporaryDirectory.appendingPathComponent("LoupeLedger-\(UUID().uuidString)", isDirectory: true)
        }
        let support = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                     appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return support.appendingPathComponent("Loupe", isDirectory: true)
    }
}
