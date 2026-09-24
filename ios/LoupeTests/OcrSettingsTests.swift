import LoupeKit
import UIKit
import Vision
import XCTest
@testable import Loupe

/// Model settings' `features.scan.ocr` / `ocr_max_pages` (Loupe Station's keys) on the phone's readers.
final class OcrSettingsTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    /// Counts calls and answers every image with the same text.
    final class CountingRecognizer: TextRecognizing {
        var calls = 0
        let answer: String
        init(_ answer: String) { self.answer = answer }
        func recognize(_ data: Data) -> String { calls += 1; return answer }
    }

    private func sample(_ relative: String) throws -> String {
        let root = try XCTUnwrap(SourcesService.bundledSample(), "sample folder missing from the app bundle")
        return root.appendingPathComponent(relative).path
    }

    /// A scanned PDF of [pages] pages with no text layer.
    private func scannedPdf(pages: Int) throws -> String {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("scan-\(UUID().uuidString).pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 200, height: 200))
        let data = renderer.pdfData { ctx in
            for _ in 0..<pages {
                ctx.beginPage()
                UIColor.gray.setFill()
                UIRectFill(CGRect(x: 20, y: 20, width: 160, height: 40))
            }
        }
        try data.write(to: url)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url.path
    }

    func testDefaultsMatchStation() {
        let d = EngineSettings.companion.DEFAULTS
        XCTAssertTrue(d.ocr)
        XCTAssertEqual(d.ocrMaxPages, 3)
        XCTAssertEqual(d.biasCorrection, "off")
        XCTAssertEqual(d.effectiveBiasCorrection, "off")
    }

    func testAScannedPdfIsReadByOcrUpToMaxPages() throws {
        let path = try scannedPdf(pages: 5)
        let rec = CountingRecognizer("Invoice 2051 فاتورة")
        let pdf = AppleExtractors(timeZone: utc, ocr: { OcrPolicy(enabled: true, maxPages: 3) }, recognizer: rec).readPdf(path: path)
        XCTAssertEqual(rec.calls, 3, "three of five pages")
        XCTAssertEqual(pdf.pages, 5)
        XCTAssertTrue(pdf.text.contains("Invoice 2051 فاتورة"), pdf.text)
    }

    func testOcrOffLeavesAScanEmpty() throws {
        let path = try scannedPdf(pages: 2)
        let rec = CountingRecognizer("should not appear")
        let pdf = AppleExtractors(timeZone: utc, ocr: { OcrPolicy(enabled: false, maxPages: 3) }, recognizer: rec).readPdf(path: path)
        XCTAssertEqual(rec.calls, 0)
        XCTAssertFalse(pdf.text.contains("should not appear"))
    }

    func testAPdfWithATextLayerIsNotOcred() throws {
        let rec = CountingRecognizer("ocr")
        let pdf = AppleExtractors(timeZone: utc, ocr: { OcrPolicy(enabled: true, maxPages: 3) }, recognizer: rec)
            .readPdf(path: try sample("documents/insurance/home-insurance-renewal-2026.pdf"))
        XCTAssertEqual(rec.calls, 0)
        XCTAssertTrue(pdf.text.contains("Annual premium"))
    }

    func testTheSampleScanStaysWithoutOcr() throws {
        let rec = CountingRecognizer("ocr")
        _ = AppleExtractors(timeZone: utc, recognizer: rec).readPdf(path: try sample("documents/downloads/scanned-letter.pdf"))
        XCTAssertEqual(rec.calls, 0, "the default (sample scan) keeps desktop parity: no OCR")
    }

    func testPhotosOcrOffReadsMetadataOnly() async {
        let photos = FakePhotoLibrary()
        photos.assets = [PhotoAssetRecord(localId: "A", fileName: "IMG_1.PNG", created: nil, isScreenshot: false,
                                          pixelWidth: 10, pixelHeight: 10, hasLocation: false)]
        photos.data["A"] = Data("a".utf8)
        var producer = PhotosProducer(library: photos, recognizer: FakeRecognizer(text: [Data("a".utf8): "RECEIPT Total 12.40 Fresh Basket Market"]))
        producer.ocr = { OcrPolicy(enabled: false, maxPages: 3) }
        let off = await producer.scan(cached: nil, state: [:])
        XCTAssertFalse(off.result.items[0].text.contains("RECEIPT"))
        producer.ocr = { OcrPolicy(enabled: true, maxPages: 3) }
        let on = await producer.scan(cached: nil, state: [:])
        XCTAssertTrue(on.result.items[0].text.contains("RECEIPT"))
    }

    /// Real Vision on a rendered Arabic + Latin image: `.accurate` and automatic language detection.
    func testVisionReadsMixedArabicAndLatin() throws {
        let size = CGSize(width: 900, height: 260)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 56), .foregroundColor: UIColor.black]
            ("INVOICE 2051" as NSString).draw(at: CGPoint(x: 30, y: 30), withAttributes: attrs)
            ("فاتورة الكهرباء" as NSString).draw(at: CGPoint(x: 30, y: 140), withAttributes: attrs)
        }
        let text = VisionTextRecognizer().recognize(try XCTUnwrap(image.pngData()))
        XCTAssertTrue(text.uppercased().contains("INVOICE"), "Latin line: \(text)")
        XCTAssertTrue(text.unicodeScalars.contains { (0x0600...0x06FF).contains($0.value) }, "Arabic line: \(text)")
    }
}
