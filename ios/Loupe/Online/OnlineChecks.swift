import CryptoKit
import Foundation
import LoupeKit

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
    /// The app's service: lists in the App Group (Loupe for Safari and "Send to Loupe" read them) and
    /// the switches mirrored there (the extensions' read of what the user turned on).
    static let shared = OnlineChecksService(groupDefaults: ProtectionGroup.defaults)

    @Published private(set) var settings: OnlinePhishingSettings
    /// The last run's status, for the "Online" label: never "checked" when nothing came back.
    @Published private(set) var status: String?
    @Published private(set) var keySet: Bool

    let key: KeyStore
    private let defaults: UserDefaults
    /// The App Group's defaults, written with every change (nil in tests unless they pass one).
    private let groupDefaults: UserDefaults?
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
         groupDefaults: UserDefaults? = nil,
         base: URL = HelperEndpoint.base,
         now: @escaping () -> Date = Date.init,
         resolver: DnsQuery? = nil,
         pause: @escaping (Double) async -> Void = { s in try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }) {
        self.defaults = defaults
        self.groupDefaults = groupDefaults
        self.key = key
        self.now = now
        let s = session ?? Self.ephemeralSession()
        let root = dir ?? Self.sharedRoot()
        facts = DomainFactsClient(base: base, session: s)
        feeds = PhishingFeeds(dir: root.appendingPathComponent("feeds"), session: s, now: now)
        phishingDb = PhishingDatabaseStore(dir: root.appendingPathComponent("phishingdb"), session: s, now: now, pause: pause)
        siteFacts = resolver.map { SiteFactsLookup(resolver: $0, now: now) } ?? SiteFactsLookup(now: now)
        safeBrowsing = SafeBrowsingClient(dir: root.appendingPathComponent("safe-browsing"), session: s, key: { key.read() }, now: now)
        settings = OnlinePhishingSettings.load(defaults)
        keySet = key.read() != nil
        if let groupDefaults { settings.save(groupDefaults) }
    }

    /// The lists' folder in the App Group (2026-09-26, browsing protection), moved there once from the
    /// app's own Application Support so the extensions read what the app downloaded.
    static func sharedRoot(fm: FileManager = .default) -> URL {
        let root = ProtectionGroup.phishingRoot(fm)
        let old = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("online-phishing")
        if old.standardizedFileURL != root.standardizedFileURL, fm.fileExists(atPath: old.path), !fm.fileExists(atPath: root.path) {
            try? fm.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? fm.moveItem(at: old, to: root)
        }
        return root
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
        if let groupDefaults { s.save(groupDefaults) }
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
        await run(factsDomains: { MailTriage.shared.onlineLookups(items: items, raws: raws, max: 40) },
                  dnsDomains: { Array(MailTriage.shared.onlineLookups(items: items, raws: raws, max: 20)) },
                  safeBrowsingUrls: { MailTriage.shared.onlineUrls(items: items, raws: raws, max: 60) }).context
    }

    /// Check a link (2026-09-26): the online data for one link, and what went out for it. Only the
    /// link's ICANN registrable domain is looked up (none for a hosting-platform page or an IP); the
    /// lists are matched on the phone; Safe Browsing is asked only as it always is (hash prefixes on
    /// a local match). nil context with every switch off: then nothing is sent.
    func linkContext(url: String) async -> (context: OnlineContext?, disclosure: OnlineDisclosure) {
        let domains = OnlineSignals.shared.lookupDomainOfUrl(url: url).map { [$0] } ?? []
        return await run(factsDomains: { domains }, dnsDomains: { domains }, safeBrowsingUrls: { [url] })
    }

    /// Brings the lists up to date and rewrites Phishing.Database's index for Loupe for Safari, when
    /// the lists are on (nothing happens with them off). The Protection screen calls it on open.
    func refreshListsForProtection() async {
        guard settings.feeds else { return }
        _ = await run(factsDomains: { [] }, dnsDomains: { [] }, safeBrowsingUrls: { [] }, onlyLists: true)
    }

    private func run(factsDomains: () -> [String], dnsDomains: () -> [String], safeBrowsingUrls: () -> [String],
                     onlyLists: Bool = false) async -> (context: OnlineContext?, disclosure: OnlineDisclosure) {
        var s = settings
        guard s.anyOn else { return (nil, .none) }
        if onlyLists { s = OnlinePhishingSettings(feeds: s.feeds, openPhish: s.openPhish, phishTank: s.phishTank, phishingDb: s.phishingDb, refreshHours: s.refreshHours) }
        let at = Self.iso.string(from: now())
        var notes: [String] = []
        var disclosure = OnlineDisclosure()
        var factsOut: [String: DomainFacts] = [:]

        if s.domainFacts {
            let domains = factsDomains()
            var withFacts = 0, without = 0
            loop: for d in domains {
                if let hit = cache[d], now().timeIntervalSince(hit.1) < 6 * 3600 {
                    let f = hit.0
                    factsOut[d] = f
                    disclosure.sent.append(.init(what: d, to: "the Loupe web helper"))
                    if f.hasFacts { withFacts += 1 } else { without += 1 }
                    continue
                }
                if let until = backoffUntil, now() < until { notes.append("Domain facts: the helper asked Loupe to wait; try later."); break }
                disclosure.sent.append(.init(what: d, to: "the Loupe web helper"))
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
                let names = entries.keys.sorted().map { $0 == "openphish" ? "OpenPhish" : "PhishTank" }.joined(separator: ", ")
                notes.append("Online · " + names + " list on this phone · \(entries.values.map(\.count).reduce(0, +)) entries · fetched \(feedsAt ?? at)")
                disclosure.matchedOnDevice.append("\(names) list on this iPhone")
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
                    disclosure.matchedOnDevice.append("Phishing.Database list on this iPhone (\(pdb.linkCount) links, \(pdb.hostCount) hosts, list date \((pdb.listDate ?? at).prefix(10)))")
                }
            }
            if lists.isEmpty && !s.phishingDb { notes.append("Phishing lists: every list is off.") }
        }

        // Formula v1.2 site facts: DNS and the blocklists, through this phone's own resolver only.
        var dnsOut: [String: DnsFacts] = [:]
        var blOut: [String: [String: DnsblResult]] = [:]
        if s.dnsFacts || !s.dnsblLists.isEmpty {
            let domains = dnsDomains()
            let lookup = siteFacts, dnsOn = s.dnsFacts, lists = s.dnsblLists
            let r = await Task.detached(priority: .utility) { lookup.lookup(domains: domains, dns: dnsOn, lists: lists, at: at) }.value
            dnsOut = r.dns
            blOut = r.dnsbl
            if r.noAnswer { notes.append("DNS: this network's resolver did not answer; DNS facts skipped.") }
            if dnsOn {
                notes.append("Online · DNS (this phone's resolver) · \(dnsOut.count) domain\(dnsOut.count == 1 ? "" : "s") · fetched \(at)")
                for d in domains { disclosure.sent.append(.init(what: d, to: "this iPhone's DNS resolver")) }
            }
            if !lists.isEmpty {
                let names = lists.compactMap { Dnsbl.shared.zone(id: $0)?.name }.joined(separator: ", ")
                notes.append("Online · Domain blocklists (\(names)) · \(blOut.count) domain\(blOut.count == 1 ? "" : "s") · fetched \(at)")
                for d in domains { disclosure.sent.append(.init(what: d, to: "the blocklists \(names) (through this iPhone's DNS resolver)")) }
            }
        }

        var hits = Set<String>()
        if s.safeBrowsing {
            if key.read() == nil {
                notes.append("Google Safe Browsing: add your own API key to use it.")
            } else {
                do {
                    try await safeBrowsing.update()
                    let urls = safeBrowsingUrls()
                    hits = urls.isEmpty ? [] : try await safeBrowsing.dangerous(urls)
                    notes.append("Online · Google Safe Browsing (your key) · fetched \(at)")
                    disclosure.conditional.append("Google Safe Browsing (your key): its list of hash prefixes is on this iPhone; only if the link matched one would a 4-byte hash prefix go to Google, never the link.")
                } catch OnlineCheckError.badKey {
                    notes.append("Google Safe Browsing: Google refused the key.")
                } catch {
                    notes.append("Google Safe Browsing: not reachable (\(error)).")
                }
            }
        }
        if !onlyLists { status = notes.joined(separator: "\n") }
        disclosure.onlineNotes = notes.filter { $0.hasPrefix("Online ·") }
        let context = OnlineContext(nowIso: at, facts: factsOut, feeds: index, safeBrowsingHits: hits,
                                    safeBrowsingFetchedAt: s.safeBrowsing ? at : nil, feedsFetchedAt: feedsAt,
                                    phishingDb: pdb, dns: dnsOut, dnsbl: blOut, dnsblFetchedAt: blOut.isEmpty ? nil : at)
        return (context, disclosure)
    }
}
