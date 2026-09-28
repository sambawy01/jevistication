import XCTest

/// Scenario 11: the game. You fly: the FIRE button is hittable, thumb-sized and never over the river; a tap on it
/// fires without pausing. Auto-fire (off by default) is turned on. Watch Loupe: the speed panel (and, without the
/// model here, the rule pilot's banner with the way to get the model). Relaunch → auto-fire is still on; then off.
/// The results card follows a decision-model run only, so it needs the real model (the device checklist).
final class GameScenarios: ScenarioCase {
    private func openYouFly() {
        root("Me")
        let play = button("me.play.human")
        reveal(play)
        XCTAssertTrue(play.waitForExistence(timeout: 10))
        play.tap()
        XCTAssertTrue(any("game.fire").waitForExistence(timeout: 10), "You fly shows FIRE")
    }

    private var autofire: XCUIElement { app.switches["game.autofire"] }

    func testFireWatchAndAutoFireSurviveARelaunch() {
        start("game", ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeModelState", "missing"])
        XCTAssertTrue(tabs.waitForExistence(timeout: 20))
        audit("11-me-play", ["me.play.human", "me.play.watch"])
        openYouFly()

        // FIRE: hittable, big, under the river on the right; a tap fires and does not pause.
        let fire = any("game.fire"), river = any("game.river")
        XCTAssertTrue(fire.isHittable)
        XCTAssertGreaterThanOrEqual(fire.frame.width, 64)
        XCTAssertFalse(fire.frame.intersects(river.frame), "FIRE is over the river: \(fire.frame) vs \(river.frame)")
        audit("11-you-fly", ["game.fire", "game.close", "game.pause", "game.rush"])
        fire.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertFalse(button("game.resume").waitForExistence(timeout: 1), "a tap on FIRE paused the game")

        // Auto-fire: off by default; on.
        reveal(autofire)
        XCTAssertTrue(autofire.exists, "the auto-fire switch is on the You fly panel")
        XCTAssertEqual(autofire.value as? String, "0", "auto-fire is off by default")
        flip(autofire)
        waitFor(autofire, "value == %@", "1")
        audit("11-autofire")

        // Watch Loupe: the speed panel; without the model the rule pilot flies, and says how to get the model.
        let watch = app.buttons["Watch Loupe"]
        reveal(watch)
        watch.tap()
        XCTAssertTrue(any("game.rows").waitForExistence(timeout: 10))
        XCTAssertFalse(any("game.fire").waitForExistence(timeout: 2), "Watch has no FIRE")
        let speed = any("game.speed")
        reveal(speed)
        XCTAssertTrue(speed.waitForExistence(timeout: 10), "Watch shows the speed panel")
        XCTAssertTrue(any("game.noModel").exists, "the rule pilot flies, and it says so")
        audit("11-watch", ["game.getModel", "game.close"])
        let close = button("game.close")
        reveal(close)
        close.tap()
        XCTAssertTrue(button("me.play.human").waitForExistence(timeout: 5), "Close returns to Me")

        // Relaunch: auto-fire stays on.
        relaunch()
        openYouFly()
        reveal(autofire)
        XCTAssertEqual(autofire.value as? String, "1", "auto-fire survived a relaunch")
        flip(autofire)                       // put it back (it lives in the app's own settings)
        waitFor(autofire, "value == %@", "0")
    }
}
