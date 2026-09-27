import XCTest

/// The pre-deployment scenario suite (2026-09-27, owner request: "put each function to the test, make sure all
/// needed actions have buttons and all designed features are on the UI, nothing is buried, and all modifications
/// and entries persist across a relaunch of the app").
///
/// Each scenario is a real user journey that ends with `relaunch()`: the app is terminated and launched again with
/// the same launch arguments minus the reset flags, and the test checks that every change survived.
///
/// Fixture scenarios run under `-LoupeFixtures -LoupeScenarioHome <name>`: the fixture ledger, judgments, sources,
/// settings and Web switches live in one folder that outlives the process (DEBUG `ScenarioHome`), as Application
/// Support/Loupe does on a phone. `-LoupeScenarioReset` on the first launch only empties it, so every scenario
/// starts from a known state and never depends on another.
///
/// Every screen a scenario visits is audited (`audit`): the actions it needs exist, are hittable and are at least
/// 44 pt, and no text or button on it says "Laya" (the model is "the decision model" to users).
///
/// Screenshots: set TEST_RUNNER_LOUPE_SHOTS to a folder to keep them (scenario-*.png); they are always attached.
class ScenarioCase: XCTestCase {
    /// Flags that stand for a fresh install or a clean fixture; `relaunch()` drops them.
    static let resetFlags: Set<String> = ["-LoupeScenarioReset", "-LoupeResetOnboarding", "-LoupeClipboardReset",
                                          "-LoupeResetModelConsent", "-LoupeResetMascot"]

    private(set) var app = XCUIApplication()
    private(set) var arguments: [String] = []
    /// Screens audited in this test (the report lists them).
    private(set) var audited: [String] = []

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    // MARK: Launching

    /// A fixture scenario: its own persistent fixture home, emptied on this first launch.
    @discardableResult
    func start(_ scenario: String, _ extra: [String]) -> XCUIApplication {
        launch(["-LoupeFixtures", "-LoupeScenarioHome", scenario, "-LoupeScenarioReset", "-LoupeLanguage", "en"] + extra)
    }

    /// Any launch; `relaunch()` reuses these arguments.
    @discardableResult
    func launch(_ args: [String]) -> XCUIApplication {
        arguments = args
        app = XCUIApplication()
        app.launchArguments = args
        app.launch()
        return app
    }

    /// Terminates and launches again with the same arguments minus the reset flags, and minus `dropping` (a flag and
    /// its value: a DEBUG seeding flag that would put back what the test just removed).
    @discardableResult
    func relaunch(dropping: [String] = []) -> XCUIApplication {
        app.terminate()
        var args: [String] = []
        var skipNext = false
        for a in arguments {
            if skipNext { skipNext = false; continue }
            if Self.resetFlags.contains(a) { continue }
            if dropping.contains(a) { skipNext = true; continue }
            args.append(a)
        }
        arguments = args
        app = XCUIApplication()
        app.launchArguments = args
        app.launch()
        return app
    }

    // MARK: Finding things

    func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
    func button(_ id: String) -> XCUIElement { app.buttons[id].firstMatch }
    var tabs: XCUIElement { app.tabBars.firstMatch }

    func tab(_ name: String) {
        let b = tabs.buttons[name]
        XCTAssertTrue(b.waitForExistence(timeout: 20), "no \(name) tab")
        b.tap()
    }

    /// Swipes up (or down) until `el` is on screen and hittable.
    @discardableResult
    func scrollTo(_ el: XCUIElement, max: Int = 14, up: Bool = true) -> Bool {
        for _ in 0..<max where !(el.exists && el.isHittable) {
            if up { app.swipeUp() } else { app.swipeDown() }
        }
        return el.exists && el.isHittable
    }

