import XCTest
import LoupeKit
@testable import Loupe

final class FakeConditions: DeviceConditions, @unchecked Sendable {
    private let lock = NSLock()
    private var _low = false
    private var _thermal: ProcessInfo.ThermalState = .nominal
    var isLowPowerMode: Bool { get { lock.withLock { _low } } set { lock.withLock { _low = newValue } } }
    var thermalState: ProcessInfo.ThermalState { get { lock.withLock { _thermal } } set { lock.withLock { _thermal = newValue } } }
}

final class FakeSortTask: SortTask {
    var expirationHandler: (() -> Void)?
    var completed: [Bool] = []
    func setTaskCompleted(success: Bool) { completed.append(success) }
}

final class FakeSortScheduler: SortScheduling {
    var registered: [String] = []
    var handler: ((SortTask) -> Void)?
    var submitted: [SortRequest] = []
    var cancelled: [String] = []
    var failSubmit = false
    func register(_ identifier: String, handler: @escaping (SortTask) -> Void) -> Bool {
        registered.append(identifier); self.handler = handler; return true
    }
    func submit(_ request: SortRequest) throws {
        if failSubmit { throw NSError(domain: "BGTaskSchedulerErrorDomain", code: 1) }
        submitted.append(request)
    }
    func cancel(_ identifier: String) { cancelled.append(identifier) }
}

@MainActor
final class FakeSortRunner: SortRunning {
    var enabled = true
    var outcome: SortService.Outcome = .skipped("nothing")
    var runs: [SortTrigger] = []
    var expired = 0
    var onRun: (() -> Void)?
    func run(_ trigger: SortTrigger) async -> SortService.Outcome { runs.append(trigger); onRun?(); return outcome }
    nonisolated func expire() { MainActor.assumeIsolated { expired += 1 } }
}

@MainActor
final class FakeSortNotifier: SortNotifying {
    var asked = 0
    var posted: [SortRecord] = []
    func requestPermission() async -> Bool { asked += 1; return true }
    func post(_ record: SortRecord) { posted.append(record) }
}

@MainActor
final class BackgroundSorterTests: XCTestCase {
    private func record(_ n: Int, finished: Bool = true) -> SortRecord {
        SortRecord(sorted: n, needYou: 2, findings: 0, finished: finished, stop: nil, at: Date(), trigger: .background)
    }

    func testRegistersAndSchedulesAChargingOnlyOfflineProcessingTask() {
        let s = FakeSortScheduler(), r = FakeSortRunner(), n = FakeSortNotifier()
        let sorter = BackgroundSorter(scheduler: s, runner: r, notifier: n)
        sorter.register()
        XCTAssertEqual(s.registered, [BackgroundSorter.identifier])
        sorter.schedule()
        let req = try! XCTUnwrap(s.submitted.first)
        XCTAssertEqual(req.identifier, "com.loupe-ai.ios.sort")
        XCTAssertTrue(req.requiresExternalPower)
        XCTAssertFalse(req.requiresNetworkConnectivity)
    }

    func testInfoPlistPermitsTheIdentifier() {
        let ids = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        XCTAssertEqual(ids, [BackgroundSorter.identifier])
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        XCTAssertEqual(modes, ["processing"])
    }

    func testOffByDefaultCancelsAndNeverRuns() async {
        let s = FakeSortScheduler(), r = FakeSortRunner(), n = FakeSortNotifier()
        r.enabled = false
        let sorter = BackgroundSorter(scheduler: s, runner: r, notifier: n)
        sorter.schedule()
        XCTAssertTrue(s.submitted.isEmpty)
        XCTAssertEqual(s.cancelled, [BackgroundSorter.identifier])
        let task = FakeSortTask()
        await sorter.handle(task)
        XCTAssertTrue(r.runs.isEmpty)
        XCTAssertEqual(task.completed, [true])
    }

