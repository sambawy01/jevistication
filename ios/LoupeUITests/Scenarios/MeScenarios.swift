import XCTest

/// Scenario 9: Me. Model settings (calibration is off by default; on survives a relaunch; then off again; a
/// per-feature switch and its Reset to default), Sort while charging (on by default) and Run now, the online checks
/// (off by default; one turned on survives a relaunch, then off again), the writing assistant's settings (opened, not
/// saved: no network), Licences, the version, and the Privacy policy and Terms links.
/// Online checks and Sort while charging live in the app's own settings (not the fixture home), so this test puts
/// them back as it found them.
final class MeScenarios: ScenarioCase {
    private func openModelSettings() {
        openAdvanced()
        let open = button("me.modelSettings")
        reveal(open)
        open.tap()
        XCTAssertTrue(app.navigationBars["Model settings"].waitForExistence(timeout: 10))
    }

    /// Me pushes Model settings (Done is only on the sheet a banner opens).
    private func closeSettings() {
        if button("settings.done").exists { button("settings.done").tap() } else { back() }
    }

    private func setting(_ key: String) -> XCUIElement {
        let sw = app.switches["settings.\(key)"]
        reveal(sw, max: 40)
        XCTAssertTrue(sw.exists, "no switch for \(key)")
        return sw
    }

    private func openOnlineChecks() {
        openAdvanced()
        let open = button("me.onlineChecks")
        reveal(open)
        open.tap()
        XCTAssertTrue(app.switches["online.domainFacts"].waitForExistence(timeout: 10))
    }

    func testSettingsTogglesSortingOnlineChecksAndAbout() {
        start("me", ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeModelState", "ready", "-LoupeSortDemo"])
        XCTAssertTrue(tabs.waitForExistence(timeout: 20))
        audit("09-me-top", ["me.reads", "me.mail", "me.assistant", "me.model", "me.export", "me.erase", "me.advanced"])

        // 1. Model settings: calibration off by default → on; the privacy check's decision-model switch off.
        openModelSettings()
        let calibration = setting("global.use_calibration")
        XCTAssertEqual(calibration.value as? String, "0", "calibration is off by default")
        flip(calibration)
        waitFor(calibration, "value == %@", "1")
        let scan = setting("features.scan.use_laya")
        XCTAssertEqual(scan.value as? String, "1")
        flip(scan)
        waitFor(scan, "value == %@", "0")
        XCTAssertTrue(button("settings.features.scan.use_laya.reset").exists, "a changed row offers Reset to default")
        audit("09-model-settings", ["settings.features.scan.use_laya.reset"])
        closeSettings()
        root("Me")

        // 2. Sort while charging: on by default here; Run now sorts (the demo's stand-in scorer).
        let sort = app.switches["me.sort.toggle"]
        reveal(sort)
        XCTAssertEqual(sort.value as? String, "1", "Sort while charging is on by default")
        let run = button("me.sort.run")
        reveal(run)
        audit("09-sorting", ["me.sort.run"])
        let summary = app.staticTexts["me.sort.summary"]
        for _ in 0..<5 {
            run.tap()
            if app.staticTexts["me.sort.progress"].waitForExistence(timeout: 3) || summary.exists { break }
            sleep(2)
        }
        XCTAssertTrue(summary.waitForExistence(timeout: 90), "Run now finishes with its counts")
        XCTAssertFalse(summary.label.hasPrefix("Last run: 0 sorted"), summary.label)

        // 3. Online checks: every one off by default; turn on the domain facts.
        openOnlineChecks()
        for id in ["online.domainFacts", "online.feeds", "online.dnsFacts", "online.safeBrowsing"] {
            let sw = app.switches[id]
            reveal(sw)
            XCTAssertEqual(sw.value as? String, "0", "\(id) is off by default")
        }
        let facts = app.switches["online.domainFacts"]
        reveal(facts)
        flip(facts)
        waitFor(facts, "value == %@", "1")
        audit("09-online-checks")
        back()

        // 4. The Personal Assistant row (today's writing assistant): off by default; nothing saved, nothing sent.
        root("Me")
        let assistant = button("me.assistant")
        reveal(assistant)
        assistant.tap()
        let enabled = app.switches["assist.enabled"]
        XCTAssertTrue(enabled.waitForExistence(timeout: 5))
        XCTAssertEqual(enabled.value as? String, "0", "the writing assistant is off by default")
        audit("09-assistant")
        back()

        // 5. About: Licences, the version, Privacy policy and Terms.
        let licences = button("me.licences")
        reveal(licences)
        XCTAssertTrue(any("me.version").exists, "the version is shown")
        XCTAssertFalse(any("me.version").label.isEmpty)
        audit("09-about", ["me.licences", "me.privacy", "me.terms"])
        licences.tap()
        XCTAssertTrue(any("licences.list").waitForExistence(timeout: 5))
        audit("09-licences")
        back()

        // 6. Relaunch: calibration on, the privacy check's switch off, the domain facts on, sorting on.
        relaunch()
        openModelSettings()
        XCTAssertEqual(setting("global.use_calibration").value as? String, "1", "calibration stays on after a relaunch")
        XCTAssertEqual(setting("features.scan.use_laya").value as? String, "0", "the feature switch stays off")
        // Put them back: calibration off, the feature switch by its Reset.
        let cal = setting("global.use_calibration")
        flip(cal)
        waitFor(cal, "value == %@", "0")
        let reset = button("settings.features.scan.use_laya.reset")
        reveal(reset, max: 40)
        reset.tap()
        waitFor(setting("features.scan.use_laya"), "value == %@", "1")
        closeSettings()
        root("Me")
        let sortAgain = app.switches["me.sort.toggle"]
        reveal(sortAgain)
        XCTAssertEqual(sortAgain.value as? String, "1", "sorting stays on")
        openOnlineChecks()
        let factsAgain = app.switches["online.domainFacts"]
        reveal(factsAgain)
        XCTAssertEqual(factsAgain.value as? String, "1", "the online check stays on after a relaunch")
        flip(factsAgain)
        waitFor(factsAgain, "value == %@", "0")
        back()

        // 7. And off stays off.
        relaunch()
        openModelSettings()
        XCTAssertEqual(setting("global.use_calibration").value as? String, "0", "calibration off stays off")
        closeSettings()
        openOnlineChecks()
        XCTAssertEqual(app.switches["online.domainFacts"].value as? String, "0")
    }
}
