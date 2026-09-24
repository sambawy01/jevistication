import CryptoKit
import XCTest
@testable import Loupe

/// Builders for v5 JSON replies (shapes from Google's v5 reference / discovery document).
enum SBFixtures {
    /// Golomb-Rice delta encoding (the inverse of the v5 "Local Database" decoding), k = 28.
    static func rice(_ values: [UInt32], k: Int = 28) -> [String: Any] {
        let v = values.sorted()
        guard let first = v.first else { return [:] }
        var bits: [Bool] = []
        for (a, b) in zip(v, v.dropFirst()) {
            let d = UInt64(b - a)
            for _ in 0..<(d >> UInt64(k)) { bits.append(true) }
            bits.append(false)
            for i in 0..<k { bits.append((d >> UInt64(i)) & 1 == 1) }
        }
        var bytes = [UInt8](repeating: 0, count: (bits.count + 7) / 8)
        for (i, bit) in bits.enumerated() where bit { bytes[i / 8] |= 1 << UInt8(i % 8) }
        return ["firstValue": first, "riceParameter": k, "entriesCount": v.count - 1, "encodedData": Data(bytes).base64EncodedString()]
    }

    static func hashList(_ name: String, _ prefixes: [UInt32], version: String = "djE=", partial: Bool = false,
                         removals: [UInt32] = [], wait: String? = "1800s", checksumOf: [UInt32]? = nil) -> [String: Any] {
        var o: [String: Any] = ["name": name, "version": version, "partialUpdate": partial,
                                "sha256Checksum": SafeBrowsingClient.checksum((checksumOf ?? prefixes).sorted()).base64EncodedString()]
        if !prefixes.isEmpty { o["additionsFourBytes"] = rice(prefixes) }
        if !removals.isEmpty { o["compressedRemovals"] = rice(removals, k: 3) }
        if let wait { o["minimumWaitDuration"] = wait }
        return o
    }

    static func batch(_ lists: [String: [UInt32]], wait: String? = "1800s") -> Data {
        let o = ["hashLists": SafeBrowsingClient.listNames.map { hashList($0, lists[$0] ?? [], wait: wait) }]
        return try! JSONSerialization.data(withJSONObject: o)
    }

    static func search(_ full: [Data], cache: String = "300s") -> Data {
        let o: [String: Any] = ["fullHashes": full.map { ["fullHash": $0.base64EncodedString(),
                                                          "fullHashDetails": [["threatType": "SOCIAL_ENGINEERING"]]] },
                                "cacheDuration": cache]
        return try! JSONSerialization.data(withJSONObject: o)
    }
}

/// Safe Browsing v5 local-list mode: canonicalisation (Google's vectors), expressions, Rice decoding,
/// prefix matching, full-hash confirmation, cache and minimumWaitDuration.
@MainActor
final class SafeBrowsingV5Tests: XCTestCase {
    private var dir: URL!
    private var clock = Date(timeIntervalSince1970: 1_790_000_000)
    private let key = "AIzaSyTESTKEY_0123456789abcdef"

    override func setUp() {
        FakeOnline.reset()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("SBv5-\(UUID().uuidString)")
    }
    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func client() -> SafeBrowsingClient {
        SafeBrowsingClient(dir: dir, session: OnlineChecksService.ephemeralSession(protocols: [FakeOnline.self]),
                           key: { [key] in key }, now: { [unowned self] in self.clock })
    }

    // MARK: canonicalisation — Google's published vectors