    func testFinishedRunNotifiesReschedulesAndCompletes() async {
        let s = FakeSortScheduler(), r = FakeSortRunner(), n = FakeSortNotifier()
        r.outcome = .finished(record(412))
        let sorter = BackgroundSorter(scheduler: s, runner: r, notifier: n)
        let task = FakeSortTask()
        await sorter.handle(task)
        XCTAssertEqual(r.runs, [.background])
        XCTAssertEqual(s.submitted.count, 1, "the next window is asked for first")
        XCTAssertEqual(n.posted.map(\.line), ["412 sorted, 2 need you"])
        XCTAssertEqual(task.completed, [true])
        XCTAssertNil(task.expirationHandler)
    }

    func testExpirationHandlerStopsTheRunAndTheTaskReportsUnfinished() async {
        let s = FakeSortScheduler(), r = FakeSortRunner(), n = FakeSortNotifier()
        let task = FakeSortTask()
        r.outcome = .stopped(.expired, record(30, finished: false))
        r.onRun = { task.expirationHandler?() } // iOS takes the time back mid-run
        let sorter = BackgroundSorter(scheduler: s, runner: r, notifier: n)
        await sorter.handle(task)
        XCTAssertEqual(r.expired, 1)
        XCTAssertEqual(task.completed, [false])
        XCTAssertEqual(n.posted.count, 1)
    }

    func testSkippedRunCompletesQuietly() async {
        let s = FakeSortScheduler(), r = FakeSortRunner(), n = FakeSortNotifier()
        r.outcome = .skipped("hot")
        let task = FakeSortTask()
        await BackgroundSorter(scheduler: s, runner: r, notifier: n).handle(task)
        XCTAssertTrue(n.posted.isEmpty)
        XCTAssertEqual(task.completed, [true])
    }

    func testPermissionIsAskedOnlyWhenPassiveModeIsTurnedOn() async {
        let s = FakeSortScheduler(), r = FakeSortRunner(), n = FakeSortNotifier()
        let sorter = BackgroundSorter(scheduler: s, runner: r, notifier: n)
        r.enabled = false
        await sorter.settingChanged(on: false)
        XCTAssertEqual(n.asked, 0)
        r.enabled = true
        await sorter.settingChanged(on: true)
        XCTAssertEqual(n.asked, 1)
        XCTAssertEqual(s.submitted.count, 1)
    }

    func testASimulatorSubmitRefusalIsSwallowed() {
        let s = FakeSortScheduler(); s.failSubmit = true
        let sorter = BackgroundSorter(scheduler: s, runner: FakeSortRunner(), notifier: FakeSortNotifier())
        sorter.schedule()
        XCTAssertNotNil(sorter.lastSubmitError)
    }
}

@MainActor
final class SortServiceTests: XCTestCase {
    private var home: URL!
    private var defaults: UserDefaults!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeSort-\(UUID().uuidString)")
        defaults = UserDefaults(suiteName: "sort-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func item(_ id: String, _ text: String) -> SourceItem {
        SourceItem(id: id, sourceId: "sample", kind: .text, path: "/s/\(id)", messageIndex: nil, name: id, text: text,
                   hasText: true, textTruncated: false, sizeBytes: Int64(text.count), contentHash: "h-\(id)",
                   mime: "text/plain", date: nil, dateOrigin: nil, email: nil, facts: [:], duplicateOf: nil)
    }

    private func judgment() -> UserJudgment {
        (JudgmentBook.shared.fromTemplate(templateId: "is-receipt", values: [:], existing: []) as! BookResult.Created).judgment
    }

    private func service(conditions: FakeConditions = FakeConditions(), backend: FakeJudgmentBackend = FakeJudgmentBackend(p: 0.55),
                         lane: ModelLane = ModelLane(), count: Int = 12) -> SortService {
        let items = (0..<count).map { item("i\($0)", $0 % 3 == 0 ? "receipt \($0)" : "note about the payment \($0)") }
        let j = judgment()
        return SortService(ledger: LedgerService(home: home), judgments: { [j] }, items: { items },
                           model: FakeModel(installed: true, backend: backend), conditions: conditions,
                           defaults: defaults, lane: lane)
    }

