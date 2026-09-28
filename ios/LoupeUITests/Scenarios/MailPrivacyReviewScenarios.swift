import XCTest

/// Scenario 10: Mail, the privacy check and Review over the sample. Me → Mail: run again, open a finding, Mark safe the
/// phishing mail (the "Found in your mail" count drops). Me → What Loupe reads → Privacy check: Mark safe one finding
/// and Undo it, then Mark safe another. Me → To review: the privacy check proposes removing a duplicate copy (a real
/// pair of files, `-LoupeReviewDemo`); Approve removes it, Undo puts it back. Relaunch → every verdict is kept and the
/// review queue is as it was left. The relaunch leaves out `-LoupeReviewDemo`: it writes the duplicate pair again at
/// every launch.
final class MailPrivacyReviewScenarios: ScenarioCase {
    /// "Phishing: 2 · Needs a reply: 3 · …" → 2 for "Phishing"; "Privacy check: 4 findings" → 4 for "Privacy check".
    private func value(_ label: String, _ name: String) -> Int {
        guard let r = label.range(of: name + ": ") else { return -1 }
        return number(String(label[r.upperBound...]))
    }

    /// Me → Mail's "Found in your mail" card, once triage has read the sample.
    private func mailFound() -> XCUIElement {
        openMail()
        let found = any("mail.found")
        XCTAssertTrue(found.waitForExistence(timeout: 30))
        waitFor(found, "NOT (label BEGINSWITH %@)", "Phishing: 0 · Needs a reply: 0", timeout: 90, "mail triage read the sample")
        return found
    }

    /// Me → What Loupe reads → the privacy check's card, once the check has run.
    private func privacyCard() -> XCUIElement {
        openReads()
        let card = button("sources.privacy")
        reveal(card)
        waitFor(card, "label CONTAINS %@", "finding", timeout: 90, "the privacy check ran")
        return card
    }

    /// Me → To review.
    private func openReview() {
        root("Me")
        let row = button("me.review")
        reveal(row)
        XCTAssertTrue(row.exists, "Me offers the review queue")
        row.tap()
        XCTAssertTrue(app.staticTexts["review.notUnsure"].waitForExistence(timeout: 10))
    }

    private func close() {
        if app.navigationBars.buttons.firstMatch.exists { back() }
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
    }

    func testVerdictsAndAnApprovedActionSurviveARelaunch() {
        start("mail-review", ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeReviewDemo"])

        // 1. Mail.
        let found = mailFound()
        let phishing = value(found.label, "Phishing")
        XCTAssertGreaterThan(phishing, 0)
        XCTAssertTrue(any("mail.section.phishing").waitForExistence(timeout: 30))
        audit("10-mail", ["mail.rerun", "mail.open", "mail.markSafe", "sources.phone.mail.setup"])
        button("mail.rerun").tap()
        XCTAssertTrue(any("mail.section.phishing").waitForExistence(timeout: 30), "a re-run shows the triage again")
        let open = button("mail.open")
        reveal(open)
        open.tap()
        XCTAssertTrue(any("item.header").waitForExistence(timeout: 10), "Open shows the email")
        XCTAssertTrue(button("item.open").isHittable, "Open is offered")
        audit("10-mail-item")
        close()
        let safe = button("mail.markSafe")
        reveal(safe)
        safe.tap()
        XCTAssertTrue(button("mail.undo").waitForExistence(timeout: 5), "Mark safe offers Undo")
        let foundAfter = any("mail.found")
        reveal(foundAfter)
        waitFor(foundAfter, "label BEGINSWITH %@", "Phishing: \(phishing - 1) ", timeout: 30, "marked safe: one fewer possible phishing")

        // 2. The privacy check: Mark safe + Undo, then Mark safe another.
        let privacy = privacyCard()
        let findings = value(privacy.label, "Privacy check")
        XCTAssertGreaterThan(findings, 1)
        privacy.tap()
        XCTAssertTrue(any("privacy.group.ids").waitForExistence(timeout: 30))
        let markSafe = button("privacy.safe")
        reveal(markSafe)
        audit("10-privacy", ["privacy.rerun", "privacy.safe"])
        markSafe.tap()
        let undo = button("privacy.undo")
        reveal(undo)
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        audit("10-privacy-undo", ["privacy.undo"])
        undo.tap()
        let safe2 = button("privacy.safe")
        reveal(safe2)
        safe2.tap()
        close()
        let privacyAfter = button("sources.privacy")
        reveal(privacyAfter)
        waitFor(privacyAfter, "label BEGINSWITH %@", "Privacy check: \(findings - 1) finding", timeout: 30, "one finding marked safe")

        // 3. Review: approve the proposed file change, undo it.
        openReview()
        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Remove the extra copy review-demo-receipt")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 90), "the privacy check proposes removing the duplicate")
        XCTAssertFalse(app.buttons["Confirm phishing"].firstMatch.exists, "an answered email is withdrawn from Review")
        let approve = app.buttons["Remove copy"].firstMatch
        reveal(approve)
        audit("10-review", ["review.reject"])
        approve.tap()
        let notice = app.staticTexts["review.notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 20))
        waitFor(notice, "label BEGINSWITH %@", "Done: Remove the extra copy", timeout: 20)
        let undoLast = button("review.undoLast")
        XCTAssertTrue(undoLast.waitForExistence(timeout: 10))
        audit("10-review-done", ["review.undoLast"])
        undoLast.tap()
        waitFor(notice, "label == %@", "Undone.", timeout: 20)
        sleep(2)
        let pendingAfterUndo = app.buttons["Remove copy"].firstMatch.exists
        let toReview = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'To review: '")).firstMatch
        let reviewCount = toReview.exists ? toReview.label : ""
        shot("10-review-undone")
        close()

        // 4. Relaunch: every verdict kept; the removed copy is not proposed again.
        relaunch(dropping: ["-LoupeReviewDemo"])
        XCTAssertEqual(value(mailFound().label, "Phishing"), phishing - 1, "Mark safe survived a relaunch")
        XCTAssertEqual(value(privacyCard().label, "Privacy check"), findings - 1, "the privacy verdict survived a relaunch")
        openReview()
        sleep(5)
        XCTAssertEqual(app.buttons["Remove copy"].firstMatch.exists, pendingAfterUndo, "the undone action's state survived a relaunch")
        let toReviewAfter = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'To review: '")).firstMatch
        if !reviewCount.isEmpty { XCTAssertEqual(toReviewAfter.label, reviewCount, "the review count survived a relaunch") }
        shot("10-review-relaunch")
    }
}
