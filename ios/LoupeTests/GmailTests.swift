import XCTest
import LoupeKit
@testable import Loupe

/// Fake Google endpoints: responses in the documented Gmail API / OAuth token shapes. No network.
final class FakeGoogle: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _requests: [(URLRequest, Data)] = []
    nonisolated(unsafe) private static var _handler: (URLRequest, Data) -> (Int, Data) = { _, _ in (500, Data()) }
    static var requests: [(URLRequest, Data)] {
        get { lock.withLock { _requests } }
        set { lock.withLock { _requests = newValue } }
    }
    static var handler: (URLRequest, Data) -> (Int, Data) {
        get { lock.withLock { _handler } }
        set { lock.withLock { _handler = newValue } }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let body = request.httpBody ?? request.httpBodyStream.map(FakeOnline.read) ?? Data()
        Self.lock.withLock { Self._requests.append((request, body)) }
        let (status, data) = Self.handler(request, body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
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
        keys = [:]
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

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
            state: PhoneStateStore(home: home), http: FakeGoogle.session)
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
}
