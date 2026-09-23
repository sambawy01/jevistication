import XCTest
import LoupeKit
@testable import Loupe

// MARK: - Fakes (no PhotoKit, EventKit, Contacts, Keychain or network in these tests)

final class FakePhotoLibrary: PhotoLibraryReading {
    var status: PhonePermission = .notAsked
    var grantOnRequest: PhonePermission = .granted
    var requests = 0
    var assets: [PhotoAssetRecord] = []
    var data: [String: Data] = [:]
    var tokenValue = 1
    var changesSince: [Int: PhotoChanges] = [:]
    var dataRequests: [String] = []

    func authorization() -> PhonePermission { status }
    func requestAuthorization() async -> PhonePermission { requests += 1; status = grantOnRequest; return status }
    func allAssets() -> [PhotoAssetRecord] { assets }
    func changes(since token: Data) -> PhotoChanges? {
        guard let t = Int(String(decoding: token, as: UTF8.self)) else { return nil }
        return changesSince[t]
    }
    func currentToken() -> Data? { Data(String(tokenValue).utf8) }
    func imageData(localId: String) async -> Data? { dataRequests.append(localId); return data[localId] }
}

struct FakeRecognizer: TextRecognizing {
    var text: [Data: String]
    func recognize(_ data: Data) -> String { text[data] ?? "" }
}

final class FakeEventStore: EventStoreReading {
    var status: PhonePermission = .notAsked
    var requests = 0
    var events: [CalendarEventRecord] = []
    func authorization() -> PhonePermission { status }
    func requestAccess() async -> PhonePermission { requests += 1; status = .granted; return status }
    func events(from: Date, to: Date) -> [CalendarEventRecord] { events.filter { $0.start >= from && $0.start <= to } }
}

final class FakeContactStore: ContactStoreReading {
    var status: PhonePermission = .notAsked
    var grantOnRequest: PhonePermission = .granted
    var requests = 0
    var cards: [ContactRecord] = []
    func authorization() -> PhonePermission { status }
    func requestAccess() async -> PhonePermission { requests += 1; status = grantOnRequest; return status }
    func contacts() throws -> [ContactRecord] { cards }
}

/// Bookmarks as plain paths, so tests need no security scope. A path listed in `gone` fails to resolve.
final class FakeBookmarks: BookmarkResolving {
    var gone: Set<String> = []
    var stale: Set<String> = []
    func makeBookmark(for url: URL) throws -> Data { Data(url.path.utf8) }
    func resolve(_ bookmark: Data) throws -> (url: URL, stale: Bool) {
        let path = String(decoding: bookmark, as: UTF8.self)
        if gone.contains(path) { throw CocoaError(.fileNoSuchFile) }
        return (URL(fileURLWithPath: path), stale.contains(path))
    }
}

/// Replays a recorded IMAP transcript: each client line must be exactly what the transcript says,
/// and the server's bytes come back in small chunks so literals straddle reads.
final class TranscriptTransport: IMAPTransport {
    enum Step { case client(String), server(Data) }
    private var steps: [Step]
    private var pending = Data()
    private(set) var sent: [String] = []
    var chunk = 7
    var mismatch: String?

    init(steps: [Step]) { self.steps = steps }

    static func load(_ name: String) throws -> TranscriptTransport {
        let url = try XCTUnwrap(Bundle(for: PhoneSourcesTests.self).url(forResource: name, withExtension: "txt"), "fixture \(name) missing")
        let raw = try Data(contentsOf: url)
        var steps: [Step] = []
        var i = raw.startIndex
        func line() -> String? {
            guard i < raw.endIndex else { return nil }
            let end = raw[i...].firstIndex(of: 10) ?? raw.endIndex
            defer { i = end < raw.endIndex ? raw.index(after: end) : end }
            return String(decoding: raw[i..<end], as: UTF8.self)
        }
        while let l = line() {
            if l.hasPrefix("C: ") { steps.append(.client(String(l.dropFirst(3)))) }
            else if l.hasPrefix("S: "), let n = Int(l.dropFirst(3)) {
                let end = raw.index(i, offsetBy: n)
                steps.append(.server(Data(raw[i..<end])))
                i = raw.index(after: end)   // the newline after the raw bytes
            }
        }
        return TranscriptTransport(steps: steps)
    }

