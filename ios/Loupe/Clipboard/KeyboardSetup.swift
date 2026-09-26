import Combine
import SwiftUI
import UIKit

/// Whether the Loupe keyboard is added and has Full Access (2026-09-26).
///
/// - Added: the keyboard's bundle id among the active input modes (`UITextInputMode.activeInputModes`)
///   or in the `AppleKeyboards` preference iOS keeps for the enabled keyboards.
/// - Full Access: iOS gives no API for another process's Full Access. The keyboard writes
///   `hasFullAccess` into the App Group each time it opens, and it can write there **only** with Full
///   Access, so the app knows "on, as of the last time you used the keyboard" or "not reported".
@MainActor
final class KeyboardSetup: ObservableObject {
    static let shared = KeyboardSetup(observe: true)

    enum Status: Equatable {
        /// Not in Settings → Keyboards.
        case notAdded
        /// Added; Full Access not reported (off, or the keyboard has not been opened since it was turned on).
        case needsFullAccess
        /// Added and Full Access reported on.
        case ready
    }

    @Published private(set) var added = false
    @Published private(set) var fullAccessReported = false
    @Published private(set) var lastSeen: Date?
    @Published private(set) var peakMB: Double?

    private let modes: @MainActor () -> [String]
    private let defaults: UserDefaults
    private var observers: Set<AnyCancellable> = []

    init(modes: @escaping @MainActor () -> [String] = KeyboardSetup.enabledKeyboardIds, defaults: UserDefaults = ProtectionGroup.defaults,
         observe: Bool = false) {
        self.modes = modes
        self.defaults = defaults
        refresh()
        if observe {
            NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
                .sink { [weak self] _ in self?.refresh() }
                .store(in: &observers)
            NotificationCenter.default.publisher(for: UITextInputMode.currentInputModeDidChangeNotification)
                .sink { [weak self] _ in self?.refresh() }
                .store(in: &observers)
            let center = CFNotificationCenterGetDarwinNotifyCenter()
            CFNotificationCenterAddObserver(center, Unmanaged.passUnretained(self).toOpaque(), { _, observer, _, _, _ in
                guard let observer else { return }
                let setup = Unmanaged<KeyboardSetup>.fromOpaque(observer).takeUnretainedValue()
                Task { @MainActor in setup.refresh() }
            }, ClipboardShared.keyboardChanged as CFString, nil, .deliverImmediately)
        }
    }

    func refresh() {
        added = modes().contains { $0 == ClipboardShared.keyboardId || $0.hasPrefix(ClipboardShared.keyboardId + ".") }
        fullAccessReported = defaults.bool(forKey: ClipboardShared.Keys.keyboardFullAccess)
        let seen = defaults.double(forKey: ClipboardShared.Keys.keyboardSeenAt)
        lastSeen = seen > 0 ? Date(timeIntervalSince1970: seen) : nil
        let peak = defaults.double(forKey: ClipboardShared.Keys.keyboardPeakMB)
        peakMB = peak > 0 ? peak : nil
    }

    var status: Status {
        guard added else { return .notAdded }
        return fullAccessReported ? .ready : .needsFullAccess
    }

    /// One line for the cards.
    var statusLine: String {
        switch status {
        case .notAdded: return "Not added yet"
        case .needsFullAccess: return "Added · turn on Allow Full Access, then open the keyboard once"
        case .ready: return "Added · Full Access on · checks pasted links on this iPhone"
        }
    }

    /// The enabled keyboards' identifiers: the active input modes, and iOS's `AppleKeyboards` list.
    static func enabledKeyboardIds() -> [String] {
        let sel = NSSelectorFromString("identifier")
        var ids = UITextInputMode.activeInputModes.compactMap { mode -> String? in
            mode.responds(to: sel) ? mode.value(forKey: "identifier") as? String : nil
        }
        ids += (UserDefaults.standard.array(forKey: "AppleKeyboards") as? [String]) ?? []
        return ids
    }
}

/// The exact switches, in order (there is no link straight to the keyboard page).
enum KeyboardSteps {
    static let steps: [(String, String)] = [
        ("gear", "Tap \"Open Loupe's settings\" below (Settings → Apps → Loupe)."),
        ("keyboard", "Tap Keyboards."),
        ("switch.2", "Turn on Loupe."),
        ("lock.open", "Turn on Allow Full Access, then tap Allow. Loupe needs it only to read what you copied; it never sends anything."),
        ("globe", "In any app, hold the globe key and choose Loupe."),
    ]

    static let privacy = "The Loupe keyboard checks links on this iPhone only. It has no network code at all, so nothing you type or copy can leave the phone. It never logs keystrokes and never stores what you type; it keeps only the website name of a link it warned you about, in Spotted."
}
