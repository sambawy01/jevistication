import Combine
import LoupeKit
import SwiftUI
import UIKit
import WidgetKit

/// The clipboard behind a small protocol, so the offer → check state machine is tested without iOS.
protocol ClipboardSource: AnyObject {
    /// Changes with every copy (any app). Reading it never prompts.
    var changeCount: Int { get }
    /// Whether there is text at all. Never prompts.
    var hasStrings: Bool { get }
    /// What kind of thing is there, **without reading it** (no paste prompt).
    func detect() async -> Set<ClipboardPattern>
    /// The copied text. This is the read that shows iOS's "Allow Paste" question (unless the user set
    /// Settings → Loupe → Paste from Other Apps → Allow). nil when the user said no or there is none.
    func readString() -> String?
}

/// The real clipboard. Detection uses `detectedPatterns(for:)` (iOS 15+ key-path form), which tells
/// which patterns are present without the values, and so without the paste prompt (verified in the
/// simulator, `ClipboardProbeUITests`); the values are read only when the user taps Check.
final class SystemClipboard: ClipboardSource {
    private var pb: UIPasteboard { .general }

    var changeCount: Int { pb.changeCount }
    var hasStrings: Bool { pb.hasStrings || pb.hasURLs }

    /// The patterns Loupe asks about, by key path.
    static let keyPaths: [(PartialKeyPath<UIPasteboard.DetectedValues>, ClipboardPattern)] = [
        (\.probableWebURL, .probableWebURL), (\.probableWebSearch, .probableWebSearch), (\.number, .number),
        (\.links, .link), (\.phoneNumbers, .phoneNumber), (\.emailAddresses, .emailAddress), (\.moneyAmounts, .moneyAmount),
    ]

    func detect() async -> Set<ClipboardPattern> {
        guard hasStrings else { return [] }
        let found = (try? await pb.detectedPatterns(for: Set(Self.keyPaths.map(\.0)))) ?? []
        return Set(Self.keyPaths.filter { found.contains($0.0) }.map(\.1))
    }

    func readString() -> String? {
        if let s = pb.string, !s.isEmpty { return s }
        return pb.url?.absoluteString
    }
}

/// Clipboard checks in the app (2026-09-26, owner request "where is the smart copy-paste monitoring
/// feature?"). When Loupe becomes active (and when Guard appears) it asks iOS **what kind** of thing
/// was copied, never what it is: `detectPatterns` does not show the paste prompt. Each copy (the
/// pasteboard's `changeCount`) is looked at once. A link or an email address gets a small chip,
/// "Check the link you copied?"; tapping Check reads it (iOS asks unless Paste is set to Allow) and
/// runs the same verdict as Check a link. Suspicious and dangerous verdicts go to the Spotted log
/// (origin "clipboard") and every check to recent checks (no query or fragment); the answer shows as
/// an in-app banner. Nothing else about the clipboard is kept.
///
/// On by default (owner decision: every on-device feature is on); Guard → Protection → Clipboard
/// turns it off. The online part follows Me → Online phishing checks, exactly as Check a link.
@MainActor
final class ClipboardMonitor: ObservableObject {
    static let shared = ClipboardMonitor()

    enum Phase: Equatable {
        case idle
        /// The chip: "Check the link you copied?".
        case offer(ClipboardKind)
        case checking(ClipboardKind)
        /// The banner with the verdict.
        case result(LinkVerdict, ClipboardKind)
        /// The banner with what went wrong (nothing to read, not a link, the user said no).
        case problem(String)

        var isVisible: Bool { self != .idle }
    }

    enum Trigger: String { case active, guardAppeared, manual, widget }

    @Published private(set) var phase: Phase = .idle
    /// Shown once, under the first result: "Paste from Other Apps → Allow" removes iOS's question.
    @Published private(set) var showPasteHint = false
    @Published var enabled: Bool {
        didSet {
            defaults.set(enabled, forKey: ClipboardShared.Keys.enabled)
            if !enabled { phase = .idle }
        }
    }

