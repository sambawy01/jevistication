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
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), "the places")
    }

    /// Every place: the actions it needs are there, hittable and 44 pt, and nothing says "Laya".
    func testEveryPlaceHasItsActions() {
        launchSmoke("home")
        XCTAssertEqual(tabs.buttons.count, 3, "three places")
        for name in ["Home", "Ask", "Me"] { XCTAssertTrue(tabs.buttons[name].exists, "no \(name) place") }
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 30))
        audit("smoke-home", ["guard.quick.checkLink", "guard.quick.checkCopied", "home.money", "home.documents", "home.protected"])

        openProtection()
        audit("smoke-protection", ["guard.quick.checkLink", "guard.runNow"])

        root("Ask")
        let mine = app.segmentedControls["judgments.section"].buttons["My judgments"]
        if mine.waitForExistence(timeout: 5) { mine.tap() }
        audit("smoke-ask", ["judgments.write", "judgments.packs"])
        let library = app.segmentedControls["judgments.section"].buttons["Library"]
        library.tap()
        XCTAssertTrue(app.textFields["library.search"].waitForExistence(timeout: 10), "the Library opens")
        audit("smoke-library")
        app.segmentedControls["judgments.section"].buttons["Web questions"].tap()
        XCTAssertTrue(any("web.template.currency").waitForExistence(timeout: 10), "Web questions open")
        audit("smoke-web", ["web.template.currency", "web.settings"])

        root("Me")
        audit("smoke-me", ["me.reads", "me.mail", "me.assistant", "me.model", "me.review", "me.export", "me.erase",
                           "me.play.watch", "me.play.human", "me.advanced", "me.licences", "me.privacy", "me.terms"])
        XCTAssertTrue(any("me.version").exists, "the version is shown")

        openReads()
        for id in ["photos", "files", "calendar", "contacts"] {
            let sw = app.switches["sources.phone.\(id).toggle"]
            reveal(sw)
            XCTAssertTrue(sw.exists, "What Loupe reads has \(id)")
        }
        audit("smoke-reads", ["sources.mail"])

        openMail()
        audit("smoke-mail", ["sources.phone.mail.setup"])

        openAdvanced()
        audit("smoke-advanced", ["me.modelSettings", "me.onlineChecks"])
    }

    /// Protection → Check a link with an ordinary website: no warning signs, and the check is in Recent checks after
    /// a relaunch.
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
        launchSmoke("me")
        let play = button("me.play.human")
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
        let play2 = button("me.play.human")
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
