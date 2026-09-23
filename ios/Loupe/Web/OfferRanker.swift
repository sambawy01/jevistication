import Foundation

/// One rule's verdict on one offer, shown to the user as the reason for the ranking.
struct RuleCheck: Equatable {
    enum Outcome: Equatable { case pass, fail, unknown }
    var outcome: Outcome
    var text: String
}

struct RankedOffer: Identifiable, Equatable {
    var offer: Offer
    var rank: Int
    var checks: [RuleCheck]
    /// 0...1. For the rule ranker this is the share of rules met, not a model probability.
    var score: Double
    /// True when the ranker cannot tell (e.g. price in another currency than the cap).
    var unsure: Bool
    var id: String { offer.id }
    var fitsAll: Bool { checks.allSatisfy { $0.outcome == .pass } }
}

/// Ranks offers against the user's priorities, on the device.
protocol OfferRanker {
    var name: String { get }
    func rank(_ offers: [Offer], by priorities: Priorities) -> [RankedOffer]
}

/// The honest baseline: explicit rules compiled from the priority text. Offers that meet more
/// rules rank higher; ties break on price (or duration if "shortest"), then id for stability.
struct RuleBasedRanker: OfferRanker {
    let name = "Rules"

    func rank(_ offers: [Offer], by p: Priorities) -> [RankedOffer] {
        let scored = offers.map { o -> RankedOffer in
            let checks = Self.checks(o, p)
            let decided = checks.filter { $0.outcome != .unknown }
            let passed = checks.filter { $0.outcome == .pass }.count
            let score = checks.isEmpty ? 1 : Double(passed) / Double(checks.count)
            return RankedOffer(offer: o, rank: 0, checks: checks, score: score, unsure: decided.count < checks.count)
        }
        let sorted = scored.sorted { a, b in
            let fa = a.checks.filter { $0.outcome == .fail }.count
            let fb = b.checks.filter { $0.outcome == .fail }.count
            if fa != fb { return fa < fb }
            if a.score != b.score { return a.score > b.score }
            if p.preferShortest, a.offer.totalMinutes != b.offer.totalMinutes { return a.offer.totalMinutes < b.offer.totalMinutes }
            if a.offer.price != b.offer.price { return a.offer.price < b.offer.price }
            return a.offer.id < b.offer.id
        }
        return sorted.enumerated().map { i, r in var r = r; r.rank = i + 1; return r }
    }

    static func checks(_ o: Offer, _ p: Priorities) -> [RuleCheck] {
        var out: [RuleCheck] = []
        if let max = p.maxStops {
            let s = o.maxStops
            let want = max == 0 ? "nonstop" : "at most \(max) stop\(max == 1 ? "" : "s")"
            let got = s == 0 ? "Nonstop" : "\(s) stop\(s == 1 ? "" : "s")"
            out.append(RuleCheck(outcome: s <= max ? .pass : .fail, text: s <= max ? got : "\(got), wanted \(want)"))
        }
        if let cap = p.priceCap {
            let capText = "\(p.priceCurrency.map { $0 + " " } ?? "")\(cap)"
            if let cur = p.priceCurrency, cur != o.currency {
                out.append(RuleCheck(outcome: .unknown, text: "Priced in \(o.currency), cap is in \(cur): can't compare"))
            } else {
                let ok = o.price <= cap
                out.append(RuleCheck(outcome: ok ? .pass : .fail,
                                     text: ok ? "\(o.currency) \(o.totalAmount) is under \(capText)" : "\(o.currency) \(o.totalAmount) is over \(capText)"))
            }
        }
        let deps = o.sliceDepartureMinutes
        if let e = p.earliestDeparture {
            if deps.isEmpty { out.append(RuleCheck(outcome: .unknown, text: "Departure time unknown")) }
            else if let early = deps.first(where: { $0 < e }) {
                out.append(RuleCheck(outcome: .fail, text: "Leaves \(LocalTime.format(minutes: early)), before \(LocalTime.format(minutes: e))"))
            } else {
                out.append(RuleCheck(outcome: .pass, text: "Every leg leaves at or after \(LocalTime.format(minutes: e))"))
            }
        }
        if let l = p.latestDeparture {
            if deps.isEmpty { out.append(RuleCheck(outcome: .unknown, text: "Departure time unknown")) }
            else if let late = deps.first(where: { $0 > l }) {
                out.append(RuleCheck(outcome: .fail, text: "Leaves \(LocalTime.format(minutes: late)), after \(LocalTime.format(minutes: l))"))
            } else {
                out.append(RuleCheck(outcome: .pass, text: "Every leg leaves by \(LocalTime.format(minutes: l))"))
            }
        }
        if let bags = p.minCheckedBags {
            let got = o.baggage.checked
            // The helper reports 0 when Duffel does not say, so a 0 is "not included or unknown".
            out.append(got >= bags
                ? RuleCheck(outcome: .pass, text: "\(got) checked bag\(got == 1 ? "" : "s") included")
                : RuleCheck(outcome: .fail, text: got == 0 ? "No checked bag listed" : "Only \(got) checked bag\(got == 1 ? "" : "s")"))
        }
        if p.refundable {
            switch o.conditions.refundable {
            case .some(true): out.append(RuleCheck(outcome: .pass, text: "Refundable"))
            case .some(false): out.append(RuleCheck(outcome: .fail, text: "Not refundable"))
            case .none: out.append(RuleCheck(outcome: .unknown, text: "Refund terms not stated"))
            }
        }
        return out
    }
}

// MARK: - LAYA HOOK ---------------------------------------------------------------------------
// When the iOS Laya backend lands (epic #6 child #3, built in parallel in engine/backend and
// ios-native/), add `LayaOfferRanker: OfferRanker` here. It should:
//   1. compile the priority text to a judgment via LoupeKit (C2 authoring),
//   2. turn each Offer into an item and let Laya score it (set selection over all offers),
//   3. return RankedOffer with `score` = calibrated confidence and `unsure` = abstentions.
// Keep RuleBasedRanker running alongside it: the honest-baseline principle says the results
// screen must show the rule ranking next to Laya's, and say so when Laya does not beat it.
enum Rankers {
    static func primary() -> OfferRanker { RuleBasedRanker() }
    static let baseline: OfferRanker = RuleBasedRanker()
}
