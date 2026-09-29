import Foundation
import LoupeKit

// Gmail through the Gmail REST API (v1), read-only: the only scope Loupe asks for is
// `https://www.googleapis.com/auth/gmail.readonly`, and the only calls it makes are GETs
// (users.getProfile, users.messages.list, users.history.list, users.messages.get?format=raw).
// Shapes as documented at developers.google.com/workspace/gmail/api/reference/rest (checked 2026-09-25).
// Loupe never modifies mail.
//
// Quota (developers.google.com/workspace/gmail/api/reference/quota, "Last updated 2026-09-10", checked
// 2026-09-27): 6,000 quota units per minute per user per project, 1,200,000 per minute per project;
// messages.get costs 20 units, messages.list 5, history.list 2, getProfile 1. So one user can make at most
// 300 messages.get a minute (5 a second) across every client of Loupe's Google project — the phone and
// Loupe Station on the Mac share it. Errors (…/gmail/api/guides/handle-errors, same date): a rate limit is
// HTTP 429, or a 403 whose `error.errors[].reason` is `rateLimitExceeded` / `userRateLimitExceeded` (domain
// `usageLimits`), or `dailyLimitExceeded`; retry those and 500/502/503/504 with exponential backoff, "at least
// one second after the error". Google's newer error model (AIP-193) adds `error.status` ("RESOURCE_EXHAUSTED"
// for a quota, HTTP 429) and `error.details[]` ErrorInfo `reason`s such as "RATE_LIMIT_EXCEEDED".

/// users.getProfile.
struct GmailProfile: Decodable, Equatable {
    let emailAddress: String
    let historyId: String
}

/// users.messages.list.
struct GmailMessageList: Decodable, Equatable {
    struct Ref: Decodable, Equatable { let id: String }
    let messages: [Ref]?
    let nextPageToken: String?
}

/// users.history.list (only `messagesAdded` is read).
struct GmailHistoryList: Decodable, Equatable {
    struct Added: Decodable, Equatable {
        struct Message: Decodable, Equatable { let id: String; let labelIds: [String]? }
        let message: Message
    }
    struct Record: Decodable, Equatable { let messagesAdded: [Added]? }
    let history: [Record]?
    let nextPageToken: String?
    let historyId: String?
}

/// users.messages.get with format=raw: `raw` is the RFC 2822 message, base64url.
struct GmailRawMessage: Decodable, Equatable {
    let id: String
    let raw: String
}

/// The Gmail client's clock, sleep and numbers, injectable so tests neither wait nor read the real clock.
///
/// Pacing: one request every 0.4 s (2.5 a second). At messages.get's 20 units that is 50 units a second,
/// 3,000 a minute — half the 6,000-unit per-user minute, leaving the other half for Loupe Station on the Mac
/// reading the same account. 200 messages take about 80 s. Sequential, no concurrency.
/// Retries (rate limits and 5xx only): 6 attempts, waits of min(32 s, 1 s · 2ⁿ + up to 1 s of jitter) —
/// about 1, 2, 4, 8, 16 s, some 31–36 s in all, so the last try lands in the next quota minute. A
/// `Retry-After` is honoured instead when present; one longer than 64 s is not waited for in a scan.
struct GmailTiming {
    /// Monotonic seconds.
    var now: () -> TimeInterval
    /// Waits that long; throws `CancellationError` when the task is cancelled.
    var sleep: (TimeInterval) async throws -> Void
    /// In 0..<1, for the jitter.
    var random: () -> Double
    var requestInterval: TimeInterval = 0.4
    var attempts = 6
    var baseDelay: TimeInterval = 1
    var maxDelay: TimeInterval = 32
    var maxRetryAfter: TimeInterval = 64
    /// After a scan that Gmail slowed down, the one automatic retry (the per-user quota is per minute).
    var autoRetryDelay: TimeInterval = 60

    static let live = GmailTiming(
        now: { ProcessInfo.processInfo.systemUptime },
        sleep: { s in try await Task.sleep(nanoseconds: UInt64(max(0, s) * 1_000_000_000)) },
        random: { Double.random(in: 0..<1) })

    /// The wait before retry number `retry` (0 for the first retry).
    func backoff(retry: Int) -> TimeInterval {
        min(maxDelay, baseDelay * pow(2, Double(min(retry, 30))) + random() * baseDelay)
    }
}

final class GmailClient {
    struct Failure: Error, Equatable, LocalizedError {
        enum Kind: Equatable { case network, unreadable, unauthorized, forbidden, notFound, rateLimited, server, other }

