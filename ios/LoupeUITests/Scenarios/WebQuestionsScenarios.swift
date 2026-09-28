import XCTest

/// Scenario 6: Web questions (in Judgments). Open a template, turn its source on, pick a ready-made question and ask
/// it; write a custom question on another template; open the settings; relaunch → the source switch, the chosen
/// question and the saved custom question are all still there. Fixture answers, no network.
final class WebQuestionsScenarios: ScenarioCase {
    private let custom = "Is this day warm enough to swim"

    private func openTemplate(_ id: String) {
        let t = any("web.template.\(id)")
        XCTAssertTrue(t.waitForExistence(timeout: 20), "no \(id) template")
        reveal(t)
        t.tap()
    }

    private func customField() -> XCUIElement {
        app.textViews["web.custom.text"].exists ? app.textViews["web.custom.text"] : app.textFields["web.custom.text"]
    }

    func testTemplatesCustomQuestionAndSettingsSurviveARelaunch() {
        start("web", ["-LoupeTab", "web", "-LoupeSkipOnboarding", "-LoupeModelState", "missing"], run: false)
        XCTAssertTrue(any("web.template.currency").waitForExistence(timeout: 20))
        audit("06-web-library", ["web.template.currency", "web.template.weather", "web.template.trains", "web.settings"])

        // 1. A template: the source is off until turned on; then a ready-made question answers.
        openTemplate("currency")
        let turnOn = button("web.source.turnOn")
        XCTAssertTrue(turnOn.waitForExistence(timeout: 5), "off by default: it says what it would send first")
        audit("06-currency-off", ["web.source.turnOn"])
        turnOn.tap()
        let variant = any("web.variant.currency.rankVsHome")
        reveal(variant)
        variant.tap()
        let ask = button("web.ask")
        reveal(ask)
        audit("06-currency-on", ["web.ask"])
        ask.tap()
        XCTAssertTrue(any("web.onlineBadge").waitForExistence(timeout: 15), "the answer shows, labelled Online")
        XCTAssertTrue(any("web.answer.rules").waitForExistence(timeout: 15))
        back()

        // 2. A custom question on Weather.
        openTemplate("weather")
        let on = button("web.source.turnOn")
        if on.waitForExistence(timeout: 3) { on.tap() }
        let c = any("web.variant.custom")
        reveal(c)
        c.tap()
        let text = customField()
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        text.tap()
        text.typeText("Explain why it rains")
        XCTAssertTrue(any("web.custom.findings").waitForExistence(timeout: 5), "the lint refuses prose")
        replaceText(text, custom)
        hideKeyboard()
        let ok = any("web.custom.ok")
        reveal(ok)
        XCTAssertTrue(ok.waitForExistence(timeout: 5), "a yes/no-able question passes")
        app.segmentedControls["web.custom.type"].buttons["Yes / no"].tap()
        let askCustom = button("web.ask")
        reveal(askCustom)
        XCTAssertTrue(askCustom.isEnabled)
        askCustom.tap()
        let q = any("web.answer.question")
        XCTAssertTrue(q.waitForExistence(timeout: 15))
        XCTAssertEqual(q.label, custom)
        shot("06-custom-answer")
        back()

        // 3. The settings: every source's switch, the helper.
        let gear = button("web.settings")
        reveal(gear)
        gear.tap()
        XCTAssertTrue(app.navigationBars["Web settings"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.switches["settings.source.currency"].value as? String, "1", "Currency is on (turned on above)")
        XCTAssertEqual(app.switches["settings.source.trains"].value as? String, "0", "Trains was never turned on")
        audit("06-web-settings")
        app.buttons["Done"].tap()

        // 4. Relaunch: the switches, the chosen question and the custom question are kept.
        relaunch()
        XCTAssertTrue(any("web.template.currency").waitForExistence(timeout: 20))
        openTemplate("currency")
        XCTAssertFalse(button("web.source.turnOn").waitForExistence(timeout: 3), "Currency stays on")
        let ask2 = button("web.ask")
        reveal(ask2)
        XCTAssertTrue(ask2.exists, "the source is on: Ask is offered")
        back()
        openTemplate("weather")
        let text2 = customField()
        reveal(text2)
        XCTAssertTrue(text2.waitForExistence(timeout: 5), "the custom question is still the chosen one")
        // (The test's keyboard Return may add a line break; the words are what must survive.)
        XCTAssertEqual((text2.value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), custom, "the custom question survived a relaunch")
        let type = app.segmentedControls["web.custom.type"].buttons["Yes / no"]
        reveal(type)
        XCTAssertTrue(type.isSelected, "and its answer type")
        audit("06-custom-after-relaunch", ["web.ask"])
    }
}
