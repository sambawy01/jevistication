import XCTest
import LoupeKit
@testable import Loupe

/// Fake Google endpoints: responses in the documented Gmail API / OAuth token shapes. No network.
final class FakeGoogle: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _requests: [(URLRequest, Data)] = []
    nonisolated(unsafe) private static var _handler: (URLRequest, Data) -> (Int, Data) = { _, _ in (500, Data()) }
    nonisolated(unsafe) private static var _headers: (URLRequest) -> [String: String] = { _ in [:] }
    static var requests: [(URLRequest, Data)] {
        get { lock.withLock { _requests } }
        set { lock.withLock { _requests = newValue } }
    }
    static var handler: (URLRequest, Data) -> (Int, Data) {
        get { lock.withLock { _handler } }
        set { lock.withLock { _handler = newValue } }
    }

    /// Extra response headers (e.g. Retry-After).
    static var headers: (URLRequest) -> [String: String] {
        get { lock.withLock { _headers } }
        set { lock.withLock { _headers = newValue } }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let body = request.httpBody ?? request.httpBodyStream.map(FakeOnline.read) ?? Data()
        Self.lock.withLock { Self._requests.append((request, body)) }
        let (status, data) = Self.handler(request, body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"].merging(Self.headers(request)) { $1 })!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static var session: URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeGoogle.self]
        return URLSession(configuration: c)
    }
}

final class GmailTests: XCTestCase {
    private var home: URL!
    private var keys: [String: MemoryKeyStore] = [:]
    private let clientId = "123-abc.apps.googleusercontent.com"

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("Gmail-\(UUID().uuidString)")
        FakeGoogle.requests = []
        FakeGoogle.headers = { _ in [:] }
        keys = [:]
        clock = FakeGmailClock()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private var clock = FakeGmailClock()

    @MainActor
    private func service(clientId: String? = nil) -> SourcesService {
        let deps = PhoneDependencies(
            photos: FakePhotoLibrary(), recognizer: FakeRecognizer(text: [:]), events: FakeEventStore(),
            contacts: FakeContactStore(), bookmarks: BookmarkStore(home: home, resolver: FakeBookmarks()),
            inbox: { [home] in home!.appendingPathComponent("inbox") }, mailAccounts: MailAccountStore(home: home),
            mailCache: home.appendingPathComponent("mail"),
            keychain: { [unowned self] a in
                let k = a.keychain().account
                if let s = self.keys[k] { return s }
                let s = MemoryKeyStore(); self.keys[k] = s; return s
            },
            makeTransport: { _ in fatalError("Gmail must not use IMAP") },
            oauth: OAuthConfig(googleClientId: clientId ?? self.clientId, microsoftClientId: ""),
            state: PhoneStateStore(home: home), http: FakeGoogle.session, gmailTiming: clock.timing)
        return SourcesService(home: home, sampleRoot: nil, deps: deps)
    }

    static func eml(_ subject: String, from: String = "Mum <mum@example.com>") -> String {
        let raw = "From: \(from)\r\nTo: me@gmail.com\r\nSubject: \(subject)\r\nDate: Thu, 24 Sep 2026 09:00:00 +0000\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nBody of \(subject).\r\n"
        return PKCE.base64url(Data(raw.utf8))
    }

    static func json(_ o: Any) -> Data { try! JSONSerialization.data(withJSONObject: o) }

    /// A fake mailbox: messages m1, m2 in the first pass; m3 added later (history 200 → 300).
    private func mailbox(historyGone: Bool = false) {
        FakeGoogle.handler = { req, body in
            let url = req.url!
            let q = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
            if url.host == "oauth2.googleapis.com" {
                return (200, Self.json(["access_token": "fresh-access", "expires_in": 3599, "token_type": "Bearer",
                                        "scope": OAuthProvider.gmailReadonly]))
            }
            XCTAssertEqual(req.httpMethod, "GET", "Gmail is read-only: GETs only")
            XCTAssertTrue(req.value(forHTTPHeaderField: "Authorization")?.hasPrefix("Bearer ") == true)
            switch url.path {
            case "/gmail/v1/users/me/profile":
                return (200, Self.json(["emailAddress": "me@gmail.com", "messagesTotal": 3, "threadsTotal": 3, "historyId": "200"]))
            case "/gmail/v1/users/me/messages":
                XCTAssertEqual(q["q"], "newer_than:30d")
                XCTAssertEqual(q["labelIds"], "INBOX")
                return (200, Self.json(["messages": [["id": "m2", "threadId": "t2"], ["id": "m1", "threadId": "t1"]], "resultSizeEstimate": 2]))
            case "/gmail/v1/users/me/history":
                if historyGone { return (404, Self.json(["error": ["code": 404, "message": "Requested entity was not found."]])) }
                XCTAssertEqual(q["startHistoryId"], "200")
                XCTAssertEqual(q["historyTypes"], "messageAdded")
                return (200, Self.json(["history": [["id": "250", "messages": [["id": "m3", "threadId": "t3"]],
                                                     "messagesAdded": [["message": ["id": "m3", "threadId": "t3", "labelIds": ["INBOX"]]]]]],
                                        "historyId": "300"]))
            default:
                let id = url.lastPathComponent
                XCTAssertEqual(q["format"], "raw")
                return (200, Self.json(["id": id, "threadId": "t", "labelIds": ["INBOX"], "historyId": "199",
                                        "internalDate": "1790000000000", "sizeEstimate": 300, "raw": Self.eml("Subject \(id)")]))
            }
        }
    }