        let status: Int
        let detail: String
        let kind: Kind
        var errorDescription: String? { recovery }

        init(status: Int, detail: String, kind: Kind? = nil) {
            self.status = status
            self.detail = detail
            self.kind = kind ?? Self.kind(status: status)
        }

        static func kind(status: Int) -> Kind {
            switch status {
            case 0: return .network
            case -1: return .unreadable
            case 401: return .unauthorized
            case 403: return .forbidden
            case 404: return .notFound
            case 429: return .rateLimited
            case 500...599: return .server
            default: return .other
            }
        }

        /// `error.errors[].reason` (Gmail's documented shape) and `error.details[].reason` (AIP-193 ErrorInfo)
        /// values that mean "slow down", not "no access".
        static let rateLimitReasons: Set<String> = ["rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded",
                                                    "dailyLimitExceeded", "RATE_LIMIT_EXCEEDED", "RESOURCE_EXHAUSTED"]

        /// A non-2xx reply, classified from its status and Google's JSON error body.
        static func from(status: Int, body: Data) -> Failure {
            let error = (try? JSONSerialization.jsonObject(with: body) as? [String: Any]).flatMap { $0["error"] as? [String: Any] }
            let message = error?["message"] as? String ?? ""
            let reasons = ((error?["errors"] as? [[String: Any]] ?? []) + (error?["details"] as? [[String: Any]] ?? []))
                .compactMap { $0["reason"] as? String }
            let lower = message.lowercased()
            let limited = status == 429
                || (error?["status"] as? String) == "RESOURCE_EXHAUSTED"
                || reasons.contains(where: rateLimitReasons.contains)
                || (status == 403 && (lower.contains("quota exceeded") || lower.contains("rate limit exceeded")))
            return Failure(status: status, detail: String(message.prefix(160)), kind: limited ? .rateLimited : nil)
        }

        /// Worth another try after a wait: rate limits and server errors only. Never 401, a real 403 or 404.
        var retryable: Bool { kind == .rateLimited || kind == .server }

        /// The sentence the Mail row shows.
        var recovery: String {
            switch kind {
            case .unauthorized: return "Google no longer accepts Loupe's sign-in for this Gmail account. Remove the mailbox and sign in with Google again."
            case .forbidden: return "Google refused read access to this Gmail account (\(detail)). Sign in again and allow \"Read your email\"; if it still fails, the Gmail API may not be enabled for Loupe's Google project yet."
            case .rateLimited: return GmailProducer.slowDown(fetched: nil, autoRetry: false)
            case .network: return "Could not reach Gmail (\(detail)). Check that you are online; Mail is the one source that needs the network."
            default: return "Gmail answered with an error (HTTP \(status)). Try again later."
            }
        }
    }

    static let base = URL(string: "https://gmail.googleapis.com/gmail/v1/users/me/")!

    let accessToken: String
    let session: URLSession
    let timing: GmailTiming
    private let lock = NSLock()
    private var nextSlot: TimeInterval?

    init(accessToken: String, session: URLSession = .shared, timing: GmailTiming = .live) {
        self.accessToken = accessToken
        self.session = session
        self.timing = timing
    }

    func request(_ path: String, _ query: [URLQueryItem] = []) -> URLRequest {
        var c = URLComponents(url: Self.base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { c.queryItems = query }
        var r = URLRequest(url: c.url!)
        r.httpMethod = "GET"
        r.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        return r
    }

    /// The limiter: request starts at least `requestInterval` apart (retries included).
    private func pace() async throws {
        let wait: TimeInterval = lock.withLock {
            let now = timing.now()
            let slot = max(now, nextSlot ?? now)
            nextSlot = slot + timing.requestInterval
            return slot - now
        }
        if wait > 0 { try await timing.sleep(wait) }
    }

    /// `Retry-After`: delay-seconds or an HTTP-date (RFC 9110 §10.2.3).
    static func retryAfter(_ value: String?, now: Date = Date()) -> TimeInterval? {
        guard let v = value?.trimmingCharacters(in: .whitespaces), !v.isEmpty else { return nil }
        if let s = TimeInterval(v), s >= 0 { return s }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f.date(from: v).map { max(0, $0.timeIntervalSince(now)) }
    }

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        var retry = 0
        while true {
            try Task.checkCancellation()
            try await pace()
            let data: Data, response: URLResponse
            do { (data, response) = try await session.data(for: request(path, query)) } catch {
                if Task.isCancelled { throw CancellationError() }
                throw Failure(status: 0, detail: error.localizedDescription)
            }
            let http = response as? HTTPURLResponse
            let status = http?.statusCode ?? 0
            if (200..<300).contains(status) {
                do { return try JSONDecoder().decode(T.self, from: data) } catch {
                    throw Failure(status: -1, detail: "unreadable reply from Gmail")
                }
            }
            let failure = Failure.from(status: status, body: data)
            guard failure.retryable, retry + 1 < timing.attempts else { throw failure }
            let wait: TimeInterval
            if let after = Self.retryAfter(http?.value(forHTTPHeaderField: "Retry-After")) {
                guard after <= timing.maxRetryAfter else { throw failure }
                wait = after
            } else {
                wait = timing.backoff(retry: retry)
            }
            try await timing.sleep(wait)
            retry += 1
        }
    }