    func testCanonicalisationVectors() {
        let vectors: [(String, String)] = [
            ("http://host/%25%32%35", "http://host/%25"),
            ("http://host/%25%32%35%25%32%35", "http://host/%25%25"),
            ("http://host/%2525252525252525", "http://host/%25"),
            ("http://host/asdf%25%32%35asd", "http://host/asdf%25asd"),
            ("http://host/%%%25%32%35asd%%", "http://host/%25%25%25asd%25%25"),
            ("http://www.google.com/", "http://www.google.com/"),
            ("http://%31%36%38%2e%31%38%38%2e%39%39%2e%32%36/%2E%73%65%63%75%72%65/%77%77%77%2E%65%62%61%79%2E%63%6F%6D/",
             "http://168.188.99.26/.secure/www.ebay.com/"),
            ("http://195.127.0.11/uploads/%20%20%20%20/.verify/.eBaysecure=updateuserdataxplimnbqmn-xplmvalidateinfoswqpcmlx=hgplmcx/",
             "http://195.127.0.11/uploads/%20%20%20%20/.verify/.eBaysecure=updateuserdataxplimnbqmn-xplmvalidateinfoswqpcmlx=hgplmcx/"),
            ("http://host%23.com/%257Ea%2521b%2540c%2523d%2524e%25f%255E00%252611%252A22%252833%252944_55%252B",
             "http://host%23.com/~a!b@c%23d$e%25f^00&11*22(33)44_55+"),
            ("http://3279880203/blah", "http://195.127.0.11/blah"),
            ("http://www.google.com/blah/..", "http://www.google.com/"),
            ("www.google.com/", "http://www.google.com/"),
            ("www.google.com", "http://www.google.com/"),
            ("http://www.evil.com/blah#frag", "http://www.evil.com/blah"),
            ("http://www.GOOgle.com/", "http://www.google.com/"),
            ("http://www.google.com.../", "http://www.google.com/"),
            ("http://www.google.com/foo\tbar\rbaz\n2", "http://www.google.com/foobarbaz2"),
            ("http://www.google.com/q?", "http://www.google.com/q?"),
            ("http://www.google.com/q?r?", "http://www.google.com/q?r?"),
            ("http://www.google.com/q?r?s", "http://www.google.com/q?r?s"),
            ("http://evil.com/foo#bar#baz", "http://evil.com/foo"),
            ("http://evil.com/foo;", "http://evil.com/foo;"),
            ("http://evil.com/foo?bar;", "http://evil.com/foo?bar;"),
            ("http://notrailingslash.com", "http://notrailingslash.com/"),
            ("http://www.gotaport.com:1234/", "http://www.gotaport.com/"),
            ("  http://www.google.com/  ", "http://www.google.com/"),
            ("http:// leadingspace.com/", "http://%20leadingspace.com/"),
            ("http://%20leadingspace.com/", "http://%20leadingspace.com/"),
            ("%20leadingspace.com/", "http://%20leadingspace.com/"),
            ("https://www.securesite.com/", "https://www.securesite.com/"),
            ("http://host.com/ab%23cd", "http://host.com/ab%23cd"),
            ("http://host.com//twoslashes?more//slashes", "http://host.com/twoslashes?more//slashes"),
            // v5 host rules: IPv6 normalisation, IPv4-mapped and NAT64 to IPv4
            ("http://[2001:0db8:0000::1]/", "http://[2001:db8::1]/"),
            ("http://[::ffff:1.2.3.4]/", "http://1.2.3.4/"),
            ("http://[64:ff9b::1.2.3.4]/", "http://1.2.3.4/"),
        ]
        for (input, want) in vectors {
            XCTAssertEqual(SafeBrowsingURL.canonicalize(input), want, input)
        }
    }

    func testExpressionsMatchGooglesExamples() {
        XCTAssertEqual(Set(SafeBrowsingURL.expressions("http://a.b.com/1/2.html?param=1")),
                       ["a.b.com/1/2.html?param=1", "a.b.com/1/2.html", "a.b.com/", "a.b.com/1/",
                        "b.com/1/2.html?param=1", "b.com/1/2.html", "b.com/", "b.com/1/"])
        XCTAssertEqual(Set(SafeBrowsingURL.expressions("http://a.b.c.d.e.f.com/1.html")),
                       ["a.b.c.d.e.f.com/1.html", "a.b.c.d.e.f.com/", "c.d.e.f.com/1.html", "c.d.e.f.com/",
                        "d.e.f.com/1.html", "d.e.f.com/", "e.f.com/1.html", "e.f.com/", "f.com/1.html", "f.com/"])
        XCTAssertEqual(Set(SafeBrowsingURL.expressions("http://1.2.3.4/1/")), ["1.2.3.4/1/", "1.2.3.4/"])
        XCTAssertEqual(Set(SafeBrowsingURL.expressions("http://example.co.uk/1")), ["example.co.uk/1", "example.co.uk/"])
        XCTAssertEqual(SafeBrowsingURL.expressions("http://a.b.com/1/2.html?param=1").first, "a.b.com/1/2.html?param=1")
    }

