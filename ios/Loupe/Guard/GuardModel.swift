import Foundation
import LoupeKit

/// LoupeKit's own bucketing (`dev.loupe.kit.tracking.ExpiryBucket.Companion.of`), called from file scope because
/// `GuardModel.ExpiryBucket` (this file's Swift-native mirror) shadows the bare name `ExpiryBucket` everywhere
/// inside `enum GuardModel`, and — separately — the Kotlin `LoupeKit` object is exported under the exact name of
/// the `LoupeKit` module, so `LoupeKit.ExpiryBucket` does not resolve to the module's type from inside GuardModel.
private func engineExpiryBucket(daysRemaining: Int64) -> ExpiryBucket {
    ExpiryBucket.companion.of(daysRemaining: daysRemaining)
}

/// The Guard tab's view model: pure functions over the watchers' latest `WatcherSummary` (no state, no SwiftUI), so
/// the grouping, sorting, totals and the locked model half are unit-tested (LoupeTests/GuardModelTests).
///
/// The five watchers and where each shows on Guard:
/// - Recurring money → Subscriptions (the census, its monthly total, and each merchant's "no charge" warning).
/// - Expiry radar → Expiring soon (the mechanical timeline; the model half says what each document is).
/// - Person impersonation, Site fraud, Term change → a section each.
enum GuardModel {
    // MARK: Expiring soon

    /// The timeline's groups, by days left on the day of the run. Mapped 1:1 from LoupeKit's own `ExpiryBucket`
    /// (task T-B, part 2): a Swift-native `CaseIterable`/`Identifiable` enum is kept here (SwiftUI's `ForEach` and
    /// `Dictionary(grouping:)` need that), but the bucketing itself — including the 365-day "older" cutoff — is the
    /// engine's, not re-derived.
    enum ExpiryBucket: Int, CaseIterable, Identifiable {
        case overdue, thisWeek, thisMonth, later, older
        var id: Int { rawValue }

        var title: String {
            switch self {
            case .overdue: return "Overdue"
            case .thisWeek: return "This week"
            case .thisMonth: return "This month"
            case .later: return "Later"
            case .older: return "Older"
            }
        }

        /// Overdue: the date has passed. This week: today to 7 days. This month: 8 to 30 days. Later: beyond, within
        /// a year. Older: expired more than a year ago (`LoupeKit.ExpiryBucket.OLDER_AFTER_DAYS`) — shown last,
        /// collapsed, and never an alert (`WatcherFindings.isAlert` already keeps these out of every finding).
        static func of(daysLeft: Int64) -> ExpiryBucket {
            switch engineExpiryBucket(daysRemaining: daysLeft) {
            case .overdue: return .overdue
            case .week: return .thisWeek
            case .month: return .thisMonth
            case .older: return .older
            default: return .later
            }
        }
    }

    struct ExpiryGroup: Identifiable {
        let bucket: ExpiryBucket
        let rows: [ExpiryRow]
        var id: Int { bucket.rawValue }
    }

    /// The rows in their buckets (empty buckets left out), soonest first inside each. `.older` sorts last (it is
    /// `ExpiryBucket`'s final case) and, on the timeline, is shown collapsed.
    static func groupExpiries(_ rows: [ExpiryRow]) -> [ExpiryGroup] {
        let sorted = rows.sorted { ($0.daysRemaining, $0.itemName) < ($1.daysRemaining, $1.itemName) }
        let byBucket = Dictionary(grouping: sorted) { ExpiryBucket.of(daysLeft: $0.daysRemaining) }
        return ExpiryBucket.allCases.compactMap { b in byBucket[b].map { ExpiryGroup(bucket: b, rows: $0) } }
    }

    /// Whether the "Inside the rule" badge should show for a row in this bucket: never for `.older` (task T-B, part
    /// 2) — that bucket raises no alert, regardless of the raw `breachesRule` flag. The one place both the timeline
    /// row and the expiry detail check this, so they can't drift apart.
    static func showsRuleBadge(_ row: ExpiryRow, in bucket: ExpiryBucket) -> Bool {
        row.breachesRule && bucket != .older
    }

