import LoupeKit
import UIKit
import XCTest
@testable import Loupe

/// Owner rule 2026-09-24: every result that names an item can open it (documents and pictures
/// only), share it, find a message in Mail, and "Show where" — masked, re-derived, never stored.
final class ItemReferenceTests: XCTestCase {
    private var dir: URL!
    private let card = "4539148803436467"          // Luhn-valid test number, IIN 4539 (not a real card)
    private var grouped: String { stride(from: 0, to: 16, by: 4).map { i in String(Array(card)[i..<i + 4]) }.joined(separator: " ") }

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeItemRef-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private struct FakeLocator: PrivacyLocating {
        let folder: URL
        func access(for itemId: String) -> PrivacyAccess {
            if itemId.hasPrefix("photos:") { return .photo(localId: String(itemId.dropFirst(7))) }
            if itemId.hasPrefix("files:t/") { return .file(folder.appendingPathComponent(String(itemId.dropFirst(8))), scope: nil) }
            return .suggestOnly("read only")
        }
    }

    private struct FakeResolver: ItemResolving {
        let target: ItemTarget
        func target(for item: SourceItem) -> ItemTarget { target }
    }

    private struct FakeLines: OCRLineReading {
        let lines: [OCRLine]
        func lines(in image: CGImage) -> [OCRLine] { lines }
    }

    private func item(_ id: String, kind: ItemKind = .text, path: String? = nil, text: String = "hello", source: String = "files") -> SourceItem {
        SourceItem(id: id, sourceId: source, kind: kind, path: path ?? "/s/\(id)", messageIndex: nil, name: (path ?? id).components(separatedBy: "/").last!,
                   text: text, hasText: true, textTruncated: false, sizeBytes: Int64(text.count), contentHash: "h-\(id)",
                   mime: "text/plain", date: nil, dateOrigin: nil, email: nil, facts: [:], duplicateOf: nil)
    }

    // MARK: open policy

    func testQuickLookOpensDocumentsAndPicturesOnly() {
        for ok in ["a.pdf", "b.PNG", "c.heic", "d.jpg", "e.txt", "f.csv", "g.docx", "i.rtf"] {
            XCTAssertTrue(ItemOpenPolicy.allows(URL(fileURLWithPath: "/x/\(ok)")), ok)
        }
        for bad in ["run.sh", "a.py", "b.js", "c.html", "d.command", "e.app", "f.swift", "g.json", "h.zip", "noextension", "i.plist", "j.rb"] {
            XCTAssertFalse(ItemOpenPolicy.allows(URL(fileURLWithPath: "/x/\(bad)")), bad)
        }
    }

    // MARK: open actions (fakes)

    func testPlansForPhotoFileScriptMailAndUnavailable() {
        let i = item("x")
        XCTAssertEqual(ItemActionPlan.make(for: i, target: .photo(localId: "P1")).open, .photo("P1"))
        XCTAssertEqual(ItemActionPlan.make(for: i, target: .photo(localId: "P1")).sharesPhoto, "P1")

        let pdf = dir.appendingPathComponent("r.pdf")
        let p = ItemActionPlan.make(for: i, target: .file(pdf, scope: nil))
        XCTAssertEqual(p.open, .quickLook(pdf, scope: nil))
        XCTAssertEqual(p.share, pdf)

        let script = ItemActionPlan.make(for: i, target: .file(dir.appendingPathComponent("evil.sh"), scope: nil))
        guard case .blocked = script.open else { return XCTFail("a script must not open") }
        XCTAssertNil(script.share)

        let ref = MailRef(messageId: "<abc.1@mail.example.com>", headers: [MailHeader(name: "Subject", value: "Hi")], file: nil)
        XCTAssertEqual(ItemActionPlan.make(for: i, target: .mail(ref)).open, .mail(URL(string: "message://%3Cabc.1%40mail.example.com%3E")!))
        let noId = MailRef(messageId: nil, headers: [MailHeader(name: "From", value: "a@b.c")], file: nil)
        XCTAssertEqual(ItemActionPlan.make(for: i, target: .mail(noId)).open, .headers(noId))

        guard case .blocked(let why) = ItemActionPlan.make(for: i, target: .unavailable("gone")).open else { return XCTFail() }
        XCTAssertEqual(why, "gone")
    }

    func testLiveResolverMapsPhotosFilesSampleAndMail() throws {
        let r = LiveItemResolver(locator: FakeLocator(folder: dir))
        XCTAssertEqual(r.target(for: item("photos:ABC", kind: .image)), .photo(localId: "ABC"))
        XCTAssertEqual(r.target(for: item("files:t/a.pdf")), .file(dir.appendingPathComponent("a.pdf"), scope: nil))
        let sample = dir.appendingPathComponent("s.txt")
        try "x".write(to: sample, atomically: true, encoding: .utf8)
        XCTAssertEqual(r.target(for: item("sample:documents/s.txt", path: sample.path, source: SourcesService.sampleId)), .file(sample, scope: nil))

        let eml = dir.appendingPathComponent("m.eml")
        try "From: Nile Bank <alerts@nile.example>\r\nSubject: Your\r\n statement\r\nMessage-ID: <x-1@nile.example>\r\nDate: Tue, 1 Sep 2026 10:00:00 +0000\r\n\r\nBody Message-ID: <nope>".write(to: eml, atomically: true, encoding: .utf8)
        guard case .mail(let ref) = r.target(for: item("mail:acct/m.eml", kind: .email, path: eml.path, source: "mail")) else { return XCTFail() }
        XCTAssertEqual(ref.messageId, "<x-1@nile.example>")
        XCTAssertEqual(ref.headers.first { $0.name == "Subject" }?.value, "Your statement")
        XCTAssertEqual(ref.mailURL?.absoluteString, "message://%3Cx-1%40nile.example%3E")
        XCTAssertEqual(ref.file, eml)
    }

