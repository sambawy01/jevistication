import XCTest

/// Scenario 3: a judgment of your own, end to end. Write it (the lint refuses absence phrasing; a pick over 10 options
/// gets the cap message), run it over the sample, open the results dashboard, filter from a donut segment, correct
/// items inline (swipe), in the detail and in bulk, undo; relaunch → the judgment and its corrections are there. Then
/// edit the wording (the calibration-restart warning) and change the threshold; relaunch → both kept. Delete it (asks
/// first); relaunch → gone.
///
/// `-LoupeStandInModel` scores with the sort demo's deterministic stand-in (no 400 MB model in the simulator);
/// `-LoupeModelState ready` unlocks what needs the model.
final class JudgmentsScenarios: ScenarioCase {
    private let title = "Energy bills"

    /// The dashboard's segments (the donut's legend buttons), by identifier.
    private var segments: XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'results.segment.'"))
    }

    /// "Unsure · needs you: 37 items, 12 percent" → 37.
    private func count(_ e: XCUIElement) -> Int {
        let label = e.label
        guard let colon = label.lastIndex(of: ":") else { return -1 }
        return Int(label[label.index(after: colon)...].prefix { $0 != "," }.filter(\.isNumber)) ?? -1
    }

    /// Every segment's count, by identifier (the answer split the corrections move).
    private func split() -> [String: Int] {
        let top = segments.firstMatch
        reveal(top)
        var out: [String: Int] = [:]
        for i in 0..<segments.count {
            let s = segments.element(boundBy: i)
            out[s.identifier] = count(s)
        }
        return out
    }

    /// The line that says the threshold ("… · yes/no · acts at 90%"), on the question card and the dashboard.
    private var actsAt: XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'acts at '")).firstMatch
    }

    private func openMine() {
        root("Ask")
        let seg = app.segmentedControls["judgments.section"].buttons["My judgments"]
        if seg.waitForExistence(timeout: 5) { seg.tap() }
    }

    private func openResults() {
        openMine()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15), "\(title) is in My judgments")
        row.tap()
        XCTAssertTrue(button("results.menu").waitForExistence(timeout: 15), "its results open")
    }

    func testWriteRunCorrectEditThresholdDeleteAcrossRelaunches() {
        start("judgments", ["-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeModelState", "ready", "-LoupeStandInModel"])
        openMine()
        XCTAssertTrue(button("judgments.write").waitForExistence(timeout: 30))
        audit("03-mine-empty", ["judgments.write", "judgments.packs", "judgments.openLibrary"])
        XCTAssertEqual(button("judgments.write").label, "Write your own", "the action says what it does")

        // 1. Write your own. The lint refuses absence phrasing …
        button("judgments.write").tap()
        let question = any("write.question")
        XCTAssertTrue(question.waitForExistence(timeout: 5))
        question.tap()
        question.typeText("Is the signature missing?")
        // The findings are at the foot of the form, under the keyboard (a polish item): put the keyboard away.
        hideKeyboard()
        let absence = any("write.finding.absence-phrasing")
        reveal(absence)
        XCTAssertTrue(absence.waitForExistence(timeout: 5), "absence phrasing is refused")
        XCTAssertFalse(button("write.create").isEnabled)
        shot("03-write-absence")

        // … and a pick keeps to 10 options.
        let shape = app.segmentedControls["write.shape"]
        reveal(shape)
        shape.buttons["Pick one"].tap()
        replaceText(question, "Which company sent this bill?")
        let field = any("write.options")
        reveal(field)
        field.tap()
        field.typeText((1...11).map { "Company \($0)" }.joined(separator: "\n"))
        hideKeyboard()
        let cap = app.staticTexts.matching(identifier: "write.finding.too-many-options").firstMatch
        reveal(cap)
        XCTAssertTrue(cap.waitForExistence(timeout: 5), "an 11-option pick is refused")
        XCTAssertTrue(cap.label.contains("10 options"), cap.label)
        XCTAssertFalse(button("write.create").isEnabled)
        shot("03-write-cap")

        // A good yes/no: options that say what they mean.
        reveal(shape)
        shape.buttons["Yes / no"].tap()
        reveal(question)
        replaceText(question, "Is this a bill from my energy company?")
        let positive = app.textFields["write.positive"]
        reveal(positive)
        positive.tap(); positive.typeText("a bill from my energy company")
        let negative = app.textFields["write.negative"]
        negative.tap(); negative.typeText("not an energy bill")
        let name = app.textFields["write.title"]
        reveal(name)
        name.tap(); name.typeText(title)
        hideKeyboard()
        let compiles = any("write.compiles")
        reveal(compiles)
        XCTAssertTrue(compiles.waitForExistence(timeout: 5), "it compiles")
        XCTAssertTrue(button("write.create").isEnabled)
        audit("03-write-ok", ["write.create"])
        button("write.create").tap()

        // 2. Run it: the results open on the new judgment.
        let run = button("results.run")
        XCTAssertTrue(run.waitForExistence(timeout: 15), "the new judgment's results offer a run")
        audit("03-results-before-run", ["results.run", "results.menu"])
        run.tap()
        // The live run shows in place above the dashboard; the answer split fills in below it.
        waitUntil(timeout: 120, "the run finishes") { app.staticTexts["Done"].exists || app.staticTexts.matching(NSPredicate(format: "label ENDSWITH '· Done'")).firstMatch.exists }
        reveal(segments.firstMatch)
        XCTAssertTrue(segments.firstMatch.waitForExistence(timeout: 30), "the run fills the dashboard")
        audit("03-dashboard", ["results.menu", "results.measure"])
        let before = split()
        XCTAssertGreaterThan(before.values.reduce(0, +), 0, "items were judged: \(before)")

        // 3. Filter from a segment; its chip removes the filter.
        let unsure = button("results.segment.unsure")
        let target = unsure.exists && count(unsure) > 3 ? unsure : segments.element(boundBy: 0)
        let targetId = target.identifier
        reveal(target)
        target.tap()
        let chip = button("results.chip.bucket")
        XCTAssertTrue(chip.waitForExistence(timeout: 5), "the segment filters the list")
        XCTAssertTrue(app.staticTexts["results.shown"].label.hasPrefix("Showing \(before[targetId] ?? -1) of"), app.staticTexts["results.shown"].label)
        audit("03-filtered", ["results.chip.bucket", "results.select", "results.sort"])

        // 4a. Correct one inline: swipe, Mark.
        let row = app.buttons.matching(identifier: "results.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.swipeLeft()
        let mark = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Mark: ' OR label BEGINSWITH 'Confirm: '")).firstMatch
        XCTAssertTrue(mark.waitForExistence(timeout: 5), "the swipe offers a correction")
        mark.tap()
        XCTAssertTrue(button("results.undo").waitForExistence(timeout: 5), "Undo is offered")

        // 4b. Correct one in its detail.
        let next = app.buttons.matching(identifier: "results.row").firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()
        let option = button("detail.option.0")
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        audit("03-detail", ["detail.option.0", "detail.option.1"])
        option.tap()
        XCTAssertTrue(button("detail.clear").waitForExistence(timeout: 5), "your answer can be removed")
        back()

        // 4c. Bulk: Select, two rows, Confirm.
        let select = button("results.select")
        reveal(select)
        select.tap()
        let rows = app.buttons.matching(identifier: "results.row")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 5))
        rows.element(boundBy: 0).tap()
        if rows.count > 1 { rows.element(boundBy: 1).tap() }
        let bulk = button("results.bulkConfirm").exists ? button("results.bulkConfirm") : button("results.bulkMark")
        XCTAssertTrue(bulk.waitForExistence(timeout: 5), "bulk actions show once rows are selected")
        audit("03-bulk", ["results.bulkConfirm", "results.bulkMark"])
        bulk.tap()
        XCTAssertTrue(button("results.undo").waitForExistence(timeout: 5))

        // 4d. Undo the bulk change.
        let afterBulk = split()
        let undo = button("results.undo")
        reveal(undo)
        undo.tap()
        waitUntil(timeout: 10, "Undo changed the split back") { self.split() != afterBulk }
        if chip.exists { reveal(chip); chip.tap() }
        let corrected = split()
        XCTAssertNotEqual(corrected, before, "two corrections moved the split")
        shot("03-corrected")

        // 5. Relaunch: the judgment and its corrections are there.
        relaunch()
        openResults()
        XCTAssertTrue(segments.firstMatch.waitForExistence(timeout: 60))
        XCTAssertEqual(split(), corrected, "the corrections survived a relaunch")
        shot("03-relaunch-corrections")

        // 6. The threshold, in Measure (while the wording has decisions: Measure shows the slider only then).
        button("results.menu").tap()
        let measureItem = app.buttons["Measure and threshold…"]
        XCTAssertTrue(measureItem.waitForExistence(timeout: 5))
        measureItem.tap()
        let slider = app.sliders["measure.threshold"]
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 10))
        sleep(1)
        reveal(slider)
        XCTAssertTrue(slider.waitForExistence(timeout: 10), "Measure has the threshold slider")
        slider.adjust(toNormalizedSliderPosition: 0.86)
        let apply = button("measure.apply")
        reveal(apply)
        XCTAssertTrue(apply.isEnabled, "a new threshold can be used")
        let chosen = apply.label.replacingOccurrences(of: "Use ", with: "")      // "0.90"
        let pct = "\(Int((Double(chosen) ?? 0) * 100 + 0.5))%"
        audit("03-measure", ["measure.apply"])
        apply.tap()
        back()
        let header = actsAt
        reveal(header)
        waitFor(header, "label CONTAINS %@", "acts at \(pct)", timeout: 10)

        // 7. Edit the wording: the restart warning shows before Save. The threshold stays.
        button("results.menu").tap()
        let edit = app.buttons["Edit wording…"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let q = any("edit.question")
        XCTAssertTrue(q.waitForExistence(timeout: 5))
        replaceText(q, "Is this a bill from my gas or electricity company?")
        let restarts = any("edit.restarts")
        reveal(restarts)
        XCTAssertTrue(restarts.waitForExistence(timeout: 5), "new wording says calibration starts again")
        audit("03-edit", ["edit.save", "edit.cancel"])
        button("edit.save").tap()
        let confirm = app.buttons["Save the new wording"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "Save asks first when calibration restarts")
        confirm.tap()
        XCTAssertFalse(any("edit.question").waitForExistence(timeout: 2))

        let kept = actsAt
        reveal(kept)
        XCTAssertTrue(kept.label.contains("acts at \(pct)"), "rewording keeps the threshold: \(kept.label)")

        // 8. Relaunch: the new wording and the threshold are kept.
        relaunch()
        openResults()
        let header2 = actsAt
        reveal(header2)
        XCTAssertTrue(header2.waitForExistence(timeout: 30))
        XCTAssertTrue(header2.label.contains("acts at \(pct)"), "the threshold survived: \(header2.label)")
        button("results.menu").tap()
        app.buttons["Edit wording…"].tap()
        XCTAssertTrue(any("edit.question").waitForExistence(timeout: 5))
        XCTAssertEqual(any("edit.question").value as? String, "Is this a bill from my gas or electricity company?", "the wording survived")
        button("edit.cancel").tap()

        // 9. Delete, asking first; relaunch: gone.
        button("results.menu").tap()
        let delete = app.buttons["Delete judgment…"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        let sure = app.buttons["Delete judgment"].firstMatch
        XCTAssertTrue(sure.waitForExistence(timeout: 5), "Delete asks first")
        sure.tap()
        XCTAssertTrue(button("judgments.openLibrary").waitForExistence(timeout: 10), "back on an empty My judgments")
        relaunch()
        openMine()
        XCTAssertTrue(button("judgments.openLibrary").waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch.exists, "deleted stays deleted")
    }

    /// The fixture dashboard (300 synthetic items, the stand-in's spread): the donut's segments sum to the items, and
    /// the borderline and a histogram bin filter too; a relaunch keeps a correction made in the detail.
    func testFixtureDashboardFiltersAndACorrectionSurvivesARelaunch() {
        start("results", ["-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeModelState", "ready",
                          "-LoupeJudgmentDemo", "is-receipt", "-LoupeResultsFixture", "300"], run: false)
        let unsure = button("results.segment.unsure")
        XCTAssertTrue(unsure.waitForExistence(timeout: 90))
        let parts = split()
        XCTAssertEqual(parts.values.reduce(0, +), 300, "the segments add up to the items: \(parts)")
        audit("03b-dashboard", ["results.segment.unsure", "results.menu"])
        let border = button("results.borderline")
        reveal(border)
        border.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'results.chip.'")).firstMatch.waitForExistence(timeout: 5),
                      "borderline filters")
        audit("03b-borderline")
        let clear = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'results.chip.'")).firstMatch
        clear.tap()

        for _ in 0..<12 where !onScreen(unsure) { app.swipeDown(velocity: .fast) }
        let before = count(unsure)
        unsure.tap()
        XCTAssertTrue(button("results.chip.bucket").waitForExistence(timeout: 5), "the segment filters")
        let row = app.buttons.matching(identifier: "results.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        XCTAssertTrue(button("detail.option.0").waitForExistence(timeout: 5))
        button("detail.option.0").tap()
        XCTAssertTrue(button("detail.clear").waitForExistence(timeout: 5))
        back()
        let u = button("results.segment.unsure")
        reveal(u)
        waitFor(u, "label BEGINSWITH %@", "Unsure · needs you: \(before - 1) items")

        relaunch()
        let u2 = button("results.segment.unsure")
        XCTAssertTrue(u2.waitForExistence(timeout: 90))
        reveal(u2)
        XCTAssertTrue(u2.label.hasPrefix("Unsure · needs you: \(before - 1) items"), "the correction survived: \(u2.label)")
    }
}
