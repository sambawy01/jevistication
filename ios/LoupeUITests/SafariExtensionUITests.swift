import XCTest

/// Loupe for Safari end to end in the simulator (2026-09-26). Opt-in: runs only with
/// `TEST_RUNNER_LOUPE_SAFARI_URL=<a page whose website name the verdict function flags>` (e.g. a local
/// server reached as http://xn--pypal-4ve.127.0.0.1.nip.io:8765/). It taps "Turn on Safari
/// protection" (iOS 26.2+ opens Safari's settings at Loupe), turns the extension on and allows it on
/// all websites, opens the page in Safari and screenshots Loupe's warning.
final class SafariExtensionUITests: XCTestCase {
    private var url: String? { ProcessInfo.processInfo.environment["LOUPE_SAFARI_URL"] }

    private func save(_ name: String, _ note: String = "") {
        let shot = XCUIScreen.main.screenshot()
        let dir = ProcessInfo.processInfo.environment["LOUPE_SHOTS"] ?? LinkCheckUITests.shots
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        if !note.isEmpty { try? note.write(toFile: dir + "/\(name).txt", atomically: true, encoding: .utf8) }
    }

    func testTurnOnInSettingsThenWarnInSafari() throws {
        try XCTSkipIf(url == nil, "set TEST_RUNNER_LOUPE_SAFARI_URL to run it")
        let url = url!
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "guard", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeModelState", "missing"]
        app.launch()
        let turnOn = app.descendants(matching: .any)["protect.safari.turnOn"].firstMatch
        for _ in 0..<14 where !(turnOn.exists && turnOn.isHittable) { app.swipeUp() }
        if turnOn.exists {
            save("protect-10-before-turn-on")
            turnOn.tap()
            let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
            _ = settings.wait(for: .runningForeground, timeout: 10)
            sleep(2)
            save("protect-11-settings-page", settings.debugDescription)
            // If iOS landed elsewhere (the deep link can fall back to Settings' root), walk there.
            if !settings.staticTexts["Allow Extension"].exists {
                settings.terminate()
                settings.launch()
                sleep(1)
                let apps = settings.buttons["com.apple.settings.apps"].firstMatch
                for _ in 0..<6 where !(apps.exists && apps.isHittable) { settings.swipeUp() }
                if apps.exists { apps.tap() }
                sleep(1)
                let safariRow = settings.buttons["Safari"].firstMatch
                for _ in 0..<8 where !(safariRow.exists && safariRow.isHittable) { settings.swipeUp() }
                if safariRow.exists { safariRow.tap() }
                sleep(1)
                let ext = settings.cells["WEB_EXTENSIONS"].firstMatch
                for _ in 0..<8 where !(ext.exists && ext.isHittable) { settings.swipeUp() }
                if ext.exists { ext.tap() }
                sleep(1)
                save("protect-11b-extensions-list", settings.debugDescription)
                let loupe = settings.cells.containing(NSPredicate(format: "label BEGINSWITH 'Loupe'")).firstMatch
                if loupe.exists { loupe.tap(); sleep(1) }
                save("protect-11c-loupe-page", settings.debugDescription)
            }
            // Allow Extension on.
            let allow = settings.switches["Allow Extension"].firstMatch
            if allow.waitForExistence(timeout: 3), "\(allow.value ?? "")" != "1" {
                allow.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            }
            sleep(2)
            // Then Permissions → All Websites (appears once the extension is allowed) → Allow.
            let all = settings.cells.containing(NSPredicate(format: "label BEGINSWITH 'All Websites'")).firstMatch
            if all.waitForExistence(timeout: 3) {
                all.tap()
                sleep(1)
                save("protect-12a-all-websites", settings.debugDescription)
                let allowRow = settings.cells.containing(NSPredicate(format: "label == 'Allow'")).firstMatch
                if allowRow.exists { allowRow.tap() } else { settings.staticTexts["Allow"].firstMatch.tap() }
                sleep(1)
                settings.navigationBars.buttons.element(boundBy: 0).tap()
                sleep(1)
            }
            save("protect-12-settings-after", settings.debugDescription)
            app.activate()
            sleep(2)
            save("protect-13-app-after")
        }

        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.launch()
        _ = safari.wait(for: .runningForeground, timeout: 10)
        let address = safari.textFields.firstMatch
        if !address.waitForExistence(timeout: 5) { safari.buttons["Address"].firstMatch.tap() }
        let bar = safari.textFields.firstMatch
        if bar.exists { bar.tap() } else { safari.buttons.matching(NSPredicate(format: "identifier CONTAINS 'URL' OR label CONTAINS 'Address'")).firstMatch.tap() }
        safari.typeText(url + "\n")
        sleep(4)
        save("protect-14a-safari-first-load", safari.debugDescription)
        // Safari asks, per site, whether Loupe may see it: allow on every website.
        for label in ["Always Allow on Every Website", "Always Allow", "Allow on Every Website", "Allow for One Day"] {
            let b = safari.buttons[label].firstMatch
            if b.exists { b.tap(); sleep(1) }
        }
        let review = safari.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'Review' OR label CONTAINS[c] 'Loupe'")).firstMatch
        if review.exists && !safari.buttons["Go back"].exists {
            review.tap(); sleep(1)
            save("protect-14b-safari-review", safari.debugDescription)
            for label in ["Always Allow on Every Website", "Always Allow", "Allow on Every Website", "Allow for One Day"] {
                let b = safari.buttons[label].firstMatch
                if b.exists { b.tap(); sleep(1); break }
            }
            safari.buttons["ReloadButton"].firstMatch.tap()
        }
        sleep(5)
        save("protect-14-safari-warning", safari.debugDescription)
        // "Continue anyway": the warning goes and stays gone for this site in this Safari session.
        let go = safari.buttons["Continue anyway"].firstMatch
        if go.exists {
            go.tap()
            sleep(2)
            save("protect-15-safari-continued")
        }
        // An ordinary site: no UI at all.
        if let normal = ProcessInfo.processInfo.environment["LOUPE_SAFARI_NORMAL_URL"] {
            safari.descendants(matching: .any).matching(NSPredicate(format: "label == 'Address'")).firstMatch.tap()
            sleep(1)
            safari.typeText(normal + "\n")
            sleep(5)
            save("protect-16-safari-normal-site", safari.debugDescription)
        }
        // Back in Loupe: the card and the Spotted log.
        app.activate()
        sleep(3)
        save("protect-17-app-after-safari")
    }
}
