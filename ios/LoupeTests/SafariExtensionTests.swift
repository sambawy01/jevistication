import XCTest
import LoupeKit
@testable import Loupe

/// Online lookups that record instead of going online.
final class FakeSafariOnline: SafariOnlineLookups {
    var factsAsked: [String] = []
    var blocklistsAsked: [String] = []
    var facts: (String) throws -> DomainFacts = { d in DomainFacts(domain: d, fetchedAt: nil, sources: [], created: nil, updated: nil, expires: nil,
                                                                    registrar: nil, firstSeen: nil, latestIssued: nil, count90d: nil, issuers: []) }
    var listed: [String: DnsblResult] = [:]
    func domainFacts(_ domain: String) async throws -> DomainFacts { factsAsked.append(domain); return try facts(domain) }
    func blocklists(_ domain: String, lists: [String], at: String) async -> [String: DnsblResult] { blocklistsAsked.append(domain); return listed }
}

/// Loupe for Safari's native side, driven directly (no Safari): the verdict for a website name, the
/// switches read from the App Group, what goes online (only the registrable domain, only when on),
/// the mapped Phishing.Database index, the Spotted log and notifications it writes, the cache, and
/// the time and memory one verdict takes.
final class SafariExtensionTests: XCTestCase {
    private var dir: URL!
    private var group: UserDefaults!
    private var center: FakeNotificationCenter!
    private var online: FakeSafariOnline!
    private var clock = ISO8601DateFormatter().date(from: "2026-09-26T10:00:00Z")!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("SafariExt-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        group = UserDefaults(suiteName: "safari-group-\(UUID().uuidString)")!
        center = FakeNotificationCenter()
        online = FakeSafariOnline()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func engine(lists: ProtectionLists? = nil) -> SafariVerdictEngine {
        let g = group!
        return SafariVerdictEngine(settings: { OnlinePhishingSettings.loadShared(g) },
                                   lists: lists ?? ProtectionLists(phishingDbDir: dir.appendingPathComponent("pdb"), feedsDir: dir.appendingPathComponent("feeds")),
                                   online: online, log: SpottedLog(dir: dir, now: { [unowned self] in self.clock }),
                                   notifier: SpottedNotifier(center: center, defaults: g, now: { [unowned self] in self.clock }),
                                   defaults: g, now: { [unowned self] in self.clock })
    }

    private func ask(_ e: SafariVerdictEngine, _ host: String, allowed: Bool = false, online: Bool = true) async -> [String: Any] {
        await e.handle(["type": "verdict", "host": host, "scheme": "https", "allowed": allowed, "online": online])
    }

    // MARK: settings from the App Group

    func testTheSwitchesAreReadFromTheAppGroupAndOffByDefault() {
        let s = OnlinePhishingSettings.loadShared(group)
        XCTAssertFalse(s.anyOn, "a phone where the app never wrote them reads every online check as off")
        XCTAssertFalse(s.domainFacts)
        XCTAssertFalse(s.feeds)
        OnlinePhishingSettings(domainFacts: true, feeds: true).save(group)
        let t = OnlinePhishingSettings.loadShared(group)
        XCTAssertTrue(t.domainFacts)
        XCTAssertTrue(t.feeds)
        XCTAssertTrue(t.phishingDb, "Phishing.Database is on whenever the lists are")
    }

    // MARK: verdicts

    func testALookAlikeIsDangerousLoggedAndNotifiesOnce() async {
        let e = engine()
        let r = await ask(e, "xn--pypal-4ve.com")
        XCTAssertEqual(r["ok"] as? Bool, true)
        XCTAssertEqual(r["level"] as? String, "dangerous")
        XCTAssertEqual(r["brand"] as? String, "PayPal")
        XCTAssertEqual(r["domain"] as? String, "p\u{0430}ypal.com")
        XCTAssertFalse((r["reasons"] as? [String] ?? []).isEmpty)
        XCTAssertEqual(r["privacy"] as? String, OnlineDisclosure.none.privacyLine(subject: "site"))
        let log = e.log.entries()
        XCTAssertEqual(log.count, 1)
        XCTAssertEqual(log[0].host, "xn--pypal-4ve.com")
        XCTAssertEqual(log[0].origin, .safari)
        XCTAssertEqual(center.posted.count, 1)
        XCTAssertEqual(center.posted[0].title, "Loupe blocked a fake PayPal page")

        let again = await ask(e, "xn--pypal-4ve.com")
        XCTAssertEqual(again["cached"] as? Bool, true)
        XCTAssertEqual(e.log.entries().count, 1)
        XCTAssertEqual(e.log.entries()[0].count, 1, "a cached answer is not a second visit")
        XCTAssertEqual(center.posted.count, 1)
    }

    func testANormalSiteGetsNoWarningAndNoLog() async {
        let e = engine()
        for host in ["www.bbc.co.uk", "example.com", "www.paypal.com"] {
            let r = await ask(e, host)
            XCTAssertEqual(r["level"] as? String, "safe", host)
        }
        XCTAssertTrue(e.log.entries().isEmpty)
        XCTAssertTrue(center.posted.isEmpty)
    }

    func testPrivateAndBadHosts() async {
        let e = engine()
        for host in ["localhost", "192.168.1.1", "router.local"] {
            let r = await ask(e, host)
            XCTAssertEqual(r["level"] as? String, "safe", host)
        }
        let bad = await e.handle(["type": "verdict", "host": "exa mple.com"])
        XCTAssertEqual(bad["ok"] as? Bool, false)
        let missing = await e.handle(["type": "verdict"])
        XCTAssertEqual(missing["error"] as? String, "bad_host")
        let unknown = await e.handle(["type": "nope"])
        XCTAssertEqual(unknown["error"] as? String, "unknown_type")
        XCTAssertEqual(SafariVerdictEngine.cleanHost("P\u{0430}YPAL.com."), "xn--pypal-4ve.com")
        XCTAssertNil(SafariVerdictEngine.cleanHost("evil.com/path"))
    }

    func testContinueIsRecordedAndSilencesNotifications() async {
        let e = engine()
        _ = await ask(e, "paypa1.com")
        let r = await e.handle(["type": "action", "host": "paypa1.com", "action": "continue"])
        XCTAssertEqual(r["ok"] as? Bool, true)
        XCTAssertEqual(e.log.entries().first?.action, .continued)
        XCTAssertTrue(e.notifier.continued(host: "paypa1.com"))
        _ = await e.handle(["type": "action", "host": "paypa1.com", "action": "back"])
        XCTAssertEqual(e.log.entries().first?.action, .wentBack)

        // Already allowed this session: no log entry, no notification. (The first ask above may have notified:
        // suspicious sites notify by default since 2026-09-26; what matters is nothing after continuing.)
        let before = center.posted.count
        let fresh = engine()
        _ = await ask(fresh, "xn--pypal-4ve.com", allowed: true)
        XCTAssertTrue(fresh.log.entries().isEmpty || fresh.log.entries().allSatisfy { $0.host != "xn--pypal-4ve.com" })
        XCTAssertEqual(center.posted.count, before, "never a notification for a site the user chose to continue to")
    }

    func testHelloRecordsWhenSafariLastAsked() async {
        let e = engine()
        XCTAssertEqual(group.double(forKey: ProtectionGroup.Keys.safariLastSeen), 0)
        let r = await e.handle(["type": "hello"])
        XCTAssertEqual(r["ok"] as? Bool, true)
        XCTAssertEqual(group.double(forKey: ProtectionGroup.Keys.safariLastSeen), clock.timeIntervalSince1970)
    }

    // MARK: what goes online

    func testWithTheSwitchesOffNothingIsLookedUp() async {
        let e = engine()
        _ = await ask(e, "shop.example.co.uk")
        _ = await ask(e, "xn--pypal-4ve.com")
        XCTAssertTrue(online.factsAsked.isEmpty)
        XCTAssertTrue(online.blocklistsAsked.isEmpty)
    }

    func testOnlineChecksSendTheRegistrableDomainOnlyAndInASecondStep() async {
        OnlinePhishingSettings(domainFacts: true, dnsbl: true).save(group)
        let e = engine()
        let first = await ask(e, "login.shop.example.co.uk", online: false)
        XCTAssertEqual(first["onlinePending"] as? Bool, true)
        XCTAssertTrue(online.factsAsked.isEmpty, "the first step never waits for the network")
        let second = await ask(e, "login.shop.example.co.uk", online: true)
        XCTAssertEqual(second["onlinePending"] as? Bool, false)
        XCTAssertEqual(online.factsAsked, ["example.co.uk"])
        XCTAssertEqual(online.blocklistsAsked, ["example.co.uk"])
        XCTAssertEqual(second["privacy"] as? String,
                       "Sent: the domain example.co.uk only, to the Loupe web helper, the blocklists Spamhaus DBL, SURBL, URIBL (through this iPhone's DNS resolver).")
        // Hosting platforms and private names are never looked up.
        online.factsAsked = []
        _ = await ask(e, "someone.github.io")
        _ = await ask(e, "nas.local")
        XCTAssertTrue(online.factsAsked.isEmpty)
    }

    func testANewDomainFromTheHelperCountsAndSwitchChangesEmptyTheCache() async {
        let e = engine()
        let before = await ask(e, "brand-new-shop.com")
        XCTAssertEqual(before["level"] as? String, "safe")
        OnlinePhishingSettings(domainFacts: true).save(group)
        online.facts = { d in DomainFacts(domain: d, fetchedAt: "2026-09-26T09:59:00Z", sources: ["rdap"], created: "2026-09-24T00:00:00Z", updated: nil,
                                          expires: nil, registrar: nil, firstSeen: nil, latestIssued: nil, count90d: nil, issuers: []) }
        let after = await ask(e, "brand-new-shop.com")
        XCTAssertEqual(after["cached"] as? Bool, false, "a change of switches empties the cache")
        XCTAssertTrue((after["reasons"] as? [String] ?? []).contains { $0.contains("less than a week ago") }, "\(after)")
        XCTAssertEqual(after["level"] as? String, "suspicious", "a young domain alone is never dangerous")
    }

    // MARK: the lists

    private func writeIndex(links: String, domains: String) -> URL {
        let pdb = dir.appendingPathComponent("pdb")
        try? FileManager.default.createDirectory(at: pdb, withIntermediateDirectories: true)
        let index = PhishingDb.shared.build(linkTexts: [links], domainTexts: [domains], listDate: "2026-09-26T06:00:00Z", maxEntries: 1000)
        XCTAssertTrue(PhishingDbFile.shared.write(index: index, path: pdb.appendingPathComponent(ProtectionGroup.phishingDbIndexName).path))
        return pdb
    }

    func testTheMappedIndexMatchesExactlyLikeLoupeKit() throws {
        let links = "https://evil.example/login?x=1\nhttp://www.bad-host.example/\nhttps://sub.phish.example/a\nhttps://docs.google.com/forms/d/abc"
        let domains = "listed-domain.example\nwww.other.example"
        let pdb = writeIndex(links: links, domains: domains)
        let kotlin = PhishingDb.shared.build(linkTexts: [links], domainTexts: [domains], listDate: "2026-09-26T06:00:00Z", maxEntries: 1000)
        let mapped = try XCTUnwrap(MappedPhishingIndex(file: pdb.appendingPathComponent(ProtectionGroup.phishingDbIndexName)))
        XCTAssertEqual(mapped.listDate, "2026-09-26T06:00:00Z")
        XCTAssertEqual(mapped.linkCount, Int(kotlin.linkCount))
        for url in ["https://evil.example/login?x=1", "https://evil.example/other", "https://bad-host.example/", "https://www.bad-host.example/",
                    "https://x.listed-domain.example/", "https://sub.phish.example/b", "https://example.com/", "https://other.example/",
                    "https://docs.google.com/forms/d/abc", "https://docs.google.com/forms/d/other"] {
            XCTAssertEqual(mapped.slice(for: url)?.match(url: url), kotlin.match(url: url), url)
        }
        XCTAssertNil(MappedPhishingIndex(data: Data("not an index".utf8)))
    }

    func testListedSitesAreFlaggedFromTheSharedIndexWhenTheListsAreOn() async throws {
        _ = writeIndex(links: "https://phishy-host.example/\n", domains: "evil-domain.example\n")
        let e = engine()
        let off = await ask(e, "phishy-host.example")
        XCTAssertEqual(off["level"] as? String, "safe", "lists off: the index is not read")
        OnlinePhishingSettings(feeds: true).save(group)
        let on = await ask(e, "phishy-host.example")
        XCTAssertNotEqual(on["level"] as? String, "safe", "\(on)")
        XCTAssertTrue((on["reasons"] as? [String] ?? []).contains { $0.contains("Phishing.Database") }, "\(on)")
        XCTAssertEqual(on["privacy"] as? String, OnlineDisclosure.none.privacyLine(subject: "site"), "matched on the phone: nothing sent")
        let domain = await ask(e, "deep.evil-domain.example")
        XCTAssertNotEqual(domain["level"] as? String, "safe", "\(domain)")
    }

    // MARK: the share sheet's fast path

    func testTheShareSheetVerdictUsesNoNetwork() throws {
        let lists = ProtectionLists(phishingDbDir: dir.appendingPathComponent("pdb"), feedsDir: dir.appendingPathComponent("feeds"))
        guard case .success(let n) = LinkInput.normalize("Check this https://p\u{0430}ypal.com/verify now") else { return XCTFail() }
        let v = DeviceLinkCheck.verdict(n, origin: .shared, settings: OnlinePhishingSettings(domainFacts: true), lists: lists)
        XCTAssertEqual(v.level, .dangerous)
        XCTAssertEqual(v.origin, .shared)
        XCTAssertTrue(v.disclosure.sent.isEmpty, "the share sheet never goes online")
    }

    // MARK: time and memory

    /// Measures one verdict: the first (cold: LoupeKit's brand list and PSL load) and then 300 fresh
    /// website names. Printed for the report; the budget is generous for CI simulators.
    func testTimeAndMemoryPerVerdict() async {
        let e = engine()
        let before = SafariVerdictEngine.footprintMB()
        let t0 = DispatchTime.now().uptimeNanoseconds
        _ = await ask(e, "cold-start.example")
        let cold = Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e6
        var times: [Double] = []
        let hosts = (0..<300).map { i in ["shop\(i).example.com", "paypa\(i)-login.top", "xn--pypal-4ve.com\(i).example", "news\(i).bbc.co.uk"][i % 4] }
        for h in hosts {
            let r = await ask(e, h)
            times.append(r["ms"] as? Double ?? 0)
        }
        let sorted = times.sorted()
        let p50 = sorted[sorted.count / 2], p95 = sorted[Int(Double(sorted.count) * 0.95)]
        let cached = await ask(e, hosts[0])
        let after = SafariVerdictEngine.footprintMB()
        print("MEASURE safari-verdict cold=\(String(format: "%.1f", cold))ms p50=\(String(format: "%.2f", p50))ms p95=\(String(format: "%.2f", p95))ms cached=\(cached["ms"] ?? -1)ms footprint before=\(String(format: "%.1f", before))MB after=\(String(format: "%.1f", after))MB delta=\(String(format: "%.1f", after - before))MB")
        XCTAssertLessThan(p95, 50, "one verdict stays well under Safari's patience")
        XCTAssertEqual(cached["cached"] as? Bool, true)
    }
}
