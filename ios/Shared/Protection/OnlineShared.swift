import Foundation
import LoupeKit

// Moved from ios/Loupe/Online/OnlineChecks.swift on 2026-09-26 so that Loupe for Safari and
// "Send to Loupe" read the same switches and ask the helper the same way (browsing protection).

/// The web helper's address, shared by the app (`HelperEndpoint`) and the extensions.
enum LoupeHelper {
    static let base = URL(string: "https://loupe-web-helper-production.up.railway.app")!
}

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

    /// The switches as the app last saved them into the App Group (the extensions' read). Off by
    /// default: a phone where the app never mirrored them reads every online check as off.
    static func loadShared(_ d: UserDefaults = ProtectionGroup.defaults) -> OnlinePhishingSettings { load(d) }

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

    init(base: URL = LoupeHelper.base, session: URLSession) {
        self.base = base
        self.session = session
    }

    /// The request for one domain: the JSON body `{"domain": d}` and nothing about the user. The
    /// helper's rate limiter wants an `X-Loupe-Install`; a fresh random UUID per request means two
    /// lookups cannot be linked to one phone.
    static func request(domain: String, base: URL = LoupeHelper.base, timeout: TimeInterval = DomainFactsClient.timeout) -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("v1/domain-facts"), timeoutInterval: timeout)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue(UUID().uuidString, forHTTPHeaderField: "X-Loupe-Install")
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["domain": domain], options: [.sortedKeys])
        return r
    }

    /// [timeout]: the app waits 15 s; Loupe for Safari waits less, so a page is never held up long.
    func facts(for domain: String, timeout: TimeInterval = DomainFactsClient.timeout) async throws -> DomainFacts {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: Self.request(domain: domain, base: base, timeout: timeout)) }
        catch { throw OnlineCheckError.offline }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw OnlineCheckError.from(status: status, data: data) }
        do { return try OnlineSignals.shared.parseDomainFacts(json: String(decoding: data, as: UTF8.self), domain: domain) }
        catch { throw OnlineCheckError.unexpected("The helper sent a reply this version cannot read.") }
    }
}

