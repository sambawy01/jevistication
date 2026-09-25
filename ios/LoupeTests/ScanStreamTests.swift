import CoreGraphics
import LoupeKit
import XCTest
@testable import Loupe

/// The live scan display's view model (Sources, 2026-09-25): counts, stage counts, rate and ETA, the throttle,
/// masking of what reaches the screen, and the summary line. No threads or screen (except the end-to-end feed test).
final class ScanStreamTests: XCTestCase {
    private let photos = ScanPipeline.of("photos")

    func testPhotoEventsCountReadTextAndSkippedPerStage() {
        var s = ScanStream(pipeline: photos)
        s.ingest(ScanEvent(name: "IMG_1.JPG", read: true, textFound: true, text: "RECEIPT Total 12.40", done: 1, total: 4), now: 0)
        s.ingest(ScanEvent(name: "IMG_2.JPG", read: true, textFound: false, done: 2, total: 4), now: 0.5)
        s.ingest(ScanEvent(name: "IMG_3.JPG", read: false, done: 3, total: 4), now: 1)
        let snap = s.snapshot(now: 1)
        XCTAssertEqual(snap.read, 2)
        XCTAssertEqual(snap.withText, 1)
        XCTAssertEqual(snap.skipped, 1)
        XCTAssertEqual(snap.done, 3)
        XCTAssertEqual(snap.total, 4)
        XCTAssertEqual(snap.stages.map(\.kind), [.read, .text, .saved], "photos: no model stage during a scan")
        XCTAssertEqual(snap.stages.map(\.count), [2, 1, nil], "Saved lands only when the scan finishes")
        XCTAssertEqual(snap.recent.first?.name, "IMG_3.JPG", "newest first")
        XCTAssertEqual(snap.ledger.map(\.name), ["IMG_1.JPG"], "the photo ledger lists photos with text")
        XCTAssertEqual(snap.batch, 3)
        XCTAssertEqual(s.snapshot(now: 1.1).batch, 0, "the conveyor slides only by what arrived since the last snapshot")
        XCTAssertEqual(snap.fraction ?? 0, 0.75, accuracy: 0.001)
    }

    func testStagesAreOnlyThoseThatRun() {
        XCTAssertEqual(ScanPipeline.of("photos", ocr: false).stages, [.read, .saved], "OCR off: no Text stage")
        XCTAssertEqual(ScanPipeline.of("files").stages, [.found, .read, .saved])
        XCTAssertEqual(ScanPipeline.of("mail", mailHost: "imap.gmail.com").stages, [.fetch, .read, .saved])
        XCTAssertEqual(ScanPipeline.of("contacts").stages, [.read, .saved])
        for source in ["photos", "files", "calendar", "contacts", "mail", "sample"] {
            let p = ScanPipeline.of(source)
            XCTAssertFalse(p.stages.map(\.label).joined().localizedCaseInsensitiveContains("laya"))
            XCTAssertEqual(p.online == nil, source != "mail", "only Mail is Online")
        }
        XCTAssertTrue(ScanPipeline.of("mail", mailHost: "imap.gmail.com").online?.contains("imap.gmail.com") == true)
        XCTAssertTrue(ScanPipeline.of("mail", gmail: true).online?.contains("Gmail API") == true)
    }

    func testRateAndEta() {
        var s = ScanStream(pipeline: photos)
        for i in 1...10 {
            s.ingest(ScanEvent(name: "IMG_\(i)", done: i, total: 30), now: Double(i) * 0.5)
        }
        let snap = s.snapshot(now: 5)
        // A 4 s window: the base is the last sample older than it (t=0.5, 1 done), so 9 items over 4.5 s.
        XCTAssertEqual(snap.rate ?? 0, 2.0, accuracy: 0.01)
        XCTAssertEqual(snap.eta ?? 0, 10, accuracy: 0.1)
        XCTAssertTrue(snap.telemetry.hasPrefix("10 of 30 · 2.0/s · about 10 s left"), snap.telemetry)
    }

    func testRateIsWindowedAndNilAtFirst() {
        var s = ScanStream(pipeline: photos)
        s.ingest(ScanEvent(name: "a", done: 1, total: 100), now: 0)
        XCTAssertNil(s.snapshot(now: 0.1).rate, "no rate from a tenth of a second")
        // Slow start, then fast: the rate follows the last few seconds, not the whole run.
        for i in 2...5 { s.ingest(ScanEvent(name: "a", done: i, total: 100), now: Double(i) * 2) }
        for i in 6...45 { s.ingest(ScanEvent(name: "a", done: i, total: 100), now: 10 + Double(i - 5) * 0.1) }
        let r = s.snapshot(now: 14).rate ?? 0
        XCTAssertGreaterThan(r, 2 * 45 / 14, "the window forgets most of the slow start (whole-run average 3.2/s)")
    }

