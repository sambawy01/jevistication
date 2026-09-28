import XCTest

/// 2026-09-28: over a big ledger (thousands of long files) the Unsure count was drawn in Now's body and
/// the launch hung until iOS killed the app (0x8BADF00D). With 3,000 long items and 3,000 model answers
/// seeded (`-LoupeBigLedger`), Now must be interactive at once and the main thread must never stall.
final class LaunchResponsivenessUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testNowIsInteractiveAtOnceOverABigLedger() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeBigLedger", "3000", "-LoupeMainWatchdog", "-LoupeTab", "now",
                               "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        let launched = Date()
        app.launch()
        let judgments = app.tabBars.buttons["Judgments"]
        XCTAssertTrue(judgments.waitForExistence(timeout: 3), "the tab bar is up within 3 s")

        // Taps and a scroll register at once while the ledger is seeded and the count drawn behind them.
        judgments.tap()
        app.tabBars.buttons["Now"].tap()
        XCTAssertTrue(app.buttons["now.review"].waitForExistence(timeout: 3) || app.scrollViews.firstMatch.exists)
        app.scrollViews.firstMatch.swipeUp()
        app.scrollViews.firstMatch.swipeDown()
        XCTAssertLessThan(Date().timeIntervalSince(launched), 20, "launch, two tab switches and a scroll")

        // The count lands (in the background) and the queue opens on it.
        let card = app.buttons["now.needsYou"]
        XCTAssertTrue(card.waitForExistence(timeout: 90), "the big ledger is seeded")
        let counted = NSPredicate(format: "label CONTAINS %@", "Needs you: 50")
        expectation(for: counted, evaluatedWith: card)
        waitForExpectations(timeout: 60)
        app.tabBars.buttons["Me"].tap()
        app.tabBars.buttons["Now"].tap()

        let stall = app.staticTexts["debug.mainStall"]
        XCTAssertTrue(stall.waitForExistence(timeout: 5))
        let worst = Int(stall.label) ?? Int.max
        print("MAIN-STALL worst=\(worst)ms")
        XCTAssertLessThan(worst, 500, "the main thread never stalled 500 ms (worst \(worst) ms)")
    }
}