    typealias Online = LinkCheckModel.Online
    private let source: ClipboardSource
    private let defaults: UserDefaults
    private let store: ProtectionStore
    private let online: Online?
    private let now: () -> Date
    private let offerFor: TimeInterval
    private var started = false
    private var observers: Set<AnyCancellable> = []
    private var dismissTask: Task<Void, Never>?
    private var scanning = false

    init(source: ClipboardSource = SystemClipboard(), defaults: UserDefaults = ProtectionGroup.defaults,
         store: ProtectionStore = .shared, online: Online? = { await OnlineChecksService.shared.linkContext(url: $0) },
         now: @escaping () -> Date = Date.init, offerFor: TimeInterval = 20) {
        self.source = source
        self.defaults = defaults
        self.store = store
        self.online = online
        self.now = now
        self.offerFor = offerFor
        enabled = ClipboardShared.enabled(defaults)
    }

    /// Watches for the app becoming active and shows the chip over any screen. Idempotent.
    func start() {
        guard !started else { return }
        started = true
        #if DEBUG
        // The unit-test host (XCTest loaded in the app) never watches: the tests drive their own monitors.
        if Self.debugSilenced || NSClassFromString("XCTestCase") != nil { return }
        if ProcessInfo.processInfo.arguments.contains("-LoupeClipboardReset") {
            defaults.removeObject(forKey: ClipboardShared.Keys.lastChangeCount)
            defaults.removeObject(forKey: ClipboardShared.Keys.pasteHintShown)
        }
        // -LoupeClipboardProbe <api>: evidence of which pasteboard calls show iOS's paste prompt.
        if let i = ProcessInfo.processInfo.arguments.firstIndex(of: "-LoupeClipboardProbe"), i + 1 < ProcessInfo.processInfo.arguments.count {
            ClipboardOverlay.shared.attach(self)
            let api = ProcessInfo.processInfo.arguments[i + 1]
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                let out = await ClipboardProbe.run(api)
                self.phase = .problem("probe \(api): \(out)")
            }
            return
        }
        #endif
        ClipboardOverlay.shared.attach(self)
        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in Task { await self?.becameActive() } }
            .store(in: &observers)
        if UIApplication.shared.applicationState == .active { Task { await becameActive() } }
    }

    #if DEBUG
    /// UI tests run with `-LoupeFixtures`; the chip stays out of their way unless a test asks for it
    /// with `-LoupeClipboard` (the simulator's clipboard can hold anything from an earlier test).
    static var debugSilenced: Bool {
        let args = ProcessInfo.processInfo.arguments
        return args.contains("-LoupeFixtures") && !args.contains("-LoupeClipboard")
    }
    #endif

    private func becameActive() async {
        // "Check copied" from the widget, run by iOS outside the app: check at once.
        let asked = defaults.double(forKey: ClipboardShared.Keys.checkRequestedAt)
        if asked > 0, now().timeIntervalSince1970 - asked < 60 {
            defaults.removeObject(forKey: ClipboardShared.Keys.checkRequestedAt)
            await checkNow(trigger: .widget)
            return
        }
        await scan(.active)
    }

    /// Looks at the clipboard once per copy. Returns whether it offered the chip.
    @discardableResult
    func scan(_ trigger: Trigger) async -> Bool {
        #if DEBUG
        if trigger != .manual && Self.debugSilenced { return false }
        #endif
        guard enabled, !scanning else { return false }
        if case .checking = phase { return false }
        let count = source.changeCount
        let last = defaults.object(forKey: ClipboardShared.Keys.lastChangeCount) as? Int
        guard count != last else { return false }
        // Seen, whatever it holds: the same copy is never offered twice (across launches too).
        defaults.set(count, forKey: ClipboardShared.Keys.lastChangeCount)
        scanning = true
        defer { scanning = false }
        let patterns = await source.detect()
        guard source.changeCount == count, let kind = ClipboardOffer.kind(for: patterns) else { return false }
        phase = .offer(kind)
        UIAccessibility.post(notification: .announcement, argument: ClipboardOffer.question(kind))
        scheduleDismiss(after: offerFor, ifStill: .offer(kind))
        return true
    }

    /// The chip's Check: reads the clipboard (iOS may ask), then the verdict.
    func check() async {
        let kind: ClipboardKind
        if case .offer(let k) = phase { kind = k } else { kind = .link }
        await run(kind: kind, text: source.readString())
    }

    /// "Check what I copied" in the app (the Guard card, the widget): no chip first.
    func checkNow(trigger: Trigger = .manual) async {
        defaults.set(source.changeCount, forKey: ClipboardShared.Keys.lastChangeCount)
        guard source.hasStrings else {
            show(.problem("There is nothing on the clipboard to check. Copy a link first."))
            return
        }
        await run(kind: nil, text: source.readString())
    }

    private func run(kind: ClipboardKind?, text: String?) async {
        dismissTask?.cancel()
        phase = .checking(kind ?? .link)
        let out = await ClipboardVerdictRun.run(text: text, online: online, store: store, now: now())
        switch out {
        case .verdict(let v, let k):
            defaults.set(v.level.rawValue, forKey: ClipboardShared.Keys.lastLevel)
            defaults.set(now().timeIntervalSince1970, forKey: ClipboardShared.Keys.lastAt)
            WidgetCenter.shared.reloadTimelines(ofKind: ClipboardShared.widgetKind)
            if !defaults.bool(forKey: ClipboardShared.Keys.pasteHintShown) {
                defaults.set(true, forKey: ClipboardShared.Keys.pasteHintShown)
                showPasteHint = true
            }
            show(.result(v, k))
            UIAccessibility.post(notification: .announcement, argument: ClipboardWords.sentence(v, kind: k))
        case .unreadable, .empty, .notCheckable:
            show(.problem(out.sentence))
        }
    }

    private func show(_ p: Phase) {
        phase = p
        // A clean result and a problem go by themselves; a warning stays until dismissed.
        switch p {
        case .result(let v, _) where !v.level.flagged: scheduleDismiss(after: 10, ifStill: p)
        case .problem: scheduleDismiss(after: 8, ifStill: p)
        default: break
        }
    }

    func dismiss() {
        dismissTask?.cancel()
        phase = .idle
        showPasteHint = false
    }

    private func scheduleDismiss(after seconds: TimeInterval, ifStill p: Phase) {
        dismissTask?.cancel()
        dismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled, let self, self.phase == p, !ClipboardOverlay.shared.presentingDetails else { return }
            self.phase = .idle
            self.showPasteHint = false
        }
    }

    /// The last clipboard and keyboard checks (recent checks keep no query or fragment).
    var recentChecks: [LinkVerdict] {
        store.recent.filter { $0.origin == .clipboard || $0.origin == .keyboard }
    }
}

