import XCTest

/// Laya is required (owner rule 2026-09-25): first launch without the model opens on Get Laya;
/// "Later" leads to the app with model features locked; with the model there is no such step.
/// `-LoupeModelState` stands in for the 400 MB files (the shipped manifest has no host).
final class GetLayaUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    /// The one-time steps may follow Get the model (depends on this simulator's history): the permissions step,
    /// protection, then the game intro. Each is skipped when shown.
    private func passIntroIfShown(_ app: XCUIApplication) {
        let permissions = app.buttons["permissions.skip"]
        if permissions.waitForExistence(timeout: 3) { permissions.tap() }
        let protect = app.buttons["protect.step.continue"]
        if protect.waitForExistence(timeout: 2) { protect.tap() }
        let skip = app.buttons["onboarding.skip"]
        if skip.waitForExistence(timeout: 3) { skip.tap() }
    }

    /// Scrolls Me's list until `el` exists (the Sorting section is below the fold).
    private func scrollTo(_ el: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<6 where !el.exists { app.swipeUp() }
    }

    func testFirstLaunchWithoutTheModelShowsGetLayaThenLaterLocksModelFeatures() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeModelState", "missing", "-LoupeNoModelHost", "-LoupeTab", "me", "-LoupeEphemeralKeychain", "-LoupeFixtures"]
        app.launch()

        // Get Laya comes first, before the tabs.
        XCTAssertTrue(any(app, "getLaya.screen").waitForExistence(timeout: 10))
        XCTAssertFalse(app.tabBars.firstMatch.exists, "Get Laya is shown before the main tabs")
        let explainer = any(app, "getLaya.explainer")
        XCTAssertTrue(explainer.label.contains("MB") && explainer.label.contains("on this iPhone"), explainer.label)
        // This build has no model host: it says so and offers no Download that cannot work.
        XCTAssertTrue(any(app, "getLaya.notConfigured").exists)
        XCTAssertFalse(app.buttons["getLaya.download"].exists)

        // Later: into the app, limited.
        app.buttons["getLaya.later"].tap()
        passIntroIfShown(app)
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5))

        // A feature that needs Laya shows the locked state instead of its controls.
        let locked = any(app, "needsLaya.sort")
        scrollTo(locked, in: app)
        XCTAssertTrue(locked.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Needs the decision model"].exists || locked.label.contains("Needs the decision model"))
        XCTAssertFalse(app.buttons["me.sort.run"].exists, "Run now is not offered while Laya is missing")

        // Its button opens Get Laya; Not now returns.
        app.buttons["needsLaya.sort.get"].tap()
        XCTAssertTrue(any(app, "getLaya.screen").waitForExistence(timeout: 5))
        app.buttons["getLaya.later"].tap()
        XCTAssertTrue(locked.waitForExistence(timeout: 5))

        // A rules-only feature keeps working: the privacy check is on Now and opens.
        app.tabBars.buttons["Now"].tap()
        let privacy = app.buttons["now.privacy"]
        for _ in 0..<6 where !privacy.isHittable { app.swipeUp() }
        XCTAssertTrue(privacy.waitForExistence(timeout: 5))
        XCTAssertFalse(any(app, "needsLaya.privacy").exists)
    }

    /// The shipped build has a host: Download and the consent (with the mobile-data toggle). The
    /// consent is reset and declined, so nothing is ever downloaded by this test.
    func testWithAHostGetLayaOffersDownloadThroughTheConsent() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeModelState", "missing", "-LoupeResetModelConsent", "-LoupeTab", "now", "-LoupeEphemeralKeychain", "-LoupeFixtures"]
        app.launch()
        XCTAssertTrue(any(app, "getLaya.screen").waitForExistence(timeout: 10))
        XCTAssertFalse(any(app, "getLaya.notConfigured").exists)
        let download = app.buttons["getLaya.download"]
        XCTAssertTrue(download.waitForExistence(timeout: 5))
        XCTAssertTrue(download.label.contains("MB"), download.label)
        XCTAssertTrue(app.switches["getLaya.cellular"].exists)
        download.tap()                                      // no consent yet: opens the consent sheet
        XCTAssertTrue(any(app, "consent.screen").waitForExistence(timeout: 5))
        XCTAssertTrue(app.switches["consent.cellular"].exists)
        XCTAssertTrue(app.buttons["consent.agree"].exists, "the host is set: agreeing would start the download")
        XCTAssertFalse(any(app, "consent.notConfigured").exists)
        app.buttons["consent.decline"].tap()                // never agree: no request in tests
        XCTAssertTrue(download.waitForExistence(timeout: 5))
        XCTAssertFalse(any(app, "getLaya.progress").exists)
    }

    func testWithTheModelThereIsNoGetLayaStep() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeModelState", "ready", "-LoupeTab", "me", "-LoupeEphemeralKeychain", "-LoupeFixtures"]
        app.launch()
        passIntroIfShown(app)
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(any(app, "getLaya.screen").exists, "no Get Laya step when the model is here")
        let run = app.buttons["me.sort.run"]
        scrollTo(run, in: app)
        XCTAssertTrue(run.waitForExistence(timeout: 5), "model features are unlocked")
        XCTAssertFalse(any(app, "needsLaya.sort").exists)
    }
}
