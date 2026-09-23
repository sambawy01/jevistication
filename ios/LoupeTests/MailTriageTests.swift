import XCTest
import LoupeKit
@testable import Loupe

/// Mail triage (epic #7 children 11 and 12): the view model over the sample inbox, the PayPal
/// phishing with its signals, and Mark safe / Confirm phishing / Undo as ledger corrections.
@MainActor
final class MailTriageTests: XCTestCase {
    private var home: URL!

    private static let sample: [SourceItem] = {
        let root = SourcesService.bundledSample()!
        return try! SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                  zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                  limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
            .scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
    }()

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeMail-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func service(_ items: [SourceItem] = sample) -> (MailTriageService, LedgerService) {
        let ledger = LedgerService(home: home)
        return (MailTriageService(ledger: ledger, items: { items }), ledger)
    }

    private func waitForRun(_ s: MailTriageService) async {
        for _ in 0..<100 where s.running { try? await Task.sleep(nanoseconds: 20_000_000) }
        await s.run()
    }

    func testTheSamplePaypalPhishingIsFlaggedFirstWithItsSignals() async throws {
        let (s, _) = service()
        await s.run()
        let rows = s.rows
        XCTAssertEqual(rows.count, Self.sample.filter { $0.kind == .email }.count)
        let top = try XCTUnwrap(rows.first)
        XCTAssertTrue(top.itemId.hasSuffix("phishing-paypal.eml"))
        XCTAssertTrue(top.phishing)
        XCTAssertEqual(top.section, .phishing)
        let codes = Set(top.signals.map(\.code))
        XCTAssertTrue(codes.isSuperset(of: ["sender_lookalike_brand", "display_brand_mismatch", "link_brand_in_subdomain", "urgent_language", "site_check"]), "\(codes)")
        XCTAssertTrue(top.signals.contains { $0.text.contains("looks like PayPal") })
        XCTAssertEqual(Int(s.summary!.phishingCount), 1)
    }

    func testRawSourcesReadTheSampleEml() {
        let raws = MailTriageService.rawSources(Self.sample)
        let paypal = Self.sample.first { $0.id.hasSuffix("phishing-paypal.eml") }!
        XCTAssertTrue(raws[paypal.id]?.contains("<a href=\"http://paypal.account-verify.example/login\">") ?? false)
        // mbox messages have no file of their own
        XCTAssertTrue(Self.sample.filter { $0.messageIndex != nil }.allSatisfy { raws[$0.id] == nil })
    }

    func testMarkSafeIsALedgerCorrectionThatDecidesAndUndoes() async throws {
        let (s, ledger) = service()
        await s.run()
        let paypal = try XCTUnwrap(s.rows.first { $0.phishing })
        s.markSafe(paypal)
        await waitForRun(s)
        ledger.flush()
        XCTAssertEqual(ledger.correctionIndex()[CorrectionKey(judgmentId: "mail-phishing", criteriaHash: "mail-phishing-v1", itemId: paypal.itemId)], "safe")
        let after = try XCTUnwrap(s.rows.first { $0.itemId == paypal.itemId })
        XCTAssertFalse(after.phishing)
        XCTAssertEqual(after.personVerdict, "safe")
        XCTAssertTrue(after.verdict.flag, "the evidence stays visible")
        s.undo()
        await waitForRun(s)
        XCTAssertTrue(try XCTUnwrap(s.rows.first { $0.itemId == paypal.itemId }).phishing)
    }

    func testConfirmPhishingOnACleanMail() async throws {
        let (s, _) = service()
        await s.run()
        let council = try XCTUnwrap(s.rows.first { $0.itemId.hasSuffix("council-tax-bill.eml") })
        XCTAssertFalse(council.phishing)
        s.confirmPhishing(council)
        await waitForRun(s)
        XCTAssertTrue(try XCTUnwrap(s.rows.first { $0.itemId == council.itemId }).phishing)
        XCTAssertEqual(Int(s.summary!.phishingCount), 2)
    }

    func testNoSourceMeansNoMail() async {
        let (s, _) = service([])
        await s.run()
        XCTAssertEqual(s.rows.count, 0)
        XCTAssertEqual(s.summary?.webLinks.count, 0)
    }
}
