import Foundation
import LoupeKit

/// One phone source's row in Sources.
struct PhoneSourceState: Equatable {
    var enabled = false
    var permission: PhonePermission = .notAsked
    var itemCount = 0
    var skippedCount = 0
    var unavailableCount = 0
    var lastScan: Date?
    var scanning = false
    /// The last failure, in words, with what to do about it.
    var problem: String?
    /// A line of detail: "12 screenshots", "3 locations", "Online · imap.mail.me.com".
    var detail: String?
    /// Photos not read yet (newest first, 300 a scan), for the card's coverage ring. nil: nothing pending known.
    var pending: Int?
    /// Items with text (photos: text found by OCR).
    var withText = 0
}

/// Everything the phone sources touch, injectable so tests use fakes (no PhotoKit, EventKit,
/// Contacts, Keychain or network in unit tests).
struct PhoneDependencies {
    var photos: PhotoLibraryReading
    var recognizer: TextRecognizing
    var events: EventStoreReading
    var contacts: ContactStoreReading
    var bookmarks: BookmarkStore
    var inbox: () -> URL
    var mailAccounts: MailAccountStore
    var mailCache: URL
    var keychain: (MailAccount) -> KeyStore
    var makeTransport: (MailAccount) -> IMAPTransport
    var oauth: OAuthConfig
    var state: PhoneStateStore
    /// HTTPS for OAuth and the Gmail API (tests pass a session over a fake URLProtocol).
    var http: URLSession = .shared

    static func live(home: URL) -> PhoneDependencies {
        PhoneDependencies(
            photos: livePhotos(), recognizer: VisionTextRecognizer(), events: EventKitReader(),
            contacts: ContactsReader(), bookmarks: BookmarkStore(home: home),
            // Under -LoupeFixtures the inbox is a throwaway folder beside the throwaway ledger.
            inbox: { LaunchOptions.current.fixtureMode ? fixtureInbox(home) : SharedInbox.folder() },
            mailAccounts: MailAccountStore(home: home), mailCache: home.appendingPathComponent("mail", isDirectory: true),
            keychain: { account in
                #if DEBUG
                if LaunchOptions.current.ephemeralKey { return MemoryKeyStore() }
                #endif
                return account.keychain()
            },
            makeTransport: { NWIMAPTransport(host: $0.host, port: $0.port) },
            oauth: .fromBundle(), state: PhoneStateStore(home: home))
    }
}

/// PhotoKit; in DEBUG with `-LoupeFixtures -LoupePhotosDemo`, a library of rendered fixture pictures instead
/// (Vision still reads them for real), so UI tests and screenshots need no real photos.
private func livePhotos() -> PhotoLibraryReading {
    #if DEBUG
    if let demo = SourcesDemo.photoLibrary() { return demo }
    #endif
    return PhotoKitLibrary()
}

/// Forwards the common scanner's progress to the live run and the live display. For Mail the display shows each
/// message's subject (read from the fetched `.eml` header on this iPhone) rather than its file name.
final class FeedObserver: NSObject, ScanObserver {
    let run: ScanObserver
    let feed: ScanFeed
    let mailSubjects: Bool
    init(run: ScanObserver, feed: ScanFeed, mailSubjects: Bool) {
        self.run = run; self.feed = feed; self.mailSubjects = mailSubjects
    }
    func onProgress(progress: ScanProgress) {
        run.onProgress(progress: progress)
        feed.scanned(progress, label: mailSubjects ? MailSubject.read(progress.current) : nil)
    }
    func isCancelled() -> Bool { false }
}

/// The Subject header of a fetched message: the first 16 KB of the file, unfolded, RFC 2047 decoded.
enum MailSubject {
    static func read(_ path: String) -> String? {
        guard path.hasSuffix(".eml"), let h = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? h.close() }
        guard let data = try? h.read(upToCount: 16_384) else { return nil }
        return subject(in: String(decoding: data, as: UTF8.self))
    }

    static func subject(in raw: String) -> String? {
        let head = raw.components(separatedBy: "\r\n\r\n").first.map { $0.components(separatedBy: "\n\n").first ?? $0 } ?? raw
        let unfolded = head.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\n[ \t]+", with: " ", options: .regularExpression)
        for line in unfolded.split(separator: "\n") where line.lowercased().hasPrefix("subject:") {
            let v = line.dropFirst("subject:".count).trimmingCharacters(in: .whitespaces)
            let decoded = MimeCodecs.shared.decodeHeader(value: v)
            return decoded.isEmpty ? "(no subject)" : decoded
        }
        return "(no subject)"
    }
}

