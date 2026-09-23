import XCTest
@testable import Loupe

final class ErrorMappingTests: XCTestCase {
    private func body(_ code: String) -> Data { Data(#"{"error":{"code":"\#(code)","message":"m"}}"#.utf8) }

    func testCodes() {
        XCTAssertEqual(HelperError.from(status: 401, data: body("INVALID_KEY")), .invalidKey)
        XCTAssertEqual(HelperError.from(status: 429, data: body("RATE_LIMITED")), .rateLimited)
        XCTAssertEqual(HelperError.from(status: 429, data: body("PROVIDER_RATE_LIMITED")), .providerRateLimited)
        XCTAssertEqual(HelperError.from(status: 404, data: body("NO_RESULTS")), .noResults)
        XCTAssertEqual(HelperError.from(status: 503, data: body("BUSY")), .busy)
        XCTAssertEqual(HelperError.from(status: 502, data: body("PROVIDER_ERROR")), .providerError)
        XCTAssertEqual(HelperError.from(status: 400, data: body("BAD_REQUEST")), .badRequest("m"))
    }

    func testStatusFallbacks() {
        XCTAssertEqual(HelperError.from(status: 401, data: Data()), .invalidKey)
        XCTAssertEqual(HelperError.from(status: 429, data: Data("x".utf8)), .rateLimited)
        XCTAssertEqual(HelperError.from(status: 503, data: Data()), .busy)
        XCTAssertEqual(HelperError.from(status: 500, data: Data()), .providerError)
        XCTAssertEqual(HelperError.from(status: 418, data: Data()), .unexpected("HTTP 418"))
    }

    func testURLErrors() {
        XCTAssertEqual(HelperError.from(urlError: URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(HelperError.from(urlError: URLError(.cannotFindHost)), .offline)
        XCTAssertEqual(HelperError.from(urlError: URLError(.timedOut)), .timeout)
        XCTAssertEqual(HelperError.offline.message, "Needs a connection. Everything else keeps working.")
        XCTAssertTrue(HelperError.invalidKey.message.contains("INVALID_KEY"))
    }

    @MainActor
    func testModelSurfacesInvalidKeyAndDoesNotStore() async {
        final class RejectingHelper: FlightsHelper {
            func health() async throws -> HealthResponse { .init(ok: true, version: "t") }
            func search(_ request: SearchRequest, key: String) async throws -> SearchResponse { throw HelperError.invalidKey }
        }
        let keys = MemoryKeyStore()
        let m = WebModel(helper: RejectingHelper(), keys: keys, connectivity: Connectivity(start: false),
                         fixtureMode: true, defaults: UserDefaults(suiteName: "t.\(UUID())")!)
        await m.addKey("duffel_test_abcdefghijk")
        XCTAssertEqual(m.keyCheck, .failed(.invalidKey))
        XCTAssertNil(keys.read())
        XCTAssertFalse(m.hasKey)
        await m.addKey("nope")
        XCTAssertEqual(m.keyCheck, .malformed)
    }

    @MainActor
    func testModelVerifiesThenStoresAndRanks() async {
        let keys = MemoryKeyStore()
        let m = WebModel(helper: FixtureFlightsHelper(), keys: keys, connectivity: Connectivity(start: false),
                         fixtureMode: true, defaults: UserDefaults(suiteName: "t.\(UUID())")!)
        await m.addKey("duffel_test_abcdefghijk")
        XCTAssertEqual(keys.read(), "duffel_test_abcdefghijk")
        XCTAssertTrue(m.hasKey)
        m.form = .fixtureExample
        await m.search()
        XCTAssertEqual(m.searchState, .results)
        XCTAssertEqual(m.ranked.first?.offer.id, "off_fixture_02")
        m.removeKey()
        XCTAssertFalse(m.hasKey)
        XCTAssertNil(keys.read())
    }
}
