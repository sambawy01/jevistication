import XCTest

/// The live run view (docs/LIVE-RUN-VIEW.md): a privacy check shows its run in place with counters moving, and
/// under Reduce Motion the view is static while the counters still update. Also saves the dark-theme design
/// screenshots (Now, Judgments results, a live run, Model settings, the game).
final class LiveRunUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    static let shots = "/Users/bistrocloud/.gstack/projects/sambawy01-jevistication/designs/ios-v2-dark"

    private func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
        try? FileManager.default.createDirectory(atPath: Self.shots, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: Self.shots + "/\(name).png"))
    }

    private func counter(_ app: XCUIApplication, _ id: String) -> Int {
        Int((app.descendants(matching: .any)["liverun.counter.\(id)"].value as? String) ?? "") ?? -1
    }

    private func startPrivacyCheck(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeSlowJobs"] + extra
        app.launch()
        let card = app.buttons["now.privacy"]
        // The Play card sits high on Now (2026-09-25): the privacy card may start below the fold.
        for _ in 0..<4 where !card.waitForExistence(timeout: 15) { app.swipeUp() }
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "findings"), evaluatedWith: card)
        waitForExpectations(timeout: 120)
        for _ in 0..<4 where !card.isHittable { app.swipeUp() }
        card.tap()
        let rerun = app.buttons["privacy.rerun"]
        XCTAssertTrue(rerun.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: rerun)
        waitForExpectations(timeout: 60)
        rerun.tap()
        // The new run is live: its stage line says it is reading (the last run's result line said "Files checked").
        let stage = app.descendants(matching: .any)["liverun.stage"]
        expectation(for: NSPredicate(format: "label == %@", "Reading and deciding"), evaluatedWith: stage)
        waitForExpectations(timeout: 30)
        return app
    }

    func testAPrivacyCheckShowsItsLiveRunWithCountersMoving() {
        let app = startPrivacyCheck([])
        let view = app.descendants(matching: .any)["liverun"]
        XCTAssertTrue(view.waitForExistence(timeout: 30), "the live run is in place on the Privacy screen")
        XCTAssertEqual(view.value as? String, "animated")
        XCTAssertTrue(app.descendants(matching: .any)["liverun.poweredBy"].exists)
        XCTAssertTrue(app.staticTexts["powered by the decision model · multilingual"].exists)
        // Counters move while the items pass (150 ms an item under -LoupeSlowJobs).
        let read = app.descendants(matching: .any)["liverun.counter.read"]
        XCTAssertTrue(read.waitForExistence(timeout: 10))
        let first = counter(app, "read")
        save("03-live-run")
        expectation(for: NSPredicate(format: "value != %@", "\(first)"), evaluatedWith: read)
        waitForExpectations(timeout: 30)
        XCTAssertGreaterThan(counter(app, "read"), first, "the read counter moved")
        XCTAssertTrue(app.descendants(matching: .any)["liverun.node.walk"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["liverun.q3"].exists)
        // It finishes with the Folder Scan result line, the cards and the cost estimate.
        let stage = app.descendants(matching: .any)["liverun.stage"]
        expectation(for: NSPredicate(format: "label BEGINSWITH %@", "Files checked"), evaluatedWith: stage)
        waitForExpectations(timeout: 60)
        let label = app.descendants(matching: .any)["liverun.cost.label"]
        for _ in 0..<6 where !label.isHittable { app.swipeUp() }
        XCTAssertEqual(label.label, "estimate · Claude Sonnet 5 list price as of 24 Sep 2026")
        XCTAssertGreaterThan(counter(app, "decisions"), 0)
    }

    func testReduceMotionIsStaticButCountersStillUpdate() {
        let app = startPrivacyCheck(["-LoupeReduceMotion"])
        let view = app.descendants(matching: .any)["liverun"]
        XCTAssertTrue(view.waitForExistence(timeout: 30))
        XCTAssertEqual(view.value as? String, "static", "no particles, a still mascot")
        let read = app.descendants(matching: .any)["liverun.counter.read"]
        XCTAssertTrue(read.waitForExistence(timeout: 10))
        let first = counter(app, "read")
        expectation(for: NSPredicate(format: "value != %@", "\(first)"), evaluatedWith: read)
        waitForExpectations(timeout: 30)
    }

    /// Design screenshots of the dark neon theme.
    func testDarkThemeScreenshots() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeFixtures"]
        app.launch()
        XCTAssertTrue(app.buttons["now.privacy"].waitForExistence(timeout: 60))
        sleep(3)
        save("01-now")

        app.terminate()
        app.launchArguments = ["-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeFixtures",
                               "-LoupeJudgmentDemo", "tax-receipt"]
        app.launch()
        sleep(8)
        save("02-judgment-results")

        app.terminate()
        app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeFixtures"]
        app.launch()
        let open = app.buttons["me.modelSettings"]
        XCTAssertTrue(open.waitForExistence(timeout: 30))
        open.tap()
        XCTAssertTrue(app.navigationBars["Model settings"].waitForExistence(timeout: 10))
        sleep(1)
        save("04-model-settings")

        app.terminate()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeGame", "watch", "-LoupeLanguage", "en"]
        app.launch()
        sleep(8)
        save("05-game")
    }
}
