import Combine
import XCTest
import LoupeKit
@testable import Loupe

/// The relaunch bug (owner report 2026-09-26: "when the app is closed and reopened, all the changes I made are
/// restored to the onboarding setup") and the owner decisions that came with it: on-device features on by
/// default (a switch the user turned off stays off), and one permissions step in onboarding that is never shown
/// again once completed. "Relaunch" here means new objects over the same UserDefaults suite and the same home
/// folder, which is what a new process reads.
@MainActor
final class SettingsPersistenceTests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!
    private var home: URL!

    override func setUpWithError() throws {
        suite = "persist-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        home = FileManager.default.temporaryDirectory.appendingPathComponent("Persist-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: home)
    }

    /// What a relaunch reads: a fresh UserDefaults object over the same suite.
    private func relaunchRecord() -> OnboardingRecord { OnboardingRecord(defaults: UserDefaults(suiteName: suite)!) }

    // MARK: The launch flow

    func testFreshInstallWalksEveryStepInOrder() {
        let r = OnboardingRecord(defaults: defaults)
        let launch = LaunchOptions()
        XCTAssertEqual(LaunchFlow.first(ready: false, launch: launch, record: r), .getModel)
        XCTAssertEqual(LaunchFlow.after(.getModel, record: r), .permissions, "Get the model, then permissions")
        r.permissionsDone = true
        XCTAssertEqual(LaunchFlow.after(.permissions, record: r), .protect, "then Safari protection and the clipboard")
        r.protectDone = true
        XCTAssertEqual(LaunchFlow.after(.protect, record: r), .tabs)
        XCTAssertTrue(LaunchFlow.showsIntro(launch: launch, record: r), "then the game intro over the tabs")
        r.introSeen = true
        XCTAssertFalse(LaunchFlow.showsIntro(launch: launch, record: r))
    }

    /// The owner's phone: the model installed and onboarding finished. A relaunch opens the tabs, nothing else.
    func testRelaunchAfterOnboardingOpensTheTabs() {
        let r = OnboardingRecord(defaults: defaults)
        r.permissionsDone = true
        r.protectDone = true
        r.introSeen = true
        let again = relaunchRecord()
        XCTAssertEqual(LaunchFlow.first(ready: true, launch: LaunchOptions(), record: again), .tabs)
        XCTAssertFalse(LaunchFlow.showsIntro(launch: LaunchOptions(), record: again))
    }

    /// The root cause end to end: readiness created over an installed model (as `ModelReadiness.shared` is at
    /// launch) must give the launch decision "tabs", not Get the Loupe Decision Model.
    func testInstalledModelAtLaunchDoesNotReopenOnboarding() {
        let status = CurrentValueSubject<LayaModel.Status, Never>(.ready)
        let readiness = ModelReadiness(status: status.eraseToAnyPublisher(), installed: { true }, refresh: {},
                                       hostConfigured: true, observesApp: false)
        let r = OnboardingRecord(defaults: defaults)
        r.permissionsDone = true
        r.protectDone = true
        r.introSeen = true
        XCTAssertEqual(LaunchFlow.first(ready: readiness.isReady, launch: LaunchOptions(), record: relaunchRecord()), .tabs)
    }

    func testSkippedStepsAreDoneAndACutShortLaunchResumes() {
        let r = OnboardingRecord(defaults: defaults)
        r.permissionsDone = true                                  // skipped or continued: done either way
        XCTAssertEqual(LaunchFlow.first(ready: true, launch: LaunchOptions(), record: relaunchRecord()), .protect,
                       "killed on the protection step: it resumes there, the permissions step does not come back")
        XCTAssertEqual(LaunchFlow.first(ready: false, launch: LaunchOptions(), record: relaunchRecord()), .getModel,
                       "Get the model still comes first while the model is missing (owner rule 2026-09-25)")
        XCTAssertEqual(LaunchFlow.after(.getModel, record: relaunchRecord()), .protect)
    }

    func testTestsAndTheGameSkipOnboarding() {
        var launch = LaunchOptions()
        launch.skipOnboarding = true
        let r = OnboardingRecord(defaults: defaults)
        XCTAssertEqual(LaunchFlow.first(ready: false, launch: launch, record: r), .tabs)
        XCTAssertFalse(LaunchFlow.showsIntro(launch: launch, record: r))
        launch.skipOnboarding = false
        launch.game = .watch
        XCTAssertEqual(LaunchFlow.first(ready: true, launch: launch, record: r), .tabs)
    }

    func testResetForgetsOnlyOnboarding() {
        let r = OnboardingRecord(defaults: defaults)
        r.permissionsDone = true; r.protectDone = true; r.introSeen = true
        defaults.set(false, forKey: SortService.enabledKey)
        r.reset()
        XCTAssertFalse(r.permissionsDone || r.protectDone || r.introSeen)
        XCTAssertEqual(defaults.object(forKey: SortService.enabledKey) as? Bool, false)
    }

    // MARK: Sources: on by default, and a switch turned off stays off across a relaunch

    private func sources() -> SourcesService {
        let deps = PhoneDependencies(
            photos: FakePhotoLibrary(), recognizer: FakeRecognizer(text: [:]), events: FakeEventStore(),
            contacts: FakeContactStore(), bookmarks: BookmarkStore(home: home, resolver: FakeBookmarks()),
            inbox: { [home] in home!.appendingPathComponent("inbox") }, mailAccounts: MailAccountStore(home: home),
            mailCache: home.appendingPathComponent("mail"), keychain: { _ in MemoryKeyStore() },
            makeTransport: { _ in fatalError("no network in this test") },
            oauth: OAuthConfig(googleClientId: "", microsoftClientId: ""), state: PhoneStateStore(home: home))
        return SourcesService(home: home, sampleRoot: nil, deps: deps)
    }

    func testOnDeviceSourcesAreOnByDefaultMailIsOffered() {
        let s = sources()
        for src in [PhoneSource.photos, .files, .calendar, .contacts] {
            XCTAssertTrue(s.isPhoneEnabled(src), "\(src) is on by default")
        }
        XCTAssertFalse(s.isPhoneEnabled(.mail), "Mail needs a sign-in: offered, not forced")
        XCTAssertTrue(s.sampleEnabled)
        XCTAssertTrue(s.inboxEnabled)
    }

    func testASwitchTurnedOffStaysOffAfterARelaunch() async {
        let s = sources()
        await s.setPhoneEnabled(.contacts, false)
        await s.setPhoneEnabled(.photos, false)
        s.setSampleEnabled(false)
        let again = sources()                                      // a new process over the same home
        XCTAssertFalse(again.isPhoneEnabled(.contacts), "set off: stays off")
        XCTAssertFalse(again.isPhoneEnabled(.photos))
        XCTAssertFalse(again.sampleEnabled)
        XCTAssertTrue(again.isPhoneEnabled(.calendar), "never set: still the default")
        XCTAssertTrue(again.isPhoneEnabled(.files))
        XCTAssertFalse(again.state(.contacts).enabled)
        XCTAssertTrue(again.state(.calendar).enabled)
    }

    /// The owner's own enabled.json, as pulled from the phone: what was set is read back exactly.
    func testTheOwnersSwitchesReadBackAsSet() throws {
        let dir = home.appendingPathComponent("sources")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data(#"{"sample": false, "photos": false, "files": true, "contacts": true, "calendar": true, "mail": true}"#.utf8)
            .write(to: dir.appendingPathComponent("enabled.json"))
        let s = sources()
        XCTAssertFalse(s.sampleEnabled)
        XCTAssertFalse(s.isPhoneEnabled(.photos), "the owner turned Photos off: the new default does not turn it on")
        XCTAssertTrue(s.isPhoneEnabled(.files) && s.isPhoneEnabled(.contacts) && s.isPhoneEnabled(.calendar))
        XCTAssertTrue(s.isPhoneEnabled(.mail))
    }

    /// Phone state kept with complete file protection is unreadable while the phone is locked (a background
    /// launch). Writing then must not replace every source's state with just one.
    func testPhoneStateIsNotOverwrittenWhenItCannotBeRead() throws {
        let store = PhoneStateStore(home: home)
        store.set("photos", ["changeToken": "42"])
        store.set("mail", ["uidValidity": "7"])
        let file = home.appendingPathComponent("sources/phone-state.json")
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: file.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: file.path) }
        guard (try? Data(contentsOf: file)) == nil else { throw XCTSkip("this runner can read a 000 file") }
        store.set("calendar", ["x": "1"])
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: file.path)
        XCTAssertEqual(store.state("photos"), ["changeToken": "42"], "unreadable: left alone")
        XCTAssertEqual(store.state("mail"), ["uidValidity": "7"])
        XCTAssertEqual(store.state("calendar"), [:])
    }

    // MARK: Other on-device switches

    func testSuspiciousSiteNotificationsAreOnUnlessTurnedOff() {
        XCTAssertTrue(ProtectionGroup.notifySuspicious(defaults))
        defaults.set(false, forKey: ProtectionGroup.Keys.notifySuspicious)
        XCTAssertFalse(ProtectionGroup.notifySuspicious(UserDefaults(suiteName: suite)!))
    }

    /// "Nothing leaves your phone unless you turn it on": every online check stays off by default.
    func testOnlineChecksStayOffByDefault() {
        let s = OnlinePhishingSettings.load(defaults)
        XCTAssertFalse(s.anyOn)
        XCTAssertFalse(s.domainFacts || s.feeds || s.safeBrowsing || s.dnsFacts || s.dnsbl || s.openPhish || s.phishTank)
        XCTAssertTrue(s.lists.isEmpty && s.dnsblLists.isEmpty, "nothing is downloaded or asked with the masters off")
    }

    // MARK: The permissions step

    @MainActor final class RecordingAsker: PermissionAsking {
        var answers: [PermissionKind: PhonePermission]
        var current: [PermissionKind: PhonePermission]
        private(set) var asked: [PermissionKind] = []
        init(answers: [PermissionKind: PhonePermission], current: [PermissionKind: PhonePermission] = [:]) {
            self.answers = answers
            self.current = current
        }
        func status(_ kind: PermissionKind) async -> PhonePermission { current[kind] ?? .notAsked }
        func request(_ kind: PermissionKind) async -> PhonePermission {
            asked.append(kind)
            current[kind] = answers[kind] ?? .granted
            return current[kind]!
        }
    }

    func testAllowWalksThePromptsInOrderAndMarksEachAnswer() async {
        let asker = RecordingAsker(answers: [.contacts: .denied])
        var answered = 0
        let m = PermissionsStepModel(asker: asker, sourceOff: { _ in false }, onAnswered: { answered += 1 })
        await m.load()
        XCTAssertEqual(m.pending, [.photos, .calendar, .contacts, .notifications])
        await m.allowAll()
        XCTAssertEqual(asker.asked, [.photos, .calendar, .contacts, .notifications], "one prompt after another, in order")
        XCTAssertEqual(m.rows.map(\.status), [.granted, .granted, .denied, .granted])
        XCTAssertEqual(m.denied, [.contacts], "denied: an Allow in Settings row, never a blocker")
        XCTAssertTrue(m.walked)
        XCTAssertNil(m.asking)
        XCTAssertEqual(answered, 1, "the sources re-read their permissions once")
    }

    func testAlreadyAnsweredAndSwitchedOffAreNotAsked() async {
        let asker = RecordingAsker(answers: [:], current: [.calendar: .granted, .notifications: .denied])
        let m = PermissionsStepModel(asker: asker, sourceOff: { $0 == .photos })
        await m.load()
        XCTAssertEqual(m.pending, [.contacts], "Photos was turned off by the user; calendar and notifications were answered")
        await m.allowAll()
        XCTAssertEqual(asker.asked, [.contacts])
        XCTAssertTrue(m.rows.first { $0.kind == .photos }!.sourceOff)
        XCTAssertEqual(m.rows.first { $0.kind == .photos }!.status, .notAsked)
    }

    func testNotificationStatusMapping() {
        XCTAssertEqual(SystemPermissionAsker.map(.notDetermined), .notAsked)
        XCTAssertEqual(SystemPermissionAsker.map(.denied), .denied)
        XCTAssertEqual(SystemPermissionAsker.map(.authorized), .granted)
        XCTAssertEqual(SystemPermissionAsker.map(.provisional), .granted)
    }
}
