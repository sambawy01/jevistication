import XCTest

/// Spec §8: Arabic right-to-left and Dynamic Type on the three places' first screens. Every action stays hittable and
/// at least 44 pt, and nothing says "Laya" (`audit`), with the layout mirrored and with Accessibility XL text.
final class ShellAccessibilityUITests: ScenarioCase {
    private let homeIds = ["run.runNow", "guard.quick.checkLink", "guard.quick.checkCopied", "home.money", "home.documents", "home.protected"]
    private let meIds = ["me.reads", "me.mail", "me.assistant", "me.model", "me.export", "me.erase", "me.advanced", "me.licences"]

    private func walk(_ tag: String) {
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 60))
        audit("\(tag)-home", homeIds)
        root("Ask")
        XCTAssertTrue(app.segmentedControls["judgments.section"].waitForExistence(timeout: 10))
        audit("\(tag)-ask", ["judgments.write", "judgments.packs"])
        root("Me")
        audit("\(tag)-me", meIds)
    }

    func testTheThreePlacesRightToLeft() {
        launch(["-LoupeFixtures", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeTab", "home",
                "-AppleTextDirection", "YES", "-NSForceRightToLeftWritingDirection", "YES"])
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        XCTAssertGreaterThan(tabs.buttons["Home"].frame.minX, tabs.buttons["Me"].frame.minX, "the tab bar is mirrored")
        walk("rtl")
    }

    func testTheThreePlacesAtAccessibilityXL() {
        launch(["-LoupeFixtures", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeTab", "home",
                "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"])
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        walk("axxl")
    }
}
