import XCTest
import Security
@testable import Loupe

final class KeychainTests: XCTestCase {
    var store: KeychainStore!

    override func setUp() {
        store = KeychainStore(service: "dev.loupe.app.tests.\(UUID().uuidString)", account: "test")
    }
    override func tearDown() { try? store.delete() }

    func testRoundTripAccessibilityAndDelete() throws {
        XCTAssertNil(store.read())
        XCTAssertEqual(store.queryStatus(), errSecItemNotFound)
        try store.save("duffel_test_one")
        XCTAssertEqual(store.read(), "duffel_test_one")
        XCTAssertEqual(store.accessibility(), kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        try store.save("duffel_test_two")      // overwrite, not duplicate
        XCTAssertEqual(store.read(), "duffel_test_two")
        try store.delete()
        XCTAssertNil(store.read())
        XCTAssertEqual(store.queryStatus(), errSecItemNotFound)
        XCTAssertNoThrow(try store.delete())   // idempotent
    }

    func testServicesAreIsolated() throws {
        let other = KeychainStore(service: store.service + ".other", account: "test")
        try store.save("duffel_test_a")
        XCTAssertNil(other.read())
    }
}
