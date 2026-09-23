import XCTest
import LoupeKit
@testable import Loupe

/// Now's watchers view model (epic #7 child 5) over the bundled sample, read with PDFKit.
@MainActor
final class WatchersTests: XCTestCase {
    private var home: URL!
    private var defaults: UserDefaults!
    private let suite = "WatchersTests-\(UUID().uuidString)"

    private static let sample: [SourceItem] = {
        let root = SourcesService.bundledSample()!
        let scanner = SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                    zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                    limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
        return try! scanner.scan(sources: SourcesService.sampleRoots(root), observer: Quiet()).items
    }()

    private final class Quiet: NSObject, ScanObserver {
        func onProgress(progress: ScanProgress) {}
        func isCancelled() -> Bool { false }
    }

    /// 23 September 2026, the day the sample's planted findings are dated against.
    private let today: Date = {
        var c = DateComponents(); c.year = 2026; c.month = 9; c.day = 23; c.hour = 12
        return Calendar(identifier: .gregorian).date(from: c)!
    }()

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeWatchers-\(UUID().uuidString)")
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: home)
        defaults.removePersistentDomain(forName: suite)
    }

    private func service(items: [SourceItem] = WatchersTests.sample, model: FakeModel? = nil) -> (WatchersService, LedgerService) {
        let ledger = LedgerService(home: home)
        return (WatchersService(ledger: ledger, items: { items }, model: model ?? FakeModel(installed: false), seen: defaults), ledger)
    }

    func testTheSamplesPlantedFindingsAreOnNow() async {
        let (s, _) = service()
        await s.run(today: today)
        let f = s.findings
        XCTAssertFalse(s.running)
        XCTAssertEqual(s.summary?.modelRan, false)
        XCTAssertTrue(f.allSatisfy { $0.sample }, "every sample finding is labelled sample")

        let premium = try? XCTUnwrap(f.first { $0.title == "Annual premium up 23%" })
        XCTAssertEqual(premium?.evidence.first, "Annual premium: £450.00 → £553.50 (+23%)")
        XCTAssertEqual(premium?.itemId, "sample:documents/insurance/home-insurance-renewal-2026.pdf")

        let passport = f.first { $0.watcher == .expiry && $0.itemName.contains("passport") }
        XCTAssertEqual(passport?.title, "passport-scan-SPECIMEN.txt expires in 113 days")
        XCTAssertTrue(passport?.evidence.contains("Date of expiry: 14 JAN 2027") == true)

        XCTAssertEqual(s.top?.watcher, .impersonation)
        XCTAssertTrue(s.top?.evidence.first?.contains("mum.family@quickmail.example") == true)
        XCTAssertTrue(f.contains { $0.watcher == .siteFraud && $0.evidence.contains { $0.contains("paypal.account-verify.example") } })
        // Trap: CloudBox writing from a second address on its own domain is not impersonation.
        XCTAssertFalse(f.contains { $0.watcher == .impersonation && $0.evidence.contains { $0.contains("cloudbox") } })

        let census = try? XCTUnwrap(s.summary?.census)
        XCTAssertEqual(census?.sample, true)
        XCTAssertTrue(census?.rows.contains { $0.merchant == "Streamflix" && $0.monthlyMinor?.int64Value == 999 } == true)
        XCTAssertGreaterThan(census?.monthlyTotalMinor ?? 0, 999)
    }

    func testNewFindingsWakeTheMascotOnce() async {
        let (s, _) = service()
        await s.run(today: today)
        XCTAssertEqual(s.newCount, s.findings.count)
        XCTAssertGreaterThan(s.newCount, 0)
        await s.run(today: today)
        XCTAssertEqual(s.newCount, 0, "already seen: no second 'found'")
    }

    func testVerdictsAreWrittenToTheLedgerAndSurviveARerun() async throws {
        let (s, ledger) = service()
        await s.run(today: today)
        let before = s.findings.count
        let mum = try XCTUnwrap(s.findings.first { $0.watcher == .impersonation })
        let premium = try XCTUnwrap(s.findings.first { $0.title == "Annual premium up 23%" })

        s.answer(mum, .dismissed)
        s.answer(premium, .confirmed)
        XCTAssertEqual(s.findings.count, before - 1)
        XCTAssertEqual(s.findings.first { $0.key == premium.key }?.verdict, .confirmed)
        ledger.flush()
        let index = ledger.correctionIndex()
        XCTAssertEqual(index[CorrectionKey(judgmentId: "watcher:impersonation", criteriaHash: WatcherFindings.shared.CRITERIA, itemId: mum.key)], "dismissed")

        await s.run(today: today)
        XCTAssertFalse(s.findings.contains { $0.key == mum.key }, "a dismissed finding stays dismissed")
        XCTAssertEqual(s.summary?.setAside, 1)

        // Undo puts it back and appends a retraction.
        s.answer(try XCTUnwrap(s.findings.first { $0.watcher == .siteFraud }), .notRelevant)
        s.undoSetAside()
        XCTAssertTrue(s.findings.contains { $0.watcher == .siteFraud })
    }

    func testNoSourcesMeansNothingShownNotAFalseAllClear() async {
        let (s, _) = service(items: [])
        await s.run(today: today)
        XCTAssertEqual(s.findings.count, 0)
        XCTAssertEqual(s.summary?.itemsChecked, 0)
        XCTAssertNil(s.top)
    }

    func testTheExpiryModelHalfRunsWhenLayaIsInstalledAndOffMain() async {
        let fake = FakeJudgmentBackend(p: 0.95)
        let (s, _) = service(model: FakeModel(installed: true, backend: fake))
        await s.run(today: today)
        XCTAssertEqual(s.summary?.modelRan, true)
        XCTAssertGreaterThan(fake.calls, 0)
        // LOUD: the passport is still raised whatever the model called it.
        XCTAssertTrue(s.findings.contains { $0.watcher == .expiry && $0.itemName.contains("passport") })
    }
}
