import XCTest
import LoupeKit
@testable import Loupe

/// Privacy check (epic #7 child 10): the view model over the sample, mark safe in the ledger, and
/// delete / move / undo on real files in a temp directory.
@MainActor
final class PrivacyTests: XCTestCase {
    private var home: URL!
    private var folder: URL!

    private static let sample: [SourceItem] = {
        let root = SourcesService.bundledSample()!
        return try! SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                  zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                  limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
            .scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
    }()

    /// Maps `files:t/<name>` to the temp folder; everything else is suggest-only.
    private struct TempLocator: PrivacyLocating {
        let folder: URL
        func access(for itemId: String) -> PrivacyAccess {
            itemId.hasPrefix("files:t/") ? .file(folder.appendingPathComponent(String(itemId.dropFirst(8))), scope: nil)
                : .suggestOnly("read only")
        }
    }

    private final class FakePhotos: PhotoDeleting {
        var deleted: [String] = []
        func delete(localId: String) async throws { deleted.append(localId) }
    }

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupePrivacy-\(UUID().uuidString)")
        folder = home.appendingPathComponent("picked")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func service(_ items: [SourceItem], photos: FakePhotos = FakePhotos()) -> (PrivacyService, LedgerService) {
        let ledger = LedgerService(home: home)
        return (PrivacyService(ledger: ledger, items: { items }, locator: TempLocator(folder: folder),
                               files: PrivacyFileActions(home: home), photos: photos), ledger)
    }

    private func fileItem(_ name: String, _ text: String) throws -> SourceItem {
        let url = folder.appendingPathComponent(name)
        try text.write(to: url, atomically: true, encoding: .utf8)
        let scan = try SourceScanner(extractors: AppleExtractors()).scan(
            sources: [SourceRoot(id: "files", type: .folder, path: folder.path, idPrefix: "files:t/")], observer: NullScanObserver())
        return try XCTUnwrap(scan.items.first { $0.fileName == name }, "scanner did not read \(name)")
    }

    func testTheSampleShowsIdDocumentsAndDuplicateReceipts() async {
        let (s, _) = service(Self.sample)
        await s.run()
        let names = Set(s.findings.filter { $0.group == .idDocuments }.map(\.itemName))
        XCTAssertEqual(names, ["passport-scan-SPECIMEN.txt", "driving-licence-SPECIMEN.txt"])
        let dup = try? XCTUnwrap(s.findings.first { $0.group == .duplicates })
        XCTAssertEqual(dup?.duplicates?.count, 2)
        XCTAssertTrue(s.findings.allSatisfy(\.sample))
        // The sample is part of the app: suggest-only.
        if case .suggestOnly = s.access(s.findings[0]) {} else { XCTFail("sample must be suggest-only") }
    }

    func testMarkSafeIsALedgerCorrectionThatSurvivesARerunAndUndoes() async throws {
        let (s, ledger) = service(Self.sample)
        await s.run()
        let before = s.findings.count
        let passport = try XCTUnwrap(s.findings.first { $0.itemName == "passport-scan-SPECIMEN.txt" })
        s.markSafe(passport)
        XCTAssertEqual(s.findings.count, before - 1)
        ledger.flush()
        XCTAssertEqual(ledger.correctionIndex()[CorrectionKey(judgmentId: "privacy", criteriaHash: "privacy-v1", itemId: passport.key)], "safe")
        await s.run()
        XCTAssertFalse(s.findings.contains { $0.key == passport.key })
        XCTAssertEqual(s.summary?.markedSafe, 1)
        s.undoSafe()
        XCTAssertTrue(s.findings.contains { $0.key == passport.key })
        ledger.flush()
        await s.run()
        XCTAssertTrue(s.findings.contains { $0.key == passport.key }, "the retraction is honoured")
    }

    func testDeleteThenUndoPutsTheFileBack() async throws {
        let a = try fileItem("receipt.txt", "Receipt total 12.00 GBP for groceries")
        let b = try fileItem("receipt copy.txt", "Receipt total 12.00 GBP for groceries")
        let (s, _) = service([a, b])
        await s.run()
        let dup = try XCTUnwrap(s.findings.first { $0.group == .duplicates })
        let victim = folder.appendingPathComponent("receipt copy.txt")
        XCTAssertEqual(s.target(dup), "files:t/receipt copy.txt")
        await s.delete(dup)
        XCTAssertFalse(FileManager.default.fileExists(atPath: victim.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent("receipt.txt").path), "the kept copy stays")
        XCTAssertNotNil(s.pendingUndo)
        await s.undoFile()
        XCTAssertTrue(FileManager.default.fileExists(atPath: victim.path))
        // Delete again and commit: gone for good, nothing left in the holding folder.
        await s.delete(try XCTUnwrap(s.findings.first { $0.group == .duplicates }))
        s.commitPending()
        XCTAssertFalse(FileManager.default.fileExists(atPath: victim.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.files.holding.path))
    }

    func testMoveToAChosenFolderAndUndo() async throws {
        let env = try fileItem("db-notes.txt", "DB_PASSWORD=Tr0ub4dor&3horse\n")
        let dest = home.appendingPathComponent("vault")
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        let (s, _) = service([env])
        await s.run()
        let f = try XCTUnwrap(s.findings.first { $0.ruleId == "password_assignment" })
        await s.move(f, to: dest)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dest.appendingPathComponent("db-notes.txt").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("db-notes.txt").path))
        await s.undoFile()
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent("db-notes.txt").path))
    }

    /// Secrets are never logged or stored: after a run and a mark safe, the ledger holds no raw value.
    func testNoSecretReachesTheLedgerOrTheFindings() async throws {
        let secret = "sk-ant-api03-" + String(repeating: "Zq8", count: 30)
        let item = try fileItem("config.txt", "ANTHROPIC_API_KEY=\(secret)\ncard 4539148800000001\n")
        let (s, ledger) = service([item])
        await s.run()
        XCTAssertTrue(s.findings.contains { $0.ruleId == "anthropic_key" })
        XCTAssertTrue(s.findings.contains { $0.ruleId == "card_number" })
        for f in s.findings { s.markSafe(f) }
        ledger.flush()
        let dump = s.summary.map { String(describing: $0) } ?? ""
        XCTAssertFalse(dump.contains(secret))
        let files = try FileManager.default.subpathsOfDirectory(atPath: home.path)
        for p in files where !p.hasPrefix("picked") {
            let data = (try? Data(contentsOf: home.appendingPathComponent(p))) ?? Data()
            let text = String(decoding: data, as: UTF8.self)
            XCTAssertFalse(text.contains(secret) || text.contains(String(secret.dropFirst(6).prefix(18))), p)
            XCTAssertFalse(text.contains("4539148800000001"), p)
        }
    }
}
