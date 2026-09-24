import CryptoKit
import Foundation
import LoupeKit

/// Opt-in online phishing checks (owner decision C, 2026-09-24), bound by PRODUCT.md §4a: **off by
/// default**, each source with its own switch, every result labelled "Online" with its source and
/// fetch time, the minimum sent, and everything works with them off. The scoring is LoupeKit's shared
/// formula (`OnlineSignals`, docs/PHISHING-FORMULA.md §5); this file only fetches.
///
/// - Domain facts: `POST {helper}/v1/domain-facts {"domain": d}` on loupe-web-helper, for the ICANN
///   registrable domain only (PRIVATE suffixes off; hosting-platform pages are never sent). A 404 means
///   the helper has not deployed the route: "online checks not available yet".
/// - Phishing lists: OpenPhish's community feed (and PhishTank's keyless list, its own switch),
///   downloaded to the phone and matched on the phone. Nothing per link leaves it.
/// - Google Safe Browsing (Update API v4) with the user's own key from the Keychain: a local list of
///   hash prefixes; only on a local match are 4-byte prefixes sent to Google, never a URL.
struct OnlinePhishingSettings: Equatable {
    var domainFacts = false
    var feeds = false
    var phishTank = false
    var safeBrowsing = false

    var anyOn: Bool { domainFacts || feeds || safeBrowsing }

    static let keys = (domainFacts: "online.phishing.domainFacts", feeds: "online.phishing.feeds",
                       phishTank: "online.phishing.phishtank", safeBrowsing: "online.phishing.safeBrowsing")

    static func load(_ d: UserDefaults) -> OnlinePhishingSettings {
        OnlinePhishingSettings(domainFacts: d.bool(forKey: keys.domainFacts), feeds: d.bool(forKey: keys.feeds),
                               phishTank: d.bool(forKey: keys.phishTank), safeBrowsing: d.bool(forKey: keys.safeBrowsing))
    }

    func save(_ d: UserDefaults) {
        d.set(domainFacts, forKey: Self.keys.domainFacts)
        d.set(feeds, forKey: Self.keys.feeds)
        d.set(phishTank, forKey: Self.keys.phishTank)
        d.set(safeBrowsing, forKey: Self.keys.safeBrowsing)
    }
}

enum OnlineCheckError: Error, Equatable {
    /// The helper answered 404: the route is not deployed yet.
    case notAvailableYet
    /// 429 `PROVIDER_RATE_LIMITED` (or any 429): back off.
    case rateLimited
    case offline
    case badKey
    case unexpected(String)

    static func from(status: Int, data: Data) -> OnlineCheckError {
        switch status {
        case 404: return .notAvailableYet
        case 429: return .rateLimited
        case 400, 401, 403: return .badKey
        default:
            let code = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]).flatMap { ($0?["error"] as? [String: Any])?["code"] as? String }
            return .unexpected(code ?? "HTTP \(status)")
        }
    }
}

// MARK: - Domain facts (loupe-web-helper)

final class DomainFactsClient {
    static let timeout: TimeInterval = 15
    let base: URL
    private let session: URLSession

    init(base: URL = HelperEndpoint.base, session: URLSession) {
        self.base = base
        self.session = session
    }

    /// The request for one domain: the JSON body `{"domain": d}` and nothing about the user. The
    /// helper's rate limiter wants an `X-Loupe-Install`; a fresh random UUID per request means two
    /// lookups cannot be linked to one phone.
    static func request(domain: String, base: URL = HelperEndpoint.base) -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("v1/domain-facts"), timeoutInterval: timeout)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue(UUID().uuidString, forHTTPHeaderField: "X-Loupe-Install")
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["domain": domain], options: [.sortedKeys])
        return r
    }

    func facts(for domain: String) async throws -> DomainFacts {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: Self.request(domain: domain, base: base)) }
        catch { throw OnlineCheckError.offline }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw OnlineCheckError.from(status: status, data: data) }
        do { return try OnlineSignals.shared.parseDomainFacts(json: String(decoding: data, as: UTF8.self), domain: domain) }
        catch { throw OnlineCheckError.unexpected("The helper sent a reply this version cannot read.") }
    }
}

// MARK: - Phishing lists (downloaded, matched on the phone)

final class PhishingFeeds {
    static let openPhishURL = URL(string: "https://raw.githubusercontent.com/openphish/public_feed/refs/heads/main/feed.txt")!
    static let phishTankURL = URL(string: "https://data.phishtank.com/data/online-valid.json")!
    static let refreshAfter: TimeInterval = 12 * 3600
    static let maxEntries = 400_000

    private let dir: URL
    private let session: URLSession
    private let now: () -> Date

