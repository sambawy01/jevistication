import XCTest
@testable import Loupe

/// Me's row values (spec 2026-09-28 §3, mockup #6).
final class MeModelTests: XCTestCase {
    func testReadsLine() {
        XCTAssertEqual(MeModel.readsLine(on: 0), "none on")
        XCTAssertEqual(MeModel.readsLine(on: 1), "1 on")
        XCTAssertEqual(MeModel.readsLine(on: 5), "5 on")
    }

    func testMailLine() {
        XCTAssertEqual(MeModel.mailLine(account: nil, on: false), "No mailbox yet")
        XCTAssertEqual(MeModel.mailLine(account: "me@example.com", on: true), "me@example.com")
        XCTAssertEqual(MeModel.mailLine(account: "me@example.com", on: false), "me@example.com · off")
    }

    func testAssistantLineIsAPlaceholderForThePaidTier() {
        XCTAssertEqual(MeModel.assistantLine(enabled: false, ready: false), "Unlock")
        XCTAssertEqual(MeModel.assistantLine(enabled: true, ready: false), "Needs setup")
        XCTAssertEqual(MeModel.assistantLine(enabled: true, ready: true), "On · Online")
    }
}
