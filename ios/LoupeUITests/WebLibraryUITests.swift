import XCTest

/// The Web tab's template library (owner decision 2026-09-25), in fixture mode (bundled schema-v1
/// answers, no network): Web → Currency → a variant → ranked rows with the Online label and the
/// required attribution; the custom question flow; NOT_CONFIGURED; Flights labelled dev-only.
/// Saves the design screenshots `web-*.png`.
final class WebLibraryUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
        try? FileManager.default.createDirectory(atPath: LiveRunUITests.shots, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: LiveRunUITests.shots + "/\(name).png"))
    }

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "web", "-LoupeSkipOnboarding", "-LoupeLanguage", "en"] + extra
        app.launch()
        return app
    }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    private func scrollTo(_ el: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 where !(el.exists && el.isHittable) { app.swipeUp() }
    }

    func testCurrencyVariantShowsRankedResultsWithAttribution() {
        let app = launch()
        let card = any(app, "web.template.currency")
        XCTAssertTrue(card.waitForExistence(timeout: 10))
        save("web-01-library")
        card.tap()
        // Off by default (§4a): the source says what it would send before anything is fetched.
        let turnOn = app.buttons["web.source.turnOn"]
        XCTAssertTrue(turnOn.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["web.ask"].exists)
        turnOn.tap()
        let variant = any(app, "web.variant.currency.rankVsHome")
        scrollTo(variant, in: app)
        variant.tap()
        let ask = app.buttons["web.ask"]
        scrollTo(ask, in: app)
        save("web-02-currency-questions")
        ask.tap()
        let badge = any(app, "web.onlineBadge")
        XCTAssertTrue(badge.waitForExistence(timeout: 10))
        XCTAssertTrue(badge.label.contains("Online · Frankfurter · fetched"), badge.label)
        XCTAssertTrue(any(app, "web.fixtureBanner").exists)
        // The rule baseline always answers; Laya answers beside it when the model is on this
        // simulator, else the rules-only banner says why.
        XCTAssertTrue(any(app, "web.answer.rules").waitForExistence(timeout: 10))
        let done = NSPredicate { [self] _, _ in any(app, "web.answer.rulesOnly").exists || any(app, "web.answer.laya").exists }
        expectation(for: done, evaluatedWith: nil)
        waitForExpectations(timeout: 120)
        XCTAssertTrue(any(app, "web.answer.rules").label.contains("#1"), any(app, "web.answer.rules").label)
        let rows = app.descendants(matching: .any).matching(identifier: "web.row")
        XCTAssertGreaterThanOrEqual(rows.count, 4)
        XCTAssertTrue(app.staticTexts["#1"].exists && app.staticTexts["#2"].exists)
        XCTAssertTrue(any(app, "web.row.link").exists, "each row links to the provider's record")
        save("web-03-currency-ranked")
        let attribution = any(app, "web.attribution")
        scrollTo(attribution, in: app)
        XCTAssertTrue(attribution.exists)
        XCTAssertTrue(attribution.label.contains("Frankfurter") && attribution.label.contains("European Central Bank"), attribution.label)
        save("web-04-currency-attribution")
    }

    func testCustomQuestionFlow() {
        let app = launch(["-LoupeWebSourcesOn"])
        any(app, "web.template.weather").tap()
        let custom = any(app, "web.variant.custom")
        scrollTo(custom, in: app)
        custom.tap()
        let text = app.textViews["web.custom.text"].exists ? app.textViews["web.custom.text"] : app.textFields["web.custom.text"]
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        text.tap()
        text.typeText("Explain why it rains")
        XCTAssertTrue(any(app, "web.custom.findings").waitForExistence(timeout: 5), "the lint refuses prose")
        XCTAssertFalse(app.buttons["web.ask"].isEnabled)
        text.clearAndType("Is this day warm enough to swim")
        XCTAssertTrue(any(app, "web.custom.ok").waitForExistence(timeout: 5))
        app.segmentedControls["web.custom.type"].buttons["Yes / no"].tap()
        let ask = app.buttons["web.ask"]
        scrollTo(ask, in: app)
        XCTAssertTrue(ask.isEnabled)
        save("web-05-custom-question")
        ask.tap()
        let q = any(app, "web.answer.question")
        XCTAssertTrue(q.waitForExistence(timeout: 10))
        XCTAssertEqual(q.label, "Is this day warm enough to swim")
        XCTAssertTrue(any(app, "web.attribution").exists || { scrollTo(any(app, "web.attribution"), in: app); return any(app, "web.attribution").exists }())
        XCTAssertTrue(any(app, "web.attribution").label.contains("Weather data by Open-Meteo.com (CC BY 4.0)"))
        save("web-06-custom-answer")
    }

    func testWeatherNotConfiguredIsHonest() {
        let app = launch(["-LoupeWebSourcesOn", "-LoupeWebState", "notConfigured", "weather"])
        any(app, "web.template.weather").tap()
        let ask = app.buttons["web.ask"]
        scrollTo(ask, in: app)
        ask.tap()
        let err = any(app, "web.error.notConfigured")
        XCTAssertTrue(err.waitForExistence(timeout: 10))
        XCTAssertTrue(err.label.contains("Weather needs Loupe's commercial Open-Meteo plan — not set up yet"), err.label)
        XCTAssertFalse(any(app, "web.onlineBadge").exists)
        save("web-07-weather-not-configured")
    }

    func testTrainsNotDeployedAndFlightsLabelledDevOnly() {
        let app = launch(["-LoupeWebSourcesOn", "-LoupeWebState", "notDeployed", "trains"])
        let flights = any(app, "web.template.flights")
        scrollTo(flights, in: app)
        XCTAssertTrue(flights.exists, "Debug builds list Flights")
        XCTAssertTrue(any(app, "web.flights.devOnly").exists)
        any(app, "web.template.trains").tap()
        let ask = app.buttons["web.ask"]
        scrollTo(ask, in: app)
        ask.tap()
        let err = any(app, "web.error.notDeployed")
        XCTAssertTrue(err.waitForExistence(timeout: 10))
        XCTAssertTrue(err.label.contains("not available yet"), err.label)
    }

    func testTrainsFixtureAnswerAndArabic() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "web", "-LoupeSkipOnboarding", "-LoupeLanguage", "ar", "-LoupeWebSourcesOn"]
        app.launch()
        any(app, "web.template.trains").tap()
        let ask = app.buttons["web.ask"]
        scrollTo(ask, in: app)
        ask.tap()
        let attribution = any(app, "web.attribution")
        XCTAssertTrue(any(app, "web.onlineBadge").waitForExistence(timeout: 10))
        save("web-08-trains-arabic")
        scrollTo(attribution, in: app)
        XCTAssertTrue(attribution.label.contains("Powered by National Rail Enquiries"))
    }
}

extension XCUIElement {
    func clearAndType(_ text: String) {
        tap()
        if let v = value as? String, !v.isEmpty {
            typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: v.count))
        }
        typeText(text)
    }
}
