import Foundation
import LoupeKit

/// The Guard tab's view model: pure functions over the watchers' latest `WatcherSummary` (no state, no SwiftUI), so
/// the grouping, sorting, totals and the locked model half are unit-tested (LoupeTests/GuardModelTests).
///
/// The five watchers and where each shows on Guard:
/// - Recurring money → Subscriptions (the census, its monthly total, and each merchant's "no charge" warning).
/// - Expiry radar → Expiring soon (the mechanical timeline; the model half says what each document is).
/// - Person impersonation, Site fraud, Term change → a section each.
enum GuardModel {
    // MARK: Expiring soon

    /// The timeline's groups, by days left on the day of the run.
    enum ExpiryBucket: Int, CaseIterable, Identifiable {
        case overdue, thisWeek, thisMonth, later
        var id: Int { rawValue }

        var title: String {
            switch self {
            case .overdue: return "Overdue"
            case .thisWeek: return "This week"
            case .thisMonth: return "This month"
            case .later: return "Later"
            }
        }

        /// Overdue: the date has passed. This week: today to 7 days. This month: 8 to 30 days. Later: beyond.
        static func of(daysLeft: Int64) -> ExpiryBucket {
            if daysLeft < 0 { return .overdue }
            if daysLeft <= 7 { return .thisWeek }
            if daysLeft <= 30 { return .thisMonth }
            return .later
        }
    }

    struct ExpiryGroup: Identifiable {
        let bucket: ExpiryBucket
        let rows: [ExpiryRow]
        var id: Int { bucket.rawValue }
    }

    /// The rows in their buckets (empty buckets left out), soonest first inside each.
    static func groupExpiries(_ rows: [ExpiryRow]) -> [ExpiryGroup] {
        let sorted = rows.sorted { ($0.daysRemaining, $0.itemName) < ($1.daysRemaining, $1.itemName) }
        let byBucket = Dictionary(grouping: sorted) { ExpiryBucket.of(daysLeft: $0.daysRemaining) }
        return ExpiryBucket.allCases.compactMap { b in byBucket[b].map { ExpiryGroup(bucket: b, rows: $0) } }
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

    /// The monthly total: the sum of the regular merchants' monthly figures (irregular ones have none).
    static func monthlyTotal(_ rows: [CensusRow]) -> Int64 {
        rows.reduce(0) { $0 + ($1.monthlyMinor?.int64Value ?? 0) }
    }

    /// A merchant's share of the monthly total, 0…1 (0 when irregular or the total is 0).
    static func share(_ row: CensusRow, total: Int64) -> Double {
        guard total > 0, let m = row.monthlyMinor?.int64Value else { return 0 }
        return min(1, Double(m) / Double(total))
    }

    /// "9.99/mo", or "irregular".
    static func perMonth(_ row: CensusRow) -> String {
        row.monthlyMinor.map { WatchersService.money($0.int64Value) + "/mo" } ?? "irregular"
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
