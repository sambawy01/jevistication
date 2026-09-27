import XCTest

/// Scenario 5: packs. Judgments → Packs → Try the example pack → the preview (labelled an example, what will be added,
/// what the lint refused) → Add; relaunch → its judgments are in My judgments.
final class PacksScenarios: ScenarioCase {
    func testTheExamplePackIsAddedAndSurvivesARelaunch() {
        start("packs", ["-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeModelState", "missing"])
        let packs = button("judgments.packs")
        XCTAssertTrue(packs.waitForExistence(timeout: 30))
        packs.tap()
        let example = button("packs.example")
        XCTAssertTrue(example.waitForExistence(timeout: 5))
        audit("05-packs-menu", ["packs.import", "packs.example", "packs.export"])
        example.tap()

        XCTAssertTrue(app.staticTexts["Bistro Cloud"].waitForExistence(timeout: 10), "the preview opens")
        XCTAssertTrue(any("packs.exampleLabel").exists, "labelled an example")
        let refused = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Not added: the lint refused them'")).firstMatch
        reveal(refused)
        XCTAssertTrue(refused.exists, "what the lint refused is listed")
        let add = button("packs.add")
        reveal(add)
        XCTAssertEqual(add.label, "Add 14 judgments")
        audit("05-packs-preview", ["packs.add", "packs.review"])
        add.tap()
        XCTAssertTrue(button("judgments.mine.j-complaint-triage-team").waitForExistence(timeout: 10))

        relaunch()
        tab("Judgments")
        let mine = app.segmentedControls["judgments.section"].buttons["My judgments"]
        if mine.waitForExistence(timeout: 5) { mine.tap() }
        let team = button("judgments.mine.j-complaint-triage-team")
        XCTAssertTrue(team.waitForExistence(timeout: 20), "the pack's judgments survive a relaunch")
        XCTAssertTrue(button("judgments.mine.j-complaint-triage-frustration").exists)
        XCTAssertFalse(button("judgments.mine.j-gmail-triage-is-phishing").exists, "the refused question was not added")
        // The list is lazy: count what is built, at least the first screen's worth, all from the pack.
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'judgments.mine.j-'"))
        XCTAssertGreaterThanOrEqual(rows.count, 5, "the pack's judgments are listed")
        audit("05-mine-after-relaunch", ["judgments.write", "judgments.packs"])
    }
}
