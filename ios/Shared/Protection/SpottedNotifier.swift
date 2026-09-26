import Foundation
import UserNotifications

/// The notification centre behind a small protocol, so the rules are tested without iOS.
protocol ProtectionNotificationCenter: AnyObject {
    /// Whether the user allowed Loupe's notifications (never asks).
    func isAuthorized() async -> Bool
    func post(id: String, title: String, body: String) async
}

final class SystemNotificationCenter: ProtectionNotificationCenter {
    func isAuthorized() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional || s.authorizationStatus == .ephemeral
    }

    func post(id: String, title: String, body: String) async {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.threadIdentifier = "protection"
        c.userInfo = ["loupe": "spotted"]
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }
}

/// Local notifications for Safari warnings (2026-09-26): a dangerous site always (once notifications
/// are allowed), a suspicious one only when the user turned that on. At most one per website per
/// [perSiteInterval], and **never** for a website the user chose to continue to (for [continuedFor]).
/// The permission is asked in context, by the app, the first time protection turns on; this only
/// checks it. State (website name → time) lives in the App Group's defaults.
final class SpottedNotifier {
    static let perSiteInterval: TimeInterval = 60 * 60
    static let continuedFor: TimeInterval = 12 * 3600
    static let notifiedKey = "protection.notified"
    static let continuedKey = "protection.continued"

    private let center: ProtectionNotificationCenter
    private let defaults: UserDefaults
    private let now: () -> Date
    private let lock = NSLock()

    init(center: ProtectionNotificationCenter = SystemNotificationCenter(), defaults: UserDefaults = ProtectionGroup.defaults,
         now: @escaping () -> Date = Date.init) {
        self.center = center
        self.defaults = defaults
        self.now = now
    }

    private func stamps(_ key: String, keep: TimeInterval) -> [String: Double] {
        let all = defaults.dictionary(forKey: key) as? [String: Double] ?? [:]
        let cutoff = now().timeIntervalSince1970 - keep
        return all.filter { $0.value >= cutoff }
    }

    /// The user tapped "Continue anyway" on [host]: no notification for it for [continuedFor].
    func markContinued(host: String) {
        lock.withLock {
            var c = stamps(Self.continuedKey, keep: Self.continuedFor)
            c[host] = now().timeIntervalSince1970
            defaults.set(c, forKey: Self.continuedKey)
        }
    }

    func continued(host: String) -> Bool { stamps(Self.continuedKey, keep: Self.continuedFor)[host] != nil }

    var notifySuspicious: Bool { defaults.bool(forKey: ProtectionGroup.Keys.notifySuspicious) }

    /// The rules, without the centre: level, the suspicious switch, continue, and the per-site limit.
    func shouldNotify(host: String, level: ProtectionLevel, userContinued: Bool) -> Bool {
        guard level == .dangerous || (level == .suspicious && notifySuspicious) else { return false }
        if userContinued || continued(host: host) { return false }
        return stamps(Self.notifiedKey, keep: Self.perSiteInterval)[host] == nil
    }

    /// Checks the rules and records the site, under the lock (two quick loads notify once).
    private func reserve(host: String, level: ProtectionLevel, userContinued: Bool) -> Bool {
        lock.withLock {
            guard shouldNotify(host: host, level: level, userContinued: userContinued) else { return false }
            var n = stamps(Self.notifiedKey, keep: Self.perSiteInterval)
            n[host] = now().timeIntervalSince1970
            defaults.set(n, forKey: Self.notifiedKey)
            return true
        }
    }

    /// The words: "Loupe blocked a fake PayPal page" · "paypa1-secure.com · Dangerous …".
    static func content(domain: String, level: ProtectionLevel, brand: String?, reason: String?) -> (title: String, body: String) {
        let title: String
        switch (level, brand) {
        case (.dangerous, let b?): title = "Loupe blocked a fake \(b) page"
        case (.dangerous, nil): title = "Loupe blocked a dangerous site"
        case (_, let b?): title = "Loupe warned you about a possible fake \(b) page"
        default: title = "Loupe warned you about a suspicious site"
        }
        let what = level == .dangerous ? "Dangerous" : "Suspicious"
        let body = [domain, what, reason].compactMap { $0 }.joined(separator: " · ")
        return (title, String(body.prefix(240)))
    }

    /// Posts one notification when the rules allow it and the user allowed notifications. Returns
    /// whether it posted. The site is recorded before posting, so two quick loads send one.
    @discardableResult
    func notifyIfNeeded(domain: String, host: String, level: ProtectionLevel, brand: String?, reason: String?,
                        userContinued: Bool) async -> Bool {
        guard reserve(host: host, level: level, userContinued: userContinued) else { return false }
        guard await center.isAuthorized() else { return false }
        let c = Self.content(domain: domain, level: level, brand: brand, reason: reason)
        await center.post(id: "spotted-\(host)-\(Int(now().timeIntervalSince1970))", title: c.title, body: c.body)
        return true
    }
}
