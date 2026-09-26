import XCTest

/// The judgment results dashboard on fixture data (`-LoupeResultsFixture`: synthetic items judged by a stand-in
/// scorer through the real sweep, throwaway ledger): a donut segment filters the list, its chip removes the
/// filter, and correcting an item moves the header's counts. With `LOUPE_SHOTS` set, also screenshots the
/// dashboard, a filtered state, the list and an item at 300 and 10,000 items, and reports the perf line.
final class ResultsDashboardUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func launch(_ n: Int, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeSkipOnboarding", "-LoupeModelState", "ready", "-LoupeTab", "judgments",
                               "-LoupeJudgmentDemo", "is-receipt", "-LoupeResultsFixture", "\(n)"] + extra
        app.launch()
        return app
    }

    /// "Unsure · needs you: 37 items, 12 percent" → 37.
    private func count(_ e: XCUIElement) -> Int {
        let label = e.label
        guard let colon = label.lastIndex(of: ":") else { return -1 }
        let digits = label[label.index(after: colon)...].prefix { $0 != "," }.filter(\.isNumber)
        return Int(digits) ?? -1
    }

    /// The list is lazy: the dashboard leaves the hierarchy once scrolled past, so scroll back to find it.
    @discardableResult
    private func scrollUpTo(_ e: XCUIElement, in app: XCUIApplication) -> Bool {
        for _ in 0..<12 where !(e.exists && e.isHittable) { app.swipeDown(velocity: .fast) }
        return e.exists
    }

    func testSegmentFiltersChipClearsAndCorrectionMovesTheHeader() {
        let app = launch(300)
        let unsure = app.buttons["results.segment.unsure"]
        XCTAssertTrue(unsure.waitForExistence(timeout: 60), "the dashboard shows the answer split")
        let before = count(unsure)
        XCTAssertGreaterThan(before, 0)

        // Tap the segment: the list shows only unsure items, with a removable chip.
        unsure.tap()
        let chip = app.buttons["results.chip.bucket"]
        XCTAssertTrue(chip.waitForExistence(timeout: 5))
        let shown = app.staticTexts["results.shown"]
        XCTAssertTrue(shown.label.hasPrefix("Showing \(before) of 300"), shown.label)
        let answers = app.buttons.matching(identifier: "results.answer")
        XCTAssertTrue(answers.firstMatch.waitForExistence(timeout: 5))
        for i in 0..<min(answers.count, 5) {
            XCTAssertEqual(answers.element(boundBy: i).label, "Answer: unsure. Change")
        }

        // The chip removes the filter.
        chip.tap()
        XCTAssertFalse(chip.waitForExistence(timeout: 2))
        XCTAssertTrue(shown.label.hasPrefix("300 items"), shown.label)

        // Correct the least sure unsure item from its detail: the header's unsure count drops by one.
        XCTAssertTrue(scrollUpTo(unsure, in: app))
        unsure.tap()
        let row = app.buttons.matching(identifier: "results.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        let option = app.buttons["detail.option.0"]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        XCTAssertTrue(app.buttons["detail.clear"].waitForExistence(timeout: 5), "your answer can be removed")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["results.undo"].waitForExistence(timeout: 5), "the change can be undone")
        scrollUpTo(app.buttons["results.segment.unsure"], in: app)
        let dropped = NSPredicate(format: "label BEGINSWITH %@", "Unsure · needs you: \(before - 1) items")
        expectation(for: dropped, evaluatedWith: app.buttons["results.segment.unsure"])
        waitForExpectations(timeout: 5)

        // Undo puts it back.
        app.buttons["results.undo"].tap()
        let back = NSPredicate(format: "label BEGINSWITH %@", "Unsure · needs you: \(before) items")
        expectation(for: back, evaluatedWith: app.buttons["results.segment.unsure"])
        waitForExpectations(timeout: 5)
    }

    func testSwipeCorrectsInline() {
        let app = launch(300)
        let unsure = app.buttons["results.segment.unsure"]
        XCTAssertTrue(unsure.waitForExistence(timeout: 60))
        let before = count(unsure)
        unsure.tap()
        let row = app.buttons.matching(identifier: "results.row").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.swipeLeft()
        let mark = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Mark: '")).firstMatch
        XCTAssertTrue(mark.waitForExistence(timeout: 5))
        mark.tap()
        XCTAssertTrue(app.buttons["results.undo"].waitForExistence(timeout: 5))
        scrollUpTo(app.buttons["results.segment.unsure"], in: app)
        let dropped = NSPredicate(format: "label BEGINSWITH %@", "Unsure · needs you: \(before - 1) items")
        expectation(for: dropped, evaluatedWith: app.buttons["results.segment.unsure"])
        waitForExpectations(timeout: 5)
    }

    // MARK: Screenshots and perf (LOUPE_SHOTS)

    private var dir: String? { ProcessInfo.processInfo.environment["LOUPE_SHOTS"] }

    private func save(_ name: String) {
        guard let dir else { return }
        let device = UIDevice.current.name.contains("SE") ? "SE" : UIDevice.current.name.contains("Max") ? "17ProMax" : "17Pro"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "\(dir)/results-\(name)-\(device).png", contents: XCUIScreen.main.screenshot().pngRepresentation)
    }

    func testShotsAndPerf() throws {
        try XCTSkipIf(dir == nil, "set TEST_RUNNER_LOUPE_SHOTS to take the screenshots")
        for n in [300, 10_000] {
            let app = launch(n, extra: n > 1000 ? ["-LoupeResultsPerf"] : [])
            let unsure = app.buttons["results.segment.unsure"]
            XCTAssertTrue(unsure.waitForExistence(timeout: 240))
            let tag = n > 1000 ? "10k" : "300"
            sleep(1)
            save("\(tag)-1-top")
            if n > 1000 {
                let perf = app.staticTexts["results.perf"]
                if perf.waitForExistence(timeout: 5) {
                    XCTContext.runActivity(named: "LOUPE-PERF ui " + perf.label) { _ in }
                    print("LOUPE-PERF ui " + perf.label)
                }
            }
            app.swipeUp()
            save("\(tag)-2-charts")
            app.swipeUp()
            save("\(tag)-3-breakdowns")
            // A filtered state: the borderline ones.
            let border = app.buttons["results.borderline"]
            scrollUpTo(border, in: app)       // back up (it may be above) …
            for _ in 0..<12 where !(border.exists && border.isHittable) { app.swipeUp(velocity: .slow) }   // … or down
            border.tap()
            sleep(1)
            save("\(tag)-4-filtered")
            if n > 1000 {
                let search = app.textFields["results.search"]
                if search.exists {
                    search.tap()
                    search.typeText("tesco rec")
                    sleep(1)
                    let perf = app.staticTexts["results.perf"]
                    if perf.exists { print("LOUPE-PERF ui search " + perf.label); XCTContext.runActivity(named: "LOUPE-PERF ui search " + perf.label) { _ in } }
                    save("\(tag)-5-search")
                    app.buttons["Clear search"].tap()
                }
            }
            if app.buttons["results.chip.bins"].exists { app.buttons["results.chip.bins"].tap() }
            let group = app.descendants(matching: .any).matching(identifier: "results.group.unsure").firstMatch
            _ = group.waitForExistence(timeout: 3)
            save("\(tag)-6-list")
            let row = app.buttons.matching(identifier: "results.row").firstMatch
            if row.waitForExistence(timeout: 5) {
                row.tap()
                sleep(1)
                save("\(tag)-7-item")
                app.swipeUp()
                save("\(tag)-8-item-correct")
            }
            app.terminate()
        }
    }
}
