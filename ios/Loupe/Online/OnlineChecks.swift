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
/// - Phishing lists: Phishing.Database (MIT; on by default once the lists are on; `PhishingDatabase.swift`),
///   OpenPhish's community feed (off by default: personal, non-commercial use only) and PhishTank's
///   keyless list, each with its own switch, downloaded to the phone and matched on the phone.
///   Nothing per link leaves it.
/// - Formula v1.2 site facts (off by default): DNS facts (MX, SPF, DMARC, DNSSEC, BIMI) and the domain
///   blocklists (Spamhaus DBL, SURBL, URIBL; non-commercial licences), asked of this phone's own
///   resolver only (`SiteDns.swift`), for ICANN registrable domains of links.
/// - Google Safe Browsing (API v5, local-list mode; `SafeBrowsing.swift`) with the user's own key from
///   the Keychain: a local list of hash prefixes; only on a local match are 4-byte prefixes sent to
///   Google, never a URL.
struct OnlinePhishingSettings: Equatable {
    var domainFacts = false
    /// "Known-phishing lists": the master switch for the downloaded lists.
    var feeds = false
    /// OpenPhish's community feed: off by default (free for personal, non-commercial use only).
    var openPhish = false
    var phishTank = false
    /// Phishing.Database (MIT): on by default whenever the lists are on (owner decision B).
    var phishingDb = true
    /// How often the large Phishing.Database ACTIVE files are refreshed, 1–168 hours (NEW files: hourly).
    var refreshHours = 6
    var safeBrowsing = false
    /// Formula v1.2 §5b: MX / SPF / DMARC / DNSSEC / BIMI from this phone's own resolver.
    var dnsFacts = false
    /// Formula v1.2 §5c: domain blocklists over DNS (master switch; each list non-commercial, low volume).
    var dnsbl = false
    var dnsblSpamhaus = true
    var dnsblSurbl = true
    var dnsblUribl = true

    var anyOn: Bool { domainFacts || feeds || safeBrowsing || dnsFacts || dnsbl }

    /// The lists downloaded while "Known-phishing lists" is on.
    var lists: [String] {
        guard feeds else { return [] }
        return (openPhish ? ["openphish"] : []) + (phishTank ? ["phishtank"] : []) + (phishingDb ? ["phishingdb"] : [])
    }

    /// The blocklists asked while the blocklist switch is on, in Dnsbl.ZONES order.
    var dnsblLists: [String] {
        guard dnsbl else { return [] }
        return (dnsblSpamhaus ? ["spamhaus_dbl"] : []) + (dnsblSurbl ? ["surbl"] : []) + (dnsblUribl ? ["uribl"] : [])
    }

    static let keys = (domainFacts: "online.phishing.domainFacts", feeds: "online.phishing.feeds",
                       openPhish: "online.phishing.openphish", phishTank: "online.phishing.phishtank",
                       phishingDb: "online.phishing.phishingdb", refreshHours: "online.phishing.refreshHours",
                       safeBrowsing: "online.phishing.safeBrowsing", dnsFacts: "online.phishing.dnsFacts",
                       dnsbl: "online.phishing.dnsbl", dnsblSpamhaus: "online.phishing.dnsbl.spamhaus",
                       dnsblSurbl: "online.phishing.dnsbl.surbl", dnsblUribl: "online.phishing.dnsbl.uribl")

    static func load(_ d: UserDefaults) -> OnlinePhishingSettings {
        func flag(_ k: String, _ fallback: Bool) -> Bool { (d.object(forKey: k) as? Bool) ?? fallback }
        let hours = (d.object(forKey: keys.refreshHours) as? Int) ?? Int(PhishingDb.shared.DEFAULT_REFRESH_HOURS)
        return OnlinePhishingSettings(
            domainFacts: flag(keys.domainFacts, false), feeds: flag(keys.feeds, false), openPhish: flag(keys.openPhish, false),
            phishTank: flag(keys.phishTank, false), phishingDb: flag(keys.phishingDb, true),
            refreshHours: Int(PhishingDb.shared.clampRefreshHours(h: Int32(hours))), safeBrowsing: flag(keys.safeBrowsing, false),
            dnsFacts: flag(keys.dnsFacts, false), dnsbl: flag(keys.dnsbl, false), dnsblSpamhaus: flag(keys.dnsblSpamhaus, true),
            dnsblSurbl: flag(keys.dnsblSurbl, true), dnsblUribl: flag(keys.dnsblUribl, true))
    }

