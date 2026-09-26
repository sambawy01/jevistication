import XCTest
@testable import Loupe

/// A notification centre that records instead of posting.
final class FakeNotificationCenter: ProtectionNotificationCenter {
    var authorized = true
    var posted: [(id: String, title: String, body: String)] = []
    func isAuthorized() async -> Bool { authorized }
    func post(id: String, title: String, body: String) async { posted.append((id, title, body)) }
}

/// The Spotted log (append, de-duplication, 90-day retention, unseen count, clearing, the week's
/// summary) and the notification rules (per-site rate limit, the suspicious switch, never after
/// "Continue anyway", only when allowed).
final class SpottedTests: XCTestCase {
    private var dir: URL!
    private var clock = Date(timeIntervalSince1970: 1_790_000_000)

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("Spotted-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func log() -> SpottedLog { SpottedLog(dir: dir, now: { [unowned self] in self.clock }) }

    @discardableResult
    private func add(_ log: SpottedLog, _ host: String, _ level: ProtectionLevel = .dangerous, origin: ProtectionOrigin = .safari) -> SpottedEntry? {
        log.record(domain: host, host: host, level: level, score: level == .dangerous ? 80 : 40,
                   reasons: ["one", "two", "three", "four"], brand: "PayPal", origin: origin)
    }

    func testAppendsFlaggedSitesOnlyNewestFirst() {
        let l = log()
        XCTAssertNil(add(l, "fine.example", .safe), "a site with no warning signs is never logged")
        add(l, "a.example")
        clock += 60
        add(l, "b.example", .suspicious)
        let e = l.entries()
        XCTAssertEqual(e.map(\.host), ["b.example", "a.example"])
        XCTAssertEqual(e[1].reasons, ["one", "two", "three"], "three reasons at most")
        XCTAssertEqual(e[1].action, .unknown)
        XCTAssertEqual(e[1].origin, .safari)
        let raw = String(decoding: try! Data(contentsOf: l.file), as: UTF8.self)
        XCTAssertFalse(raw.contains("http"), "domains only, never an address")
    }

    func testRepeatsWithinHalfAnHourMergeAndRaiseTheLevel() {
        let l = log()
        add(l, "x.example", .suspicious)
        clock += 10 * 60
        add(l, "x.example", .dangerous)
        XCTAssertEqual(l.entries().count, 1)
        XCTAssertEqual(l.entries()[0].count, 2)
        XCTAssertEqual(l.entries()[0].level, .dangerous)
        add(l, "x.example", .dangerous, origin: .shared)
        XCTAssertEqual(l.entries().count, 2, "another place is another entry")
        clock += 31 * 60
        add(l, "x.example", .dangerous)
        XCTAssertEqual(l.entries().count, 3, "after the window it is a new visit")
    }

    func testKeepsNinetyDays() {
        let l = log()
        add(l, "old.example")
        clock += 89 * 24 * 3600
        add(l, "recent.example")
        XCTAssertEqual(l.entries().count, 2)
        clock += 2 * 24 * 3600
        XCTAssertEqual(l.entries().map(\.host), ["recent.example"])
    }

    func testUnseenCountActionsRemoveAndClear() {
        let l = log()
        add(l, "a.example")
        add(l, "b.example")
        XCTAssertEqual(l.unseenCount, 2)
        l.markAllSeen()
        XCTAssertEqual(l.unseenCount, 0)
        add(l, "c.example")
        XCTAssertEqual(l.unseenCount, 1)
        l.setAction(host: "a.example", origin: .safari, .continued)
        XCTAssertEqual(l.entries().first { $0.host == "a.example" }?.action, .continued)
        l.remove(l.entries()[0].id)
        XCTAssertEqual(l.entries().count, 2)
        l.clear()
        XCTAssertTrue(l.entries().isEmpty)
    }

    func testTheWeeksSummaryForNow() {
        let l = log()
        add(l, "a.example")
        add(l, "a.example", origin: .manual)
        add(l, "b.example", .suspicious)
        var s = SpottedSummary.of(l.entries(), now: clock)
        XCTAssertEqual(s.sitesThisWeek, 2)
        XCTAssertEqual(s.dangerousThisWeek, 1)
        XCTAssertEqual(s.headline, "Loupe spotted 2 risky sites this week")
        XCTAssertEqual(l.distinctSites(since: clock.addingTimeInterval(-3600)), 2)
        s = SpottedSummary.of(l.entries(), now: clock.addingTimeInterval(8 * 24 * 3600))
        XCTAssertNil(s.headline)
    }

    @MainActor
    func testTheStoreBadgeClearsWhenViewed() {
        let store = ProtectionStore(log: log(), recentStore: RecentChecksStore(dir: dir), defaults: UserDefaults(suiteName: "sp-\(UUID())")!,
                                    now: { [unowned self] in self.clock })
        XCTAssertEqual(store.unseenCount, 0)
        add(store.log, "a.example")
        store.reload()
        XCTAssertEqual(store.unseenCount, 1)
        XCTAssertEqual(store.summary.sitesThisWeek, 1)
        store.markSeen()
        XCTAssertEqual(store.unseenCount, 0)
    }

    // MARK: notifications

    private func notifier(_ center: FakeNotificationCenter, _ defaults: UserDefaults) -> SpottedNotifier {
        SpottedNotifier(center: center, defaults: defaults, now: { [unowned self] in self.clock })
    }

    func testDangerousNotifiesOncePerSitePerHour() async {
        let center = FakeNotificationCenter()
        let d = UserDefaults(suiteName: "sn-\(UUID())")!
        let n = notifier(center, d)
        let first = await n.notifyIfNeeded(domain: "paypa1-secure.com", host: "paypa1-secure.com", level: .dangerous, brand: "PayPal",
                                           reason: "Looks like PayPal", userContinued: false)
        XCTAssertTrue(first)
        XCTAssertEqual(center.posted.count, 1)
        XCTAssertEqual(center.posted[0].title, "Loupe blocked a fake PayPal page")
        XCTAssertTrue(center.posted[0].body.hasPrefix("paypa1-secure.com · Dangerous"))
        let again = await n.notifyIfNeeded(domain: "paypa1-secure.com", host: "paypa1-secure.com", level: .dangerous, brand: "PayPal", reason: nil, userContinued: false)
        XCTAssertFalse(again, "rate-limited per site")
        let other = await n.notifyIfNeeded(domain: "other.example", host: "other.example", level: .dangerous, brand: nil, reason: nil, userContinued: false)
        XCTAssertTrue(other)
        XCTAssertEqual(center.posted.last?.title, "Loupe blocked a dangerous site")
        clock += 61 * 60
        let later = await n.notifyIfNeeded(domain: "paypa1-secure.com", host: "paypa1-secure.com", level: .dangerous, brand: "PayPal", reason: nil, userContinued: false)
        XCTAssertTrue(later)
        XCTAssertEqual(center.posted.count, 3)
    }

    /// Suspicious sites notify by default (owner decision 2026-09-26: on-device features on); a user who turned
    /// the switch off is never notified about them.
    func testSuspiciousOnlyWithTheSwitch() async {
        let center = FakeNotificationCenter()
        let d = UserDefaults(suiteName: "sn-\(UUID())")!
        let n = notifier(center, d)
        XCTAssertTrue(ProtectionGroup.notifySuspicious(d), "never set: on")
        d.set(false, forKey: ProtectionGroup.Keys.notifySuspicious)
        let off = await n.notifyIfNeeded(domain: "s.example", host: "s.example", level: .suspicious, brand: nil, reason: nil, userContinued: false)
        XCTAssertFalse(off, "turned off by the user: stays off")
        d.set(true, forKey: ProtectionGroup.Keys.notifySuspicious)
        let on = await n.notifyIfNeeded(domain: "s.example", host: "s.example", level: .suspicious, brand: nil, reason: nil, userContinued: false)
        XCTAssertTrue(on)
        XCTAssertEqual(center.posted.first?.title, "Loupe warned you about a suspicious site")
        let safe = await n.notifyIfNeeded(domain: "ok.example", host: "ok.example", level: .safe, brand: nil, reason: nil, userContinued: false)
        XCTAssertFalse(safe)
    }

    func testNeverForASiteTheUserContinuedTo() async {
        let center = FakeNotificationCenter()
        let d = UserDefaults(suiteName: "sn-\(UUID())")!
        let n = notifier(center, d)
        let asked = await n.notifyIfNeeded(domain: "c.example", host: "c.example", level: .dangerous, brand: nil, reason: nil, userContinued: true)
        XCTAssertFalse(asked)
        n.markContinued(host: "d.example")
        XCTAssertTrue(n.continued(host: "d.example"))
        let after = await n.notifyIfNeeded(domain: "d.example", host: "d.example", level: .dangerous, brand: nil, reason: nil, userContinued: false)
        XCTAssertFalse(after)
        clock += 13 * 3600
        XCTAssertFalse(n.continued(host: "d.example"), "the choice lasts 12 hours")
        XCTAssertTrue(center.posted.isEmpty)
    }

    func testNothingPostsWithoutPermission() async {
        let center = FakeNotificationCenter()
        center.authorized = false
        let n = notifier(center, UserDefaults(suiteName: "sn-\(UUID())")!)
        let posted = await n.notifyIfNeeded(domain: "x.example", host: "x.example", level: .dangerous, brand: nil, reason: nil, userContinued: false)
        XCTAssertFalse(posted)
        XCTAssertTrue(center.posted.isEmpty)
    }
}
