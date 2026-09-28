import XCTest

/// Me → Delete all my Loupe data (audit P1-4, 2026-09-27): two steps (a dialog, then DELETE typed), then Loupe
/// starts again. Under -LoupeFixtures the ledger and caches are throwaway folders, so this never touches real data.
final class DataEraseUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testDeleteAllMyDataAsksTwiceThenStartsAgain() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeModelState", "missing",
                               "-LoupeJudgmentDemo", "tax-receipt"]
        app.launch()
        XCTAssertTrue(app.buttons["results.menu"].waitForExistence(timeout: 15), "a judgment exists")
        let tabs = app.tabBars.firstMatch
        tabs.buttons["Me"].tap()
        // About, at the bottom: the version, Privacy policy, Terms, and no duplicate Web settings.
        let terms = app.buttons["me.terms"]
        for _ in 0..<10 where !(terms.exists && terms.isHittable) { app.swipeUp() }
        XCTAssertTrue(terms.exists)
        XCTAssertTrue(app.buttons["me.privacy"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["me.version"].exists, "About shows the version")
        XCTAssertFalse(app.buttons["Web settings"].exists, "the duplicate Web settings entry is gone")
        let erase = app.buttons["me.erase"]
        for _ in 0..<10 where !(erase.exists && erase.isHittable) { app.swipeDown() }
        XCTAssertTrue(erase.waitForExistence(timeout: 5))
        erase.tap()
        let proceed = app.buttons["Continue"].firstMatch
        XCTAssertTrue(proceed.waitForExistence(timeout: 5), "first step: a dialog")
        proceed.tap()
        XCTAssertTrue(app.navigationBars["Delete my data"].waitForExistence(timeout: 5), "second step: the sheet")
        XCTAssertEqual(app.switches["erase.keepModel"].value as? String, "1", "keep the model is the default")
        let field = app.textFields["erase.confirmField"]
        for _ in 0..<6 where !(field.exists && field.isHittable) { app.swipeUp() }
        XCTAssertTrue(field.waitForExistence(timeout: 5), "second step: type DELETE")
        let confirm = app.buttons["erase.confirm"]
        for _ in 0..<4 where !(confirm.exists && confirm.isHittable) { app.swipeUp() }
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertFalse(confirm.isEnabled, "nothing happens until DELETE is typed")
        field.tap()
        field.typeText("DELETE")
        XCTAssertTrue(confirm.isEnabled)
        confirm.tap()
        // Back at the start (onboarding is skipped here, so the places, on Home), and the judgment is gone.
        XCTAssertTrue(tabs.buttons["Home"].waitForExistence(timeout: 20))
        let deadline = Date().addingTimeInterval(20)
        while !tabs.buttons["Home"].isSelected && Date() < deadline { usleep(200_000) }
        XCTAssertTrue(tabs.buttons["Home"].isSelected)
        tabs.buttons["Ask"].tap()
        XCTAssertTrue(app.buttons["judgments.openLibrary"].waitForExistence(timeout: 10), "My judgments is empty")
    }
}