    func profile() async throws -> GmailProfile { try await get("profile") }

    func listMessages(query: String, maxResults: Int, pageToken: String?) async throws -> GmailMessageList {
        var q = [URLQueryItem(name: "labelIds", value: "INBOX"), URLQueryItem(name: "q", value: query),
                 URLQueryItem(name: "maxResults", value: String(maxResults))]
        if let pageToken { q.append(URLQueryItem(name: "pageToken", value: pageToken)) }
        return try await get("messages", q)
    }

    func history(startHistoryId: String, pageToken: String?) async throws -> GmailHistoryList {
        var q = [URLQueryItem(name: "startHistoryId", value: startHistoryId),
                 URLQueryItem(name: "historyTypes", value: "messageAdded"),
                 URLQueryItem(name: "labelId", value: "INBOX"),
                 URLQueryItem(name: "maxResults", value: "500")]
        if let pageToken { q.append(URLQueryItem(name: "pageToken", value: pageToken)) }
        return try await get("history", q)
    }

    /// The message's RFC 2822 bytes.
    func raw(id: String) async throws -> Data {
        let m: GmailRawMessage = try await get("messages/\(id)", [URLQueryItem(name: "format", value: "raw")])
        guard let d = Self.base64url(m.raw) else { throw Failure(status: -1, detail: "a message could not be decoded") }
        return d
    }

    static func base64url(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}

/// The Gmail producer. First pass: the inbox's messages from the last [firstPassWindow] (Gmail
/// search `newer_than:30d`), all of them, newest first; the mailbox's historyId is taken from
/// users.getProfile *before* listing so nothing arriving meanwhile is lost. Later passes:
/// users.history.list from the stored historyId (messageAdded in INBOX) — only new mail, newest first.
/// A 404 there (historyId too old) starts a fresh first pass. Each message is kept as a `.eml` under
/// Application Support and read by the shared scanner and MIME parser, labelled Online (PRODUCT §4a).
///
/// Never drops mail. A scan fetches at most [maxPerSync] messages, newest first (the nightly run many more); a pass with more keeps
/// the rest as a saved pass (its ids, its historyId) and the next scan continues it. Before continuing,
/// that scan asks history for anything newer than the pass's historyId and puts it at the front, so fresh
/// mail never waits behind a backlog. The stored historyId advances only once every id up to it is here.
///
/// Resumable: a message whose `.eml` is already there is not fetched again. When Gmail still says "slow
/// down" after the client's retries, the scan reads what it has, saves the pass as paused and throws
/// [Interrupted]; the next scan continues that pass without asking Gmail for anything else first. A first
/// pass deletes the cached messages outside its window only once the whole window is here.
struct GmailProducer {
    static let historyKey = "gmailHistoryId"
    /// An unfinished pass: its message ids (comma-separated, newest first; a first pass keeps the fetched ones
    /// too, so it knows its whole window), the historyId to save when it completes, "1" for a first pass, and "1"
    /// when a rate limit paused it ("0": it was over [maxPerSync]; the next scan adds newer mail first).
    static let passIdsKey = "gmailPassIds"
    static let passHistoryKey = "gmailPassHistoryId"
    static let passFreshKey = "gmailPassFresh"
    static let passPausedKey = "gmailPassPaused"
    static let host = "gmail.googleapis.com"

    /// The scan stopped because Gmail asked Loupe to slow down: `output` has what was fetched and the state
    /// to continue from.
    struct Interrupted: Error {
        let output: PhoneScanOutput
        let fetched: Int
        let total: Int
        let failure: GmailClient.Failure
    }

    /// One pass: the ids to fetch and what to save once they are all here.
    struct Pass: Equatable {
        var ids: [String]
        var historyId: String
        var fresh: Bool
        var paused: Bool