    private func paths() -> [String] { FakeGoogle.requests.map { $0.0.url!.path } }

    @MainActor
    func testSignInThenFirstPassThenHistoryIncremental() async throws {
        mailbox()
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: "r0", expiresIn: 3599), username: "")
        XCTAssertNil(s.state(.mail).problem)
        let account = try XCTUnwrap(s.mailAccount)
        XCTAssertEqual(account.auth, .gmailAPI)
        XCTAssertEqual(account.username, "me@gmail.com", "the address comes from users.getProfile")
        XCTAssertEqual(account.host, "gmail.googleapis.com")
        let stored = try XCTUnwrap(keys[account.keychain().account]?.read())
        XCTAssertTrue(stored.contains("r0"), "the refresh token is kept in the key store")
        XCTAssertFalse(try String(contentsOf: home.appendingPathComponent("sources/mail-account.json")).contains("r0"))

        var mail = s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }
        XCTAssertEqual(mail.count, 2)
        XCTAssertTrue(mail.allSatisfy { ($0.facts["online"] ?? "").hasPrefix("Online · gmail.googleapis.com · fetched ") })
        XCTAssertTrue(mail.contains { $0.text.contains("Body of Subject m1") })

        FakeGoogle.requests = []
        await s.scanPhone(.mail)
        XCTAssertNil(s.state(.mail).problem)
        mail = s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }
        XCTAssertEqual(mail.count, 3)
        XCTAssertFalse(paths().contains("/gmail/v1/users/me/messages"), "no re-listing once a historyId is stored")
        XCTAssertEqual(paths().filter { $0.hasPrefix("/gmail/v1/users/me/messages/") }, ["/gmail/v1/users/me/messages/m3"], "only the new message is fetched")
        // The refresh used the stored refresh token and the stored access token was replaced.
        let refresh = String(decoding: try XCTUnwrap(FakeGoogle.requests.first { $0.0.url?.host == "oauth2.googleapis.com" }).1, as: UTF8.self)
        XCTAssertTrue(refresh.contains("grant_type=refresh_token") && refresh.contains("refresh_token=r0") && refresh.contains("client_id=\(clientId)"), refresh)
        XCTAssertFalse(refresh.contains("client_secret"))
        let kept = try JSONDecoder().decode(OAuthTokens.self, from: Data(try XCTUnwrap(keys[account.keychain().account]?.read()).utf8))
        XCTAssertEqual(kept, OAuthTokens(accessToken: "fresh-access", refreshToken: "r0", expiresIn: 3599), "Google omits refresh_token on refresh; the old one is kept")
        XCTAssertEqual(PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)[GmailProducer.historyKey], "300")
    }

    @MainActor
    func testExpiredHistoryIdStartsAFreshPass() async throws {
        mailbox(historyGone: true)
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: "r0", expiresIn: 3599), username: "")
        FakeGoogle.requests = []
        await s.scanPhone(.mail)
        XCTAssertNil(s.state(.mail).problem)
        XCTAssertTrue(paths().contains("/gmail/v1/users/me/history"))
        XCTAssertTrue(paths().contains("/gmail/v1/users/me/messages"), "404 on history → list again")
        XCTAssertEqual(s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }.count, 2)
    }

    @MainActor
    func testUnauthorizedShowsSignInAgain() async throws {
        mailbox()
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: nil, expiresIn: nil), username: "")
        FakeGoogle.handler = { _, _ in (401, Self.json(["error": ["code": 401, "message": "Invalid Credentials"]])) }
        await s.scanPhone(.mail)
        XCTAssertTrue(s.state(.mail).problem?.contains("sign in with Google again") == true, s.state(.mail).problem ?? "")
    }

    @MainActor
    func testNoClientIdMakesNoRequest() async {
        let s = service(clientId: "")
        do { try await s.signInMail(.google, username: ""); XCTFail("expected the gate") } catch {
            XCTAssertTrue(error.localizedDescription.contains("Needs a Google OAuth client ID"))
        }
        XCTAssertTrue(FakeGoogle.requests.isEmpty)
    }

    func testTokenExchangeDecodesAndRefusesErrors() async throws {
        let flow = OAuthFlow(provider: .google, clientId: clientId)
        FakeGoogle.handler = { _, _ in (200, Self.json(["access_token": "at", "refresh_token": "rt", "expires_in": 3599,
                                                         "scope": OAuthProvider.gmailReadonly, "token_type": "Bearer"])) }
        let tokens = try await flow.exchange(flow.tokenRequest(code: "c", pkce: PKCE(verifier: "v")), session: FakeGoogle.session)
        XCTAssertEqual(tokens, OAuthTokens(accessToken: "at", refreshToken: "rt", expiresIn: 3599))
        let body = String(decoding: FakeGoogle.requests.last!.1, as: UTF8.self)
        XCTAssertTrue(body.contains("redirect_uri=com.googleusercontent.apps.123-abc%3A%2Foauth2redirect"), body)
        FakeGoogle.handler = { _, _ in (400, Self.json(["error": "invalid_grant"])) }
        do { _ = try await flow.exchange(flow.refreshRequest(refreshToken: "x"), session: FakeGoogle.session); XCTFail() } catch {}
    }

    func testBase64urlRoundTrip() {
        let d = Data((0..<255).map(UInt8.init))
        XCTAssertEqual(GmailClient.base64url(PKCE.base64url(d)), d)
    }

    // MARK: Rate limits, retries, pacing, resume (2026-09-27)

    /// Google's documented error bodies (developers.google.com/workspace/gmail/api/guides/handle-errors) and the
    /// owner's device report (a 403 "Quota exceeded for quota metric 'Total Query Cost'…").
    static func googleError(_ code: Int, _ message: String, reason: String? = nil, status: String? = nil, domain: String = "usageLimits") -> Data {
        var e: [String: Any] = ["code": code, "message": message]
        if let reason { e["errors"] = [["domain": domain, "reason": reason, "message": message]] }
        if let status { e["status"] = status }
        return json(["error": e])
    }

    static let quotaMessage = "Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user' of service 'gmail.googleapis.com' for consumer 'project_number:123'."

    private func client() -> GmailClient { GmailClient(accessToken: "a", session: FakeGoogle.session, timing: clock.timing) }

    private static func rawReply(_ id: String) -> (Int, Data) {
        (200, json(["id": id, "threadId": "t", "raw": eml("Subject \(id)")]))
    }

    func testClassifiesRateLimitsApartFromPermission() {
        typealias F = GmailClient.Failure
        XCTAssertEqual(F.from(status: 429, body: Self.googleError(429, "Too many requests", reason: "rateLimitExceeded")).kind, .rateLimited)
        XCTAssertEqual(F.from(status: 403, body: Self.googleError(403, "User Rate Limit Exceeded", reason: "userRateLimitExceeded")).kind, .rateLimited)
        XCTAssertEqual(F.from(status: 403, body: Self.googleError(403, "Rate Limit Exceeded", reason: "rateLimitExceeded")).kind, .rateLimited)
        XCTAssertEqual(F.from(status: 403, body: Self.googleError(403, Self.quotaMessage, reason: "rateLimitExceeded", status: "PERMISSION_DENIED", domain: "global")).kind, .rateLimited)
        XCTAssertEqual(F.from(status: 403, body: Self.googleError(403, Self.quotaMessage, status: "PERMISSION_DENIED")).kind, .rateLimited, "the message alone")
        XCTAssertEqual(F.from(status: 429, body: Self.json(["error": ["code": 429, "message": "x", "status": "RESOURCE_EXHAUSTED",
                                                                       "details": [["@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "RATE_LIMIT_EXCEEDED"]]]])).kind, .rateLimited)
        let permission = F.from(status: 403, body: Self.googleError(403, "Request had insufficient authentication scopes.", reason: "insufficientPermissions", status: "PERMISSION_DENIED", domain: "global"))
        XCTAssertEqual(permission.kind, .forbidden)
        XCTAssertFalse(permission.retryable)
        XCTAssertTrue(permission.recovery.hasPrefix("Google refused read access"), permission.recovery)
        let limited = F.from(status: 403, body: Self.googleError(403, Self.quotaMessage, reason: "rateLimitExceeded"))
        XCTAssertTrue(limited.recovery.hasPrefix("Gmail asked Loupe to slow down."), limited.recovery)
        XCTAssertFalse(limited.recovery.contains("Sign in again"))
        XCTAssertEqual(F.from(status: 503, body: Data()).kind, .server)
        XCTAssertTrue(F.from(status: 503, body: Data()).retryable)
        XCTAssertFalse(F.from(status: 404, body: Data()).retryable)
        XCTAssertFalse(F.from(status: 401, body: Data()).retryable)
    }

    func test429ThenSuccessRetriesAndSucceeds() async throws {
        let calls = GmailCounter()
        FakeGoogle.handler = { req, _ in
            calls.bump() == 1 ? (429, Self.googleError(429, "Too many requests", reason: "rateLimitExceeded")) : Self.rawReply(req.url!.lastPathComponent)
        }
        var t = clock.timing; t.requestInterval = 0
        let data = try await GmailClient(accessToken: "a", session: FakeGoogle.session, timing: t).raw(id: "m1")
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains("Subject m1"))
        XCTAssertEqual(FakeGoogle.requests.count, 2)
        XCTAssertEqual(clock.sleeps, [1], "the first backoff: 1 s · 2⁰ (+ jitter, 0 here)")
    }

    func testUserRateLimit403IsRetriedNotAPermissionError() async throws {
        let calls = GmailCounter()
        FakeGoogle.handler = { req, _ in
            calls.bump() <= 2 ? (403, Self.googleError(403, "User Rate Limit Exceeded", reason: "userRateLimitExceeded")) : Self.rawReply(req.url!.lastPathComponent)
        }
        var t = clock.timing; t.requestInterval = 0; t.attempts = 5
        _ = try await GmailClient(accessToken: "a", session: FakeGoogle.session, timing: t).raw(id: "m1")
        XCTAssertEqual(FakeGoogle.requests.count, 3)
        XCTAssertEqual(clock.sleeps, [1, 2], "exponential")
    }

    func testPermission403IsNotRetriedAndKeepsItsText() async {
        FakeGoogle.handler = { _, _ in (403, Self.googleError(403, "Request had insufficient authentication scopes.", reason: "insufficientPermissions", status: "PERMISSION_DENIED", domain: "global")) }
        do { _ = try await client().raw(id: "m1"); XCTFail("expected a failure") } catch let f as GmailClient.Failure {
            XCTAssertEqual(f.kind, .forbidden)
            XCTAssertTrue(f.recovery.contains("Sign in again and allow \"Read your email\""), f.recovery)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(FakeGoogle.requests.count, 1, "never retried")
        XCTAssertTrue(clock.sleeps.isEmpty)
    }

    func testNotFoundAndUnauthorizedAreNotRetried() async {
        for status in [401, 404] {
            FakeGoogle.requests = []
            FakeGoogle.handler = { _, _ in (status, Self.googleError(status, "x")) }
            do { _ = try await client().profile(); XCTFail() } catch {}
            XCTAssertEqual(FakeGoogle.requests.count, 1, "HTTP \(status)")
        }
    }

    func testRetryAfterIsHonoured() async throws {
        let calls = GmailCounter()
        FakeGoogle.headers = { _ in calls.value == 1 ? ["Retry-After": "7"] : [:] }
        FakeGoogle.handler = { req, _ in
            calls.bump() == 1 ? (429, Self.googleError(429, "Too many requests", reason: "rateLimitExceeded")) : Self.rawReply(req.url!.lastPathComponent)
        }
        var t = clock.timing; t.requestInterval = 0
        _ = try await GmailClient(accessToken: "a", session: FakeGoogle.session, timing: t).raw(id: "m1")
        XCTAssertEqual(clock.sleeps, [7], "Retry-After, not the backoff")
        XCTAssertEqual(GmailClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT", now: Date(timeIntervalSince1970: 1792567650)), 30)
        XCTAssertNil(GmailClient.retryAfter("soon"))
    }

    func testRetryAfterTooLongIsNotWaitedFor() async {
        FakeGoogle.headers = { _ in ["Retry-After": "3600"] }
        FakeGoogle.handler = { _, _ in (429, Self.googleError(429, "Too many requests", reason: "rateLimitExceeded")) }
        do { _ = try await client().raw(id: "m1"); XCTFail() } catch let f as GmailClient.Failure {
            XCTAssertEqual(f.kind, .rateLimited)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(FakeGoogle.requests.count, 1)
    }

    func testRetriesStopAfterTheLastAttempt() async {
        FakeGoogle.handler = { _, _ in (503, Self.googleError(503, "Backend Error", reason: "backendError", domain: "global")) }
        var t = clock.timing; t.requestInterval = 0; t.attempts = 6
        do { _ = try await GmailClient(accessToken: "a", session: FakeGoogle.session, timing: t).profile(); XCTFail() } catch let f as GmailClient.Failure {
            XCTAssertEqual(f.kind, .server)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(FakeGoogle.requests.count, 6)
        XCTAssertEqual(clock.sleeps, [1, 2, 4, 8, 16])
        XCTAssertEqual(GmailTiming.live.backoff(retry: 10), 32, "capped")
    }

    func testLimiterSpacesRequests() async throws {
        let clock = self.clock
        let times = GmailBox<[TimeInterval]>([])
        FakeGoogle.handler = { req, _ in
            times.update { $0.append(clock.now) }
            return Self.rawReply(req.url!.lastPathComponent)
        }
        let c = client()
        for id in ["m1", "m2", "m3", "m4", "m5"] { _ = try await c.raw(id: id) }
        let t = times.value
        XCTAssertEqual(t.count, 5)
        for (a, b) in zip(t, t.dropFirst()) { XCTAssertEqual(b - a, 0.4, accuracy: 1e-9) }
        XCTAssertEqual(clock.sleeps.count, 4, "no wait before the first request")
        for w in clock.sleeps { XCTAssertEqual(w, 0.4, accuracy: 1e-9) }
    }

    func testCancellationStopsTheBackoff() async throws {
        FakeGoogle.handler = { _, _ in (429, Self.googleError(429, "Too many requests", reason: "rateLimitExceeded")) }
        var t = GmailTiming.live
        t.baseDelay = 100
        t.maxDelay = 100
        let c = GmailClient(accessToken: "a", session: FakeGoogle.session, timing: t)
        let started = Date()
        let task = Task { try await c.raw(id: "m1") }
        while FakeGoogle.requests.isEmpty { try await Task.sleep(nanoseconds: 5_000_000) }
        task.cancel()
        do { _ = try await task.value; XCTFail("expected cancellation") } catch {
            XCTAssertTrue(error is CancellationError, "\(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
        XCTAssertEqual(FakeGoogle.requests.count, 1)
    }

    /// A mailbox of m1…m4 whose messages in `limited` answer 403 userRateLimitExceeded; m5 is added by history.
    private func quotaMailbox(limited: GmailBox<Set<String>>) {
        FakeGoogle.handler = { req, _ in
            let url = req.url!
            if url.host == "oauth2.googleapis.com" {
                return (200, Self.json(["access_token": "fresh-access", "expires_in": 3599, "token_type": "Bearer"]))
            }
            switch url.path {
            case "/gmail/v1/users/me/profile":
                return (200, Self.json(["emailAddress": "me@gmail.com", "historyId": "200"]))
            case "/gmail/v1/users/me/messages":
                return (200, Self.json(["messages": ["m1", "m2", "m3", "m4"].map { ["id": $0, "threadId": "t"] }]))
            case "/gmail/v1/users/me/history":
                return (200, Self.json(["history": [["id": "250", "messagesAdded": [["message": ["id": "m5", "threadId": "t"]]]]], "historyId": "300"]))
            default:
                let id = url.lastPathComponent
                if limited.value.contains(id) { return (403, Self.googleError(403, Self.quotaMessage, reason: "rateLimitExceeded")) }
                return Self.rawReply(id)
            }
        }
    }

    private func gets() -> [String] { paths().filter { $0.hasPrefix("/gmail/v1/users/me/messages/") }.map { ($0 as NSString).lastPathComponent } }

    @MainActor
    func testInterruptedFirstPassShowsWhatItHasAndResumesWithoutRefetching() async throws {
        let limited = GmailBox<Set<String>>(["m3"])
        quotaMailbox(limited: limited)
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: "r0", expiresIn: 3599), username: "")
        defer { s.deps.gmailRetry.cancel() }
        XCTAssertEqual(s.state(.mail).problem, "Gmail asked Loupe to slow down. Loupe fetched 2 of 4 messages and will continue in a minute.")
        XCTAssertFalse(s.state(.mail).problem?.contains("Sign in again") == true)
        XCTAssertEqual(s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }.count, 2, "the fetched messages are shown")
        XCTAssertNotNil(s.deps.gmailRetry.task, "one automatic retry is scheduled")
        var st = PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)
        XCTAssertNil(st[GmailProducer.historyKey], "no historyId before the pass completes")
        XCTAssertEqual(st[GmailProducer.passIdsKey], "m1,m2,m3,m4")
        XCTAssertEqual(st[GmailProducer.passHistoryKey], "200")
        XCTAssertEqual(gets().filter { $0 == "m3" }.count, 3, "retried up to the attempt limit")
        XCTAssertFalse(gets().contains("m4"), "stops at the rate limit")

        limited.value = []
        FakeGoogle.requests = []
        await s.scanPhone(.mail)
        XCTAssertNil(s.state(.mail).problem)
        XCTAssertNil(s.deps.gmailRetry.task, "a successful scan cancels the automatic retry")
        XCTAssertFalse(paths().contains("/gmail/v1/users/me/messages"), "no re-listing")
        XCTAssertFalse(paths().contains("/gmail/v1/users/me/profile"))
        XCTAssertEqual(gets(), ["m3", "m4"], "only what was missing")
        XCTAssertEqual(s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }.count, 4)
        st = PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)
        XCTAssertEqual(st, [GmailProducer.historyKey: "200"], "historyId saved once the pass is complete; the pass is cleared")
    }

    @MainActor
    func testInterruptedHistoryPassKeepsTheOldHistoryId() async throws {
        let limited = GmailBox<Set<String>>([])
        quotaMailbox(limited: limited)
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: "r0", expiresIn: 3599), username: "")
        defer { s.deps.gmailRetry.cancel() }
        XCTAssertEqual(PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)[GmailProducer.historyKey], "200")
        limited.value = ["m5"]
        await s.scanPhone(.mail)
        XCTAssertEqual(s.state(.mail).problem, "Gmail asked Loupe to slow down. Loupe fetched 0 of 1 messages and will continue in a minute.")
        var st = PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)
        XCTAssertEqual(st[GmailProducer.historyKey], "200", "not advanced to 300 while m5 is missing")
        XCTAssertEqual(st[GmailProducer.passHistoryKey], "300")
        XCTAssertEqual(s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }.count, 4)
        limited.value = []
        FakeGoogle.requests = []
        await s.scanPhone(.mail)
        XCTAssertNil(s.state(.mail).problem)
        XCTAssertFalse(paths().contains("/gmail/v1/users/me/history"), "the saved pass is continued")
        XCTAssertEqual(gets(), ["m5"])
        st = PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)
        XCTAssertEqual(st, [GmailProducer.historyKey: "300"])
    }

    @MainActor
    func testTheAutomaticRetryRunsOnceAndIsNotChained() async throws {
        let limited = GmailBox<Set<String>>(["m1", "m2", "m3", "m4"])
        quotaMailbox(limited: limited)
        clock.autoRetryNanos = 20_000_000
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: "r0", expiresIn: 3599), username: "")
        defer { s.deps.gmailRetry.cancel() }
        XCTAssertEqual(s.state(.mail).problem, "Gmail asked Loupe to slow down. Loupe fetched 0 of 4 messages and will continue in a minute.")
        let second = "Gmail asked Loupe to slow down. Loupe fetched 0 of 4 messages; tap Scan again in a few minutes to continue."
        for _ in 0..<300 where s.state(.mail).problem != second { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertEqual(s.state(.mail).problem, second, "the automatic retry ran and, slowed down again, schedules no other")
        XCTAssertNil(s.deps.gmailRetry.task)
        // Scan again still works, and schedules the automatic retry again.
        limited.value = ["m4"]
        await s.scanPhone(.mail)
        XCTAssertEqual(s.state(.mail).problem, "Gmail asked Loupe to slow down. Loupe fetched 3 of 4 messages and will continue in a minute.")
        limited.value = []
        for _ in 0..<300 where s.state(.mail).problem != nil || s.state(.mail).scanning || s.deps.gmailRetry.task != nil {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertNil(s.state(.mail).problem, "the automatic retry finished the pass")
        XCTAssertEqual(s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }.count, 4)
    }

    @MainActor
    func testRateLimitedListingSaysSlowDownNotSignIn() async throws {
        let limited = GmailBox<Set<String>>([])
        quotaMailbox(limited: limited)
        let s = service()
        try await s.saveOAuthMail(.google, tokens: OAuthTokens(accessToken: "a0", refreshToken: "r0", expiresIn: 3599), username: "")
        defer { s.deps.gmailRetry.cancel() }
        FakeGoogle.handler = { req, _ in
            req.url?.host == "oauth2.googleapis.com" ? (200, Self.json(["access_token": "x", "expires_in": 3599]))
                : (403, Self.googleError(403, Self.quotaMessage, reason: "rateLimitExceeded"))
        }
        await s.scanPhone(.mail)
        XCTAssertEqual(s.state(.mail).problem, "Gmail asked Loupe to slow down. Loupe will try again in a minute.")
        XCTAssertEqual(PhoneStateStore(home: home).state(PhoneSource.mail.rawValue)[GmailProducer.historyKey], "200", "state untouched")
    }

    @MainActor
    func testACompleteFirstPassDropsStaleCachedMessagesAndKeepsFetchedOnes() async throws {
        quotaMailbox(limited: GmailBox<Set<String>>([]))
        let account = MailAccount(host: GmailProducer.host, port: 443, username: "me@gmail.com", auth: .gmailAPI)
        let folder = home.appendingPathComponent("mail").appendingPathComponent(account.key)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let old = Data(("From: a@example.com\r\nSubject: Old\r\n\r\nold\r\n").utf8)
        try old.write(to: folder.appendingPathComponent("old1.eml"))
        try GmailClient.base64url(Self.eml("Subject m2"))!.write(to: folder.appendingPathComponent("m2.eml"))
        let producer = GmailProducer(account: account, client: client(), cacheRoot: home.appendingPathComponent("mail"))
        let seen = GmailBox<[String]>([])
        let out = try await producer.scan(state: [:], progress: { n, m in seen.update { $0.append("\(n)/\(m)") } })
        XCTAssertEqual(out.state, [GmailProducer.historyKey: "200"])
        XCTAssertEqual(seen.value, ["1/4", "2/4", "3/4", "4/4"], "progress counts the cached message as here")
        XCTAssertEqual(gets(), ["m1", "m3", "m4"], "m2 was already here")
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("old1.eml").path), "outside the window: removed")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted(), ["m1.eml", "m2.eml", "m3.eml", "m4.eml"])
    }

    // MARK: Never drop mail over the per-scan cap (2026-09-29: a phishing email was one of 64 dropped)

    /// A busy mailbox. `window`: the first pass's list, newest first (paged). `added[h]`: the ids added to the
    /// inbox after historyId h, oldest first, and the historyId after them; a start with no entry answers
    /// nothing new at that same historyId. Messages in `limited` answer 403 rateLimitExceeded.
    private func busyMailbox(window: [String] = [], added: GmailBox<[String: ([String], String)]>,
                             limited: GmailBox<Set<String>> = GmailBox([])) {
        FakeGoogle.handler = { req, _ in
            let url = req.url!
            let q = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
            switch url.path {
            case "/gmail/v1/users/me/profile":
                return (200, Self.json(["emailAddress": "me@gmail.com", "historyId": "200"]))
            case "/gmail/v1/users/me/messages":
                let start = Int(q["pageToken"] ?? "") ?? 0
                let size = Int(q["maxResults"] ?? "") ?? 100
                var o: [String: Any] = ["messages": window.dropFirst(start).prefix(size).map { ["id": $0, "threadId": "t"] }]
                if start + size < window.count { o["nextPageToken"] = String(start + size) }
                return (200, Self.json(o))
            case "/gmail/v1/users/me/history":
                let start = q["startHistoryId"] ?? ""
                let (ids, latest) = added.value[start] ?? ([], start)
                return (200, Self.json(["history": ids.map { ["id": "1", "messagesAdded": [["message": ["id": $0, "threadId": "t"]]]] },
                                        "historyId": latest]))
            default:
                let id = url.lastPathComponent
                if limited.value.contains(id) { return (403, Self.googleError(403, Self.quotaMessage, reason: "rateLimitExceeded")) }
                return Self.rawReply(id)
            }
        }
    }

    private func gmailAccount() -> MailAccount { MailAccount(host: GmailProducer.host, port: 443, username: "me@gmail.com", auth: .gmailAPI) }
    private func producer() -> GmailProducer { GmailProducer(account: gmailAccount(), client: client(), cacheRoot: home.appendingPathComponent("mail")) }
    private var mailFolder: URL { home.appendingPathComponent("mail").appendingPathComponent(gmailAccount().key) }
    private func cached() -> Set<String> {
        Set(((try? FileManager.default.contentsOfDirectory(atPath: mailFolder.path)) ?? []).map { ($0 as NSString).deletingPathExtension })
    }
    private func historyStarts() -> [String] {
        FakeGoogle.requests.filter { $0.0.url!.path == "/gmail/v1/users/me/history" }
            .compactMap { URLComponents(url: $0.0.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "startHistoryId" }?.value }
    }
    private static func ids(_ prefix: String, _ range: ClosedRange<Int>) -> [String] { range.map { String(format: "%@%03d", prefix, $0) } }
    private static func count(_ csv: String?) -> Int { csv?.split(separator: ",").count ?? 0 }

    func testHistoryOverTheCapQueuesTheRestAndTheNextScanFetchesThemBeforeAdvancing() async throws {
        let old = Self.ids("h", 1...264) // oldest first, as history lists them
        let added = GmailBox<[String: ([String], String)]>(["200": (old, "300")])
        busyMailbox(added: added)
        let first = try await producer().scan(state: [GmailProducer.historyKey: "200"])
        XCTAssertEqual(gets(), Array(old.suffix(200).reversed()), "the newest 200, newest first")
        XCTAssertEqual(first.state[GmailProducer.historyKey], "200", "not advanced while 64 are missing")
        XCTAssertEqual(first.state[GmailProducer.passHistoryKey], "300")
        XCTAssertEqual(first.state[GmailProducer.passPausedKey], "0")
        XCTAssertEqual(first.state[GmailProducer.passFreshKey], "0")
        XCTAssertEqual(first.state[GmailProducer.passIdsKey], Array(old.prefix(64).reversed()).joined(separator: ","), "the 64 older ones are queued")
        XCTAssertEqual(first.result.skipped.first { $0.path == "Mail" }?.reason,
                       "not fetched yet: 64 older messages — Loupe reads 200 at a time, newest first; the next scan fetches the rest")
        XCTAssertEqual(cached().count, 200)

        // The next scan: new mail since the pass's historyId first, then the queued 64; only then 300 → 400.
        added.update { $0["300"] = (["n1", "n2"], "400") }
        FakeGoogle.requests = []
        let second = try await producer().scan(state: first.state)
        XCTAssertEqual(historyStarts(), ["300"], "asks only for what is newer than the pass")
        XCTAssertEqual(gets(), ["n2", "n1"] + Array(old.prefix(64).reversed()))
        XCTAssertEqual(second.state, [GmailProducer.historyKey: "400"], "advanced once every id up to it is here; the pass is cleared")
        XCTAssertFalse(second.result.skipped.contains { $0.path == "Mail" })
        XCTAssertEqual(cached(), Set(old + ["n1", "n2"]), "nothing was dropped")
    }

    func testFirstPassOverTheCapQueuesTheWindowAndPrunesOnlyOnceItIsAllHere() async throws {
        let window = Self.ids("w", 1...520) // newest first, as messages.list gives them; two pages
        busyMailbox(window: window, added: GmailBox([:]))
        try FileManager.default.createDirectory(at: mailFolder, withIntermediateDirectories: true)
        try Data("From: a@example.com\r\nSubject: Old\r\n\r\nold\r\n".utf8).write(to: mailFolder.appendingPathComponent("old1.eml"))
        try GmailClient.base64url(Self.eml("Subject w450"))!.write(to: mailFolder.appendingPathComponent("w450.eml"))

        let seen = GmailBox<[String]>([])
        let first = try await producer().scan(state: [:], progress: { n, m in seen.update { $0.append("\(n)/\(m)") } })
        XCTAssertEqual(paths().filter { $0 == "/gmail/v1/users/me/messages" }.count, 2, "the whole window is listed")
        XCTAssertEqual(gets(), Array(window.prefix(200)))
        XCTAssertEqual(seen.value.first, "1/201")
        XCTAssertEqual(seen.value.last, "201/201", "this scan's share: the cached one plus 200")
        XCTAssertNil(first.state[GmailProducer.historyKey])
        XCTAssertEqual(first.state[GmailProducer.passFreshKey], "1")
        XCTAssertEqual(first.state[GmailProducer.passHistoryKey], "200")
        XCTAssertEqual(Self.count(first.state[GmailProducer.passIdsKey]), 520, "a first pass keeps its whole window")
        XCTAssertEqual(first.result.skipped.first { $0.path == "Mail" }?.reason,
                       "not fetched yet: 319 older messages — Loupe reads 200 at a time, newest first; the next scan fetches the rest")
        XCTAssertTrue(cached().contains("old1"), "no pruning before the window is all here")
        XCTAssertTrue(cached().contains("w450"), "a queued message's cached copy is kept")

        FakeGoogle.requests = []
        let second = try await producer().scan(state: first.state)
        XCTAssertEqual(historyStarts(), ["200"], "newer mail is asked for from the pass's historyId")
        XCTAssertFalse(paths().contains("/gmail/v1/users/me/messages"), "not listed again")
        XCTAssertEqual(gets(), Array(window[200..<400]))
        XCTAssertNil(second.state[GmailProducer.historyKey])
        XCTAssertTrue(cached().contains("old1"))
        XCTAssertTrue(cached().contains("w450"))

        FakeGoogle.requests = []
        let third = try await producer().scan(state: second.state)
        XCTAssertEqual(gets(), window[400...].filter { $0 != "w450" })
        XCTAssertEqual(third.state, [GmailProducer.historyKey: "200"])
        XCTAssertEqual(cached(), Set(window), "complete: outside the window pruned, the whole window kept")
    }

    func testRateLimitInTheQueuedRestPausesAndResumesWithoutLosingAnything() async throws {
        let old = Self.ids("h", 1...264)
        let added = GmailBox<[String: ([String], String)]>(["200": (old, "300"), "300": (["n1"], "400")])
        let limited = GmailBox<Set<String>>([])
        busyMailbox(added: added, limited: limited)
        let first = try await producer().scan(state: [GmailProducer.historyKey: "200"])
        XCTAssertEqual(Self.count(first.state[GmailProducer.passIdsKey]), 64)

        // Next scan: n1 first, then the queued rest from h064 down, until Gmail says slow down at h040.
        limited.value = ["h040"]
        FakeGoogle.requests = []
        var paused: GmailProducer.Interrupted?
        do { _ = try await producer().scan(state: first.state); XCTFail("expected the rate limit") } catch let p as GmailProducer.Interrupted { paused = p }
        let p = try XCTUnwrap(paused)
        XCTAssertEqual(p.fetched, 25, "n1 and h064…h041")
        XCTAssertEqual(p.total, 65)
        XCTAssertEqual(p.output.state[GmailProducer.historyKey], "200", "still not advanced")
        XCTAssertEqual(p.output.state[GmailProducer.passHistoryKey], "400")
        XCTAssertEqual(p.output.state[GmailProducer.passPausedKey], "1")
        XCTAssertEqual(p.output.state[GmailProducer.passIdsKey], Array(old.prefix(40).reversed()).joined(separator: ","))
        XCTAssertEqual(Set(gets()), Set(["n1"] + Self.ids("h", 40...64)))

        // Resume: a paused pass continues without asking Gmail for anything else first.
        limited.value = []
        FakeGoogle.requests = []
        let done = try await producer().scan(state: p.output.state)
        XCTAssertTrue(historyStarts().isEmpty)
        XCTAssertEqual(gets(), Array(old.prefix(40).reversed()))
        XCTAssertEqual(done.state, [GmailProducer.historyKey: "400"])
        XCTAssertEqual(cached(), Set(old + ["n1"]))
    }

    func testAPassSavedBeforeThePausedFlagResumesAsPaused() {
        let pass = GmailProducer.Pass(state: [GmailProducer.passIdsKey: "a,b", GmailProducer.passHistoryKey: "9",
                                              GmailProducer.passFreshKey: "0", "gmailPassMore": "0"])
        XCTAssertEqual(pass, GmailProducer.Pass(ids: ["a", "b"], historyId: "9", fresh: false, paused: true))
    }
}

