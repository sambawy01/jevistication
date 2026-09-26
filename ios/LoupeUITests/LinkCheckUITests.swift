import XCTest

/// Guard → Protection → Check a link (2026-09-26) with fixture links, no network (online checks are
/// off by default): a look-alike of a brand's domain comes out Dangerous, an ordinary one with no
/// warning signs, a non-web link is refused, and the checks land in Recent checks and Spotted.
/// Saves `protect-*.png` to $LOUPE_SHOTS (TEST_RUNNER_LOUPE_SHOTS), else the scratch folder.
final class LinkCheckUITests: XCTestCase {
    static let shots = "/private/tmp/claude-501/-Users-bistrocloud-Documents-Loupe/2e3bc6ff-acd2-4440-810c-d52004581420/scratchpad/shots"

    override func setUp() { continueAfterFailure = false }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    private func scrollTo(_ el: XCUIElement, in app: XCUIApplication, max: Int = 14) {
        for _ in 0..<max where !(el.exists && el.isHittable) { app.swipeUp() }
    }

    private func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
        let dir = ProcessInfo.processInfo.environment["LOUPE_SHOTS"] ?? Self.shots
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
    }

    private func check(_ app: XCUIApplication, _ text: String) {
        let clear = app.buttons["protect.link.clear"]
        if clear.exists { clear.tap() }
        let field = any(app, "protect.link.input")
        field.tap()
        field.typeText(text)
        app.buttons["protect.link.check"].tap()
    }

    func testCheckALinkFromGuard() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "guard", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeModelState", "missing"]
        app.launch()
        XCTAssertTrue(any(app, "guard.header").waitForExistence(timeout: 15))

        // Guard → Protection: the Safari card (off in the simulator) and Check a link.
        let safari = any(app, "protect.safari")
        scrollTo(safari, in: app)
        XCTAssertTrue(safari.waitForExistence(timeout: 10))
        XCTAssertTrue(any(app, "protect.safari.turnOn").exists, "the one button while Safari protection is off")
        save("protect-01-guard-protection")
        let row = any(app, "protect.checkLink")
        scrollTo(row, in: app)
        row.tap()
        XCTAssertTrue(any(app, "protect.link.input").waitForExistence(timeout: 5))
        save("protect-02-check-empty")

        // A look-alike of PayPal written with a Cyrillic "а" (typed in its punycode form).
        check(app, "https://xn--pypal-4ve.com/signin")
        let verdict = any(app, "protect.verdict")
        XCTAssertTrue(verdict.waitForExistence(timeout: 10))
        XCTAssertEqual(verdict.value as? String, "dangerous")
        XCTAssertTrue(any(app, "protect.verdict.title").label.contains("Dangerous"))
        XCTAssertTrue(any(app, "protect.verdict.ascii").exists, "the international name is shown with its written form")
        XCTAssertTrue(any(app, "protect.verdict.privacy").label.hasPrefix("0 bytes out"))
        save("protect-03-check-dangerous")

        // An ordinary website: no warning signs.
        check(app, "www.bbc.co.uk/news")
        let predicate = NSPredicate(format: "value == %@", "safe")
        expectation(for: predicate, evaluatedWith: any(app, "protect.verdict"))
        waitForExpectations(timeout: 10)
        XCTAssertTrue(any(app, "protect.verdict.title").label.contains("No warning signs found"))
        save("protect-04-check-safe")

        // Not a web link.
        check(app, "mailto:someone@example.com")
        XCTAssertTrue(any(app, "protect.link.problem").waitForExistence(timeout: 5))

        // Both checks are in Recent checks.
        let recent = any(app, "protect.recent.row")
        scrollTo(recent, in: app)
        XCTAssertTrue(recent.exists)
        save("protect-05-recent")

        // Spotted: the look-alike is there (new), and viewing it clears the badge.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let spotted = any(app, "protect.spotted")
        scrollTo(spotted, in: app)
        XCTAssertTrue(spotted.waitForExistence(timeout: 5))
        spotted.tap()
        XCTAssertTrue(any(app, "protect.spotted.entry").waitForExistence(timeout: 5))
        save("protect-06-spotted")
        any(app, "protect.spotted.entry").tap()
        save("protect-07-spotted-detail")
    }

    func testTheManualStepsWithTheIllustration() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "guard", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeModelState", "missing"]
        app.launch()
        let steps = any(app, "protect.safari.steps")
        scrollTo(steps, in: app)
        XCTAssertTrue(steps.waitForExistence(timeout: 15))
        steps.tap()
        XCTAssertTrue(app.staticTexts["Turn on Allow Extension."].waitForExistence(timeout: 5))
        save("protect-08-safari-steps")
    }
}