    /// On by default (owner decision 2026-09-26), still gated by charging, heat and Low Power Mode; a user who
    /// turned it off finds it off after a relaunch (a new service over the same defaults).
    func testOnByDefaultAndAnExplicitOffSurvivesARelaunch() {
        XCTAssertNil(defaults.object(forKey: SortService.enabledKey), "never set")
        let first = service()
        XCTAssertTrue(first.enabled, "never set: on")
        first.enabled = false
        XCTAssertFalse(service().enabled, "set off: stays off after a relaunch")
        service().enabled = true
        XCTAssertTrue(service().enabled)
    }

    func testRunsThenSkipsWhatIsAlreadySorted() async {
        let s = service()
        guard case .finished(let r) = await s.run(.manual) else { return XCTFail("did not finish") }
        XCTAssertEqual(r.sorted, 12)
        XCTAssertEqual(r.needYou, 12, "0.55 answers are below the threshold")
        XCTAssertEqual(s.last, r)
        XCTAssertEqual(s.progress?.total, 12)
        guard case .finished(let again) = await s.run(.manual) else { return XCTFail() }
        XCTAssertEqual(again.sorted, 0)
        XCTAssertEqual(s.progress?.alreadyDecided, 12)
    }

    func testThermalAndLowPowerGateTheRun() async {
        let c = FakeConditions()
        let backend = FakeJudgmentBackend()
        let s = service(conditions: c, backend: backend)
        c.thermalState = .serious
        guard case .skipped = await s.run(.background) else { return XCTFail() }
        c.thermalState = .critical
        guard case .skipped = await s.run(.background) else { return XCTFail() }
        c.thermalState = .fair
        c.isLowPowerMode = true
        guard case .skipped = await s.run(.background) else { return XCTFail() }
        XCTAssertEqual(backend.calls, 0)
    }

    func testHeatMidRunStopsAndTheNextRunResumes() async {
        let c = FakeConditions()
        let backend = FakeJudgmentBackend()
        backend.onScore = { if backend.calls == 5 { c.thermalState = .serious } }
        let s = service(conditions: c, backend: backend)
        guard case .stopped(let why, let r) = await s.run(.background) else { return XCTFail() }
        XCTAssertEqual(why, .thermal)
        XCTAssertEqual(r.sorted, 5)
        c.thermalState = .nominal
        backend.onScore = nil
        guard case .finished(let rest) = await s.run(.background) else { return XCTFail() }
        XCTAssertEqual(rest.sorted, 7, "only what was not sorted before the stop")
        XCTAssertEqual(backend.calls, 12)
    }

    func testExpiryStopsAtTheNextItem() async {
        let backend = FakeJudgmentBackend()
        let s = service(backend: backend)
        backend.onScore = { if backend.calls == 3 { s.expire() ; Thread.sleep(forTimeInterval: 0.2) } }
        guard case .stopped(let why, _) = await s.run(.background) else { return XCTFail() }
        XCTAssertEqual(why, .expired)
        XCTAssertLessThan(backend.calls, 12)
    }

    func testForegroundWorkPreemptsAndTheSortResumesByItself() async {
        let lane = ModelLane()
        let backend = FakeJudgmentBackend()
        var claim: ModelClaim?
        backend.onScore = { if backend.calls == 4 { claim = lane.claim(priority: .foreground) } }
        let s = service(backend: backend, lane: lane)
        guard case .stopped(let why, let r) = await s.run(.manual) else { return XCTFail() }
        XCTAssertEqual(why, .preempted)
        XCTAssertEqual(r.sorted, 4)
        backend.onScore = nil
        claim?.release()
        // The lane's idle signal resumes the run; wait for it to land.
        for _ in 0..<100 where s.last?.finished != true { try? await Task.sleep(nanoseconds: 50_000_000) }
        XCTAssertEqual(s.last?.finished, true)
        XCTAssertEqual(s.last?.sorted, 12, "the card counts the whole run across the pause")
        XCTAssertEqual(backend.calls, 12)
    }

    func testModelMissingSortsNothing() async {
        let j = judgment()
        let s = SortService(ledger: LedgerService(home: home), judgments: { [j] }, items: { [self.item("a", "b")] },
                            model: FakeModel(installed: false), conditions: FakeConditions(), defaults: defaults, lane: ModelLane())
        guard case .skipped(let why) = await s.run(.manual) else { return XCTFail() }
        XCTAssertTrue(why.contains("not installed"))
    }
}