    func save(_ d: UserDefaults) {
        d.set(domainFacts, forKey: Self.keys.domainFacts)
        d.set(feeds, forKey: Self.keys.feeds)
        d.set(openPhish, forKey: Self.keys.openPhish)
        d.set(phishTank, forKey: Self.keys.phishTank)
        d.set(phishingDb, forKey: Self.keys.phishingDb)
        d.set(refreshHours, forKey: Self.keys.refreshHours)
        d.set(safeBrowsing, forKey: Self.keys.safeBrowsing)
        d.set(dnsFacts, forKey: Self.keys.dnsFacts)
        d.set(dnsbl, forKey: Self.keys.dnsbl)
        d.set(dnsblSpamhaus, forKey: Self.keys.dnsblSpamhaus)
        d.set(dnsblSurbl, forKey: Self.keys.dnsblSurbl)
        d.set(dnsblUribl, forKey: Self.keys.dnsblUribl)
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
    let phishingDb: PhishingDatabaseStore
    let siteFacts: SiteFactsLookup
    let safeBrowsing: SafeBrowsingClient
    private let now: () -> Date
    /// In memory only, never on disk: facts per domain for 6 hours (the helper keeps its own cache).
    private var cache: [String: (DomainFacts, Date)] = [:]
    private var backoffUntil: Date?

    init(defaults: UserDefaults = .standard,
         session: URLSession? = nil,
         key: KeyStore = KeychainStore(service: "com.loupe-ai.ios.safebrowsing", account: "google-safe-browsing-key"),
         dir: URL? = nil,
         base: URL = HelperEndpoint.base,
         now: @escaping () -> Date = Date.init,
         resolver: DnsQuery? = nil,
         pause: @escaping (Double) async -> Void = { s in try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }) {
        self.defaults = defaults
        self.key = key
        self.now = now
        let s = session ?? Self.ephemeralSession()
        let root = dir ?? (FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("online-phishing"))
        facts = DomainFactsClient(base: base, session: s)
        feeds = PhishingFeeds(dir: root.appendingPathComponent("feeds"), session: s, now: now)
        phishingDb = PhishingDatabaseStore(dir: root.appendingPathComponent("phishingdb"), session: s, now: now, pause: pause)
        siteFacts = resolver.map { SiteFactsLookup(resolver: $0, now: now) } ?? SiteFactsLookup(now: now)
        safeBrowsing = SafeBrowsingClient(dir: root.appendingPathComponent("safe-browsing"), session: s, key: { key.read() }, now: now)
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
        s.refreshHours = Int(PhishingDb.shared.clampRefreshHours(h: Int32(clamping: s.refreshHours)))
        settings = s
        s.save(defaults)
        if !s.feeds || (!s.openPhish && !s.phishTank) { feeds.remove() }
        if !s.feeds || !s.phishingDb { phishingDb.remove() }
        if !s.dnsFacts && !s.dnsbl { siteFacts.forget() }
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
        var pdb: PhishingDbIndex?
        if s.feeds {
            let lists = s.lists.filter { $0 != "phishingdb" }
            // The refresh's live run: one step per list (its id), in Me → Online checks or the dock.
            let job = ActivityCenter.shared.start("feeds", title: "act.title.feeds", view: "protection",
                                                  total: lists.count + (s.phishingDb ? 1 : 0), stage: "act.stage.downloading")
            var refreshed = 0
            for l in lists {
                job.step(l, key: "act.stage.downloading", state: "running")
                do {
                    try await feeds.refresh(l)
                    refreshed += 1
                    job.step(l, key: "act.stage.downloading", state: "done")
                } catch {
                    notes.append("Phishing list \(l): could not download (\(error)).")
                    job.step(l, key: "act.stage.downloading", state: "error")
                }
                job.progress(refreshed, of: nil)
            }
            defer { job.finish("done", "act.res.feeds", ["lists": refreshed]) }
            let entries = feeds.entries(lists)
            if !entries.isEmpty {
                index = FeedIndex(entries: entries)
                feedsAt = lists.compactMap { feeds.fetchedAt($0) }.min().map { Self.iso.string(from: $0) }
                notes.append("Online · " + entries.keys.sorted().map { $0 == "openphish" ? "OpenPhish" : "PhishTank" }.joined(separator: ", ") +
                             " list on this phone · \(entries.values.map(\.count).reduce(0, +)) entries · fetched \(feedsAt ?? at)")
            }
            if s.phishingDb {
                job.step("phishingdb", key: "act.stage.downloading", state: "running")
                let pdbError = await phishingDb.refresh(refreshHours: s.refreshHours)
                job.step("phishingdb", key: "act.stage.downloading", state: pdbError == nil ? "done" : "error")
                if pdbError == nil { refreshed += 1; job.progress(refreshed, of: nil) }
                if let error = pdbError {
                    notes.append("Phishing.Database: could not update (\(error)); the last good copy is kept.")
                }
                let store = phishingDb
                pdb = await Task.detached(priority: .utility) { store.loadedIndex() }.value
                if let pdb {
                    notes.append("Online · Phishing.Database list on this phone · \(pdb.linkCount) links, \(pdb.hostCount) hosts · list date \(pdb.listDate ?? at)")
                }
            }
            if lists.isEmpty && !s.phishingDb { notes.append("Phishing lists: every list is off.") }
        }

        // Formula v1.2 site facts: DNS and the blocklists, through this phone's own resolver only.
        var dnsOut: [String: DnsFacts] = [:]
        var blOut: [String: [String: DnsblResult]] = [:]
        if s.dnsFacts || !s.dnsblLists.isEmpty {
            let domains = Array(MailTriage.shared.onlineLookups(items: items, raws: raws, max: 20))
            let lookup = siteFacts, dnsOn = s.dnsFacts, lists = s.dnsblLists
            let r = await Task.detached(priority: .utility) { lookup.lookup(domains: domains, dns: dnsOn, lists: lists, at: at) }.value
            dnsOut = r.dns
            blOut = r.dnsbl
            if r.noAnswer { notes.append("DNS: this network's resolver did not answer; DNS facts skipped.") }
            if dnsOn { notes.append("Online · DNS (this phone's resolver) · \(dnsOut.count) domain\(dnsOut.count == 1 ? "" : "s") · fetched \(at)") }
            if !lists.isEmpty {
                let names = lists.compactMap { Dnsbl.shared.zone(id: $0)?.name }.joined(separator: ", ")
                notes.append("Online · Domain blocklists (\(names)) · \(blOut.count) domain\(blOut.count == 1 ? "" : "s") · fetched \(at)")
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
                             safeBrowsingFetchedAt: s.safeBrowsing ? at : nil, feedsFetchedAt: feedsAt,
                             phishingDb: pdb, dns: dnsOut, dnsbl: blOut, dnsblFetchedAt: blOut.isEmpty ? nil : at)
    }
}
