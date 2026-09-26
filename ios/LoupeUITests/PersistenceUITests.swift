import XCTest

/// The relaunch bug (owner report 2026-09-26: "when the app is closed and reopened, all the changes I made are
/// restored to the onboarding setup"). Onboarding is walked once on a fresh install (the one permissions step,
/// then protection, then the intro), settings are changed, the app is terminated and launched again: onboarding
/// must not come back and every change must still be there.
///
/// Not under -LoupeFixtures: the sources and settings live in the app's real Application Support and
/// UserDefaults, as on the phone. `-LoupeResetOnboarding` on the first launch only stands for a fresh install;
/// `-LoupePermissions granted` answers the permissions step without iOS prompts.
final class PersistenceUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Set TEST_RUNNER_LOUPE_SHOTS to a folder to keep the screenshots (persist-*.png).
    private var dir: String? { ProcessInfo.processInfo.environment["LOUPE_SHOTS"] }

    private func shot(_ name: String) {
        let s = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: s)
        a.name = "persist-\(name)"
        a.lifetime = .keepAlways
        add(a)
        guard let dir else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "\(dir)/persist-\(name).png", contents: s.pngRepresentation)
    }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        // "installed", not "ready": readiness takes its real path over a stand-in installed model, the path that
        // misread the model as missing on every relaunch (the bug this test guards).
        app.launchArguments = extra + ["-LoupeModelState", "installed", "-LoupePermissions", "granted", "-LoupeEphemeralKeychain",
                                       "-LoupeTab", "sources"]
        app.launch()
        return app
    }

    private func phoneSwitch(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        let toggle = app.switches["sources.phone.\(id).toggle"]
        for _ in 0..<6 where !(toggle.exists && toggle.isHittable) { app.swipeUp() }
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), "no switch for \(id)")
        return toggle
    }

    func testSettingsSurviveARelaunchAndOnboardingDoesNotComeBack() {
        // 1. A fresh install with the model already here: the permissions step comes first.
        var app = launch(["-LoupeResetOnboarding"])
        XCTAssertTrue(any(app, "permissions.screen").waitForExistence(timeout: 15), "the one permissions step")
        XCTAssertFalse(app.tabBars.firstMatch.exists)
        XCTAssertTrue(any(app, "permissions.explainer").label.contains("Nothing leaves the phone"))
        for kind in ["photos", "calendar", "contacts", "notifications"] {
            XCTAssertTrue(any(app, "permissions.row.\(kind)").exists, "no row for \(kind)")
            XCTAssertEqual(any(app, "permissions.row.\(kind).status").value as? String, "Not asked yet")
        }
        XCTAssertTrue(app.buttons["permissions.skip"].exists, "the step is skippable")
        shot("permissions-step")

        // Allow access walks the prompts in sequence and marks what was granted.
        app.buttons["permissions.allow"].tap()
        let next = app.buttons["permissions.continue"]
        XCTAssertTrue(next.waitForExistence(timeout: 10))
        for kind in ["photos", "calendar", "contacts", "notifications"] {
            XCTAssertEqual(any(app, "permissions.row.\(kind).status").value as? String, "Allowed", kind)
        }
        shot("permissions-granted")
        next.tap()

        // 2. Protection (Safari, the clipboard slot), then the intro over the tabs.
        XCTAssertTrue(any(app, "protect.step.screen").waitForExistence(timeout: 10))
        XCTAssertTrue(any(app, "protect.safari").exists, "the Safari protection card")
        shot("protect-step")
        app.buttons["protect.step.continue"].tap()
        let skipIntro = app.buttons["onboarding.skip"]
        XCTAssertTrue(skipIntro.waitForExistence(timeout: 10), "the one-time game intro")
        skipIntro.tap()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))

        // 3. The on-device sources are on by default; turn Contacts off. Mail waits for a sign-in.
        XCTAssertEqual(phoneSwitch(app, "calendar").value as? String, "1", "on by default")
        let contacts = phoneSwitch(app, "contacts")
        XCTAssertEqual(contacts.value as? String, "1", "on by default")
        contacts.tap()
        XCTAssertEqual(contacts.value as? String, "0")
        XCTAssertEqual(phoneSwitch(app, "mail").value as? String, "0", "Mail is offered, not forced")

        // Me → Sorting: on by default; turn it off.
        app.tabBars.buttons["Me"].tap()
        let sort = app.switches["me.sort.toggle"]
        for _ in 0..<8 where !(sort.exists && sort.isHittable) { app.swipeUp() }
        XCTAssertTrue(sort.waitForExistence(timeout: 5))
        XCTAssertEqual(sort.value as? String, "1", "background sorting is on by default")
        sort.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()   // the switch, not the label
        XCTAssertEqual(sort.value as? String, "0")

        // 4. Close the app and open it again (no reset this time).
        app.terminate()
        app = launch([])

        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15), "straight to the tabs")
        for id in ["getLaya.screen", "permissions.screen", "protect.step.screen"] {
            XCTAssertFalse(any(app, id).exists, "\(id) came back after a relaunch")
        }
        XCTAssertFalse(app.buttons["onboarding.skip"].waitForExistence(timeout: 2), "the intro came back after a relaunch")
        XCTAssertEqual(phoneSwitch(app, "contacts").value as? String, "0", "Contacts stays off")
        XCTAssertEqual(phoneSwitch(app, "calendar").value as? String, "1", "Calendar stays on")
        shot("relaunch-sources")
        app.tabBars.buttons["Me"].tap()
        let sortAgain = app.switches["me.sort.toggle"]
        for _ in 0..<8 where !(sortAgain.exists && sortAgain.isHittable) { app.swipeUp() }
        XCTAssertEqual(sortAgain.value as? String, "0", "Sort while charging stays off")
        shot("relaunch-me")
        // Leave the simulator as the other tests expect it (the switch lives in the shared UserDefaults).
        sortAgain.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertEqual(sortAgain.value as? String, "1")
    }

    /// Skipping the permissions step counts as completing it: it never comes back, and nothing was asked.
    func testSkippedPermissionsStepNeverReturns() {
        var app = launch(["-LoupeResetOnboarding"])
        XCTAssertTrue(any(app, "permissions.screen").waitForExistence(timeout: 15))
        app.buttons["permissions.skip"].tap()
        XCTAssertTrue(any(app, "protect.step.screen").waitForExistence(timeout: 10))
        app.terminate()

        app = launch([])
        XCTAssertTrue(any(app, "protect.step.screen").waitForExistence(timeout: 15), "resumes at the step it stopped on")
        XCTAssertFalse(any(app, "permissions.screen").exists, "the skipped permissions step does not return")
        app.buttons["protect.step.continue"].tap()
        let skipIntro = app.buttons["onboarding.skip"]
        if skipIntro.waitForExistence(timeout: 10) { skipIntro.tap() }
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        app.terminate()

        app = launch([])
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15))
        XCTAssertFalse(any(app, "permissions.screen").exists)
        XCTAssertFalse(any(app, "protect.step.screen").exists)
    }
}
