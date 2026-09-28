import XCTest

/// Scenario 2: Sources. Every source's switch goes off and on again; then a mixed set (Calendar and Contacts off,
/// the rest on) survives a relaunch; the live scan shows and settles, and Scan again runs it once more.
/// Adding a Files folder needs iOS's document picker (a separate process the harness cannot fill with a known
/// folder), so it is on the device checklist (docs/PREDEPLOY-CHECKLIST.md), not here.
final class SourcesScenarios: ScenarioCase {
    private let phone = ["photos", "files", "calendar", "contacts"]

    private func phoneSwitch(_ id: String) -> XCUIElement {
        let t = app.switches["sources.phone.\(id).toggle"]
        reveal(t)
        XCTAssertTrue(t.waitForExistence(timeout: 5), "no switch for \(id)")
        return t
    }

    /// A source turned on scans at once, and its card grows with the live display: wait for every scan to settle so
    /// the next tap lands where the switch is.
    private func settle() {
        let scanning = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'sources.scan.'"))
        waitUntil(timeout: 90, "a scan never settled") { scanning.count == 0 }
    }

    /// Waits until the switch stops moving (a card above it grows or shrinks as its scan settles).
    private func steady(_ sw: XCUIElement) {
        var last = sw.frame
        for _ in 0..<20 {
            usleep(400_000)
            let now = sw.frame
            if now == last { return }
            last = now
        }
    }

    private func set(_ sw: XCUIElement, _ on: Bool, _ name: String) {
        let want = on ? "1" : "0"
        for attempt in 0..<3 where (sw.value as? String) != want {
            settle()
            reveal(sw)
            steady(sw)
            flip(sw)
            // A source turned on asks iOS; the Contacts sheet can take several seconds to come up.
            if on { answerSystemPrompt(wait: 8) }
            if XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", want), object: sw)],
                                timeout: 5) == .completed { break }
            // The tap missed (the card moved under it): a test-side miss, tried again.
            if attempt == 2 { XCTFail("\(name) did not turn \(on ? "on" : "off")") }
        }
        // And it stays there: nothing (a scan ending, a queued follow-up scan, a retry) may put it back.
        sleep(3)
        XCTAssertEqual(sw.value as? String, want, "\(name) flipped back after being turned \(on ? "on" : "off")")
    }

    func testEverySourceTurnsOffAndOnAndTheSetSurvivesARelaunch() {
        // Permission prompts (a phone source turned on) are answered by the fake; any real one is allowed.
        addUIInterruptionMonitor(withDescription: "permissions") { alert in
            for label in ["Allow Full Access", "Allow", "OK", "Allow While Using App"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }
        start("sources", ["-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupePermissions", "granted",
                          "-LoupePhotosDemo"])
        // No sample in the app (2026-09-28): -LoupeRunNow's run reads the hidden test fixture, nothing on screen names it.
        waitFor(app.staticTexts["debug.fixture.count"], "label == %@", "48 items", timeout: 90)
        XCTAssertFalse(any("sources.sample").exists, "no sample card")
        audit("02-sources-top")

        // Each phone source: off, then on again. Mail waits for a mailbox (its row offers the setup).
        for id in phone {
            let sw = phoneSwitch(id)
            XCTAssertEqual(sw.value as? String, "1", "\(id) is on by default")
            set(sw, false, id)
            set(sw, true, id)
        }
        // Mail is one place: its entry opens the Mail screen, where the mailbox's switch and setup are.
        let mailEntry = button("sources.mail")
        reveal(mailEntry)
        mailEntry.tap()
        XCTAssertTrue(app.switches["sources.phone.mail.toggle"].waitForExistence(timeout: 10), "Mail opens")
        let mail = phoneSwitch("mail")
        XCTAssertEqual(mail.value as? String, "0", "Mail is offered, not forced")
        audit("02-sources-mail", ["sources.phone.mail.setup"])
        back()
        // Files offers its picker.
        let add = button("sources.phone.files.add")
        answerSystemPrompt(wait: 8)
        settle()
        reveal(add)
        steady(add)
        audit("02-sources-files", ["sources.phone.files.add"])

        // The set to keep: Calendar and Contacts off, the rest on.
        set(phoneSwitch("calendar"), false, "calendar")
        set(phoneSwitch("contacts"), false, "contacts")

        // Close and open again.
        relaunch()
        XCTAssertEqual(app.staticTexts["debug.fixture.count"].label, "48 items", "the cache is read back, no rescan")
        XCTAssertEqual(phoneSwitch("photos").value as? String, "1", "Photos stays on")
        XCTAssertEqual(phoneSwitch("files").value as? String, "1", "Files stays on")
        XCTAssertEqual(phoneSwitch("calendar").value as? String, "0", "Calendar stays off")
        XCTAssertEqual(phoneSwitch("contacts").value as? String, "0", "Contacts stays off")
        let mailAgain = button("sources.mail")
        reveal(mailAgain)
        mailAgain.tap()
        XCTAssertTrue(app.switches["sources.phone.mail.toggle"].waitForExistence(timeout: 10), "Mail opens")
        XCTAssertEqual(phoneSwitch("mail").value as? String, "0", "Mail stays off")
        back()
        shot("02-relaunch")

        // The live scan: Photos reads the demo pictures in place, then settles on its summary and count.
        let photosRescan = button("sources.phone.photos.rescan").exists ? button("sources.phone.photos.rescan") : button("sources.phone.photos.allow")
        reveal(photosRescan)
        XCTAssertTrue(photosRescan.exists, "Photos offers a scan")
        photosRescan.tap()
        let display = any("sources.scan.photos")
        XCTAssertTrue(display.waitForExistence(timeout: 20), "the live scan display shows in the card")
        audit("02-scan-live")
        let summary = app.staticTexts["sources.scan.summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 120), "the scan settles on its summary")
        XCTAssertTrue(summary.label.hasSuffix("0 bytes out"), summary.label)
        let count = app.staticTexts["sources.phone.photos.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 20), "the card rests on its new count")
        XCTAssertEqual(count.label, "16 items")

        // Scan again: it runs once more and settles on the same count (nothing new, nothing lost).
        let again = button("sources.phone.photos.rescan")
        reveal(again)
        audit("02-scan-rest", ["sources.phone.photos.rescan"])
        again.tap()
        XCTAssertTrue(display.waitForExistence(timeout: 20), "Scan again shows the live display")
        XCTAssertTrue(count.waitForExistence(timeout: 120))
        XCTAssertEqual(count.label, "16 items")

        // The photos read survive a relaunch too (the cache, not a rescan from nothing).
        relaunch()
        let countAfter = app.staticTexts["sources.phone.photos.count"]
        reveal(countAfter)
        XCTAssertTrue(countAfter.waitForExistence(timeout: 30))
        XCTAssertEqual(countAfter.label, "16 items", "the scanned photos are still there after a relaunch")
    }
}
