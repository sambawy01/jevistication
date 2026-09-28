import XCTest

final class MailTriageUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Me → Mail shows the mailbox, what was found, and the sample's PayPal phishing first with its signals.
    func testMailShowsThePaypalPhishingAndItsSignals() {
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeFixtures", "-LoupeRunNow", "-LoupeTab", "mail", "-LoupeSkipOnboarding"]
        app.launch()
        XCTAssertTrue(app.switches["sources.phone.mail.toggle"].waitForExistence(timeout: 20), "the mailbox is on the Mail screen")
        let found = app.descendants(matching: .any)["mail.found"]
        XCTAssertTrue(found.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "NOT (label BEGINSWITH %@)", "Phishing: 0"), evaluatedWith: found)
        waitForExpectations(timeout: 60)
        XCTAssertTrue(app.descendants(matching: .any)["mail.section.phishing"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["mail.rerun"].exists, "a re-run on the screen itself (audit P2-11)")
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
