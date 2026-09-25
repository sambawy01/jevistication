import Foundation
import LoupeKit

/// A mailbox the user added. Not secret: the password or OAuth token lives in the Keychain.
struct MailAccount: Codable, Equatable {
    enum Auth: String, Codable { case appPassword, googleOAuth, microsoftOAuth }

    var host: String
    var port: UInt16
    var username: String
    var auth: Auth

    /// A short, stable key for the cache folder and item ids (no address in a file name).
    var key: String { BookmarkStore.stableId(URL(fileURLWithPath: "/\(username.lowercased())@\(host.lowercased())")) }

    func keychain() -> KeychainStore {
        KeychainStore(service: "dev.loupe.app.mail", account: "\(auth.rawValue):\(username.lowercased())@\(host.lowercased())")
    }
}

/// Presets for providers that support IMAP with an app password. Hosts are the providers' published ones.
struct MailPreset: Identifiable, Equatable {
    let id: String
    let name: String
    let host: String
    let help: String

    static let all: [MailPreset] = [
        MailPreset(id: "icloud", name: "iCloud Mail", host: "imap.mail.me.com",
                   help: "Use an app-specific password: appleid.apple.com → Sign-In and Security → App-Specific Passwords. Your user name is your iCloud email address."),
        MailPreset(id: "fastmail", name: "Fastmail", host: "imap.fastmail.com",
                   help: "Use an app password: Fastmail Settings → Privacy & Security → Integrations → App passwords, with IMAP access."),
        MailPreset(id: "gmail", name: "Gmail (app password)", host: "imap.gmail.com",
                   help: "Needs 2-Step Verification on, then myaccount.google.com → Security → App passwords. Your normal Google password will not work."),
        MailPreset(id: "other", name: "Other IMAP", host: "",
                   help: "Any server with IMAP over TLS on port 993. Use an app password if your provider offers one."),
    ]
}

/// Reads and writes the account list (JSON, one account today) under Application Support.
final class MailAccountStore {
    private let url: URL
    init(home: URL) { url = home.appendingPathComponent("sources/mail-account.json") }

    func load() -> MailAccount? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(MailAccount.self, from: data)
    }

    func save(_ account: MailAccount?) throws {
        if let account {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(account).write(to: url, options: [.atomic, .completeFileProtection])
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }
}

/// The Mail producer: one IMAP sync (only messages after the last UID seen, all again if the
/// server's UIDVALIDITY changed), each message kept as a `.eml` file under Application Support, then
/// the whole folder read by the common scanner and MIME parser — the same items an exported mailbox
/// gives — and every item labelled Online (PRODUCT.md §4a).
struct MailProducer {
    static let validityKey = "uidValidity"
    static let lastUidKey = "lastUid"

    let account: MailAccount
    let credential: IMAPCredential
    let cacheRoot: URL
    let makeTransport: (MailAccount) -> IMAPTransport
    var maxPerSync = 200
    var now: () -> Date = Date.init

    var folder: URL { cacheRoot.appendingPathComponent(account.key, isDirectory: true) }

    func scan(state: [String: String], observer: ScanObserver = NullScanObserver(), fetched: () -> Void = {}) async throws -> PhoneScanOutput {
        let client = IMAPClient(transport: makeTransport(account))
        let known = state[Self.validityKey].flatMap(UInt32.init)
        let after = state[Self.lastUidKey].flatMap(UInt32.init) ?? 0
        let sync = try await client.sync(username: account.username, credential: credential,
                                         knownUidValidity: known, afterUid: after, maxMessages: maxPerSync)
        fetched()
        let fm = FileManager.default
        if known != sync.uidValidity { try? fm.removeItem(at: folder) }   // the old UIDs mean nothing now
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        for m in sync.messages {
            try m.raw.write(to: folder.appendingPathComponent("\(sync.uidValidity).\(m.uid).eml"), options: [.atomic, .completeFileProtection])
        }
        let root = SourceRoot(id: PhoneSourceIds.shared.MAIL, type: .mailExport, path: folder.path, idPrefix: "mail:\(account.key)/")
        var result = try SourceScanner(extractors: AppleExtractors.live()).scan(sources: [root], observer: observer)
        result = PhoneItems.companion.labelOnline(result: result, from: account.host, fetchedIso: ISOStamp.now(now()))
        if sync.remaining > 0 {
            result = ScanResult(items: result.items, skipped: result.skipped + [Skipped(path: "Mail", reason: "not fetched: \(sync.remaining) older message\(sync.remaining == 1 ? "" : "s") — Loupe reads the newest \(maxPerSync) at a time, then only new mail")], unavailable: result.unavailable)
        }
        return PhoneScanOutput(result: result, state: [Self.validityKey: String(sync.uidValidity), Self.lastUidKey: String(sync.highestUid)])
    }

