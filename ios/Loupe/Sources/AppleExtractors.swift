import Foundation
import ImageIO
import LoupeKit
import PDFKit
import UIKit

/// Model settings' `features.scan.ocr` / `ocr_max_pages` (Loupe Station's keys), read when a file is read.
struct OcrPolicy: Equatable {
    var enabled: Bool
    var maxPages: Int

    /// No OCR: what the bundled sample scan uses, so its items stay identical to the desktop scanner's.
    static let off = OcrPolicy(enabled: false, maxPages: 0)

    init(enabled: Bool, maxPages: Int) {
        self.enabled = enabled
        self.maxPages = maxPages
    }

    init(_ settings: EngineSettings) {
        self.init(enabled: settings.ocr, maxPages: Int(settings.ocrMaxPages))
    }

    /// The settings now (thread-safe: the store is not main-actor bound).
    static func current() -> OcrPolicy { OcrPolicy(ModelSettingsService.sharedStore.current) }
}

/// The iPhone's platform readers for LoupeKit's common scanner (epic #7 child 2): PDF text layers
/// through PDFKit, image metadata (dimensions, camera, EXIF date, GPS presence) through ImageIO.
/// The JVM uses PDFBox and metadata-extractor for the same two calls. A scanned PDF (no text layer)
/// is read with on-device Vision OCR, up to `ocr_max_pages` pages, when [ocr] says so — as Loupe
/// Station does; the default is no OCR (the sample scan, parity with the desktop). Called on the
/// scan's background queue.
final class AppleExtractors: NSObject, PlatformExtractors {
    private let timeZone: TimeZone
    private let ocr: () -> OcrPolicy
    private let recognizer: TextRecognizing

    init(timeZone: TimeZone = .current, ocr: @escaping () -> OcrPolicy = { .off },
         recognizer: TextRecognizing = VisionTextRecognizer()) {
        self.timeZone = timeZone
        self.ocr = ocr
        self.recognizer = recognizer
    }

    /// The phone's sources (Files, Share inbox, Mail attachments): OCR as Model settings say.
    static func live() -> AppleExtractors { AppleExtractors(ocr: { OcrPolicy.current() }) }

    func readPdf(path: String) -> PdfInfo {
        guard let doc = PDFDocument(url: URL(fileURLWithPath: path)) else {
            return PdfInfo(text: "", pages: 0, createdIso: nil, producer: nil, error: "damaged PDF: PDFKit could not open it")
        }
        if doc.isLocked {
            return PdfInfo(text: "", pages: 0, createdIso: nil, producer: nil, error: "encrypted PDF: needs a password")
        }
        if doc.isEncrypted && !doc.allowsCopying {
            return PdfInfo(text: "", pages: 0, createdIso: nil, producer: nil, error: "encrypted PDF: text extraction not permitted")
        }
        let attributes = doc.documentAttributes
        let created = attributes?[PDFDocumentAttribute.creationDateAttribute] as? Date
        let producer = attributes?[PDFDocumentAttribute.producerAttribute] as? String
        var text = (doc.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if Self.looksScanned(text), doc.pageCount > 0 {
            let policy = ocr()
            if policy.enabled && policy.maxPages > 0 {
                let recognised = ocrPages(doc, maxPages: policy.maxPages)
                if !recognised.isEmpty { text = recognised }
            }
        }
        return PdfInfo(text: text,
                       pages: Int32(doc.pageCount),
                       createdIso: created.map(isoDay),
                       producer: producer,
                       error: nil)
    }

    /// Fewer than 12 letters or digits: PDFKit found no real text layer (a scan).
    static func looksScanned(_ text: String) -> Bool { text.filter { $0.isLetter || $0.isNumber }.count < 12 }

    /// The first [maxPages] pages rendered (about 2x, longest side at most 2,400 px) and read by Vision.
    private func ocrPages(_ doc: PDFDocument, maxPages: Int) -> String {
        var pages: [String] = []
        for i in 0..<min(doc.pageCount, maxPages) {
            guard let page = doc.page(at: i) else { continue }
            let bounds = page.bounds(for: .mediaBox)
            guard bounds.width > 0, bounds.height > 0 else { continue }
            let scale = min(2.0, 2_400 / max(bounds.width, bounds.height))
            let image = page.thumbnail(of: CGSize(width: bounds.width * scale, height: bounds.height * scale), for: .mediaBox)
            guard let png = image.pngData() else { continue }
            let t = recognizer.recognize(png).trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { pages.append(t) }
        }
        return pages.joined(separator: "\n\n")
    }

    func readImage(path: String) -> ImageInfo {
        readImage(source: CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil))
    }

    /// The same metadata from image bytes in memory: PhotoKit hands a photo over as data (child 7).
    func readImage(data: Data) -> ImageInfo {
        readImage(source: CGImageSourceCreateWithData(data as CFData, nil))
    }

    private func readImage(source: CGImageSource?) -> ImageInfo {
        guard let source,
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
            return ImageInfo(facts: ["metadata": "unreadable (ImageIO could not read it)"], takenIso: nil)
        }
        var facts: [(String, String)] = []
        if let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int {
            facts.append(("dimensions", "\(w)x\(h)"))
        }
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        let camera = [tiff?[kCGImagePropertyTIFFMake] as? String, tiff?[kCGImagePropertyTIFFModel] as? String]
            .compactMap { $0 }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        if !camera.isEmpty { facts.append(("camera", camera)) }
        // EXIF DateTimeOriginal is "yyyy:MM:dd HH:mm:ss" local to the camera, with no zone: the
        // date part is the day the photo was taken, as metadata-extractor reports it on the JVM.
        var taken: String?
        if let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
           let raw = exif[kCGImagePropertyExifDateTimeOriginal] as? String, raw.count >= 10 {
            let day = raw.prefix(10).replacingOccurrences(of: ":", with: "-")
            if day.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil { taken = day }
        }
        if let taken { facts.append(("taken", taken)) }
        if let gps = props[kCGImagePropertyGPSDictionary] as? [CFString: Any],
           gps[kCGImagePropertyGPSLatitude] != nil, gps[kCGImagePropertyGPSLongitude] != nil {
            facts.append(("location", "GPS position recorded"))
        }
        // Kotlin sees a Swift dictionary as an unordered map; the scanner keeps this key order
        // only for display, and the desktop's order is the same (dimensions, camera, taken, location).
        return ImageInfo(facts: Dictionary(uniqueKeysWithValues: facts), takenIso: taken)
    }

    private func isoDay(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