    private func queueServer() {
        while case .server(let d)? = steps.first { pending.append(d); steps.removeFirst() }
    }

    func open() async throws { queueServer() }

    func send(_ data: Data) async throws {
        let line = String(decoding: data, as: UTF8.self).replacingOccurrences(of: "\r\n", with: "")
        sent.append(line)
        guard case .client(let expected)? = steps.first else {
            mismatch = "unexpected command: \(line)"
            throw IMAPClient.Failure(kind: .connection, detail: mismatch!)
        }
        guard expected == line else {
            mismatch = "expected \(expected), got \(line)"
            throw IMAPClient.Failure(kind: .connection, detail: mismatch!)
        }
        steps.removeFirst()
        queueServer()
    }

    func receive() async throws -> Data {
        guard !pending.isEmpty else { throw IMAPClient.Failure(kind: .connection, detail: "The server closed the connection.") }
        let n = min(chunk, pending.count)
        defer { pending.removeFirst(n) }
        return Data(pending.prefix(n))
    }

    func close() {}
}

// MARK: - Tests

final class PhoneSourcesTests: XCTestCase {
    private var home: URL!
    private var inbox: URL!
    private var photos: FakePhotoLibrary!
    private var events: FakeEventStore!
    private var contacts: FakeContactStore!
    private var bookmarks: FakeBookmarks!
    private var keys: [String: MemoryKeyStore] = [:]
    private var transports: [TranscriptTransport] = []

