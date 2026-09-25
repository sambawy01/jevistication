import Foundation
import ImageIO
import LoupeKit
import Photos
import PhotosUI
import UIKit
import Vision

/// One photo as PhotoKit lists it: metadata only, no pixels.
struct PhotoAssetRecord: Equatable {
    let localId: String
    let fileName: String
    let created: Date?
    let isScreenshot: Bool
    let pixelWidth: Int
    let pixelHeight: Int
    let hasLocation: Bool
}

/// What changed since a PhotoKit persistent change token.
struct PhotoChanges: Equatable {
    var updated: Set<String>
    var deleted: Set<String>
}

/// The slice of PhotoKit the Photos source uses, behind a protocol so tests use a fake library.
protocol PhotoLibraryReading: AnyObject {
    func authorization() -> PhonePermission
    func requestAuthorization() async -> PhonePermission
    /// Every image asset the app can see (all of them, or the user's limited selection).
    func allAssets() -> [PhotoAssetRecord]
    /// Changes since [token], or nil when the token is unknown or expired (then nothing is assumed).
    func changes(since token: Data) -> PhotoChanges?
    func currentToken() -> Data?
    /// The original image bytes (network access off: iCloud-only originals are not downloaded).
    func imageData(localId: String) async -> Data?
}

/// On-device text recognition, behind a protocol so tests do not run Vision.
protocol TextRecognizing {
    func recognize(_ data: Data) -> String
}

/// Vision `VNRecognizeTextRequest`: `.accurate`, language correction on, on-device only.
/// `.accurate` is required for Arabic (`ar-SA` is not read at `.fast`), and
/// `automaticallyDetectsLanguage` (iOS 16+) lets one image mix Arabic and Latin script.
struct VisionTextRecognizer: TextRecognizing {
    func recognize(_ data: Data) -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        // Vision runs on the device; there is no server-side recognition in this API.
        let handler = VNImageRequestHandler(data: data, options: [:])
        do { try handler.perform([request]) } catch { return "" }
        let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        return lines.joined(separator: "\n")
    }
}

/// PhotoKit, limited-library aware. Reads never download from iCloud (`isNetworkAccessAllowed = false`).
final class PhotoKitLibrary: PhotoLibraryReading {
    private static func map(_ s: PHAuthorizationStatus) -> PhonePermission {
        switch s {
        case .authorized: return .granted
        case .limited: return .limited
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notAsked
        @unknown default: return .denied
        }
    }

    func authorization() -> PhonePermission { Self.map(PHPhotoLibrary.authorizationStatus(for: .readWrite)) }

    func requestAuthorization() async -> PhonePermission {
        Self.map(await PHPhotoLibrary.requestAuthorization(for: .readWrite))
    }

    func allAssets() -> [PhotoAssetRecord] {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: .image, options: options)
        var out: [PhotoAssetRecord] = []
        out.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            let name = PHAssetResource.assetResources(for: asset).first?.originalFilename ?? "Photo"
            out.append(PhotoAssetRecord(localId: asset.localIdentifier, fileName: name, created: asset.creationDate,
                                        isScreenshot: asset.mediaSubtypes.contains(.photoScreenshot),
                                        pixelWidth: asset.pixelWidth, pixelHeight: asset.pixelHeight,
                                        hasLocation: asset.location != nil))
        }
        return out
    }

    func changes(since token: Data) -> PhotoChanges? {
        guard let t = try? NSKeyedUnarchiver.unarchivedObject(ofClass: PHPersistentChangeToken.self, from: token),
              let changes = try? PHPhotoLibrary.shared().fetchPersistentChanges(since: t) else { return nil }
        var out = PhotoChanges(updated: [], deleted: [])
        for change in changes {
            guard let details = try? change.changeDetails(for: .asset) else { continue }
            out.updated.formUnion(details.insertedLocalIdentifiers)
            out.updated.formUnion(details.updatedLocalIdentifiers)
            out.deleted.formUnion(details.deletedLocalIdentifiers)
        }
        out.updated.subtract(out.deleted)
        return out
    }

    func currentToken() -> Data? {
        try? NSKeyedArchiver.archivedData(withRootObject: PHPhotoLibrary.shared().currentChangeToken, requiringSecureCoding: true)
    }

    func imageData(localId: String) async -> Data? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localId], options: nil).firstObject else { return nil }
        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = false
        options.deliveryMode = .highQualityFormat
        options.isSynchronous = false
        return await withCheckedContinuation { cont in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                cont.resume(returning: data)
            }
        }
    }

    /// Shows iOS's picker for the limited selection ("Choose more photos").
    @MainActor
    static func presentLimitedPicker() {
        guard let root = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first?
            .windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        var top = root
        while let next = top.presentedViewController { top = next }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top)
    }
}

