import XCTest
import LoupeKit
@testable import Loupe

/// Epic #7 child 2: the PDFKit and ImageIO readers on the bundled sample, the full sample scan
/// through LoupeKit's common scanner, and the cache later tabs read.
final class SourcesTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    private func sample(_ relative: String) throws -> String {
        let root = try XCTUnwrap(SourcesService.bundledSample(), "sample folder missing from the app bundle")
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
        let root = try XCTUnwrap(SourcesService.bundledSample())
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

    @MainActor
    func testServiceScansCachesAndSwitchesOff() throws {
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("SourcesTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: home) }
        let service = SourcesService(home: home, sampleRoot: SourcesService.bundledSample())
        XCTAssertTrue(service.sampleEnabled)
        service.start()
        XCTAssertTrue(service.scanning)
        let done = expectation(description: "scan finished")
        let poll = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { _ in
            MainActor.assumeIsolated { if service.sampleScan != nil && !service.scanning { done.fulfill() } }
        }
        wait(for: [done], timeout: 30)
        poll.invalidate()
        XCTAssertEqual(service.sampleScan?.itemCount, 48)
        XCTAssertEqual(service.items().count, 48)

        // A fresh service over the same home reads the cache: no rescan.
        let again = SourcesService(home: home, sampleRoot: SourcesService.bundledSample())
        XCTAssertEqual(again.sampleScan?.itemCount, 48)
        again.setSampleEnabled(false)
        XCTAssertTrue(again.items().isEmpty)
        XCTAssertFalse(SourcesService(home: home, sampleRoot: nil).sampleEnabled)
    }

    private final class NoObserver: NSObject, ScanObserver {
        func onProgress(progress: ScanProgress) {}
        func isCancelled() -> Bool { false }
    }
}
