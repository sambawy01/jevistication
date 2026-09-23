import XCTest

/// The Inbox (epic #7 child 15): a CSV imported through the DEBUG launch path (-LoupeInboxDemo, the
/// same `importToInbox` the Files picker calls) shows as one import with its counts, and its rows as
/// items labelled with where they came from; the import can be removed.
final class InboxUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testImportedCsvRowsAreItems() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupeInboxDemo"]
        app.launch()

        let inbox = app.buttons["sources.inbox"]
        for _ in 0..<4 where !inbox.isHittable { app.swipeUp() }
        XCTAssertTrue(inbox.waitForExistence(timeout: 20))
        let summary = app.staticTexts["sources.inbox.summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 5))
        let imported = NSPredicate(format: "label BEGINSWITH '1 import · 3 items'")
        expectation(for: imported, evaluatedWith: summary)
        waitForExpectations(timeout: 20)
        inbox.tap()

        let batch = app.buttons["inbox.batch.0"]
        XCTAssertTrue(batch.waitForExistence(timeout: 10))
        XCTAssertTrue(batch.label.contains("statement-fixture.csv"), batch.label)
        XCTAssertTrue(batch.label.contains("3 CSV rows"), batch.label)
        batch.tap()

        XCTAssertTrue(app.staticTexts["inbox.batch.counts"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["inbox.batch.label"].label.hasPrefix("Imported · Test fixture · statement-fixture.csv"))
        let first = app.buttons["inbox.item.0"]
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        XCTAssertTrue(first.label.contains("STREAMFLIX.COM"), first.label)
        XCTAssertTrue(first.label.contains("row 1"), first.label)
        XCTAssertTrue(app.buttons["inbox.item.2"].label.contains("Cafe Luna"))

        // Open a row: the text the model reads is the row with its column names.
        first.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Description: STREAMFLIX.COM'")).firstMatch.waitForExistence(timeout: 5))
        app.swipeDown(velocity: .fast)

        let remove = app.buttons["inbox.batch.remove"]
        for _ in 0..<3 where !remove.isHittable { app.swipeUp() }
        remove.tap()
        XCTAssertTrue(app.staticTexts["Nothing imported yet."].waitForExistence(timeout: 5))
    }
}
