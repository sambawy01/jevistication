import Foundation

/// Check a link's recent checks (2026-09-26): the last 50 verdicts from Check a link and the share
/// sheet, on this phone only (App Group `protection/recent-checks.json`), clearable. A check keeps
/// the address without its query or fragment (`LinkVerdict.displayURL`), since those can hold
/// sign-in or reset tokens.
final class RecentChecksStore {
    static let maxEntries = 50

    let file: URL
    private let lockFile: URL

    init(dir: URL = ProtectionGroup.protectionDir()) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent("recent-checks.json")
        lockFile = dir.appendingPathComponent(".recent.lock")
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }()
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()

    private func read() -> [LinkVerdict] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? Self.decoder.decode([LinkVerdict].self, from: data)) ?? []
    }

    /// Newest first.
    func all() -> [LinkVerdict] { GroupFileLock.with(lockFile) { read() } }

    /// Adds a verdict at the top; an earlier check of the same address moves up instead of repeating.
    func add(_ v: LinkVerdict) {
        GroupFileLock.with(lockFile) {
            var list = read().filter { $0.displayURL != v.displayURL }
            list.insert(v, at: 0)
            if let data = try? Self.encoder.encode(Array(list.prefix(Self.maxEntries))) {
                try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
        }
    }

    func remove(_ id: UUID) {
        GroupFileLock.with(lockFile) {
            let list = read().filter { $0.id != id }
            if let data = try? Self.encoder.encode(list) { try? data.write(to: file, options: .atomic) }
        }
    }

    func clear() {
        GroupFileLock.with(lockFile) { try? FileManager.default.removeItem(at: file) }
    }
}
