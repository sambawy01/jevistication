import CryptoKit
import Darwin
import Foundation
import LoupeKit

// MARK: - Google Safe Browsing API v5, local-list mode (the user's own key)
//
// Shapes checked against Google's v5 reference and discovery document (2026-09-24):
// - `GET v5/hashLists:batchGet?names=..&version=..` → `{"hashLists": [HashList]}` in request order.
//   HashList: `name`, `version` (bytes), `partialUpdate`, `compressedRemovals` and
//   `additionsFourBytes` (RiceDeltaEncoded32Bit: `firstValue`, `riceParameter`, `entriesCount`,
//   `encodedData`), `minimumWaitDuration` (duration), `sha256Checksum` (bytes).
// - `GET v5/hashes:search?hashPrefixes=..` (4-byte prefixes, at most 1000) →
//   `{"fullHashes": [{"fullHash", "fullHashDetails": [{"threatType"}]}], "cacheDuration"}`.
// - The "List URL Check Procedure": canonicalise, host-suffix/path-prefix expressions, SHA256, look up
//   4-byte prefixes in the cache, then the local lists, and only the prefixes that hit the local
//   lists (and are not cached) go to `hashes:search`. Every queried prefix is cached until
//   `now + cacheDuration`, found or not.
//
// PRODUCT.md §4a: nothing here runs unless the switch is on and the key is set; the key goes in the
// `X-Goog-Api-Key` header (never the URL); no URL, host or full hash is ever sent.

enum SafeBrowsingURL {
    /// Canonicalises [raw] per "URLs and Hashing" (v5). Returns nil when there is no host.
    static func canonicalize(_ raw: String) -> String? {
        var bytes = Array(raw.trimmingCharacters(in: .whitespaces).utf8).filter { $0 != 0x09 && $0 != 0x0D && $0 != 0x0A }
        if let hash = bytes.firstIndex(of: UInt8(ascii: "#")) { bytes.removeSubrange(hash...) }
        var s = bytes
        // scheme
        var scheme = Array("http".utf8)
        if let r = find(s, Array("://".utf8)), r > 0, s[..<r].allSatisfy({ isAlnum($0) || $0 == 0x2B || $0 == 0x2D || $0 == 0x2E }) {
            scheme = s[..<r].map(lower)
            s = Array(s[(r + 3)...])
        }
        s = unescapeFully(s)
        // authority, path, query
        let endAuth = s.firstIndex { $0 == UInt8(ascii: "/") || $0 == UInt8(ascii: "?") } ?? s.count
        var auth = Array(s[..<endAuth])
        var rest = Array(s[endAuth...])
        if let at = auth.lastIndex(of: UInt8(ascii: "@")) { auth = Array(auth[(at + 1)...]) }
        if auth.first == UInt8(ascii: "["), let close = auth.firstIndex(of: UInt8(ascii: "]")) {
            auth = Array(auth[...close])
        } else if let colon = auth.lastIndex(of: UInt8(ascii: ":")) {
            auth = Array(auth[..<colon])
        }
        guard let host = canonicalHost(auth), !host.isEmpty else { return nil }
        var query: [UInt8]?
        if let q = rest.firstIndex(of: UInt8(ascii: "?")) { query = Array(rest[(q + 1)...]); rest = Array(rest[..<q]) }
        let path = canonicalPath(rest)
        var out = scheme + Array("://".utf8) + escape(Array(host.utf8)) + escape(path)
        if let query { out += [UInt8(ascii: "?")] + escape(query) }
        return String(decoding: out, as: UTF8.self)
    }