private func fixtureInbox(_ home: URL) -> URL {
    let dir = home.appendingPathComponent("SharedInbox", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
}

extension SourcesService {
    #if DEBUG
    /// `-LoupeReviewDemo` (with -LoupeFixtures): two identical files in the throwaway inbox and Files
    /// on, so the privacy check finds a duplicate the app can really remove (and put back).
    func seedReviewDemo() async {
        let inbox = deps.inbox()
        let text = "Fresh Basket receipt\nTotal 12.40\nThank you for shopping with us.\n"
        for name in ["review-demo-receipt.txt", "review-demo-receipt copy.txt"] {
            try? Data(text.utf8).write(to: inbox.appendingPathComponent(name), options: .atomic)
        }
        await setPhoneEnabled(.files, true)
    }
    #endif

    func isPhoneEnabled(_ s: PhoneSource) -> Bool {
        library?.isEnabled(sourceId: s.rawValue, default: false) ?? false
    }

    func state(_ s: PhoneSource) -> PhoneSourceState { phone[s] ?? PhoneSourceState() }

    func permission(_ s: PhoneSource) -> PhonePermission {
        switch s {
        case .photos: return deps.photos.authorization()
        case .calendar: return deps.events.authorization()
        case .contacts: return deps.contacts.authorization()
        case .files, .mail: return .notNeeded
        }
    }

    /// Reads each source's switch, permission (without asking) and cached counts.
    func loadPhoneStates() {
        for s in PhoneSource.allCases {
            var st = phone[s] ?? PhoneSourceState()
            st.enabled = isPhoneEnabled(s)
            st.permission = permission(s)
            apply(cached: s, to: &st)
            phone[s] = st
        }
    }

    private func apply(cached s: PhoneSource, to st: inout PhoneSourceState) {
        let scans = s.cacheIds.compactMap { library?.cached(sourceId: $0) }
        st.itemCount = scans.reduce(0) { $0 + Int($1.itemCount) }
        st.skippedCount = scans.reduce(0) { $0 + $1.result.skipped.count }
        st.unavailableCount = scans.reduce(0) { $0 + $1.result.unavailable.count }
        st.lastScan = scans.map { Date(timeIntervalSince1970: Double($0.scannedAtEpochMillis) / 1000) }.max()
        st.detail = detail(s, scans: scans)
        st.withText = scans.reduce(0) { $0 + $1.result.items.filter(\.hasText).count }
        st.pending = s == .photos ? deps.state.state(s.rawValue)[PhotosProducer.remainingKey].flatMap(Int.init) : nil
    }

    private func detail(_ s: PhoneSource, scans: [CachedScan]) -> String? {
        let items = scans.flatMap(\.result.items)
        switch s {
        case .photos:
            let shots = items.filter { $0.facts["screenshot"] != nil }.count
            let text = items.filter(\.hasText).count
            return items.isEmpty ? nil : "\(text) with text · \(shots) screenshot\(shots == 1 ? "" : "s")"
        case .files:
            let picked = deps.bookmarks.all().count
            let shared = scans.first { $0.sourceId == PhoneSourceIds.shared.SHARED }?.itemCount ?? 0
            return "\(picked) picked location\(picked == 1 ? "" : "s") · \(shared) sent to Loupe"
        case .contacts:
            let withEmail = items.filter { $0.facts["emails"] != nil }.count
            return items.isEmpty ? nil : "\(withEmail) with an email address (known to the impersonation watcher)"
        case .mail:
            guard let a = deps.mailAccounts.load() else { return "No mailbox added" }
            return "Online · \(a.username) on \(a.host)"
        case .calendar:
            return items.isEmpty ? nil : "A year back to a year ahead"
        }
    }

    /// The switch. Turning a source on is the only place its iOS permission is asked for; off
    /// removes its items from every judgment and watcher, and (for Mail) stops all requests.
    func setPhoneEnabled(_ s: PhoneSource, _ on: Bool) async {
        guard let library else { return }
        do { try library.setEnabled(sourceId: s.rawValue, enabled: on) } catch {
            phone[s, default: .init()].problem = "Could not save the setting: \(error.localizedDescription)"
            return
        }
        phone[s, default: .init()].enabled = on
        revision += 1
        guard on else { return }
        if permission(s) == .notAsked {
            switch s {
            case .photos: _ = await deps.photos.requestAuthorization()
            case .calendar: _ = await deps.events.requestAccess()
            case .contacts: _ = await deps.contacts.requestAccess()
            case .files, .mail: break
            }
        }
        phone[s, default: .init()].permission = permission(s)
        await scanPhone(s)
    }

    /// Re-scans the sources that are cheap to re-read whenever the app opens: picked files (they
    /// may have changed) and the "Send to Loupe" inbox.
    func refreshOnOpen() {
        guard isPhoneEnabled(.files) else { return }
        Task { await scanPhone(.files) }
    }

    /// Scans one phone source now. Refuses when it is off (Mail: no request is made).
    func scanPhone(_ s: PhoneSource) async {
        guard isPhoneEnabled(s), !state(s).scanning, let library else { return }
        let perm = permission(s)
        phone[s, default: .init()].permission = perm
        guard perm.canRead else {
            phone[s, default: .init()].problem = perm.recovery(for: s) ?? "Loupe cannot read \(s.title.lowercased()) yet."
            return
        }
        phone[s, default: .init()].scanning = true
        phone[s, default: .init()].problem = nil
        defer { phone[s, default: .init()].scanning = false }
        // The live run (the Activity dock, docs/LIVE-RUN-VIEW.md) and the live display in the source's card.
        let run = SourceScanRun(source: s.rawValue, stage: s == .mail ? "act.stage.connecting" : "act.stage.walking")
        let account = s == .mail ? deps.mailAccounts.load() : nil
        let live = beginLive(s.rawValue, ScanPipeline.of(s.rawValue, ocr: s == .photos ? OcrPolicy.current().enabled : true,
                                                           mailHost: account?.host, gmail: account?.auth == .gmailAPI))
        let feed = live.feed
        let obs = run.observer
        let both = FeedObserver(run: obs, feed: feed, mailSubjects: s == .mail)
        defer { settle(live) }
        do {
            switch s {
            case .photos:
                feed.status("Listing your photos")
                let cacheId = PhoneSourceIds.shared.PHOTOS
                var producer = PhotosProducer(library: deps.photos, recognizer: deps.recognizer)
                producer.ocr = { OcrPolicy.current() }
                producer.onItem = { done, total, read, ocr in run.item(done: done, of: total, read: read, ocr: ocr) }
                producer.onRead = { feed.item($0) }
                producer.onListed = { feed.listed($0) }
                #if DEBUG
                producer.pace = SourcesDemo.photoPace
                #endif
                let cached = library.cached(sourceId: cacheId)?.result
                let prior = deps.state.state(s.rawValue)
                let out = await Task.detached(priority: .utility) { await producer.scan(cached: cached, state: prior) }.value
                try store(out, as: cacheId, for: s)
            case .files:
                feed.status("Listing your files")
                let deps = self.deps
                let (files, shared) = try await runOffMain {
                    (try FilesProducer(store: deps.bookmarks).scan(observer: both), try SharedInbox.scan(folder: deps.inbox()))
                }
                run.ocrCount(Self.ocrItems(files.result))
                try store(files, as: PhoneSourceIds.shared.FILES, for: s)
                try store(shared, as: PhoneSourceIds.shared.SHARED, for: s)
            case .calendar:
                feed.status("Reading your calendar")
                let deps = self.deps
                let out = try await runOffMain { () -> PhoneScanOutput in
                    let out = CalendarProducer(store: deps.events).scan()
                    Self.feedItems(out.result.items, to: feed)
                    return out
                }
                try store(out, as: PhoneSourceIds.shared.CALENDAR, for: s)
            case .contacts:
                feed.status("Reading your contacts")
                let deps = self.deps
                let out = try await runOffMain { () -> PhoneScanOutput in
                    let out = try ContactsProducer(store: deps.contacts).scan()
                    Self.feedItems(out.result.items, to: feed)
                    return out
                }
                try store(out, as: PhoneSourceIds.shared.CONTACTS, for: s)
            case .mail:
                guard let account else {
                    phone[s, default: .init()].problem = "Add a mailbox first: tap Mail, then enter your server and an app password."
                    run.fail()
                    feed.fail()
                    return
                }
                feed.status("Connecting to \(account.host)")
                let fetched = {
                    run.job.stage("act.stage.walking")
                    feed.fetched()
                    feed.status("Reading the fetched mail")
                }
                if account.auth == .gmailAPI {
                    let token = try await oauthAccessToken(account)
                    let producer = GmailProducer(account: account, client: GmailClient(accessToken: token, session: deps.http),
                                                 cacheRoot: deps.mailCache)
                    let out = try await producer.scan(state: deps.state.state(s.rawValue), observer: both, fetched: fetched)
                    try store(out, as: PhoneSourceIds.shared.MAIL, for: s)
                    break
                }
                let credential = try await mailCredential(account)
                let producer = MailProducer(account: account, credential: credential, cacheRoot: deps.mailCache,
                                            makeTransport: deps.makeTransport)
                let out = try await producer.scan(state: deps.state.state(s.rawValue), observer: both, fetched: fetched)
                try store(out, as: PhoneSourceIds.shared.MAIL, for: s)
            }
            let st = state(s)
            run.finish(items: st.itemCount, skipped: st.skippedCount)
            feed.finish(saved: st.itemCount)
        } catch let failure as IMAPClient.Failure {
            let host = deps.mailAccounts.load()?.host ?? ""
            phone[s, default: .init()].problem = failure.recovery(host: host)
            Log.mail.error("mail scan failed: kind=\(String(describing: failure.kind), privacy: .public) server=\(IMAPClient.Failure.brief(failure.detail), privacy: .public)")
            run.fail()
            feed.fail()
        } catch let failure as GmailClient.Failure {
            phone[s, default: .init()].problem = failure.recovery
            Log.mail.error("gmail scan failed: status=\(failure.status, privacy: .public)")
            run.fail()
            feed.fail()
        } catch {
            phone[s, default: .init()].problem = "The scan failed: \(error.localizedDescription)"
            run.fail()
            feed.fail()
        }
    }

    /// Items read in one go (Calendar, Contacts) pass through the live display one by one, as read.
    nonisolated static func feedItems(_ items: [SourceItem], to feed: ScanFeed) {
        for (i, item) in items.enumerated() {
            feed.item(ScanEvent(name: item.name, read: true, done: i + 1, total: items.count))
        }
    }

    /// Items whose text came from a picture (OCR): images and scanned PDFs with text.
    nonisolated static func ocrItems(_ r: ScanResult) -> Int {
        r.items.filter { $0.kind == .image && $0.hasText }.count
    }

    private func store(_ out: PhoneScanOutput, as cacheId: String, for s: PhoneSource) throws {
        guard let library else { return }
        _ = try library.store(sourceId: cacheId, result: out.result)
        if !out.state.isEmpty { deps.state.set(s.rawValue, out.state) }
        var st = state(s)
        apply(cached: s, to: &st)
        phone[s] = st
        revision += 1
    }

    private func runOffMain<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { cont in
            queue.async { cont.resume(with: Result { try work() }) }
        }
    }

    // MARK: Files

    func addPicked(_ urls: [URL]) async {
        do { try deps.bookmarks.add(urls) } catch {
            phone[.files, default: .init()].problem = "Could not keep access to that location: \(error.localizedDescription)"
            return
        }
        if !isPhoneEnabled(.files) { await setPhoneEnabled(.files, true) } else { await scanPhone(.files) }
    }

    func removePicked(_ id: String) async {
        try? deps.bookmarks.remove(id: id)
        await scanPhone(.files)
    }

    // MARK: Mail

    var mailAccount: MailAccount? { deps.mailAccounts.load() }

    /// Saves the account and its app password (Keychain, this device only), then syncs once.
    func saveMail(_ account: MailAccount, secret: String) async throws {
        let host = account.host.trimmingCharacters(in: .whitespaces).lowercased()
        guard !host.isEmpty, host.range(of: #"^[a-z0-9.-]+$"#, options: .regularExpression) != nil else {
            throw PhoneSourceError("Enter the IMAP server name, e.g. imap.mail.me.com.")
        }
        let secret = MailInput.secret(secret, host: host)
        guard !account.username.trimmingCharacters(in: .whitespaces).isEmpty, !secret.isEmpty else {
            throw PhoneSourceError("Enter the user name and the app password.")
        }
        var clean = account
        clean.host = host
        clean.username = MailInput.username(account.username, host: host)
        if let old = deps.mailAccounts.load(), old != clean { removeMailData(old) }
        try deps.keychain(clean).save(secret)
        try deps.mailAccounts.save(clean)
        phone[.mail, default: .init()].detail = "Online · \(clean.username) on \(clean.host)"
        if !isPhoneEnabled(.mail) { await setPhoneEnabled(.mail, true) } else { await scanPhone(.mail) }
    }

    /// Forgets the mailbox: its password or token, every fetched message and its items.
    func removeMail() {
        if let account = deps.mailAccounts.load() { removeMailData(account) }
        try? deps.mailAccounts.save(nil)
        _ = try? library?.store(sourceId: PhoneSourceIds.shared.MAIL, result: ScanResult.companion.EMPTY)
        loadPhoneStates()
        revision += 1
    }

    private func removeMailData(_ account: MailAccount) {
        try? deps.keychain(account).delete()
        MailProducer.forget(account, cacheRoot: deps.mailCache)
        deps.state.set(PhoneSource.mail.rawValue, [:])
    }

    /// Signs in with Google (the Gmail API, read-only) or Microsoft (IMAP), only when a client ID is
    /// configured, and saves the account.
    func signInMail(_ provider: OAuthProvider, username: String) async throws {
        guard case .ready(let clientId) = deps.oauth.availability(provider) else {
            if case .needsClientId(let why) = deps.oauth.availability(provider) { throw PhoneSourceError(why) }
            return
        }
        let tokens = try await OAuthFlow(provider: provider, clientId: clientId).signIn(loginHint: username, session: deps.http)
        try await saveOAuthMail(provider, tokens: tokens, username: username)
    }

    /// After the browser step: for Google, the address comes from users.getProfile.
    func saveOAuthMail(_ provider: OAuthProvider, tokens: OAuthTokens, username: String) async throws {
        let account: MailAccount
        switch provider {
        case .google:
            let profile = try await GmailClient(accessToken: tokens.accessToken, session: deps.http).profile()
            account = MailAccount(host: GmailProducer.host, port: 443, username: profile.emailAddress, auth: .gmailAPI)
        case .microsoft:
            account = MailAccount(host: provider.imapHost, port: 993, username: username, auth: .microsoftOAuth)
        }
        let json = String(decoding: try JSONEncoder().encode(tokens), as: UTF8.self)
        try await saveMail(account, secret: json)
    }

    private func mailCredential(_ account: MailAccount) async throws -> IMAPCredential {
        if account.auth == .appPassword {
            guard let secret = deps.keychain(account).read(), !secret.isEmpty else {
                throw PhoneSourceError("The mailbox password is missing from the Keychain. Remove the mailbox and add it again.")
            }
            return .password(secret)
        }
        return .oauthToken(try await oauthAccessToken(account))
    }

    /// A fresh access token from the refresh token kept in the Keychain (this device only).
    func oauthAccessToken(_ account: MailAccount) async throws -> String {
        guard let secret = deps.keychain(account).read(), !secret.isEmpty else {
            throw PhoneSourceError("The mailbox sign-in is missing from the Keychain. Remove the mailbox and add it again.")
        }
        let provider: OAuthProvider = account.auth == .gmailAPI ? .google : .microsoft
        guard case .ready(let clientId) = deps.oauth.availability(provider) else {
            throw PhoneSourceError("Needs a \(provider.name) OAuth client ID. Remove the mailbox and add it with an app password.")
        }
        let saved = try JSONDecoder().decode(OAuthTokens.self, from: Data(secret.utf8))
        guard let refresh = saved.refreshToken else { return saved.accessToken }
        let flow = OAuthFlow(provider: provider, clientId: clientId)
        let fresh = try await flow.exchange(flow.refreshRequest(refreshToken: refresh), session: deps.http)
        let keep = OAuthTokens(accessToken: fresh.accessToken, refreshToken: fresh.refreshToken ?? refresh, expiresIn: fresh.expiresIn)
        try deps.keychain(account).save(String(decoding: try JSONEncoder().encode(keep), as: UTF8.self))
        return keep.accessToken
    }
}