    // MARK: Rice decoding — the worked example in "Local Database"

    func testRiceDecodingOfGooglesExample() throws {
        let data = Data([0x74, 0x00, 0xD2, 0x97, 0x1B, 0xED, 0x49, 0x74, 0x00])   // "t\000\322\227\033\355It\000"
        XCTAssertEqual(try RiceDelta.decode32(firstValue: 489_866_504, riceParameter: 30, entriesCount: 2, encoded: data),
                       [0x1d32c508, 0x291bc542, 0xf7a502e5])
        XCTAssertEqual(SafeBrowsingClient.prefix(SafeBrowsingClient.hash("a.example.com/")), 0x291bc542)
        XCTAssertThrowsError(try RiceDelta.decode32(firstValue: 1, riceParameter: 30, entriesCount: 5, encoded: Data([0x00])))
        let values: [UInt32] = [7, 900, 1 << 20, 0xFFFF_FFF0]
        XCTAssertEqual(try SafeBrowsingClient.rice(SBFixtures.rice(values)), values)
    }

    // MARK: updates

    func testPartialUpdateRemovalsThenAdditionsAndChecksum() {
        let old = SafeBrowsingClient.ListState(version: "djE=", prefixes: [10, 20, 30])
        let diff = SBFixtures.hashList("se-4b", [25], version: "djI=", partial: true, removals: [0, 2], checksumOf: [20, 25])
        XCTAssertEqual(SafeBrowsingClient.apply(diff, to: old), .init(version: "djI=", prefixes: [20, 25]))
        let bad = SBFixtures.hashList("se-4b", [25], partial: true, removals: [0], checksumOf: [1])
        XCTAssertNil(SafeBrowsingClient.apply(bad, to: old), "checksum mismatch drops the list")
        let outOfRange = SBFixtures.hashList("se-4b", [], partial: true, removals: [9], checksumOf: [])
        XCTAssertNil(SafeBrowsingClient.apply(outOfRange, to: old))
    }