    /// Up to 30 host-suffix / path-prefix expressions of the canonical URL, exact ones first.
    static func expressions(_ url: String) -> [String] {
        guard let canon = canonicalize(url), let sep = canon.range(of: "://") else { return [] }
        let afterScheme = canon[sep.upperBound...]
        let slash = afterScheme.firstIndex(of: "/") ?? afterScheme.endIndex
        let host = String(afterScheme[..<slash])
        let pathAndQuery = String(afterScheme[slash...])
        let path = pathAndQuery.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? "/"

        var hosts = [host]
        if !isIPLiteral(host), let reg = OriginFacts.shared.registrableDomain(host: host, publicSuffixes: PublicSuffix.shared.DEFAULT) {
            let extra = host.dropLast(reg.count).split(separator: ".").map(String.init)   // labels left of eTLD+1
            var suffix = reg
            var suffixes = [reg]
            for label in extra.reversed().prefix(3) { suffix = label + "." + suffix; suffixes.append(suffix) }
            for h in suffixes.reversed() where h != host { hosts.append(h) }
        }
        var paths = [pathAndQuery, path, "/"]
        var acc = "/"
        for comp in path.split(separator: "/").dropLast().prefix(3) { acc += comp + "/"; paths.append(acc) }
        var out: [String] = []
        for h in hosts { for p in paths where !out.contains(h + p) { out.append(h + p) } }
        return Array(out.prefix(30))
    }

    static func isIPLiteral(_ h: String) -> Bool { h.hasPrefix("[") || parseIPv4(h) != nil }

    // MARK: helpers

    private static func canonicalHost(_ raw: [UInt8]) -> String? {
        var h = String(decoding: raw, as: UTF8.self)
        if h.hasPrefix("[") && h.hasSuffix("]") {
            var a = in6_addr()
            guard inet_pton(AF_INET6, String(h.dropFirst().dropLast()), &a) == 1 else { return h.lowercased() }
            let b = withUnsafeBytes(of: a) { Array($0) }
            if b[0..<10].allSatisfy({ $0 == 0 }) && b[10] == 0xFF && b[11] == 0xFF { return b[12...].map(String.init).joined(separator: ".") }
            if b[0..<12] == [0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0][...] { return b[12...].map(String.init).joined(separator: ".") }
            var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            inet_ntop(AF_INET6, &a, &buf, socklen_t(buf.count))
            return "[" + String(cString: buf).lowercased() + "]"
        }
        while h.hasPrefix(".") { h.removeFirst() }
        while h.hasSuffix(".") { h.removeLast() }
        while h.contains("..") { h = h.replacingOccurrences(of: "..", with: ".") }
        if let v4 = parseIPv4(h) { return v4 }
        if h.unicodeScalars.contains(where: { $0.value > 127 && !CharacterSet.controlCharacters.contains($0) }),
           !h.unicodeScalars.contains(where: { $0.value <= 32 }),
           let idn = URLComponents(string: "http://" + h + "/")?.encodedHost, idn.allSatisfy(\.isASCII) {
            h = idn
        }
        return h.lowercased()
    }

