import XCTest
import LoupeKit
@testable import Loupe

/// A Backend that answers from a table keyed by offer price, so tests control Laya's view exactly.
final class FakeBackend: NSObject, Backend {
    var fitsByPrice: [String: Double]
    var calls = 0
    var onScore: (() -> Void)?
    init(_ fitsByPrice: [String: Double]) { self.fitsByPrice = fitsByPrice }
    func score(judgment: JudgmentChoice, state: TextState) -> Scored {
        calls += 1
        onScore?()
        let price = fitsByPrice.keys.first { state.text.contains("Price: GBP \($0)") } ?? ""
        let p = fitsByPrice[price] ?? 0.5
        return Scored(masses: [FlightPriorities.shared.FITS: KotlinDouble(value: p),
                               FlightPriorities.shared.MISSES: KotlinDouble(value: 1 - p)],
                      modelContext: nil, optionCriteria: nil)
    }
}

final class LayaRankerTests: XCTestCase {
    let offers = Fixture.response.offers
    let priorities = PriorityParser.parse("nonstop, under £200, not before 7am, 1 checked bag")

    func testOfferBecomesCompactState() {
        let o = offers.first { $0.id == "off_fixture_04" }!
        let state = FlightState.shared.of(offer: LayaRanker.facts(o), budget: FlightState.shared.BUDGET)
        XCTAssertTrue(state.isComplete)
        XCTAssertTrue(state.text.hasPrefix("Price: \(o.currency) \(o.totalAmount)\nAirline: \(o.owner)"), state.text)
        XCTAssertTrue(state.text.contains("1 stop"), state.text)
        XCTAssertLessThan(state.text.count, Int(FlightState.shared.BUDGET))
    }

    func testPrioritiesKeepTheirTextForLaya() {
        XCTAssertEqual(priorities.text, "nonstop, under £200, not before 7am, 1 checked bag")
        let j = FlightPriorities.shared.compile(priorities: priorities.text) as? FlightJudgmentReady
        XCTAssertEqual(j?.judgment.question, "Does this flight offer fit these priorities: nonstop, under £200, not before 7am, 1 checked bag?")
        XCTAssertEqual(j?.judgment.candidates, ["fits", "does not fit"])
    }

    func testOrdersByModelProbabilityAndMarksUnsure() throws {
        let prices = offers.map(\.totalAmount)
        // Reverse the rule order's intuition: the most expensive offer is the one Laya likes.
        var table: [String: Double] = [:]
        for (i, p) in prices.sorted(by: { Decimal(string: $0)! > Decimal(string: $1)! }).enumerated() {
            table[p] = [0.97, 0.9, 0.7, 0.4, 0.1, 0.02][i]
        }
        let backend = FakeBackend(table)
        var seen: [(Int, Int)] = []
        let r = try LayaRanker(backend: backend).rank(offers, by: priorities) { seen.append(($0, $1)) }
        XCTAssertEqual(r.count, offers.count)
        XCTAssertEqual(backend.calls, offers.count)
        XCTAssertEqual(r.map(\.rank), Array(1...offers.count))
        XCTAssertEqual(r.map(\.score), [0.97, 0.9, 0.7, 0.4, 0.1, 0.02])
        XCTAssertEqual(r.map(\.unsure), [false, false, true, true, false, false])   // 0.80 threshold
        XCTAssertTrue(r.allSatisfy { $0.scoreKind == .model && !$0.checks.isEmpty })
        XCTAssertEqual(seen.first?.0, 0)
        XCTAssertEqual(seen.last?.0, offers.count)
    }

    func testRefusedPrioritiesThrow() {
        XCTAssertThrowsError(try LayaRanker(backend: FakeBackend([:])).rank(offers, by: PriorityParser.parse("explain the best one")) { _, _ in }) {
            guard case LayaRanker.Failure.refused(let reasons) = $0 else { return XCTFail("\($0)") }
            XCTAssertFalse(reasons.isEmpty)
        }
    }

