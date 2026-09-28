import XCTest

final class ReviewPacksUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Me → To review → approve the privacy check's proposal to remove a duplicate copy (a real
    /// duplicate pair in the throwaway Send to Loupe inbox), then undo it.
    func testApproveAPrivacyCheckProposedAction() {
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeFixtures", "-LoupeReviewDemo", "-LoupeRunNow"]
        app.launch()
        // "To review" is a List row (UIKit-backed): below the fold it is not in the hierarchy at all until
        // scrolled to, so scroll first and only then wait for it to exist.
        let card = app.buttons["me.review"]
        for _ in 0..<10 where !(card.exists && card.isHittable) { app.swipeUp() }
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        card.tap()
        XCTAssertTrue(app.staticTexts["review.notUnsure"].waitForExistence(timeout: 10))
        // The duplicate is proposed once the inbox scan and the privacy check have run (live).
        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Remove the extra copy review-demo-receipt")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 90))
        let approve = app.buttons["Remove copy"].firstMatch
        XCTAssertTrue(approve.exists)
        approve.tap()
        let notice = app.staticTexts["review.notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 20))
        expectation(for: NSPredicate(format: "label BEGINSWITH %@", "Done: Remove the extra copy"), evaluatedWith: notice)
        waitForExpectations(timeout: 20)
        // The removed copy is held: Undo puts it back.
        let undo = app.buttons["review.undoLast"]
        XCTAssertTrue(undo.waitForExistence(timeout: 10))
        undo.tap()
        expectation(for: NSPredicate(format: "label == %@", "Undone."), evaluatedWith: notice)
        waitForExpectations(timeout: 20)
    }

    /// Judgments → Packs → Try the example pack → preview (labelled an example) → Add → its judgments.
    func testImportTheExamplePackAndSeeItsJudgments() {
        let app = XCUIApplication.loupe()
        app.launchArguments = ["-LoupeTab", "judgments", "-LoupeSkipOnboarding", "-LoupeFixtures"]
        app.launch()
        let menu = app.buttons["judgments.packs"]
        XCTAssertTrue(menu.waitForExistence(timeout: 30))
        menu.tap()
        app.buttons["packs.example"].tap()
        XCTAssertTrue(app.staticTexts["Bistro Cloud"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any)["packs.exampleLabel"].exists)
        let add = app.buttons["packs.add"]
        for _ in 0..<12 where !add.isHittable { app.swipeUp() }
        XCTAssertEqual(add.label, "Add 14 judgments")
        add.tap()
        XCTAssertTrue(app.staticTexts["Complaint triage: Team"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["judgments.mine.j-complaint-triage-team"].exists)
        XCTAssertTrue(app.buttons["judgments.mine.j-complaint-triage-frustration"].exists)
        // A refused question (bare yes/no) was not added.
        XCTAssertFalse(app.buttons["judgments.mine.j-gmail-triage-is-phishing"].exists)
    }
}
