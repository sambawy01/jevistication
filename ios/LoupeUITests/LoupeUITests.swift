import XCTest

final class LoupeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testWebTabShowsKeyOnboarding() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeEphemeralKeychain"]
        app.launch()
        app.tabBars.buttons["Web"].tap()
        let addKey = app.buttons["web.onboarding.addKey"]
        XCTAssertTrue(addKey.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Flights uses your own Duffel key. Searches go online through Loupe's helper; your files, mail and judgments never do."].exists)
        addKey.tap()
        XCTAssertTrue(app.secureTextFields["web.key.field"].waitForExistence(timeout: 3))
    }

    func testFixtureSearchShowsRankedResults() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "web"]
        app.launch()
        let go = app.buttons["search.go"]
        XCTAssertTrue(go.waitForExistence(timeout: 5))
        go.tap()
        XCTAssertTrue(app.staticTexts["TAP Air Portugal"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["results.testBanner"].exists)
    }
}