    func testCancellationStopsBetweenOffers() async {
        let backend = FakeBackend([:])
        let ranker = LayaRanker(backend: backend)
        let offers = self.offers, p = self.priorities
        let task = Task.detached { () -> Error? in
            withUnsafeCurrentTask { $0?.cancel() }
            do { _ = try ranker.rank(offers, by: p) { _, _ in }; return nil } catch { return error }
        }
        let error = await task.value
        XCTAssertTrue(error is CancellationError)
        XCTAssertEqual(backend.calls, 0)
    }

    @MainActor
    func testWebModelFallsBackToRulesWithoutTheModel() async throws {
        let m = WebModel(helper: FixtureFlightsHelper(), keys: MemoryKeyStore("k"), connectivity: Connectivity(start: false),
                         laya: { nil }, fixtureMode: true)
        m.form = .fixtureExample
        await m.search()
        for _ in 0..<100 {
            if case .rulesOnly = m.ranking { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(m.ranking, .rulesOnly(.modelNotInstalled))
        XCTAssertEqual(m.shown.map(\.offer.id), RuleBasedRanker().rank(offers, by: priorities).map(\.offer.id))
        XCTAssertEqual(m.rankerName, "Rules")
    }

    @MainActor
    func testWebModelShowsLayaAndTheDisagreement() async throws {
        var table: [String: Double] = [:]
        for o in offers { table[o.totalAmount] = o.id == "off_fixture_05" ? 0.95 : 0.05 }
        let backend = FakeBackend(table)
        let m = WebModel(helper: FixtureFlightsHelper(), keys: MemoryKeyStore("k"), connectivity: Connectivity(start: false),
                         laya: { backend }, fixtureMode: true)
        m.form = .fixtureExample
        await m.search()
        for _ in 0..<100 where m.ranking != .laya { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertEqual(m.ranking, .laya)
        XCTAssertEqual(m.shown.first?.offer.id, "off_fixture_05")
        XCTAssertEqual(m.rankerName, "The decision model (on this phone)")
        XCTAssertEqual(m.topDisagreement?.rules.offer.id, "off_fixture_02")
        m.showRules = true
        XCTAssertEqual(m.shown.first?.offer.id, "off_fixture_02")
        XCTAssertEqual(m.rankerName, "Rules")
    }
}

/// Runs the real Laya when it has been side-loaded into this simulator's app container
/// (ios-native/sideload-models.sh com.loupe-ai.ios booted); skips cleanly otherwise.
final class LayaOnDeviceTests: XCTestCase {
    func testRealModelRanksEveryFixtureOffer() throws {
        guard let dir = LayaOnPhone.shared.directory(), LayaOnPhone.shared.missing(directory: dir).isEmpty else {
            throw XCTSkip("Laya is not side-loaded; run ios-native/sideload-models.sh com.loupe-ai.ios booted")
        }
        let opened = LayaOnPhone.shared.open(directory: dir)
        guard let ready = opened as? LayaOnPhone.OpenedReady else {
            return XCTFail((opened as? LayaOnPhone.OpenedFailed)?.message ?? "could not open")
        }
        defer { ready.laya.close() }
        let offers = Fixture.response.offers
        let p = PriorityParser.parse("nonstop, under £200, not before 7am, 1 checked bag")
        let start = Date()
        let r = try LayaRanker(backend: ready.laya.backend).rank(offers, by: p) { _, _ in }
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertEqual(r.count, offers.count)
        XCTAssertEqual(Set(r.map(\.offer.id)), Set(offers.map(\.id)))
        XCTAssertTrue(r.allSatisfy { (0...1).contains($0.score) && $0.note == nil })
        let rules = RuleBasedRanker().rank(offers, by: p)
        print("LAYA-RANKING " + r.map { "\($0.offer.id)=\(String(format: "%.3f", $0.score))\($0.unsure ? "?" : "")" }.joined(separator: " "))
        print("RULE-RANKING " + rules.map(\.offer.id).joined(separator: " "))
        print("LAYA-SECONDS \(String(format: "%.2f", elapsed)) for \(offers.count) offers (simulator)")
    }
}
