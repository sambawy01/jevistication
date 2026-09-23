import XCTest

/// Me → Appearance → Mascot (epic #7 child 17): switching to the drone persists across relaunch.
final class MascotKindUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testSwitchToDroneInMePersistsAcrossRelaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeEphemeralKeychain", "-LoupeTab", "me", "-LoupeResetMascot"]
        app.launch()
        let picker = app.segmentedControls["me.mascot"]
        for _ in 0..<6 where !picker.exists { app.swipeUp() }
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        XCTAssertTrue(picker.buttons["Robot"].isSelected)                   // the default
        picker.buttons["Drone"].tap()
        XCTAssertTrue(picker.buttons["Drone"].isSelected)
        app.terminate()

        app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeEphemeralKeychain", "-LoupeTab", "me"]
        app.launch()
        let again = app.segmentedControls["me.mascot"]
        for _ in 0..<6 where !again.exists { app.swipeUp() }
        XCTAssertTrue(again.waitForExistence(timeout: 5))
        XCTAssertTrue(again.buttons["Drone"].isSelected)
        again.buttons["Robot"].tap()                                          // leave the default behind
    }
}
