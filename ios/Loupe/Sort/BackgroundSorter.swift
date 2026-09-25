import BackgroundTasks
import Foundation
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

/// What the handler runs. `SortService` in the app.
@MainActor
protocol SortRunning: AnyObject {
    var enabled: Bool { get }
    func run(_ trigger: SortTrigger) async -> SortService.Outcome
    nonisolated func expire()
}

extension SortService: SortRunning {}

/// The summary notification. Permission is asked only when the owner turns passive mode on.
@MainActor
protocol SortNotifying: AnyObject {
    func requestPermission() async -> Bool
    func post(_ record: SortRecord)
}

/// F1 on the iPhone: "Sort while charging". One `BGProcessingTask` (external power required, no
/// network needed) that runs the coordinator; its expiration handler checkpoints and stops (every
/// decided row is already in the ledger). Scheduled only while the setting is on.
@MainActor
final class BackgroundSorter {
    static let identifier = "com.loupe-ai.ios.sort"
    static let shared = BackgroundSorter(scheduler: SystemSortScheduler(), runner: SortService.shared,
                                         notifier: SystemSortNotifier())

    private let scheduler: SortScheduling
    private let runner: SortRunning
    private let notifier: SortNotifying
    /// The last submit's error (the simulator refuses BGTask submits); shown nowhere, kept for tests.
    private(set) var lastSubmitError: String?

    init(scheduler: SortScheduling, runner: SortRunning, notifier: SortNotifying) {
        self.scheduler = scheduler
        self.runner = runner
        self.notifier = notifier
    }

    /// Must run before the app finishes launching (`LoupeApp.init`).
    func register() {
        scheduler.register(Self.identifier) { [weak self] task in
            Task { @MainActor in await self?.handle(task) }
        }
    }

    /// Asks iOS for the next charging window, or withdraws the ask when the setting is off.
    func schedule() {
        guard runner.enabled else { scheduler.cancel(Self.identifier); return }
        do {
            try scheduler.submit(SortRequest(identifier: Self.identifier, requiresExternalPower: true,
                                             requiresNetworkConnectivity: false,
                                             earliestBeginDate: Date(timeIntervalSinceNow: 15 * 60)))
            lastSubmitError = nil
        } catch {
            lastSubmitError = error.localizedDescription
        }
    }

    /// The owner flipped "Sort while charging".
    func settingChanged(on: Bool) async {
        if on { _ = await notifier.requestPermission() }
        schedule()
    }

    /// The BGTask handler: schedule the next window, run, notify, complete.
    func handle(_ task: SortTask) async {
        schedule()
        guard runner.enabled else { task.setTaskCompleted(success: true); return }
        let runner = self.runner
        task.expirationHandler = { runner.expire() }
        let outcome = await runner.run(.background)
        task.expirationHandler = nil
        switch outcome {
        case .finished(let record):
            if record.sorted > 0 || record.findings > 0 { notifier.post(record) }
            task.setTaskCompleted(success: true)
        case .stopped(_, let record):
            // Checkpointed: the next window resumes. Say what was done if anything was.
            if record.sorted > 0 { notifier.post(record) }
            task.setTaskCompleted(success: false)
        case .skipped, .busy:
            task.setTaskCompleted(success: true)
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
final class SystemSortNotifier: SortNotifying {
    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert])) ?? false
    }

    func post(_ record: SortRecord) {
        let content = UNMutableNotificationContent()
        content.title = "Loupe sorted while charging"
        content.body = record.line + (record.finished ? "." : " so far; it carries on next time.")
        let request = UNNotificationRequest(identifier: "sort.summary", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
