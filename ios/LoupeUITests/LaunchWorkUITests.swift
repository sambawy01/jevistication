import XCTest

/// Owner decision A (2026-09-28): opening the app only LOADS and shows the latest saved results. With sources on (the
/// hidden test fixture, Files on by default) a launch starts no scan and no run; Run now does; a relaunch shows what
/// that run saved (the privacy check, mail triage and the watchers) without running anything again.
final class LaunchWorkUITests: ScenarioCase {
    private var launchWork: XCUIElement { app.staticTexts["debug.launchWork"] }

    func testALaunchRunsNothingAndARelaunchShowsTheSavedResults() {
        start("launch-work", ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing"], run: false)

        // 1. A fresh launch with sources on: nothing read, nothing run, and Now says so.
        XCTAssertTrue(any("findings.notChecked").waitForExistence(timeout: 30), "Not checked yet")
        sleep(3)
        XCTAssertEqual(launchWork.label, "runs=0 scans=0", "no scan and no run at launch")
        XCTAssertFalse(button("run.cancel").exists)
        audit("launch-01-fresh", ["run.runNow", "findings.notChecked.runNow"])

        // 2. Run now: the panel shows the run, then the results.
        button("run.runNow").tap()
        XCTAssertTrue(any("now.topFinding").waitForExistence(timeout: 120), "the watchers ran over the fixture")
        let lastTitle = app.staticTexts["run.lastTitle"]
        XCTAssertTrue(lastTitle.waitForExistence(timeout: 120))
        XCTAssertTrue(lastTitle.label.hasPrefix("Checked on request"), lastTitle.label)
        let privacy = button("now.privacy")
        let mail = button("now.mail")
        XCTAssertTrue(privacy.label.contains("findings"), privacy.label)
        XCTAssertTrue(mail.label.contains("possible phishing"), mail.label)
        let privacyBefore = privacy.label, mailBefore = mail.label

        // 3. Relaunch: the saved results, loaded; nothing runs.
        relaunch()
        XCTAssertTrue(any("now.topFinding").waitForExistence(timeout: 30), "the findings are loaded from disk")
        sleep(3)
        XCTAssertEqual(launchWork.label, "runs=0 scans=0", "a relaunch runs nothing")
        XCTAssertFalse(button("run.cancel").exists)
        XCTAssertEqual(button("now.privacy").label, privacyBefore, "the privacy check's results, as saved")
        XCTAssertEqual(button("now.mail").label, mailBefore, "mail triage's results, as saved")
        XCTAssertTrue(app.staticTexts["run.lastTitle"].label.hasPrefix("Checked on request"))
        shot("launch-02-relaunch")
    }

    /// Owner decision D: a run is visible and can be cancelled; what it read stays, and nothing later in it runs.
    func testARunShowsItsProgressAndCancels() {
        start("launch-cancel", ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeSlowJobs"], run: false)
        XCTAssertTrue(button("run.runNow").waitForExistence(timeout: 30))
        button("run.runNow").tap()
        let stage = app.staticTexts["run.stage"]
        XCTAssertTrue(stage.waitForExistence(timeout: 30), "the live panel")
        XCTAssertTrue(app.staticTexts["run.counts"].waitForExistence(timeout: 30))
        // The privacy check paces its items under -LoupeSlowJobs: cancel it there.
        let privacyStage = NSPredicate(format: "label BEGINSWITH %@", "Privacy check")
        expectation(for: privacyStage, evaluatedWith: stage)
        waitForExpectations(timeout: 120)
        XCTAssertTrue(app.staticTexts["run.counts"].label.contains(" of "), app.staticTexts["run.counts"].label)
        audit("launch-03-running", ["run.cancel"])
        button("run.cancel").tap()
        let last = app.staticTexts["run.last"]
        XCTAssertTrue(last.waitForExistence(timeout: 30))
        XCTAssertEqual(last.label, "Cancelled. What it read is saved.")
        XCTAssertFalse(button("run.cancel").exists)
        XCTAssertTrue(any("findings.notChecked").exists, "the watchers never ran")
    }
}
