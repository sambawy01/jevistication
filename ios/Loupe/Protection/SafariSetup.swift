import Combine
import os
import SafariServices
import SwiftUI
import UserNotifications

/// Safari's settings behind a small protocol, so the turn-on flow is tested without Safari.
protocol SafariExtensionControl {
    /// iOS 26.2+: Loupe can read the extension's state and open Safari's settings at it.
    var canReadAndOpen: Bool { get }
    /// Whether Loupe for Safari is on; nil when this iOS cannot say.
    func isEnabled() async throws -> Bool?
    /// Opens Safari's extension settings at Loupe.
    func openSettings() async throws
}

/// The real one: `SFSafariExtensionManager` and `SFSafariSettings` (iOS 26.2+). Older iOS: neither.
struct SystemSafariExtensionControl: SafariExtensionControl {
    let id: String

    init(id: String = ProtectionGroup.safariExtensionId) { self.id = id }

    var canReadAndOpen: Bool {
        if #available(iOS 26.2, *) { return true }
        return false
    }

    func isEnabled() async throws -> Bool? {
        guard #available(iOS 26.2, *) else { return nil }
        return try await SFSafariExtensionManager.stateOfExtension(withIdentifier: id).isEnabled
    }

    func openSettings() async throws {
        guard #available(iOS 26.2, *) else { throw SafariSetup.Unavailable() }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            SFSafariSettings.openExtensionsSettings(forIdentifiers: [id]) { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            }
        }
    }
}

/// Asks for notification permission, behind a protocol for tests.
protocol NotificationPermission {
    func request() async -> Bool
}

struct SystemNotificationPermission: NotificationPermission {
    func request() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }
}

/// Turning on Loupe for Safari (owner request 2026-09-26: as easy as possible).
///
/// iOS 26.2+: one button opens Safari's settings right at Loupe; when the app comes back to the
/// foreground the state is read again and the card flips to "On · Safari protected". Older iOS, or
/// when iOS refuses to open the page: the manual steps with the illustration. The first time
/// protection is seen on, Loupe asks (once) whether it may notify about dangerous sites.
@MainActor
final class SafariSetup: ObservableObject {
    struct Unavailable: Error {}

    enum Phase: Equatable {
        /// Not read yet.
        case checking
        /// iOS says it is off.
        case off
        /// Safari's settings were opened; waiting for the user to come back.
        case openedSettings
        /// iOS says it is on.
        case on
        /// This iOS cannot say (before 26.2), or reading failed: the manual steps, and when Safari
        /// last asked Loupe about a site.
        case unknown
    }

    enum ManualReason: Equatable {
        case olderIOS
        case openFailed(String)
    }

    /// The app's instance: re-reads the state whenever the app becomes active.
    static let shared = SafariSetup(observeForeground: true)
    static let askedNotificationsKey = "protection.notify.asked"
    static let log = Logger(subsystem: "com.loupe-ai.ios", category: "protection")

    @Published private(set) var phase: Phase = .checking
    /// Set when the manual steps should be shown (and why).
    @Published var manual: ManualReason?
    /// True for a moment after protection turned on while the user watched (the small celebration).
    @Published var celebrate = false
    /// When Loupe for Safari last answered Safari (a time only), for older iOS.
    @Published private(set) var lastSeen: Date?

    private let control: SafariExtensionControl
    private let notifications: NotificationPermission
    private let defaults: UserDefaults
    private let now: () -> Date
    private var observers: Set<AnyCancellable> = []

    init(control: SafariExtensionControl = SystemSafariExtensionControl(),
         notifications: NotificationPermission = SystemNotificationPermission(),
         defaults: UserDefaults = ProtectionGroup.defaults, now: @escaping () -> Date = Date.init, observeForeground: Bool = false) {
        self.control = control
        self.notifications = notifications
        self.defaults = defaults
        self.now = now
        if observeForeground {
            NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
                .sink { [weak self] _ in Task { await self?.refresh() } }
                .store(in: &observers)
        }
    }

    var isOn: Bool { phase == .on }
    var canOpenDirectly: Bool { control.canReadAndOpen }

    /// Reads the state again (launch, foreground, Guard appearing). Coming back from Safari's settings
    /// with it on is the moment to celebrate and to ask about notifications.
    func refresh() async {
        let seen = defaults.double(forKey: ProtectionGroup.Keys.safariLastSeen)
        lastSeen = seen > 0 ? Date(timeIntervalSince1970: seen) : nil
        let before = phase
        do {
            guard let enabled = try await control.isEnabled() else {
                phase = .unknown
                return
            }
            phase = enabled ? .on : (before == .openedSettings ? .openedSettings : .off)
            if enabled && before != .on && before != .checking {
                manual = nil
                celebrate = true
            }
            if enabled { await askNotificationsOnce() }
        } catch {
            Self.log.error("Safari extension state unreadable: \(String(describing: error), privacy: .public)")
            phase = .unknown
        }
    }

    /// "Turn on Safari protection": Safari's settings at Loupe, or the manual steps.
    func turnOn() async {
        guard control.canReadAndOpen else {
            manual = .olderIOS
            return
        }
        do {
            try await control.openSettings()
            phase = .openedSettings
        } catch {
            Self.log.error("Opening Safari's extension settings failed: \(String(describing: error), privacy: .public)")
            manual = .openFailed(error.localizedDescription)
        }
    }

    /// The first time protection is seen on: may Loupe notify about dangerous sites? Asked once.
    func askNotificationsOnce() async {
        guard !defaults.bool(forKey: Self.askedNotificationsKey) else { return }
        defaults.set(true, forKey: Self.askedNotificationsKey)
        _ = await notifications.request()
    }

    /// The status line under the card's title.
    var statusLine: String {
        switch phase {
        case .checking: return "Checking…"
        case .off: return "Off. Safari is not protected yet."
        case .openedSettings: return "Waiting for you to turn it on in Safari's settings."
        case .on: return "On · Safari protected"
        case .unknown:
            if let lastSeen, now().timeIntervalSince(lastSeen) < 14 * 24 * 3600 {
                return "Working: Safari last asked Loupe about a site \(Self.relative(lastSeen, now: now()))."
            }
            return "This iPhone does not tell Loupe whether it is on. Follow the steps below, then open any website in Safari: Loupe notes when Safari asks it."
        }
    }

    static func relative(_ d: Date, now: Date) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        return f.localizedString(for: d, relativeTo: now)
    }
}
