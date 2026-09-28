import XCTest

/// Scenario 1: a first run. Get the decision model (the model is missing) → Later; the permissions step → Skip for
/// now; Protection → Continue (it has no separate "Not now": both of its cards are optional and Continue leaves
/// them off); the game intro → Not now. Relaunch: none of the one-time steps come back. Get the decision model does
/// come back while the model is missing: by design it opens every launch until the model is here (owner rule
/// 2026-09-25); Later again goes straight to the tabs.
final class OnboardingScenarios: ScenarioCase {
    func testFirstRunSkippingEverythingThenRelaunch() {
        start("onboarding", ["-LoupeModelState", "missing", "-LoupeResetOnboarding", "-LoupeResetModelConsent", "-LoupeTab", "now"], run: false)

        // 1. Get the decision model: why, the size, Download (through the consent) and Later.
        XCTAssertTrue(any("getLaya.screen").waitForExistence(timeout: 20), "a first run opens on Get the decision model")
        XCTAssertFalse(tabs.exists, "before the tabs")
        XCTAssertTrue(any("getLaya.explainer").label.contains("MB"), any("getLaya.explainer").label)
        audit("01-getmodel", ["getLaya.download", "getLaya.later"])
        button("getLaya.later").tap()

        // 2. The one permissions step: Allow access or Skip for now.
        XCTAssertTrue(any("permissions.screen").waitForExistence(timeout: 10))
        for kind in ["photos", "calendar", "contacts", "notifications"] {
            XCTAssertTrue(any("permissions.row.\(kind)").exists, "no \(kind) row")
        }
        audit("01-permissions", ["permissions.allow", "permissions.skip"])
        button("permissions.skip").tap()

        // 3. Protection: the Safari card and the clipboard card, both optional.
        XCTAssertTrue(any("protect.step.screen").waitForExistence(timeout: 10))
        XCTAssertTrue(any("protect.safari").exists, "the Safari protection card")
        audit("01-protect", ["protect.safari.turnOn", "protect.safari.steps", "protect.step.continue"])
        button("protect.step.continue").tap()

        // 4. The game intro, once, over the tabs.
        XCTAssertTrue(button("onboarding.skip").waitForExistence(timeout: 10), "the one-time game intro")
        audit("01-intro", ["onboarding.watch", "onboarding.skip"])
        button("onboarding.skip").tap()
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
        // Onboarding complete: the one first check has run (owner decision C, 2026-09-28), visibly, on Now: over the
        // hidden test fixture here (the app has no sample).
        let lastTitle = app.staticTexts["run.lastTitle"]
        XCTAssertTrue(lastTitle.waitForExistence(timeout: 120), "the first check ran and left its record on Now")
        XCTAssertTrue(lastTitle.label.hasPrefix("First check"), lastTitle.label)
        // The fixture's 48 items at least (Files and Send to Loupe may hold some from other tests on this simulator).
        let checked = app.staticTexts["run.last"].label
        XCTAssertTrue(checked.hasPrefix("Checked "), checked)
        XCTAssertGreaterThanOrEqual(number(checked), 48, checked)
        // Later locks the model features, and says how to get the model.
        tab("Me")
        let locked = any("needsLaya.sort")
        reveal(locked)
        XCTAssertTrue(locked.exists, "Sorting is locked without the model")
        audit("01-me-locked", ["needsLaya.sort.get"])

        // 5. Relaunch: the skipped steps stay skipped.
        relaunch()
        XCTAssertTrue(any("getLaya.screen").waitForExistence(timeout: 20), "Get the decision model opens every launch while the model is missing")
        button("getLaya.later").tap()
        XCTAssertTrue(tabs.waitForExistence(timeout: 10), "Later goes straight to the tabs")
        for id in ["permissions.screen", "protect.step.screen"] {
            XCTAssertFalse(any(id).exists, "\(id) came back after a relaunch")
        }
        XCTAssertFalse(button("onboarding.skip").waitForExistence(timeout: 3), "the game intro came back after a relaunch")
        // Nothing runs at launch, and the first check does not run twice: Now shows its saved record.
        XCTAssertFalse(button("run.cancel").waitForExistence(timeout: 3), "no run at launch")
        tab("Now")
        XCTAssertTrue(app.staticTexts["run.lastTitle"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["run.lastTitle"].label.hasPrefix("First check"), app.staticTexts["run.lastTitle"].label)
        shot("01-relaunch-tabs")

        // 6. And once more (a second relaunch is where a half-recorded step would show).
        relaunch()
        XCTAssertTrue(any("getLaya.screen").waitForExistence(timeout: 20))
        button("getLaya.later").tap()
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
        XCTAssertFalse(any("permissions.screen").exists)
        XCTAssertFalse(any("protect.step.screen").exists)
        XCTAssertFalse(button("onboarding.skip").waitForExistence(timeout: 2))
    }

    /// With the model installed, a first run has no model step, and after the one-time steps a relaunch goes straight
    /// to the tabs (the owner's relaunch bug of 2026-09-26, through the real readiness path).
    func testFirstRunWithTheModelThenRelaunchGoesStraightToTheTabs() {
        start("onboarding-model", ["-LoupeModelState", "installed", "-LoupeResetOnboarding", "-LoupePermissions", "granted", "-LoupeTab", "now"],
              run: false)
        XCTAssertTrue(any("permissions.screen").waitForExistence(timeout: 20), "no model step: the permissions step first")
        XCTAssertFalse(any("getLaya.screen").exists)
        button("permissions.allow").tap()
        XCTAssertTrue(button("permissions.continue").waitForExistence(timeout: 10))
        audit("01b-permissions-granted", ["permissions.continue"])
        button("permissions.continue").tap()
        XCTAssertTrue(any("protect.step.screen").waitForExistence(timeout: 10))
        button("protect.step.continue").tap()
        XCTAssertTrue(button("onboarding.skip").waitForExistence(timeout: 10))
        button("onboarding.skip").tap()
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))

        relaunch()
        XCTAssertTrue(tabs.waitForExistence(timeout: 20), "straight to the tabs")
        for id in ["getLaya.screen", "permissions.screen", "protect.step.screen"] {
            XCTAssertFalse(any(id).exists, "\(id) came back")
        }
        XCTAssertFalse(button("onboarding.skip").waitForExistence(timeout: 3))
    }
}
