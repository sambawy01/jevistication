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
    func testMailSetupOffersGmailSignInAndGatesOutlook() {
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
        let google = app.buttons["mail.oauth.google.signin"]
        for _ in 0..<3 where !google.exists { app.swipeUp() }
        XCTAssertTrue(google.exists, "the Google client ID is configured, so Gmail sign-in is offered")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'read-only'")).firstMatch.exists)
        XCTAssertTrue(app.descendants(matching: .any)["mail.oauth.microsoft"].label.contains("Needs a Microsoft OAuth client ID"))
    }

    /// A Photos scan over the fixture pictures (-LoupePhotosDemo: rendered pictures, read by Vision for real) shows
    /// the live scan display in the card, not a spinner and "Reading…": the stages with live counts, the masked
    /// ledger, the telemetry and "0 bytes out"; it ends in the summary, then the card rests on the new count.
    func testPhotosScanShowsTheLiveDisplayThenTheSummary() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupePhotosDemo"]
        app.launch()
        XCTAssertTrue(app.staticTexts["sources.sample.count"].waitForExistence(timeout: 30))
        let toggle = app.switches["sources.phone.photos.toggle"]
        for _ in 0..<3 where !toggle.isHittable { app.swipeUp() }
        toggle.tap()

        let display = app.descendants(matching: .any)["sources.scan.photos"]
        XCTAssertTrue(display.waitForExistence(timeout: 15), "the live display replaces the spinner")
        XCTAssertEqual(display.value as? String, "animated")
        XCTAssertFalse(app.staticTexts["Reading…"].exists)
        XCTAssertFalse(app.activityIndicators["sources.phone.photos.progress"].exists)
        for stage in ["read", "text", "saved"] {
            XCTAssertTrue(app.descendants(matching: .any)["sources.scan.stage.\(stage)"].exists, "no \(stage) stage")
        }
        XCTAssertFalse(app.descendants(matching: .any)["sources.scan.stage.fetch"].exists, "Photos fetches nothing")
        XCTAssertTrue(app.descendants(matching: .any)["sources.scan.bytesOut"].exists)
        let live = app.descendants(matching: .any)["sources.scan.live"]
        let reading = NSPredicate(format: "label BEGINSWITH 'Photos: ' AND label CONTAINS ' of 16 read'")
        expectation(for: reading, evaluatedWith: live)
        waitForExpectations(timeout: 30)
        // Text read from a picture reaches the ledger masked.
        let ledger = app.descendants(matching: .any)["sources.scan.ledger.0"]
        XCTAssertTrue(ledger.waitForExistence(timeout: 30))

        let summary = app.staticTexts["sources.scan.summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 90), "the scan ends in its summary")
        XCTAssertTrue(summary.label.hasPrefix("16 photos · "), summary.label)
        XCTAssertTrue(summary.label.contains("with text"), summary.label)
        XCTAssertTrue(summary.label.hasSuffix("0 bytes out"), summary.label)
        XCTAssertEqual(app.descendants(matching: .any)["sources.scan.stage.saved"].value as? String, "16")

        let count = app.staticTexts["sources.phone.photos.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 15), "the card returns to rest")
        XCTAssertEqual(count.label, "16 items")
        XCTAssertFalse(display.exists)
    }

    /// Reduce Motion: the same live display with the same numbers, marked static (no sliding, sweeping or flowing).
    func testPhotosScanUnderReduceMotionIsStaticWithTheSameInformation() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupePhotosDemo",
                               "-LoupeScanDemo", "photos", "-LoupeReduceMotion"]
        app.launch()
        let display = app.descendants(matching: .any)["sources.scan.photos"]
        XCTAssertTrue(display.waitForExistence(timeout: 30))
        XCTAssertEqual(display.value as? String, "static")
        XCTAssertTrue(app.descendants(matching: .any)["sources.scan.stage.text"].exists)
        XCTAssertTrue(app.staticTexts["sources.scan.summary"].waitForExistence(timeout: 90))
    }
}
