import XCTest
import LoupeKit
@testable import Loupe

/// Check a link (2026-09-26): reading what the user pasted (URL normalisation, IDN / punycode,
/// defanged links, pasted messages), the verdict mapping, shorteners, what was sent, the recent
/// checks and the view model. No network: the model gets no online part, or a fake one.
@MainActor
final class LinkCheckTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("LinkCheck-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func norm(_ s: String) throws -> LinkInput.Normalized {
        switch LinkInput.normalize(s) {
        case .success(let n): return n
        case .failure(let p): throw XCTSkip("unexpected problem \(p) for \(s)")
        }
    }

    private func problem(_ s: String) -> LinkInput.Problem? {
        if case .failure(let p) = LinkInput.normalize(s) { return p }
        return nil
    }

    private func verdict(_ s: String) throws -> LinkVerdict {
        LinkChecker.verdict(try norm(s), online: nil, origin: .manual)
    }

    // MARK: normalisation

    func testNormalisesTypedAndPastedAddresses() throws {
        let bare = try norm("  PayPal.com  ")
        XCTAssertEqual(bare.url, "https://PayPal.com")
        XCTAssertEqual(bare.host, "paypal.com")
        XCTAssertTrue(bare.addedScheme)
        XCTAssertEqual(bare.registrable, "paypal.com")

        let full = try norm("HTTP://Login.Example.COM/Path/To?token=secret#frag")
        XCTAssertEqual(full.host, "login.example.com")
        XCTAssertFalse(full.addedScheme)
        XCTAssertEqual(full.displayURL, "http://login.example.com/Path/To", "no query or fragment kept for display")

        XCTAssertEqual(try norm("<https://example.org/a>.").host, "example.org")
        XCTAssertEqual(try norm("\"example.net\",").host, "example.net")
        XCTAssertEqual(try norm("hxxps://evil-example[.]com/login").url, "https://evil-example.com/login")
        XCTAssertEqual(try norm("//example.com/x").host, "example.com")
        XCTAssertEqual(try norm("example.com:8443/admin").host, "example.com")
    }

    func testAPastedMessageGivesItsFirstLink() throws {
        let n = try norm("Your parcel is waiting. Pay the fee: https://royalmail-redelivery.example-fees.top/pay?id=9 today!")
        XCTAssertEqual(n.host, "royalmail-redelivery.example-fees.top")
        XCTAssertEqual(try norm("Visit www.example.com for more").host, "www.example.com")
        XCTAssertEqual(try norm("see example.org please").host, "example.org")
    }

    func testRefusesWhatIsNotAWebLink() {
        XCTAssertEqual(problem(""), .empty)
        XCTAssertEqual(problem("   \n "), .empty)
        XCTAssertEqual(problem("mailto:someone@example.com"), .notWeb("mailto"))
        XCTAssertEqual(problem("javascript:alert(1)"), .notWeb("javascript"))
        XCTAssertEqual(problem("ftp://example.com/file"), .notWeb("ftp"))
        XCTAssertEqual(problem("tel:+441234567"), .notWeb("tel"))
        XCTAssertEqual(problem("hello"), .noHost)
        XCTAssertEqual(problem("hello there friend"), .noHost)
        XCTAssertEqual(problem(String(repeating: "a", count: LinkInput.maxLength + 1)), .tooLong)
        XCTAssertNotNil(LinkInput.Problem.noHost.message.range(of: "example.com"))
    }

    // MARK: international names

    func testUnicodeLookAlikeIsReadAsPunycodeAndFlaggedDangerous() throws {
        // "pаypal.com" with a Cyrillic а (U+0430).
        let n = try norm("https://p\u{0430}ypal.com/signin")
        XCTAssertEqual(n.host, "xn--pypal-4ve.com")
        XCTAssertEqual(n.unicodeHost, "p\u{0430}ypal.com")
        XCTAssertTrue(n.isInternational)
        let v = LinkChecker.verdict(n, online: nil, origin: .manual)
        XCTAssertEqual(v.level, .dangerous)
        XCTAssertEqual(v.brand, "PayPal")
        XCTAssertTrue(v.reasons.contains { $0.code == "homograph_brand" }, "\(v.reasons)")
        XCTAssertTrue(v.siteLine.contains("written as xn--pypal-4ve.com"))
    }

    func testTypedPunycodeGivesTheSameVerdict() throws {
        let v = try verdict("xn--pypal-4ve.com")
        XCTAssertEqual(v.level, .dangerous)
        XCTAssertEqual(v.unicodeHost, "p\u{0430}ypal.com")
        XCTAssertGreaterThanOrEqual(v.score, Int(SiteScoring.shared.DANGER_AT))
    }

    func testAnOrdinaryInternationalNameIsNotFlagged() throws {
        // Rule 2 of the formula: someone's ordinary site in Arabic script, not a look-alike.
        let v = try verdict("https://مثال.مصر/")
        XCTAssertEqual(v.level, .safe, "\(v.reasons)")
        XCTAssertTrue(v.host.hasPrefix("xn--"))
    }

    // MARK: verdicts

    func testVerdictLevelsMapTheFormula() {
        XCTAssertEqual(ProtectionLevel(formula: "danger"), .dangerous)
        XCTAssertEqual(ProtectionLevel(formula: "caution"), .suspicious)
        XCTAssertEqual(ProtectionLevel(formula: "safe"), .safe)
        XCTAssertEqual(ProtectionLevel(formula: "anything"), .safe)
        XCTAssertEqual(ProtectionLevel.safe.title, "No warning signs found", "the lowest level is never called safe")
        XCTAssertEqual(ProtectionLevel.suspicious.title, "Suspicious")
        XCTAssertEqual(ProtectionLevel.dangerous.title, "Dangerous")
        XCTAssertFalse(ProtectionLevel.safe.flagged)
    }

    func testLookAlikesAndBaitAreSuspiciousOrWorse() throws {
        let lookalike = try verdict("paypa1.com")
        XCTAssertTrue(lookalike.level.flagged)
        XCTAssertTrue(lookalike.reasons.contains { $0.code == "lookalike_brand" && $0.text.contains("PayPal") }, "\(lookalike.reasons)")
        let bait = try verdict("https://paypal-secure-login.com/verify")
        XCTAssertTrue(bait.level.flagged)
        XCTAssertTrue(bait.reasons.contains { $0.code == "brand_in_domain_bait" })
        let sub = try verdict("https://paypal.com.account-verify.example/")
        XCTAssertTrue(sub.reasons.contains { $0.code == "brand_domain_in_subdomain" }, "\(sub.reasons)")
        let at = try verdict("https://www.paypal.com@evil.example/")
        XCTAssertTrue(at.reasons.contains { $0.code == "userinfo_in_url" })
    }

    func testRealBrandAndOrdinarySitesHaveNoWarningSigns() throws {
        for s in ["https://www.paypal.com/signin", "www.bbc.co.uk/news", "https://example.com/"] {
            let v = try verdict(s)
            XCTAssertEqual(v.level, .safe, "\(s): \(v.reasons)")
            XCTAssertTrue(v.reasons.isEmpty, "\(s): \(v.reasons)")
        }
    }

    func testShortenersAndSuspiciousEndingsAreNamed() throws {
        let short = try verdict("bit.ly/3xYzAbc")
        XCTAssertTrue(short.reasons.contains { $0.code == "url_shortener" })
        XCTAssertEqual(short.level, .safe, "a shortener alone is a small thing noticed, not a warning")
        XCTAssertEqual(short.reasonsTitle, "Small things noticed")
        let tld = try verdict("https://parcel-fee.top/")
        XCTAssertTrue(tld.reasons.contains { $0.code == "suspicious_tld" && $0.text.contains(".top") }, "\(tld.reasons)")
    }

    func testWhatRanOnThePhoneAndWhatLeftIt() throws {
        let v = try verdict("example.com")
        XCTAssertEqual(v.onDevice.count, LinkChecker.onDeviceChecks.count)
        XCTAssertTrue(v.onDevice[0].contains("\(Brands.shared.BRANDS.count) well-known brands"))
        XCTAssertTrue(v.privacyLine.hasPrefix("0 bytes out"), v.privacyLine)

        var d = OnlineDisclosure()
        d.sent = [.init(what: "example.com", to: "the Loupe web helper"), .init(what: "example.com", to: "this iPhone's DNS resolver")]
        XCTAssertEqual(d.privacyLine, "Sent: the domain example.com only, to the Loupe web helper, this iPhone's DNS resolver.")
        d.sent = []
        d.conditional = ["Google Safe Browsing: prefixes only."]
        XCTAssertTrue(d.privacyLine.hasPrefix("Nothing about this link left your iPhone."))
    }

    // MARK: recent checks

    func testRecentChecksStoreAddsDedupesCapsAndClears() throws {
        let store = RecentChecksStore(dir: dir)
        XCTAssertTrue(store.all().isEmpty)
        let a = try verdict("https://a.example/x?token=1")
        store.add(a)
        store.add(try verdict("https://b.example/"))
        store.add(try verdict("https://a.example/x?token=2"))   // same address without the query: moves up
        XCTAssertEqual(store.all().map(\.host), ["a.example", "b.example"])
        let raw = try String(contentsOf: store.file, encoding: .utf8)
        XCTAssertFalse(raw.contains("token"), "queries are never stored")
        for i in 0..<60 { store.add(try verdict("https://site\(i).example/")) }
        XCTAssertEqual(store.all().count, RecentChecksStore.maxEntries)
        XCTAssertEqual(store.all().first?.host, "site59.example")
        store.remove(store.all()[0].id)
        XCTAssertEqual(store.all().first?.host, "site58.example")
        store.clear()
        XCTAssertTrue(store.all().isEmpty)
    }

    // MARK: the view model

    func testTheModelChecksKeepsRecentAndLogsFlaggedOnes() async throws {
        let store = ProtectionStore(log: SpottedLog(dir: dir), recentStore: RecentChecksStore(dir: dir), defaults: UserDefaults(suiteName: "lc-\(UUID())")!)
        var asked: [String] = []
        let model = LinkCheckModel(online: { url in asked.append(url); return (nil, .none) }, store: store)

        model.input = "   "
        let none = await model.check()
        XCTAssertNil(none)
        XCTAssertEqual(model.problem, LinkInput.Problem.empty.message)
        XCTAssertTrue(asked.isEmpty, "nothing is looked up for a bad input")

        model.paste("  xn--pypal-4ve.com  ")
        XCTAssertEqual(model.input, "xn--pypal-4ve.com")
        let v = await model.check()
        XCTAssertEqual(v?.level, .dangerous)
        XCTAssertEqual(asked, ["https://xn--pypal-4ve.com"])
        XCTAssertEqual(store.recent.count, 1)
        XCTAssertEqual(store.spotted.count, 1)
        XCTAssertEqual(store.spotted.first?.origin, .manual)
        XCTAssertEqual(store.unseenCount, 1)

        model.input = "https://example.com"
        let safe = await model.check()
        XCTAssertEqual(safe?.level, .safe)
        XCTAssertEqual(store.recent.count, 2)
        XCTAssertEqual(store.spotted.count, 1, "a verdict with no warning signs is never in Spotted")

        model.show(store.recent[1])
        XCTAssertEqual(model.verdict?.host, "xn--pypal-4ve.com")
        model.clear()
        XCTAssertNil(model.verdict)
        XCTAssertEqual(model.input, "")
    }

    func testTheModelShowsWhatTheOnlineChecksSent() async throws {
        let store = ProtectionStore(log: SpottedLog(dir: dir), recentStore: RecentChecksStore(dir: dir), defaults: UserDefaults(suiteName: "lc-\(UUID())")!)
        let model = LinkCheckModel(online: { _ in
            var d = OnlineDisclosure()
            d.sent = [.init(what: "example.com", to: "the Loupe web helper")]
            d.onlineNotes = ["Online · Loupe web helper · 1 domain with registration facts · fetched 2026-09-26T10:00:00Z"]
            return (OnlineContext(nowIso: "2026-09-26T10:00:00Z", facts: [:], feeds: nil, safeBrowsingHits: [], safeBrowsingFetchedAt: nil,
                                  feedsFetchedAt: nil, phishingDb: nil, dns: [:], dnsbl: [:], dnsblFetchedAt: nil), d)
        }, store: store)
        model.input = "https://www.example.com/page?x=1"
        let v = await model.check()
        XCTAssertEqual(v?.privacyLine, "Sent: the domain example.com only, to the Loupe web helper.")
        XCTAssertEqual(v?.disclosure.onlineNotes.count, 1)
    }

    func testTheLinkCheckOnlinePartSendsOnlyTheRegistrableDomain() async throws {
        let defaults = UserDefaults(suiteName: "lc-online-\(UUID())")!
        FakeOnline.reset()
        FakeOnline.handler = { _, _ in (200, Data(#"{"domain":"example.co.uk","sources":[]}"#.utf8)) }
        let group = UserDefaults(suiteName: "lc-group-\(UUID())")!
        let service = OnlineChecksService(defaults: defaults, session: OnlineChecksService.ephemeralSession(protocols: [FakeOnline.self]),
                                          key: MemoryKeyStoreForProtection(), dir: dir, groupDefaults: group)
        // Off: nothing is sent and nothing is looked up.
        let off = await service.linkContext(url: "https://login.shop.example.co.uk/a?b=c")
        XCTAssertNil(off.context)
        XCTAssertTrue(FakeOnline.requests.isEmpty)
        XCTAssertEqual(off.disclosure, .none)

        service.update(OnlinePhishingSettings(domainFacts: true))
        XCTAssertTrue(OnlinePhishingSettings.loadShared(group).domainFacts, "the switch is mirrored into the App Group")
        let on = await service.linkContext(url: "https://login.shop.example.co.uk/a?b=c")
        XCTAssertNotNil(on.context)
        XCTAssertEqual(FakeOnline.requests.count, 1)
        let body = String(decoding: FakeOnline.bodies[0], as: UTF8.self)
        XCTAssertEqual(body, #"{"domain":"example.co.uk"}"#)
        XCTAssertEqual(on.disclosure.sent, [.init(what: "example.co.uk", to: "the Loupe web helper")])

        // A page on a hosting platform is never looked up.
        FakeOnline.reset()
        let hosted = await service.linkContext(url: "https://someone.github.io/login")
        XCTAssertTrue(FakeOnline.requests.isEmpty)
        XCTAssertTrue(hosted.disclosure.sent.isEmpty)
    }
}

/// An in-memory key store for these tests.
final class MemoryKeyStoreForProtection: KeyStore {
    var value: String?
    func read() -> String? { value }
    func save(_ value: String) throws { self.value = value }
    func delete() throws { value = nil }
}
