import XCTest
import LoupeKit
@testable import Loupe

/// Owner decisions A and B (2026-09-24) on the phone: Phishing.Database (conditional GETs, a pause
/// between files, the sanity check, the atomic swap, the last good copy), the list defaults
/// (Phishing.Database on, OpenPhish off), and the v1.2 DNS facts / blocklists through a fake
/// resolver. No network and no real DNS: FakeOnline answers every request, a fake answers every
/// DNS question.
@MainActor
final class PhishingDatabaseTests: XCTestCase {
    private var defaults: UserDefaults!
    private var dir: URL!
    private final class Clock { var now = ISO8601DateFormatter().date(from: "2026-09-24T12:00:00Z")! }
    private var clock = Clock()
    private var pauses: [Double] = []

    /// Station's samples (loupe-kit/src/commonTest/fixtures/phishingdb, with their NOTICE).
    private static let fixtures: URL = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../loupe-kit/src/commonTest/fixtures/phishingdb").standardized

    private static let sample: [SourceItem] = {
        let root = SourcesService.bundledSample()!
        return try! SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                  zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                  limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
            .scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
    }()
    private var raws: [String: String] { MailTriageService.rawSources(Self.sample) }

    override func setUp() {
        FakeOnline.reset()
        defaults = UserDefaults(suiteName: "pdb-tests-\(UUID().uuidString)")!
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("LoupePdb-\(UUID().uuidString)")
        clock = Clock()
        pauses = []
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func service(resolver: DnsQuery? = nil) -> OnlineChecksService {
        let c = clock
        return OnlineChecksService(defaults: defaults, session: OnlineChecksService.ephemeralSession(protocols: [FakeOnline.self]),
                                   key: MemoryKeyStore(nil), dir: dir, now: { c.now }, resolver: resolver,
                                   pause: { [weak self] s in await MainActor.run { self?.pauses.append(s) } })
    }

    private func fixture(_ name: String) -> String { try! String(contentsOf: Self.fixtures.appendingPathComponent(name), encoding: .utf8) }

    /// A full-size ACTIVE body: Station's sample plus filler so it passes the 1,000-line minimum.
    private var linksActive: Data {
        Data((fixture("phishing-links-ACTIVE.txt") + (0..<1100).map { "https://filler\($0).example/p\n" }.joined()
              + "https://paypal.account-verify.example/login\n").utf8)
    }
    private var domainsActive: Data {
        Data((fixture("phishing-domains-ACTIVE.txt") + (0..<1100).map { "filler\($0).example\n" }.joined()).utf8)
    }

    private func serve(links: Data? = nil, domains: Data? = nil, newLinks: String = "", newDomains: String = "fresh-scam.example\n") {
        let l = links ?? linksActive, d = domains ?? domainsActive
        FakeOnline.handler = { req, _ in
            switch req.url?.lastPathComponent {
            case "phishing-links-ACTIVE.txt": return (200, l)
            case "phishing-domains-ACTIVE.txt": return (200, d)
            case "phishing-links-NEW-today.txt": return (200, Data(newLinks.utf8))
            case "phishing-domains-NEW-today.txt": return (200, Data(newDomains.utf8))
            default: return (200, Data("<!DOCTYPE html><html>redirect</html>".utf8))
            }
        }
        FakeOnline.headers = { req in
            ["Content-Type": "text/plain; charset=utf-8", "ETag": "\"\(req.url!.lastPathComponent)-v1\"",
             "Last-Modified": "Thu, 24 Sep 2026 09:30:22 GMT"]
        }
    }

    // MARK: defaults

    func testDefaultsPhishingDatabaseOnOpenPhishOffDnsOff() {
        let s = OnlinePhishingSettings.load(defaults)
        XCTAssertFalse(s.anyOn)
        XCTAssertFalse(s.feeds)
        XCTAssertTrue(s.phishingDb, "on by default whenever the lists are on")
        XCTAssertFalse(s.openPhish, "OpenPhish is off by default (non-commercial free feed)")
        XCTAssertEqual(s.refreshHours, 6)
        XCTAssertFalse(s.dnsFacts)
        XCTAssertFalse(s.dnsbl)
        XCTAssertEqual(s.lists, [])
        var on = s
        on.feeds = true
        XCTAssertEqual(on.lists, ["phishingdb"])
        // a phone that had the lists on before: OpenPhish is no longer implied
        defaults.set(true, forKey: OnlinePhishingSettings.keys.feeds)
        XCTAssertEqual(OnlinePhishingSettings.load(defaults).lists, ["phishingdb"])
        // the refresh setting is clamped to 1–168 hours
        let svc = service()
        var h = svc.settings
        h.refreshHours = 500
        svc.update(h)
        XCTAssertEqual(svc.settings.refreshHours, 168)
        h.refreshHours = 0
        svc.update(h)
        XCTAssertEqual(svc.settings.refreshHours, 1)
    }

    // MARK: downloading

    func testFirstDownloadIsOnePlainGetPerFileWithAPauseBetween() async throws {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true))
        serve()
        let maybe = await s.context(items: Self.sample, raws: raws)
        let ctx = try XCTUnwrap(maybe)
        XCTAssertEqual(FakeOnline.requests.map { $0.url!.absoluteString }, [
            "https://phish.co.za/latest/phishing-links-ACTIVE.txt", "https://phish.co.za/latest/phishing-domains-ACTIVE.txt",
            "https://phish.co.za/latest/phishing-links-NEW-today.txt", "https://phish.co.za/latest/phishing-domains-NEW-today.txt",
        ], "only the four list files; OpenPhish is off")
        XCTAssertTrue(FakeOnline.requests.allSatisfy { $0.httpMethod == "GET" && $0.value(forHTTPHeaderField: "If-None-Match") == nil })
        XCTAssertEqual(pauses, [3, 3, 3], "about 3 s between files")
        let pdb = try XCTUnwrap(ctx.phishingDb)
        XCTAssertEqual(pdb.match(url: "http://00000000000000000update.emy.ba")?.how, "url")
        XCTAssertEqual(pdb.match(url: "https://www.fresh-scam.example/")?.how, "domain", "the NEW file is in the index")
        XCTAssertTrue(s.status?.contains("Online · Phishing.Database list on this phone") ?? false, s.status ?? "")
        // matched on the phone, as the existing list signals
        let summary = MailTriage.shared.summariseOnline(items: Self.sample, raws: raws, corrections: [:], online: ctx)
        let paypal = try XCTUnwrap(summary.rows.first { $0.itemId.hasSuffix("phishing-paypal.eml") })
        XCTAssertTrue(paypal.signals.contains { $0.code == "link_phish_list" && $0.text.hasPrefix("Online · Phishing.Database") }, "\(paypal.signals.map(\.text))")
    }

    func testConditionalGetKeepsTheCopyOn304AndNewFilesAreHourly() async throws {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true))
        serve()
        _ = await s.context(items: Self.sample, raws: raws)
        FakeOnline.requests = []
        // within the hour: nothing is due
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 0)
        // an hour later: only the NEW files, conditionally
        clock.now = clock.now.addingTimeInterval(3601)
        FakeOnline.handler = { _, _ in (304, Data()) }
        let maybe = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.map { $0.url!.lastPathComponent }, ["phishing-links-NEW-today.txt", "phishing-domains-NEW-today.txt"])
        XCTAssertEqual(FakeOnline.requests.last?.value(forHTTPHeaderField: "If-None-Match"), "\"phishing-domains-NEW-today.txt-v1\"")
        XCTAssertEqual(FakeOnline.requests.last?.value(forHTTPHeaderField: "If-Modified-Since"), "Thu, 24 Sep 2026 09:30:22 GMT")
        XCTAssertEqual(maybe?.phishingDb?.match(url: "http://00000000000000000update.emy.ba")?.how, "url", "the copy is kept")
        // after the refresh period (6 h): the ACTIVE files too
        clock.now = clock.now.addingTimeInterval(6 * 3600)
        FakeOnline.requests = []
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 4)
    }

    func testABadDownloadKeepsTheLastGoodCopy() async throws {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true))
        serve()
        _ = await s.context(items: Self.sample, raws: raws)
        let good = try String(contentsOf: s.phishingDb.file("phishing-links-ACTIVE.txt"), encoding: .utf8)
        clock.now = clock.now.addingTimeInterval(7 * 3600)
        for (status, body, type) in [(200, "<!DOCTYPE html><html>Redirecting…</html>", "text/html"),        // the soft 404
                                     (200, "http://a.example/x\n", "text/plain"),                              // too few lines
                                     (403, "Forbidden", "text/html")] {                                         // the burst limit
            FakeOnline.handler = { _, _ in (status, Data(body.utf8)) }
            FakeOnline.headers = { _ in ["Content-Type": type] }
            clock.now = clock.now.addingTimeInterval(7 * 3600)
            let maybe = await s.context(items: Self.sample, raws: raws)
            XCTAssertTrue(s.status?.contains("the last good copy is kept") ?? false, s.status ?? "")
            XCTAssertEqual(try String(contentsOf: s.phishingDb.file("phishing-links-ACTIVE.txt"), encoding: .utf8), good)
            XCTAssertEqual(maybe?.phishingDb?.match(url: "http://00000000000000000update.emy.ba")?.how, "url")
            XCTAssertFalse(FileManager.default.fileExists(atPath: s.phishingDb.file("phishing-links-ACTIVE.txt.part").path))
        }
        XCTAssertEqual(s.phishingDb.state["links_active"]?.lastError, "refused")
    }

    func testTurningTheListOffDeletesItAndSendsNothing() async {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true))
        serve()
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertTrue(FileManager.default.fileExists(atPath: s.phishingDb.dir.path))
        s.update(OnlinePhishingSettings(feeds: true, phishingDb: false))
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.phishingDb.dir.path))
        FakeOnline.requests = []
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 0)
    }

    func testOpenPhishOnlyWhenTurnedOn() async {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true, phishingDb: false))
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertFalse(FakeOnline.requests.contains { $0.url == PhishingFeeds.openPhishURL })
    }

    // MARK: v1.2 DNS facts and blocklists (fake resolver)

    private final class FakeResolver: NSObject, DnsQuery {
        var asked: [String] = []
        let answers: [String: DnsAnswer]
        init(_ answers: [String: DnsAnswer]) { self.answers = answers }
        func query(name: String, type: String) -> DnsAnswer? {
            asked.append("\(name) \(type)")
            return answers["\(name) \(type)"] ?? DnsAnswer(rcode: 3, records: [], ad: false)
        }
    }

    func testDnsFactsAndBlocklistsGoThroughTheResolverOnly() async throws {
        let r = FakeResolver([
            "test.surbl.org.multi.surbl.org A": DnsAnswer(rcode: 0, records: ["127.0.0.254"], ad: false),
            "account-verify.example.multi.surbl.org A": DnsAnswer(rcode: 0, records: ["127.0.0.8"], ad: false),
            "account-verify.example A": DnsAnswer(rcode: 0, records: ["192.0.2.10"], ad: false),
            "account-verify.example MX": DnsAnswer(rcode: 0, records: ["10 mx.account-verify.example"], ad: false),
        ])
        let s = service(resolver: r)
        // off by default: no question at all
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertTrue(r.asked.isEmpty)
        s.update(OnlinePhishingSettings(dnsFacts: true))
        let maybe = await s.context(items: Self.sample, raws: raws)
        let ctx = try XCTUnwrap(maybe)
        XCTAssertEqual(FakeOnline.requests.count, 0, "no HTTP request: DNS only")
        XCTAssertFalse(r.asked.isEmpty)
        XCTAssertFalse(r.asked.contains { $0.contains("surbl") || $0.contains("spamhaus") || $0.contains("uribl") }, "blocklists stay off")
        for q in r.asked {
            let name = String(q.split(separator: " ")[0])
            let base = name.hasPrefix("_dmarc.") ? String(name.dropFirst(7)) : name.hasPrefix("default._bimi.") ? String(name.dropFirst(14)) : name
            XCTAssertEqual(OnlineSignals.shared.lookupDomain(host: base), base, "only ICANN registrable domains: \(q)")
        }
        XCTAssertEqual(ctx.dns["account-verify.example"]?.mx, true)
        XCTAssertTrue(s.status?.contains("Online · DNS (this phone's resolver)") ?? false, s.status ?? "")

        // the blocklists: the test point first, then the domain; a phish listing is a warning sign
        r.asked = []
        s.update(OnlinePhishingSettings(dnsbl: true, dnsblSpamhaus: false, dnsblSurbl: true, dnsblUribl: false))
        let maybe2 = await s.context(items: Self.sample, raws: raws)
        let ctx2 = try XCTUnwrap(maybe2)
        XCTAssertEqual(r.asked.first, "test.surbl.org.multi.surbl.org A")
        XCTAssertEqual(r.asked.filter { $0.hasPrefix("test.") }.count, 1, "the test point is cached for an hour")
        XCTAssertEqual(ctx2.dnsbl["account-verify.example"]?["surbl"]?.category, "phish")
        let check = SiteCheck.shared.checkUrl(url: "https://account-verify.example/login", online: ctx2)
        XCTAssertEqual(check.verdict.level, "danger")
        XCTAssertTrue(check.lines.contains { $0.contains("blocklist of phishing or malware domains") }, "\(check.lines)")
        XCTAssertEqual(check.linesTitle, "Warning signs")
    }

    func testAResolverThatAnswersNothingStopsEarly() async throws {
        final class Dead: NSObject, DnsQuery {
            var count = 0
            func query(name: String, type: String) -> DnsAnswer? { count += 1; return nil }
        }
        let dead = Dead()
        let s = service(resolver: dead)
        s.update(OnlinePhishingSettings(dnsFacts: true))
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(dead.count, 5, "one domain's five questions, then it stops")
        XCTAssertTrue(s.status?.contains("did not answer") ?? false, s.status ?? "")
    }

    // MARK: the DNS transport (no network)

    func testWireResolverChecksTheAnswerItGetsBack() {
        final class Echo: NSObject, DnsTransport {
            var sent: [UInt8] = []
            func send(packet: KotlinByteArray) -> KotlinByteArray? {
                sent = (0..<Int(packet.size)).map { UInt8(bitPattern: packet.get(index: Int32($0))) }
                return KotlinByteArray(size: 3)                    // garbage: never an answer
            }
        }
        let t = Echo()
        XCTAssertNil(WireResolver(transport: t).query(name: "example.com", type: "TXT"))
        XCTAssertEqual(t.sent[2] & 0x01, 0x01, "RD")
        XCTAssertEqual(t.sent[3] & 0x20, 0x20, "AD requested")
        XCTAssertTrue(t.sent.count > 12 + 13)
    }
}
