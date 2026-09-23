import XCTest
@testable import Loupe

final class RankerTests: XCTestCase {
    let ranker = RuleBasedRanker()

    func testOwnerExampleRanking() {
        let p = PriorityParser.parse("nonstop, under £200, not before 7am, 1 checked bag")
        let r = ranker.rank(Fixture.response.offers, by: p)
        XCTAssertEqual(r.map(\.offer.id), ["off_fixture_02", "off_fixture_03", "off_fixture_04",
                                           "off_fixture_06", "off_fixture_05", "off_fixture_01"])
        XCTAssertEqual(r.map(\.rank), [1, 2, 3, 4, 5, 6])
        XCTAssertTrue(r[0].fitsAll)
        XCTAssertEqual(r[0].score, 1)
        XCTAssertFalse(r[2].fitsAll)
        XCTAssertTrue(r[2].checks.contains { $0.outcome == .fail && $0.text.contains("wanted nonstop") })
        XCTAssertTrue(r[5].checks.contains { $0.outcome == .fail && $0.text.contains("06:10") })
    }

    func testNoPrioritiesIsPriceOrder() {
        let r = ranker.rank(Fixture.response.offers, by: Priorities())
        let prices = r.map(\.offer.price)
        XCTAssertEqual(prices, prices.sorted())
        XCTAssertTrue(r.allSatisfy { $0.score == 1 && $0.checks.isEmpty })
    }

    func testCurrencyMismatchIsUnsureNotFail() {
        let r = ranker.rank(Fixture.response.offers, by: PriorityParser.parse("under €200"))
        XCTAssertTrue(r.allSatisfy { $0.unsure })
        XCTAssertTrue(r.allSatisfy { $0.checks.first?.outcome == .unknown })
    }

    func testShortestPreference() {
        let r = ranker.rank(Fixture.response.offers, by: PriorityParser.parse("shortest"))
        XCTAssertNotEqual(r.last?.offer.id, nil)
        XCTAssertEqual(r.last?.offer.id, "off_fixture_04")   // the only connecting itinerary
    }

    func testDeterministic() {
        let p = PriorityParser.parse("max 1 stop, before 8pm")
        XCTAssertEqual(ranker.rank(Fixture.response.offers, by: p), ranker.rank(Fixture.response.offers.reversed(), by: p))
    }
}
