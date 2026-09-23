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
}
