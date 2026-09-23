import XCTest
@testable import Loupe

final class WireTests: XCTestCase {
    func testDecodeFixture() {
        let r = Fixture.response
        XCTAssertEqual(r.schema, 1)
        XCTAssertEqual(r.source, "duffel")
        XCTAssertFalse(r.live)
        XCTAssertEqual(r.offers.count, 6)
        let o = r.offers[1]
        XCTAssertEqual(o.price, Decimal(string: "187.40"))
        XCTAssertEqual(o.maxStops, 0)
        XCTAssertEqual(o.sliceDepartureMinutes, [7 * 60 + 35, 17 * 60 + 20])
        XCTAssertEqual(o.baggage.checked, 1)
    }

    func testDecodeIssueExampleWithoutSeconds() throws {
        let json = """
        { "schema": 1, "source": "duffel", "fetchedAt": "2026-09-23T13:02:11Z", "live": false,
          "offers": [{ "id": "off_1", "totalAmount": "187.40", "currency": "GBP", "owner": "TAP Air Portugal",
            "slices": [{ "segments": [{ "from": "LIS", "to": "LHR", "departAt": "2026-10-14T07:35", "arriveAt": "2026-10-14T10:10",
                         "carrier": "TP", "flight": "TP1350", "aircraft": "A320" }], "durationMinutes": 155 }],
            "baggage": { "checked": 1, "carryOn": 1 }, "conditions": { "refundable": false, "changeable": true },
            "expiresAt": "2026-09-23T13:32:11Z" }] }
        """
        let r = try JSONDecoder().decode(SearchResponse.self, from: Data(json.utf8))
        XCTAssertEqual(r.offers[0].sliceDepartureMinutes, [455])
        XCTAssertEqual(ResultsView.fetchedTime(r.fetchedAt).count, 5)
    }

    func testSearchRequestEncodingAndHeaders() throws {
        var f = SearchForm()
        f.origin = "lis"; f.destination = "LHR"; f.adults = 2; f.cabin = .premium_economy; f.maxStops = 0
        f.departDate = DateComponents(calendar: .current, year: 2026, month: 10, day: 14).date!
        f.returnDate = DateComponents(calendar: .current, year: 2026, month: 10, day: 16).date!
        let body = try f.request()
        let req = try HelperEndpoint.searchRequest(body, key: "duffel_test_abc123456789", installId: "3b241101-e2bb-4255-8caf-4136c566a962")
        XCTAssertEqual(req.url?.absoluteString, "https://loupe-web-helper-production.up.railway.app/v1/flights/search")
        XCTAssertEqual(req.httpMethod, "POST")
        XCTAssertEqual(req.timeoutInterval, 20)
        XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer duffel_test_abc123456789")
        XCTAssertEqual(req.value(forHTTPHeaderField: "X-Loupe-Install"), "3b241101-e2bb-4255-8caf-4136c566a962")
        let obj = try JSONSerialization.jsonObject(with: req.httpBody!) as! [String: Any]
        XCTAssertEqual(Set(obj.keys), ["schema", "slices", "passengers", "cabinClass", "maxConnections"])
        XCTAssertEqual(obj["schema"] as? Int, 1)
        XCTAssertEqual(obj["cabinClass"] as? String, "premium_economy")
        XCTAssertEqual(obj["maxConnections"] as? Int, 0)
        let slices = obj["slices"] as! [[String: String]]
        XCTAssertEqual(slices, [["origin": "LIS", "destination": "LHR", "departureDate": "2026-10-14"],
                                ["origin": "LHR", "destination": "LIS", "departureDate": "2026-10-16"]])
        XCTAssertEqual((obj["passengers"] as! [[String: String]]), [["type": "adult"], ["type": "adult"]])
    }

    func testFormValidation() {
        var f = SearchForm(); f.origin = "LI"; f.destination = "LHR"
        XCTAssertThrowsError(try f.request())
        f.origin = "LHR"
        XCTAssertThrowsError(try f.request())
        f.origin = "L1S"
        XCTAssertFalse(SearchForm.isIATA(f.origin))
    }

    func testHealthRequest() {
        let r = HelperEndpoint.healthRequest()
        XCTAssertEqual(r.url?.absoluteString, "https://loupe-web-helper-production.up.railway.app/v1/health")
        XCTAssertNil(r.value(forHTTPHeaderField: "Authorization"))
    }

    func testInstallIdStable() {
        let d = UserDefaults(suiteName: "test.install.\(UUID())")!
        let a = InstallID.value(d), b = InstallID.value(d)
        XCTAssertEqual(a, b)
        XCTAssertNotNil(UUID(uuidString: a))
    }

    func testKeyPrefixValidation() {
        XCTAssertEqual(DuffelKey.validate("  duffel_test_abcdefghij \n"), "duffel_test_abcdefghij")
        XCTAssertNotNil(DuffelKey.validate("duffel_live_abcdefghij"))
        XCTAssertNil(DuffelKey.validate("sk_test_abcdefghij"))
        XCTAssertNil(DuffelKey.validate("duffel_test_"))
        XCTAssertNil(DuffelKey.validate("duffel_test_abc def ghij"))
    }
}
