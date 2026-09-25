import Foundation
import Network

// A small read-only IMAP4rev1 client (RFC 3501) for the Mail source (epic #7 child 7). No third-party
// code. Read-only by construction, as Loupe Station's `mail/imap.py` (the spec this ports): the
// mailbox is opened with EXAMINE, bodies are fetched with BODY.PEEK so nothing is marked read, and
// `IMAPClient.send` refuses every command outside a short allowlist.

/// One server response: untagged (`*`), a continuation (`+`) or tagged (`A1 OK …`). Literals
/// (`{n}` followed by n bytes) are cut out of [text] — each is replaced by `{n}` — and kept in
/// [literals], in order.
struct IMAPResponse: Equatable {
    enum Kind: Equatable { case untagged, continuation, tagged(String) }
    let kind: Kind
    let text: String
    let literals: [Data]

    /// `OK`, `NO` or `BAD` for a tagged or untagged status response.
    var status: String? {
        let first = text.split(separator: " ", maxSplits: 1).first.map(String.init)?.uppercased()
        return ["OK", "NO", "BAD", "BYE", "PREAUTH"].contains(first ?? "") ? first : nil
    }

    /// A bracketed response code's argument, e.g. `code("UIDVALIDITY")` in `OK [UIDVALIDITY 7] …`.
    func code(_ name: String) -> String? {
        guard let open = text.range(of: "[" + name + " ", options: .caseInsensitive),
              let close = text.range(of: "]", range: open.upperBound..<text.endIndex) else { return nil }
        return String(text[open.upperBound..<close.lowerBound])
    }
}

/// Splits a byte stream into responses. Feed it whatever the socket delivers; `next()` returns a
/// response once it is complete (every literal fully received), nil until then.
struct IMAPResponseReader {
    private(set) var buffer = Data()
    /// A response larger than this is refused: a hostile server cannot make the phone buffer gigabytes.
    var maxResponseBytes = 64 * 1024 * 1024

    mutating func feed(_ data: Data) { buffer.append(data) }

    enum ReadError: Error, Equatable { case tooLarge, malformed(String) }

    mutating func next() throws -> IMAPResponse? {
        var text = Data()
        var literals: [Data] = []
        var i = buffer.startIndex
        while true {
            guard let crlf = buffer[i...].firstRange(of: Data([13, 10])) else {
                if buffer.count > maxResponseBytes { throw ReadError.tooLarge }
                return nil
            }
            let line = buffer[i..<crlf.lowerBound]
            // A line ending in {n} announces a literal of n bytes after the CRLF.
            if let n = Self.literalLength(line) {
                guard n <= maxResponseBytes else { throw ReadError.tooLarge }
                let start = crlf.upperBound
                guard buffer.distance(from: start, to: buffer.endIndex) >= n else { return nil }
                let end = buffer.index(start, offsetBy: n)
                text.append(line)
                literals.append(Data(buffer[start..<end]))
                i = end
                continue
            }
            text.append(line)
            let rest = crlf.upperBound
            buffer = Data(buffer[rest...])
            return try Self.response(String(decoding: text, as: UTF8.self), literals)
        }
    }

    private static func literalLength(_ line: Data) -> Int? {
        guard line.last == UInt8(ascii: "}"), let open = line.lastIndex(of: UInt8(ascii: "{")) else { return nil }
        var digits = line[line.index(after: open)..<line.index(before: line.endIndex)]
        if digits.last == UInt8(ascii: "+") { digits = digits.dropLast() }   // LITERAL+ (non-synchronising)
        guard !digits.isEmpty, digits.allSatisfy({ $0 >= 48 && $0 <= 57 }), digits.count < 12 else { return nil }
        return Int(String(decoding: digits, as: UTF8.self))
    }

