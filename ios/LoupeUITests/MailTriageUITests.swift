import XCTest

final class MailTriageUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Now → Mail triage shows the sample's PayPal phishing first, with the signals behind it.
    func testNowOpensMailTriageWithThePaypalPhishingAndItsSignals() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        let card = app.buttons["now.mail"]
        for _ in 0..<4 where !card.waitForExistence(timeout: 15) { app.swipeUp() }
        XCTAssertTrue(card.exists)
        let loaded = NSPredicate(format: "label CONTAINS %@", "possible phishing")
        expectation(for: loaded, evaluatedWith: card)
        waitForExpectations(timeout: 60)
        card.tap()
        XCTAssertTrue(app.descendants(matching: .any)["mail.section.phishing"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Your account has been limited"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["mail.flag.phishing"].exists)
        let lookalike = app.staticTexts["mail.signal.sender_lookalike_brand"]
        XCTAssertTrue(lookalike.exists)
        XCTAssertTrue(lookalike.label.contains("looks like PayPal"))
        XCTAssertTrue(app.staticTexts["mail.signal.display_brand_mismatch"].exists)
        XCTAssertTrue(app.staticTexts["mail.signal.link_brand_in_subdomain"].exists)
        XCTAssertTrue(app.staticTexts["mail.signal.urgent_language"].exists)
    }
}
