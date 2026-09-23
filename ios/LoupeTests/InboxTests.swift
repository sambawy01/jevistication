import LoupeKit
import XCTest
@testable import Loupe

/// The Inbox (epic #7 child 15) through the app's service: imports land as batches, their items
/// reach `items()` (what judgments, the sweep, the watchers, the privacy check and mail triage
/// read) labelled with where they came from, archives are unpacked safely, the share sheet's waiting
/// folder is collected on open, and a removed batch leaves no items behind.
final class InboxTests: XCTestCase {
    private var home: URL!
    private var shareInbox: URL!
    private var staging: URL!

    override func setUpWithError() throws {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("Inbox-\(UUID().uuidString)")
        shareInbox = home.appendingPathComponent("SharedInbox")
        staging = home.appendingPathComponent("picked")
        for d in [shareInbox!, staging!] { try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true) }
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    @MainActor
    private func service() -> SourcesService {
        let deps = PhoneDependencies(
            photos: FakePhotoLibrary(), recognizer: FakeRecognizer(text: [:]), events: FakeEventStore(), contacts: FakeContactStore(),
            bookmarks: BookmarkStore(home: home, resolver: FakeBookmarks()), inbox: { [shareInbox] in shareInbox! },
            mailAccounts: MailAccountStore(home: home), mailCache: home.appendingPathComponent("mail"),
            keychain: { _ in MemoryKeyStore() }, makeTransport: { _ in fatalError("no network in Inbox tests") },
            oauth: OAuthConfig(googleClientId: "", microsoftClientId: ""), state: PhoneStateStore(home: home))
        return SourcesService(home: home, sampleRoot: nil, deps: deps)
    }

    private func write(_ name: String, _ text: String) throws -> URL {
        let url = staging.appendingPathComponent(name)
        try Data(text.utf8).write(to: url)
        return url
    }

    @MainActor
    func testImportedCsvRowsAreItemsEverywhereAndRemovable() async throws {
        let s = service()
        let csv = try write("statement.csv", SourcesService.inboxDemoCSV)
        let imported = await s.importToInbox([csv])
        let batch = try XCTUnwrap(imported)
        XCTAssertEqual(batch.rowCount, 3)
        XCTAssertEqual(s.inboxBatches.map(\.id), [batch.id])
        let rows = s.items().filter { $0.sourceId == PhoneSourceIds.shared.INBOX }
        XCTAssertEqual(rows.map(\.name), ["STREAMFLIX.COM", "STREAMFLIX.COM", "Cafe Luna"])
        XCTAssertTrue(rows.allSatisfy { $0.facts["imported"]?.hasPrefix("Imported · Files · statement.csv") == true })
        XCTAssertTrue(rows[2].text.contains("Amount: -1,234.50"), rows[2].text)
        XCTAssertEqual(rows[2].sourceLabel, rows[2].facts["imported"], "results show where an item came from")
        XCTAssertEqual(s.judgeableItems().filter { $0.sourceId == "inbox" }.count, 3, "judgments and the sweep read them")

        let before = s.revision
        s.removeInboxBatch(batch.id)
        XCTAssertTrue(s.inboxBatches.isEmpty)
        XCTAssertTrue(s.items().isEmpty)
        XCTAssertGreaterThan(s.revision, before, "watchers re-run")
        XCTAssertTrue(FileManager.default.fileExists(atPath: csv.path), "the original is never touched")
    }