    private static func response(_ line: String, _ literals: [Data]) throws -> IMAPResponse {
        if line.hasPrefix("* ") { return IMAPResponse(kind: .untagged, text: String(line.dropFirst(2)), literals: literals) }
        if line == "+" || line.hasPrefix("+ ") {
            return IMAPResponse(kind: .continuation, text: String(line.dropFirst(min(2, line.count))), literals: literals)
        }
        guard let space = line.firstIndex(of: " ") else { throw ReadError.malformed(line) }
        return IMAPResponse(kind: .tagged(String(line[..<space])), text: String(line[line.index(after: space)...]), literals: literals)
    }
}

/// Parsing the few untagged responses the client needs.
enum IMAPParse {
    /// `SEARCH 4 9 12` → [4, 9, 12].
    static func search(_ r: IMAPResponse) -> [UInt32]? {
        let parts = r.text.split(separator: " ")
        guard parts.first?.uppercased() == "SEARCH" else { return nil }
        return parts.dropFirst().compactMap { UInt32($0) }
    }

    /// `12 EXISTS` → 12.
    static func exists(_ r: IMAPResponse) -> Int? {
        let parts = r.text.split(separator: " ")
        guard parts.count == 2, parts[1].uppercased() == "EXISTS" else { return nil }
        return Int(parts[0])
    }

    /// `5 FETCH (UID 101 BODY[] {n})` → (101, the literal). Only responses carrying a UID and a body.
    static func fetch(_ r: IMAPResponse) -> (uid: UInt32, body: Data)? {
        let parts = r.text.split(separator: " ", maxSplits: 2)
        guard parts.count == 3, parts[1].uppercased() == "FETCH", let body = r.literals.first else { return nil }
        guard let range = r.text.range(of: "UID ", options: .caseInsensitive) else { return nil }
        let digits = r.text[range.upperBound...].prefix { $0.isNumber }
        guard let uid = UInt32(digits) else { return nil }
        return (uid, body)
    }

    /// An IMAP quoted string. Refuses CR, LF and NUL, which no quoted string may carry.
    static func quote(_ s: String) throws -> String {
        // By scalar: "\r\n" is one Swift Character, so a Character check would miss it.
        guard !s.unicodeScalars.contains(where: { $0 == "\r" || $0 == "\n" || $0 == "\0" }) else {
            throw PhoneSourceError("The user name or password contains a line break, which IMAP cannot send.")
        }
        return "\"" + s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
    }
}

/// A byte pipe to the server. The real one is TLS over Network.framework; tests replay a recorded
/// transcript, so no test touches the network.
protocol IMAPTransport: AnyObject {
    func open() async throws
    func send(_ data: Data) async throws
    /// The next chunk of bytes from the server (any size), or throws when the connection closes.
    func receive() async throws -> Data
    func close()
}

/// How to sign in: an app password (iCloud, Fastmail, Gmail with 2-step and an app password…), or an
/// OAuth access token through SASL XOAUTH2 (Gmail and Outlook, once a client ID is configured).
enum IMAPCredential: Equatable {
    case password(String)
    case oauthToken(String)
}

/// What one sync fetched.
struct IMAPSyncResult: Equatable {
    let uidValidity: UInt32
    let highestUid: UInt32
    let messages: [(uid: UInt32, raw: Data)]
    /// Newer messages left for the next sync (the per-sync cap was reached).
    let remaining: Int

    static func == (a: IMAPSyncResult, b: IMAPSyncResult) -> Bool {
        a.uidValidity == b.uidValidity && a.highestUid == b.highestUid && a.remaining == b.remaining
            && a.messages.map(\.uid) == b.messages.map(\.uid) && a.messages.map(\.raw) == b.messages.map(\.raw)
    }
}

final class IMAPClient {
    struct Failure: Error, Equatable, LocalizedError {
        enum Kind: Equatable { case authentication, protocolError, connection, mailbox }
        let kind: Kind
        let detail: String
        var errorDescription: String? { detail }
    }

    private let transport: IMAPTransport
    private var reader = IMAPResponseReader()
    private var tagCounter = 0
    /// The largest part of each message fetched: headers and text, not big attachments.
    var maxBytesPerMessage = 256 * 1024
    var batchSize = 25

