import XCTest

final class SourcesUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Sources lists the sample, labelled as sample data, with its item count, and every phone source
    /// (epic #7 child 7) as a real row with its own switch — all off, so nothing has asked for a
    /// permission. Mail is labelled Online.
    func testSourcesListsEveryRowAndSampleStillWorks() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding"]
        app.launch()
        let count = app.staticTexts["sources.sample.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 30))
        XCTAssertEqual(count.label, "48 items")
        XCTAssertEqual(app.staticTexts["sources.sample.label"].label, "Sample data — not from your phone")
        XCTAssertTrue(app.staticTexts["Sample data"].exists)

        for id in ["photos", "files", "calendar", "contacts", "mail"] {
            let toggle = app.switches["sources.phone.\(id).toggle"]
            if !toggle.exists { app.swipeUp() }
            XCTAssertTrue(toggle.waitForExistence(timeout: 5), "no row for \(id)")
            XCTAssertEqual(toggle.value as? String, "0", "\(id) must be off until the user turns it on")
        }
        XCTAssertTrue(app.descendants(matching: .any)["sources.phone.mail.online"].exists, "Mail is labelled Online")
    }

    /// The Mail screen offers app-password IMAP and says plainly that Google / Microsoft sign-in needs
    /// an OAuth client ID (none is configured: owner-blocked).
    func testMailSetupSaysOAuthNeedsAClientId() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding"]
        app.launch()
        let setup = app.buttons["sources.phone.mail.setup"]
        for _ in 0..<4 where !setup.isHittable { app.swipeUp() }
        XCTAssertTrue(setup.waitForExistence(timeout: 10))
        setup.tap()
        XCTAssertTrue(app.textFields["mail.host"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.textFields["mail.host"].value as? String, "imap.mail.me.com")
        XCTAssertTrue(app.secureTextFields["mail.password"].exists)
        let google = app.descendants(matching: .any)["mail.oauth.google"]
        for _ in 0..<3 where !google.exists { app.swipeUp() }
        XCTAssertTrue(google.exists)
        XCTAssertTrue(google.label.contains("Needs a Google OAuth client ID"), google.label)
        XCTAssertTrue(app.descendants(matching: .any)["mail.oauth.microsoft"].label.contains("Needs a Microsoft OAuth client ID"))
    }
}