/// One clipboard check, shared by the chip, the Guard card and "Check what I copied" (Siri,
/// Shortcuts, the Action Button): the text → a link or an email address's domain → the verdict of
/// Check a link (online parts only as switched on in the app) → recent checks and, when flagged, the
/// Spotted log (origin "clipboard").
enum ClipboardVerdictRun {
    enum Outcome: Equatable {
        case verdict(LinkVerdict, ClipboardKind)
        /// iOS gave no text: the user chose Don't Allow, or the clipboard holds none.
        case unreadable
        case empty
        /// Text with no link or email address in it.
        case notCheckable

        /// What Siri says and the banner shows.
        var sentence: String {
            switch self {
            case .verdict(let v, let k): return ClipboardWords.sentence(v, kind: k)
            case .unreadable: return "Loupe could not read what you copied. If iOS asked, choose Allow Paste, or set Settings → Loupe → Paste from Other Apps → Allow."
            case .empty: return "There is nothing on the clipboard to check. Copy a link first."
            case .notCheckable: return "What you copied has no link or email address in it. Loupe checks links and the websites behind email addresses."
            }
        }
    }

    @MainActor
    static func run(text: String?, online: LinkCheckModel.Online?, store: ProtectionStore, now: Date = Date()) async -> Outcome {
        guard let text else { return .unreadable }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .empty }
        guard case .success(let target) = ClipboardTarget.extract(text) else { return .notCheckable }
        let (context, disclosure) = await online?(target.input.url) ?? (nil, .none)
        let v = LinkChecker.verdict(target.input, online: context, disclosure: disclosure, origin: .clipboard, now: now)
        store.add(v)
        return .verdict(v, target.kind)
    }
}
