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
        // The card leads with the live, on-device decisions; no numbers until this iPhone measured a run.
        XCTAssertTrue(app.staticTexts["Watch Loupe fly"].exists)
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "on this iPhone")).firstMatch.exists)
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
    func testWatchWithoutModelFliesBaselineWithNote() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeGame", "watch", "-LoupeNoModel"]
        app.launch()
        let rows = app.descendants(matching: .any)["game.rows"]
        XCTAssertTrue(rows.waitForExistence(timeout: 5))
        let note = app.descendants(matching: .any)["game.noModel"]
        XCTAssertTrue(note.waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["game.getModel"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["game.bars"].exists)
    }

    /// You fly: a labelled FIRE button sits in the control bar under the river — right, thumb-sized,
    /// and never over the river (owner's report 2026-09-25: it covered the plane). Tapping it fires
    /// rather than pausing (a quick tap on the river pauses), holding it does not pause either, and
    /// the hint says steering never fires. Watch mode shows no FIRE button.
    func testFireButtonFiresWithoutPausingAndIsAbsentInWatch() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeGame", "human"]
        app.launch()
        let fire = app.descendants(matching: .any)["game.fire"]
        XCTAssertTrue(fire.waitForExistence(timeout: 5))
        XCTAssertEqual(fire.label, "Fire")
        let river = app.descendants(matching: .any)["game.river"]
        XCTAssertTrue(river.exists)
        XCTAssertGreaterThanOrEqual(fire.frame.width, 64)
        XCTAssertFalse(fire.frame.intersects(river.frame), "FIRE covers the river: \(fire.frame) vs \(river.frame)")
        XCTAssertGreaterThanOrEqual(fire.frame.minY, river.frame.maxY, "FIRE sits under the river")
        XCTAssertGreaterThan(fire.frame.midX, river.frame.midX, "FIRE sits on the right, in the thumb zone")
        XCTAssertLessThanOrEqual(fire.frame.maxY, app.frame.maxY - 20, "FIRE clears the home indicator")
        let help = app.descendants(matching: .any)["game.help"]
        XCTAssertTrue(help.label.contains("steering never fires"), help.label)

        let resume = app.buttons["game.resume"]
        fire.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertFalse(resume.waitForExistence(timeout: 1), "a tap on FIRE paused the game")
        fire.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.8)
        XCTAssertFalse(resume.waitForExistence(timeout: 1), "holding FIRE paused the game")

        // A quick tap on the open river still pauses.
        river.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).tap()
        XCTAssertTrue(resume.waitForExistence(timeout: 3), "a tap on the river pauses")
        XCTAssertFalse(fire.exists, "no FIRE button while paused")
        resume.tap()
        XCTAssertTrue(fire.waitForExistence(timeout: 3))

        app.buttons["Watch Loupe"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["game.rows"].waitForExistence(timeout: 5))
        XCTAssertFalse(fire.waitForExistence(timeout: 2), "Watch mode has no FIRE button")
    }
}
