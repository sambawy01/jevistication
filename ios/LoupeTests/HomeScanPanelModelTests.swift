import XCTest
@testable import Loupe

/// Home's scan panel (owner rulings O-5, C-16): driven by `RunCoordinator.current` through the pure adapter
/// `HomeScanPanelModel.progress`; every real source with its switch; idle, the last run in one line. Never the sample
/// (owner ruling O-6).
@MainActor
final class HomeScanPanelModelTests: XCTestCase {
    private func run(_ reason: RunReason = .manual, stage: RunStage, part: String? = nil, item: String? = nil,
                     counts: [RunStage: RunStageCount] = [:], rate: Double? = nil, eta: TimeInterval? = nil,
                     cancelling: Bool = false) -> RunProgress {
        RunProgress(id: UUID(), reason: reason, startedAt: Date(), stages: reason.stages, stage: stage, part: part, item: item,
                    counts: counts, rate: rate, eta: eta, cancelling: cancelling)
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

    // MARK: The adapter: RunProgress → HomeScanProgress

    func testTheSourcesStageNamesTheSourceItReadsAndItsRowMovesLive() {
        let p = HomeScanPanelModel.progress(run(stage: .sources, part: "Photos · 2 of 3", item: "IMG_4107.JPG",
                                                counts: [.sources: RunStageCount(done: 120, total: 300, finished: false)],
                                                rate: 8, eta: 20))
        XCTAssertEqual(p.reason, "Run now · step 1 of 5")
        XCTAssertEqual(p.stage, "Reading your sources · Photos · 2 of 3")
        XCTAssertEqual(p.currentItem, "IMG_4107.JPG", "the live scan's masked name, as given")
        XCTAssertEqual(p.reading, HomeScanProgress.Stage(id: "photos", name: "Photos", done: 120, total: 300))
        XCTAssertEqual(p.fraction, 0.4)
        XCTAssertEqual(p.counts, "120 of 300 items · \(ScanSnapshot.rateText(8))/s · about \(ScanSnapshot.duration(20)) left")
        XCTAssertFalse(p.cancelling)
        // Its row: "Reading · 120 of 300" while its source is read.
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: p.reading, on: true, restingCount: 9), "Reading · 120 of 300")
    }

    func testEveryStageOfTheRunIsListedWithWhereItGot() {
        let p = HomeScanPanelModel.progress(run(stage: .privacy,
                                                counts: [.sources: RunStageCount(done: 48, total: 48, finished: true),
                                                         .privacy: RunStageCount(done: 12, total: 40, finished: false)]))
        XCTAssertEqual(p.stages.map(\.id), ["sources", "privacy", "mail", "watchers", "sort"])
        XCTAssertEqual(p.stages.map(\.name), ["Reading your sources", "Privacy check", "Mail triage", "Watchers", "Sorting"])
        XCTAssertEqual(p.stages.map(HomeScanPanelModel.stageLine), ["Done · 48", "12 of 40", "Waiting", "Waiting", "Waiting"])
        XCTAssertEqual(p.stages.map(\.running), [false, true, false, false, false])
        XCTAssertEqual(p.stage, "Privacy check", "main's run.stage label: a UI test waits for BEGINSWITH \"Privacy check\"")
        XCTAssertEqual(p.reason, "Run now · step 2 of 5")
        XCTAssertEqual(p.counts, "12 of 40 items")
        XCTAssertEqual(p.fraction, 0.3)
        XCTAssertNil(p.reading, "no source is read outside the sources stage")
        XCTAssertNil(p.currentItem)
    }

    func testAStageThatHasNotCountedYetSaysStarting() {
        let p = HomeScanPanelModel.progress(run(.itemsChanged, stage: .privacy,
                                                counts: [.privacy: RunStageCount(done: 0, total: nil, finished: false)]))
        XCTAssertEqual(p.counts, "Starting")
        XCTAssertNil(p.fraction)
        XCTAssertEqual(p.reason, "Re-check · step 1 of 3")
        XCTAssertEqual(p.stages.map(\.id), ["privacy", "mail", "watchers"])
        let unknownTotal = HomeScanPanelModel.progress(run(stage: .mail, counts: [.mail: RunStageCount(done: 7, total: nil, finished: false)]))
        XCTAssertEqual(unknownTotal.counts, "7 emails")
        XCTAssertEqual(HomeScanPanelModel.progress(run(stage: .watchers, item: "")).currentItem, nil, "an empty name is no item")
    }

    func testTheFixtureAndUnknownPartsReadNoPhoneSource() {
        let fixture = HomeScanPanelModel.progress(run(stage: .sources, part: "Test fixture · 1 of 4",
                                                      counts: [.sources: RunStageCount(done: 3, total: 48, finished: false)]))
        XCTAssertEqual(fixture.reading?.id, SourcesService.sampleId)
        XCTAssertFalse(HomeScanPanelModel.sources(inboxHasImports: true).contains { $0.id == fixture.reading?.id },
                       "the fixture has no row in the panel")
        XCTAssertNil(HomeScanPanelModel.sourceId(part: nil))
        XCTAssertNil(HomeScanPanelModel.sourceId(part: "Expiry radar"))
        XCTAssertEqual(HomeScanPanelModel.sourceId(part: "Contacts · 3 of 3"), "contacts")
        XCTAssertEqual(HomeScanPanelModel.sourceId(part: "Mail"), "mail")
        // A source whose scan has finished (the stage moves to the next source) reads no longer.
        let between = HomeScanPanelModel.progress(run(stage: .sources, part: "Files · 1 of 2",
                                                      counts: [.sources: RunStageCount(done: 9, total: 9, finished: true)]))
        XCTAssertNil(between.reading)
    }

    func testCancellingIsCarried() {
        XCTAssertTrue(HomeScanPanelModel.progress(run(stage: .watchers, cancelling: true)).cancelling)
    }

    // MARK: The panel's state

    func testTheStateFollowsTheRunTheLoadAndTheResults() {
        typealias M = HomeScanPanelModel
        XCTAssertEqual(M.state(running: true, loaded: false, checked: false, hasLast: false), .running, "a run shows at once")
        XCTAssertEqual(M.state(running: false, loaded: false, checked: false, hasLast: true), .loading)
        XCTAssertEqual(M.state(running: false, loaded: true, checked: false, hasLast: false), .notChecked)
        XCTAssertEqual(M.state(running: false, loaded: true, checked: false, hasLast: true), .notChecked,
                       "a cancelled run left no watchers' results: still not checked")
        XCTAssertEqual(M.state(running: false, loaded: true, checked: true, hasLast: true), .last)
        XCTAssertEqual(M.state(running: false, loaded: true, checked: true, hasLast: false), .hidden)
    }

    func testTheLastRunsTitleSaysWhatStartedIt() {
        func record(_ reason: RunReason) -> RunRecord {
            RunRecord(id: UUID(), reason: reason, startedAt: Date(), endedAt: Date(), stagesRun: [], counts: [:],
                      newFindings: RunFindings(), outcome: .finished, note: nil)
        }
        XCTAssertTrue(HomeScanPanelModel.lastTitle(record(.manual)).hasPrefix("Checked on request · "))
        XCTAssertTrue(HomeScanPanelModel.lastTitle(record(.firstCheck)).hasPrefix("First check · "))
        XCTAssertTrue(HomeScanPanelModel.lastTitle(record(.nightly)).hasPrefix("Overnight check · "))
        XCTAssertEqual(HomeScanPanelModel.lastWhat(.scanAgain("photos")), "Scan again")
        XCTAssertEqual(HomeScanPanelModel.lastWhat(.itemsChanged), "Re-check")
    }

    // MARK: Rows

    func testARowReadsOnlyWhileItsSourceIsRead() {
        let reading = HomeScanProgress.Stage(id: "files", name: "Files", done: 1, total: 2)
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: reading, on: true, restingCount: 0), "Reading · 1 of 2")
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: nil, on: true, restingCount: 9), "9 items")
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: nil, on: true, restingCount: 1), "1 item")
        XCTAssertEqual(HomeScanPanelModel.rowLine(stage: nil, on: false, restingCount: 9), "Off")
        XCTAssertTrue(HomeScanPanelModel.rowReading(reading))
        XCTAssertFalse(HomeScanPanelModel.rowReading(nil))
        XCTAssertEqual(HomeScanPanelModel.stageCount(HomeScanProgress.Stage(id: "x", name: "X", done: 4, total: nil)), "4")
    }
}
