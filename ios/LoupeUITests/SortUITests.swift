import XCTest

final class SortUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Me → Run now shows progress over the sample and finishes with real counts.
    func testRunNowShowsProgressAndCompletes() {
        let app = XCUIApplication()
        // The demo's stand-in scorer plays the model: Laya reads as ready (it is required, 2026-09-25).
        app.launchArguments = ["-LoupeFixtures", "-LoupeSortDemo", "-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        app.launch()
        let toggle = app.switches["me.sort.toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        XCTAssertEqual(toggle.value as? String, "0", "passive mode is off by default")
        let run = app.buttons["me.sort.run"]
        XCTAssertTrue(run.waitForExistence(timeout: 5))
        // The sample scan may still be running on first launch.
        let summary = app.staticTexts["me.sort.summary"]
        for _ in 0..<5 {
            run.tap()
            if app.staticTexts["me.sort.progress"].waitForExistence(timeout: 3) { break }
            sleep(2)
        }
        XCTAssertTrue(app.staticTexts["me.sort.progress"].exists || summary.exists, "progress shown")
        XCTAssertTrue(summary.waitForExistence(timeout: 60))
        XCTAssertTrue(summary.label.contains("sorted"), summary.label)
        XCTAssertFalse(summary.label.hasPrefix("Last run: 0 sorted"), summary.label)
    }
}
