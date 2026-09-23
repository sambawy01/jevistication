import XCTest

final class SourcesUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Sources lists the sample, labelled as sample data, with its item count; phone sources are
    /// present but not yet available.
    func testSourcesShowsSampleWithCount() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding"]
        app.launch()
        let count = app.staticTexts["sources.sample.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 30))
        XCTAssertEqual(count.label, "48 items")
        XCTAssertEqual(app.staticTexts["sources.sample.label"].label, "Sample data — not from your phone")
        XCTAssertTrue(app.staticTexts["Sample data"].exists)
        let photos = app.descendants(matching: .any)["sources.phone.photos"]
        XCTAssertTrue(photos.exists)
        XCTAssertTrue(photos.label.contains("Coming in child 7"), photos.label)
    }
}
