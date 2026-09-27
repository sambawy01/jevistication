import XCTest

/// Scenario 8: protection. A link copied in another app gets the chip (put on the clipboard by the test runner, as
/// ClipboardUITests does; `xcrun simctl pbcopy` does the same from the Mac) and Check says Dangerous; Check a link
/// with a look-alike (Dangerous) and a normal site (no warning signs); recent checks; the Guard badge until Spotted is
/// viewed; Spotted's list and detail; clear recent checks. Two relaunches: the recent checks and Spotted are kept, the
/// badge stays cleared once seen, the cleared recent checks stay cleared, and the same copy is not offered again.
final class ProtectionScenarios: ScenarioCase {
    private let lookalike = "https://xn--pypal-4ve.com/signin?session=secret-token"

    override func tearDown() {
        UIPasteboard.general.items = []
        super.tearDown()
    }

    private func check(_ text: String) {
        let clear = button("protect.link.clear")
        if clear.exists { clear.tap() }
        let field = any("protect.link.input")
        field.tap()
        field.typeText(text)
        button("protect.link.check").tap()
    }

    /// The unseen count that badges the Guard tab (`ProtectionStore.unseenCount`). The tab's badge is drawn but not
    /// exposed to accessibility, so this reads the same count from Guard's Spotted row ("2 new"); empty when none.
    private var guardBadge: String {
        let row = any("guard.quick.spotted")
        if !row.exists { tab("Guard") }
        reveal(row)
        let words = row.label.components(separatedBy: CharacterSet(charactersIn: ",·"))
        return words.first { $0.hasSuffix(" new") }?.trimmingCharacters(in: .whitespaces) ?? ""
    }

    private func openCheckLink() {
        let quick = button("guard.quick.checkLink")
        reveal(quick)
        quick.tap()
        XCTAssertTrue(any("protect.link.input").waitForExistence(timeout: 5))
    }

    func testChecksSpottedAndClearingSurviveRelaunches() {
        UIPasteboard.general.string = lookalike
        start("protection", ["-LoupeTab", "guard", "-LoupeSkipOnboarding", "-LoupeModelState", "missing",
                             "-LoupeClipboard", "-LoupeClipboardReset"])
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20))

        // 1. The chip for the link copied in another app, offered without reading it; Check reads it (iOS asks) and
        // says Dangerous.
        let chip = any("clip.chip")
        XCTAssertTrue(chip.waitForExistence(timeout: 15), "the chip for a copied link")
        audit("08-chip", ["clip.chip.check", "clip.chip.dismiss"])
        any("clip.chip.check").tap()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow Paste"].firstMatch
        if allow.waitForExistence(timeout: 4) { allow.tap() }
        let banner = any("clip.banner")
        XCTAssertTrue(banner.waitForExistence(timeout: 10))
        XCTAssertEqual(banner.value as? String, "dangerous")
        any("clip.banner.dismiss").tap()
        XCTAssertFalse(guardBadge.isEmpty, "Guard is badged while Spotted has unseen entries")

        // 2. Relaunch: the same copy is not offered again. (Before any typing: XCUITest's typing moves the app
        // process's own pasteboard change count, which a fresh process does not see, so after typing a relaunch
        // would look like a new copy. On a phone, typing does not touch the pasteboard.)
        relaunch()
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20))
        XCTAssertFalse(any("clip.chip").waitForExistence(timeout: 5), "each copy is offered once, across launches")
        XCTAssertFalse(guardBadge.isEmpty, "unseen Spotted entries still badge Guard")

        // 3. Check a link: a look-alike and a normal website.
        openCheckLink()
        check("https://xn--pypal-4ve.com/login")
        let verdict = any("protect.verdict")
        XCTAssertTrue(verdict.waitForExistence(timeout: 10))
        XCTAssertEqual(verdict.value as? String, "dangerous")
        audit("08-dangerous", ["protect.link.check"])
        check("www.bbc.co.uk/news")
        waitFor(any("protect.verdict"), "value == %@", "safe")
        let recent = app.descendants(matching: .any).matching(identifier: "protect.recent.row")
        reveal(recent.firstMatch)
        XCTAssertGreaterThanOrEqual(recent.count, 3, "the clipboard check and both link checks are in Recent checks")
        audit("08-recent", ["protect.recent.clear"])
        back()

        // Relaunch: the recent checks and the badge are kept.
        relaunch()
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20))
        if any("clip.chip").waitForExistence(timeout: 3) { any("clip.chip.dismiss").tap() }   // the typing artefact above
        XCTAssertFalse(guardBadge.isEmpty, "unseen Spotted entries still badge Guard")
        openCheckLink()
        let recentAgain = app.descendants(matching: .any).matching(identifier: "protect.recent.row")
        reveal(recentAgain.firstMatch)
        XCTAssertGreaterThanOrEqual(recentAgain.count, 3, "recent checks survived a relaunch")
        back()

        // 4. Spotted: the list and an entry's detail; viewing it clears the badge.
        let spotted = any("guard.quick.spotted")
        reveal(spotted)
        spotted.tap()
        let entry = any("protect.spotted.entry")
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        audit("08-spotted", ["protect.spotted.clear"])
        entry.tap()
        audit("08-spotted-detail")
        back()
        back()
        waitUntil(timeout: 5, "the badge clears once Spotted is viewed (\(guardBadge))") { self.guardBadge.isEmpty }

        // 5. Clear recent checks (asks first).
        openCheckLink()
        let clear = button("protect.recent.clear")
        reveal(clear)
        clear.tap()
        let sure = app.buttons["Clear recent checks"].firstMatch
        XCTAssertTrue(sure.waitForExistence(timeout: 5), "Clear asks first")
        sure.tap()
        XCTAssertFalse(any("protect.recent.row").waitForExistence(timeout: 2))
        back()

        // 6. Relaunch: Spotted kept, the badge stays cleared, recent checks stay cleared.
        relaunch()
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20))
        if any("clip.chip").waitForExistence(timeout: 3) { any("clip.chip.dismiss").tap() }
        XCTAssertTrue(guardBadge.isEmpty, "the badge stays cleared: \(guardBadge)")
        let spotted2 = any("guard.quick.spotted")
        reveal(spotted2)
        spotted2.tap()
        XCTAssertTrue(any("protect.spotted.entry").waitForExistence(timeout: 5), "Spotted survived a relaunch")
        back()
        openCheckLink()
        XCTAssertFalse(any("protect.recent.row").waitForExistence(timeout: 2), "cleared recent checks stay cleared")
    }
}
