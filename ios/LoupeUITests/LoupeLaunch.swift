import XCTest

/// The app as the UI tests launch it (2026-09-28): the synthetic sample is not in the app any more, only in this test
/// bundle; its path goes to the app in `LOUPE_FIXTURE_SAMPLE`, which a DEBUG `-LoupeFixtures` launch reads as a hidden
/// fixture source (the simulator shares the Mac's file system). Nothing runs at launch: a test that needs results
/// launches with `-LoupeRunNow` (one full run, as Run now), and a relaunch without it shows what was saved.
extension XCUIApplication {
    private final class FixtureAnchor {}

    /// The sample folder inside this UI test bundle.
    static var fixtureSamplePath: String? {
        Bundle(for: FixtureAnchor.self).url(forResource: "sample", withExtension: nil)?.path
    }

    /// `XCUIApplication()` with the fixture sample's path set (used only when the launch has `-LoupeFixtures`).
    static func loupe() -> XCUIApplication {
        let app = XCUIApplication()
        if let path = fixtureSamplePath { app.launchEnvironment["LOUPE_FIXTURE_SAMPLE"] = path }
        return app
    }
}
