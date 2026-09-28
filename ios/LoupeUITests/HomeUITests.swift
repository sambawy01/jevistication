import XCTest

/// Home over the sample (spec 2026-09-28 §3): the cards in order, each opening today's screen for it, the newest
/// findings in Needs attention, and a second tap on Home going back to its first screen.
final class HomeUITests: ScenarioCase {
    private func launchHome() {
        // Nothing runs at launch (2026-09-28): -LoupeRunNow's run (Run now's) reads the fixture and runs the checks.
        launch(["-LoupeFixtures", "-LoupeRunNow", "-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeLanguage", "en"])
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
        XCTAssertTrue(any("finding.0").waitForExistence(timeout: 10), "the newest finding is first on Home: finding.0")
        // No sample data in the app (2026-09-28): no sample badge on a finding, no Sample pill anywhere.
        XCTAssertFalse(any("finding.0").label.localizedCaseInsensitiveContains("sample data"), any("finding.0").label)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label == %@", "Sample")).firstMatch.exists, "no Sample pill")
        // The cards are numbered by their place in Needs attention: 0, 1, 2 and never more.
        let cards = any("home.attention").descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@", "finding\\.[0-9]+"))
        let ids = Set(cards.allElementsBoundByIndex.map(\.identifier))
        XCTAssertFalse(ids.isEmpty)
        XCTAssertLessThanOrEqual(ids.count, 3, "three findings at most on Home; the rest are on Protection: \(ids)")
        XCTAssertEqual(ids, Set((0..<ids.count).map { "finding.\($0)" }), "numbered by place on Home: \(ids)")
        XCTAssertFalse(any("finding.3").exists, "three findings at most on Home; the rest are on Protection")
        // FindingCard's own id covers its children, so its Open item is found by its label: the first one in
        // Needs attention is finding.0's (the other cards there have none).
        let open = any("home.attention").buttons["Open item"].firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 5), "the sample's newest finding names its item")
        XCTAssertGreaterThanOrEqual(open.frame.height, 44)
    }
}