    /// "113 days left", "Today", "Tomorrow", "Expired 4 days ago".
    static func daysLeftLine(_ days: Int64) -> String {
        switch days {
        case ..<(-1): return "Expired \(-days) days ago"
        case -1: return "Expired yesterday"
        case 0: return "Expires today"
        case 1: return "Tomorrow"
        default: return "\(days) days left"
        }
    }

    /// The model half of the expiry radar (deciding what a document is), for the Guard's gate.
    enum ModelHalf: Equatable {
        /// The decision model judged the documents in the last run.
        case ran
        /// Model settings turned the watchers' model off: the mechanical half ran alone, by choice.
        case turnedOff
        /// The model is not ready (missing, downloading, failed): locked behind "Needs the decision model".
        case locked
        /// The model is ready but the last run did not use it (it arrived after the run): the next run will.
        case pending
    }

    static func modelHalf(modelRan: Bool, modelReady: Bool, turnedOff: Bool) -> ModelHalf {
        if modelRan { return .ran }
        if turnedOff { return .turnedOff }
        return modelReady ? .pending : .locked
    }

    /// The document's name: the decision model's judgment first ("passport", when the model ran and judged it a
    /// listed type), then the rules' mechanical `documentKind` ("car licence" -> "Car licence"), then the item's own
    /// name — the one titling rule Guard's timeline row and detail, and Home's Documents card, all share.
    static func documentTitle(_ row: ExpiryRow) -> String {
        if let t = row.documentType { return t.prefix(1).uppercased() + t.dropFirst() }
        if let id = row.documentKind, let kind = DocumentKind.entries.first(where: { $0.id == id }) {
            return kind.title.prefix(1).uppercased() + kind.title.dropFirst()
        }
        return row.itemName
    }

    /// What a row says about the document's type, by the model half's state.
    static func typeLine(_ row: ExpiryRow, half: ModelHalf) -> String {
        switch half {
        case .ran:
            if let t = row.documentType { return "The decision model: \(t)" }
            return row.breachesRule ? "The decision model did not judge it a listed type" : "Not judged: outside the rule"
        case .turnedOff: return "Type not judged: the model is off for the watchers"
        case .locked: return "Type: needs the decision model"
        case .pending: return "Type: judged on the next run"
        }
    }

    // MARK: Subscriptions

    /// Most expensive first by the monthly figure; irregular merchants (no monthly figure) after, by their typical
    /// charge; then by name so the order is stable.
    static func sortedSubscriptions(_ rows: [CensusRow]) -> [CensusRow] {
        rows.sorted { a, b in
            let am = a.monthlyMinor?.int64Value, bm = b.monthlyMinor?.int64Value
            switch (am, bm) {
            case let (x?, y?) where x != y: return x > y
            case (.some, nil): return true
            case (nil, .some): return false
            default:
                if am == nil && a.typicalMinor != b.typicalMinor { return a.typicalMinor > b.typicalMinor }
                return a.merchant.localizedCaseInsensitiveCompare(b.merchant) == .orderedAscending
            }
        }
    }

    /// The sum of the regular merchants' monthly figures across every currency (irregular ones have none). This
    /// mirrors `SubscriptionCensus.monthlyTotalMinor`'s own engine-side definition
    /// (`WatcherFindings.summarise`'s `keptRows.sumOf { it.monthlyMinor ?: 0L }`) — a single `Long` field on the
    /// Kotlin struct, so it is a cross-currency sum by the engine's own design, not a Swift bug. Used **only** to
    /// keep that field honest after a local Confirm/Set aside/Undo (`census(_:replacing:with:)`,
    /// `census(_:restoring:)`), which must match what a real re-run would produce for the same field. Not used for
    /// display any more — see `monthlyByCurrency` for that (task T-B, part 2).
    static func monthlyTotal(_ rows: [CensusRow]) -> Int64 {
        rows.reduce(0) { $0 + ($1.monthlyMinor?.int64Value ?? 0) }
    }

