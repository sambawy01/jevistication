import Darwin
import Foundation
import LoupeKit

/// The online lookups Loupe for Safari may make, each **for a registrable domain only** and only while
/// its switch is on in the app. Behind a protocol so tests drive the engine with no network.
protocol SafariOnlineLookups: AnyObject {
    /// Domain age and certificates from the Loupe web helper (`POST /v1/domain-facts {"domain": d}`).
    func domainFacts(_ domain: String) async throws -> DomainFacts
    /// The domain blocklists (Spamhaus DBL, SURBL, URIBL) through this iPhone's DNS resolver.
    func blocklists(_ domain: String, lists: [String], at: String) async -> [String: DnsblResult]
}

/// One answer to Safari: what the content script shows (or does not show, for no warning signs).
struct SafariVerdict: Equatable {
    let host: String
    let domain: String
    let level: ProtectionLevel
    let score: Int
    let reasons: [String]
    let brand: String?
    let onlineNotes: [String]
    let privacyLine: String
    /// The online checks are on and have not run yet: the extension asks again with `online: true`.
    var onlinePending = false
    var cached = false
    var ms: Double = 0
    var memoryMB: Double = 0

    var message: [String: Any] {
        var m: [String: Any] = [
            "ok": true, "host": host, "domain": domain, "level": level.rawValue, "title": level.title, "score": score,
            "reasons": reasons, "online": onlineNotes, "privacy": privacyLine, "cached": cached, "onlinePending": onlinePending,
            "ms": (ms * 10).rounded() / 10, "memoryMB": (memoryMB * 10).rounded() / 10,
        ]
        if let brand { m["brand"] = brand }
        return m
    }
}

/// Loupe for Safari's native side, without Safari (2026-09-26): `SafariWebExtensionHandler` hands it
/// each message from the extension's background script and sends back what it returns, so tests
/// drive it directly. Messages:
///
/// - `{"type": "verdict", "host": h, "scheme": "https", "allowed": false, "online": false}`: the verdict for one
///   website name. **Only the website name reaches this side**, never the address or the page. The
///   mechanical checks run here; the online ones only when switched on in the app (read from the App
///   Group) and only for the registrable domain, in a second message (`online: true`) so the warning
///   never waits for the network. A suspicious or dangerous site is added to the Spotted log and may
///   notify (never when `allowed`: the user already chose to continue).
/// - `{"type": "action", "host": h, "action": "back" | "continue"}`: what the user did on the warning.
/// - `{"type": "hello"}`: the extension is running (the app's "on" hint).
///
/// Verdicts are cached per website name for [cacheFor] (in memory only); a change of switches
/// empties the cache.
final class SafariVerdictEngine {
    static let cacheFor: TimeInterval = 30 * 60
    static let factsFor: TimeInterval = 6 * 3600
    /// Safari should never wait long: the helper gets this long, then the verdict goes without it.
    static let onlineTimeout: TimeInterval = 3

    private let settings: () -> OnlinePhishingSettings
    private let lists: ProtectionLists
    private let online: SafariOnlineLookups?
    let log: SpottedLog
    let notifier: SpottedNotifier
    private let defaults: UserDefaults
    private let now: () -> Date
    private let lock = NSLock()
    private var cache: [String: (SafariVerdict, Date, OnlinePhishingSettings)] = [:]
    private var facts: [String: (DomainFacts, Date)] = [:]
    private var backoffUntil: Date?
    private var helperMissing = false
    /// What this process already logged per website name (so the online step adds nothing twice).
    private var logged: [String: (rank: Int, at: Date)] = [:]

    init(settings: @escaping () -> OnlinePhishingSettings = { OnlinePhishingSettings.loadShared() },
         lists: ProtectionLists = ProtectionLists(), online: SafariOnlineLookups?,
         log: SpottedLog = SpottedLog(), notifier: SpottedNotifier = SpottedNotifier(),
         defaults: UserDefaults = ProtectionGroup.defaults, now: @escaping () -> Date = Date.init) {
        self.settings = settings
        self.lists = lists
        self.online = online
        self.log = log
        self.notifier = notifier
        self.defaults = defaults
        self.now = now
    }

