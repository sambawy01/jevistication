import XCTest

/// Me → Laya model. With no host (DEBUG -LoupeNoModelHost): the honest "not configured" state and
/// the consent copy. With the shipped Supabase host: Download is offered (never tapped here, so no
/// test makes a network request).
final class ModelDeliveryUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testModelScreenSaysNotConfiguredAndShowsTheConsentCopy() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "me", "-LoupeEphemeralKeychain", "-LoupeNoModelHost"]
        app.launch()
        let entry = app.descendants(matching: .any)["me.model"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
        XCTAssertTrue(app.descendants(matching: .any)["model.screen"].waitForExistence(timeout: 5))
        if app.descendants(matching: .any)["model.ready"].waitForExistence(timeout: 1) {
            throw XCTSkip("The model is side-loaded on this simulator; the not-configured state cannot show.")
        }
        let notConfigured = app.descendants(matching: .any)["model.notConfigured"]
        XCTAssertTrue(notConfigured.waitForExistence(timeout: 5))
        XCTAssertTrue(notConfigured.label.contains("Model host not configured"), notConfigured.label)
        XCTAssertFalse(app.buttons["model.download"].exists, "no Download button without a host")

        app.buttons["model.whatItInvolves"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["consent.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Download the on-device model?"].exists)
        let size = app.descendants(matching: .any)["consent.line.0"]
        XCTAssertTrue(size.label.contains("once") && size.label.contains("MB"), size.label)
        XCTAssertTrue(app.descendants(matching: .any)["consent.line.1"].label.contains("one-time download"))
        XCTAssertTrue(app.descendants(matching: .any)["consent.line.2"].label.contains("nothing else goes online"))
        XCTAssertTrue(app.descendants(matching: .any)["consent.notConfigured"].exists)
        XCTAssertFalse(app.buttons["consent.agree"].exists, "cannot agree to a download that has no host")
        app.buttons["consent.decline"].tap()
        XCTAssertTrue(notConfigured.waitForExistence(timeout: 5))
    }

    func testWithTheHostTheModelScreenOffersDownload() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "me", "-LoupeEphemeralKeychain", "-LoupeResetModelConsent"]
        app.launch()
        let entry = app.descendants(matching: .any)["me.model"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
        XCTAssertTrue(app.descendants(matching: .any)["model.screen"].waitForExistence(timeout: 5))
        if app.descendants(matching: .any)["model.ready"].waitForExistence(timeout: 1) {
            throw XCTSkip("The model is on this simulator; Download is not offered.")
        }
        XCTAssertTrue(app.buttons["model.download"].waitForExistence(timeout: 5), "the host is set: Download is offered")
        XCTAssertTrue(app.buttons["model.download"].label.contains("MB"))
        XCTAssertTrue(app.switches["model.cellular"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["model.notConfigured"].exists)
    }
}