    init(dir: URL, session: URLSession, now: @escaping () -> Date = Date.init) {
        self.dir = dir
        self.session = session
        self.now = now
    }

    private func file(_ list: String) -> URL { dir.appendingPathComponent("\(list).txt") }

    func fetchedAt(_ list: String) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: file(list).path))?[.modificationDate] as? Date
    }

    /// Downloads a list when it is missing or older than 12 hours. The file is the same for everyone.
    func refresh(_ list: String, force: Bool = false) async throws {
        if !force, let at = fetchedAt(list), now().timeIntervalSince(at) < Self.refreshAfter { return }
        let url = list == "phishtank" ? Self.phishTankURL : Self.openPhishURL
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.setValue("Loupe-iOS (phishing list download)", forHTTPHeaderField: "User-Agent")
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: req) } catch { throw OnlineCheckError.offline }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw OnlineCheckError.from(status: status, data: data) }
        let urls: [String]
        if list == "phishtank" {
            let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] ?? []
            urls = rows.compactMap { $0["url"] as? String }
        } else {
            urls = FeedIndex.companion.parseOpenPhish(text: String(decoding: data, as: UTF8.self))
        }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var target = file(list)
        try urls.prefix(Self.maxEntries).joined(separator: "\n").write(to: target, atomically: true, encoding: .utf8)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? target.setResourceValues(values)
    }

    func entries(_ lists: [String]) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for list in lists {
            guard let text = try? String(contentsOf: file(list), encoding: .utf8) else { continue }
            out[list] = text.split(separator: "\n").map(String.init)
        }
        return out
    }

    func remove() { try? FileManager.default.removeItem(at: dir) }
}

// MARK: - Google Safe Browsing (Update API v4, the user's own key)

final class SafeBrowsingClient {
    static let base = URL(string: "https://safebrowsing.googleapis.com/v4/")!
    static let threatTypes = ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE"]
    private let dir: URL
    private let session: URLSession
    private let key: () -> String?

    /// Per threat type: the client state Google gave and the sorted hash prefixes.
    struct ListState: Codable { var state: String; var prefixes: [Data] }
    private(set) var lists: [String: ListState] = [:]

    init(dir: URL, session: URLSession, key: @escaping () -> String?) {
        self.dir = dir
        self.session = session
        self.key = key
        if let d = try? Data(contentsOf: dir.appendingPathComponent("lists.json")),
           let l = try? JSONDecoder().decode([String: ListState].self, from: d) { lists = l }
    }

    private var client: [String: String] { ["clientId": "loupe-ios", "clientVersion": "0.1.0"] }