    func testUpdateUsesBatchGetAndRespectsMinimumWait() async throws {
        let c = client()
        FakeOnline.handler = { _, _ in (200, SBFixtures.batch(["se-4b": [1, 2, 3]], wait: "600s")) }
        try await c.update()
        let q = try XCTUnwrap(URLComponents(url: FakeOnline.requests[0].url!, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(q.filter { $0.name == "names" }.map(\.value), ["se-4b", "mw-4b", "uws-4b"])
        XCTAssertTrue(q.filter { $0.name == "version" }.isEmpty, "first fetch sends no version")
        XCTAssertEqual(c.lists["se-4b"]?.prefixes, [1, 2, 3])

        try await c.update()
        XCTAssertEqual(FakeOnline.requests.count, 1, "within minimumWaitDuration: no request")
        clock.addTimeInterval(601)
        try await c.update()
        XCTAssertEqual(FakeOnline.requests.count, 2)
        let q2 = try XCTUnwrap(URLComponents(url: FakeOnline.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(q2.filter { $0.name == "version" }.map(\.value), ["djE=", "djE=", "djE="])
        // persisted across instances
        XCTAssertEqual(client().lists["se-4b"]?.prefixes, [1, 2, 3])
    }

    func testMissingWaitMeansFetchAgainButBounded() async throws {
        let c = client()
        FakeOnline.handler = { _, _ in (200, SBFixtures.batch([:], wait: nil)) }
        try await c.update()
        XCTAssertEqual(FakeOnline.requests.count, SafeBrowsingClient.maxChainedUpdates)
        try await c.update()
        XCTAssertEqual(FakeOnline.requests.count, SafeBrowsingClient.maxChainedUpdates, "then a short pause")
    }

    // MARK: lookups

    func testNoLocalPrefixHitSendsNothing() async throws {
        let c = client()
        FakeOnline.handler = { _, _ in (200, SBFixtures.batch(["se-4b": [42]])) }
        try await c.update()
        let hits = try await c.dangerous(["http://innocent.example/page"])
        XCTAssertTrue(hits.isEmpty)
        XCTAssertEqual(FakeOnline.requests.count, 1, "only the list download")
    }

    func testPrefixHitIsConfirmedByFullHashOnly() async throws {
        let c = client()
        let bad = SafeBrowsingClient.hash("evil.example/")
        let collide = SafeBrowsingClient.hash("other.example/")   // Google returns a different full hash
        FakeOnline.handler = { req, _ in
            req.url!.path.hasSuffix("batchGet") ? (200, SBFixtures.batch(["mw-4b": [SafeBrowsingClient.prefix(bad)]]))
                                                 : (200, SBFixtures.search([collide]))
        }
        try await c.update()
        let hits = try await c.dangerous(["http://evil.example/x/y.html?z=1"])
        XCTAssertTrue(hits.isEmpty, "a prefix hit without the matching full hash is safe")
        FakeOnline.handler = { _, _ in (200, SBFixtures.search([bad])) }
        clock.addTimeInterval(301)
        let hits2 = try await c.dangerous(["http://evil.example/x/y.html?z=1"])
        XCTAssertEqual(hits2, ["http://evil.example/x/y.html?z=1"])
    }

    func testCacheAnswersUntilCacheDurationExpires() async throws {
        let c = client()
        let bad = SafeBrowsingClient.hash("evil.example/")
        FakeOnline.handler = { req, _ in
            req.url!.path.hasSuffix("batchGet") ? (200, SBFixtures.batch(["se-4b": [SafeBrowsingClient.prefix(bad)]]))
                                                 : (200, SBFixtures.search([bad], cache: "300s"))
        }
        try await c.update()
        let url = "http://evil.example/"
        let first = try await c.dangerous([url])
        XCTAssertEqual(first, [url])
        XCTAssertEqual(FakeOnline.requests.count, 2)
        clock.addTimeInterval(200)
        let cached = try await c.dangerous([url])
        XCTAssertEqual(cached, [url])
        XCTAssertEqual(FakeOnline.requests.count, 2, "answered from the cache")
        clock.addTimeInterval(101)
        _ = try await c.dangerous([url])
        XCTAssertEqual(FakeOnline.requests.count, 3, "expired: asked again")
    }

    func testNegativeResultIsCachedToo() async throws {
        let c = client()
        let p = SafeBrowsingClient.prefix(SafeBrowsingClient.hash("maybe.example/"))
        FakeOnline.handler = { req, _ in
            req.url!.path.hasSuffix("batchGet") ? (200, SBFixtures.batch(["se-4b": [p]])) : (200, SBFixtures.search([]))
        }
        try await c.update()
        _ = try await c.dangerous(["http://maybe.example/"])
        _ = try await c.dangerous(["http://maybe.example/"])
        XCTAssertEqual(FakeOnline.requests.count, 2)
    }

    func testNoKeyNoRequest() async {
        let c = SafeBrowsingClient(dir: dir, session: OnlineChecksService.ephemeralSession(protocols: [FakeOnline.self]), key: { nil })
        do { try await c.update(); XCTFail("needs a key") } catch { XCTAssertEqual(error as? OnlineCheckError, .badKey) }
        XCTAssertEqual(FakeOnline.requests.count, 0)
    }
}
