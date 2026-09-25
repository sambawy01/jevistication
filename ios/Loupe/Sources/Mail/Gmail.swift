import Foundation
import LoupeKit

// Gmail through the Gmail REST API (v1), read-only: the only scope Loupe asks for is
// `https://www.googleapis.com/auth/gmail.readonly`, and the only calls it makes are GETs
// (users.getProfile, users.messages.list, users.history.list, users.messages.get?format=raw).
// Shapes as documented at developers.google.com/workspace/gmail/api/reference/rest (checked 2026-09-25).
// Loupe never modifies mail.

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

final class GmailClient {
    struct Failure: Error, Equatable, LocalizedError {
        let status: Int
        let detail: String
        var errorDescription: String? { recovery }

        /// The sentence the Mail row shows.
        var recovery: String {
            switch status {
            case 401: return "Google no longer accepts Loupe's sign-in for this Gmail account. Remove the mailbox and sign in with Google again."
            case 403: return "Google refused read access to this Gmail account (\(detail)). Sign in again and allow \"Read your email\"; if it still fails, the Gmail API may not be enabled for Loupe's Google project yet."
            case 429: return "Gmail asked Loupe to slow down. Try again in a few minutes."
            case 0: return "Could not reach Gmail (\(detail)). Check that you are online; Mail is the one source that needs the network."
            default: return "Gmail answered with an error (HTTP \(status)). Try again later."
            }
        }
    }

    static let base = URL(string: "https://gmail.googleapis.com/gmail/v1/users/me/")!

    let accessToken: String
    let session: URLSession

    init(accessToken: String, session: URLSession = .shared) {
        self.accessToken = accessToken
        self.session = session
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

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request(path, query)) } catch {
            throw Failure(status: 0, detail: error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])
                .flatMap { $0["error"] as? [String: Any] }.flatMap { $0["message"] as? String } ?? ""
            throw Failure(status: status, detail: String(message.prefix(160)))
        }
        do { return try JSONDecoder().decode(T.self, from: data) } catch {
            throw Failure(status: -1, detail: "unreadable reply from Gmail")
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
/// search `newer_than:30d`), newest first, at most [maxPerSync]; the mailbox's historyId is taken
/// from users.getProfile *before* listing so nothing arriving meanwhile is lost. Later passes:
/// users.history.list from the stored historyId (messageAdded in INBOX) — only new mail. A 404
/// there (historyId too old) starts a fresh first pass. Each message is kept as a `.eml` under
/// Application Support and read by the shared scanner and MIME parser, labelled Online (PRODUCT §4a).
struct GmailProducer {
    static let historyKey = "gmailHistoryId"
    static let host = "gmail.googleapis.com"

    let account: MailAccount
    let client: GmailClient
    let cacheRoot: URL
    var maxPerSync = 200
    var firstPassWindow = "newer_than:30d"
    var now: () -> Date = Date.init

    var folder: URL { cacheRoot.appendingPathComponent(account.key, isDirectory: true) }

    func scan(state: [String: String], observer: ScanObserver = NullScanObserver(), fetched: () -> Void = {}) async throws -> PhoneScanOutput {
        let fm = FileManager.default
        var ids: [String] = []
        var remaining = 0
        var newHistory: String
        var fresh = true
        if let start = state[Self.historyKey], !start.isEmpty {
            do {
                var token: String?
                var seen = Set<String>()
                var latest = start
                repeat {
                    let page = try await client.history(startHistoryId: start, pageToken: token)
                    for r in page.history ?? [] {
                        for a in r.messagesAdded ?? [] where seen.insert(a.message.id).inserted { ids.append(a.message.id) }
                    }
                    if let h = page.historyId { latest = h }
                    token = page.nextPageToken
                } while token != nil
                newHistory = latest
                fresh = false
                // History is oldest first; keep the newest when capped.
                ids = ids.filter { !fm.fileExists(atPath: folder.appendingPathComponent("\($0).eml").path) }
                if ids.count > maxPerSync { remaining = ids.count - maxPerSync; ids = Array(ids.suffix(maxPerSync)) }
            } catch let f as GmailClient.Failure where f.status == 404 {
                newHistory = ""
            }
        } else {
            newHistory = ""
        }
        if fresh {
            try? fm.removeItem(at: folder)
            newHistory = try await client.profile().historyId
            var token: String?
            repeat {
                let page = try await client.listMessages(query: firstPassWindow, maxResults: min(500, maxPerSync + 1), pageToken: token)
                ids += (page.messages ?? []).map(\.id)
                token = ids.count > maxPerSync ? nil : page.nextPageToken
            } while token != nil
            if ids.count > maxPerSync { remaining = ids.count - maxPerSync; ids = Array(ids.prefix(maxPerSync)) }
        }
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        for id in ids where id.range(of: #"^[A-Za-z0-9]+$"#, options: .regularExpression) != nil {
            let raw = try await client.raw(id: id)
            try raw.write(to: folder.appendingPathComponent("\(id).eml"), options: [.atomic, .completeFileProtection])
        }
        fetched()
        let root = SourceRoot(id: PhoneSourceIds.shared.MAIL, type: .mailExport, path: folder.path, idPrefix: "mail:\(account.key)/")
        var result = try SourceScanner(extractors: AppleExtractors.live()).scan(sources: [root], observer: observer)
        result = PhoneItems.companion.labelOnline(result: result, from: Self.host, fetchedIso: ISOStamp.now(now()))
        if remaining > 0 {
            result = ScanResult(items: result.items, skipped: result.skipped + [Skipped(path: "Mail", reason: "not fetched: \(remaining) more message\(remaining == 1 ? "" : "s") — Loupe reads \(maxPerSync) at a time")], unavailable: result.unavailable)
        }
        return PhoneScanOutput(result: result, state: [Self.historyKey: newHistory])
    }
}