        init(ids: [String], historyId: String, fresh: Bool, paused: Bool = false) {
            self.ids = ids.filter(GmailProducer.validId)
            self.historyId = historyId
            self.fresh = fresh
            self.paused = paused
        }

        init?(state: [String: String]) {
            guard let ids = state[GmailProducer.passIdsKey], let h = state[GmailProducer.passHistoryKey], !h.isEmpty else { return nil }
            // A pass saved before the paused flag existed was saved by a rate limit.
            self.init(ids: ids.split(separator: ",").map(String.init), historyId: h,
                      fresh: state[GmailProducer.passFreshKey] == "1", paused: state[GmailProducer.passPausedKey] != "0")
        }

        var state: [String: String] {
            [GmailProducer.passIdsKey: ids.joined(separator: ","), GmailProducer.passHistoryKey: historyId,
             GmailProducer.passFreshKey: fresh ? "1" : "0", GmailProducer.passPausedKey: paused ? "1" : "0"]
        }
    }

    /// The Mail row's words when Gmail asked Loupe to slow down. `fetched`: N of M messages of the pass.
    static func slowDown(fetched: (Int, Int)?, autoRetry: Bool) -> String {
        let head = "Gmail asked Loupe to slow down."
        switch (fetched, autoRetry) {
        case let ((n, m)?, true): return "\(head) Loupe fetched \(n) of \(m) messages and will continue in a minute."
        case let ((n, m)?, false): return "\(head) Loupe fetched \(n) of \(m) messages; tap Scan again in a few minutes to continue."
        case (nil, true): return "\(head) Loupe will try again in a minute."
        case (nil, false): return "\(head) Tap Scan again in a few minutes."
        }
    }

