import XCTest

final class UnsureQueueUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Ask → Needs you → answer the first item → the count drops by one.
    func testAnsweringAnItemDropsTheCount() {
        let app = XCUIApplication()
        // The demo's stand-in scorer plays the model: the model reads as ready (it is required, 2026-09-25).
        app.launchArguments = ["-LoupeFixtures", "-LoupeQueueDemo", "-LoupeTab", "ask", "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Ask"].waitForExistence(timeout: 20), "Ask hosts today's judgments")
        let card = app.buttons["ask.needsYou"]
        XCTAssertTrue(card.waitForExistence(timeout: 30))
        card.tap()
        let count = app.staticTexts["queue.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 5))
        let before = Int(count.label.replacingOccurrences(of: "Needs you: ", with: "")) ?? -1
        XCTAssertGreaterThan(before, 0)
        let option = app.buttons["queue.option.0"]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        let dropped = NSPredicate(format: "label == %@", "Needs you: \(before - 1)")
        expectation(for: dropped, evaluatedWith: count)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(app.buttons["queue.undo"].isEnabled)
    }

    /// `-LoupeOpen queue` opens Ask with the Unsure queue pushed (the badge's door in phase 1).
    func testOpenQueueLaunchesStraightIntoTheQueue() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeQueueDemo", "-LoupeTab", "now", "-LoupeOpen", "queue",
                               "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        app.launch()
        XCTAssertTrue(app.staticTexts["queue.count"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.tabBars.buttons["Ask"].isSelected)
    }
}
