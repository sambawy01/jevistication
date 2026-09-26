import XCTest
@testable import Loupe

/// Safari's settings, faked: whether iOS can read and open them, the state it reports, failures.
final class FakeSafariControl: SafariExtensionControl {
    var canReadAndOpen = true
    var enabled: Bool? = false
    var readError: Error?
    var openError: Error?
    var opened = 0
    func isEnabled() async throws -> Bool? {
        if let readError { throw readError }
        return canReadAndOpen ? enabled : nil
    }
    func openSettings() async throws {
        if let openError { throw openError }
        opened += 1
    }
}

final class FakePermission: NotificationPermission {
    var asked = 0
    func request() async -> Bool { asked += 1; return true }
}

/// The turn-on flow (owner request 2026-09-26): off → Safari's settings opened → back in the app → on,
/// with a celebration and one notification question; an error or an older iOS → the manual steps.
@MainActor
final class SafariSetupTests: XCTestCase {
    private struct Boom: LocalizedError { var errorDescription: String? { "iOS said no" } }

    private func setup(_ control: FakeSafariControl, _ permission: FakePermission = FakePermission(),
                       defaults: UserDefaults = UserDefaults(suiteName: "setup-\(UUID())")!) -> SafariSetup {
        SafariSetup(control: control, notifications: permission, defaults: defaults)
    }

    func testOffThenSettingsThenForegroundThenOn() async {
        let control = FakeSafariControl()
        let permission = FakePermission()
        let s = setup(control, permission)
        XCTAssertEqual(s.phase, .checking)
        await s.refresh()
        XCTAssertEqual(s.phase, .off)
        XCTAssertFalse(s.celebrate)
        XCTAssertEqual(permission.asked, 0, "never asked at launch")

        await s.turnOn()
        XCTAssertEqual(control.opened, 1)
        XCTAssertEqual(s.phase, .openedSettings)
        XCTAssertNil(s.manual)

        // Back in the app, still off: keeps waiting.
        await s.refresh()
        XCTAssertEqual(s.phase, .openedSettings)

        control.enabled = true
        await s.refresh()               // the app became active again
        XCTAssertEqual(s.phase, .on)
        XCTAssertTrue(s.isOn)
        XCTAssertTrue(s.celebrate)
        XCTAssertEqual(s.statusLine, "On · Safari protected")
        XCTAssertEqual(permission.asked, 1, "notifications asked the first time protection is on")

        s.celebrate = false
        await s.refresh()
        XCTAssertFalse(s.celebrate, "no second celebration")
        XCTAssertEqual(permission.asked, 1, "asked once")
    }

    func testAlreadyOnAtLaunchIsQuiet() async {
        let control = FakeSafariControl()
        control.enabled = true
        let s = setup(control)
        await s.refresh()
        XCTAssertEqual(s.phase, .on)
        XCTAssertFalse(s.celebrate)
    }

    func testAnOpenErrorFallsBackToTheManualSteps() async {
        let control = FakeSafariControl()
        control.openError = Boom()
        let s = setup(control)
        await s.refresh()
        await s.turnOn()
        XCTAssertEqual(s.manual, .openFailed("iOS said no"))
        XCTAssertEqual(s.phase, .off)
    }

    func testOlderIOSShowsTheManualSteps() async {
        let control = FakeSafariControl()
        control.canReadAndOpen = false
        let defaults = UserDefaults(suiteName: "setup-\(UUID())")!
        let s = setup(control, defaults: defaults)
        await s.refresh()
        XCTAssertEqual(s.phase, .unknown)
        XCTAssertFalse(s.canOpenDirectly)
        await s.turnOn()
        XCTAssertEqual(s.manual, .olderIOS)
        XCTAssertEqual(control.opened, 0)
        XCTAssertTrue(s.statusLine.contains("does not tell Loupe"))

        // Safari asked Loupe about a site recently: the card says it is working.
        defaults.set(Date().addingTimeInterval(-300).timeIntervalSince1970, forKey: ProtectionGroup.Keys.safariLastSeen)
        await s.refresh()
        XCTAssertNotNil(s.lastSeen)
        XCTAssertTrue(s.statusLine.hasPrefix("Working"), s.statusLine)
    }

    func testAnUnreadableStateIsUnknownNotOff() async {
        let control = FakeSafariControl()
        control.readError = Boom()
        let s = setup(control)
        await s.refresh()
        XCTAssertEqual(s.phase, .unknown)
    }
}
