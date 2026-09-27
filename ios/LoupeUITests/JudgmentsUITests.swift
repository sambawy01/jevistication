import XCTest

final class JudgmentsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Judgments → Library → a template → Use this → it appears in My judgments.
    func testUseATemplateAppearsInMyJudgments() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "judgments", "-LoupeSkipOnboarding"]
        app.launch()
        let open = app.buttons["judgments.openLibrary"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        open.tap()
        let search = app.textFields["library.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("tax time")
        let template = app.buttons["library.template.tax-receipt"]
        XCTAssertTrue(template.waitForExistence(timeout: 5))
        template.tap()
        let use = app.buttons["template.use"]
        XCTAssertTrue(use.waitForExistence(timeout: 5))
        use.tap()
        XCTAssertTrue(app.buttons["Added to My judgments"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.segmentedControls["judgments.section"].buttons["My judgments"].tap()
        let mine = app.buttons["judgments.mine.tax-receipt"]
        XCTAssertTrue(mine.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Receipts for tax time"].exists)
    }

    /// Audit P1-3 (2026-09-27): the results screen's menu edits the wording and deletes, asking first; the long-press
    /// Delete on My judgments asks too. Packs and Write your own show their text.
    func testEditAndDeleteFromTheResultsMenu() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeModelState", "missing",
                               "-LoupeJudgmentDemo", "tax-receipt"]
        app.launch()
        let menu = app.buttons["results.menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        let edit = app.buttons["Edit wording…"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let title = app.textFields["edit.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        title.typeText(" 2")
        app.buttons["edit.save"].tap()
        XCTAssertTrue(app.navigationBars["Receipts for tax time 2"].waitForExistence(timeout: 5), "the new name is saved and shown")

        menu.tap()
        let delete = app.buttons["Delete judgment…"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        let confirm = app.buttons["Delete judgment"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "Delete asks first")
        confirm.tap()
        let mine = app.buttons["judgments.mine.tax-receipt"]
        XCTAssertTrue(app.buttons["judgments.openLibrary"].waitForExistence(timeout: 5), "back on an empty My judgments")
        XCTAssertFalse(mine.exists)
        XCTAssertEqual(app.buttons["judgments.write"].label, "Write your own")
        XCTAssertEqual(app.buttons["judgments.packs"].label, "Packs")
    }

    func testLongPressDeleteAsksFirst() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeModelState", "missing",
                               "-LoupeJudgmentDemo", "tax-receipt"]
        app.launch()
        XCTAssertTrue(app.buttons["results.menu"].waitForExistence(timeout: 15))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let mine = app.buttons["judgments.mine.tax-receipt"]
        XCTAssertTrue(mine.waitForExistence(timeout: 5))
        mine.press(forDuration: 1.2)
        let delete = app.buttons["Delete…"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(app.buttons["Delete judgment"].waitForExistence(timeout: 5), "asks before deleting")
        if cancel.exists { cancel.tap() } else { app.tap() }
        XCTAssertTrue(mine.waitForExistence(timeout: 5), "cancelled: still there")
    }
}