    private func post(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        guard let k = key(), !k.isEmpty else { throw OnlineCheckError.badKey }
        var comps = URLComponents(url: Self.base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "key", value: k)]
        var r = URLRequest(url: comps.url!, timeoutInterval: 30)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: r) } catch { throw OnlineCheckError.offline }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw OnlineCheckError.from(status: status, data: data) }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    /// `threatListUpdates:fetch`: full or partial updates of the local prefix lists (RAW compression).
    func update() async throws {
        let reqs: [[String: Any]] = Self.threatTypes.map { t in
            ["threatType": t, "platformType": "ANY_PLATFORM", "threatEntryType": "URL",
             "state": lists[t]?.state ?? "", "constraints": ["supportedCompressions": ["RAW"]]]
        }
        let resp = try await post("threatListUpdates:fetch", ["client": client, "listUpdateRequests": reqs])
        for u in resp["listUpdateResponses"] as? [[String: Any]] ?? [] {
            guard let t = u["threatType"] as? String else { continue }
            var prefixes = (u["responseType"] as? String) == "FULL_UPDATE" ? [] : (lists[t]?.prefixes ?? [])
            for rem in u["removals"] as? [[String: Any]] ?? [] {
                let idx = ((rem["rawIndices"] as? [String: Any])?["indices"] as? [Int]) ?? []
                for i in Set(idx).sorted(by: >) where i < prefixes.count { prefixes.remove(at: i) }
            }
            for add in u["additions"] as? [[String: Any]] ?? [] {
                guard let raw = add["rawHashes"] as? [String: Any], let size = raw["prefixSize"] as? Int, size > 0,
                      let b64 = raw["rawHashes"] as? String, let bytes = Data(base64Encoded: b64) else { continue }
                var i = 0
                while i + size <= bytes.count { prefixes.append(bytes.subdata(in: i..<(i + size))); i += size }
            }
            prefixes.sort { $0.lexicographicallyPrecedes($1) }
            lists[t] = ListState(state: u["newClientState"] as? String ?? "", prefixes: prefixes)
        }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try JSONEncoder().encode(lists).write(to: dir.appendingPathComponent("lists.json"), options: .atomic)
    }

    /// Host-suffix / path-prefix expressions of [url] (simplified Safe Browsing canonicalisation).
    static func expressions(_ url: String) -> [String] {
        guard let c = URLComponents(string: url), var host = c.host?.lowercased() else { return [] }
        while host.hasSuffix(".") { host.removeLast() }
        let path = c.percentEncodedPath.isEmpty ? "/" : c.percentEncodedPath
        let query = c.percentEncodedQuery.map { "?" + $0 } ?? ""
        var hosts = [host]
        let labels = host.split(separator: ".")
        if labels.count > 2 && Int(labels.last!) == nil {
            for n in stride(from: min(5, labels.count - 1), through: 2, by: -1) { hosts.append(labels.suffix(n).joined(separator: ".")) }
        }
        var paths = [path + query, path]
        var acc = "/"
        paths.append(acc)
        for comp in path.split(separator: "/").dropLast().prefix(3) { acc += comp + "/"; paths.append(acc) }
        var out: [String] = []
        for h in hosts.prefix(5) { for p in paths where !out.contains(h + p) { out.append(h + p) } }
        return out
    }

    static func hash(_ s: String) -> Data { Data(SHA256.hash(data: Data(s.utf8))) }

    private func localMatches(_ hash: Data) -> [Data] {
        lists.values.flatMap { l in l.prefixes.filter { hash.starts(with: $0) } }
    }

    /// The URLs Google lists as dangerous. Only prefixes that already match the local list are
    /// sent (`fullHashes:find`), never a URL; with no local match nothing is sent.
    func dangerous(_ urls: [String]) async throws -> Set<String> {
        var byPrefix: [Data: [(String, Data)]] = [:]
        for u in urls {
            for e in Self.expressions(u) {
                let h = Self.hash(e)
                for p in localMatches(h) { byPrefix[p, default: []].append((u, h)) }
            }
        }
        if byPrefix.isEmpty { return [] }
        let body: [String: Any] = [
            "client": client,
            "clientStates": lists.values.map(\.state),
            "threatInfo": ["threatTypes": Self.threatTypes, "platformTypes": ["ANY_PLATFORM"], "threatEntryTypes": ["URL"],
                           "threatEntries": byPrefix.keys.map { ["hash": $0.base64EncodedString()] }],
        ]
        let resp = try await post("fullHashes:find", body)
        let full = Set((resp["matches"] as? [[String: Any]] ?? []).compactMap { ($0["threat"] as? [String: Any])?["hash"] as? String }.compactMap { Data(base64Encoded: $0) })
        var out = Set<String>()
        for pairs in byPrefix.values { for (u, h) in pairs where full.contains(h) { out.insert(u) } }
        return out
    }

    func remove() {
        lists = [:]
        try? FileManager.default.removeItem(at: dir)
    }
}

// MARK: - The service

@MainActor
final class OnlineChecksService: ObservableObject {
    static let shared = OnlineChecksService()

    @Published private(set) var settings: OnlinePhishingSettings
    /// The last run's status, for the "Online" label: never "checked" when nothing came back.
    @Published private(set) var status: String?
    @Published private(set) var keySet: Bool

    let key: KeyStore
    private let defaults: UserDefaults
    private let facts: DomainFactsClient
    let feeds: PhishingFeeds
    let safeBrowsing: SafeBrowsingClient
    private let now: () -> Date
    /// In memory only, never on disk: facts per domain for 6 hours (the helper keeps its own cache).
    private var cache: [String: (DomainFacts, Date)] = [:]
    private var backoffUntil: Date?

    init(defaults: UserDefaults = .standard,
         session: URLSession? = nil,
         key: KeyStore = KeychainStore(service: "dev.loupe.app.safebrowsing", account: "google-safe-browsing-key"),
         dir: URL? = nil,
         base: URL = HelperEndpoint.base,
         now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.key = key
        self.now = now
        let s = session ?? Self.ephemeralSession()
        let root = dir ?? (FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("online-phishing"))
        facts = DomainFactsClient(base: base, session: s)
        feeds = PhishingFeeds(dir: root.appendingPathComponent("feeds"), session: s, now: now)
        safeBrowsing = SafeBrowsingClient(dir: root.appendingPathComponent("safe-browsing"), session: s, key: { key.read() })
        settings = OnlinePhishingSettings.load(defaults)
        keySet = key.read() != nil
    }

