import XCTest

final class AssistUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Me → Writing assistant reads Off on a fresh install, and its switch is off.
    func testSettingsShowAssistantOffByDefault() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeEphemeralKeychain"]
        app.launch()
        let row = app.buttons["me.assistant"]
        for _ in 0..<5 where !row.waitForExistence(timeout: 5) { app.swipeUp() }
        XCTAssertTrue(row.exists)
        XCTAssertTrue(row.label.contains("Off"), row.label)
        row.tap()
        let toggle = app.switches["assist.enabled"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        XCTAssertEqual(toggle.value as? String, "0")
        XCTAssertEqual(app.staticTexts["assist.status"].label, "Off")
    }

    /// With the DEBUG fake provider: Mail triage → Draft a reply shows the Online preview; after
    /// Send, the draft appears labelled Draft.
    func testFakeProviderDraftAppearsLabelledDraft() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeEphemeralKeychain", "-LoupeFakeAssistant"]
        app.launch()
        let card = app.buttons["now.mail"]
        for _ in 0..<4 where !card.waitForExistence(timeout: 15) { app.swipeUp() }
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Mail triage:"), evaluatedWith: card)
        waitForExpectations(timeout: 60)
        card.tap()
        let draft = app.buttons["mail.draftReply"].firstMatch
        for _ in 0..<6 where !draft.waitForExistence(timeout: 5) { app.swipeUp() }
        XCTAssertTrue(draft.exists)
        draft.tap()
        XCTAssertTrue(app.descendants(matching: .any)["assist.online"].firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["assist.preview"].exists)
        app.buttons["assist.send"].tap()
        let label = app.descendants(matching: .any)["assist.draftLabel"].firstMatch
        XCTAssertTrue(label.waitForExistence(timeout: 20))
        XCTAssertTrue(label.label.lowercased().contains("draft"), label.label)
        XCTAssertEqual(app.staticTexts["assist.draftSubject"].label.hasPrefix("Re:"), true)
        app.buttons["assist.approve"].tap()
        XCTAssertTrue(app.buttons["assist.copy"].waitForExistence(timeout: 10))
    }
}
