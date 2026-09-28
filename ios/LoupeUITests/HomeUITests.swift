import XCTest

/// Home over the sample (spec 2026-09-28 §3): the cards in order, each opening today's screen for it, the newest
/// findings in Needs attention, and a second tap on Home going back to its first screen.
final class HomeUITests: ScenarioCase {
    private func launchHome() {
        launch(["-LoupeFixtures", "-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeLanguage", "en"])
        let money = button("home.money")
        XCTAssertTrue(money.waitForExistence(timeout: 30))
        waitFor(money, "label CONTAINS %@", "a month", timeout: 90, "the watchers ran over the sample")
    }

    func testHomeCardsOpenTheirScreensAndHomeComesBack() {
        launchHome()
        let attention = any("home.attention"), quick = button("guard.quick.checkLink")
        let money = button("home.money"), docs = button("home.documents"), protected = button("home.protected")
        XCTAssertTrue(attention.exists, "the sample has findings: Needs attention shows")
        XCTAssertLessThan(attention.frame.minY, quick.frame.minY, "Needs attention, then Quick check")
        reveal(protected)
        XCTAssertLessThan(quick.frame.minY, money.frame.minY)
        XCTAssertLessThan(money.frame.minY, docs.frame.minY)
        XCTAssertLessThan(docs.frame.minY, protected.frame.minY, "Money, Documents, then Protected")
        audit("home", ["guard.quick.checkLink", "guard.quick.checkCopied", "home.money", "home.documents", "home.protected"])

        reveal(money); money.tap()
        XCTAssertTrue(any("guard.subscriptions").waitForExistence(timeout: 10), "Money opens Subscriptions")
        back()
        reveal(docs); docs.tap()
        XCTAssertTrue(any("guard.expiry").waitForExistence(timeout: 10), "Documents opens Expiring")
        back()
        reveal(protected); protected.tap()
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 10), "Protected opens the Protection screen")
        tab("Home")
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 5), "a second tap on Home goes back to its first screen")
    }

    func testNeedsAttentionShowsTheNewestFindings() {
        launchHome()
        XCTAssertTrue(any("finding.0").waitForExistence(timeout: 10))
        XCTAssertFalse(any("finding.3").exists, "three findings at most on Home; the rest are on Protection")
        let open = button("finding.open.0")
        if open.exists { XCTAssertGreaterThanOrEqual(open.frame.height, 44) }
    }
}