    static func ephemeralSession(protocols: [AnyClass] = []) -> URLSession {
        let cfg = URLSessionConfiguration.ephemeral          // no cookies, no URL cache on disk
        cfg.urlCache = nil
        cfg.waitsForConnectivity = false
        cfg.timeoutIntervalForRequest = 30
        if !protocols.isEmpty { cfg.protocolClasses = protocols }
        return URLSession(configuration: cfg)
    }

    func update(_ next: OnlinePhishingSettings) {
        var s = next
        if !s.feeds { s.phishTank = false }
        settings = s
        s.save(defaults)
        if !s.feeds { feeds.remove() }
        if !s.safeBrowsing { safeBrowsing.remove() }
        if !s.domainFacts { cache = [:] }
        status = s.anyOn ? nil : "Online checks are off: nothing is sent."
    }

    func saveKey(_ value: String) throws {
        let k = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard k.count >= 20, k.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { throw OnlineCheckError.badKey }
        try key.save(k)
        keySet = true
    }

    func deleteKey() {
        try? key.delete()
        keySet = false
        var s = settings
        s.safeBrowsing = false
        update(s)
    }

    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// The online data for one triage run, or nil with every switch off — then **no request is made**.
    func context(items: [SourceItem], raws: [String: String]) async -> OnlineContext? {
        let s = settings
        guard s.anyOn else { return nil }
        let at = Self.iso.string(from: now())
        var notes: [String] = []
        var factsOut: [String: DomainFacts] = [:]

        if s.domainFacts {
            let domains = MailTriage.shared.onlineLookups(items: items, raws: raws, max: 40)
            var withFacts = 0, without = 0
            loop: for d in domains {
                if let hit = cache[d], now().timeIntervalSince(hit.1) < 6 * 3600 {
                    let f = hit.0
                    factsOut[d] = f
                    if f.hasFacts { withFacts += 1 } else { without += 1 }
                    continue
                }
                if let until = backoffUntil, now() < until { notes.append("Domain facts: the helper asked Loupe to wait; try later."); break }
                do {
                    let f = try await facts.facts(for: d)
                    cache[d] = (f, now())
                    factsOut[d] = f
                    if f.hasFacts { withFacts += 1 } else { without += 1 }
                } catch let e as OnlineCheckError {
                    switch e {
                    case .notAvailableYet: notes.append("Domain facts: online checks not available yet."); break loop
                    case .rateLimited:
                        backoffUntil = now().addingTimeInterval(600)
                        notes.append("Domain facts: the helper is busy (rate limited); try later.")
                        break loop
                    case .offline: notes.append("Domain facts: offline."); break loop
                    default: continue
                    }
                } catch { continue }
            }
            if !domains.isEmpty && (withFacts + without) > 0 {
                notes.append("Online · Loupe web helper · \(withFacts) domain\(withFacts == 1 ? "" : "s") with registration facts" +
                             (without > 0 ? ", \(without) with none found" : "") + " · fetched \(at)")
            } else if domains.isEmpty {
                notes.append("Domain facts: nothing to look up.")
            }
        }

        var index: FeedIndex?
        var feedsAt: String?
        if s.feeds {
            let lists = s.phishTank ? ["openphish", "phishtank"] : ["openphish"]
            for l in lists {
                do { try await feeds.refresh(l) } catch { notes.append("Phishing list \(l): could not download (\(error)).") }
            }
            let entries = feeds.entries(lists)
            if !entries.isEmpty {
                index = FeedIndex(entries: entries)
                feedsAt = lists.compactMap { feeds.fetchedAt($0) }.min().map { Self.iso.string(from: $0) }
                notes.append("Online · " + entries.keys.sorted().map { $0 == "openphish" ? "OpenPhish" : "PhishTank" }.joined(separator: ", ") +
                             " list on this phone · \(entries.values.map(\.count).reduce(0, +)) entries · fetched \(feedsAt ?? at)")
            }
        }

        var hits = Set<String>()
        if s.safeBrowsing {
            if key.read() == nil {
                notes.append("Google Safe Browsing: add your own API key to use it.")
            } else {
                do {
                    try await safeBrowsing.update()
                    hits = try await safeBrowsing.dangerous(MailTriage.shared.onlineUrls(items: items, raws: raws, max: 60))
                    notes.append("Online · Google Safe Browsing (your key) · fetched \(at)")
                } catch OnlineCheckError.badKey {
                    notes.append("Google Safe Browsing: Google refused the key.")
                } catch {
                    notes.append("Google Safe Browsing: not reachable (\(error)).")
                }
            }
        }
        status = notes.joined(separator: "\n")
        return OnlineContext(nowIso: at, facts: factsOut, feeds: index, safeBrowsingHits: hits,
                             safeBrowsingFetchedAt: s.safeBrowsing ? at : nil, feedsFetchedAt: feedsAt)
    }
}
