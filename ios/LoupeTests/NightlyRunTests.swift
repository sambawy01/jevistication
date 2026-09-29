import XCTest
import LoupeKit
@testable import Loupe

@MainActor
final class FakeNightlyNotifier: NightlyNotifying {
    var asked = 0
    var posted: [(body: String, at: DateComponents?)] = []
    func requestPermission() async -> Bool { asked += 1; return true }
    func post(_ body: String, at: DateComponents?) { posted.append((body, at)) }
}

/// The nightly run (owner decision B, 2026-09-28) with a fake BGTask, a fake scheduler and an injectable clock: its
/// order, its checkpoint and resume, the once-a-day rule, heat and Low Power, and the morning notification.
@MainActor
final class NightlyRunTests: XCTestCase {
    private var home: URL!
    private var now: Date!
    private let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/London")!
        return c
    }()

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeNightly-\(UUID().uuidString)")
        now = date(2026, 9, 28, 2, 30)
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }

    private struct Rig {
        let work: FakeRunWork
        let coordinator: RunCoordinator
        let scheduler: FakeSortScheduler
        let notifier: FakeNightlyNotifier
        let conditions: FakeConditions
        let sorter: BackgroundSorter
        let store: RunStateStore
    }

    private func rig(enabled: Bool = true, mail: Bool = false, notify: Bool = true) -> Rig {
        let work = FakeRunWork()
        let store = RunStateStore(home: home)
        let c = RunCoordinator(work: work, store: store, clock: { [unowned self] in self.now }, publishInterval: 0)
        let scheduler = FakeSortScheduler(), notifier = FakeNightlyNotifier(), conditions = FakeConditions()
        let sorter = BackgroundSorter(scheduler: scheduler, runner: c, notifier: notifier, store: store, conditions: conditions,
                                      enabled: { enabled }, mailOn: { mail }, notify: { notify },
                                      clock: { [unowned self] in self.now }, calendar: calendar)
        return Rig(work: work, coordinator: c, scheduler: scheduler, notifier: notifier, conditions: conditions, sorter: sorter, store: store)
    }

    // MARK: Scheduling

    func testRegistersAndAsksForTonightsChargingWindow() throws {
        now = date(2026, 9, 28, 10)
        let r = rig()
        r.sorter.register()
        XCTAssertEqual(r.scheduler.registered, [BackgroundSorter.identifier])
        r.sorter.schedule()
        let req = try XCTUnwrap(r.scheduler.submitted.last)
        XCTAssertEqual(req.identifier, "com.loupe-ai.ios.sort")
        XCTAssertTrue(req.requiresExternalPower)
        XCTAssertFalse(req.requiresNetworkConnectivity, "no Mail, no network")
        XCTAssertEqual(req.earliestBeginDate, date(2026, 9, 28, 22), "tonight")
        let withMail = rig(mail: true)
        withMail.sorter.schedule()
        XCTAssertEqual(withMail.scheduler.submitted.last?.requiresNetworkConnectivity, true, "Mail on: the run needs the network")
    }

    func testInfoPlistPermitsTheIdentifier() {
        let ids = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        XCTAssertEqual(ids, [BackgroundSorter.identifier])
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        XCTAssertEqual(modes, ["processing"])
    }

    func testOffCancelsTheRequestAndNeverRuns() async {
        let r = rig(enabled: false)
        r.sorter.schedule()
        XCTAssertTrue(r.scheduler.submitted.isEmpty)
        XCTAssertEqual(r.scheduler.cancelled, [BackgroundSorter.identifier])
        let task = FakeSortTask()
        await r.sorter.handle(task)
        XCTAssertTrue(r.work.log.isEmpty)
        XCTAssertEqual(task.completed, [true])
    }

    func testPermissionIsAskedOnlyWhenTheNightlyRunIsTurnedOn() async {
        let r = rig()
        await r.sorter.settingChanged(on: false)
        XCTAssertEqual(r.notifier.asked, 0)
        await r.sorter.settingChanged(on: true)
        XCTAssertEqual(r.notifier.asked, 1)
    }

    func testASimulatorSubmitRefusalIsSwallowed() {
        let r = rig()
        r.scheduler.failSubmit = true
        r.sorter.schedule()
        XCTAssertNotNil(r.sorter.lastSubmitError)
    }

    // MARK: The run

    func testTheNightlyRunDoesEveryStageInOrderThenAsksForTheNextNight() async throws {
        let r = rig()
        let task = FakeSortTask()
        await r.sorter.handle(task)
        XCTAssertEqual(r.work.log, ["scan:files", "scan:photos", "privacy", "mail", "watchers", "sort"])
        XCTAssertEqual(r.work.overnightScans, ["files", "photos"], "the nightly run scans as overnight (Mail drains its queue)")
        XCTAssertEqual(task.completed, [true])
        XCTAssertNil(task.expirationHandler)
        let state = r.store.load()
        XCTAssertEqual(state.nightlyDay, "2026-09-28")
        XCTAssertNil(state.checkpoint)
        let record = try XCTUnwrap(state.lastNightly)
        XCTAssertEqual(record.reason, .nightly)
        XCTAssertEqual(record.outcome, .finished)
        XCTAssertEqual(record.stagesRun, RunStage.allCases)
        XCTAssertEqual(r.coordinator.lastNightly, record)
        XCTAssertEqual(r.scheduler.submitted.last?.earliestBeginDate, date(2026, 9, 29, 0),
                       "ran today: the next window opens with the next calendar day")
    }

    func testAtMostOnceACalendarDay() async {
        let r = rig()
        await r.sorter.handle(FakeSortTask())
        XCTAssertEqual(r.work.log.count, 6)
        now = date(2026, 9, 28, 4)
        let second = FakeSortTask()
        await r.sorter.handle(second)
        XCTAssertEqual(r.work.log.count, 6, "same day: nothing runs")
        XCTAssertEqual(second.completed, [true])
        now = date(2026, 9, 29, 1)
        await r.sorter.handle(FakeSortTask())
        XCTAssertEqual(r.work.log.count, 12, "the next day it runs again")
        XCTAssertEqual(r.store.load().nightlyDay, "2026-09-29")
    }

    func testAnExpiredRunIsCheckpointedAndTheNextNightContinuesIt() async throws {
        let r = rig()
        r.work.newFindings = ["privacy": 2, "watchers": 1]
        let task = FakeSortTask()
        // iOS takes the time back during mail triage.
        r.work.onStage["mail"] = { task.expirationHandler?() }
        await r.sorter.handle(task)
        XCTAssertEqual(r.work.log, ["scan:files", "scan:photos", "privacy", "mail"])
        XCTAssertEqual(task.completed, [false])
        XCTAssertEqual(r.work.stopped, [.expired])
        let cp = try XCTUnwrap(r.store.load().checkpoint)
        XCTAssertEqual(cp.stagesDone, [.sources, .privacy])
        XCTAssertEqual(cp.sourcesDone, ["files", "photos"])
        XCTAssertEqual(cp.newFindings.privacy, 2)
        XCTAssertEqual(r.store.load().lastNightly?.outcome, .expired)
        XCTAssertTrue(r.notifier.posted.isEmpty, "no notification for a run that did not finish")

        // The next night: it continues where it stopped, and the record covers the whole run.
        r.work.onStage = [:]
        r.work.log = []
        now = date(2026, 9, 29, 3)
        let next = FakeSortTask()
        await r.sorter.handle(next)
        XCTAssertEqual(r.work.log, ["mail", "watchers", "sort"])
        XCTAssertEqual(next.completed, [true])
        let record = try XCTUnwrap(r.store.load().lastNightly)
        XCTAssertEqual(record.id, cp.runId, "the same run, continued")
        XCTAssertEqual(record.outcome, .finished)
        XCTAssertEqual(record.counts[.sources], 15, "the first night's reading counts")
        XCTAssertEqual(record.newFindings.total, 3)
        XCTAssertNil(r.store.load().checkpoint)
        XCTAssertEqual(r.notifier.posted.map(\.body), ["Loupe checked your phone overnight · 3 new findings"])
    }

    func testExpiredWhileReadingTheSourcesResumesAtTheNextSource() async throws {
        let r = rig()
        let task = FakeSortTask()
        r.work.onStage["scan:photos"] = { task.expirationHandler?() }
        await r.sorter.handle(task)
        XCTAssertEqual(try XCTUnwrap(r.store.load().checkpoint).sourcesDone, ["files"])
        r.work.onStage = [:]
        r.work.log = []
        now = date(2026, 9, 29, 3)
        await r.sorter.handle(FakeSortTask())
        XCTAssertEqual(r.work.log, ["scan:photos", "privacy", "mail", "watchers", "sort"], "Files is not read twice")
    }

    func testHeatAndLowPowerKeepItFromStartingWithoutUsingTheDay() async {
        let r = rig()
        r.conditions.thermalState = .serious
        let task = FakeSortTask()
        await r.sorter.handle(task)
        XCTAssertTrue(r.work.log.isEmpty)
        XCTAssertEqual(task.completed, [true])
        XCTAssertNil(r.store.load().nightlyDay, "the day is not used up")
        r.conditions.thermalState = .nominal
        r.conditions.isLowPowerMode = true
        await r.sorter.handle(FakeSortTask())
        XCTAssertTrue(r.work.log.isEmpty)
        r.conditions.isLowPowerMode = false
        now = date(2026, 9, 28, 4)
        await r.sorter.handle(FakeSortTask())
        XCTAssertEqual(r.work.log.count, 6, "later that night, cool and charging: it runs")
    }

    func testHeatMidRunStopsBetweenStagesAndTheRestRunsNextTime() async throws {
        let r = rig()
        r.work.onStage["privacy"] = { r.work.blocker = .thermal }
        let task = FakeSortTask()
        await r.sorter.handle(task)
        XCTAssertEqual(r.work.log, ["scan:files", "scan:photos", "privacy"], "privacy finishes, mail never starts")
        XCTAssertEqual(task.completed, [false])
        let record = try XCTUnwrap(r.store.load().lastNightly)
        XCTAssertEqual(record.outcome, .stopped)
        XCTAssertEqual(try XCTUnwrap(r.store.load().checkpoint).stagesDone, [.sources, .privacy])
        r.work.blocker = nil
        r.work.log = []
        r.work.onStage = [:]
        now = date(2026, 9, 29, 2)
        await r.sorter.handle(FakeSortTask())
        XCTAssertEqual(r.work.log, ["mail", "watchers", "sort"])
    }

    func testAManualFullRunClearsAStoppedNightsCheckpoint() async throws {
        let r = rig()
        let task = FakeSortTask()
        r.work.onStage["mail"] = { task.expirationHandler?() }
        await r.sorter.handle(task)
        XCTAssertNotNil(r.store.load().checkpoint)
        r.work.onStage = [:]
        await r.coordinator.run(.manual)
        XCTAssertNil(r.store.load().checkpoint, "Run now did everything the night had left")
    }

    // MARK: The morning notification

    func testTheMorningNotificationIsSentOnlyWhenTheNightFoundSomethingNew() async {
        let quiet = rig()
        await quiet.sorter.handle(FakeSortTask())
        XCTAssertTrue(quiet.notifier.posted.isEmpty, "nothing new: no notification")

        let found = rig()
        found.work.newFindings = ["mail": 1]
        now = date(2026, 9, 30, 2, 10)
        await found.sorter.handle(FakeSortTask())
        XCTAssertEqual(found.notifier.posted.count, 1)
        XCTAssertEqual(found.notifier.posted.first?.body, "Loupe checked your phone overnight · 1 new finding")
        XCTAssertEqual(found.notifier.posted.first?.at, DateComponents(year: 2026, month: 9, day: 30, hour: 8, minute: 0), "in the morning")

        let off = rig(notify: false)
        off.work.newFindings = ["mail": 1]
        now = date(2026, 10, 1, 2)
        await off.sorter.handle(FakeSortTask())
        XCTAssertTrue(off.notifier.posted.isEmpty, "switched off in Me")
    }

    func testTheMorningNoticeDecision() {
        func record(_ new: Int, outcome: RunOutcome = .finished, reason: RunReason = .nightly) -> RunRecord {
            RunRecord(id: UUID(), reason: reason, startedAt: now, endedAt: now, stagesRun: RunStage.allCases, counts: [:],
                      newFindings: RunFindings(privacy: new, mail: 0, watchers: 0), outcome: outcome, note: nil)
        }
        let night = date(2026, 9, 28, 3)
        XCTAssertEqual(MorningNotice.plan(record(3), enabled: true, now: night, calendar: calendar),
                       .init(body: "Loupe checked your phone overnight · 3 new findings",
                             at: DateComponents(year: 2026, month: 9, day: 28, hour: 8, minute: 0)))
        XCTAssertEqual(MorningNotice.plan(record(2), enabled: true, now: date(2026, 9, 28, 23, 40), calendar: calendar)?.at,
                       DateComponents(year: 2026, month: 9, day: 29, hour: 8, minute: 0), "late evening: the next morning")
        XCTAssertNil(MorningNotice.plan(record(2), enabled: true, now: date(2026, 9, 28, 9, 30), calendar: calendar)?.at,
                     "finished after 08:00: now")
        XCTAssertNil(MorningNotice.plan(record(0), enabled: true, now: night, calendar: calendar))
        XCTAssertNil(MorningNotice.plan(record(3), enabled: false, now: night, calendar: calendar))
        XCTAssertNil(MorningNotice.plan(record(3, outcome: .expired), enabled: true, now: night, calendar: calendar))
        XCTAssertNil(MorningNotice.plan(record(3, reason: .manual), enabled: true, now: night, calendar: calendar))
    }

    func testTheWindowPolicy() {
        var state = RunState()
        XCTAssertEqual(NightlyPolicy.earliestBegin(now: date(2026, 9, 28, 14), state: state, calendar: calendar), date(2026, 9, 28, 22))
        XCTAssertEqual(NightlyPolicy.earliestBegin(now: date(2026, 9, 28, 23), state: state, calendar: calendar),
                       date(2026, 9, 28, 23, 15), "already night: soon")
        XCTAssertEqual(NightlyPolicy.earliestBegin(now: date(2026, 9, 28, 3), state: state, calendar: calendar),
                       date(2026, 9, 28, 3, 15), "before dawn: soon")
        state.nightlyDay = "2026-09-28"
        state.nightlyAt = date(2026, 9, 28, 3)
        XCTAssertEqual(NightlyPolicy.earliestBegin(now: date(2026, 9, 28, 3), state: state, calendar: calendar), date(2026, 9, 29, 0),
                       "the next calendar day, at night")
        var late = RunState()
        late.nightlyDay = "2026-09-28"
        late.nightlyAt = date(2026, 9, 28, 23)
        XCTAssertEqual(NightlyPolicy.earliestBegin(now: date(2026, 9, 28, 23), state: late, calendar: calendar), date(2026, 9, 29, 22),
                       "a late run: not again after midnight the same night, but the next night")
        XCTAssertEqual(NightlyPolicy.decide(now: date(2026, 9, 29, 0, 30), state: late, blocker: nil, busy: false, calendar: calendar),
                       .skip("Already checked tonight."))
        XCTAssertEqual(NightlyPolicy.decide(now: date(2026, 9, 28, 23), state: state, blocker: nil, busy: false, calendar: calendar),
                       .skip("Already checked today."))
        XCTAssertEqual(NightlyPolicy.decide(now: date(2026, 9, 29, 0, 5), state: state, blocker: nil, busy: false, calendar: calendar),
                       .run(resume: nil))
        XCTAssertEqual(NightlyPolicy.decide(now: date(2026, 9, 29, 0, 5), state: state, blocker: nil, busy: true, calendar: calendar),
                       .skip("Another run was going."))
    }
}
