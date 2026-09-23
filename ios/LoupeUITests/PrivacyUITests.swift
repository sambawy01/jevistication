import XCTest

final class PrivacyUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Now → Privacy check shows the sample's SPECIMEN ID documents and duplicate receipts.
    func testNowOpensPrivacyCheckWithSampleFindings() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        let card = app.buttons["now.privacy"]
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        let loaded = NSPredicate(format: "label CONTAINS %@", "findings")
        expectation(for: loaded, evaluatedWith: card)
        waitForExpectations(timeout: 60)
        card.tap()
        XCTAssertTrue(app.descendants(matching: .any)["privacy.group.ids"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["documents/identity/passport-scan-SPECIMEN.txt"].exists)
        XCTAssertTrue(app.staticTexts["documents/identity/driving-licence-SPECIMEN.txt"].exists)
        let dups = app.descendants(matching: .any)["privacy.group.duplicates"]
        for _ in 0..<6 where !dups.exists { app.swipeUp() }
        XCTAssertTrue(dups.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "fresh-basket-2026-08-14 (copy).txt")).firstMatch.exists)
    }
}
