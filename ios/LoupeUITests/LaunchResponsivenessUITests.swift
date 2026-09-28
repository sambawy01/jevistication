import XCTest

/// 2026-09-28: over a big ledger (thousands of long files) the Unsure count was drawn in Now's body and
/// the launch hung until iOS killed the app (0x8BADF00D). With 3,000 long items and 3,000 model answers
/// seeded (`-LoupeBigLedger`), Home must be interactive at once and the main thread must never stall.
final class LaunchResponsivenessUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testNowIsInteractiveAtOnceOverABigLedger() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeBigLedger", "3000", "-LoupeMainWatchdog", "-LoupeTab", "home",
                               "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        let launched = Date()
        app.launch()
        let ask = app.tabBars.buttons["Ask"]
        XCTAssertTrue(ask.waitForExistence(timeout: 3), "the tab bar is up within 3 s")

        // Taps and a scroll register at once while the ledger is seeded and the count drawn behind them.
        ask.tap()
        app.tabBars.buttons["Home"].tap()
        XCTAssertTrue(app.buttons["home.money"].waitForExistence(timeout: 3) || app.scrollViews.firstMatch.exists)
        app.scrollViews.firstMatch.swipeUp()
        app.scrollViews.firstMatch.swipeDown()
        XCTAssertLessThan(Date().timeIntervalSince(launched), 20, "launch, two tab switches and a scroll")

        // The count lands (in the background) and the queue's door, at the top of Ask, opens on it.
        app.tabBars.buttons["Ask"].tap()
        let card = app.buttons["ask.needsYou"]
        XCTAssertTrue(card.waitForExistence(timeout: 90), "the big ledger is seeded")
        let counted = NSPredicate(format: "label CONTAINS %@", "Needs you: 50")
        expectation(for: counted, evaluatedWith: card)
        waitForExpectations(timeout: 60)
        app.tabBars.buttons["Me"].tap()
        app.tabBars.buttons["Home"].tap()

        let stall = app.staticTexts["debug.mainStall"]
        XCTAssertTrue(stall.waitForExistence(timeout: 5))
        let worst = Int(stall.label) ?? Int.max
        print("MAIN-STALL worst=\(worst)ms")
        XCTAssertLessThan(worst, 500, "the main thread never stalled 500 ms (worst \(worst) ms)")
    }
}
