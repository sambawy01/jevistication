import XCTest

/// Scenario 12: Me → Delete all my Loupe data, keeping the decision model. Before: a finished onboarding, a pack of
/// judgments, link checks (recent and Spotted) and Contacts turned off. After: onboarding starts again (at the
/// permissions step: the model is still installed, so no model step), every list is empty and every switch is back to
/// its default, and model features are still unlocked. Relaunch → still empty, and onboarding does not come back.
/// `-LoupeModelState installed`: readiness takes its real path over a stand-in installed model.
final class DeleteDataScenarios: ScenarioCase {
    private func walkOnboarding() {
        XCTAssertTrue(any("permissions.screen").waitForExistence(timeout: 20), "onboarding starts at the permissions step")
        XCTAssertFalse(any("getLaya.screen").exists, "the model was kept: no model step")
        button("permissions.skip").tap()
        XCTAssertTrue(button("protect.step.continue").waitForExistence(timeout: 10))
        button("protect.step.continue").tap()
        // The one-time game intro follows the steps, after an erase too (it used to be skipped then, and came up at
        // the next launch instead).
        let intro = button("onboarding.skip")
        XCTAssertTrue(intro.waitForExistence(timeout: 10), "the game intro follows the onboarding steps")
        intro.tap()
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
    }

    private func contactsSwitch() -> XCUIElement {
        openReads()
        let sw = app.switches["sources.phone.contacts.toggle"]
        reveal(sw)
        XCTAssertTrue(sw.waitForExistence(timeout: 10))
        return sw
    }

    private func assertEmpty(_ when: String) {
        root("Ask")
        let mine = app.segmentedControls["judgments.section"].buttons["My judgments"]
        if mine.waitForExistence(timeout: 5) { mine.tap() }
        XCTAssertTrue(button("judgments.openLibrary").waitForExistence(timeout: 15), "\(when): My judgments is empty")
        root("Home")
        let spotted = any("guard.quick.spotted")
        reveal(spotted)
        spotted.tap()
        XCTAssertTrue(any("protect.spotted.empty").waitForExistence(timeout: 10), "\(when): Spotted is empty")
        back()
        let check = button("guard.quick.checkLink")
        reveal(check)
        check.tap()
        XCTAssertTrue(any("protect.link.input").waitForExistence(timeout: 5))
        XCTAssertFalse(any("protect.recent.row").waitForExistence(timeout: 2), "\(when): no recent checks")
        back()
        XCTAssertEqual(contactsSwitch().value as? String, "1", "\(when): Contacts is back to its default (on)")
        root("Me")
        // The first check after the new onboarding may still be going: Run now shows once it has finished.
        // Bring the Checks section on screen first; Run now shows once the first check is over.
        reveal(app.switches["me.sort.toggle"])
        let run = button("me.sort.run")
        XCTAssertTrue(run.waitForExistence(timeout: 120), "\(when): Run now")
        reveal(run)
        XCTAssertTrue(run.exists, "\(when): the decision model is still installed (Run now is unlocked)")
        XCTAssertFalse(any("needsLaya.sort").exists)
    }

    func testDeleteEverythingButTheModelThenRelaunch() {
        start("erase", ["-LoupeModelState", "installed", "-LoupeResetOnboarding", "-LoupePermissions", "granted", "-LoupeTab", "now"], run: false)
        walkOnboarding()

        // Data to delete: a pack of judgments, a dangerous link check (recent + Spotted), Contacts off.
        root("Ask")
        button("judgments.packs").tap()
        button("packs.example").tap()
        let add = button("packs.add")
        reveal(add)
        add.tap()
        XCTAssertTrue(button("judgments.mine.j-complaint-triage-team").waitForExistence(timeout: 10))
        root("Home")
        let check = button("guard.quick.checkLink")
        reveal(check)
        check.tap()
        let field = any("protect.link.input")
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("https://xn--pypal-4ve.com/login")
        button("protect.link.check").tap()
        XCTAssertTrue(any("protect.verdict").waitForExistence(timeout: 10))
        back()
        let contacts = contactsSwitch()
        flip(contacts)
        waitFor(contacts, "value == %@", "0")

        // Me → Delete all my Loupe data: a dialog, then DELETE typed; the model kept.
        root("Me")
        let erase = button("me.erase")
        reveal(erase)
        audit("12-me-erase", ["me.erase"])
        erase.tap()
        let proceed = app.buttons["Continue"].firstMatch
        XCTAssertTrue(proceed.waitForExistence(timeout: 5), "first step: a dialog")
        proceed.tap()
        XCTAssertTrue(app.navigationBars["Delete my data"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.switches["erase.keepModel"].value as? String, "1", "Keep the decision model is on by default")
        let confirmField = app.textFields["erase.confirmField"]
        reveal(confirmField)
        let confirm = button("erase.confirm")
        XCTAssertFalse(confirm.isEnabled, "nothing happens until DELETE is typed")
        confirmField.tap()
        confirmField.typeText("DELETE")
        hideKeyboard()
        reveal(confirm)
        audit("12-erase-sheet", ["erase.confirm"])
        confirm.tap()

        // Onboarding again, then everything empty and the model still here.
        walkOnboarding()
        XCTAssertTrue(tabs.buttons["Home"].isSelected, "after the erase Loupe starts again on Home")
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 20), "Home's first screen, not a screen left pushed")
        assertEmpty("after the erase")
        shot("12-after-erase")

        // Relaunch: still empty, and the onboarding just walked does not come back.
        relaunch()
        XCTAssertTrue(tabs.waitForExistence(timeout: 20), "straight to the tabs")
        XCTAssertFalse(any("permissions.screen").exists)
        XCTAssertFalse(any("getLaya.screen").exists, "the model survived the erase and the relaunch")
        XCTAssertFalse(button("onboarding.skip").waitForExistence(timeout: 3), "the intro does not come back after a relaunch")
        assertEmpty("after a relaunch")
    }
}