    /// inet_aton-style IPv4: 1–4 parts, decimal / octal / hex, the last part filling the rest.
    static func parseIPv4(_ h: String) -> String? {
        let parts = h.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...4).contains(parts.count) else { return nil }
        var nums: [UInt64] = []
        for p in parts {
            let s = p.lowercased()
            let v: UInt64?
            if s.hasPrefix("0x") { v = s.count == 2 ? 0 : UInt64(s.dropFirst(2), radix: 16) }
            else if s.hasPrefix("0") && s.count > 1 { v = UInt64(s.dropFirst(), radix: 8) }
            else { v = UInt64(s, radix: 10) }
            guard let v, !s.isEmpty else { return nil }
            nums.append(v)
        }
        var value: UInt64 = 0
        for (i, n) in nums.dropLast().enumerated() { guard n <= 255 else { return nil }; value |= n << (8 * (3 - UInt64(i))) }
        let last = nums.last!
        guard last < (1 << (8 * UInt64(5 - nums.count))) else { return nil }
        value |= last
        return [24, 16, 8, 0].map { String((value >> $0) & 0xFF) }.joined(separator: ".")
    }

    private static func canonicalPath(_ raw: [UInt8]) -> [UInt8] {
        let text = String(decoding: raw, as: UTF8.self)
        if text.isEmpty { return Array("/".utf8) }
        var stack: [Substring] = []
        let segs = text.split(separator: "/", omittingEmptySubsequences: false)
        for seg in segs {
            switch seg {
            case "", ".": continue
            case "..": _ = stack.popLast()
            default: stack.append(seg)
            }
        }
        let last = segs.last ?? ""
        let trailing = last.isEmpty || last == "." || last == ".."
        var out = "/" + stack.joined(separator: "/")
        if trailing && !stack.isEmpty { out += "/" }
        // keep the original bytes where possible (non-UTF-8 input): rebuild only when changed
        return out == text ? raw : Array(out.utf8)
    }

    private static func unescapeFully(_ input: [UInt8]) -> [UInt8] {
        var s = input
        for _ in 0..<64 {
            var out: [UInt8] = []
            out.reserveCapacity(s.count)
            var i = 0, changed = false
            while i < s.count {
                if s[i] == 0x25, i + 2 < s.count, let a = hex(s[i + 1]), let b = hex(s[i + 2]) {
                    out.append(a << 4 | b); i += 3; changed = true
                } else { out.append(s[i]); i += 1 }
            }
            s = out
            if !changed { break }
        }
        return s
    }

    private static func escape(_ b: [UInt8]) -> [UInt8] {
        let digits = Array("0123456789ABCDEF".utf8)
        var out: [UInt8] = []
        for c in b {
            if c <= 32 || c >= 127 || c == UInt8(ascii: "#") || c == UInt8(ascii: "%") {
                out += [UInt8(ascii: "%"), digits[Int(c >> 4)], digits[Int(c & 15)]]
            } else { out.append(c) }
        }
        return out
    }

    private static func hex(_ c: UInt8) -> UInt8? {
        switch c {
        case 0x30...0x39: return c - 0x30
        case 0x41...0x46: return c - 0x37
        case 0x61...0x66: return c - 0x57
        default: return nil
        }
    }

    private static func isAlnum(_ c: UInt8) -> Bool { (0x30...0x39).contains(c) || (0x41...0x5A).contains(c) || (0x61...0x7A).contains(c) }
    private static func lower(_ c: UInt8) -> UInt8 { (0x41...0x5A).contains(c) ? c + 32 : c }
    private static func find(_ s: [UInt8], _ needle: [UInt8]) -> Int? {
        guard s.count >= needle.count else { return nil }
        for i in 0...(s.count - needle.count) where Array(s[i..<(i + needle.count)]) == needle { return i }
        return nil
    }
}

/// Golomb-Rice delta decoding of a `RiceDeltaEncoded32Bit` (v5 "Local Database").
enum RiceDelta {
    struct Malformed: Error {}

    static func decode32(firstValue: UInt32, riceParameter k: Int, entriesCount: Int, encoded: Data) throws -> [UInt32] {
        guard entriesCount >= 0 else { throw Malformed() }
        var out = [firstValue]
        if entriesCount == 0 { return out }
        guard (1...31).contains(k) else { throw Malformed() }
        let bytes = [UInt8](encoded)
        let total = bytes.count * 8
        var pos = 0
        func bit() throws -> UInt64 {
            guard pos < total else { throw Malformed() }
            defer { pos += 1 }
            return UInt64((bytes[pos >> 3] >> UInt8(pos & 7)) & 1)
        }
        var value = UInt64(firstValue)
        out.reserveCapacity(entriesCount + 1)
        for _ in 0..<entriesCount {
            var q: UInt64 = 0
            while try bit() == 1 { q += 1; if q > (1 << (32 - k)) { throw Malformed() } }
            var r: UInt64 = 0
            for i in 0..<k { r |= try bit() << UInt64(i) }
            value += (q << UInt64(k)) + r
            guard value <= UInt64(UInt32.max) else { throw Malformed() }
            out.append(UInt32(value))
        }
        return out
    }
}

final class SafeBrowsingClient {
    static let base = URL(string: "https://safebrowsing.googleapis.com/v5/")!
    /// v5 list names (the v4 MALWARE / SOCIAL_ENGINEERING / UNWANTED_SOFTWARE lists, 4-byte prefixes).
    static let listNames = ["se-4b", "mw-4b", "uws-4b"]
    /// Updates chained in one run when Google omits `minimumWaitDuration` (it has more to send).
    static let maxChainedUpdates = 4
    /// Safe Browsing lets a client extend a negative cache, never beyond 24 hours; Loupe does not.
    static let maxCache: TimeInterval = 24 * 3600

