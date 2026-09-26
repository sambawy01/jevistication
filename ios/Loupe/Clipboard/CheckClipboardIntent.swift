import AppIntents
import UIKit

/// "Check what I copied" (2026-09-26): Siri ("Check what I copied with Loupe"), Shortcuts and the Action
/// Button. It reads the clipboard (iOS handles consent: "Allow Paste?", unless Paste from Other Apps is
/// set to Allow), runs Check a link's verdict (on this iPhone; online parts only as switched on in the
/// app, domain only) and answers in words: "That link looks like a fake PayPal page." or "No warning
/// signs found in that link." A suspicious or dangerous one is added to the Spotted log.
///
/// Shortcuts can hand it the text instead (the `Text` parameter, e.g. from "Get Clipboard"). When iOS
/// runs it in the background and will not give it the clipboard, it asks to continue in Loupe.
struct CheckClipboardIntent: AppIntent, ForegroundContinuableIntent {
    static let title: LocalizedStringResource = "Check what I copied"
    static let description = IntentDescription("Checks the link you copied for phishing, on this iPhone, and tells you what Loupe found.",
                                               categoryName: "Protection")

    @Parameter(title: "Text", description: "The text to check. Leave empty to check what you copied.",
               inputOptions: String.IntentInputOptions(keyboardType: .URL, capitalizationType: .none, autocorrect: false))
    var text: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Check \(\.$text) for phishing")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let clipboard = SystemClipboard()
        var value = text
        if value?.isEmpty ?? true {
            value = clipboard.readString()
            if value == nil, clipboard.hasStrings, UIApplication.shared.applicationState != .active {
                value = try await requestToContinueInForeground("Open Loupe to read what you copied?") { clipboard.readString() }
            }
        }
        let out = await ClipboardIntentAnswer.answer(text: value, clipboardEmpty: text == nil && !clipboard.hasStrings)
        return .result(value: out, dialog: IntentDialog(stringLiteral: out))
    }
}

/// The intent's answer without iOS (unit-tested with a fake clipboard): the sentence it says.
enum ClipboardIntentAnswer {
    @MainActor
    static func answer(text: String?, clipboardEmpty: Bool, store: ProtectionStore = .shared,
                       online: LinkCheckModel.Online? = { await OnlineChecksService.shared.linkContext(url: $0) }) async -> String {
        if clipboardEmpty && (text?.isEmpty ?? true) { return ClipboardVerdictRun.Outcome.empty.sentence }
        let out = await ClipboardVerdictRun.run(text: text, online: online, store: store)
        if case .verdict(let v, _) = out {
            let d = ProtectionGroup.defaults
            d.set(v.level.rawValue, forKey: ClipboardShared.Keys.lastLevel)
            d.set(Date().timeIntervalSince1970, forKey: ClipboardShared.Keys.lastAt)
        }
        return out.sentence
    }
}

/// Siri and Spotlight phrases (the app's one `AppShortcutsProvider`).
struct LoupeAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: CheckClipboardIntent(),
                    phrases: [
                        "Check what I copied with \(.applicationName)",
                        "Check my clipboard with \(.applicationName)",
                        "Check the link I copied with \(.applicationName)",
                    ],
                    shortTitle: "Check what I copied",
                    systemImageName: "doc.on.clipboard")
    }
}
