import XCTest

/// The device smoke subset (scheme `LoupeDeviceSmoke`, 2026-09-27): only non-destructive journeys, safe on the owner's
/// own iPhone with their real data. No fixtures, no fixture home and no reset flag of any kind (no
/// `-LoupeScenarioReset`, `-LoupeResetOnboarding`, `-LoupeClipboardReset`), never Delete all my Loupe data, never a
/// model download, no mailbox. What they change is small and put back (a game preference), or is a normal use of the
/// app (one link check in Recent checks). `-LoupeSkipOnboarding` only keeps the steps out of the way; it records
/// nothing. They run in the simulator suite too.
final class DeviceSmokeScenarios: ScenarioCase {
    private func launchSmoke(_ tab: String) {
        launch(["-LoupeSkipOnboarding", "-LoupeTab", tab])
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), "the tabs")
    }

    /// Every tab: the actions it needs are there, hittable and 44 pt, and nothing says "Laya".
    func testEveryTabHasItsActions() {
        launchSmoke("now")
        XCTAssertEqual(tabs.buttons.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }.count, 5, "five tabs")
        audit("smoke-now", ["now.play.watch", "now.play.human"])

        tab("Guard")
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20))
        audit("smoke-guard", ["guard.quick.checkLink", "guard.quick.checkCopied", "guard.runNow"])

        tab("Judgments")
        let mine = app.segmentedControls["judgments.section"].buttons["My judgments"]
        if mine.waitForExistence(timeout: 5) { mine.tap() }
        audit("smoke-judgments", ["judgments.write", "judgments.packs"])
        let library = app.segmentedControls["judgments.section"].buttons["Library"]
        library.tap()
        XCTAssertTrue(app.textFields["library.search"].waitForExistence(timeout: 10), "the Library opens")
        audit("smoke-library")
        app.segmentedControls["judgments.section"].buttons["Web questions"].tap()
        XCTAssertTrue(any("web.template.currency").waitForExistence(timeout: 10), "Web questions open")
        audit("smoke-web", ["web.template.currency", "web.settings"])

        tab("Sources")
        for id in ["photos", "files", "calendar", "contacts", "mail"] {
            let sw = app.switches["sources.phone.\(id).toggle"]
            reveal(sw)
            XCTAssertTrue(sw.exists, "Sources has \(id)")
        }
        audit("smoke-sources")

        tab("Me")
        audit("smoke-me", ["me.model", "me.modelSettings", "me.onlineChecks", "me.assistant", "me.export", "me.erase",
                           "me.licences", "me.privacy", "me.terms"])
        XCTAssertTrue(any("me.version").exists, "the version is shown")
    }

    /// Guard → Check a link with an ordinary website: no warning signs, and the check is in Recent checks after a
    /// relaunch.
    func testCheckAWebsiteAndItIsInRecentChecksAfterARelaunch() {
        launchSmoke("guard")
        let check = button("guard.quick.checkLink")
        XCTAssertTrue(check.waitForExistence(timeout: 20))
        check.tap()
        let field = any("protect.link.input")
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("www.bbc.co.uk/news")
        button("protect.link.check").tap()
        let verdict = any("protect.verdict")
        XCTAssertTrue(verdict.waitForExistence(timeout: 20))
        XCTAssertEqual(verdict.value as? String, "safe")
        audit("smoke-check-link", ["protect.link.check"])

        relaunch()
        let again = button("guard.quick.checkLink")
        XCTAssertTrue(again.waitForExistence(timeout: 20))
        again.tap()
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == 'protect.recent.row' AND label CONTAINS 'bbc.co.uk'")).firstMatch
        reveal(row)
        XCTAssertTrue(row.exists, "the check survived a relaunch")
    }

    /// A preference (the game's auto-fire) survives a relaunch, then is put back.
    func testAPreferenceSurvivesARelaunch() {
        launchSmoke("now")
        let play = button("now.play.human")
        reveal(play)
        play.tap()
        let fire = any("game.fire")
        XCTAssertTrue(fire.waitForExistence(timeout: 10))
        XCTAssertFalse(fire.frame.intersects(any("game.river").frame), "FIRE is never over the river")
        let autofire = app.switches["game.autofire"]
        reveal(autofire)
        let before = autofire.value as? String
        flip(autofire)
        let changed = before == "1" ? "0" : "1"
        waitFor(autofire, "value == %@", changed)

        relaunch()
        let play2 = button("now.play.human")
        reveal(play2)
        play2.tap()
        let autofire2 = app.switches["game.autofire"]
        reveal(autofire2)
        XCTAssertEqual(autofire2.value as? String, changed, "auto-fire survived a relaunch")
        flip(autofire2)
        waitFor(autofire2, "value == %@", before ?? "0")
        let close = button("game.close")
        reveal(close)
        close.tap()
    }
}
