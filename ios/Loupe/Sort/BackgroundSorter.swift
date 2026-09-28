import BackgroundTasks
import Foundation
import LoupeKit
import UserNotifications

/// A processing request, as `BGProcessingTaskRequest` takes it (a value, so tests can read it).
struct SortRequest: Equatable {
    let identifier: String
    let requiresExternalPower: Bool
    let requiresNetworkConnectivity: Bool
    let earliestBeginDate: Date?
}

/// The one BGTask this handler sees. `BGProcessingTask` in the app; a fake in tests.
protocol SortTask: AnyObject {
    var expirationHandler: (() -> Void)? { get set }
    func setTaskCompleted(success: Bool)
}

/// `BGTaskScheduler`, behind a seam: tests cannot run real background tasks.
protocol SortScheduling: AnyObject {
    @discardableResult func register(_ identifier: String, handler: @escaping (SortTask) -> Void) -> Bool
    func submit(_ request: SortRequest) throws
    func cancel(_ identifier: String)
}

/// What the nightly handler runs: `RunCoordinator` in the app.
@MainActor
protocol NightlyRunning: AnyObject {
    var isRunning: Bool { get }
    func runNightly(resume: RunCheckpoint?) async -> RunRecord
    nonisolated func expire()
}

extension RunCoordinator: NightlyRunning {}

/// The morning notification. Permission is asked in onboarding's permissions step (or when the nightly run is turned
/// on); a denied permission simply means no banner.
@MainActor
protocol NightlyNotifying: AnyObject {
    func requestPermission() async -> Bool
    /// Schedules (or posts now, `at` nil) the one morning summary, replacing an earlier one not shown yet.
    func post(_ body: String, at: DateComponents?)
}

/// The nightly run's settings, in the app's own settings (they are switches, not results).
enum NightlySettings {
    /// "Check overnight while charging": the key of the old "Sort while charging" (on by default since 2026-09-26),
    /// kept so the owner's choice carries over.
    static var enabledKey: String { SortService.enabledKey }
    /// "Tell me in the morning" (on by default; the notification is sent only when the run found something new).
    static let notifyKey = "nightly.notify"

    static func notifies(_ d: UserDefaults = .standard) -> Bool { d.object(forKey: notifyKey) as? Bool ?? true }
}

/// The once-a-day rule and the charging window (owner decision B, 2026-09-28). Pure, so it is tested with any clock.
enum NightlyPolicy {
    enum Decision: Equatable {
        /// Do nothing this time; [why] in words (logged).
        case skip(String)
        /// Run, continuing [resume] when a stopped run left a checkpoint.
        case run(resume: RunCheckpoint?)
    }

    /// The night starts at 22:00 (iOS still picks the moment: charging, idle, usually the small hours).
    static let nightStartHour = 22

    /// The least time between two nightly runs: one a night, even when midnight falls between them.
    static let minimumGap: TimeInterval = 12 * 3600

    /// What a BGTask window that opens at [now] does.
    static func decide(now: Date, state: RunState, blocker: StopReason?, busy: Bool, calendar: Calendar = .current) -> Decision {
        if let b = blocker { return .skip(SortService.words(b)) }
        if busy { return .skip("Another run was going.") }
        if state.nightlyDay == day(now, calendar) { return .skip("Already checked today.") }
        if let at = state.nightlyAt, now.timeIntervalSince(at) < minimumGap { return .skip("Already checked tonight.") }
        return .run(resume: state.checkpoint)
    }

    /// The earliest the next window should open: at night (22:00–06:00), not on a calendar day that already had its
    /// run, and not within 12 hours of the last one. iOS still picks the moment (charging, idle).
    static func earliestBegin(now: Date, state: RunState, calendar: Calendar = .current) -> Date {
        var t = now.addingTimeInterval(15 * 60)
        if let at = state.nightlyAt { t = max(t, at.addingTimeInterval(minimumGap)) }
        if state.nightlyDay == day(now, calendar), let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now)) {
            t = max(t, tomorrow)
        }
        let hour = calendar.component(.hour, from: t)
        if hour >= 6 && hour < nightStartHour {
            t = calendar.date(bySettingHour: nightStartHour, minute: 0, second: 0, of: t) ?? t
        }
        return t
    }

    /// The calendar day of [date] in the phone's time zone, `yyyy-MM-dd`.
    static func day(_ date: Date, _ calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}

/// When and what the morning notification says (owner decision B): "Loupe checked your phone overnight · N new
/// findings", only when the night's run finished and found something new, never when switched off.
enum MorningNotice {
    struct Plan: Equatable {
        let body: String
        /// When to show it (today or tomorrow at 08:00), or nil to show it now.
        let at: DateComponents?
    }

    static let morningHour = 8

    static func plan(_ record: RunRecord, enabled: Bool, now: Date, calendar: Calendar = .current) -> Plan? {
        guard enabled, record.reason == .nightly, record.outcome == .finished else { return nil }
        let n = record.newFindings.total
        guard n > 0 else { return nil }
        let body = "Loupe checked your phone overnight · \(n) new finding\(n == 1 ? "" : "s")"
        let hour = calendar.component(.hour, from: now)
        if hour >= morningHour && hour < 20 { return Plan(body: body, at: nil) }
        let day = hour < morningHour ? now : (calendar.date(byAdding: .day, value: 1, to: now) ?? now)
        var c = calendar.dateComponents([.year, .month, .day], from: day)
        c.hour = morningHour
        c.minute = 0
        return Plan(body: body, at: c)
    }
}

