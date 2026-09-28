import Combine
import XCTest
import LoupeKit
@testable import Loupe

/// Epic #7 child 2: the PDFKit and ImageIO readers on the bundled sample, the full sample scan
/// through LoupeKit's common scanner, and the cache later tabs read.
final class SourcesTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    private func sample(_ relative: String) throws -> String {
        let root = try XCTUnwrap(TestSample.root(), "sample folder missing from the test bundle")
        return root.appendingPathComponent(relative).path
    }

    func testPdfKitReadsTheTextLayerAndInfo() throws {
        let pdf = AppleExtractors(timeZone: utc).readPdf(path: try sample("documents/insurance/home-insurance-renewal-2026.pdf"))
        XCTAssertNil(pdf.error)
        XCTAssertEqual(pdf.pages, 1)
        XCTAssertTrue(pdf.text.contains("Annual premium: £553.50"), pdf.text)
        XCTAssertEqual(pdf.createdIso, "2026-10-01")
        XCTAssertEqual(pdf.producer, "Loupe sample generator")
    }

    func testPdfKitFindsNoTextInAScan() throws {
        let pdf = AppleExtractors(timeZone: utc).readPdf(path: try sample("documents/downloads/scanned-letter.pdf"))
        XCTAssertNil(pdf.error)
        XCTAssertEqual(pdf.pages, 1)
        XCTAssertLessThan(pdf.text.filter { $0.isLetter || $0.isNumber }.count, 12)
        XCTAssertEqual(pdf.createdIso, "2026-05-03")
    }

    func testPdfKitReportsADamagedPdf() throws {
        let bogus = FileManager.default.temporaryDirectory.appendingPathComponent("bogus-\(UUID().uuidString).pdf")
        try Data("%PDF-1.4 not really".utf8).write(to: bogus)
        defer { try? FileManager.default.removeItem(at: bogus) }
        XCTAssertNotNil(AppleExtractors().readPdf(path: bogus.path).error)
    }

    func testImageIOReadsDimensionsOnly() throws {
        let png = AppleExtractors(timeZone: utc).readImage(path: try sample("documents/photos/lisbon-sunset.png"))
        XCTAssertEqual(png.facts["dimensions"], "320x200")
        XCTAssertNil(png.takenIso)
        let jpg = AppleExtractors(timeZone: utc).readImage(path: try sample("documents/photos/IMG_2051.jpg"))
        XCTAssertEqual(jpg.facts["dimensions"], "320x240")
        XCTAssertNil(jpg.facts["location"])
    }

    func testImageIOReadsExifDateCameraAndGps() throws {
        // The sample's images carry no EXIF, so write one that does and read it back.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("exif-\(UUID().uuidString).jpg")
        defer { try? FileManager.default.removeItem(at: url) }
        let ctx = CGContext(data: nil, width: 8, height: 6, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        let dest = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil))
        let props: [CFString: Any] = [
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:07:14 18:05:00"],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Sample", kCGImagePropertyTIFFModel: "Cam 1"],
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 38.7, kCGImagePropertyGPSLatitudeRef: "N",
                                            kCGImagePropertyGPSLongitude: 9.1, kCGImagePropertyGPSLongitudeRef: "W"],
        ]
        CGImageDestinationAddImage(dest, ctx.makeImage()!, props as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        let info = AppleExtractors(timeZone: utc).readImage(path: url.path)
        XCTAssertEqual(info.facts["dimensions"], "8x6")
        XCTAssertEqual(info.facts["camera"], "Sample Cam 1")
        XCTAssertEqual(info.takenIso, "2026-07-14")
        XCTAssertEqual(info.facts["location"], "GPS position recorded")
    }

    /// The whole sample through the common scanner with PDFKit/ImageIO: the desktop's counts
    /// (48 items: 24 emails, 3 skipped, 1 duplicate), stable ids, PDF text via PDFKit.
    func testSampleScanMatchesTheDesktopShape() throws {
        let root = try XCTUnwrap(TestSample.root())
        let scanner = SourceScanner(extractors: AppleExtractors(timeZone: utc), zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                    limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
        let result = try scanner.scan(sources: SourcesService.sampleRoots(root), observer: NoObserver())
        XCTAssertEqual(result.items.count, 48)
        XCTAssertEqual(result.items.filter { $0.kind == .email }.count, 24)
        XCTAssertEqual(result.skipped.count, 3)
        XCTAssertEqual(result.duplicates, 1)
        let renewal = try XCTUnwrap(result.items.first { $0.id == "sample:documents/insurance/home-insurance-renewal-2026.pdf" })
        XCTAssertTrue(renewal.hasText)
        XCTAssertTrue(renewal.text.hasPrefix("File: home-insurance-renewal-2026.pdf\n\n"))
        XCTAssertEqual(renewal.dateIso, "2026-10-01")
        XCTAssertEqual(renewal.dateOrigin, .pdfInfo)
        let scan = try XCTUnwrap(result.items.first { $0.id.hasSuffix("scanned-letter.pdf") })
        XCTAssertFalse(scan.hasText)
        let mbox = result.items.filter { $0.id.hasPrefix("sample:mail/subscriptions-2026.mbox#") }
        XCTAssertEqual(mbox.count, 14)
    }

    /// Opening the app loads, it never scans (owner decision 2026-09-28): `start()` reads nothing, even with a source
    /// on and never read. A scan runs when asked (a run's sources stage); the cache then survives a relaunch.
    @MainActor
    func testStartScansNothingAndAScanIsCachedForTheNextLaunch() async throws {
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("SourcesTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: home) }
        let service = SourcesService(home: home, sampleRoot: TestSample.root())
        XCTAssertTrue(service.sampleEnabled, "the fixture source is on")
        XCTAssertNil(service.sampleScan)
        service.start()
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertFalse(service.scanning, "nothing scans at launch")
        XCTAssertTrue(service.liveScans.isEmpty)
        XCTAssertNil(service.sampleScan)
        XCTAssertTrue(service.items().isEmpty)

        await service.scanSample()
        XCTAssertEqual(service.sampleScan?.itemCount, 48)
        XCTAssertEqual(service.items().count, 48)

        // A fresh service over the same home (a relaunch) reads the cache: no rescan.
        let again = SourcesService(home: home, sampleRoot: TestSample.root())
        XCTAssertEqual(again.sampleScan?.itemCount, 48)
        again.start()
        XCTAssertFalse(again.scanning)
        again.setSampleEnabled(false)
        XCTAssertTrue(again.items().isEmpty)
        XCTAssertFalse(SourcesService(home: home, sampleRoot: nil).sampleEnabled, "no sample outside tests")
        XCTAssertTrue(SourcesService(home: home, sampleRoot: nil).items().isEmpty, "and none of its cached items")
    }

    /// Cancel mid-scan (2026-09-28): the scanner stops between files; what it read replaces its items and the last
    /// scan's other items stay, so the cache is never cut short, and it still reads back.
    @MainActor
    func testACancelledScanKeepsWhatItReadAndWhatItDidNotReach() async throws {
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("SourcesTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: home) }
        let service = SourcesService(home: home, sampleRoot: TestSample.root())
        await service.scanSample()
        let full = try XCTUnwrap(service.sampleScan)
        XCTAssertEqual(full.itemCount, 48)

        // Cancelled a few files in (seen through the live scan, as the run panel sees it).
        let cancel = RunCancel()
        var sub: Any?
        let watch = service.$liveScans.sink { scans in
            guard let live = scans["sample"] else { return }
            sub = live.$snapshot.sink { if $0.done >= 5 { cancel.cancel() } }
        }
        await service.scanSample(cancel: cancel)
        watch.cancel()
        _ = sub
        XCTAssertTrue(cancel.isCancelled)
        let after = try XCTUnwrap(service.sampleScan)
        XCTAssertEqual(Set(after.result.items.map(\.id)), Set(full.result.items.map(\.id)), "every item is still there")
        XCTAssertEqual(after.result.items.count, 48, "none twice")
        XCTAssertGreaterThan(after.scannedAtEpochMillis, full.scannedAtEpochMillis)
        XCTAssertEqual(SourcesService(home: home, sampleRoot: TestSample.root()).sampleScan?.itemCount, 48, "the cache reads back")

        // Cancelled before the first file: nothing is lost either.
        let early = RunCancel()
        early.cancel()
        await service.scanSample(cancel: early)
        XCTAssertEqual(service.sampleScan?.itemCount, 48)
    }

    func testTheMergeOfACancelledScan() {
        func item(_ id: String, _ text: String) -> SourceItem {
            SourceItem(id: id, sourceId: "files", kind: .text, path: "/f/\(id)", messageIndex: nil, name: id, text: text, hasText: true,
                       textTruncated: false, sizeBytes: 1, contentHash: "h-\(id)-\(text)", mime: "text/plain", date: nil, dateOrigin: nil,
                       email: nil, facts: [:], duplicateOf: nil)
        }
        let previous = ScanResult.of([item("a", "old"), item("b", "old"), item("c", "old")], skipped: [Skipped(path: "/f/x", reason: "old")])
        let partial = ScanResult.of([item("b", "new"), item("d", "new")], skipped: [Skipped(path: "/f/y", reason: "new")])
        let merged = SourceMerge.keepUnreached(partial: partial, previous: previous)
        XCTAssertEqual(merged.items.map(\.id), ["b", "d", "a", "c"])
        XCTAssertEqual(merged.items.first { $0.id == "b" }?.text, "new", "what was read replaces the old item")
        XCTAssertEqual(Set(merged.skipped.map(\.path)), ["/f/x", "/f/y"])
        XCTAssertEqual(SourceMerge.keepUnreached(partial: partial, previous: nil).items.map(\.id), ["b", "d"])
    }

    private final class NoObserver: NSObject, ScanObserver {
        func onProgress(progress: ScanProgress) {}
        func isCancelled() -> Bool { false }
    }
}
