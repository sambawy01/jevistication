import XCTest

/// Me → Model settings: turn Laya off for the privacy check, run it, see the banner, and Reset to
/// default from the banner's Turn it on puts it back (and the banner goes on the next run).
final class ModelSettingsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func tapSwitch(_ sw: XCUIElement) {
        // A SwiftUI Toggle in a List: tap the switch itself, at the trailing end of the row.
        sw.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, max: Int = 25) {
        for _ in 0..<max where !(element.exists && element.isHittable) { app.swipeUp() }
    }

    func testTurnLayaOffRunSeeTheBannerAndResetRestores() {
        let app = XCUIApplication()
        // -LoupeFixtures: settings and ledger live in a throwaway directory.
        app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeFixtures", "-LoupeLanguage", "en"]
        app.launch()

        let open = app.buttons["me.modelSettings"]
        XCTAssertTrue(open.waitForExistence(timeout: 30))
        open.tap()

        let toggle = app.switches["settings.features.scan.use_laya"]
        XCTAssertTrue(app.navigationBars["Model settings"].waitForExistence(timeout: 10))
        scrollTo(toggle, in: app)
        XCTAssertTrue(toggle.exists)
        XCTAssertEqual(toggle.value as? String, "1")
        tapSwitch(toggle)
        XCTAssertEqual(toggle.value as? String, "0", "Laya is now off for the privacy check")
        XCTAssertTrue(app.buttons["settings.features.scan.use_laya.reset"].exists, "a changed row offers Reset to default")

        // Run the privacy check from Now.
        app.tabBars.buttons["Now"].tap()
        let card = app.buttons["now.privacy"]
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        card.tap()
        let rerun = app.buttons["privacy.rerun"]
        XCTAssertTrue(rerun.waitForExistence(timeout: 30))
        rerun.tap()
        let banner = app.descendants(matching: .any)["banner.layaOff.scan"]
        XCTAssertTrue(banner.waitForExistence(timeout: 30), "the run had Laya off: the banner says so")
        XCTAssertTrue(app.staticTexts["The decision model was off for this run — answers come from rules only."].exists)

        // Turn it on → Model settings at the feature → Reset to default.
        app.buttons["banner.layaOff.scan.turnOn"].tap()
        let reset = app.buttons["settings.features.scan.use_laya.reset"]
        XCTAssertTrue(app.navigationBars["Model settings"].waitForExistence(timeout: 10))
        scrollTo(reset, in: app)
        XCTAssertTrue(reset.exists, "the sheet opens at the feature, where its changed row offers Reset")
        reset.tap()
        let sheetToggle = app.switches["settings.features.scan.use_laya"]
        XCTAssertTrue(sheetToggle.waitForExistence(timeout: 5))
        XCTAssertEqual(sheetToggle.value as? String, "1", "Reset restored the default: Laya on")
        XCTAssertFalse(app.buttons["settings.features.scan.use_laya.reset"].exists)
        app.buttons["settings.done"].tap()

        // The next run has Laya on: the banner goes.
        XCTAssertTrue(rerun.waitForExistence(timeout: 10))
        rerun.tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: banner)
        waitForExpectations(timeout: 30)
    }
}
