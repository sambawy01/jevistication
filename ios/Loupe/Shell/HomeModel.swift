import Foundation
import LoupeKit

/// Home's cards as values (spec 2026-09-28 §3): pure, so the order, the words and the empty states are unit-tested.
enum HomeModel {
    // MARK: Needs attention

    /// One thing that needs the person, with one primary action each (the view decides the action).
    enum Attention: Equatable, Identifiable {
        case spotted(headline: String, dangerous: Int)
        case mail(phishing: Int)
        case finding(key: String)
        case review(count: Int)
        case privacy(count: Int)

        var id: String {
            switch self {
            case .spotted: return "spotted"
            case .mail: return "mail"
            case .finding(let key): return "finding:" + key
            case .review: return "review"
            case .privacy: return "privacy"
            }
        }
    }

    /// Risky sites (Safari, clipboard, shared links), mail phishing, the watchers' newest findings (impersonation,
    /// site fraud, expiry, price rises; at most `findingLimit`), review proposals, then privacy findings. Empty when
    /// nothing waits: the section is then not shown at all.
    static func attention(findingKeys: [String], spottedHeadline: String?, dangerousThisWeek: Int, phishing: Int,
                          toReview: Int, privacyFindings: Int, findingLimit: Int = 3) -> [Attention] {
        var out: [Attention] = []
        if let spottedHeadline { out.append(.spotted(headline: spottedHeadline, dangerous: dangerousThisWeek)) }
        if phishing > 0 { out.append(.mail(phishing: phishing)) }
        out += findingKeys.prefix(findingLimit).map { Attention.finding(key: $0) }
        if toReview > 0 { out.append(.review(count: toReview)) }
        if privacyFindings > 0 { out.append(.privacy(count: privacyFindings)) }
        return out
    }

    // MARK: Money and Documents

    /// A card's words: a headline, one line under it, whether it should offer "Open What Loupe reads", and whether
    /// nothing was checked yet (loaded, no results, nothing running): Home's scan panel then offers Run now, and the
    /// card, with no source on, the way to one.
    struct Summary: Equatable {
        let headline: String
        let detail: String
        let needsSource: Bool
        var needsRun = false
    }

    /// No summary yet. The last results are saved and read back at launch, off the main thread, and nothing heavy
    /// starts when Loupe opens (O-4): so "Loading the last results…" until they are read, "Reading your sources" only
    /// while a run, a scan or the watchers really go; otherwise it is not checked yet.
    private static func notYet(running: Bool, loaded: Bool, what: String, readingDetail: String) -> Summary {
        if !loaded { return Summary(headline: "Loading the last results…", detail: "The last check's results show here in a moment.", needsSource: false) }
        return running ? Summary(headline: "Reading your sources", detail: readingDetail, needsSource: false)
                       : Summary(headline: "Not checked yet", detail: "Run a check to see \(what).", needsSource: false, needsRun: true)
    }

    /// The Money card from the census; nil until the watchers have run (or their saved results are read back).
    /// `running`: a run, a scan or the watchers are going now; `loaded`: the saved results have been read back.
    static func money(_ census: SubscriptionCensus?, mailCovered: Bool, running: Bool = false, loaded: Bool = true) -> Summary {
        guard let census else {
            return notYet(running: running, loaded: loaded, what: "your subscriptions",
                          readingDetail: "Subscriptions show here once the watchers have run.")
        }
        let rows = census.rows
        if rows.isEmpty {
            if !mailCovered {
                return Summary(headline: "No subscriptions yet",
                               detail: "Turn on Mail, or import a bank statement, and Loupe finds what you pay for.",
                               needsSource: true)
            }
            return Summary(headline: "No subscriptions yet",
                           detail: "No merchant charged three or more times in the \(census.chargesFound) charge\(census.chargesFound == 1 ? "" : "s") read.",
                           needsSource: false)
        }
        let total = rows.reduce(Int64(0)) { $0 + ($1.monthlyMinor?.int64Value ?? 0) }
        var detail = "\(rows.count) subscription\(rows.count == 1 ? "" : "s")"
        if let next = rows.compactMap({ r in r.nextExpectedIso.map { (r.merchant, $0) } }).min(by: { $0.1 < $1.1 }) {
            detail += " · \(next.0) next on \(GuardModel.day(next.1))"
        }
        return Summary(headline: "\(WatchersService.money(total)) a month", detail: detail, needsSource: false)
    }

    /// The Documents card from the expiry timeline; nil rows until the watchers have run (or their saved results are
    /// read back).
    static func documents(_ rows: [ExpiryRow]?, documentsCovered: Bool, running: Bool = false, loaded: Bool = true) -> Summary {
        guard let rows else {
            return notYet(running: running, loaded: loaded, what: "which documents expire soon",
                          readingDetail: "Expiry dates show here once the watchers have run.")
        }
        guard let soonest = rows.min(by: { $0.daysRemaining < $1.daysRemaining }) else {
            if !documentsCovered {
                return Summary(headline: "No documents read yet",
                               detail: "Turn on Files or Photos: Loupe finds the expiry dates on IDs, licences, passports and policies.",
                               needsSource: true)
            }
            return Summary(headline: "No expiry dates found",
                           detail: "Not an all-clear: a document Loupe cannot read is not checked.", needsSource: false)
        }
        return Summary(headline: "\(soonest.itemName): \(GuardModel.daysLeftLine(soonest.daysRemaining))",
                       detail: rows.count > 1 ? "+ \(rows.count - 1) more" : "1 document", needsSource: false)
    }

    // MARK: Protected

    enum Guardrail: String, CaseIterable {
        case safari, keyboard, clipboard

        var title: String {
            switch self {
            case .safari: return "Safari"
            case .keyboard: return "Keyboard"
            case .clipboard: return "Clipboard"
            }
        }
    }

    struct Protected: Equatable {
        let on: [Guardrail: Bool]
        let onlineChecksOn: Bool

        var firstOff: Guardrail? { Guardrail.allCases.first { on[$0] != true } }
        var title: String { firstOff == nil ? "Protected" : "Not fully protected" }
        /// "Safari on · Keyboard off · Clipboard on": words, not ticks, so VoiceOver reads it as it looks.
        var line: String { Guardrail.allCases.map { "\($0.title) \(on[$0] == true ? "on" : "off")" }.joined(separator: " · ") }
        var bytesLine: String { onlineChecksOn ? "Online checks on" : "0 bytes out" }
    }

    static func protected(safari: Bool, keyboard: Bool, clipboard: Bool, onlineChecksOn: Bool) -> Protected {
        Protected(on: [.safari: safari, .keyboard: keyboard, .clipboard: clipboard], onlineChecksOn: onlineChecksOn)
    }
}
