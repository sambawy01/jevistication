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

    // MARK: The adapter (today's live scans → the panel's progress; RunCoordinator's progress after the rebase)

    private func running(_ source: String, done: Int, total: Int?, rate: Double?, eta: TimeInterval?, current: String?) -> ScanSnapshot {
        var s = snap(source, .running, read: done)
        s.done = done
        s.total = total
        s.rate = rate
        s.eta = eta
        if let current {
            s.recent = [ScanSnapshot.Recent(id: 1, name: current, snippet: "", read: true, textFound: false, boxes: [],
                                            thumb: nil, at: 0)]
        }
        return s
    }

    func testNoScansGiveNoProgress() {
        XCTAssertNil(HomeScanPanelModel.progress([]))
    }

    func testRunningScansBecomeOneProgress() {
        let p = HomeScanPanelModel.progress([running("photos", done: 120, total: 300, rate: 8, eta: 20, current: "IMG_4107.JPG"),
                                             running("files", done: 4, total: nil, rate: 2, eta: nil, current: "lease.pdf")])
        XCTAssertEqual(p?.stage, "Reading Photos, Files")
        XCTAssertEqual(p?.currentItem, "IMG_4107.JPG", "the first running scan's newest item (already masked by the scan)")
        XCTAssertEqual(p?.stages, [HomeScanProgress.Stage(id: "photos", name: "Photos", done: 120, total: 300),
                                   HomeScanProgress.Stage(id: "files", name: "Files", done: 4, total: nil)])
        XCTAssertEqual(p?.rate, 10, "the scans' rates together")
        XCTAssertEqual(p?.eta, 20, "the longest time left")
        XCTAssertNil(p?.summary)
        XCTAssertEqual(p?.running, true)
    }

    func testAFinishedScanStillCountedWhileAnotherRuns() {
        let p = HomeScanPanelModel.progress([snap("photos", .finished, read: 9, summary: "9 photos · 0 bytes out"),
                                             running("files", done: 1, total: 2, rate: nil, eta: nil, current: nil)])
        XCTAssertEqual(p?.stage, "Reading Files")
        XCTAssertNil(p?.currentItem)
        XCTAssertNil(p?.rate)
        XCTAssertEqual(p?.stages.map(\.id), ["photos", "files"])
        XCTAssertEqual(p?.running, true)
    }

    func testFinishedScansBecomeTheSummary() {
        let p = HomeScanPanelModel.progress([snap("photos", .finished, read: 5, summary: "5 photos · 0 bytes out")])
        XCTAssertEqual(p?.summary, "Photos: 5 photos · 0 bytes out")
        XCTAssertEqual(p?.running, false)
        XCTAssertNil(p?.eta)
    }

    func testCancelShowsOnlyWithACancelWhileRunning() {
        let live = HomeScanPanelModel.progress([running("photos", done: 1, total: 2, rate: nil, eta: nil, current: nil)])
        let done = HomeScanPanelModel.progress([snap("photos", .finished, read: 2, summary: "2 photos")])
        XCTAssertTrue(HomeScanPanelModel.showsCancel(live, canCancel: true))
        XCTAssertFalse(HomeScanPanelModel.showsCancel(live, canCancel: false), "today's scans cannot be cancelled")
        XCTAssertFalse(HomeScanPanelModel.showsCancel(done, canCancel: true), "nothing to cancel once it finished")
        XCTAssertFalse(HomeScanPanelModel.showsCancel(nil, canCancel: true))
    }

    /// A source that finished while another still reads is not "Reading": it shows its resting count (or Off).
    func testAFinishedSourceIsNotReadingWhileAnotherRuns() {
        let p = HomeScanPanelModel.progress([snap("photos", .finished, read: 9, summary: "9 photos · 0 bytes out"),
                                             running("files", done: 1, total: 2, rate: nil, eta: nil, current: nil)])
        let photos = p?.stages.first { $0.id == "photos" }
        let files = p?.stages.first { $0.id == "files" }
        XCTAssertEqual(photos?.running, false)
        XCTAssertEqual(files?.running, true)
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: photos, on: true, restingCount: 9), "9 items")
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: photos, on: false, restingCount: 9), "Off")
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: files, on: true, restingCount: 0), "Reading · 1 of 2")
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: nil, on: true, restingCount: 1), "1 item")
        XCTAssertFalse(HomeScanPanelModel.rowReading(photos))
        XCTAssertTrue(HomeScanPanelModel.rowReading(files))
    }
}
