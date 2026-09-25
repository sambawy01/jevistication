import XCTest

final class GameUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Open the game from Now's Play card, see it run (rows climb), pause it, and see it stop.
    func testPlayFromNowRunsAndPauses() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "now"]
        app.launch()

        let play = app.buttons["now.play.human"]
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        play.tap()

        let rows = app.descendants(matching: .any)["game.rows"]
        XCTAssertTrue(rows.waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["game.river"].exists)
        let first = Int(rows.value as? String ?? "") ?? -1
        let running = expectation(for: NSPredicate { _, _ in (Int(rows.value as? String ?? "") ?? -1) > first + 3 },
                                  evaluatedWith: nil)
        wait(for: [running], timeout: 6)

        app.buttons["game.pause"].tap()
        XCTAssertTrue(app.buttons["game.resume"].waitForExistence(timeout: 3))
        let paused = Int(rows.value as? String ?? "") ?? -1
        sleep(1)
        XCTAssertEqual(Int(rows.value as? String ?? "") ?? -2, paused, "the river moved while paused")

        app.buttons["game.resume"].tap()
        XCTAssertFalse(app.buttons["game.resume"].waitForExistence(timeout: 1))
        app.buttons["game.close"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 3))
    }

    /// Watch from Now's Play card: the river runs, the level climbs (30 rows a level here), and
    /// pause stops it.
    func testWatchFromNowLevelClimbsAndPauses() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "now", "-LoupeGameLevelRows", "30"]
        app.launch()
        let watch = app.buttons["now.play.watch"]
        XCTAssertTrue(watch.waitForExistence(timeout: 5))
        watch.tap()
        let level = app.descendants(matching: .any)["game.level"]
        let rows = app.descendants(matching: .any)["game.rows"]
        XCTAssertTrue(level.waitForExistence(timeout: 5))
        XCTAssertEqual(level.value as? String, "1")
        let climbed = expectation(for: NSPredicate { _, _ in (Int(level.value as? String ?? "") ?? 0) >= 2 }, evaluatedWith: nil)
        wait(for: [climbed], timeout: 20)

        app.buttons["game.pause"].tap()
        XCTAssertTrue(app.buttons["game.resume"].waitForExistence(timeout: 3))
        let paused = rows.value as? String
        sleep(1)
        XCTAssertEqual(rows.value as? String, paused, "the river moved while paused")
        app.buttons["game.resume"].tap()
        app.buttons["game.close"].tap()
        XCTAssertTrue(watch.waitForExistence(timeout: 3))
    }

    /// Watch mode without the model: the baseline flies, with a note and a link to Me → Laya model.
    func testWatchWithoutModelFliesBaselineWithNote() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeGame", "watch"]
        app.launch()
        let rows = app.descendants(matching: .any)["game.rows"]
        XCTAssertTrue(rows.waitForExistence(timeout: 5))
        let note = app.descendants(matching: .any)["game.noModel"]
        if !note.waitForExistence(timeout: 3) {
            throw XCTSkip("the Laya model is installed on this simulator; the no-model note does not apply")
        }
        XCTAssertTrue(app.buttons["game.getModel"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["game.bars"].exists)
    }
}