    // MARK: Show where

    private func blankImage() -> CGImage {
        UIGraphicsImageRenderer(size: CGSize(width: 10, height: 10)).image { _ in }.cgImage!
    }

    func testEvidenceOnAPhotoIsMaskedAndBoxedOnItsLine() {
        let photo = PhoneItems().photo(localId: "P1", name: "IMG_1507.HEIC", ocrText: "VISA\n\(grouped)", createdIso: "2026-09-01",
                                       takenIso: nil, dimensions: nil, camera: nil, hasLocation: false, screenshot: false, sizeBytes: 1)
        let box = CGRect(x: 0.1, y: 0.4, width: 0.8, height: 0.1)
        let lines = [OCRLine(text: "LOUPE TEST BANK", box: CGRect(x: 0.1, y: 0.8, width: 0.5, height: 0.1)),
                     OCRLine(text: "VISA", box: CGRect(x: 0.1, y: 0.6, width: 0.2, height: 0.1)),
                     OCRLine(text: grouped, box: box)]
        let shown = EvidenceDeriver(lineReader: FakeLines(lines: lines)).derive(item: photo, ruleId: "card_number", image: blankImage())
        XCTAssertEqual(shown.count, 1)
        XCTAssertEqual(shown[0].masked, "•••• •••• •••• 6467")
        XCTAssertEqual(shown[0].brand, "Visa")
        XCTAssertEqual(shown[0].box, box)
        XCTAssertTrue(shown[0].checks.contains { $0.contains("Luhn") })
        XCTAssertFalse(shown[0].context.filter(\.isNumber).contains("45391488"))
    }

    func testEvidenceOnTextHasNoBox() {
        let i = item("files:t/n.txt", text: "Card: \(grouped) exp 09/29")
        let shown = EvidenceDeriver(lineReader: FakeLines(lines: [])).derive(item: i, ruleId: "card_number", image: nil)
        XCTAssertEqual(shown.map(\.masked), ["•••• •••• •••• 6467"])
        XCTAssertNil(shown[0].box)
    }

    /// No extracted text is persisted: deriving evidence (and building the plan) writes nothing
    /// anywhere in the app's container or its defaults that holds the number.
    func testShowWhereWritesNothing() throws {
        let roots = [URL(fileURLWithPath: NSHomeDirectory()), FileManager.default.temporaryDirectory]
        UserDefaults.standard.synchronize()
        let before = snapshot(roots)
        let i = item("files:t/n.txt", text: "Card: \(grouped) — IBAN EG380019000500000000263180002")
        for _ in 0..<3 {
            _ = EvidenceDeriver(lineReader: FakeLines(lines: [OCRLine(text: grouped, box: .zero)])).derive(item: i, ruleId: "card_number", image: blankImage())
            _ = EvidenceDeriver(lineReader: FakeLines(lines: [])).derive(item: i, ruleId: "iban", image: nil)
        }
        UserDefaults.standard.synchronize()
        let after = snapshot(roots)
        for (path, stamp) in after where before[path] != stamp {
            guard !path.contains("LoupeItemRef-"), let data = FileManager.default.contents(atPath: path) else { continue }
            let s = String(decoding: data, as: UTF8.self)
            XCTAssertFalse(s.contains(card) || s.contains(grouped) || s.contains("263180002"), "evidence written to \(path)")
        }
        let defaults = "\(UserDefaults.standard.dictionaryRepresentation())"
        XCTAssertFalse(defaults.contains(card) || defaults.contains(grouped))
    }

    private func snapshot(_ roots: [URL]) -> [String: Date] {
        var out: [String: Date] = [:]
        for root in roots {
            guard let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey]) else { continue }
            for case let u as URL in e {
                let v = try? u.resourceValues(forKeys: [.contentModificationDateKey, .isRegularFileKey])
                if v?.isRegularFile == true { out[u.path] = v?.contentModificationDate ?? .distantPast }
            }
        }
        return out
    }

    // MARK: row facts

    func testRowShowsSourceFolderAndDate() {
        let photo = PhoneItems().photo(localId: "P1", name: "IMG_1507.HEIC", ocrText: "", createdIso: "2026-09-01",
                                       takenIso: nil, dimensions: nil, camera: nil, hasLocation: false, screenshot: false, sizeBytes: 1)
        XCTAssertEqual(photo.sourceAndFolder, "Photos")
        XCTAssertEqual(photo.dateLine, "Taken 2026-09-01 (photo library date)")
        let f = item("files:loc/Receipts/a.pdf", path: "/tmp/Receipts/a.pdf")
        XCTAssertEqual(f.sourceAndFolder, "Files · Receipts")
    }
}
