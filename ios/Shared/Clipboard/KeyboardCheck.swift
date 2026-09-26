import Foundation

/// The Loupe keyboard's verdict (2026-09-26): **on this iPhone only**. The mechanical checks of the
/// shared phishing formula and the lists the app already downloaded into the App Group (Phishing.Database
/// through its memory-mapped index, the small feeds); never an online check, whatever the app's
/// switches say. The keyboard target has no network code at all (LoupeTests/ClipboardTests greps its
/// sources; its build phase checks the linked binary for network symbols).
///
/// Nothing is stored except a suspicious or dangerous verdict's website name in the Spotted log.
enum KeyboardCheck {
    struct Result {
        let target: ClipboardTarget
        let verdict: LinkVerdict
    }

    /// The verdict for copied or pasted [text], or nil when there is no link or email address in it.
    static func check(_ text: String, lists: ProtectionLists = ProtectionLists(),
                      settings: OnlinePhishingSettings = OnlinePhishingSettings.loadShared(), now: Date = Date()) -> Result? {
        guard case .success(let target) = ClipboardTarget.extract(text) else { return nil }
        let v = DeviceLinkCheck.verdict(target.input, origin: .keyboard, settings: settings, lists: lists, now: now)
        return Result(target: target, verdict: v)
    }
}