    func testScannerProgressBecomesItemsWithNames() {
        var s = ScanStream(pipeline: .of("files"))
        s.scanned(seen: 1, total: 3, itemsRead: 1, skippedNow: 0, current: "/x/y/tax-2026.pdf", now: 0)
        s.scanned(seen: 1, total: 3, itemsRead: 1, skippedNow: 0, current: "/x/y/tax-2026.pdf", now: 0.1)
        s.scanned(seen: 3, total: 3, itemsRead: 2, skippedNow: 1, current: "/x/y/notes.txt", now: 0.2)
        let snap = s.snapshot(now: 0.2)
        XCTAssertEqual(snap.read, 2)
        XCTAssertEqual(snap.skipped, 1)
        XCTAssertEqual(snap.stages.first { $0.kind == .found }?.count, 3)
        XCTAssertEqual(snap.recent.map(\.name), ["notes.txt", "tax-2026.pdf"], "one entry per file, the file name only")
        XCTAssertEqual(snap.ledger.count, 2, "sources without text list every name")
    }

    func testSnippetsAndNamesAreMaskedWithThePrivacyMasks() {
        var s = ScanStream(pipeline: photos)
        let raw = "VISA 4539 1488 0343 6467\nsalma.hany@example.com\npassword = hunter2-sunrise\nAccount 88213904417"
        s.ingest(ScanEvent(name: "card 4539148803436467.jpg", textFound: true, text: raw, done: 1, total: 1), now: 0)
        let snap = s.snapshot(now: 0)
        let shown = [snap.ledger.first?.snippet ?? "", snap.ledger.first?.name ?? "", snap.recent.first?.snippet ?? ""].joined(separator: " ")
        for secret in ["4539 1488", "6467", "salma.hany@example.com", "hunter2-sunrise", "88213904417", "4539148803436467"] {
            XCTAssertFalse(shown.contains(secret), "\(secret) reached the screen: \(shown)")
        }
        XCTAssertTrue(snap.ledger.first?.snippet.contains("VISA") == true, "the words around it stay")
        XCTAssertLessThanOrEqual(snap.ledger.first?.snippet.count ?? 0, 64)
    }

    func testMaskHidesNumbersCutAtTheEdgeAndContactsShowInitialsOnly() {
        let long = String(repeating: "word ", count: 12) + "4539 1488 0343 6467"
        XCTAssertFalse(ScanMask.text(long, limit: 70).contains("4539"), "masked before truncation")
        XCTAssertEqual(ScanMask.text("  many   spaces\n\nhere ", limit: 40), "many spaces here")
        XCTAssertEqual(ScanMask.initials("Salma Hany"), "S.H.")
        XCTAssertEqual(ScanMask.initials(""), "—")
        var s = ScanStream(pipeline: .of("contacts"))
        s.ingest(ScanEvent(name: "Salma Hany", done: 1, total: 1), now: 0)
        XCTAssertEqual(s.snapshot(now: 0).recent.first?.name, "S.H.")
    }

    func testFinishLandsSavedAndWritesTheSummary() {
        var s = ScanStream(pipeline: photos)
        s.ingest(ScanEvent(name: "a", textFound: true, text: "hi", done: 1, total: 3), now: 0)
        s.ingest(ScanEvent(name: "b", done: 2, total: 3), now: 0.2)
        s.ingest(ScanEvent(name: "c", read: false, done: 3, total: 3), now: 0.4)
        s.finish(saved: 1204, now: 1)
        let snap = s.snapshot(now: 1)
        XCTAssertEqual(snap.phase, .finished)
        XCTAssertEqual(snap.stages.last?.count, 1204)
        XCTAssertEqual(snap.stages.last?.pulseAt, 1)
        XCTAssertEqual(snap.summary, "2 photos · 1 with text · 1 skipped · 0 bytes out")
        XCTAssertNil(snap.eta)
        XCTAssertEqual(ScanStream.summary(pipeline: photos, read: 0, withText: 0, skipped: 0), "No new photos · 0 bytes out")
        XCTAssertEqual(ScanStream.summary(pipeline: .of("mail", mailHost: "h"), read: 1, withText: 0, skipped: 0), "1 message · fetched read-only")
    }

    func testAccessibilitySummary() {
        var s = ScanStream(pipeline: photos)
        for i in 1...312 { s.ingest(ScanEvent(name: "p", textFound: i <= 48, done: i, total: 1204), now: Double(i) * 0.01) }
        let label = s.snapshot(now: 3.2).accessibilitySummary
        XCTAssertTrue(label.hasPrefix("Photos: 312 of 1,204 read, 48 with text"), label)
    }

