import Foundation

/// What the user did about a warning.
enum SpottedAction: String, Codable {
    case wentBack, continued, unknown

    var title: String {
        switch self {
        case .wentBack: return "You went back"
        case .continued: return "You continued anyway"
        case .unknown: return "No choice recorded"
        }
    }
}

/// One suspicious or dangerous verdict in the Spotted log. **A website name only**: never a full
/// address, a path, a query or anything from the page.
struct SpottedEntry: Codable, Equatable, Identifiable {
    var id = UUID()
    var at: Date
    /// The website name as people read it (international letters decoded).
    var domain: String
    /// The website name in ASCII (punycode), the key for de-duplication and actions.
    var host: String
    var level: ProtectionLevel
    var score: Int
    /// The strongest reasons, at most three, as plain lines.
    var reasons: [String]
    var brand: String?
    var origin: ProtectionOrigin
    var action: SpottedAction = .unknown
    var seen = false
    /// How many times the same site was flagged in the same place within [SpottedLog.dedupeWindow].
    var count = 1
}

/// The Spotted log (2026-09-26): each suspicious or dangerous verdict from Safari, a shared link or
/// Check a link, kept 90 days in the App Group (`protection/spotted.json`), clearable. The app, "Send
/// to Loupe" and Loupe for Safari all write it; a file lock serialises them.
final class SpottedLog {
    static let retention: TimeInterval = 90 * 24 * 3600
    /// The same site flagged again in the same place within 30 minutes updates the entry.
    static let dedupeWindow: TimeInterval = 30 * 60
    static let maxEntries = 2000
    static let maxReasons = 3

    let file: URL
    private let lockFile: URL
    private let now: () -> Date

    init(dir: URL = ProtectionGroup.protectionDir(), now: @escaping () -> Date = Date.init) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent("spotted.json")
        lockFile = dir.appendingPathComponent(".spotted.lock")
        self.now = now
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

    private func read() -> [SpottedEntry] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? Self.decoder.decode([SpottedEntry].self, from: data)) ?? []
    }

    private func write(_ entries: [SpottedEntry]) {
        guard let data = try? Self.encoder.encode(entries) else { return }
        try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func pruned(_ entries: [SpottedEntry]) -> [SpottedEntry] {
        let cutoff = now().addingTimeInterval(-Self.retention)
        return Array(entries.filter { $0.at >= cutoff }.sorted { $0.at > $1.at }.prefix(Self.maxEntries))
    }

    private func mutate<T>(_ body: (inout [SpottedEntry]) -> T) -> T {
        GroupFileLock.with(lockFile) {
            var entries = pruned(read())
            let out = body(&entries)
            write(pruned(entries))
            return out
        }
    }

    /// Adds a flagged verdict (a safe one is never logged). Returns the entry, or nil for a safe level.
    @discardableResult
    func record(domain: String, host: String, level: ProtectionLevel, score: Int, reasons: [String], brand: String?,
                origin: ProtectionOrigin) -> SpottedEntry? {
        guard level.flagged, !host.isEmpty else { return nil }
        let t = now()
        let top = Array(reasons.prefix(Self.maxReasons))
        return mutate { entries in
            if let i = entries.firstIndex(where: { $0.host == host && $0.origin == origin && t.timeIntervalSince($0.at) < Self.dedupeWindow }) {
                entries[i].at = t
                entries[i].count += 1
                if level.rank >= entries[i].level.rank {
                    entries[i].level = level
                    entries[i].score = score
                    entries[i].reasons = top
                    entries[i].brand = brand ?? entries[i].brand
                }
                return entries[i]
            }
            let e = SpottedEntry(at: t, domain: String(domain.prefix(253)), host: String(host.prefix(253)), level: level, score: score,
                                 reasons: top, brand: brand, origin: origin)
            entries.insert(e, at: 0)
            return e
        }
    }

    /// Adds a verdict from Check a link or the share sheet.
    @discardableResult
    func record(_ v: LinkVerdict) -> SpottedEntry? {
        record(domain: v.unicodeHost, host: v.host, level: v.level, score: v.score, reasons: v.reasons.map(\.text),
               brand: v.brand, origin: v.origin)
    }

    /// Records what the user did on Safari's warning for [host] (the newest entry for it in the last day).
    func setAction(host: String, origin: ProtectionOrigin, _ action: SpottedAction) {
        let t = now()
        mutate { entries in
            if let i = entries.firstIndex(where: { $0.host == host && $0.origin == origin && t.timeIntervalSince($0.at) < 24 * 3600 }) {
                entries[i].action = action
            }
        }
    }

    /// Newest first, older than 90 days dropped.
    func entries() -> [SpottedEntry] { GroupFileLock.with(lockFile) { pruned(read()) } }

    var unseenCount: Int { entries().filter { !$0.seen }.count }

    func markAllSeen() {
        mutate { entries in for i in entries.indices { entries[i].seen = true } }
    }

    func remove(_ id: UUID) {
        mutate { entries in entries.removeAll { $0.id == id } }
    }

    func clear() {
        GroupFileLock.with(lockFile) { try? FileManager.default.removeItem(at: file) }
    }

    /// Distinct risky websites flagged since [since] ("Loupe spotted N risky sites this week").
    func distinctSites(since: Date) -> Int { Set(entries().filter { $0.at >= since }.map(\.host)).count }
}

/// Now's card: "Loupe spotted N risky sites this week" (exposed for the Now screen to show).
struct SpottedSummary: Equatable {
    let sitesThisWeek: Int
    let dangerousThisWeek: Int
    let unseen: Int
    let latest: SpottedEntry?

    var headline: String? {
        guard sitesThisWeek > 0 else { return nil }
        return "Loupe spotted \(sitesThisWeek) risky site\(sitesThisWeek == 1 ? "" : "s") this week"
    }

    static func of(_ entries: [SpottedEntry], now: Date = Date()) -> SpottedSummary {
        let week = entries.filter { $0.at >= now.addingTimeInterval(-7 * 24 * 3600) }
        return SpottedSummary(sitesThisWeek: Set(week.map(\.host)).count,
                              dangerousThisWeek: Set(week.filter { $0.level == .dangerous }.map(\.host)).count,
                              unseen: entries.filter { !$0.seen }.count, latest: entries.first)
    }
}
