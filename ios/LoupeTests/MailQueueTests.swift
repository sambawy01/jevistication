import XCTest
import LoupeKit
@testable import Loupe

/// IMAP never drops new mail over the per-scan cap (2026-09-29, the same fix as Gmail's): newest first, the rest
/// queued in the state and fetched by the next scan or drained by the nightly run. Synthetic IMAP transcripts in the
/// shape of the recorded ones (`Fixtures/imap-*.txt`); no network.
final class MailQueueTests: XCTestCase {
    private var home: URL!
    private let account = MailAccount(host: "imap.example.com", port: 993, username: "me@example.com", auth: .appPassword)

    override func setUp() { home = FileManager.default.temporaryDirectory.appendingPathComponent("MailQueue-\(UUID().uuidString)") }
    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private static func eml(_ uid: UInt32) -> String {
        "From: News <news@example.com>\r\nTo: me@example.com\r\nSubject: Message \(uid)\r\nDate: Mon, 21 Sep 2026 09:00:00 +0000\r\nMessage-ID: <\(uid)@example.com>\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nBody \(uid).\r\n"
    }

    /// One sync's transcript: EXAMINE says UIDVALIDITY 7 and [exists]; the search from [from] + 1 answers [found];
    /// then one FETCH per batch in [fetches] (as the client must ask for them), answering every UID except [expunged];
    /// then LOGOUT.
    private static func sync(exists: Int, from: UInt32, found: [UInt32], fetches: [[UInt32]], expunged: Set<UInt32> = []) -> TranscriptTransport {
        var steps: [TranscriptTransport.Step] = [.server(Data("* OK IMAP ready\r\n".utf8)),
                                                 .client("L1 LOGIN \"me@example.com\" \"pw\""), .server(Data("L1 OK done\r\n".utf8)),
                                                 .client("L2 EXAMINE \"INBOX\""),
                                                 .server(Data("* \(exists) EXISTS\r\n* OK [UIDVALIDITY 7] UIDs valid\r\nL2 OK [READ-ONLY] done\r\n".utf8))]
        var tag = 3
        if exists > 0 {
            steps.append(.client("L3 UID SEARCH UID \(from + 1):*"))
            steps.append(.server(Data("* SEARCH\(found.map { " \($0)" }.joined())\r\nL3 OK done\r\n".utf8)))
            tag = 4
        }
        for batch in fetches {
            steps.append(.client("L\(tag) UID FETCH \(batch.map(String.init).joined(separator: ",")) (UID BODY.PEEK[]<0.262144>)"))
            var body = ""
            for (i, uid) in batch.enumerated() where !expunged.contains(uid) {
                let m = eml(uid)
                body += "* \(i + 1) FETCH (UID \(uid) BODY[]<0> {\(m.utf8.count)}\r\n\(m))\r\n"
            }
            steps.append(.server(Data("\(body)L\(tag) OK done\r\n".utf8)))
            tag += 1
        }
        steps.append(.client("L\(tag) LOGOUT"))
        steps.append(.server(Data("* BYE bye\r\nL\(tag) OK done\r\n".utf8)))
        return TranscriptTransport(steps: steps)
    }

    /// Newest first in batches of 25, each batch's UID set ascending.
    private static func batches(_ newestFirst: [UInt32]) -> [[UInt32]] {
        stride(from: 0, to: newestFirst.count, by: 25).map { Array(newestFirst[$0..<min($0 + 25, newestFirst.count)]).sorted() }
    }

    private func producer(_ t: TranscriptTransport, cap: Int = MailCap.scan) -> MailProducer {
        var p = MailProducer(account: account, credential: .password("pw"), cacheRoot: home.appendingPathComponent("mail"),
                             makeTransport: { _ in t })
        p.maxPerSync = cap
        return p
    }

