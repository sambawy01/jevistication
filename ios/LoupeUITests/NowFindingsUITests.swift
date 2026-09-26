import XCTest

final class NowFindingsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Now runs the watchers over the sample and shows the newest findings; Guard has every one of them, among them
    /// the +23% renewal premium with its evidence.
    func testNowShowsTheNewestFindingsAndGuardThePremium() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["now.topFinding"].waitForExistence(timeout: 60))
        let door = app.buttons["now.guard"]
        for _ in 0..<10 where !(door.exists && door.isHittable) { app.swipeUp() }
        XCTAssertTrue(door.exists)
        XCTAssertTrue(app.descendants(matching: .any)["finding.0"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["finding.2"].exists, "Now shows the newest two, Guard the rest")
        door.tap()
        let premium = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Annual premium up 23%")).firstMatch
        for _ in 0..<14 where !(premium.exists && premium.isHittable) { app.swipeUp() }
        XCTAssertTrue(premium.waitForExistence(timeout: 5))
        XCTAssertTrue(premium.label.contains("£450.00 → £553.50 (+23%)"), premium.label)
        XCTAssertTrue(premium.label.contains("sample data"), premium.label)
        premium.tap()
        let evidence = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "£450.00 → £553.50 (+23%)")).firstMatch
        XCTAssertTrue(evidence.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", "Sample")).firstMatch.exists)
    }
}