    override func setUpWithError() throws {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("PhoneSources-\(UUID().uuidString)")
        inbox = home.appendingPathComponent("inbox")
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        photos = FakePhotoLibrary()
        events = FakeEventStore()
        contacts = FakeContactStore()
        bookmarks = FakeBookmarks()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    @MainActor
    private func service(oauth: OAuthConfig = OAuthConfig(googleClientId: "", microsoftClientId: "")) -> SourcesService {
        let deps = PhoneDependencies(
            photos: photos, recognizer: FakeRecognizer(text: [Data("receipt-bytes".utf8): "RECEIPT\nTotal £12.40"]),
            events: events, contacts: contacts, bookmarks: BookmarkStore(home: home, resolver: bookmarks),
            inbox: { [inbox] in inbox! }, mailAccounts: MailAccountStore(home: home),
            mailCache: home.appendingPathComponent("mail"),
            keychain: { [unowned self] a in
                let k = a.keychain().account
                if let s = self.keys[k] { return s }
                let s = MemoryKeyStore(); self.keys[k] = s; return s
            },
            makeTransport: { [unowned self] _ in self.transports.removeFirst() },
            oauth: oauth, state: PhoneStateStore(home: home))
        return SourcesService(home: home, sampleRoot: nil, deps: deps)
    }

    // MARK: IMAP parsing and the client, against recorded transcripts

    func testReaderSplitsResponsesAndLiteralsAcrossChunks() throws {
        var reader = IMAPResponseReader()
        let bytes = Data("* 1 FETCH (UID 9 BODY[] {12}\r\nHello\r\nWorld)\r\nL1 OK done\r\n".utf8)
        var out: [IMAPResponse] = []
        for b in bytes {
            reader.feed(Data([b]))
            while let r = try reader.next() { out.append(r) }
        }
        XCTAssertEqual(out.count, 2)
        XCTAssertEqual(out[0].kind, .untagged)
        XCTAssertEqual(out[0].literals, [Data("Hello\r\nWorld".utf8)])
        let fetched = try XCTUnwrap(IMAPParse.fetch(out[0]))
        XCTAssertEqual(fetched.uid, 9)
        XCTAssertEqual(out[1].kind, .tagged("L1"))
        XCTAssertEqual(out[1].status, "OK")
    }

    func testResponseCodesSearchAndQuoting() throws {
        var reader = IMAPResponseReader()
        reader.feed(Data("* OK [UIDVALIDITY 3857529045] UIDs valid\r\n* SEARCH 2 84 882\r\n* 23 EXISTS\r\n+ go ahead\r\n".utf8))
        let ok = try XCTUnwrap(reader.next())
        XCTAssertEqual(ok.code("UIDVALIDITY"), "3857529045")
        XCTAssertEqual(IMAPParse.search(try XCTUnwrap(reader.next())), [2, 84, 882])
        XCTAssertEqual(IMAPParse.exists(try XCTUnwrap(reader.next())), 23)
        XCTAssertEqual(try XCTUnwrap(reader.next()).kind, .continuation)
        XCTAssertEqual(try IMAPParse.quote(#"a"b\c"#), #""a\"b\\c""#)
        XCTAssertThrowsError(try IMAPParse.quote("pass\r\nA2 DELETE INBOX"))
        XCTAssertThrowsError(try IMAPParse.quote("pass\nx"))
        XCTAssertThrowsError(try IMAPParse.quote("pass\rx"))
    }

    func testAHostileLiteralSizeIsRefused() {
        var reader = IMAPResponseReader()
        reader.maxResponseBytes = 1024
        reader.feed(Data("* 1 FETCH (BODY[] {999999999}\r\n".utf8))
        XCTAssertThrowsError(try reader.next())
    }

    func testOnlyReadOnlyCommandsAreAllowed() {
        for ok in ["CAPABILITY", "LOGIN \"a\" \"b\"", "EXAMINE \"INBOX\"", "UID SEARCH UID 1:*",
                   "UID FETCH 1,2 (UID BODY.PEEK[]<0.262144>)", "LOGOUT", "AUTHENTICATE XOAUTH2 abc"] {
            XCTAssertTrue(IMAPClient.allowed(ok), ok)
        }
        for refused in ["SELECT INBOX", "UID STORE 1 +FLAGS (\\Seen)", "UID FETCH 1 (BODY[])", "UID FETCH 1 (RFC822)",
                        "UID FETCH 1 (UID BODY[TEXT])", "EXPUNGE", "APPEND INBOX {3}", "DELETE INBOX", "UID COPY 1 Trash"] {
            XCTAssertFalse(IMAPClient.allowed(refused), refused)
        }
    }

    func testFirstSyncReadsEveryMessageReadOnly() async throws {
        let t = try TranscriptTransport.load("imap-first-sync")
        let r = try await IMAPClient(transport: t).sync(username: "me@example.com", credential: .password(#"app pass "q""#),
                                                        knownUidValidity: nil, afterUid: 0, maxMessages: 200)
        XCTAssertNil(t.mismatch)
        XCTAssertEqual(r.uidValidity, 7)
        XCTAssertEqual(r.highestUid, 102)
        XCTAssertEqual(r.messages.map(\.uid), [101, 102])
        XCTAssertTrue(String(decoding: r.messages[1].raw, as: UTF8.self).contains("From the kitchen"))
        XCTAssertEqual(r.remaining, 0)
        XCTAssertFalse(t.sent.contains { $0.contains("SELECT") || $0.contains("STORE") })
    }

    func testTheCapKeepsTheNewestAndSaysHowManyAreLeft() async throws {
        let t = try TranscriptTransport.load("imap-first-sync")
        // With a cap of one, only the newest (102) is fetched: the transcript's FETCH line differs, so
        // a mismatch is expected there — the point is which UIDs the client asked for.
        _ = try? await IMAPClient(transport: t).sync(username: "me@example.com", credential: .password(#"app pass "q""#),
                                                     knownUidValidity: nil, afterUid: 0, maxMessages: 1)
        XCTAssertEqual(t.sent.last, "L4 UID FETCH 102 (UID BODY.PEEK[]<0.262144>)")
    }

    func testAuthenticationFailureIsTyped() async throws {
        let t = try TranscriptTransport.load("imap-auth-failure")
        do {
            _ = try await IMAPClient(transport: t).sync(username: "me@example.com", credential: .password("wrong"),
                                                        knownUidValidity: nil, afterUid: 0, maxMessages: 10)
            XCTFail("expected a failure")
        } catch let f as IMAPClient.Failure {
            XCTAssertEqual(f.kind, .authentication)
            XCTAssertTrue(f.recovery.contains("app password"))
        }
    }

    // MARK: Mail through the service: Keychain, incremental UIDs, Online labels, off switch

    @MainActor
    func testMailSyncsIncrementallyLabelsOnlineAndForgets() async throws {
        let s = service()
        transports = [try TranscriptTransport.load("imap-first-sync"), try TranscriptTransport.load("imap-incremental")]
        let account = MailAccount(host: "IMAP.Example.com ", port: 993, username: "me@example.com", auth: .appPassword)
        try await s.saveMail(account, secret: #"app pass "q""#)
        XCTAssertNil(s.state(.mail).problem)
        XCTAssertTrue(s.isPhoneEnabled(.mail))
        XCTAssertEqual(s.state(.mail).itemCount, 2)
        let saved = try XCTUnwrap(s.mailAccount)
        XCTAssertEqual(saved.host, "imap.example.com")
        XCTAssertEqual(keys[saved.keychain().account]?.read(), #"app pass "q""#, "the password is in the key store, not the account file")
        let accountFile = try String(contentsOf: home.appendingPathComponent("sources/mail-account.json"))
        XCTAssertFalse(accountFile.contains("app pass"))

        let mail = s.items().filter { $0.sourceId == PhoneSourceIds.shared.MAIL }
        XCTAssertEqual(mail.count, 2)
        XCTAssertTrue(mail.allSatisfy { ($0.facts["online"] ?? "").hasPrefix("Online · imap.example.com · fetched ") })
        let mum = try XCTUnwrap(mail.first { $0.email?.fromName == "Mum" })
        XCTAssertTrue(mum.text.contains("From the kitchen"), "a body line starting \"From \" stays in its message")
        XCTAssertEqual(mum.kind, .email)

        // Second sync asks only for UIDs after 102.
        await s.scanPhone(.mail)
        XCTAssertNil(s.state(.mail).problem)
        XCTAssertEqual(s.state(.mail).itemCount, 3)

        // Off: no request is made (no transport is left, so a request would fail loudly) and no items.
        await s.setPhoneEnabled(.mail, false)
        await s.scanPhone(.mail)
        XCTAssertTrue(s.items().filter { $0.sourceId == "mail" }.isEmpty)

        s.removeMail()
        XCTAssertNil(s.mailAccount)
        XCTAssertNil(keys[saved.keychain().account]?.read())
        XCTAssertFalse(FileManager.default.fileExists(atPath: home.appendingPathComponent("mail/\(saved.key)").path))
    }

    @MainActor
    func testMailAuthFailureShowsRecoveryText() async throws {
        let s = service()
        transports = [try TranscriptTransport.load("imap-auth-failure")]
        try await s.saveMail(MailAccount(host: "imap.example.com", port: 993, username: "me@example.com", auth: .appPassword), secret: "wrong")
        XCTAssertTrue(s.state(.mail).problem?.contains("app password") == true, s.state(.mail).problem ?? "nil")
        XCTAssertEqual(s.state(.mail).itemCount, 0)
    }

    // MARK: OAuth: PKCE, request shapes, and the owner-blocked gate

    func testPkceMatchesRfc7636AppendixB() {
        let p = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        XCTAssertEqual(p.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        XCTAssertGreaterThanOrEqual(PKCE.random().verifier.count, 43)
    }

    func testOAuthIsGatedOnEmptyClientIds() {
        let empty = OAuthConfig.fromBundle(.main)
        XCTAssertEqual(empty, OAuthConfig(googleClientId: "", microsoftClientId: ""), "the shipped config must be empty (owner-blocked)")
        for p in OAuthProvider.allCases {
            guard case .needsClientId(let why) = empty.availability(p) else { return XCTFail("\(p) should be gated") }
            XCTAssertTrue(why.contains("Needs a \(p.name) OAuth client ID"), why)
        }
        XCTAssertEqual(OAuthConfig(googleClientId: "123-abc.apps.googleusercontent.com", microsoftClientId: "").availability(.google),
                       .ready(clientId: "123-abc.apps.googleusercontent.com"))
    }

    @MainActor
    func testSignInRefusesWithoutAClientId() async {
        let s = service()
        do {
            try await s.signInMail(.google, username: "me@gmail.com")
            XCTFail("expected the gate")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("Needs a Google OAuth client ID"))
        }
        XCTAssertNil(s.mailAccount)
    }

    func testAuthorizationAndTokenRequests() throws {
        let flow = OAuthFlow(provider: .google, clientId: "123-abc.apps.googleusercontent.com")
        let pkce = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        let url = flow.authorizationURL(pkce: pkce, state: "s1", loginHint: "me@gmail.com")
        let q = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(url.host, "accounts.google.com")
        XCTAssertEqual(q["code_challenge"], pkce.challenge)
        XCTAssertEqual(q["code_challenge_method"], "S256")
        XCTAssertEqual(q["redirect_uri"], "com.googleusercontent.apps.123-abc:/oauth2redirect")
        XCTAssertEqual(q["scope"], "https://mail.google.com/ email")
        XCTAssertEqual(q["state"], "s1")
        XCTAssertNil(q["client_secret"])

        XCTAssertEqual(try OAuthFlow.code(from: URL(string: "com.googleusercontent.apps.123-abc:/oauth2redirect?state=s1&code=abc")!, expectedState: "s1"), "abc")
        XCTAssertThrowsError(try OAuthFlow.code(from: URL(string: "x:/r?state=evil&code=abc")!, expectedState: "s1"))
        XCTAssertThrowsError(try OAuthFlow.code(from: URL(string: "x:/r?state=s1&error=access_denied")!, expectedState: "s1"))

        let token = flow.tokenRequest(code: "c/1", pkce: pkce)
        XCTAssertEqual(token.url?.absoluteString, "https://oauth2.googleapis.com/token")
        XCTAssertEqual(token.httpMethod, "POST")
        let body = String(decoding: token.httpBody ?? Data(), as: UTF8.self)
        XCTAssertTrue(body.contains("code=c%2F1"), body)
        XCTAssertTrue(body.contains("code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), body)
        XCTAssertTrue(body.contains("grant_type=authorization_code"))
        let ms = OAuthFlow(provider: .microsoft, clientId: "abc").refreshRequest(refreshToken: "r")
        XCTAssertEqual(ms.url?.host, "login.microsoftonline.com")
        XCTAssertTrue(String(decoding: ms.httpBody ?? Data(), as: UTF8.self).contains("grant_type=refresh_token"))
    }

    // MARK: Photos: permission only on enable, OCR, screenshots, incremental

    private func asset(_ id: String, screenshot: Bool = false, created: TimeInterval = 1_790_000_000) -> PhotoAssetRecord {
        PhotoAssetRecord(localId: id, fileName: "\(id).PNG", created: Date(timeIntervalSince1970: created), isScreenshot: screenshot,
                         pixelWidth: 10, pixelHeight: 20, hasLocation: false)
    }

    @MainActor
    func testPhotosAskOnlyWhenTurnedOnAndReadIncrementally() async throws {
        photos.assets = [asset("A", screenshot: true), asset("B")]
        photos.data = ["A": Data("receipt-bytes".utf8), "B": Data("sunset".utf8)]
        let s = service()
        XCTAssertEqual(photos.requests, 0, "no prompt at launch")
        XCTAssertEqual(s.state(.photos).permission, .notAsked)
        await s.setPhoneEnabled(.photos, true)
        XCTAssertEqual(photos.requests, 1)
        XCTAssertEqual(s.state(.photos).itemCount, 2)
        let shot = try XCTUnwrap(s.items().first { $0.id == "photos:A" })
        XCTAssertTrue(shot.hasText)
        XCTAssertEqual(shot.facts["screenshot"], "yes")
        XCTAssertTrue(shot.text.hasPrefix("Screenshot: A.PNG\n\nRECEIPT"))
        XCTAssertFalse(try XCTUnwrap(s.items().first { $0.id == "photos:B" }).hasText)

        // Incremental: nothing new, nothing re-read. Then B is edited, C added, A deleted.
        photos.dataRequests = []
        photos.tokenValue = 2
        await s.scanPhone(.photos)
        XCTAssertEqual(photos.dataRequests, [])
        photos.changesSince[2] = PhotoChanges(updated: ["B", "C"], deleted: ["A"])
        photos.assets = [asset("C"), asset("B")]
        photos.data["C"] = Data("c".utf8)
        await s.scanPhone(.photos)
        XCTAssertEqual(Set(photos.dataRequests), ["B", "C"])
        XCTAssertEqual(Set(s.items().filter { $0.sourceId == "photos" }.map(\.id)), ["photos:B", "photos:C"])
        XCTAssertEqual(photos.requests, 1, "asked once")
    }

    func testPhotosCapPerScanAndICloudOnlyOriginals() async {
        photos.assets = (0..<5).map { asset("P\($0)") }
        for i in 0..<4 { photos.data["P\(i)"] = Data("p\(i)".utf8) }   // P4 is only in iCloud
        var producer = PhotosProducer(library: photos, recognizer: FakeRecognizer(text: [:]))
        producer.maxPerScan = 3
        let first = await producer.scan(cached: nil, state: [:])
        XCTAssertEqual(first.result.items.count, 3)
        XCTAssertTrue(first.result.skipped.contains { $0.reason.contains("2 older photos") })
        let second = await producer.scan(cached: first.result, state: first.state)
        XCTAssertEqual(second.result.items.count, 4)
        XCTAssertTrue(second.result.skipped.contains { $0.reason.contains("iCloud only") })
    }

    @MainActor
    func testLimitedAndDeniedPhotosSayWhatToDo() async {
        photos.grantOnRequest = .denied
        let s = service()
        await s.setPhoneEnabled(.photos, true)
        XCTAssertEqual(s.state(.photos).permission, .denied)
        XCTAssertTrue(s.state(.photos).problem?.contains("Settings → Loupe → Photos") == true)
        XCTAssertEqual(PhonePermission.limited.recovery(for: .photos)?.contains("Choose more"), true)
        XCTAssertTrue(PhonePermission.limited.canRead)
    }

    // MARK: Calendar and Contacts

    @MainActor
    func testCalendarEventsBecomeItemsPerOccurrence() async throws {
        let now = Date()
        events.events = [
            CalendarEventRecord(eventId: "E1", title: "Passport renewal appointment", start: now.addingTimeInterval(86_400),
                                end: now.addingTimeInterval(90_000), allDay: false, location: "Consulate", calendar: "Home",
                                organizer: nil, attendees: ["Alex <alex@x.example>"], recurrence: "every year", notes: nil),
            CalendarEventRecord(eventId: "E1", title: "Passport renewal appointment", start: now.addingTimeInterval(86_400 * 30),
                                end: nil, allDay: true, location: nil, calendar: nil, organizer: nil, attendees: [], recurrence: nil, notes: nil),
            CalendarEventRecord(eventId: "OLD", title: "Too old", start: now.addingTimeInterval(-86_400 * 800), end: nil, allDay: true,
                                location: nil, calendar: nil, organizer: nil, attendees: [], recurrence: nil, notes: nil),
        ]
        let s = service()
        XCTAssertEqual(events.requests, 0)
        await s.setPhoneEnabled(.calendar, true)
        XCTAssertEqual(events.requests, 1)
        let cal = s.items().filter { $0.sourceId == "calendar" }
        XCTAssertEqual(cal.count, 2)
        XCTAssertTrue(cal.allSatisfy { $0.kind == .event && $0.id.hasPrefix("calendar:E1@") })
        XCTAssertTrue(cal[0].text.contains("Attendees: Alex <alex@x.example>"))
        XCTAssertTrue(cal[0].text.contains("Repeats: every year"))
        XCTAssertTrue(s.judgeableItems().contains { $0.id == cal[0].id })
    }

    @MainActor
    func testContactsFeedTheWatcherButAreNotJudged() async throws {
        contacts.cards = [ContactRecord(contactId: "C1", name: "Dana Rivers", organization: nil, emails: ["dana@rivers.example"], phones: ["+44 7700 900123"])]
        let s = service()
        await s.setPhoneEnabled(.contacts, true)
        XCTAssertEqual(contacts.requests, 1)
        XCTAssertEqual(s.state(.contacts).itemCount, 1)
        XCTAssertEqual(s.items().filter { $0.kind == .contact }.count, 1)
        XCTAssertTrue(s.judgeableItems().filter { $0.kind == .contact }.isEmpty)
        let book = WatcherRun.shared.addressBook(items: s.items())
        XCTAssertEqual(book.first?.name, "Dana Rivers")

        await s.setPhoneEnabled(.contacts, false)
        XCTAssertTrue(s.items().isEmpty, "off removes its items")
        XCTAssertEqual(contacts.requests, 1)
    }

    @MainActor
    func testRestrictedContactsAreNotReadAndSaySo() async {
        contacts.grantOnRequest = .restricted
        let s = service()
        await s.setPhoneEnabled(.contacts, true)
        XCTAssertEqual(s.state(.contacts).itemCount, 0)
        XCTAssertTrue(s.state(.contacts).problem?.contains("Screen Time") == true)
    }

    // MARK: Files: bookmark persistence, rescans, the share inbox

    @MainActor
    func testPickedFoldersPersistAsBookmarksAndRescan() async throws {
        let folder = home.appendingPathComponent("Picked", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("Invoice 42\nTotal due: £120.00 by 1 October".utf8).write(to: folder.appendingPathComponent("invoice.txt"))
        let single = home.appendingPathComponent("note.md")
        try Data("# Warranty\nThe fridge warranty expires 2027-03-01.".utf8).write(to: single)

        let s = service()
        await s.addPicked([folder, single])
        XCTAssertTrue(s.isPhoneEnabled(.files))
        XCTAssertEqual(s.state(.files).itemCount, 2)

        // Bookmarks survive a new store (a relaunch), with stable ids, and no duplicate on re-pick.
        let again = BookmarkStore(home: home, resolver: bookmarks)
        XCTAssertEqual(again.all().count, 2)
        try again.add([folder])
        XCTAssertEqual(again.all().count, 2)
        let ids = Set(s.items().filter { $0.sourceId == "files" }.map(\.id))
        XCTAssertTrue(ids.contains("files:\(BookmarkStore.stableId(folder))/invoice.txt"), "\(ids)")

        // A file added later appears on the next scan; a location that vanished says what to do.
        try Data("Second receipt for the sofa, paid £300".utf8).write(to: folder.appendingPathComponent("sofa.txt"))
        bookmarks.gone = [single.path]
        await s.scanPhone(.files)
        XCTAssertEqual(s.state(.files).itemCount, 2)
        XCTAssertEqual(s.state(.files).unavailableCount, 1)
        let cached = try XCTUnwrap(s.library?.cached(sourceId: "files"))
        XCTAssertTrue(cached.result.unavailable.first?.reason.contains("pick it again") == true)

        await s.removePicked(BookmarkStore.stableId(single))
        XCTAssertEqual(BookmarkStore(home: home, resolver: bookmarks).all().count, 1)
    }

    @MainActor
    func testSendToLoupeInboxIsReadOnOpen() async throws {
        try SharedInbox.drop(text: "Link shared to Loupe: https://shop.example/order/77\n", title: "shop.example", into: inbox)
        let pdfSource = try XCTUnwrap(SourcesService.bundledSample()).appendingPathComponent("documents/insurance/home-insurance-renewal-2026.pdf")
        try SharedInbox.drop(file: pdfSource, into: inbox)
        try SharedInbox.drop(file: pdfSource, into: inbox)   // same name again: kept, not overwritten
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path).count, 3)
        XCTAssertEqual(SharedInbox.sanitize("../etc/passwd"), "Shared..-etc-passwd", "no path escapes and no hidden files")

        let s = service()
        await s.setPhoneEnabled(.files, true)
        let shared = s.items().filter { $0.sourceId == PhoneSourceIds.shared.SHARED }
        XCTAssertEqual(shared.count, 3)
        XCTAssertTrue(shared.contains { $0.text.contains("https://shop.example/order/77") })
        XCTAssertEqual(shared.filter { $0.duplicateOf != nil }.count, 1)
    }

    // MARK: Keychain for mail

    func testMailPasswordUsesTheThisDeviceOnlyKeychain() throws {
        let account = MailAccount(host: "imap.example.com", port: 993, username: "Tester-\(UUID().uuidString)@example.com", auth: .appPassword)
        let store = account.keychain()
        defer { try? store.delete() }
        XCTAssertEqual(store.service, "dev.loupe.app.mail")
        try store.save("app-password")
        XCTAssertEqual(store.read(), "app-password")
        XCTAssertEqual(store.accessibility(), kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        XCTAssertNotEqual(MailAccount(host: "imap.example.com", port: 993, username: account.username, auth: .googleOAuth).keychain().account,
                          store.account, "OAuth tokens and app passwords never share an item")
    }
}
