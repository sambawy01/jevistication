import XCTest
import LoupeKit
@testable import Loupe

/// Opening the app only loads the latest saved results (owner decision A, 2026-09-28): the watchers', the privacy
/// check's and mail triage's summaries are saved after each run and after each answer, and a new service over the
/// same home (a relaunch) shows them without running anything. The no-launch-work guarantee at this level.
@MainActor
final class ResultsPersistenceTests: XCTestCase {
    private var home: URL!
    private var suite: String!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeResults-\(UUID().uuidString)")
        suite = "ResultsPersistenceTests-\(UUID().uuidString)"
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: home)
        UserDefaults.standard.removePersistentDomain(forName: suite)
    }

    private static let sample: [SourceItem] = {
        let root = TestSample.root()!
        return try! SourceScanner(extractors: AppleExtractors()).scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
    }()

    /// Items that would give different results: a relaunch that re-ran would show these, not the saved ones.
    private final class Reads { var count = 0 }

    private func waitLoaded(_ loaded: @escaping @MainActor () -> Bool) async throws {
        let end = Date().addingTimeInterval(10)
        while !loaded() {
            if Date() > end { return XCTFail("the saved results never loaded") }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    func testTheWatchersResultsAreSavedAndLoadedWithoutARun() async throws {
        let store = ResultsStore(home: home)
        let defaults = UserDefaults(suiteName: suite)!
        let w = WatchersService(ledger: LedgerService(home: home), items: { Self.sample }, model: FakeModel(installed: false),
                                seen: defaults, settings: FakeSettings(), results: store)
        try await waitLoaded { w.loaded }
        XCTAssertNil(w.summary, "nothing saved yet: not checked")
        await w.run(today: Date(timeIntervalSince1970: 1_790_553_600))
        let ran = try XCTUnwrap(w.summary)
        XCTAssertFalse(ran.findings.isEmpty)
        // An answer is saved too.
        let dismissed = try XCTUnwrap(ran.findings.last)
        w.answer(dismissed, .dismissed)
        store.flush()

        let reads = Reads()
        let again = WatchersService(ledger: LedgerService(home: home), items: { reads.count += 1; return [] }, model: FakeModel(installed: false),
                                    seen: defaults, settings: FakeSettings(), results: store)
        try await waitLoaded { again.loaded }
        XCTAssertEqual(again.summary?.findings.map(\.key), w.summary?.findings.map(\.key))
        XCTAssertFalse(again.summary?.findings.contains { $0.key == dismissed.key } ?? true, "the answer survived")
        XCTAssertEqual(again.newKeys, w.newKeys)
        XCTAssertNotNil(again.lastRun)
        XCTAssertFalse(again.running)
        XCTAssertEqual(reads.count, 0, "loading read no items: nothing ran")
    }

    func testThePrivacyResultsAreSavedAndLoadedWithoutARun() async throws {
        let store = ResultsStore(home: home)
        let p = PrivacyService(ledger: LedgerService(home: home), items: { Self.sample }, locator: NoLocator(),
                               files: PrivacyFileActions(home: home), photos: NoPhotos(), settings: FakeSettings(), results: store)
        try await waitLoaded { p.loaded }
        await p.run()
        let passport = try XCTUnwrap(p.findings.first { $0.itemName == "passport-scan-SPECIMEN.txt" })
        p.markSafe(passport)
        store.flush()
        let reads = Reads()
        let again = PrivacyService(ledger: LedgerService(home: home), items: { reads.count += 1; return [] }, locator: NoLocator(),
                                   files: PrivacyFileActions(home: home), photos: NoPhotos(), settings: FakeSettings(), results: store)
        try await waitLoaded { again.loaded }
        XCTAssertEqual(again.summary, p.summary)
        XCTAssertEqual(again.summary?.markedSafe, 1)
        XCTAssertEqual(reads.count, 0)
    }

    func testTheMailResultsAreSavedAndLoadedWithoutARun() async throws {
        let store = ResultsStore(home: home)
        let m = MailTriageService(ledger: LedgerService(home: home), items: { Self.sample }, settings: FakeSettings(), results: store)
        try await waitLoaded { m.loaded }
        await m.run()
        XCTAssertGreaterThan(m.summary?.phishingCount ?? 0, 0)
        store.flush()
        let reads = Reads()
        let again = MailTriageService(ledger: LedgerService(home: home), items: { reads.count += 1; return [] }, settings: FakeSettings(), results: store)
        try await waitLoaded { again.loaded }
        XCTAssertEqual(again.summary, m.summary)
        XCTAssertEqual(reads.count, 0)
    }

    func testACancelledCheckKeepsThePreviousResults() async throws {
        let store = ResultsStore(home: home)
        let p = PrivacyService(ledger: LedgerService(home: home), items: { Self.sample }, locator: NoLocator(),
                               files: PrivacyFileActions(home: home), photos: NoPhotos(), settings: FakeSettings(), results: store)
        await p.run()
        let before = try XCTUnwrap(p.summary)
        // Cancelled at its first item (LoupeKit asks before each one): the partial check is dropped.
        let cancel = RunCancel()
        let reporter = RunReporter { _ in cancel.cancel() }
        await p.run(cancel: cancel, report: reporter)
        XCTAssertTrue(cancel.isCancelled)
        XCTAssertEqual(p.summary, before, "the previous results stay; a partial check is never shown or saved")
        XCTAssertFalse(p.running)
        store.flush()
        XCTAssertEqual(store.loadPrivacy()?.0, before)
    }

    func testAnUnreadableResultsFileIsNoResult() async throws {
        let store = ResultsStore(home: home)
        try FileManager.default.createDirectory(at: store.dir, withIntermediateDirectories: true)
        try Data("{\"version\":1,\"kind\":\"privacy\",\"findi".utf8).write(to: store.file(.privacy))
        let p = PrivacyService(ledger: LedgerService(home: home), items: { [] }, locator: NoLocator(),
                               files: PrivacyFileActions(home: home), photos: NoPhotos(), settings: FakeSettings(), results: store)
        try await waitLoaded { p.loaded }
        XCTAssertNil(p.summary)
    }

    private struct NoLocator: PrivacyLocating {
        func access(for itemId: String) -> PrivacyAccess { .suggestOnly("read only") }
    }

    private final class NoPhotos: PhotoDeleting {
        func delete(localId: String) async throws {}
    }
}