    static func validId(_ id: String) -> Bool { id.range(of: #"^[A-Za-z0-9]+$"#, options: .regularExpression) != nil }

    let account: MailAccount
    let client: GmailClient
    let cacheRoot: URL
    /// Messages fetched per scan: [MailCap.scan], or [MailCap.overnight] in the nightly run.
    var maxPerSync = MailCap.scan
    var firstPassWindow = "newer_than:30d"
    var now: () -> Date = Date.init

    var folder: URL { cacheRoot.appendingPathComponent(account.key, isDirectory: true) }

    private func file(_ id: String) -> URL { folder.appendingPathComponent("\(id).eml") }
    private func have(_ id: String) -> Bool { FileManager.default.fileExists(atPath: file(id).path) }

    /// `progress(n, m)`: n of this scan's m messages are here — the pass's cached ones plus at most
    /// [maxPerSync] to fetch (the fetch is paced, so 200 take about 80 s). `shouldStop` (the run's Cancel, or
    /// iOS ending the nightly run) is asked before each message: the rest stays queued, as over the cap.
    func scan(state: [String: String], observer: ScanObserver = NullScanObserver(), fetched: () -> Void = {},
              shouldStop: () -> Bool = { false }, progress: (Int, Int) -> Void = { _, _ in }) async throws -> PhoneScanOutput {
        let pass: Pass
        if let saved = Pass(state: state) {
            pass = saved.paused ? saved : try await withNewer(saved)
        } else if let start = state[Self.historyKey], !start.isEmpty, let incremental = try await historyPass(from: start) {
            pass = incremental
        } else {
            pass = try await firstPass()
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // Newest first; the rest stays in the pass for the next scan.
        let todo = pass.ids.filter { !have($0) }
        let batch = todo.prefix(max(0, maxPerSync))
        var here = pass.ids.count - todo.count
        let total = here + batch.count
        var gone = Set<String>()
        var interrupted: GmailClient.Failure?
        progress(here, total)
        for id in batch {
            if shouldStop() { break }
            do {
                let raw = try await client.raw(id: id)
                try raw.write(to: file(id), options: [.atomic, .completeFileProtection])
                here += 1
                progress(here, total)
            } catch let f as GmailClient.Failure where f.kind == .notFound {
                gone.insert(id) // deleted since it was listed
            } catch let f as GmailClient.Failure where f.kind == .rateLimited {
                interrupted = f
                break
            }
        }
        var left = pass
        left.ids = pass.ids.filter { !gone.contains($0) }
        let waiting = left.ids.filter { !have($0) }.count
        // Only once the whole window is here: a queued message must not lose its cached copy.
        if interrupted == nil, waiting == 0, pass.fresh { prune(keeping: Set(left.ids)) }
        fetched()
        let root = SourceRoot(id: PhoneSourceIds.shared.MAIL, type: .mailExport, path: folder.path, idPrefix: "mail:\(account.key)/")
        var result = try SourceScanner(extractors: AppleExtractors.live()).scan(sources: [root], observer: observer)
        result = PhoneItems.companion.labelOnline(result: result, from: Self.host, fetchedIso: ISOStamp.now(now()))
        guard waiting > 0 else { return PhoneScanOutput(result: result, state: [Self.historyKey: pass.historyId]) }
        // Not all here: keep the pass (and the old historyId) so the next scan continues it. A first pass keeps
        // its whole window (prune needs it); a history pass only what is still missing.
        if !pass.fresh { left.ids = left.ids.filter { !have($0) } }
        left.paused = interrupted != nil
        var st = left.state
        if let h = state[Self.historyKey], !h.isEmpty { st[Self.historyKey] = h }
        let plural = waiting == 1 ? "" : "s"
        if let f = interrupted {
            result = ScanResult(items: result.items, skipped: result.skipped + [Skipped(path: "Mail", reason: "not fetched yet: \(waiting) message\(plural) — Gmail asked Loupe to slow down; the next scan continues")], unavailable: result.unavailable)
            throw Interrupted(output: PhoneScanOutput(result: result, state: st), fetched: here, total: total - gone.count, failure: f)
        }
        result = ScanResult(items: result.items, skipped: result.skipped + [Skipped(path: "Mail", reason: "not fetched yet: \(waiting) older message\(plural) — Loupe reads \(maxPerSync) at a time, newest first; the rest are fetched on the next scan or overnight while charging")], unavailable: result.unavailable)
        return PhoneScanOutput(result: result, state: st)
    }

    /// A saved pass with the mail that arrived since its historyId put in front (newest first). When Gmail
    /// no longer has that history, or asks Loupe to slow down, the pass continues as it is.
    private func withNewer(_ saved: Pass) async throws -> Pass {
        let newer: (ids: [String], latest: String)?
        do {
            newer = try await added(since: saved.historyId)
        } catch let f as GmailClient.Failure where f.kind == .rateLimited {
            return saved
        }
        guard let newer else { return saved }
        let known = Set(saved.ids)
        return Pass(ids: newer.ids.filter { !known.contains($0) } + saved.ids, historyId: newer.latest, fresh: saved.fresh)
    }

    /// New mail since `start`, newest first; nil when Gmail no longer has that history (404).
    private func historyPass(from start: String) async throws -> Pass? {
        guard let newer = try await added(since: start) else { return nil }
        return Pass(ids: newer.ids.filter { !have($0) }, historyId: newer.latest, fresh: false)
    }

    /// The messages added to the inbox since `start`, newest first, and the mailbox's historyId now;
    /// nil when Gmail no longer has that history (404).
    private func added(since start: String) async throws -> (ids: [String], latest: String)? {
        var ids: [String] = []
        var seen = Set<String>()
        var latest = start
        var token: String?
        do {
            repeat {
                let page = try await client.history(startHistoryId: start, pageToken: token)
                for r in page.history ?? [] {
                    for a in r.messagesAdded ?? [] where seen.insert(a.message.id).inserted { ids.append(a.message.id) }
                }
                if let h = page.historyId { latest = h }
                token = page.nextPageToken
            } while token != nil
        } catch let f as GmailClient.Failure where f.kind == .notFound {
            return nil
        }
        // History is oldest first.
        return (ids.reversed(), latest)
    }

    /// The whole window's messages, newest first (the list is not capped: the scan fetches [maxPerSync] at a
    /// time and keeps the rest). Cached messages are kept (not re-fetched).
    private func firstPass() async throws -> Pass {
        let historyId = try await client.profile().historyId
        var ids: [String] = []
        var seen = Set<String>()
        var token: String?
        repeat {
            let page = try await client.listMessages(query: firstPassWindow, maxResults: 500, pageToken: token)
            for m in page.messages ?? [] where seen.insert(m.id).inserted { ids.append(m.id) }
            token = page.nextPageToken
        } while token != nil
        return Pass(ids: ids, historyId: historyId, fresh: true)
    }

    /// After a complete first pass: the cached messages outside its window go, so the cache does not grow.
    private func prune(keeping ids: Set<String>) {
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        where url.pathExtension == "eml" && !ids.contains(url.deletingPathExtension().lastPathComponent) {
            try? fm.removeItem(at: url)
        }
    }
}
