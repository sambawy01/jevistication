import XCTest

final class SourcesUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Sources has no sample (owner decision 2026-09-28: no sample data in the app) and lists every phone source
    /// (epic #7 child 7) as a real row with its own switch: the on-device ones on by default (owner decision
    /// 2026-09-26), Mail off until a mailbox is added. Mail is labelled Online. Nothing was read at launch.
    func testSourcesListsEveryPhoneSourceAndNoSample() {
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding"]
        app.launch()
        XCTAssertTrue(app.switches["sources.phone.photos.toggle"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.descendants(matching: .any)["sources.sample"].exists, "no sample card")
        XCTAssertFalse(app.staticTexts["Sample data"].exists)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "sample")).firstMatch.exists,
                       "no word of a sample on Sources")
        XCTAssertEqual(app.staticTexts["debug.fixture.count"].label, "0 items", "the hidden test fixture was not read at launch")

        for id in ["photos", "files", "calendar", "contacts"] {
            let toggle = app.switches["sources.phone.\(id).toggle"]
            if !toggle.exists { app.swipeUp() }
            XCTAssertTrue(toggle.waitForExistence(timeout: 5), "no row for \(id)")
            XCTAssertEqual(toggle.value as? String, "1", "\(id) is on by default")
        }
        XCTAssertFalse(app.switches["sources.phone.mail.toggle"].exists, "Mail is one place: its switch is on the Mail screen")
        let mail = app.buttons["sources.mail"]
        for _ in 0..<4 where !mail.isHittable { app.swipeUp() }
        mail.tap()
        let toggle = app.switches["sources.phone.mail.toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        XCTAssertEqual(toggle.value as? String, "0", "Mail waits for a sign-in")
        XCTAssertTrue(app.descendants(matching: .any)["sources.phone.mail.online"].exists, "Mail is labelled Online")
    }

    /// The Mail screen offers app-password IMAP and says plainly that Google / Microsoft sign-in needs
    /// an OAuth client ID (none is configured: owner-blocked).
    func testMailSetupOffersGmailSignInAndGatesOutlook() {
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "mail", "-LoupeSkipOnboarding"]
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
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupePhotosDemo"]
        app.launch()
        // Photos is on by default; iOS has not asked yet, so the card offers Allow access, which asks and reads.
        let allow = app.buttons["sources.phone.photos.allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 30))
        for _ in 0..<3 where !(allow.exists && allow.isHittable) { app.swipeUp() }
        XCTAssertEqual(app.switches["sources.phone.photos.toggle"].value as? String, "1")
        allow.tap()

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
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupePhotosDemo",
                               "-LoupeScanDemo", "photos", "-LoupeReduceMotion"]
        app.launch()
        let display = app.descendants(matching: .any)["sources.scan.photos"]
        XCTAssertTrue(display.waitForExistence(timeout: 30))
        XCTAssertEqual(display.value as? String, "static")
        XCTAssertTrue(app.descendants(matching: .any)["sources.scan.stage.text"].exists)
        // It ends: the summary settles for 4 s (a busy snapshot can miss that window), then the card rests on its count.
        let summary = app.staticTexts["sources.scan.summary"]
        let count = app.staticTexts["sources.phone.photos.count"]
        let deadline = Date().addingTimeInterval(120)
        while !(summary.exists || (count.exists && count.label == "16 items")) && Date() < deadline { usleep(300_000) }
        XCTAssertTrue(summary.exists || count.label == "16 items", "the scan ended in its summary and count")
    }
}
