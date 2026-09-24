import XCTest

final class PrivacyUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Now → Privacy check shows the sample's SPECIMEN ID documents and duplicate receipts.
    func testNowOpensPrivacyCheckWithSampleFindings() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        let card = app.buttons["now.privacy"]
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        let loaded = NSPredicate(format: "label CONTAINS %@", "findings")
        expectation(for: loaded, evaluatedWith: card)
        waitForExpectations(timeout: 60)
        card.tap()
        XCTAssertTrue(app.descendants(matching: .any)["privacy.group.ids"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["documents/identity/passport-scan-SPECIMEN.txt"].exists)
        XCTAssertTrue(app.staticTexts["documents/identity/driving-licence-SPECIMEN.txt"].exists)
        let dups = app.descendants(matching: .any)["privacy.group.duplicates"]
        for _ in 0..<6 where !dups.exists { app.swipeUp() }
        XCTAssertTrue(dups.exists)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "fresh-basket-2026-08-14 (copy).txt")).firstMatch.exists)
    }
}

final class PrivacyShowWhereUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Owner rule 2026-09-24: a privacy finding on a picture → Show where shows the masked value
    /// and draws the OCR line's box over the image (a rendered test-card photo, DEBUG only).
    func testShowWhereMasksTheCardAndBoxesItOnThePhoto() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupePrivacyPhotoDemo", "-LoupeTab", "now", "-LoupeSkipOnboarding"]
        app.launch()
        let card = app.buttons["now.privacy"]
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        expectation(for: NSPredicate(format: "label CONTAINS %@", "findings"), evaluatedWith: card)
        waitForExpectations(timeout: 60)
        card.tap()
        XCTAssertTrue(app.descendants(matching: .any)["privacy.group.cards"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Payment card ×1"].exists, "one clean chip")
        let show = app.buttons["item.showWhere.card_number"]
        for _ in 0..<8 where !show.isHittable { app.swipeUp() }
        show.tap()
        XCTAssertTrue(app.staticTexts["•••• •••• •••• 6467"].waitForExistence(timeout: 60), "masked value")
        XCTAssertTrue(app.descendants(matching: .any)["evidence.image"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["evidence.box.0"].waitForExistence(timeout: 30), "the OCR line's box")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "4539 1488")).firstMatch.exists, "no unmasked value")
    }
}
