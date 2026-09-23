import XCTest
@testable import Loupe

final class PriorityParserTests: XCTestCase {
    func testOwnerExample() {
        let p = PriorityParser.parse("nonstop, under £200, not before 7am, 1 checked bag")
        XCTAssertEqual(p.maxStops, 0)
        XCTAssertEqual(p.priceCap, 200)
        XCTAssertEqual(p.priceCurrency, "GBP")
        XCTAssertEqual(p.earliestDeparture, 7 * 60)
        XCTAssertNil(p.latestDeparture)
        XCTAssertEqual(p.minCheckedBags, 1)
        XCTAssertTrue(p.unrecognised.isEmpty)
    }

    func testVariants() {
        let p = PriorityParser.parse("max 1 stop; below €150.50 and before 9:30pm, 2 bags, refundable, fastest")
        XCTAssertEqual(p.maxStops, 1)
        XCTAssertEqual(p.priceCap, Decimal(string: "150.50"))
        XCTAssertEqual(p.priceCurrency, "EUR")
        XCTAssertEqual(p.latestDeparture, 21 * 60 + 30)
        XCTAssertEqual(p.minCheckedBags, 2)
        XCTAssertTrue(p.refundable)
        XCTAssertTrue(p.preferShortest)
    }

    func testDirectAndAfterAndNoCurrency() {
        let p = PriorityParser.parse("Direct, after 18:00, under 300")
        XCTAssertEqual(p.maxStops, 0)
        XCTAssertEqual(p.earliestDeparture, 18 * 60)
        XCTAssertEqual(p.priceCap, 300)
        XCTAssertNil(p.priceCurrency)
    }

    func testUnrecognisedIsReportedNotDropped() {
        let p = PriorityParser.parse("window seat, nonstop")
        XCTAssertEqual(p.maxStops, 0)
        XCTAssertEqual(p.unrecognised, ["window seat"])
    }

    func testEmpty() {
        XCTAssertTrue(PriorityParser.parse("").isEmpty)
        XCTAssertTrue(PriorityParser.parse("  , ").isEmpty)
    }

    func testClock() {
        XCTAssertEqual(PriorityParser.clock("12am"), 0)
        XCTAssertEqual(PriorityParser.clock("12pm"), 12 * 60)
        XCTAssertEqual(PriorityParser.clock("7 pm"), 19 * 60)
        XCTAssertNil(PriorityParser.clock("25:00"))
    }
}