    /// One total per currency (`WatcherFindings.monthlyByCurrency`, the engine's own grouping — never one sum across
    /// currencies), largest first, ties broken by currency code. A row whose currency is empty (no currency read
    /// from its charges) groups under `""`. The one derivation Guard's Subscriptions total and Home's Money card
    /// both use (task T-B, part 2).
    static func monthlyByCurrency(_ rows: [CensusRow]) -> [(currency: String, minor: Int64)] {
        WatcherFindings.shared.monthlyByCurrency(rows: rows)
            .filter { $0.value.int64Value > 0 }
            .sorted { a, b in
                a.value.int64Value != b.value.int64Value ? a.value.int64Value > b.value.int64Value : a.key < b.key
            }
            .map { (currency: $0.key, minor: $0.value.int64Value) }
    }

    /// A merchant's share of its own currency's total, 0…1 (0 when irregular or that total is 0). Never mixes
    /// currencies: pass the total for `row.currency` (from `monthlyByCurrency`), not a cross-currency sum.
    static func share(_ row: CensusRow, total: Int64) -> Double {
        guard total > 0, let m = row.monthlyMinor?.int64Value else { return 0 }
        return min(1, Double(m) / Double(total))
    }

    /// A merchant's own monthly figure, in its own currency ("9.99", "EGP 450.00"), or nil when irregular. Never the
    /// census total's currency — a row is always shown in its own (task T-B, part 2).
    static func monthlyAmount(_ row: CensusRow) -> String? {
        guard let m = row.monthlyMinor else { return nil }
        let money = WatchersService.money(m.int64Value)
        return row.currency.isEmpty ? money : "\(row.currency) \(money)"
    }

    /// "9.99/mo", "EGP 450.00/mo", or "irregular".
    static func perMonth(_ row: CensusRow) -> String {
        monthlyAmount(row).map { "\($0)/mo" } ?? "irregular"
    }

    /// The census with one merchant's row replaced (a Confirm) or removed (nil: not a subscription / set aside), and
    /// its total and set-aside count kept honest.
    static func census(_ c: SubscriptionCensus, replacing merchant: String, with row: CensusRow?) -> SubscriptionCensus {
        let rows = c.rows.compactMap { $0.merchant == merchant ? row : $0 }
        let removed = c.rows.count - rows.count
        return SubscriptionCensus(rows: rows, monthlyTotalMinor: monthlyTotal(rows), chargesFound: c.chargesFound,
                                  sample: !rows.isEmpty && rows.allSatisfy(\.sample), setAside: c.setAside + Int32(removed))
    }

    /// Undo: the row back in the census.
    static func census(_ c: SubscriptionCensus, restoring row: CensusRow) -> SubscriptionCensus {
        let rows = c.rows.filter { $0.merchant != row.merchant } + [row]
        return SubscriptionCensus(rows: rows, monthlyTotalMinor: monthlyTotal(rows), chargesFound: c.chargesFound,
                                  sample: rows.allSatisfy(\.sample), setAside: max(0, c.setAside - 1))
    }

    /// The merchant's "no charge for N days" finding, if the recurring watcher raised one.
    static func quietFinding(_ row: CensusRow, in findings: [WatcherFinding]) -> WatcherFinding? {
        findings.first { $0.watcher == .recurring && $0.itemName == row.merchant }
    }

    // MARK: The other watchers

    /// The findings of one watcher, in the order the watcher ranks them.
    static func findings(_ kind: WatcherKind, in all: [WatcherFinding]) -> [WatcherFinding] {
        all.filter { $0.watcher == kind }
    }

    /// The sections after Subscriptions and Expiring soon, in the watchers' urgency order.
    static let otherWatchers: [WatcherKind] = [.impersonation, .siteFraud, .termChange]

    // MARK: Dates

    private static let iso: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    /// "14 Jan 2027" from "2027-01-14" (the ISO string when it does not parse).
    static func day(_ isoDay: String) -> String {
        guard let d = iso.date(from: isoDay) else { return isoDay }
        return d.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: TimeZone(identifier: "UTC")!))
    }
}
