import Foundation
import LoupeKit

/// Ranks offers with Laya on this phone: the user's priorities become a Choice judgment through
/// C2's authoring path, each offer a compact TextState, and the engine's own validate → calibrate →
/// policy path decides (LoupeKit `FlightJudge`). An offer the policy would not act on is marked
/// unsure — the uncertain queue's rule. Synchronous and CPU-bound: call it off the main thread.
struct LayaRanker: OfferRanker {
    let name = "Laya"
    let backend: Backend

    enum Failure: Error, Equatable {
        /// C2's lint refused the priorities text; the reasons are shown to the user.
        case refused([String])
    }

    func rank(_ offers: [Offer], by priorities: Priorities) -> [RankedOffer] {
        (try? rank(offers, by: priorities, progress: { _, _ in })) ?? []
    }

    /// Cancellable (checked between offers) and reports `(done, total)` after each offer.
    func rank(_ offers: [Offer], by p: Priorities, progress: (Int, Int) -> Void) throws -> [RankedOffer] {
        try decide(offers, by: p, progress: progress).ranked
    }

    /// Laya's ranking plus one ledger row per offer it judged (A5): `resolvedBy` model (or
    /// unusable), item `web:duffel:<offer id>`, the full distribution and propensity. Only a run
    /// that finished yields rows; a cancelled one throws and logs nothing.
    func decide(_ offers: [Offer], by p: Priorities, progress: (Int, Int) -> Void) throws -> (ranked: [RankedOffer], rows: [LedgerRow]) {
        let judgment: JudgmentChoice
        switch FlightPriorities.shared.compile(priorities: p.text) {
        case let ready as FlightJudgmentReady: judgment = ready.judgment
        case let refused as FlightJudgmentRefused: throw Failure.refused(refused.reasons)
        default: throw Failure.refused(["could not compile the priorities"])
        }
        // Judge in the rule ranking's order, so a tie in Laya's answer falls back to the baseline.
        let baseline = RuleBasedRanker().rank(offers, by: p)
        let judge = FlightJudge(backend: backend)
        var verdicts: [OfferVerdict] = []
        var rows: [LedgerRow] = []
        progress(0, baseline.count)
        for (i, r) in baseline.enumerated() {
            try Task.checkCancellation()
            let d = judge.decide(judgment: judgment, offer: Self.facts(r.offer))
            verdicts.append(d.verdict)
            rows.append(d.row)
            progress(i + 1, baseline.count)
        }
        let byId = Dictionary(uniqueKeysWithValues: baseline.map { ($0.offer.id, $0) })
        let ranked: [RankedOffer] = FlightJudge.Companion.shared.order(verdicts: verdicts).enumerated().compactMap { i, v in
            guard var r = byId[v.id] else { return nil }
            r.rank = i + 1
            r.score = v.fit
            r.scoreKind = .model
            r.unsure = v.unsure
            r.note = v.failure.map { "Model answer unusable: \($0)" }
                ?? (v.truncated ? "The model read only part of this offer" : nil)
            return r
        }
        return (ranked, rows)
    }

    /// The helper's offer as the facts Laya reads. Prices stay the decimal string the helper sent.
    static func facts(_ o: Offer) -> FlightFacts {
        let legs = o.slices.map { s -> FlightLeg in
            FlightLeg(from: s.segments.first?.from ?? "?",
                      to: s.segments.last?.to ?? "?",
                      departs: LocalTime.hhmm(s.segments.first?.departAt ?? ""),
                      arrives: LocalTime.hhmm(s.segments.last?.arriveAt ?? ""),
                      stops: Int32(max(0, s.segments.count - 1)),
                      durationMinutes: Int32(s.durationMinutes ?? -1),
                      flights: s.segments.map(\.flight).joined(separator: "+"))
        }
        return FlightFacts(id: o.id, price: o.totalAmount, currency: o.currency, airline: o.owner, legs: legs,
                           checkedBags: Int32(o.baggage.checked), carryOnBags: Int32(o.baggage.carryOn),
                           refundable: term(o.conditions.refundable), changeable: term(o.conditions.changeable))
    }

    private static func term(_ b: Bool?) -> Term {
        switch b { case .some(true): .yes; case .some(false): .no; case .none: .notStated }
    }
}
