import AppIntents
import Foundation

/// The widget's "Check copied" button (2026-09-26, iOS 17 interactive widget). Compiled into the widget
/// and the app. It opens Loupe, which then reads the clipboard in the foreground (so iOS can ask
/// "Allow Paste?") and shows the verdict banner. When iOS runs it in the app, it checks at once; when
/// it runs it in the widget's process, it leaves a time-only request in the App Group that the app
/// honours as soon as it is active (within a minute).
struct CheckCopiedIntent: AppIntent {
    static let title: LocalizedStringResource = "Check copied"
    static let description = IntentDescription("Opens Loupe and checks the link you copied.")
    static let openAppWhenRun: Bool = true
    /// Shortcuts and Siri use "Check what I copied" (it answers without opening Loupe).
    static let isDiscoverable: Bool = false

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        #if LOUPE_WIDGET
        ProtectionGroup.defaults.set(Date().timeIntervalSince1970, forKey: ClipboardShared.Keys.checkRequestedAt)
        #else
        await ClipboardMonitor.shared.checkNow(trigger: .widget)
        #endif
        return .result()
    }
}