    // MARK: messages

    func handle(_ message: [String: Any]) async -> [String: Any] {
        defaults.set(now().timeIntervalSince1970, forKey: ProtectionGroup.Keys.safariLastSeen)
        switch message["type"] as? String {
        case "verdict":
            guard let host = Self.cleanHost(message["host"] as? String) else { return ["ok": false, "error": "bad_host"] }
            let scheme = (message["scheme"] as? String)?.lowercased() == "http" ? "http" : "https"
            let allowed = message["allowed"] as? Bool ?? false
            let network = message["online"] as? Bool ?? true
            let v = await verdict(host: host, scheme: scheme, network: network)
            if v.level.flagged && !allowed && shouldLog(v) {
                if log.record(domain: v.domain, host: v.host, level: v.level, score: v.score, reasons: v.reasons, brand: v.brand, origin: .safari) != nil {
                    ProtectionGroup.post(ProtectionGroup.spottedChanged)
                }
                await notifier.notifyIfNeeded(domain: v.domain, host: v.host, level: v.level, brand: v.brand,
                                              reason: v.reasons.first, userContinued: false)
            }
            return v.message
        case "action":
            guard let host = Self.cleanHost(message["host"] as? String) else { return ["ok": false, "error": "bad_host"] }
            let action: SpottedAction = (message["action"] as? String) == "continue" ? .continued : .wentBack
            log.setAction(host: host, origin: .safari, action)
            if action == .continued { notifier.markContinued(host: host) }
            ProtectionGroup.post(ProtectionGroup.spottedChanged)
            return ["ok": true]
        case "hello":
            return ["ok": true, "version": 1]
        default:
            return ["ok": false, "error": "unknown_type"]
        }
    }

    /// A website name from Safari, lowercased, in ASCII (IDNA), or nil when it is not one.
    static func cleanHost(_ raw: String?) -> String? {
        guard var h = raw?.trimmingCharacters(in: .whitespaces).lowercased(), !h.isEmpty, h.count <= 253 else { return nil }
        if h.hasSuffix(".") { h.removeLast() }
        if !h.allSatisfy(\.isASCII) { h = Hosts.shared.toAsciiDomain(domain: h) ?? "" }
        let ok = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-_:[]")
        guard !h.isEmpty, h.unicodeScalars.allSatisfy(ok.contains) else { return nil }
        return h
    }

    /// Logs a flagged site once per [SpottedLog.dedupeWindow] in this process, again only when a later
    /// step raises its level (the log itself also merges repeats).
    private func shouldLog(_ v: SafariVerdict) -> Bool {
        lock.withLock {
            if let prev = logged[v.host], now().timeIntervalSince(prev.at) < SpottedLog.dedupeWindow, prev.rank >= v.level.rank { return false }
            logged[v.host] = (v.level.rank, now())
            return true
        }
    }

    private func cached(_ key: String, _ s: OnlinePhishingSettings) -> SafariVerdict? {
        lock.withLock {
            if let hit = cache[key], hit.2 == s, now().timeIntervalSince(hit.1) < Self.cacheFor { return hit.0 }
            if cache.values.contains(where: { $0.2 != s }) { cache = [:] }
            return nil
        }
    }

    // MARK: the verdict

