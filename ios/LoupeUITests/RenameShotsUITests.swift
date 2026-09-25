import XCTest

/// Screenshots of every screen that names the on-device model (the 2026-09-25 rename: "the Loupe
/// Decision Model", short form "the decision model"), to check the longer name for truncation on a
/// large and a small iPhone. Runs only when `TEST_RUNNER_LOUPE_SHOTS=<dir>` is set for xcodebuild;
/// each shot is named `rename-<screen>-<device>.png`. No model files and no network are needed:
/// `-LoupeModelState` stands in for the model, and Download is never tapped.
final class RenameShotsUITests: XCTestCase {
    private var dir: String? { ProcessInfo.processInfo.environment["LOUPE_SHOTS"] }

    override func setUp() { continueAfterFailure = true }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    private func save(_ name: String) {
        guard let dir else { return }
        let device = UIDevice.current.name.replacingOccurrences(of: " ", with: "-")
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "\(dir)/rename-\(name)-\(device).png", contents: png)
    }

    private func launch(_ args: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = args + ["-LoupeEphemeralKeychain", "-LoupeFixtures"]
        app.launch()
        return app
    }

    func testShots() throws {
        try XCTSkipIf(dir == nil, "set TEST_RUNNER_LOUPE_SHOTS to take the screenshots")

        // Onboarding's first step without the model: Get the Loupe Decision Model, with Download.
        var app = launch(["-LoupeModelState", "missing", "-LoupeTab", "me", "-LoupeLanguage", "en"])
        XCTAssertTrue(any(app, "getLaya.screen").waitForExistence(timeout: 10))
        save("get-model")
        app.swipeUp()
        save("get-model-lower")
        app.terminate()

        // A build with no model host: it says it cannot download.
        app = launch(["-LoupeModelState", "missing", "-LoupeNoModelHost", "-LoupeTab", "me", "-LoupeLanguage", "en"])
        XCTAssertTrue(any(app, "getLaya.screen").waitForExistence(timeout: 10))
        save("get-model-nohost")
        app.buttons["getLaya.later"].tap()
        let skip = app.buttons["onboarding.skip"]
        if skip.waitForExistence(timeout: 3) { skip.tap() }

        // Me: the Decision model row, then the locked Sorting card further down.
        XCTAssertTrue(app.buttons["me.model"].waitForExistence(timeout: 10))
        save("me")
        let locked = any(app, "needsLaya.sort")
        for _ in 0..<6 where !(locked.exists && locked.isHittable) { app.swipeUp() }
        XCTAssertTrue(locked.exists)
        save("needs-model")
        app.terminate()

        // Me → Decision model (ready), and Model settings.
        app = launch(["-LoupeModelState", "ready", "-LoupeSkipOnboarding", "-LoupeTab", "me", "-LoupeLanguage", "en"])
        let row = app.buttons["me.model"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        sleep(1)
        save("model-page")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let settings = app.buttons["me.modelSettings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(app.navigationBars["Model settings"].waitForExistence(timeout: 10))
        save("model-settings")
        let game = app.switches["settings.features.game.use_laya"]
        for _ in 0..<25 where !(game.exists && game.isHittable) { app.swipeUp() }
        save("model-settings-features")
        app.terminate()

        // Model settings in Arabic.
        app = launch(["-LoupeModelState", "ready", "-LoupeSkipOnboarding", "-LoupeTab", "me", "-LoupeLanguage", "ar"])
        let settingsAr = app.buttons["me.modelSettings"]
        XCTAssertTrue(settingsAr.waitForExistence(timeout: 10))
        settingsAr.tap()
        sleep(1)
        save("model-settings-ar")
        app.terminate()

        // The Watch card on Now.
        app = launch(["-LoupeModelState", "ready", "-LoupeSkipOnboarding", "-LoupeTab", "now", "-LoupeLanguage", "en"])
        let watch = app.buttons["now.play.watch"]
        XCTAssertTrue(watch.waitForExistence(timeout: 10))
        for _ in 0..<6 where !watch.isHittable { app.swipeUp() }
        save("watch-card")
        app.terminate()
    }
}
