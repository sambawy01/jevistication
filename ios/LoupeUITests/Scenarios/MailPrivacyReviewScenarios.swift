import XCTest

/// Scenario 10: mail triage, the privacy check and Review over the sample. Mail triage: run again, open a finding,
/// Mark safe the phishing mail. Privacy check: Mark safe one finding and Undo it, then Mark safe another. Review: the
/// privacy check proposes removing a duplicate copy (a real pair of files, `-LoupeReviewDemo`); Approve removes it,
/// Undo puts it back. Relaunch → every verdict is kept and the review queue is as it was left.
/// The relaunch leaves out `-LoupeReviewDemo`: it writes the duplicate pair again at every launch.
final class MailPrivacyReviewScenarios: ScenarioCase {
    private func nowCard(_ id: String, contains text: String, timeout: TimeInterval = 90) -> XCUIElement {
        tab("Now")
        let card = button(id)
        reveal(card)
        XCTAssertTrue(card.waitForExistence(timeout: 30), "Now has \(id)")
        waitFor(card, "label CONTAINS %@", text, timeout: timeout)
        return card
    }

    private func count(_ card: XCUIElement, _ word: String) -> Int {
        // "Mail triage: 1 possible phishing · 3 need a reply" → the number before `word`.
        guard let r = card.label.range(of: word) else { return 0 }
        return number(String(card.label[..<r.lowerBound].split(separator: " ").last ?? ""))
    }

    private func close() {
        if app.navigationBars.buttons.firstMatch.exists { back() }
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
    }

    func testVerdictsAndAnApprovedActionSurviveARelaunch() {
        start("mail-review", ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeReviewDemo"])

        // 1. Mail triage.
        let mailCard = nowCard("now.mail", contains: "possible phishing")
        let phishing = count(mailCard, " possible phishing")
        XCTAssertGreaterThan(phishing, 0)
        mailCard.tap()
        XCTAssertTrue(any("mail.section.phishing").waitForExistence(timeout: 30))
        audit("10-mail", ["mail.rerun", "mail.open", "mail.markSafe"])
        button("mail.rerun").tap()
        XCTAssertTrue(any("mail.section.phishing").waitForExistence(timeout: 30), "a re-run shows the triage again")
        let open = button("mail.open")
        reveal(open)
        open.tap()
        XCTAssertTrue(any("item.header").waitForExistence(timeout: 10), "Open shows the email")
        // Open / Share / Headers under an item are 26 pt bordered capsules (a polish item, left as they are: enlarging
        // them broke the Review rows' layout); present and hittable is what is checked here.
        XCTAssertTrue(button("item.open").isHittable, "Open is offered")
        audit("10-mail-item")
        close()
        let safe = button("mail.markSafe")
        reveal(safe)
        safe.tap()
        XCTAssertTrue(button("mail.undo").waitForExistence(timeout: 5), "Mark safe offers Undo")
        close()
        let mailAfter = nowCard("now.mail", contains: "Mail triage")
        XCTAssertEqual(count(mailAfter, " possible phishing"), phishing - 1, "marked safe: one fewer possible phishing (\(mailAfter.label))")

        // 2. The privacy check: Mark safe + Undo, then Mark safe another.
        let privacyCard = nowCard("now.privacy", contains: "finding")
        let findings = count(privacyCard, " finding")
        XCTAssertGreaterThan(findings, 1)
        privacyCard.tap()
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
        let privacyAfter = nowCard("now.privacy", contains: "finding")
        XCTAssertEqual(count(privacyAfter, " finding"), findings - 1, "one finding marked safe (\(privacyAfter.label))")

        // 3. Review: approve the proposed file change, undo it, approve again.
        let review = nowCard("now.review", contains: "To review", timeout: 30)
        review.tap()
        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Remove the extra copy review-demo-receipt")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 90), "the privacy check proposes removing the duplicate")
        // The email marked safe above is not waiting for "Confirm phishing" any more (it was, before the fix).
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
        // After Undo the copy is back; whether the check proposes it again is the review queue's state to keep.
        let pendingAfterUndo = app.buttons["Remove copy"].firstMatch.exists
        let toReview = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'To review: '")).firstMatch
        let reviewCount = toReview.exists ? toReview.label : ""
        shot("10-review-undone")
        close()

        // 4. Relaunch: every verdict kept; the removed copy is not proposed again.
        relaunch(dropping: ["-LoupeReviewDemo"])
        let m = nowCard("now.mail", contains: "Mail triage")
        waitUntil(timeout: 60, "mail triage ran") { !m.label.contains("reading") }
        XCTAssertEqual(count(m, " possible phishing"), phishing - 1, "Mark safe survived a relaunch (\(m.label))")
        let p = nowCard("now.privacy", contains: "finding")
        XCTAssertEqual(count(p, " finding"), findings - 1, "the privacy verdict survived a relaunch (\(p.label))")
        let r = button("now.review")
        reveal(r)
        XCTAssertTrue(r.exists)
        r.tap()
        XCTAssertTrue(app.staticTexts["review.notUnsure"].waitForExistence(timeout: 10))
        sleep(5)
        XCTAssertEqual(app.buttons["Remove copy"].firstMatch.exists, pendingAfterUndo, "the undone action's state survived a relaunch")
        let toReviewAfter = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'To review: '")).firstMatch
        if !reviewCount.isEmpty { XCTAssertEqual(toReviewAfter.label, reviewCount, "the review count survived a relaunch") }
        shot("10-review-relaunch")
    }
}