    struct ListState: Codable, Equatable {
        var version: String          // base64, opaque
        var prefixes: [UInt32]       // sorted, big-endian interpretation of the first 4 bytes
    }
    struct Stored: Codable {
        var lists: [String: ListState] = [:]
        var nextUpdateAt: Date?
    }
    struct CacheEntry { var expires: Date; var fullHashes: Set<Data> }

    private let dir: URL
    private let session: URLSession
    private let key: () -> String?
    private let now: () -> Date
    private(set) var stored = Stored()
    /// The `hashes:search` cache, per 4-byte prefix, in memory only.
    private(set) var cache: [UInt32: CacheEntry] = [:]

    var lists: [String: ListState] { stored.lists }

    init(dir: URL, session: URLSession, key: @escaping () -> String?, now: @escaping () -> Date = Date.init) {
        self.dir = dir
        self.session = session
        self.key = key
        self.now = now
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("lists.json"))   // the v4 client's lists
        if let d = try? Data(contentsOf: file), let s = try? JSONDecoder().decode(Stored.self, from: d) { stored = s }
    }

    private var file: URL { dir.appendingPathComponent("lists-v5.json") }

    private func get(_ path: String, _ items: [(String, String)]) async throws -> [String: Any] {
        guard let k = key(), !k.isEmpty else { throw OnlineCheckError.badKey }
        var comps = URLComponents(url: Self.base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_.~"))
        comps.percentEncodedQuery = items.isEmpty ? nil : items.map { n, v in
            n + "=" + (v.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")
        }.joined(separator: "&")
        var r = URLRequest(url: comps.url!, timeoutInterval: 30)
        r.httpMethod = "GET"
        r.setValue(k, forHTTPHeaderField: "X-Goog-Api-Key")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: r) } catch { throw OnlineCheckError.offline }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw OnlineCheckError.from(status: status, data: data) }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    /// Protobuf-JSON duration ("300s", "1.5s") in seconds.
    static func duration(_ any: Any?) -> TimeInterval? {
        guard let s = any as? String, s.hasSuffix("s"), let v = Double(s.dropLast()), v.isFinite, v >= 0 else { return nil }
        return v
    }

    private static func int(_ any: Any?) -> Int? {
        if let n = any as? NSNumber { return n.intValue }
        if let s = any as? String { return Int(s) }
        return nil
    }

    static func rice(_ any: Any?) throws -> [UInt32] {
        guard let o = any as? [String: Any] else { return [] }
        let first = UInt32(truncatingIfNeeded: int(o["firstValue"]) ?? 0)
        let data = (o["encodedData"] as? String).flatMap { Data(base64Encoded: $0) } ?? Data()
        return try RiceDelta.decode32(firstValue: first, riceParameter: int(o["riceParameter"]) ?? 0,
                                      entriesCount: int(o["entriesCount"]) ?? 0, encoded: data)
    }

    static func checksum(_ prefixes: [UInt32]) -> Data {
        var d = Data(capacity: prefixes.count * 4)
        for p in prefixes { withUnsafeBytes(of: p.bigEndian) { d.append(contentsOf: $0) } }
        return Data(SHA256.hash(data: d))
    }

    /// Applies one HashList to [old]; nil when the result fails the checksum or the diff is corrupt.
    static func apply(_ h: [String: Any], to old: ListState?) -> ListState? {
        let partial = h["partialUpdate"] as? Bool ?? false
        var prefixes = partial ? (old?.prefixes ?? []) : []
        do {
            if partial, h["compressedRemovals"] != nil {
                let idx = Set(try rice(h["compressedRemovals"]))
                guard idx.allSatisfy({ Int($0) < prefixes.count }) else { return nil }
                prefixes = prefixes.enumerated().filter { !idx.contains(UInt32($0.offset)) }.map(\.element)
            }
            if h["additionsFourBytes"] != nil { prefixes += try rice(h["additionsFourBytes"]) }
        } catch { return nil }
        prefixes.sort()
        if let sum = (h["sha256Checksum"] as? String).flatMap({ Data(base64Encoded: $0) }), sum != checksum(prefixes) { return nil }
        let version = h["version"] as? String ?? old?.version ?? ""
        return ListState(version: version, prefixes: prefixes)
    }

    /// `hashLists:batchGet`, only when `minimumWaitDuration` has passed. A list that fails its
    /// checksum is dropped and fetched in full on the next request.
    func update() async throws {
        if let next = stored.nextUpdateAt, now() < next { return }
        for _ in 0..<Self.maxChainedUpdates {
            var items = Self.listNames.map { ("names", $0) }
            for n in Self.listNames { if let v = stored.lists[n]?.version, !v.isEmpty { items.append(("version", v)) } }
            let resp = try await get("hashLists:batchGet", items)
            let got = resp["hashLists"] as? [[String: Any]] ?? []
            var corrupt = false
            var wait: TimeInterval?
            for (i, h) in got.enumerated() {
                let name = h["name"] as? String ?? (i < Self.listNames.count ? Self.listNames[i] : "")
                guard Self.listNames.contains(name) else { continue }
                if let next = Self.apply(h, to: stored.lists[name]) { stored.lists[name] = next }
                else { stored.lists[name] = nil; corrupt = true }
                if let w = Self.duration(h["minimumWaitDuration"]), w > 0 { wait = max(wait ?? 0, w) }
            }
            try save()
            if let wait, !corrupt {
                stored.nextUpdateAt = now().addingTimeInterval(wait)
                try save()
                return
            }
        }
        stored.nextUpdateAt = now().addingTimeInterval(60)
        try save()
    }

    private func save() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var target = file
        try JSONEncoder().encode(stored).write(to: target, options: .atomic)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? target.setResourceValues(values)
    }

    static func hash(_ s: String) -> Data { Data(SHA256.hash(data: Data(s.utf8))) }
    static func prefix(_ h: Data) -> UInt32 { h.prefix(4).reduce(0) { $0 << 8 | UInt32($1) } }

    private func inLocalLists(_ p: UInt32) -> Bool {
        stored.lists.values.contains { l in
            var lo = 0, hi = l.prefixes.count
            while lo < hi { let m = (lo + hi) / 2; if l.prefixes[m] < p { lo = m + 1 } else { hi = m } }
            return lo < l.prefixes.count && l.prefixes[lo] == p
        }
    }

    /// The URLs Google lists as dangerous. Cached answers first; then only prefixes that match the
    /// local lists go to `hashes:search` (4 bytes each), never a URL. No local match: nothing is sent.
    func dangerous(_ urls: [String]) async throws -> Set<String> {
        let t = now()
        cache = cache.filter { $0.value.expires > t }
        var out = Set<String>()
        var pending: [UInt32: [(String, Data)]] = [:]
        for u in urls {
            for e in SafeBrowsingURL.expressions(u) {
                let h = Self.hash(e), p = Self.prefix(h)
                if let c = cache[p] {
                    if c.fullHashes.contains(h) { out.insert(u) }
                    continue
                }
                if inLocalLists(p) { pending[p, default: []].append((u, h)) }
            }
        }
        if pending.isEmpty { return out }
        let prefixes = Array(pending.keys.sorted().prefix(1000))
        let items = prefixes.map { p -> (String, String) in
            var be = p.bigEndian
            return ("hashPrefixes", Data(bytes: &be, count: 4).base64EncodedString())
        }
        let resp = try await get("hashes:search", items)
        let life = min(Self.duration(resp["cacheDuration"]) ?? 300, Self.maxCache)
        let full = Set((resp["fullHashes"] as? [[String: Any]] ?? []).compactMap { ($0["fullHash"] as? String).flatMap { Data(base64Encoded: $0) } }
            .filter { $0.count == 32 })
        let expires = now().addingTimeInterval(life)
        for p in prefixes { cache[p] = CacheEntry(expires: expires, fullHashes: full.filter { Self.prefix($0) == p }) }
        for p in prefixes { for (u, h) in pending[p] ?? [] where full.contains(h) { out.insert(u) } }
        return out
    }

    func remove() {
        stored = Stored()
        cache = [:]
        try? FileManager.default.removeItem(at: dir)
    }
}
