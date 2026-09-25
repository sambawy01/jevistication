import XCTest

final class LoupeUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testWebTabShowsKeyOnboarding() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeEphemeralKeychain", "-LoupeSkipOnboarding"]
        app.launch()
        app.tabBars.buttons["Web"].tap()
        // Flights is a development-only template (owner decision 2026-09-25).
        let flights = app.descendants(matching: .any)["web.template.flights"].firstMatch
        for _ in 0..<4 where !(flights.exists && flights.isHittable) { app.swipeUp() }
        flights.tap()
        XCTAssertTrue(app.descendants(matching: .any)["web.flights.devBanner"].waitForExistence(timeout: 5))
        let addKey = app.buttons["web.onboarding.addKey"]
        XCTAssertTrue(addKey.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Flights uses your own Duffel key. Searches go online through Loupe's helper; your files, mail and judgments never do."].exists)
        addKey.tap()
        XCTAssertTrue(app.secureTextFields["web.key.field"].waitForExistence(timeout: 3))
    }

    func testFixtureSearchShowsRankedResults() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "web", "-LoupeSkipOnboarding"]
        app.launch()
        let flights = app.descendants(matching: .any)["web.template.flights"].firstMatch
        XCTAssertTrue(flights.waitForExistence(timeout: 10))
        for _ in 0..<4 where !flights.isHittable { app.swipeUp() }
        flights.tap()
        let go = app.buttons["search.go"]
        XCTAssertTrue(go.waitForExistence(timeout: 5))
        go.tap()
        XCTAssertTrue(app.staticTexts["TAP Air Portugal"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["results.testBanner"].exists)
    }
}
