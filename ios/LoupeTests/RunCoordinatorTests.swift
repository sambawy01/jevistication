import XCTest
import LoupeKit
@testable import Loupe

/// A `RunWork` that records what ran, in order, and can stop a stage the way the real ones do (between items, on the
/// run's cancel flag). No scanning, no checks, no model.
@MainActor
final class FakeRunWork: RunWork {
    var sources = ["files", "photos"]
    var log: [String] = []
    /// Called as a stage starts ("scan:files", "privacy", …), before it looks at the cancel flag.
    var onStage: [String: () -> Void] = [:]
    var counts: [String: Int] = ["scan:files": 10, "scan:photos": 5, "privacy": 15, "mail": 3, "watchers": 15, "sort": 12]
    var newFindings: [String: Int] = [:]
    var blocker: StopReason?
    /// Stages that report progress this many times (items), with a yield between.
    var items = 3
    var stopped: [RunOutcome] = []

    func sourceIds(only: String?) -> [String] { only.map { sources.contains($0) ? [$0] : [] } ?? sources }
    func sourceTitle(_ id: String) -> String { id.capitalized }

    private func stage(_ name: String, cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        log.append(name)
        onStage[name]?()
        for i in 0..<items {
            // A few turns for an expiry or Cancel sent through the main actor to land.
            for _ in 0..<5 { await Task.yield() }
            if cancel.isCancelled { return RunStageResult(count: i, completed: false) }
            report.report(done: i + 1, total: items, item: "\(name) item \(i + 1)")
        }
        return RunStageResult(count: counts[name] ?? 0, newFindings: newFindings[name] ?? 0)
    }

    func scan(source: String, cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        await stage("scan:\(source)", cancel: cancel, report: report)
    }
    func privacy(cancel: RunCancel, report: RunReporter) async -> RunStageResult { await stage("privacy", cancel: cancel, report: report) }
    func mail(cancel: RunCancel, report: RunReporter) async -> RunStageResult { await stage("mail", cancel: cancel, report: report) }
    func watchers(cancel: RunCancel, report: RunReporter) async -> RunStageResult { await stage("watchers", cancel: cancel, report: report) }
    func sort(trigger: SortTrigger, cancel: RunCancel, report: RunReporter) async -> RunStageResult {
        await stage("sort", cancel: cancel, report: report)
    }
    func stop(_ reason: RunOutcome) { stopped.append(reason) }
}

@MainActor
final class RunCoordinatorTests: XCTestCase {
    private var home: URL!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeRun-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func coordinator(_ work: FakeRunWork) -> RunCoordinator {
        RunCoordinator(work: work, store: RunStateStore(home: home), publishInterval: 0)
    }

    func testRunNowRunsEveryStageInOrderAndSavesTheRecord() async throws {
        let work = FakeRunWork()
        work.newFindings = ["privacy": 2, "watchers": 1]
        let c = coordinator(work)
        let ran = await c.run(.manual)
        let record = try XCTUnwrap(ran)
        XCTAssertEqual(work.log, ["scan:files", "scan:photos", "privacy", "mail", "watchers", "sort"])
        XCTAssertEqual(record.outcome, .finished)
        XCTAssertEqual(record.stagesRun, RunStage.allCases)
        XCTAssertEqual(record.counts[.sources], 15, "items read across the sources")
        XCTAssertEqual(record.counts[.sort], 12)
        XCTAssertEqual(record.newFindings, RunFindings(privacy: 2, mail: 0, watchers: 1))
        XCTAssertNil(c.current)
        XCTAssertEqual(c.last, record)
        // Saved: a new coordinator over the same home (a relaunch) shows it without running anything.
        let again = coordinator(FakeRunWork())
        XCTAssertEqual(again.last?.id, record.id)
        XCTAssertEqual(again.last?.counts, record.counts)
        XCTAssertEqual(again.last?.stagesRun, record.stagesRun)
        XCTAssertEqual(again.last?.newFindings, record.newFindings)
        XCTAssertEqual(again.last?.outcome, .finished)
    }

    func testScanAgainReadsThatSourceThenTheChecksWithoutTheSort() async {
        let work = FakeRunWork()
        let c = coordinator(work)
        await c.run(.scanAgain("photos"))
        XCTAssertEqual(work.log, ["scan:photos", "privacy", "mail", "watchers"])
        work.log = []
        await c.run(.itemsChanged)
        XCTAssertEqual(work.log, ["privacy", "mail", "watchers"])
    }