    /// The band of the screen a tap can land in: under the navigation bar, above the tab bar (when the tab bar is
    /// the front-most, i.e. no sheet is over it). An element under the translucent bars still reports hittable, and
    /// a tap there hits the bar.
    private var visibleBand: ClosedRange<CGFloat> {
        let nav = app.navigationBars.firstMatch
        let top = nav.exists && nav.isHittable ? nav.frame.maxY + 2 : 50
        let bottom = tabs.exists && tabs.isHittable && tabs.frame.minY > 0 ? tabs.frame.minY - 2 : app.frame.maxY - 20
        return top...max(top + 1, bottom)
    }

    private func inNavigationBar(_ el: XCUIElement) -> Bool {
        let nav = app.navigationBars.firstMatch
        return nav.exists && nav.frame.insetBy(dx: 0, dy: -4).contains(el.frame)
    }

    func onScreen(_ el: XCUIElement) -> Bool {
        guard el.exists, el.isHittable else { return false }
        if inNavigationBar(el) { return true }
        let f = el.frame, band = visibleBand
        if f.height >= band.upperBound - band.lowerBound { return true }
        return f.minY >= band.lowerBound - 2 && f.maxY <= band.upperBound + 2
    }

    /// Scrolls until the element sits fully in the visible band: short drags in the direction it lies, big swipes
    /// while it is not in the tree yet (lists are lazy). Stops when a drag no longer moves it (the end of the page).
    @discardableResult
    func reveal(_ el: XCUIElement, max: Int = 24) -> Bool {
        var lostDown = 0
        var lastY: CGFloat = .nan
        for _ in 0..<max {
            if onScreen(el) { return true }
            if !el.exists {
                // Not built yet: look further down first, then up.
                if lostDown < 10 { app.swipeUp(); lostDown += 1 } else { app.swipeDown() }
                continue
            }
            let y = el.frame.midY
            if y == lastY { return el.isHittable }       // the page does not move any more
            lastY = y
            let band = visibleBand
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55))
            let delta: CGFloat = el.frame.minY < band.lowerBound ? 0.22 : -0.22
            start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55 + delta)))
        }
        return onScreen(el)
    }

    /// Replaces a field's text: the cursor goes to the very end (a tap in the middle of a wrapped question would
    /// leave text behind), everything is deleted, then `text` is typed.
    func replaceText(_ el: XCUIElement, _ text: String) {
        el.tap()
        let current = (el.value as? String) ?? ""
        let placeholder = el.placeholderValue ?? ""
        if !current.isEmpty && current != placeholder {
            el.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.92)).tap()
            el.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        }
        el.typeText(text)
    }

    /// Puts the keyboard away (a drag on the form, which dismisses it interactively, else Return).
    func hideKeyboard() {
        guard app.keyboards.firstMatch.exists else { return }
        let nav = app.navigationBars.firstMatch
        if nav.exists { nav.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        if app.keyboards.firstMatch.exists { app.swipeDown(velocity: .slow) }
        if app.keyboards.firstMatch.exists, app.keyboards.buttons["Return"].exists { app.keyboards.buttons["Return"].tap() }
    }

    func back() { app.navigationBars.buttons.element(boundBy: 0).tap() }

    /// A SwiftUI Toggle in a list row: tap the switch itself, at the trailing end.
    func flip(_ sw: XCUIElement) { sw.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap() }

    func waitFor(_ el: XCUIElement, _ format: String, _ args: Any..., timeout: TimeInterval = 10, _ message: String = "") {
        let p = NSPredicate(format: format, argumentArray: args)
        let e = XCTNSPredicateExpectation(predicate: p, object: el)
        let r = XCTWaiter().wait(for: [e], timeout: timeout)
        XCTAssertEqual(r, .completed, message.isEmpty ? "\(el) never matched \(format) \(args); label: \(el.exists ? el.label : "gone")" : message)
    }

    func waitUntil(timeout: TimeInterval, _ message: String, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline { usleep(250_000) }
        XCTAssertTrue(condition(), message)
    }

    /// The first number in a label ("Needs you: 12" → 12).
    func number(_ label: String) -> Int {
        let digits = label.split(whereSeparator: { !$0.isNumber }).first.map(String.init) ?? ""
        return Int(digits) ?? -1
    }

    /// Answers an iOS permission prompt if one is up (a phone source turned on asks iOS; the Contacts one is a
    /// full sheet, not an alert, so an interruption monitor does not see it). Returns whether one was answered.
    @discardableResult
    func answerSystemPrompt(wait: TimeInterval = 3) -> Bool {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let labels = ["Allow Full Access", "Allow", "OK", "Allow While Using App", "Continue"]
        let deadline = Date().addingTimeInterval(wait)
        repeat {
            for host in [springboard, app] {
                let share = host.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Share All'")).firstMatch
                if share.exists { share.tap(); return true }
                for l in labels where host.alerts.buttons[l].exists { host.alerts.buttons[l].tap(); return true }
            }
            usleep(300_000)
        } while Date() < deadline
        return false
    }

    // MARK: The buttons audit (scenario 13)

    /// The screen's needed actions exist, are hittable (scrolled to) and are at least 44 pt each way, and nothing on
    /// it says "Laya". `ids` are accessibility identifiers of buttons (or any element, when not a button).
    func audit(_ screen: String, _ ids: [String] = [], minSize: CGFloat = 44, file: StaticString = #filePath, line: UInt = #line) {
        audited.append(screen)
        for id in ids {
            let b = app.buttons[id].firstMatch.exists ? app.buttons[id].firstMatch : any(id)
            reveal(b)
            XCTAssertTrue(b.exists, "\(screen): no \(id)", file: file, line: line)
            XCTAssertTrue(b.isHittable, "\(screen): \(id) is not hittable", file: file, line: line)
            let f = b.frame
            // A navigation bar's own items are the system's size (36 pt capsules on iOS 26, with UIKit's larger
            // touch area around them); they are not ours to size.
            let nav = app.navigationBars.firstMatch
            if nav.exists, nav.frame.insetBy(dx: 0, dy: -4).contains(f) { continue }
            XCTAssertGreaterThanOrEqual(f.height + 0.5, minSize, "\(screen): \(id) is \(f.height) pt tall", file: file, line: line)
            XCTAssertGreaterThanOrEqual(f.width + 0.5, minSize, "\(screen): \(id) is \(f.width) pt wide", file: file, line: line)
        }
        noLaya(screen, file: file, line: line)
        shot(screen)
    }

    /// No user-visible text or button label on the screen contains "Laya" (identifiers may; labels may not). One
    /// snapshot of the whole tree rather than a query per element.
    func noLaya(_ screen: String, file: StaticString = #filePath, line: UInt = #line) {
        let tree = app.debugDescription
        var offending: [String] = []
        for row in tree.split(separator: "\n") where row.contains("Laya") {
            // "…, identifier: 'needsLaya.sort', label: 'Needs the decision model', value: …": identifiers may say it.
            var visible = String(row)
            if let id = visible.range(of: "identifier: '") {
                let rest = visible[id.upperBound...]
                if let end = rest.range(of: "'") { visible.removeSubrange(id.lowerBound..<end.upperBound) }
            }
            // The one allowed mention: the Licences screen credits the upstream model (PRODUCT.md §11: "the Licences
            // screen credits the upstream Laya model by Convai"; Apache-2.0 attribution).
            if visible.contains("Laya by Convai") { continue }
            if visible.contains("Laya") { offending.append(String(row).trimmingCharacters(in: .whitespaces)) }
        }
        XCTAssertTrue(offending.isEmpty, "\(screen): user-visible \"Laya\": \(offending)", file: file, line: line)
    }

    // MARK: Evidence

    func shot(_ name: String) {
        let s = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: s)
        a.name = "scenario-\(name)"
        a.lifetime = .keepAlways
        add(a)
        guard let dir = ProcessInfo.processInfo.environment["LOUPE_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: "\(dir)/scenario-\(name).png", contents: s.pngRepresentation)
    }
}
