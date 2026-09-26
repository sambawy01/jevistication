import LoupeKit
import SwiftUI
import UIKit

/// Check a link (2026-09-26): paste or type a link, get a verdict from the shared phishing formula,
/// its score, the reasons, what ran on this iPhone and online, and what (if anything) left the phone.
@MainActor
final class LinkCheckModel: ObservableObject {
    @Published var input = ""
    @Published private(set) var verdict: LinkVerdict?
    @Published private(set) var problem: String?
    @Published private(set) var checking = false

    /// The online part: nil for "no online checks at all" (tests; nothing is sent).
    typealias Online = (String) async -> (context: OnlineContext?, disclosure: OnlineDisclosure)
    private let online: Online?
    private let store: ProtectionStore
    private let now: () -> Date

    init(online: Online? = { await OnlineChecksService.shared.linkContext(url: $0) },
         store: ProtectionStore = .shared, now: @escaping () -> Date = Date.init) {
        self.online = online
        self.store = store
        self.now = now
    }

    /// The text of a Paste tap (the clipboard is read only then, by the system's paste control).
    func paste(_ text: String) {
        input = text.trimmingCharacters(in: .whitespacesAndNewlines)
        problem = nil
    }

    func clear() {
        input = ""
        verdict = nil
        problem = nil
    }

    /// Checks [input]. Returns the verdict (also kept in recent checks and, when flagged, Spotted).
    @discardableResult
    func check() async -> LinkVerdict? {
        problem = nil
        switch LinkInput.normalize(input) {
        case .failure(let p):
            verdict = nil
            problem = p.message
            return nil
        case .success(let n):
            checking = true
            defer { checking = false }
            let (context, disclosure) = await online?(n.url) ?? (nil, .none)
            let v = LinkChecker.verdict(n, online: context, disclosure: disclosure, origin: .manual, now: now())
            verdict = v
            store.add(v)
            return v
        }
    }

    /// Shows a recent check again (as it was; "Check again" re-runs it).
    func show(_ v: LinkVerdict) {
        verdict = v
        input = v.displayURL
        problem = nil
    }
}
