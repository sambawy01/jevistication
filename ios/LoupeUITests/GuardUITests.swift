import XCTest

/// The Guard tab (owner decision 2026-09-26) over the bundled sample (-LoupeFixtures): the five tabs, the status
/// header, Subscriptions with a merchant's evidence, the Expiring soon timeline with its locked model half, the other
/// watchers, Protection; and Web questions in Judgments, with the old `-LoupeTab web` redirected there.
/// Saves `guard-*.png` (to $LOUPE_SHOTS, set with TEST_RUNNER_LOUPE_SHOTS, else the design folder).
final class GuardUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func launch(_ tab: String, _ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", tab, "-LoupeSkipOnboarding", "-LoupeLanguage", "en",
                               "-LoupeModelState", "missing"] + extra
        app.launch()
        return app
    }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    private func scrollTo(_ el: XCUIElement, in app: XCUIApplication, max: Int = 12) {
        for _ in 0..<max where !(el.exists && el.isHittable) { app.swipeUp() }
    }

    private func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let size = shot.image.size
        let device = min(size.width, size.height) <= 380 ? "se" : "pro"
        let a = XCTAttachment(screenshot: shot); a.name = "\(name)-\(device)"; a.lifetime = .keepAlways; add(a)
        let dir = ProcessInfo.processInfo.environment["LOUPE_SHOTS"] ?? LiveRunUITests.shots
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name)-\(device).png"))
    }

    func testFiveTabsWithGuardAndNoWebTab() {
        let app = launch("now")
        let tabs = app.tabBars.firstMatch
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
        XCTAssertEqual(tabs.buttons.allElementsBoundByIndex.map(\.label), ["Now", "Guard", "Judgments", "Sources", "Me"])
        XCTAssertFalse(tabs.buttons["Web"].exists)
        // Now keeps the newest findings and leads to Guard.
        let door = app.buttons["now.guard"]
        scrollTo(door, in: app)
        XCTAssertTrue(door.waitForExistence(timeout: 60))
        door.tap()
        XCTAssertTrue(tabs.buttons["Guard"].isSelected)
        XCTAssertTrue(any(app, "guard.header").waitForExistence(timeout: 5))
    }

    func testGuardShowsTheSamplesWatchersWithEvidence() {
        let app = launch("guard")
        XCTAssertTrue(any(app, "guard.header").waitForExistence(timeout: 10))
        let total = any(app, "guard.subscriptions.total")
        XCTAssertTrue(total.waitForExistence(timeout: 60), "the watchers ran over the sample")
        XCTAssertTrue(any(app, "guard.runNow").exists)
        XCTAssertTrue(any(app, "guard.header.bytesOut").exists, "online checks are off: 0 bytes out")
        save("guard-01-home")

        // Subscriptions: the most expensive first; Streamflix opens its receipts and the answers.
        let streamflix = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Streamflix")).firstMatch
        scrollTo(streamflix, in: app)
        XCTAssertTrue(streamflix.exists)
        XCTAssertTrue(streamflix.label.contains("9.99 a month"), streamflix.label)
        streamflix.tap()
        XCTAssertTrue(any(app, "guard.subscription.detail.amount").waitForExistence(timeout: 5))
        XCTAssertTrue(any(app, "guard.subscription.evidence.0").exists, "the receipts it came from")
        let notSub = app.buttons["guard.subscription.notSubscription"]
        scrollTo(notSub, in: app, max: 4)
        XCTAssertTrue(notSub.exists)
        XCTAssertTrue(app.buttons["guard.subscription.setAside"].exists)
        app.swipeDown(); app.swipeDown()
        save("guard-02-subscription")
        scrollTo(notSub, in: app, max: 4)
        notSub.tap()
        // Back on Guard: the notice offers Undo, and Undo puts Streamflix back.
        let undo = app.buttons["guard.undo"]
        for _ in 0..<12 where !(undo.exists && undo.isHittable) { app.swipeDown() }
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        undo.tap()

        // Expiring soon: the passport, dated, the model half locked while the dates show.
        let passport = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "passport")).firstMatch
        scrollTo(passport, in: app)
        XCTAssertTrue(passport.exists)
        XCTAssertTrue(passport.label.contains("113 days left") || passport.label.contains("days left"), passport.label)
        XCTAssertTrue(passport.label.contains("needs the decision model"), passport.label)
        let gate = any(app, "needsLaya.guardExpiry")
        scrollTo(gate, in: app, max: 3)
        XCTAssertTrue(gate.exists, "the model half is locked behind the decision model")
        for _ in 0..<3 where !passport.isHittable { app.swipeDown() }
        passport.tap()
        XCTAssertTrue(any(app, "guard.expiry.detail").waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // The other watchers and Protection.
        let impostor = any(app, "guard.impersonation.0")
        scrollTo(impostor, in: app)
        XCTAssertTrue(impostor.exists)
        let fraud = any(app, "guard.site-fraud.0")
        scrollTo(fraud, in: app)
        XCTAssertTrue(fraud.exists)
        let protection = any(app, "guard.protection.mail")
        scrollTo(protection, in: app)
        XCTAssertTrue(protection.exists)
        XCTAssertTrue(any(app, "guard.protection.onlineChecks").exists)
        save("guard-04-protection")
    }

    /// The expiry timeline brought to the top for its screenshot.
    func testExpiringSoonTimeline() {
        let app = launch("guard", ["-LoupeGuardSection", "expiry"])
        let group = any(app, "guard.expiry.group.later")
        XCTAssertTrue(group.waitForExistence(timeout: 60))
        sleep(1)
        XCTAssertTrue(group.isHittable)
        save("guard-03-expiring")
    }

    func testWebQuestionsAreInJudgments() {
        let app = launch("judgments")
        let segment = app.segmentedControls["judgments.section"].buttons["Web questions"]
        XCTAssertTrue(segment.waitForExistence(timeout: 10))
        segment.tap()
        let currency = any(app, "web.template.currency")
        XCTAssertTrue(currency.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["web.settings"].exists)
        save("guard-05-judgments-web")
        currency.tap()
        XCTAssertTrue(app.buttons["web.source.turnOn"].waitForExistence(timeout: 5), "a template opens as it did on the Web tab")
    }

    func testTheOldWebLaunchArgumentOpensWebQuestions() {
        let app = launch("web")
        XCTAssertTrue(any(app, "web.template.currency").waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Judgments"].isSelected)
        XCTAssertTrue(app.segmentedControls["judgments.section"].buttons["Web questions"].isSelected)
    }
}
