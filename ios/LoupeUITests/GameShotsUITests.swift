import XCTest

/// Screenshots of the game for review (the Watch card, the FIRE bar, the in-run speed panel, the
/// results card). Runs only when `TEST_RUNNER_LOUPE_SHOTS=<dir>` is set for xcodebuild; each shot is
/// named after the device. The watch shots need Laya side-loaded (`ios-native/sideload-models.sh`).
final class GameShotsUITests: XCTestCase {
    private var dir: String? { ProcessInfo.processInfo.environment["LOUPE_SHOTS"] }

    override func setUp() {
        continueAfterFailure = true
    }

    private func save(_ name: String) {
        guard let dir else { return }
        let device = UIDevice.current.name.replacingOccurrences(of: " ", with: "-")
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "\(dir)/\(name)-\(device).png", contents: png)
    }

    func testShots() throws {
        try XCTSkipIf(dir == nil, "set TEST_RUNNER_LOUPE_SHOTS to take the screenshots")

        // The Watch card on Now.
        var app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "now"]
        app.launch()
        let watch = app.buttons["now.play.watch"]
        XCTAssertTrue(watch.waitForExistence(timeout: 10))
        for _ in 0..<6 where !watch.isHittable { app.swipeUp() }
        save("watch-card")
        app.terminate()

        // You fly: the FIRE bar under the river.
        app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeGame", "human"]
        app.launch()
        let fire = app.descendants(matching: .any)["game.fire"]
        XCTAssertTrue(fire.waitForExistence(timeout: 10))
        let river = app.descendants(matching: .any)["game.river"]
        XCTAssertFalse(fire.frame.intersects(river.frame))
        sleep(2)
        save("fire-bar")
        app.terminate()

        // Watch: the speed panel mid-run, then the results card when the run ends.
        app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeGame", "watch", "-LoupeGameLevelRows", "60"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["game.speed"].waitForExistence(timeout: 20))
        sleep(12)
        save("speed-panel")
        let results = app.descendants(matching: .any)["game.results"]
        if results.waitForExistence(timeout: 400) {
            sleep(1)
            save("results-card")
        }
    }
}