    /// [network] false: the mechanical checks and the lists on this phone only (nothing is sent);
    /// true: also the online checks that are switched on.
    func verdict(host: String, scheme: String, network: Bool = true) async -> SafariVerdict {
        let start = DispatchTime.now().uptimeNanoseconds
        let s = settings()
        let key = "\(scheme)://\(host)#\(network)"
        if let hit = cached(key, s) {
            var v = hit
            v.cached = true
            v.ms = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6
            v.memoryMB = Self.footprintMB()
            return v
        }

        guard case .success(let input) = LinkInput.normalize("\(scheme)://\(host)/") else {
            return SafariVerdict(host: host, domain: host, level: .safe, score: 0, reasons: [], brand: nil, onlineNotes: [],
                                 privacyLine: OnlineDisclosure.none.privacyLine(subject: "site"))
        }
        let (context, disclosure, pending) = Hosts.shared.isPrivateHost(host: host)
            ? (nil, .none, false) : await onlineContext(url: input.url, settings: s, network: network)
        let lv = LinkChecker.verdict(input, online: context, disclosure: disclosure, origin: .safari, now: now())
        var v = SafariVerdict(host: lv.host, domain: lv.unicodeHost, level: lv.level, score: lv.score,
                              reasons: lv.reasons.map { r in r.online.map { "\($0): \(r.text)" } ?? r.text },
                              brand: lv.brand, onlineNotes: disclosure.onlineNotes, privacyLine: disclosure.privacyLine(subject: "site"))
        v.onlinePending = pending
        v.ms = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6
        v.memoryMB = Self.footprintMB()
        let at = now()
        lock.withLock { cache[key] = (v, at, s) }
        return v
    }

    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// The lists on this phone and, when switched on, the helper and the blocklists, for [url]'s
    /// registrable domain only. nil context when every online check is off: then nothing is read
    /// or sent.
    func onlineContext(url: String, settings s: OnlinePhishingSettings, network: Bool = true) async -> (OnlineContext?, OnlineDisclosure, Bool) {
        guard s.anyOn else { return (nil, .none, false) }
        let at = Self.iso.string(from: now())
        var d = OnlineDisclosure()
        let local = lists.onDevice(url: url, settings: s)
        let slice = local.phishingDb, feedIndex = local.feeds, feedsAt = local.feedsAt
        d.matchedOnDevice = local.notes
        let domain = OnlineSignals.shared.lookupDomainOfUrl(url: url)
        let wantsNetwork = domain != nil && online != nil && (s.domainFacts || !s.dnsblLists.isEmpty)
        var factsOut: [String: DomainFacts] = [:]
        if network, s.domainFacts, let domain, let online {
            let t = now()
            let (hit, waiting) = lock.withLock {
                (facts[domain].flatMap { t.timeIntervalSince($0.1) < Self.factsFor ? $0.0 : nil },
                 (backoffUntil.map { t < $0 } ?? false) || helperMissing)
            }
            if let hit {
                factsOut[domain] = hit
                d.sent.append(.init(what: domain, to: "the Loupe web helper"))
            } else if !waiting {
                d.sent.append(.init(what: domain, to: "the Loupe web helper"))
                do {
                    let f = try await online.domainFacts(domain)
                    let at = now()
                    lock.withLock { facts[domain] = (f, at) }
                    factsOut[domain] = f
                } catch OnlineCheckError.rateLimited {
                    let until = now().addingTimeInterval(600)
                    lock.withLock { backoffUntil = until }
                } catch OnlineCheckError.notAvailableYet {
                    lock.withLock { helperMissing = true }
                } catch {}
            }
            if let f = factsOut[domain], f.hasFacts {
                d.onlineNotes.append("Online · Loupe web helper · registration facts for \(domain) · fetched \(f.fetchedAt ?? at)")
            }
        }
        var blOut: [String: [String: DnsblResult]] = [:]
        if network, !s.dnsblLists.isEmpty, let domain, let online {
            blOut[domain] = await online.blocklists(domain, lists: s.dnsblLists, at: at)
            let names = s.dnsblLists.compactMap { Dnsbl.shared.zone(id: $0)?.name }.joined(separator: ", ")
            d.sent.append(.init(what: domain, to: "the blocklists \(names) (through this iPhone's DNS resolver)"))
            d.onlineNotes.append("Online · Domain blocklists (\(names)) · fetched \(at)")
        }
        if s.safeBrowsing { d.conditional.append("Google Safe Browsing runs in the app's link check, not in Safari.") }
        let ctx = OnlineContext(nowIso: at, facts: factsOut, feeds: feedIndex, safeBrowsingHits: [], safeBrowsingFetchedAt: nil,
                                feedsFetchedAt: feedsAt, phishingDb: slice, dns: [:], dnsbl: blOut, dnsblFetchedAt: blOut.isEmpty ? nil : at)
        return (ctx, d, wantsNetwork && !network)
    }

    /// This process's memory footprint (what iOS counts against an extension's limit), in MB.
    static func footprintMB() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : -1
    }
}