    init(transport: IMAPTransport) { self.transport = transport }

    /// The only commands this client will send (after the tag). Everything else is refused.
    static func allowed(_ command: String) -> Bool {
        let c = command.uppercased()
        if c.hasPrefix("UID FETCH ") {
            // Read-only fetch items only: BODY.PEEK never sets \Seen; BODY[ and RFC822 would.
            return c.contains("BODY.PEEK[") && !c.contains(" BODY[") && !c.contains("(BODY[") && !c.contains("RFC822 ") && !c.contains("RFC822)")
        }
        return ["CAPABILITY", "LOGIN ", "AUTHENTICATE XOAUTH2 ", "EXAMINE ", "UID SEARCH ", "LOGOUT", "NOOP"].contains { c.hasPrefix($0) }
    }

    /// Sends one command and collects responses up to its tagged completion.
    @discardableResult
    func command(_ command: String) async throws -> [IMAPResponse] {
        guard Self.allowed(command) else {
            throw Failure(kind: .protocolError, detail: "Refused to send an IMAP command outside the read-only allowlist.")
        }
        tagCounter += 1
        let tag = "L\(tagCounter)"
        try await transport.send(Data("\(tag) \(command)\r\n".utf8))
        var out: [IMAPResponse] = []
        while true {
            let r = try await nextResponse()
            if case .tagged(let t) = r.kind, t == tag {
                if r.status != "OK" {
                    let kind: Failure.Kind = command.uppercased().hasPrefix("LOGIN") || command.uppercased().hasPrefix("AUTHENTICATE") ? .authentication : .mailbox
                    throw Failure(kind: kind, detail: r.text)
                }
                return out
            }
            if case .continuation = r.kind, command.uppercased().hasPrefix("AUTHENTICATE") {
                // XOAUTH2 failure: the server sends a base64 error challenge; answer with an empty line.
                try await transport.send(Data("\r\n".utf8))
                continue
            }
            if r.kind == .untagged, r.status == "BYE", !command.uppercased().hasPrefix("LOGOUT") {
                throw Failure(kind: .connection, detail: "The server closed the connection: \(r.text)")
            }
            out.append(r)
        }
    }

    private func nextResponse() async throws -> IMAPResponse {
        while true {
            do {
                if let r = try reader.next() { return r }
            } catch {
                throw Failure(kind: .protocolError, detail: "The server sent something that is not IMAP: \(error)")
            }
            reader.feed(try await transport.receive())
        }
    }

    /// Connects, signs in, opens INBOX read-only and fetches the messages after [afterUid] (all of
    /// them after a UIDVALIDITY change), newest [maxMessages] at most, then logs out.
    func sync(username: String, credential: IMAPCredential, mailbox: String = "INBOX",
              knownUidValidity: UInt32?, afterUid: UInt32, maxMessages: Int) async throws -> IMAPSyncResult {
        try await transport.open()
        defer { transport.close() }
        let greeting = try await nextResponse()
        guard greeting.kind == .untagged, greeting.status == "OK" || greeting.status == "PREAUTH" else {
            throw Failure(kind: .connection, detail: "The server did not greet as IMAP: \(greeting.text)")
        }
        switch credential {
        case .password(let password):
            try await command("LOGIN \(try IMAPParse.quote(username)) \(try IMAPParse.quote(password))")
        case .oauthToken(let token):
            let sasl = "user=\(username)\u{1}auth=Bearer \(token)\u{1}\u{1}"
            try await command("AUTHENTICATE XOAUTH2 \(Data(sasl.utf8).base64EncodedString())")
        }
        let examine = try await command("EXAMINE \(try IMAPParse.quote(mailbox))")
        guard let validity = examine.lazy.compactMap({ $0.code("UIDVALIDITY") }).first.flatMap(UInt32.init) else {
            throw Failure(kind: .mailbox, detail: "The server did not say the mailbox's UIDVALIDITY.")
        }
        let exists = examine.lazy.compactMap(IMAPParse.exists).first ?? 0
        let from = (knownUidValidity == validity) ? afterUid : 0
        var uids: [UInt32] = []
        if exists > 0 {
            let found = try await command("UID SEARCH UID \(from &+ 1):*")
            // "n:*" always matches the highest UID, even below n: keep only the new ones.
            uids = found.compactMap(IMAPParse.search).flatMap { $0 }.filter { $0 > from }.sorted()
        }
        let take = Array(uids.suffix(maxMessages))
        var messages: [(uid: UInt32, raw: Data)] = []
        for start in stride(from: 0, to: take.count, by: batchSize) {
            let chunk = take[start..<min(start + batchSize, take.count)]
            let set = chunk.map(String.init).joined(separator: ",")
            let responses = try await command("UID FETCH \(set) (UID BODY.PEEK[]<0.\(maxBytesPerMessage)>)")
            messages += responses.compactMap(IMAPParse.fetch).map { (uid: $0.uid, raw: $0.body) }
        }
        _ = try? await command("LOGOUT")
        messages.sort { $0.uid < $1.uid }
        return IMAPSyncResult(uidValidity: validity, highestUid: max(take.last ?? from, from), messages: messages,
                              remaining: uids.count - take.count)
    }
}

