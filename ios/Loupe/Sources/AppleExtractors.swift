import Foundation
import ImageIO
import LoupeKit
import PDFKit

/// The iPhone's platform readers for LoupeKit's common scanner (epic #7 child 2): PDF text layers
/// through PDFKit, image metadata (dimensions, camera, EXIF date, GPS presence) through ImageIO.
/// The JVM uses PDFBox and metadata-extractor for the same two calls. Pixels are never read and
/// there is no OCR here, as on the desktop. Called on the scan's background queue.
final class AppleExtractors: NSObject, PlatformExtractors {
    private let timeZone: TimeZone

    init(timeZone: TimeZone = .current) {
        self.timeZone = timeZone
    }

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
        return PdfInfo(text: (doc.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                       pages: Int32(doc.pageCount),
                       createdIso: created.map(isoDay),
                       producer: producer,
                       error: nil)
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
