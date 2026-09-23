import XCTest

final class UnsureQueueUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Now → Needs you → answer the first item → the count drops by one.
    func testAnsweringAnItemDropsTheCount() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeQueueDemo", "-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        let card = app.buttons["now.needsYou"]
        XCTAssertTrue(card.waitForExistence(timeout: 20))
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
}