    @MainActor
    func testMailAndArchivesReachMailTriageSafely() async throws {
        let eml = "From: Billing <billing@streamflix.example>\r\nTo: me@example.com\r\nSubject: Your receipt\r\nDate: Fri, 5 Jun 2026 09:00:00 +0000\r\n\r\nThanks for your payment of 9.99 GBP.\r\n"
        let zip = staging.appendingPathComponent("export.zip")
        try Self.zip([("mail/receipt.eml", eml), ("../../outside.txt", "escape"), ("notes/todo.txt", "Cancel the gym before October renews.")]).write(to: zip)
        let s = service()
        let imported = await s.importToInbox([zip])
        let batch = try XCTUnwrap(imported)
        XCTAssertEqual(batch.emailCount, 1)
        XCTAssertEqual(batch.skippedCount, 1)
        XCTAssertTrue(s.inboxSkipped(batch).first?.reason.hasPrefix("unsafe path") == true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: home.appendingPathComponent("outside.txt").path))
        let mail = s.items().filter { $0.kind == .email }
        XCTAssertEqual(mail.first?.email?.fromAddress, "billing@streamflix.example", "mail triage reads imported mail like any other")
    }

    @MainActor
    func testShareSheetWaitingFolderBecomesOneBatchOnOpen() async throws {
        let waiting = SharedInbox.importFolder(in: shareInbox)
        try SharedInbox.drop(text: "https://shop.example/order/77", title: "shop.example", into: waiting)
        try SharedInbox.drop(file: try write("s.csv", "Date,Payee,Amount\n2026-06-05,Gym,30.00\n"), into: waiting)
        XCTAssertTrue(SharedInbox.goesToInbox(URL(fileURLWithPath: "/x/a.MBOX")))
        XCTAssertFalse(SharedInbox.goesToInbox(URL(fileURLWithPath: "/x/a.pdf")))
        let s = service()
        await s.collectShared()
        XCTAssertEqual(s.inboxBatches.count, 1)
        XCTAssertEqual(s.inboxBatches.first?.origin, "Share sheet")
        XCTAssertEqual(s.items().count, 2)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: waiting.path).isEmpty, "collected once, not again")
        await s.collectShared()
        XCTAssertEqual(s.inboxBatches.count, 1)
        // The `shared` scan (Files) never reads the hidden waiting folder.
        XCTAssertTrue(try SharedInbox.scan(folder: shareInbox).result.items.isEmpty)
    }

    @MainActor
    func testPastedTextAndTheSwitch() async throws {
        let s = service()
        _ = await s.importTextToInbox("Parking permit renews on 30 November 2026, reference PP-2231.")
        XCTAssertEqual(s.items().count, 1)
        XCTAssertEqual(s.items().first?.facts["imported"]?.hasPrefix("Imported · Pasted"), true)
        s.setInboxEnabled(false)
        XCTAssertTrue(s.items().isEmpty, "off: imported items leave every judgment and watcher")
        s.setInboxEnabled(true)
        XCTAssertEqual(s.items().count, 1)
        _ = await s.importTextToInbox("   ")
        XCTAssertEqual(s.inboxBatches.count, 1, "nothing to import makes no batch")
    }

    /// A stored-only ZIP, enough for the flow (the malicious cases are LoupeKit's ZipReaderTest).
    static func zip(_ entries: [(String, String)]) -> Data {
        var out = Data()
        var central = Data()
        func u16(_ v: Int, _ d: inout Data) { d.append(UInt8(v & 0xFF)); d.append(UInt8((v >> 8) & 0xFF)) }
        func u32(_ v: UInt32, _ d: inout Data) { u16(Int(v & 0xFFFF), &d); u16(Int(v >> 16), &d) }
        for (name, text) in entries {
            let data = Data(text.utf8)
            let n = Data(name.utf8)
            let crc = crc32(data)
            let offset = UInt32(out.count)
            u32(0x04034b50, &out); u16(20, &out); u16(0x800, &out); u16(0, &out); u16(0, &out); u16(0, &out)
            u32(crc, &out); u32(UInt32(data.count), &out); u32(UInt32(data.count), &out); u16(n.count, &out); u16(0, &out)
            out.append(n); out.append(data)
            u32(0x02014b50, &central); u16((3 << 8) | 20, &central); u16(20, &central); u16(0x800, &central); u16(0, &central)
            u16(0, &central); u16(0, &central); u32(crc, &central); u32(UInt32(data.count), &central); u32(UInt32(data.count), &central)
            u16(n.count, &central); u16(0, &central); u16(0, &central); u16(0, &central); u16(0, &central)
            u32(UInt32(0x81A4) << 16, &central); u32(offset, &central); central.append(n)
        }
        let cdStart = UInt32(out.count)
        out.append(central)
        u32(0x06054b50, &out); u16(0, &out); u16(0, &out); u16(entries.count, &out); u16(entries.count, &out)
        u32(UInt32(central.count), &out); u32(cdStart, &out); u16(0, &out)
        return out
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in data {
            c ^= UInt32(b)
            for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1) }
        }
        return ~c
    }
}