    func testProgressIsPublishedWhileItRunsAndClearedAfter() async {
        let work = FakeRunWork()
        let c = coordinator(work)
        var seen: [RunProgress] = []
        let sub = c.$current.sink { if let p = $0 { seen.append(p) } }
        await c.run(.manual)
        sub.cancel()
        XCTAssertNil(c.current)
        XCTAssertEqual(Set(seen.map(\.stage)), Set(RunStage.allCases), "every stage showed")
        let privacy = seen.last { $0.stage == .privacy && $0.count?.done == 3 }
        XCTAssertEqual(privacy?.count?.total, 3)
        XCTAssertEqual(privacy?.item, "privacy item 3")
        XCTAssertTrue(seen.contains { $0.stage == .sources && ($0.part ?? "").hasPrefix("Photos · 2 of 2") })
        XCTAssertTrue(seen.allSatisfy { $0.reason == .manual && $0.stages == RunStage.allCases })
    }

    func testCancelStopsTheStageAndSkipsTheRest() async throws {
        let work = FakeRunWork()
        let c = coordinator(work)
        work.onStage["mail"] = { c.cancel() }
        let ran = await c.run(.manual)
        let record = try XCTUnwrap(ran)
        XCTAssertEqual(work.log, ["scan:files", "scan:photos", "privacy", "mail"], "the watchers and the sort never started")
        XCTAssertEqual(record.outcome, .cancelled)
        XCTAssertEqual(record.stagesRun, [.sources, .privacy])
        XCTAssertEqual(work.stopped, [.cancelled], "the sort's own coordinator is told too")
        XCTAssertNil(c.current)
        XCTAssertNil(RunStateStore(home: home).load().checkpoint, "a cancelled run by hand leaves no checkpoint")
    }

    func testARunAskedForWhileOneGoesRunsAfterItOnceAndACoveredOneIsDropped() async {
        let work = FakeRunWork()
        let c = coordinator(work)
        var asked: [Task<RunRecord?, Never>] = []
        work.onStage["privacy"] = {
            work.onStage["privacy"] = nil   // once
            // Mid-run: a re-check (covered by nothing yet queued), then a Scan again that covers it.
            asked.append(Task { await c.run(.itemsChanged) })
            asked.append(Task { await c.run(.scanAgain("files")) })
        }
        await c.run(.manual)
        for t in asked { _ = await t.value }
        XCTAssertEqual(work.log, ["scan:files", "scan:photos", "privacy", "mail", "watchers", "sort",
                                  "scan:files", "privacy", "mail", "watchers"],
                       "one follow-up run: the Scan again, which covers the re-check")
    }

    func testAFullRunStillReadingTheSourcesCoversARecheck() async {
        let work = FakeRunWork()
        let c = coordinator(work)
        var later: Task<RunRecord?, Never>?
        work.onStage["scan:files"] = {
            work.onStage["scan:files"] = nil
            later = Task { await c.run(.itemsChanged) }
        }
        let record = await c.run(.manual)
        let covered = await later?.value
        XCTAssertEqual(work.log.filter { $0 == "privacy" }.count, 1, "no second run")
        XCTAssertEqual(covered?.id, record?.id)
    }

    func testTheFirstCheckAfterOnboardingRunsExactlyOnce() async throws {
        let work = FakeRunWork()
        let c = coordinator(work)
        XCTAssertFalse(c.firstCheckDone)
        c.firstCheckAfterOnboarding()
        c.firstCheckAfterOnboarding()
        try await waitUntil { c.last != nil && !c.isRunning }
        XCTAssertEqual(c.last?.reason, .firstCheck)
        XCTAssertEqual(work.log.filter { $0 == "sort" }.count, 1)
        XCTAssertTrue(c.firstCheckDone)
        // A relaunch: the record says it ran; onboarding completing again (it cannot, but a stray call) runs nothing.
        let work2 = FakeRunWork()
        let again = coordinator(work2)
        again.firstCheckAfterOnboarding()
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(work2.log.isEmpty)
        XCTAssertFalse(again.isRunning)
    }

    func testCreatingTheCoordinatorRunsNothing() async {
        // The no-launch-work guarantee at this level: building the coordinator (as the app does at launch) reads the
        // saved state and starts no stage.
        let work = FakeRunWork()
        let c = coordinator(work)
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(work.log.isEmpty)
        XCTAssertNil(c.current)
    }

    private func waitUntil(_ f: @escaping @MainActor () -> Bool, timeout: TimeInterval = 5) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !f() {
            if Date() > end { XCTFail("timed out"); return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }
}