    func testThrottleCoalescesToTwelveAHertz() {
        var t = ScanThrottle(hz: 12)
        var fires: [Double] = []
        var pending: Double?
        // 1,000 changes over one second, flushing whenever a scheduled flush comes due.
        for i in 0..<1000 {
            let now = Double(i) / 1000
            if let due = pending, now >= due { t.fired(now: due); fires.append(due); pending = nil }
            if let d = t.request(now: now) { pending = now + d }
        }
        if let due = pending { t.fired(now: due); fires.append(due) }
        XCTAssertLessThanOrEqual(fires.count, 13)
        XCTAssertGreaterThanOrEqual(fires.count, 11)
        for (a, b) in zip(fires, fires.dropFirst()) { XCTAssertGreaterThanOrEqual(b - a, 1 / 12 - 1e-9) }
        XCTAssertEqual(ScanThrottle(hz: 12).interval, 1 / 12, accuracy: 1e-9)
    }

    @MainActor
    func testFeedPublishesAtMostTwelveTimesASecondAndKeepsEveryCount() async throws {
        let live = LiveScan(pipeline: photos)
        let feed = live.feed
        let q = DispatchQueue(label: "producer")
        let done = expectation(description: "fed")
        q.async {
            for i in 1...600 {
                feed.item(ScanEvent(name: "IMG_\(i)", textFound: i % 3 == 0, text: i % 3 == 0 ? "text \(i)" : "", done: i, total: 600))
                if i % 50 == 0 { usleep(20_000) }   // 600 items over ~0.25 s
            }
            done.fulfill()
        }
        await fulfillment(of: [done], timeout: 5)
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(live.snapshot.read, 600, "the trailing flush lands the last count")
        XCTAssertEqual(live.snapshot.withText, 200)
        XCTAssertLessThanOrEqual(live.publishes, 8, "~0.55 s at 12 Hz, not 600 redraws")
        feed.finish(saved: 600)
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(live.snapshot.phase, .finished)
        XCTAssertTrue(live.finished)
    }

    func testPhotosProducerReportsEachPhotoWithItsTextAndName() async {
        let lib = FakePhotoLibrary()
        lib.status = .granted
        lib.assets = [PhotoAssetRecord(localId: "a", fileName: "IMG_1.JPG", created: nil, isScreenshot: false, pixelWidth: 1, pixelHeight: 1, hasLocation: false),
                      PhotoAssetRecord(localId: "b", fileName: "IMG_2.JPG", created: nil, isScreenshot: false, pixelWidth: 1, pixelHeight: 1, hasLocation: false)]
        lib.data = ["a": Data("receipt".utf8)]     // b is iCloud only
        var producer = PhotosProducer(library: lib, recognizer: FakeRecognizer(text: [Data("receipt".utf8): "RECEIPT\nTotal 12.40"]))
        let box = EventBox()
        producer.onRead = { box.add($0) }
        let out = await producer.scan(cached: nil, state: [:])
        XCTAssertEqual(out.result.items.first?.text.contains("Total 12.40"), true, "the stored text is unchanged")
        XCTAssertEqual(box.events.map(\.name), ["IMG_1.JPG", "IMG_2.JPG"])
        XCTAssertEqual(box.events.map(\.read), [true, false])
        XCTAssertEqual(box.events.first?.textFound, true)
        XCTAssertEqual(box.events.first?.text, "RECEIPT\nTotal 12.40")
        XCTAssertEqual(out.state[PhotosProducer.remainingKey], "0")
    }

    func testMailSubjectIsReadFromTheHeader() {
        let raw = "From: a@b.c\r\nSubject: =?UTF-8?B?WW91ciByZWNlaXB0?=\r\n and more\r\n\r\nSubject: not this"
        XCTAssertEqual(MailSubject.subject(in: raw), "Your receipt and more")
        XCTAssertEqual(MailSubject.subject(in: "From: a\r\n\r\nbody"), "(no subject)")
    }
}

private final class EventBox: @unchecked Sendable {
    private let lock = NSLock()
    private var list: [ScanEvent] = []
    func add(_ e: ScanEvent) { lock.lock(); list.append(e); lock.unlock() }
    var events: [ScanEvent] { lock.lock(); defer { lock.unlock() }; return list }
}

extension ScanStreamTests {
    func testListingSetsTheTotalAndTheScannersDoneReportAddsNoItem() {
        var s = ScanStream(pipeline: .of("photos"))
        s.listed(total: 16)
        let snap = s.snapshot(now: 0)
        XCTAssertEqual(snap.total, 16)
        XCTAssertEqual(snap.status, "Reading 16 photos with on-device OCR")
        var f = ScanStream(pipeline: .of("sample"))
        f.scanned(seen: 2, total: 2, itemsRead: 2, skippedNow: 0, current: "/a/b.pdf", now: 0)
        f.scanned(seen: 2, total: 2, itemsRead: 2, skippedNow: 0, current: "done", now: 0.1)
        XCTAssertEqual(f.snapshot(now: 0.1).recent.map(\.name), ["b.pdf"])
    }
}
