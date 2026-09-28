import XCTest

/// Scenario 7: Guard over the sample. The quick actions at the top (Check a link, Check what I copied, Spotted) work;
/// Run now runs the watchers again; subscriptions: confirm one, mark one "not a subscription", set one aside and Undo
/// it; Expiring soon opens a document's detail. Relaunch → the verdicts are kept (one fewer recurring charge, the
/// confirmed one still confirmed, the undone one still listed).
final class GuardScenarios: ScenarioCase {
    private var summary: XCUIElement {
        app.otherElements.matching(NSPredicate(format: "label BEGINSWITH 'Subscriptions: '")).firstMatch
    }

    private func merchant(_ i: Int) -> String {
        let row = button("guard.subscription.\(i)")
        reveal(row)
        XCTAssertTrue(row.exists, "no subscription row \(i)")
        return row.label.components(separatedBy: ",").first ?? row.label
    }

    private func row(named name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'guard.subscription.' AND label BEGINSWITH %@", name + ",")).firstMatch
    }

    private func charges() -> Int {
        let s = summary
        reveal(s)
        // "Subscriptions: £41.96 a month, 5 recurring charges, sample data"
        let part = s.label.components(separatedBy: ", ").first { $0.contains("recurring charge") } ?? ""
        return number(part)
    }

    private func waitForWatchers() {
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20))
        XCTAssertTrue(any("guard.subscriptions.total").waitForExistence(timeout: 90), "the watchers ran over the sample")
    }

    func testQuickActionsAndSubscriptionVerdictsSurviveARelaunch() {
        UIPasteboard.general.items = []
        // -LoupeClipboard: under -LoupeFixtures the clipboard's chip and banner stay silent unless a test asks.
        start("guard", ["-LoupeTab", "guard", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeClipboard"])
        waitForWatchers()
        audit("07-guard-top", ["guard.quick.checkLink", "guard.quick.checkCopied", "guard.quick.spotted", "guard.runNow"])

        // The quick actions.
        button("guard.quick.checkLink").tap()
        XCTAssertTrue(any("protect.link.input").waitForExistence(timeout: 5), "Check a link opens")
        back()
        let spotted = any("guard.quick.spotted")
        reveal(spotted)
        spotted.tap()
        XCTAssertTrue(any("protect.spotted.empty").waitForExistence(timeout: 5) || any("protect.spotted.entry").exists, "Spotted opens")
        back()
        UIPasteboard.general.string = "https://www.bbc.co.uk/news"
        let copied = button("guard.quick.checkCopied")
        reveal(copied)
        copied.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow Paste"].firstMatch
        if allow.waitForExistence(timeout: 4) { allow.tap() }
        let banner = any("clip.banner")
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "Check what I copied answers")
        XCTAssertEqual(banner.value as? String, "safe")
        any("clip.banner.dismiss").tap()
        UIPasteboard.general.items = []

        // Run now: the watchers run again and read the sample (not 0 items).
        let run = button("guard.runNow")
        reveal(run)
        run.tap()
        let lastRun = any("guard.header.lastRun")
        reveal(lastRun)
        waitFor(lastRun, "NOT (label ENDSWITH '· 0 items')", timeout: 60)

        // Subscriptions (the sample has two): confirm one; set the other aside and Undo it; then mark it "not a
        // subscription".
        let before = charges()
        XCTAssertGreaterThanOrEqual(before, 2, "the sample has recurring charges")
        let confirmed = merchant(0), other = merchant(1)

        row(named: confirmed).tap()
        let confirm = button("guard.subscription.confirm")
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        audit("07-subscription", ["guard.subscription.confirm", "guard.subscription.notSubscription", "guard.subscription.setAside"])
        confirm.tap()
        XCTAssertFalse(confirm.waitForExistence(timeout: 2), "confirmed: the Confirm button goes")
        back()

        reveal(row(named: other))
        row(named: other).tap()
        let aside = button("guard.subscription.setAside")
        reveal(aside)
        aside.tap()
        let undo = button("guard.undo")
        reveal(undo)
        XCTAssertTrue(undo.waitForExistence(timeout: 5), "Set aside offers Undo")
        audit("07-undo", ["guard.undo"])
        undo.tap()
        XCTAssertTrue(row(named: other).waitForExistence(timeout: 10), "Undo puts \(other) back")
        XCTAssertEqual(charges(), before, "Undo restored the count")

        reveal(row(named: other))
        row(named: other).tap()
        let not = button("guard.subscription.notSubscription")
        reveal(not)
        not.tap()
        XCTAssertTrue(any("guard.subscriptions.total").waitForExistence(timeout: 10))
        waitUntil(timeout: 10, "\(other) leaves the list") { !self.row(named: other).exists }
        XCTAssertEqual(charges(), before - 1, "one recurring charge fewer")

        // Expiring soon: a dated document opens its detail.
        let passport = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "passport")).firstMatch
        reveal(passport)
        XCTAssertTrue(passport.exists, "Expiring soon lists the passport")
        passport.tap()
        XCTAssertTrue(any("guard.expiry.detail").waitForExistence(timeout: 5))
        audit("07-expiry-detail")
        back()

        // Relaunch: the verdicts are kept.
        relaunch()
        waitForWatchers()
        XCTAssertEqual(charges(), before - 1, "still one fewer after a relaunch")
        XCTAssertFalse(row(named: other).exists, "\(other) stays out")
        let c = row(named: confirmed)
        reveal(c)
        c.tap()
        XCTAssertTrue(any("guard.subscription.detail.amount").waitForExistence(timeout: 5))
        XCTAssertFalse(button("guard.subscription.confirm").exists, "\(confirmed) is still confirmed")
        back()
        // Home: Money and Documents open the same Subscriptions and Expiring, with the verdicts kept.
        root("Home")
        audit("07-home", ["home.money", "home.documents", "home.protected"])
        let money = button("home.money")
        reveal(money)
        money.tap()
        XCTAssertTrue(any("guard.subscriptions.total").waitForExistence(timeout: 10), "Money opens Subscriptions")
        XCTAssertEqual(charges(), before - 1, "the same census as on Protection")
        back()
        let documents = button("home.documents")
        reveal(documents)
        documents.tap()
        let passportRow = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "passport")).firstMatch
        reveal(passportRow)
        XCTAssertTrue(passportRow.exists, "Documents opens Expiring with the passport")
        shot("07-relaunch-confirmed")
    }
}
