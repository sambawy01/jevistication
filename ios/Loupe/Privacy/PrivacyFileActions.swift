import Foundation
import LoupeKit
import Photos

/// What the app may do to the thing behind a finding. Only files the app can reach (picked Files
/// locations, the Send to Loupe inbox) can be deleted or moved; a photo can be deleted through
/// PhotoKit (iOS asks the user); everything else — the sample, mail, calendar — is suggest-only.
enum PrivacyAccess: Equatable {
    case file(URL, scope: URL?)
    case photo(localId: String)
    case suggestOnly(String)
}

/// Finds the file behind an item id, behind a protocol so tests use a temp directory.
protocol PrivacyLocating {
    func access(for itemId: String) -> PrivacyAccess
}

/// The live locator: `files:<location>/<path>` through the saved bookmarks, `shared:<path>` in the
/// App Group inbox, `photos:<id>` through PhotoKit.
struct LivePrivacyLocator: PrivacyLocating {
    var bookmarks: BookmarkStore
    var inbox: () -> URL

    func access(for itemId: String) -> PrivacyAccess {
        if itemId.hasPrefix("photos:") { return .photo(localId: String(itemId.dropFirst("photos:".count))) }
        if itemId.hasPrefix("shared:") {
            return .file(inbox().appendingPathComponent(String(itemId.dropFirst("shared:".count))), scope: nil)
        }
        if itemId.hasPrefix("files:") {
            let rest = itemId.dropFirst("files:".count)
            guard let slash = rest.firstIndex(of: "/") else { return .suggestOnly("Loupe cannot find this file any more.") }
            let locId = String(rest[..<slash]), rel = String(rest[rest.index(after: slash)...])
            guard let loc = bookmarks.all().first(where: { $0.id == locId }),
                  let (url, _) = try? bookmarks.resolver.resolve(loc.bookmark) else {
                return .suggestOnly("The picked location is gone. Pick it again in Sources.")
            }
            return .file(loc.isFolder ? url.appendingPathComponent(rel) : url, scope: url)
        }
        if itemId.hasPrefix("sample:") { return .suggestOnly("Sample data is part of the app and cannot be changed.") }
        return .suggestOnly("Loupe only reads this source; change it in its own app.")
    }
}

/// A delete or move that can still be undone.
struct PrivacyUndo: Equatable {
    enum Kind: Equatable { case deleted, moved }
    let kind: Kind
    let original: URL
    let current: URL
    let scope: URL?
    let findingKey: String
}

enum PrivacyFileError: LocalizedError {
    case missing, exists(String)
    var errorDescription: String? {
        switch self {
        case .missing: return "The file is not there any more."
        case .exists(let n): return "Something named \(n) is already there."
        }
    }
}

/// Deletes and moves with undo. A delete first moves the file into Loupe's own holding folder so
/// Undo can put it back; `commit` (when the Undo offer goes, or on the next launch) removes it for good.
final class PrivacyFileActions {
    let holding: URL
    private let fm = FileManager.default

    init(home: URL) { holding = home.appendingPathComponent("privacy-held", isDirectory: true) }

    private func scoped<T>(_ scope: URL?, _ work: () throws -> T) rethrows -> T {
        let on = scope?.startAccessingSecurityScopedResource() ?? false
        defer { if on { scope?.stopAccessingSecurityScopedResource() } }
        return try work()
    }

    func delete(_ url: URL, scope: URL?, key: String) throws -> PrivacyUndo {
        try scoped(scope) {
            guard fm.fileExists(atPath: url.path) else { throw PrivacyFileError.missing }
            let dir = holding.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let held = dir.appendingPathComponent(url.lastPathComponent)
            try fm.moveItem(at: url, to: held)
            return PrivacyUndo(kind: .deleted, original: url, current: held, scope: scope, findingKey: key)
        }
    }

    func move(_ url: URL, scope: URL?, to folder: URL, key: String) throws -> PrivacyUndo {
        try scoped(scope) {
            let folderOn = folder.startAccessingSecurityScopedResource()
            defer { if folderOn { folder.stopAccessingSecurityScopedResource() } }
            guard fm.fileExists(atPath: url.path) else { throw PrivacyFileError.missing }
            let target = folder.appendingPathComponent(url.lastPathComponent)
            guard !fm.fileExists(atPath: target.path) else { throw PrivacyFileError.exists(url.lastPathComponent) }
            try fm.moveItem(at: url, to: target)
            return PrivacyUndo(kind: .moved, original: url, current: target, scope: folder, findingKey: key)
        }
    }

    func undo(_ u: PrivacyUndo, originalScope: URL?) throws {
        try scoped(originalScope) {
            try scoped(u.kind == .moved ? u.scope : nil) {
                guard !fm.fileExists(atPath: u.original.path) else { throw PrivacyFileError.exists(u.original.lastPathComponent) }
                try fm.moveItem(at: u.current, to: u.original)
                if u.kind == .deleted { try? fm.removeItem(at: u.current.deletingLastPathComponent()) }
            }
        }
    }

    /// Removes held (deleted) files for good.
    func commit() { try? fm.removeItem(at: holding) }
}

/// PhotoKit deletion: iOS shows its own confirmation, and the photo goes to Recently Deleted.
protocol PhotoDeleting {
    func delete(localId: String) async throws
}

struct PhotoKitDeleter: PhotoDeleting {
    func delete(localId: String) async throws {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localId], options: nil)
        guard assets.count > 0 else { throw PrivacyFileError.missing }
        try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }
    }
}