    private func cached() -> Set<String> {
        let folder = home.appendingPathComponent("mail").appendingPathComponent(account.key)
        return Set(((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []).map { ($0 as NSString).deletingPathExtension })
    }

    private static func files(_ uids: [UInt32]) -> Set<String> { Set(uids.map { "7.\($0)" }) }

    func testOverTheCapQueuesTheRestAndTheNextScanFetchesThemWithNewMail() async throws {
        let new = Array(UInt32(101)...364) // 264 since the last scan (lastUid 100)
        let newestFirst = Array(new.reversed())
        let t1 = Self.sync(exists: 400, from: 100, found: new, fetches: Self.batches(Array(newestFirst.prefix(200))))
        let first = try await producer(t1).scan(state: [MailProducer.validityKey: "7", MailProducer.lastUidKey: "100"])
        XCTAssertNil(t1.mismatch)
        XCTAssertEqual(first.state[MailProducer.lastUidKey], "364", "every new UID is fetched or queued")
        XCTAssertEqual(first.state[MailProducer.pendingKey], newestFirst.dropFirst(200).map(String.init).joined(separator: ","),
                       "the 64 older ones are queued, newest first")
        XCTAssertEqual(first.result.skipped.first { $0.path == "Mail" }?.reason,
                       "not fetched yet: 64 older messages — Loupe reads 200 at a time, newest first; the rest are fetched on the next scan or overnight while charging")
        XCTAssertEqual(cached(), Self.files(Array(newestFirst.prefix(200))))

        // The next scan: the 2 new ones first, then the 64 queued.
        let t2 = Self.sync(exists: 402, from: 364, found: [365, 366],
                           fetches: Self.batches([366, 365] + Array(newestFirst.dropFirst(200))))
        let second = try await producer(t2).scan(state: first.state)
        XCTAssertNil(t2.mismatch)
        XCTAssertEqual(second.state, [MailProducer.validityKey: "7", MailProducer.lastUidKey: "366"], "the queue is empty")
        XCTAssertFalse(second.result.skipped.contains { $0.path == "Mail" })
        XCTAssertEqual(cached(), Self.files(new + [365, 366]), "nothing was dropped")
    }

    func testTheNightlyCapDrainsTheQueueInOneRun() async throws {
        let queue = Array(UInt32(101)...364).reversed().map { $0 } // 264 queued, newest first
        let state = [MailProducer.validityKey: "7", MailProducer.lastUidKey: "364",
                     MailProducer.pendingKey: queue.map(String.init).joined(separator: ",")]
        let t = Self.sync(exists: 400, from: 364, found: [], fetches: Self.batches(queue))
        let out = try await producer(t, cap: MailCap.overnight).scan(state: state)
        XCTAssertNil(t.mismatch)
        XCTAssertEqual(out.state, [MailProducer.validityKey: "7", MailProducer.lastUidKey: "364"])
        XCTAssertEqual(cached().count, 264)
    }

    func testAStoppedScanQueuesWhatItDidNotFetch() async throws {
        let new = Array(UInt32(101)...160) // 60 new: batches of 25, 25, 10
        let newestFirst = Array(new.reversed())
        let t = Self.sync(exists: 60, from: 100, found: new, fetches: [Self.batches(newestFirst)[0]])
        var asked = 0
        let out = try await producer(t, cap: MailCap.overnight).scan(state: [MailProducer.validityKey: "7", MailProducer.lastUidKey: "100"],
                                                                     shouldStop: { asked += 1; return asked > 1 })
        XCTAssertNil(t.mismatch)
        XCTAssertEqual(out.state[MailProducer.lastUidKey], "160")
        XCTAssertEqual(out.state[MailProducer.pendingKey], newestFirst.dropFirst(25).map(String.init).joined(separator: ","))
        XCTAssertEqual(cached(), Self.files(Array(newestFirst.prefix(25))))
    }

    func testAQueuedMessageExpungedMeanwhileIsNotQueuedAgain() async throws {
        let state = [MailProducer.validityKey: "7", MailProducer.lastUidKey: "300", MailProducer.pendingKey: "250,240,230"]
        let t = Self.sync(exists: 10, from: 300, found: [], fetches: [[230, 240, 250]], expunged: [240])
        let out = try await producer(t).scan(state: state)
        XCTAssertNil(t.mismatch)
        XCTAssertEqual(out.state, [MailProducer.validityKey: "7", MailProducer.lastUidKey: "300"])
        XCTAssertEqual(cached(), Self.files([230, 250]))
    }

    func testTheFirstSyncStartsFromTheNewestAndSaysHowManyOlderItDidNotRead() async throws {
        let all = Array(UInt32(1)...250)
        let t = Self.sync(exists: 250, from: 0, found: all, fetches: Self.batches(Array(all.suffix(200).reversed())))
        // A pending list from before a UIDVALIDITY change means nothing now.
        let out = try await producer(t).scan(state: [MailProducer.validityKey: "3", MailProducer.lastUidKey: "99", MailProducer.pendingKey: "5,4"])
        XCTAssertNil(t.mismatch)
        XCTAssertEqual(out.state, [MailProducer.validityKey: "7", MailProducer.lastUidKey: "250"])
        XCTAssertEqual(out.result.skipped.first { $0.path == "Mail" }?.reason,
                       "not read: 50 older messages from before Loupe was added — Loupe starts from the newest 200, then reads all new mail")
        XCTAssertEqual(cached(), Self.files(Array(all.suffix(200))))
    }

    func testAnEmptyMailboxClearsTheQueue() async throws {
        let t = Self.sync(exists: 0, from: 300, found: [], fetches: [])
        let out = try await producer(t).scan(state: [MailProducer.validityKey: "7", MailProducer.lastUidKey: "300", MailProducer.pendingKey: "250"])
        XCTAssertNil(t.mismatch)
        XCTAssertEqual(out.state, [MailProducer.validityKey: "7", MailProducer.lastUidKey: "300"])
    }
}
