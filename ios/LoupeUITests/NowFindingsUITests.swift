import XCTest

final class NowFindingsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Now runs the watchers over the sample and shows the +23% renewal premium, with its evidence.
    func testNowShowsThePremiumFindingFromTheSample() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["now.topFinding"].waitForExistence(timeout: 60))
        let premium = app.staticTexts["Annual premium up 23%"]
        for _ in 0..<8 where !(premium.exists && premium.isHittable) { app.swipeUp() }
        XCTAssertTrue(premium.waitForExistence(timeout: 5))
        let evidence = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "£450.00 → £553.50 (+23%)")).firstMatch
        XCTAssertTrue(evidence.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", "Sample")).firstMatch.exists)
    }
}
