import Foundation
import LoupeKit

/// A file or folder the user picked, kept as a security-scoped bookmark so Loupe can read it again
/// after a relaunch without asking. Only the bookmark is stored; nothing is copied.
struct PickedLocation: Codable, Equatable, Identifiable {
    let id: String
    var name: String
    var isFolder: Bool
    var bookmark: Data
}

/// Resolving a bookmark, behind a protocol so tests do not need the Files app.
protocol BookmarkResolving {
    func makeBookmark(for url: URL) throws -> Data
    /// The URL and whether the bookmark is stale (it still resolves, but should be re-made).
    func resolve(_ bookmark: Data) throws -> (url: URL, stale: Bool)
}

struct SecurityScopedBookmarks: BookmarkResolving {
    func makeBookmark(for url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try url.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    func resolve(_ bookmark: Data) throws -> (url: URL, stale: Bool) {
        var stale = false
        let url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        return (url, stale)
    }
}

/// The picked locations, persisted as JSON under Application Support (complete file protection).
final class BookmarkStore {
    private let url: URL
    let resolver: BookmarkResolving

    init(home: URL, resolver: BookmarkResolving = SecurityScopedBookmarks()) {
        url = home.appendingPathComponent("sources/bookmarks.json")
        self.resolver = resolver
    }

    func all() -> [PickedLocation] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([PickedLocation].self, from: data)) ?? []
    }

    private func write(_ list: [PickedLocation]) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(list).write(to: url, options: [.atomic, .completeFileProtection])
    }

    /// Adds [urls] (from the document picker); a location already present is replaced, not duplicated.
    @discardableResult
    func add(_ urls: [URL]) throws -> [PickedLocation] {
        var list = all()
        for u in urls {
            let isFolder = (try? u.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? u.hasDirectoryPath
            let bookmark = try resolver.makeBookmark(for: u)
            let id = Self.stableId(u)
            list.removeAll { $0.id == id }
            list.append(PickedLocation(id: id, name: u.lastPathComponent, isFolder: isFolder, bookmark: bookmark))
        }
        try write(list)
        return list
    }

    func remove(id: String) throws {
        try write(all().filter { $0.id != id })
    }

    /// Re-makes a stale bookmark after it resolved.
    func refresh(id: String, url: URL) {
        var list = all()
        guard let i = list.firstIndex(where: { $0.id == id }), let fresh = try? resolver.makeBookmark(for: url) else { return }
        list[i].bookmark = fresh
        try? write(list)
    }

    /// A short id from the path, so item ids survive relaunches and do not carry the container path.
    static func stableId(_ url: URL) -> String {
        let bytes = Array(url.standardizedFileURL.path.utf8)
        var h: UInt64 = 0xcbf29ce484222325
        for b in bytes { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return String(h, radix: 36)
    }
}

/// The Files producer: every picked location read by the common scanner, inside its security scope.
/// Items are keyed `files:<location id>/<relative path>`, so a rescan on open replaces them in place.
struct FilesProducer {
    let store: BookmarkStore
    var extractors = AppleExtractors.live()

    func scan() throws -> PhoneScanOutput {
        var roots: [SourceRoot] = []
        var scoped: [URL] = []
        var unavailable: [Skipped] = []
        var tmpFiles: [URL] = []
        defer {
            scoped.forEach { $0.stopAccessingSecurityScopedResource() }
            tmpFiles.forEach { try? FileManager.default.removeItem(at: $0.deletingLastPathComponent()) }
        }
        for loc in store.all() {
            guard let (url, stale) = try? store.resolver.resolve(loc.bookmark) else {
                unavailable.append(Skipped(path: loc.name, reason: "cannot be found any more — it was moved, deleted, or its app removed it. Remove it and pick it again."))
                continue
            }
            if url.startAccessingSecurityScopedResource() { scoped.append(url) }
            if stale { store.refresh(id: loc.id, url: url) }
            if loc.isFolder {
                roots.append(SourceRoot(id: PhoneSourceIds.shared.FILES, type: .folder, path: url.path, idPrefix: "files:\(loc.id)/"))
            } else {
                // The scanner reads folders; a single picked file is read as a one-file folder. It is
                // copied into a temporary folder for the scan and the copy removed afterwards.
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("loupe-pick-\(loc.id)-\(UUID().uuidString)")
                let target = dir.appendingPathComponent(url.lastPathComponent)
                do {
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: url, to: target)
                    tmpFiles.append(target)
                    roots.append(SourceRoot(id: PhoneSourceIds.shared.FILES, type: .folder, path: dir.path, idPrefix: "files:\(loc.id)/"))
                } catch {
                    unavailable.append(Skipped(path: loc.name, reason: "could not be opened: \(error.localizedDescription)"))
                }
            }
        }
        let result = try SourceScanner(extractors: extractors).scan(sources: roots, observer: NullScanObserver())
        return PhoneScanOutput(result: .of(result.items, skipped: result.skipped, unavailable: result.unavailable + unavailable))
    }
}

extension SharedInbox {
    /// Everything in the inbox, read by the common scanner as the `shared` source.
    static func scan(folder: URL, extractors: AppleExtractors = AppleExtractors.live()) throws -> PhoneScanOutput {
        let root = SourceRoot(id: PhoneSourceIds.shared.SHARED, type: .folder, path: folder.path, idPrefix: "shared:")
        let result = try SourceScanner(extractors: extractors).scan(sources: [root], observer: NullScanObserver())
        return PhoneScanOutput(result: result)
    }
}

final class NullScanObserver: NSObject, ScanObserver {
    func onProgress(progress: ScanProgress) {}
    func isCancelled() -> Bool { false }
}