/// The nightly run (owner decision B, 2026-09-28), grown out of F1's "Sort while charging": one `BGProcessingTask`
/// (external power required; network too when Mail is on) that runs, at most once per calendar day, the source
/// rescans, the privacy check, mail triage, the watchers and the sort, through `RunCoordinator`. Its expiration
/// handler stops at the next item; the run is checkpointed per stage and per source and the next night continues
/// it. Heat and Low Power Mode keep it from starting (without using up the day) and stop it between stages. The
/// morning notification says what it found. The class keeps its name and identifier (`com.loupe-ai.ios.sort`, in
/// `project.yml`'s `BGTaskSchedulerPermittedIdentifiers`) so existing installs keep their permission.
@MainActor
final class BackgroundSorter {
    static let identifier = "com.loupe-ai.ios.sort"
    static let shared = BackgroundSorter(
        scheduler: SystemSortScheduler(), runner: RunCoordinator.shared, notifier: SystemNightlyNotifier(),
        store: RunCoordinator.shared.store, conditions: SystemConditions(),
        enabled: { SortService.shared.enabled },
        mailOn: { SourcesService.shared.isPhoneEnabled(.mail) && SourcesService.shared.mailAccount != nil },
        notify: { NightlySettings.notifies() })

    private let scheduler: SortScheduling
    private let runner: NightlyRunning
    private let notifier: NightlyNotifying
    private let store: RunStateStore
    private let conditions: DeviceConditions
    private let enabled: () -> Bool
    private let mailOn: () -> Bool
    private let notify: () -> Bool
    private let clock: () -> Date
    private let calendar: Calendar
    /// The last submit's error (the simulator refuses BGTask submits); shown nowhere, kept for tests.
    private(set) var lastSubmitError: String?
    /// The last request submitted (tests).
    private(set) var lastRequest: SortRequest?

    init(scheduler: SortScheduling, runner: NightlyRunning, notifier: NightlyNotifying, store: RunStateStore,
         conditions: DeviceConditions, enabled: @escaping () -> Bool, mailOn: @escaping () -> Bool,
         notify: @escaping () -> Bool, clock: @escaping () -> Date = Date.init, calendar: Calendar = .current) {
        self.scheduler = scheduler
        self.runner = runner
        self.notifier = notifier
        self.store = store
        self.conditions = conditions
        self.enabled = enabled
        self.mailOn = mailOn
        self.notify = notify
        self.clock = clock
        self.calendar = calendar
    }

    /// Must run before the app finishes launching (`LoupeApp.init`).
    func register() {
        scheduler.register(Self.identifier) { [weak self] task in
            Task { @MainActor in await self?.handle(task) }
        }
    }

    /// Asks iOS for the next night's charging window, or withdraws the ask when the nightly run is off. Cheap: no
    /// work runs here.
    func schedule() {
        guard enabled() else { scheduler.cancel(Self.identifier); lastRequest = nil; return }
        let request = SortRequest(identifier: Self.identifier, requiresExternalPower: true,
                                  requiresNetworkConnectivity: mailOn(),
                                  earliestBeginDate: NightlyPolicy.earliestBegin(now: clock(), state: store.load(), calendar: calendar))
        lastRequest = request
        do {
            try scheduler.submit(request)
            lastSubmitError = nil
        } catch {
            lastSubmitError = error.localizedDescription
        }
    }

    /// The owner flipped "Check overnight while charging".
    func settingChanged(on: Bool) async {
        if on { _ = await notifier.requestPermission() }
        schedule()
    }

    /// The BGTask handler: decide (once a day, not hot, not in Low Power Mode), run or continue, notify, complete,
    /// and ask for the next window.
    func handle(_ task: SortTask) async {
        guard enabled() else {
            schedule()
            task.setTaskCompleted(success: true)
            return
        }
        let now = clock()
        switch NightlyPolicy.decide(now: now, state: store.load(), blocker: conditions.blocker, busy: runner.isRunning, calendar: calendar) {
        case .skip(let why):
            Log.run.info("nightly skipped: \(why, privacy: .public)")
            schedule()
            task.setTaskCompleted(success: true)
        case .run(let resume):
            // The day is used from the moment it starts: at most one nightly run a calendar day (and a night).
            store.update {
                $0.nightlyDay = NightlyPolicy.day(now, calendar)
                $0.nightlyAt = now
            }
            let runner = self.runner
            task.expirationHandler = { runner.expire() }
            let record = await runner.runNightly(resume: resume)
            task.expirationHandler = nil
            if let plan = MorningNotice.plan(record, enabled: notify(), now: clock(), calendar: calendar) {
                notifier.post(plan.body, at: plan.at)
            }
            schedule()
            task.setTaskCompleted(success: record.outcome == .finished)
        }
    }
}

// MARK: - The real system pieces

extension BGProcessingTask: SortTask {}

final class SystemSortScheduler: SortScheduling {
    @discardableResult
    func register(_ identifier: String, handler: @escaping (SortTask) -> Void) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let task = task as? BGProcessingTask else { task.setTaskCompleted(success: false); return }
            handler(task)
        }
    }

    func submit(_ request: SortRequest) throws {
        let r = BGProcessingTaskRequest(identifier: request.identifier)
        r.requiresExternalPower = request.requiresExternalPower
        r.requiresNetworkConnectivity = request.requiresNetworkConnectivity
        r.earliestBeginDate = request.earliestBeginDate
        try BGTaskScheduler.shared.submit(r)
    }

    func cancel(_ identifier: String) { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier) }
}

@MainActor
final class SystemNightlyNotifier: NightlyNotifying {
    static let requestId = "nightly.summary"

    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert])) ?? false
    }

    func post(_ body: String, at: DateComponents?) {
        let content = UNMutableNotificationContent()
        content.body = body
        let trigger = at.map { UNCalendarNotificationTrigger(dateMatching: $0, repeats: false) }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.requestId])
        center.add(UNNotificationRequest(identifier: Self.requestId, content: content, trigger: trigger))
    }
}