/// TLS to the server over Network.framework (port 993, implicit TLS; the system validates the
/// certificate). No STARTTLS: a plaintext first hop is not offered.
final class NWIMAPTransport: IMAPTransport {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.loupe-ai.ios.imap")
    private let timeout: TimeInterval

    init(host: String, port: UInt16 = 993, timeout: TimeInterval = 30) {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = Int(timeout)
        let params = NWParameters(tls: NWProtocolTLS.Options(), tcp: tcp)
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 993, using: params)
        self.timeout = timeout
    }

    func open() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var resumed = false
            let finish: (Result<Void, Error>) -> Void = { r in
                guard !resumed else { return }
                resumed = true
                cont.resume(with: r)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(.success(()))
                case .failed(let e): finish(.failure(IMAPClient.Failure(kind: .connection, detail: "Could not connect: \(e.localizedDescription)")))
                case .waiting(let e): finish(.failure(IMAPClient.Failure(kind: .connection, detail: "No route to the mail server: \(e.localizedDescription)")))
                case .cancelled: finish(.failure(IMAPClient.Failure(kind: .connection, detail: "The connection was cancelled.")))
                default: break
                }
            }
            connection.start(queue: queue)
            let seconds = Int(timeout)
            queue.asyncAfter(deadline: .now() + timeout) {
                finish(.failure(IMAPClient.Failure(kind: .connection, detail: "The mail server did not answer in \(seconds) seconds.")))
            }
        }
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: IMAPClient.Failure(kind: .connection, detail: error.localizedDescription)) } else { cont.resume() }
            })
        }
    }

    func receive() async throws -> Data {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            var resumed = false
            let lock = NSLock()
            @discardableResult
            func finish(_ r: Result<Data, Error>) -> Bool {
                lock.lock(); defer { lock.unlock() }
                guard !resumed else { return false }
                resumed = true
                cont.resume(with: r)
                return true
            }
            connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { data, _, complete, error in
                if let data, !data.isEmpty { finish(.success(data)) } else if let error {
                    finish(.failure(IMAPClient.Failure(kind: .connection, detail: error.localizedDescription)))
                } else if complete {
                    finish(.failure(IMAPClient.Failure(kind: .connection, detail: "The server closed the connection.")))
                }
            }
            queue.asyncAfter(deadline: .now() + timeout) { [connection] in
                // Only a receive that is still waiting times out; one that got its bytes is done.
                if finish(.failure(IMAPClient.Failure(kind: .connection, detail: "The mail server stopped answering."))) {
                    connection.cancel()
                }
            }
        }
    }

    func close() { connection.cancel() }
}