/// A fake monotonic clock: sleeps are recorded and advance it at once. The automatic retry's wait (999 s) is a
/// real, short or long, cancellable sleep instead.
final class FakeGmailClock: @unchecked Sendable {
    private let lock = NSLock()
    private var t: TimeInterval = 1000
    private var _sleeps: [TimeInterval] = []
    var autoRetryNanos: UInt64 = 60_000_000_000
    var now: TimeInterval { lock.withLock { t } }
    var sleeps: [TimeInterval] { lock.withLock { _sleeps } }

    var timing: GmailTiming {
        var g = GmailTiming(now: { [self] in now }, sleep: { [self] s in
            if s >= 999 { try await Task.sleep(nanoseconds: autoRetryNanos); return }
            try Task.checkCancellation()
            lock.withLock { _sleeps.append(s); t += s }
        }, random: { 0 })
        g.attempts = 3
        g.autoRetryDelay = 999
        return g
    }
}

final class GmailCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var value: Int { lock.withLock { n } }
    /// Increments; returns the new count.
    func bump() -> Int { lock.withLock { n += 1; return n } }
}

final class GmailBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T {
        get { lock.withLock { v } }
        set { lock.withLock { v = newValue } }
    }
    func update(_ f: (inout T) -> Void) { lock.withLock { f(&v) } }
}
