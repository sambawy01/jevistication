import CryptoKit
import XCTest
import LoupeKit
@testable import Loupe

/// A fake network: records every request and answers from a handler. Nothing reaches the internet.
final class FakeOnline: URLProtocol {
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var bodies: [Data] = []
    nonisolated(unsafe) static var handler: (URLRequest, Data) -> (Int, Data) = { _, _ in (500, Data()) }

    static func reset() { requests = []; bodies = []; handler = { _, _ in (500, Data()) } }

    static func read(_ stream: InputStream) -> Data {
        stream.open(); defer { stream.close() }
        var out = Data()
        var buf = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable { let n = stream.read(&buf, maxLength: buf.count); if n <= 0 { break }; out.append(buf, count: n) }
        return out
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let body = request.httpBody ?? request.httpBodyStream.map(Self.read) ?? Data()
        Self.requests.append(request)
        Self.bodies.append(body)
        let (status, data) = Self.handler(request, body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

/// The opt-in online phishing checks (decision C): nothing is sent while off; only ICANN registrable
/// domains go to the helper; 404 / 429 / `sources: []` are handled; lists are matched on the phone;
/// Safe Browsing needs the user's key and sends hash prefixes, never a URL.
@MainActor
final class OnlineChecksTests: XCTestCase {
    private var defaults: UserDefaults!
    private var dir: URL!
    private let now = ISO8601DateFormatter().date(from: "2026-09-24T12:00:00Z")!

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
        defaults = UserDefaults(suiteName: "online-tests-\(UUID().uuidString)")!
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeOnline-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func service(key: String? = nil) -> OnlineChecksService {
        OnlineChecksService(defaults: defaults, session: OnlineChecksService.ephemeralSession(protocols: [FakeOnline.self]),
                            key: MemoryKeyStore(key), dir: dir, now: { [now] in now })
    }

    private func facts(_ domain: String, created: String = "2026-09-22T00:00:00Z", sources: [String] = ["rdap", "crtsh"]) -> Data {
        let o: [String: Any] = ["domain": domain, "fetched_at": "2026-09-24T11:59:00Z", "sources": sources,
                                "registration": ["created": created, "updated": NSNull(), "expires": "2027-09-22", "registrar": "R", "rdap_server": "x"],
                                "certificates": ["first_seen": "2026-09-23T00:00:00Z", "latest_issued": "2026-09-23T00:00:00Z", "count_90d": 1, "issuers": ["R10"]],
                                "errors": []]
        return try! JSONSerialization.data(withJSONObject: o)
    }

    private func domainOf(_ body: Data) -> String? {
        (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["domain"] as? String
    }

    // MARK: off by default: zero requests

    func testNothingIsSentWhileEverySwitchIsOff() async {
        let s = service(key: "AIzaSyTESTKEY_0123456789abcdef")
        XCTAssertFalse(s.settings.anyOn, "off by default")
        let ctx = await s.context(items: Self.sample, raws: raws)
        XCTAssertNil(ctx)
        // the whole mail triage with the checks off: no request at all
        let ledger = LedgerService(home: dir)
        let mail = MailTriageService(ledger: ledger, items: { Self.sample }, online: s)
        await mail.run()
        XCTAssertNil(mail.onlineStatus)
        XCTAssertTrue(mail.rows.first!.phishing)
        XCTAssertEqual(FakeOnline.requests.count, 0)
    }

    // MARK: domain facts

    func testDomainFactsSendOnlyTheIcannRegistrableDomain() async throws {
        let s = service()
        s.update(OnlinePhishingSettings(domainFacts: true))
        FakeOnline.handler = { [unowned self] req, body in (200, self.facts(self.domainOf(body) ?? "")) }
        let maybe = await s.context(items: Self.sample, raws: raws)
        let ctx = try XCTUnwrap(maybe)
        XCTAssertFalse(FakeOnline.requests.isEmpty)
        var installs = Set<String>()
        for (r, b) in zip(FakeOnline.requests, FakeOnline.bodies) {
            XCTAssertEqual(r.httpMethod, "POST")
            XCTAssertEqual(r.url?.path, "/v1/domain-facts")
            XCTAssertEqual(r.url?.host, HelperEndpoint.base.host)
            let o = try XCTUnwrap(try JSONSerialization.jsonObject(with: b) as? [String: Any])
            XCTAssertEqual(Array(o.keys), ["domain"], "only the domain is sent")
            let d = try XCTUnwrap(o["domain"] as? String)
            XCTAssertEqual(OnlineSignals.shared.lookupDomain(host: d), d, "an ICANN registrable domain")
            XCTAssertNil(r.value(forHTTPHeaderField: "Authorization"))
            installs.insert(r.value(forHTTPHeaderField: "X-Loupe-Install") ?? "")
        }
        XCTAssertEqual(installs.count, FakeOnline.requests.count, "a fresh random id per request")
        let asked = Set(FakeOnline.bodies.compactMap(domainOf))
        XCTAssertTrue(asked.contains("paypa1-secure.example"))
        XCTAssertTrue(asked.contains("account-verify.example"))
        XCTAssertFalse(asked.contains { $0.hasSuffix("paypal.com") || $0 == "gmail.com" }, "\(asked)")
        XCTAssertEqual(ctx.facts.count, asked.count)
        XCTAssertTrue(s.status?.contains("Online · Loupe web helper") ?? false, s.status ?? "")

        // the facts reach the shared formula, labelled Online
        let summary = MailTriage.shared.summariseOnline(items: Self.sample, raws: raws, corrections: [:], online: ctx)
        let paypal = try XCTUnwrap(summary.rows.first { $0.itemId.hasSuffix("phishing-paypal.eml") })
        let online = paypal.signals.filter { $0.kind == "online" }
        XCTAssertTrue(online.contains { $0.code == "sender_domain_new_week" }, "\(paypal.signals.map(\.code))")
        XCTAssertTrue(online.allSatisfy { $0.text.hasPrefix("Online · Loupe web helper") })

        // a second run within 6 hours is served from memory
        let before = FakeOnline.requests.count
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, before)
    }

    func testA404SaysNotAvailableYetAndStops() async {
        let s = service()
        s.update(OnlinePhishingSettings(domainFacts: true))
        FakeOnline.handler = { _, _ in (404, Data(#"{"error":{"code":"NOT_FOUND"}}"#.utf8)) }
        let ctx = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 1)
        XCTAssertTrue(s.status?.contains("online checks not available yet") ?? false, s.status ?? "")
        XCTAssertEqual(ctx?.facts.count, 0)
    }

    func testA429BacksOff() async {
        let s = service()
        s.update(OnlinePhishingSettings(domainFacts: true))
        FakeOnline.handler = { _, _ in (429, Data(#"{"error":{"code":"PROVIDER_RATE_LIMITED"}}"#.utf8)) }
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 1)
        XCTAssertTrue(s.status?.contains("rate limited") ?? false, s.status ?? "")
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 1, "no request while backing off")
    }

    func testEmptySourcesAreNeverCountedAsChecked() async throws {
        let s = service()
        s.update(OnlinePhishingSettings(domainFacts: true))
        FakeOnline.handler = { [unowned self] _, body in (200, self.facts(self.domainOf(body) ?? "", sources: [])) }
        let maybe = await s.context(items: Self.sample, raws: raws)
        let ctx = try XCTUnwrap(maybe)
        XCTAssertTrue(s.status?.contains("0 domains with registration facts") ?? false, s.status ?? "")
        XCTAssertTrue(ctx.facts.values.allSatisfy { !$0.hasFacts })
        let summary = MailTriage.shared.summariseOnline(items: Self.sample, raws: raws, corrections: [:], online: ctx)
        XCTAssertTrue(summary.rows.flatMap(\.signals).allSatisfy { $0.kind != "online" })
    }

    // MARK: phishing lists

    func testListsAreDownloadedOnceAndMatchedOnThePhone() async throws {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true))
        FakeOnline.handler = { req, _ in
            req.url == PhishingFeeds.openPhishURL ? (200, Data("http://paypal.account-verify.example/login\nhttps://other.example/x\n".utf8)) : (404, Data())
        }
        let maybe = await s.context(items: Self.sample, raws: raws)
        let ctx = try XCTUnwrap(maybe)
        XCTAssertEqual(FakeOnline.requests.map(\.url), [PhishingFeeds.openPhishURL], "only the list itself is fetched")
        XCTAssertEqual(FakeOnline.requests.first?.httpMethod, "GET")
        let summary = MailTriage.shared.summariseOnline(items: Self.sample, raws: raws, corrections: [:], online: ctx)
        let paypal = try XCTUnwrap(summary.rows.first { $0.itemId.hasSuffix("phishing-paypal.eml") })
        XCTAssertTrue(paypal.signals.contains { $0.code == "link_phish_list" && $0.text.hasPrefix("Online · OpenPhish") }, "\(paypal.signals.map(\.text))")
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 1, "refreshed at most every 12 hours")
    }

    // MARK: Google Safe Browsing

    func testSafeBrowsingNeedsTheUsersKey() async {
        let s = service(key: nil)
        s.update(OnlinePhishingSettings(safeBrowsing: true))
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertEqual(FakeOnline.requests.count, 0)
        XCTAssertTrue(s.status?.contains("add your own API key") ?? false)
    }

    func testSafeBrowsingSendsOnlyHashPrefixes() async throws {
        let key = "AIzaSyTESTKEY_0123456789abcdef"
        let s = service(key: key)
        s.update(OnlinePhishingSettings(safeBrowsing: true))
        let url = "http://paypal.account-verify.example/login"
        let full = SafeBrowsingClient.hash("paypal.account-verify.example/login")
        FakeOnline.handler = { req, _ in
            if req.url!.path.hasSuffix("hashLists:batchGet") {
                return (200, SBFixtures.batch(["se-4b": [SafeBrowsingClient.prefix(full)], "mw-4b": [], "uws-4b": []]))
            }
            return (200, SBFixtures.search([full]))
        }
        let maybe = await s.context(items: Self.sample, raws: raws)
        let ctx = try XCTUnwrap(maybe)
        XCTAssertEqual(ctx.safeBrowsingHits, [url])
        XCTAssertEqual(FakeOnline.requests.map { $0.url!.path }, ["/v5/hashLists:batchGet", "/v5/hashes:search"])
        for (r, b) in zip(FakeOnline.requests, FakeOnline.bodies) {
            XCTAssertEqual(r.url?.host, "safebrowsing.googleapis.com")
            XCTAssertEqual(r.httpMethod, "GET")
            XCTAssertEqual(r.value(forHTTPHeaderField: "X-Goog-Api-Key"), key)
            XCTAssertFalse(r.url!.absoluteString.contains(key), "the key is never in the URL")
            XCTAssertTrue(b.isEmpty)
            let text = r.url!.absoluteString.lowercased()
            XCTAssertFalse(text.contains("paypal") || text.contains("example"), "no URL or host is ever sent: \(text)")
        }
        let q = try XCTUnwrap(URLComponents(url: FakeOnline.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(q.map(\.name), ["hashPrefixes"], "only the one prefix that hit the local list")
        XCTAssertEqual(Data(base64Encoded: q[0].value!), full.prefix(4))
        let summary = MailTriage.shared.summariseOnline(items: Self.sample, raws: raws, corrections: [:], online: ctx)
        let paypal = try XCTUnwrap(summary.rows.first { $0.itemId.hasSuffix("phishing-paypal.eml") })
        XCTAssertTrue(paypal.signals.contains { $0.code == "link_safe_browsing" })
    }

    func testSafeBrowsingOffSendsNothingEvenWithAKey() async {
        let s = service(key: "AIzaSyTESTKEY_0123456789abcdef")
        s.update(OnlinePhishingSettings(safeBrowsing: false))
        let ctx = await s.context(items: Self.sample, raws: raws)
        XCTAssertNil(ctx)
        XCTAssertEqual(FakeOnline.requests.count, 0)
    }

    func testTurningASwitchOffForgetsItsData() async {
        let s = service()
        s.update(OnlinePhishingSettings(feeds: true))
        FakeOnline.handler = { _, _ in (200, Data("http://x.example/\n".utf8)) }
        _ = await s.context(items: Self.sample, raws: raws)
        XCTAssertNotNil(s.feeds.fetchedAt("openphish"))
        s.update(OnlinePhishingSettings())
        XCTAssertNil(s.feeds.fetchedAt("openphish"))
        XCTAssertEqual(s.status, "Online checks are off: nothing is sent.")
    }
}
