import XCTest

/// The Loupe keyboard in the simulator (2026-09-26). Opt-in (`TEST_RUNNER_LOUPE_KEYBOARD=1`): it changes
/// the simulator's Settings. It follows the app's own guidance (Set up → "Add the Loupe keyboard" opens
/// Settings at Loupe → Keyboards → Loupe on → Allow Full Access → Allow), then types in Check a link
/// with the Loupe keyboard and shows the strip's verdict for a copied look-alike link.
/// Needs the simulator's hardware keyboard disconnected (I/O → Keyboard → Connect Hardware Keyboard off).
final class KeyboardUITests: XCTestCase {
    override func setUp() { continueAfterFailure = true }

    override func tearDown() { UIPasteboard.general.items = [] }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    private func save(_ name: String, _ note: String = "") {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
        let dir = ProcessInfo.processInfo.environment["LOUPE_SHOTS"] ?? LinkCheckUITests.shots
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        if !note.isEmpty { try? note.write(toFile: dir + "/\(name).txt", atomically: true, encoding: .utf8) }
    }

    private func turnOn(_ s: XCUIElement) {
        if s.exists, "\(s.value ?? "")" != "1" { s.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() }
    }

    func testAddTheKeyboardThenCheckAPastedLink() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["LOUPE_KEYBOARD"] == nil, "set TEST_RUNNER_LOUPE_KEYBOARD=1 to run it")
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "guard", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeModelState", "missing"]
        app.launch()

        // Guard → Clipboard → Set up → "Add the Loupe keyboard" (opens Loupe's page in Settings).
        let setup = any(app, "clip.card.setup")
        for _ in 0..<14 where !(setup.exists && setup.isHittable) { app.swipeUp() }
        setup.tap()
        let add = any(app, "clip.setup.keyboard")
        for _ in 0..<4 where !(add.exists && add.isHittable) { app.swipeUp() }
        if add.exists {
            add.tap()
            let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
            _ = settings.wait(for: .runningForeground, timeout: 10)
            sleep(2)
            save("clip-10-settings-loupe", settings.debugDescription)
            var keyboards = settings.staticTexts["Keyboards"].firstMatch
            if !keyboards.waitForExistence(timeout: 4) {
                // The simulator's Settings can open at its root on a cold start: walk to Apps → Loupe.
                let apps = settings.buttons["com.apple.settings.apps"].firstMatch
                for _ in 0..<6 where !(apps.exists && apps.isHittable) { settings.swipeUp() }
                apps.tap()
                sleep(1)
                let loupe = settings.staticTexts["Loupe"].firstMatch
                for _ in 0..<10 where !(loupe.exists && loupe.isHittable) { settings.swipeUp() }
                loupe.tap()
                sleep(1)
                save("clip-10b-settings-apps-loupe", settings.debugDescription)
                keyboards = settings.staticTexts["Keyboards"].firstMatch
            }
            keyboards.tap()
            sleep(1)
            turnOn(settings.switches["Loupe"].firstMatch)
            sleep(1)
            turnOn(settings.switches["Allow Full Access"].firstMatch)
            sleep(1)
            let allow = settings.alerts.buttons["Allow"].firstMatch
            if allow.waitForExistence(timeout: 3) { allow.tap() }
            sleep(1)
            save("clip-11-settings-keyboards", settings.debugDescription)
            app.activate()
            sleep(2)
        }
        if app.buttons["Done"].firstMatch.exists { app.buttons["Done"].firstMatch.tap() }

        // Copy a look-alike link "in another app", then type in Check a link.
        UIPasteboard.general.string = "https://xn--pypal-4ve.com/signin"
        // A fresh launch: a running app keeps the keyboard list it started with.
        app.terminate()
        app.launchArguments += ["-LoupeOpen", "linkcheck"]
        app.launch()
        let field = any(app, "protect.link.input")
        if !field.waitForExistence(timeout: 8) {
            // -LoupeOpen linkcheck opens it once the Protection section has loaded; else walk there.
            let row = any(app, "protect.checkLink")
            for _ in 0..<20 where !(row.exists && row.isHittable) && !field.exists { app.swipeUp() }
            for _ in 0..<20 where !(row.exists && row.isHittable) && !field.exists { app.swipeDown() }
            if !field.exists {
                if !row.exists { save("clip-06z-no-row", app.debugDescription) }
                row.tap()
            }
        }
        XCTAssertTrue(field.waitForExistence(timeout: 8))
        field.tap()
        sleep(2)
        // Switch keyboards with the globe until the Loupe strip shows.
        let strip = any(app, "kb.strip.text")
        var seen: [String] = []
        for _ in 0..<8 where !strip.exists {
            // iOS's one-time "Quickly Change Keyboards" tip.
            if app.buttons["Continue"].firstMatch.exists { app.buttons["Continue"].firstMatch.tap(); sleep(1) }
            let globe = app.buttons["Next keyboard"].firstMatch
            guard globe.exists else { break }
            seen.append("\(globe.value ?? "")")
            if seen.count == 3 {
                // Hold the globe: the list of keyboards.
                globe.press(forDuration: 1.2)
                sleep(1)
                save("clip-06y-keyboard-list", app.debugDescription)
                let pick = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Loupe'")).firstMatch
                if pick.exists { pick.tap(); sleep(2); continue }
            }
            globe.tap()
            sleep(2)
        }
        if !strip.exists { save("clip-06x-keyboard-not-found", seen.joined(separator: ", ") + "\n" + app.debugDescription) }
        XCTAssertTrue(strip.waitForExistence(timeout: 5), "the Loupe keyboard is up")
        // The strip offers a check without reading the clipboard; a tap reads it (iOS asks).
        let offer = NSPredicate(format: "label BEGINSWITH 'Copied a link'")
        expectation(for: offer, evaluatedWith: strip)
        waitForExpectations(timeout: 10)
        save("clip-06a-keyboard-offer", strip.label)
        strip.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let asked = springboard.buttons["Allow Paste"].firstMatch.waitForExistence(timeout: 5)
        if asked { springboard.buttons["Allow Paste"].firstMatch.tap() }
        let verdict = NSPredicate(format: "label CONTAINS 'PayPal' OR label CONTAINS 'no warning signs'")
        expectation(for: verdict, evaluatedWith: strip)
        waitForExpectations(timeout: 15)
        save("clip-06-keyboard-strip", "\(strip.label)\npaste prompt on the keyboard's read: \(asked)")
        XCTAssertTrue(strip.label.contains("fake PayPal"), strip.label)

        // Type with it, then the Arabic layout.
        for ch in ["l", "o", "u", "p", "e"] { app.keys[ch].exists ? app.keys[ch].tap() : any(app, "kb.key.\(ch)").tap() }
        save("clip-07-keyboard-typed")
        any(app, "kb.key.Arabic").tap()
        sleep(1)
        save("clip-08-keyboard-arabic")
        any(app, "kb.key.English").tap()
        any(app, "kb.strip.info").tap()
        sleep(1)
        save("clip-09-keyboard-info", any(app, "kb.info.text").label)
        any(app, "kb.info.done").tap()

        // Leave the system keyboard selected: later tests type with `typeText`, which needs it.
        for _ in 0..<6 where strip.exists {
            let globe = app.buttons["Next keyboard"].firstMatch
            guard globe.exists else { break }
            globe.tap()
            sleep(1)
        }
    }
}
