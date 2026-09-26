import Foundation

/// Clipboard checks' shared switches and status (2026-09-26), in the App Group's defaults so the app,
/// the widget and the Loupe keyboard read the same thing. **No clipboard content is ever kept here**:
/// only switches, times, levels and the keyboard's own status.
enum ClipboardShared {
    enum Keys {
        /// "Check what I copy" (the chip). **On by default** (owner decision: every on-device feature is on).
        static let enabled = "clipboard.enabled"
        /// The pasteboard change count already looked at (each copy is offered once).
        static let lastChangeCount = "clipboard.lastChangeCount"
        /// The "Paste from Other Apps: Allow" hint was shown once.
        static let pasteHintShown = "clipboard.pasteHintShown"
        /// The last check's level and time (the widget shows them; never the link or its website).
        static let lastLevel = "clipboard.last.level"
        static let lastAt = "clipboard.last.at"
        /// "Check copied" from the widget, when iOS ran its intent outside the app: the app checks the
        /// clipboard as soon as it is active, if this is recent.
        static let checkRequestedAt = "clipboard.checkRequestedAt"

        /// Written by the Loupe keyboard (it can write here only with Full Access): Full Access is on,
        /// and when the keyboard last ran with it.
        static let keyboardFullAccess = "keyboard.fullAccess"
        static let keyboardSeenAt = "keyboard.seenAt"
        /// The keyboard's highest memory footprint seen (MB), for the report and Diagnostics.
        static let keyboardPeakMB = "keyboard.peakMB"
    }

    /// The Loupe keyboard's bundle id (project.yml `LoupeKeyboard`).
    static let keyboardId = "com.loupe-ai.ios.keyboard"
    /// Posted (Darwin notify) by the keyboard after it wrote its status, so the app's card updates.
    static let keyboardChanged = "com.loupe-ai.ios.keyboard.status"
    /// The widget's kind (WidgetKit), for reloads.
    static let widgetKind = "LoupeCheckCopied"

    static func enabled(_ d: UserDefaults = ProtectionGroup.defaults) -> Bool {
        (d.object(forKey: Keys.enabled) as? Bool) ?? true
    }
}