/// The Photos producer: incremental by design. PhotoKit's metadata listing is cheap, OCR is not, so
/// each scan reads only photos it has not read (newest first, up to `maxPerScan`), photos the change
/// token says were edited, and drops photos that are gone. "Scan again" continues where it stopped.
struct PhotosProducer {
    static let tokenKey = "changeToken"
    let library: PhotoLibraryReading
    let recognizer: TextRecognizing
    var maxPerScan = 300
    var extractors = AppleExtractors()
    /// `features.scan.ocr`: off reads a photo's metadata only. Default on (what the app did before).
    var ocr: () -> OcrPolicy = { OcrPolicy(enabled: true, maxPages: 1) }
    /// Per photo: (done, total, read, text found by OCR). Drives the Sources live run.
    var onItem: @Sendable (Int, Int, Bool, Bool) -> Void = { _, _, _, _ in }

    func scan(cached: ScanResult?, state: [String: String], cancelled: () -> Bool = { false }) async -> PhoneScanOutput {
        let assets = library.allAssets()
        let visible = Set(assets.map(\.localId))
        let prefix = "photos:"
        var kept = (cached?.items ?? []).filter { visible.contains(String($0.id.dropFirst(prefix.count))) }
        let have = Set(kept.map { String($0.id.dropFirst(prefix.count)) })
        var edited = Set<String>()
        if let t = state[Self.tokenKey], let token = Data(base64Encoded: t), let changes = library.changes(since: token) {
            edited = changes.updated.intersection(have)
        }
        let pending = assets.filter { !have.contains($0.localId) || edited.contains($0.localId) }
        let batch = pending.prefix(maxPerScan)
        let builder = PhoneItems()
        let ocrOn = ocr().enabled
        var fresh: [SourceItem] = []
        var skipped: [Skipped] = []
        var processed = 0
        for asset in batch {
            if cancelled() { break }
            processed += 1
            guard let data = await library.imageData(localId: asset.localId) else {
                skipped.append(Skipped(path: asset.fileName, reason: "not on this iPhone: the original is in iCloud only (Loupe does not download it)"))
                onItem(processed, batch.count, false, false)
                continue
            }
            let info = extractors.readImage(data: data)
            let text = ocrOn ? recognizer.recognize(data) : ""
            onItem(processed, batch.count, true, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            let created = asset.created.map { ISOStamp.local($0) }
            let dims = info.facts["dimensions"] ?? (asset.pixelWidth > 0 ? "\(asset.pixelWidth)x\(asset.pixelHeight)" : nil)
            fresh.append(builder.photo(localId: asset.localId, name: asset.fileName, ocrText: text, createdIso: created,
                                       takenIso: info.takenIso, dimensions: dims, camera: info.facts["camera"],
                                       hasLocation: asset.hasLocation || info.facts["location"] != nil,
                                       screenshot: asset.isScreenshot, sizeBytes: Int64(data.count)))
        }
        let freshIds = Set(fresh.map(\.id))
        kept.removeAll { freshIds.contains($0.id) }
        let remaining = pending.count - processed
        if remaining > 0 {
            skipped.append(Skipped(path: "Photos", reason: "not read yet: \(remaining) older photo\(remaining == 1 ? "" : "s") — scan again to continue"))
        }
        var out = PhoneScanOutput(result: .of(kept + fresh, skipped: skipped))
        if let token = library.currentToken() { out.state[Self.tokenKey] = token.base64EncodedString() }
        return out
    }
}