    /// Removes every fetched message of [account] from the phone.
    static func forget(_ account: MailAccount, cacheRoot: URL) {
        try? FileManager.default.removeItem(at: cacheRoot.appendingPathComponent(account.key, isDirectory: true))
    }
}

extension IMAPClient.Failure {
    /// The recovery sentence the Mail row shows (no host: generic wording).
    var recovery: String { recovery(host: "") }

    /// The recovery sentence for [host], read from the server's own response code and text: Gmail
    /// answers `NO [AUTHENTICATIONFAILED] Invalid credentials` for a wrong or normal password,
    /// `NO [ALERT] Application-specific password required` when 2-Step Verification needs an app
    /// password, `NO [ALERT] Your account is not enabled for IMAP use` when IMAP is off, and
    /// `[WEBALERT …]` / "log in via your web browser" when Google wants the sign-in confirmed.
    func recovery(host: String) -> String {
        let text = detail.lowercased()
        let gmail = host.lowercased().hasSuffix("gmail.com") || host.lowercased().hasSuffix("googlemail.com")
        let appPasswordHelp = "turn on 2-Step Verification, then create one at myaccount.google.com/apppasswords and paste its 16 letters here"
        switch kind {
        case .authentication:
            if text.contains("not enabled for imap") || text.contains("imap access is disabled") || text.contains("imap is disabled") {
                return gmail ? "IMAP is off in Gmail settings: open Gmail on the web → Settings → See all settings → Forwarding and POP/IMAP → Enable IMAP, then try again."
                    : "IMAP is turned off for this account. Turn it on in your mail provider's settings, then try again."
            }
            if text.contains("application-specific password") || text.contains("app password") {
                return gmail ? "Gmail needs an app password, not your Google password: \(appPasswordHelp)."
                    : "This server needs an app password, not your normal password. Create one in your provider's security settings."
            }
            if text.contains("webalert") || text.contains("web browser") || text.contains("web login required") {
                return gmail ? "Google blocked this sign-in until you confirm it: sign in at accounts.google.com on this iPhone, approve the new device, then try again."
                    : "The provider wants you to confirm this sign-in in a web browser first, then try again."
            }
            if text.contains("[unavailable]") || text.contains("temporarily") || text.contains("try again later") {
                return "The mail server is busy and refused the sign-in for now. Try again in a few minutes."
            }
            if gmail {
                return "Gmail refused the user name or password (\(Self.brief(detail))). Use your full Gmail address and an app password — your Google password does not work: \(appPasswordHelp)."
            }
            return "Wrong user name or password (\(Self.brief(detail))). Check the user name (usually your full email address) and use an app password if your provider gives one."
        case .connection:
            return "Could not reach the mail server (\(Self.brief(detail))). Check the server name and that you are online; Mail is the one source that needs the network."
        case .mailbox:
            return "The server would not open the inbox read-only: \(detail)"
        case .protocolError:
            return "The server's reply could not be read: \(detail)"
        }
    }

    /// The server's words without the tag noise, short.
    static func brief(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespaces)
        for p in ["NO ", "BAD "] where t.uppercased().hasPrefix(p) { t = String(t.dropFirst(p.count)) }
        return String(t.prefix(120))
    }
}

/// Cleans what people paste into the mailbox form. Google shows an app password as four groups of
/// four letters ("abcd efgh ijkl mnop"); the spaces and a stray newline from a paste are not part of
/// it. A bare Gmail user name gets its domain.
enum MailInput {
    static func secret(_ raw: String, host: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if isGoogle(host) {
            let squeezed = t.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "\u{00A0}", with: "")
            if squeezed.count == 16, squeezed.allSatisfy({ $0.isLetter }) { return squeezed }
        }
        return t
    }

    static func username(_ raw: String, host: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if isGoogle(host), !t.isEmpty, !t.contains("@") { return t + "@gmail.com" }
        return t
    }

    static func isGoogle(_ host: String) -> Bool {
        let h = host.lowercased()
        return h == "imap.gmail.com" || h.hasSuffix(".gmail.com") || h.hasSuffix("googlemail.com")
    }
}
