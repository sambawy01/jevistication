import XCTest
@testable import Loupe

/// Home's scan panel (owner ruling O-5): while a scan runs, the live display and every real source with its switch;
/// after it, one line. Never the sample (owner ruling O-6).
@MainActor
final class HomeScanPanelModelTests: XCTestCase {
    private func snap(_ source: String, _ phase: ScanSnapshot.Phase, read: Int = 0, summary: String? = nil) -> ScanSnapshot {
        var s = ScanSnapshot(pipeline: .of(source))
        s.phase = phase
        s.read = read
        s.summary = summary
        return s
    }

    func testSourcesAreTheRealOnesInAFixedOrder() {
        XCTAssertEqual(HomeScanPanelModel.sources(inboxHasImports: false).map(\.id),
                       ["photos", "files", "mail", "calendar", "contacts"])
        XCTAssertEqual(HomeScanPanelModel.sources(inboxHasImports: true).map(\.id),
                       ["photos", "files", "mail", "calendar", "contacts", "inbox"],
                       "the Inbox has a switch once something was imported")
        XCTAssertEqual(HomeScanPanelModel.sources(inboxHasImports: true).map(\.title),
                       ["Photos", "Files", "Mail", "Calendar", "Contacts", "Inbox"])
    }

    func testSourcesNeverHoldTheSample() {
        for inbox in [false, true] {
            let ids = HomeScanPanelModel.sources(inboxHasImports: inbox).map(\.id)
            XCTAssertFalse(ids.contains(SourcesService.sampleId), "\(ids)")
        }
    }

    func testScansFollowTheSourceOrderWithoutTheSample() {
        XCTAssertEqual(HomeScanPanelModel.scanKeys(["contacts", SourcesService.sampleId, "photos", "mail"]),
                       ["photos", "mail", "contacts"])
        XCTAssertEqual(HomeScanPanelModel.scanKeys([SourcesService.sampleId]), [], "the sample alone shows no panel")
    }

    func testNoSummaryLineWhileAScanRunsOrWhenNothingRan() {
        XCTAssertNil(HomeScanPanelModel.summaryLine([]))
        XCTAssertNil(HomeScanPanelModel.summaryLine([snap("photos", .finished, read: 3, summary: "3 photos · 0 bytes out"),
                                                     snap("files", .running)]))
    }

    func testOneFinishedScanSaysItsOwnSummary() {
        XCTAssertEqual(HomeScanPanelModel.summaryLine([snap("photos", .finished, read: 120,
                                                            summary: "120 photos · 30 with text · 0 bytes out")]),
                       "Photos: 120 photos · 30 with text · 0 bytes out")
    }

    func testSeveralFinishedScansAreCountedTogether() {
        let line = HomeScanPanelModel.summaryLine([snap("photos", .finished, read: 1200, summary: "x"),
                                                   snap("contacts", .finished, read: 34, summary: "y")])
        XCTAssertEqual(line, "Read \(1234.formatted()) items from 2 sources")
        XCTAssertEqual(HomeScanPanelModel.summaryLine([snap("photos", .finished, read: 1, summary: "x"),
                                                       snap("files", .finished, read: 0, summary: "y")]),
                       "Read 1 item from 2 sources")
    }

    func testAStoppedScanIsNamed() {
        XCTAssertEqual(HomeScanPanelModel.summaryLine([snap("mail", .failed)]), "Mail: the scan stopped")
        XCTAssertEqual(HomeScanPanelModel.summaryLine([snap("photos", .finished, read: 5, summary: "5 photos · 0 bytes out"),
                                                       snap("mail", .failed)]),
                       "Photos: 5 photos · 0 bytes out · Mail stopped")
    }
}
