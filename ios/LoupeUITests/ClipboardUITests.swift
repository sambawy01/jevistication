import XCTest

/// Clipboard checks (2026-09-26) in the simulator. The test runner puts a link on the clipboard (as if
/// copied in another app; `xcrun simctl pbcopy` does the same from the Mac), then Loupe offers the chip
/// without reading it, Check reads it (iOS asks "Allow Paste"), and the verdict shows as a banner and
/// lands in Spotted and the Clipboard card. Saves `clip-*.png` to $LOUPE_SHOTS, else the scratch folder.
final class ClipboardUITests: XCTestCase {
    static let lookalike = "https://xn--pypal-4ve.com/signin?session=secret-token"

    override func setUp() { continueAfterFailure = false }

    override func tearDown() {
        // Leave nothing on the simulator's clipboard for the next test class.
        UIPasteboard.general.items = []
    }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    private func save(_ name: String, note: String = "") {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
        let dir = ProcessInfo.processInfo.environment["LOUPE_SHOTS"] ?? LinkCheckUITests.shots
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        if !note.isEmpty { try? note.write(toFile: dir + "/\(name).txt", atomically: true, encoding: .utf8) }
    }

    /// iOS's paste question. While it is up, the app's main thread waits inside the pasteboard read,
    /// so the app cannot be queried; the question itself is SpringBoard's. Returns whether it appeared.
    @discardableResult
    private func answerPaste(_ app: XCUIApplication, allow: Bool = true, wait: TimeInterval = 4) -> Bool {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let label = allow ? "Allow Paste" : "Don’t Allow Paste"
        let b = springboard.buttons[label].firstMatch
        if b.waitForExistence(timeout: wait) { b.tap(); return true }
        return false
    }

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeClipboard", "-LoupeTab", "guard", "-LoupeSkipOnboarding",
                               "-LoupeLanguage", "en", "-LoupeModelState", "missing"] + extra
        app.launch()
        return app
    }

    /// Which pasteboard calls show iOS's paste prompt (evidence for the design: detection must not).
    func testWhichPasteboardCallsAskToPaste() {
        var lines: [String] = []
        for api in ["changeCount", "hasStrings", "detectPatterns", "detectedPatterns", "detectedValues", "string"] {
            UIPasteboard.general.string = Self.lookalike
            let app = launch(["-LoupeClipboardProbe", api])
            let prompted = answerPaste(app, allow: true, wait: 5)
            let result = any(app, "clip.problem.text")
            _ = result.waitForExistence(timeout: 6)
            let line = "\(api): \(prompted ? "PROMPT" : "no prompt") · \(result.exists ? result.label : "no result")"
            lines.append(line)
            if api == "detectedValues" || api == "string" { if prompted { save("clip-probe-\(api)-answered") } }
            app.terminate()
        }
        let report = lines.joined(separator: "\n")
        save("clip-00-probe", note: report)
        let a = XCTAttachment(string: report); a.name = "pasteboard-prompts"; a.lifetime = .keepAlways; add(a)
        // The design depends on these two: detection never asks, reading does.
        XCTAssertTrue(lines.contains { $0.hasPrefix("detectedPatterns: no prompt") }, report)
        XCTAssertTrue(lines.contains { $0.hasPrefix("changeCount: no prompt") }, report)
    }

    func testChipCheckBannerSpottedAndCard() {
        UIPasteboard.general.string = Self.lookalike
        let app = launch(["-LoupeClipboardReset"])

        // The chip, offered without reading the clipboard (no paste question yet).
        let chip = any(app, "clip.chip")
        XCTAssertTrue(chip.waitForExistence(timeout: 15), "the chip for a copied link")
        XCTAssertEqual(any(app, "clip.chip.text").label, "Check the link you copied?")
        XCTAssertFalse(answerPaste(app, wait: 1), "detecting a link must not ask to paste")
        save("clip-01-chip")

        // Check: iOS asks, the verdict is Dangerous (a PayPal look-alike).
        any(app, "clip.chip.check").tap()
        let asked = answerPaste(app)
        let banner = any(app, "clip.banner")
        XCTAssertTrue(banner.waitForExistence(timeout: 10))
        XCTAssertEqual(banner.value as? String, "dangerous")
        XCTAssertTrue(any(app, "clip.banner.sentence").label.contains("fake PayPal"), any(app, "clip.banner.sentence").label)
        XCTAssertTrue(any(app, "clip.hint").exists, "the one-time Paste from Other Apps hint")
        save("clip-02-dangerous", note: "paste prompt shown on Check: \(asked)")

        // Details: the full verdict card.
        any(app, "clip.banner.details").tap()
        XCTAssertTrue(app.staticTexts["What you copied"].waitForExistence(timeout: 5))
        save("clip-03-details")
        any(app, "clip.sheet.done").tap()
        any(app, "clip.banner.dismiss").tap()
        XCTAssertFalse(banner.waitForExistence(timeout: 1))

        // Guard → Protection → Clipboard: the switch, the last check, the keyboard's status.
        let card = any(app, "clip.card")
        for _ in 0..<14 where !(card.exists && card.isHittable) { app.swipeUp() }
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        let recent = any(app, "clip.card.recent")
        XCTAssertTrue(recent.exists)
        XCTAssertTrue(recent.label.contains("xn--pypal-4ve.com"), "the written form, never a name that reads like paypal.com: \(recent.label)")
        XCTAssertFalse(recent.label.contains("secret-token"), "no query is kept")
        for _ in 0..<2 where any(app, "clip.card.setup").exists && !any(app, "clip.card.setup").isHittable { app.swipeUp() }
        save("clip-04-card")

        // Spotted: the look-alike, from the clipboard.
        let spotted = any(app, "protect.spotted")
        for _ in 0..<6 where !(spotted.exists && spotted.isHittable) { app.swipeUp() }
        spotted.tap()
        let entry = any(app, "protect.spotted.entry")
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        XCTAssertTrue(entry.label.contains("Clipboard"), entry.label)
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Set up: the ClipboardSetupCard.
        let setup = any(app, "clip.card.setup")
        for _ in 0..<6 where !(setup.exists && setup.isHittable) { app.swipeDown() }
        setup.tap()
        XCTAssertTrue(any(app, "clip.setup").waitForExistence(timeout: 5))
        let privacy = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'no network code'")).firstMatch
        XCTAssertTrue(privacy.waitForExistence(timeout: 3), "the keyboard's privacy line")
        save("clip-05-setup")

        // The same copy is never offered again (across launches).
        app.terminate()
        let again = launch()
        XCTAssertTrue(any(again, "guard.header").waitForExistence(timeout: 15))
        XCTAssertFalse(any(again, "clip.chip").waitForExistence(timeout: 4), "each copy is offered once")

        // A new copy is.
        UIPasteboard.general.string = "Your parcel is waiting: https://royalmail-redelivery.example-fees.top/pay"
        XCUIDevice.shared.press(.home)
        again.activate()
        XCTAssertTrue(any(again, "clip.chip").waitForExistence(timeout: 10), "a new copy gets the chip")
    }

    /// Numbers and phone numbers are detected but not offered (Loupe has no check for them).
    func testANumberIsNotOffered() {
        UIPasteboard.general.string = "+20 100 123 4567"
        let app = launch(["-LoupeClipboardReset"])
        XCTAssertTrue(any(app, "guard.header").waitForExistence(timeout: 15))
        XCTAssertFalse(any(app, "clip.chip").waitForExistence(timeout: 4))
        XCTAssertFalse(answerPaste(app, wait: 1), "nothing was read")
    }
}
