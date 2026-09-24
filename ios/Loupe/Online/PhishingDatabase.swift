import Foundation
import LoupeKit

/// Phishing.Database on the phone (owner decision B, 2026-09-24): Loupe Station's
/// `laya_studio/online/phishingdb.py` behaviour. MIT licence, © 2018-2025 Mitchell Krog, Nissar
/// Chababy and the Phishing.Database Contributors (Me → Licences). It aggregates upstream sources it
/// does not all name, so a hit is shown as "Phishing.Database", never as a named authority's verdict.
///
/// - Four files from https://phish.co.za/latest/: the two ACTIVE files on the refresh setting
///   (6 h default, 1–168), the two NEW-today files hourly. There are no .gz copies, and an unknown
///   name answers 200 with an HTML page, so the Content-Type and the body are checked.
/// - One conditional GET per file (If-None-Match / If-Modified-Since from the last good copy; a 304
///   keeps it), about 3 s between files (the server refuses bursts with 403), a sanity check
///   (`PhishingDb.check`: not HTML, ≥ 1,000 lines for ACTIVE, ≥ 90 % parse), then an atomic swap.
///   Any failure keeps the last good copy. NEW files are merged into recent-*.txt, which a fresh
///   ACTIVE copy resets.
/// - Matching is on the phone (`PhishingDbIndex`); nothing about a link is ever sent.
final class PhishingDatabaseStore {
    struct FileState: Codable, Equatable {
        var etag: String?
        var lastModified: String?
        var lines: Int?
        var fetchedAt: Date?
        var checkedAt: Date?
        var lastError: String?
    }

    static let baseURL = URL(string: PhishingDb.shared.BASE_URL)!
    static let gapSeconds: Double = PhishingDb.shared.FILE_GAP_S
    static let newEvery: TimeInterval = TimeInterval(PhishingDb.shared.NEW_EVERY_S)

    let dir: URL
    private let session: URLSession
    private let now: () -> Date
    /// Waits between two files (tests pass a recorder; the app sleeps).
    private let pause: (Double) async -> Void
    private(set) var state: [String: FileState] = [:]
    private var index: PhishingDbIndex?
    private var indexStamp: [String: Date] = [:]

    init(dir: URL, session: URLSession, now: @escaping () -> Date = Date.init,
         pause: @escaping (Double) async -> Void = { s in try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }) {
        self.dir = dir
        self.session = session
        self.now = now
        self.pause = pause
        state = (try? JSONDecoder().decode([String: FileState].self, from: Data(contentsOf: stateURL))) ?? [:]
    }

    private var stateURL: URL { dir.appendingPathComponent("state.json") }
    func file(_ name: String) -> URL { dir.appendingPathComponent(name) }
    private var files: [PhishingDb.ListFile] { PhishingDb.shared.FILES }

    private func saveState() {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(state) { try? data.write(to: stateURL, options: .atomic) }
    }

    /// Whether a file is due: missing, or last checked longer ago than its period.
    func due(_ f: PhishingDb.ListFile, refreshHours: Int) -> Bool {
        let period = f.tier == "full" ? TimeInterval(refreshHours) * 3600 : Self.newEvery
        guard FileManager.default.fileExists(atPath: file(f.name).path), let at = state[f.key]?.checkedAt else { return true }
        return now().timeIntervalSince(at) >= period
    }

    /// Downloads what is due, one request per file with a pause between them. Returns the first
    /// error code (nil when every due file came down or was unchanged).
    @discardableResult
    func refresh(refreshHours: Int, force: Bool = false) async -> String? {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var failure: String?
        var first = true
        for f in files where force || due(f, refreshHours: refreshHours) {
            if !first { await pause(Self.gapSeconds) }
            first = false
            do {
                try await fetch(f)
            } catch let e as FetchError {
                failure = failure ?? e.code
                var st = state[f.key] ?? FileState()
                st.lastError = e.code
                st.checkedAt = now()
                state[f.key] = st
            } catch {
                failure = failure ?? "network"
            }
        }
        saveState()
        return failure
    }

    struct FetchError: Error { let code: String }

    private func fetch(_ f: PhishingDb.ListFile) async throws {
        let dest = file(f.name)
        var req = URLRequest(url: Self.baseURL.appendingPathComponent(f.name), timeoutInterval: f.tier == "full" ? 300 : 60)
        req.setValue("Loupe-iOS (phishing list download)", forHTTPHeaderField: "User-Agent")
        req.cachePolicy = .reloadIgnoringLocalCacheData
        let prev = state[f.key]
        if let prev, FileManager.default.fileExists(atPath: dest.path) {  // never a conditional GET without the copy it vouches for
            if let e = prev.etag { req.setValue(e, forHTTPHeaderField: "If-None-Match") }
            if let m = prev.lastModified { req.setValue(m, forHTTPHeaderField: "If-Modified-Since") }
        }
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: req) } catch { throw FetchError(code: "offline") }
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        if status == 304 {
            var st = prev ?? FileState()
            st.checkedAt = now()
            st.lastError = nil
            state[f.key] = st
            return
        }
        guard status == 200 else { throw FetchError(code: status == 403 ? "refused" : "http_\(status)") }
        guard data.count <= Int(f.maxBytes) else { throw FetchError(code: "too_large") }
        let type = http?.value(forHTTPHeaderField: "Content-Type")
        let text = String(decoding: data, as: UTF8.self)
        let checked = PhishingDb.shared.checkFile(text: text, kind: f.kind, tier: f.tier, contentType: type)
        guard let lines = checked.valid else { throw FetchError(code: "bad_list") }
        // the swap: write beside, then replace in one step; the last good copy stays until then
        let part = dir.appendingPathComponent(f.name + ".part")
        do {
            try data.write(to: part, options: .atomic)
            if FileManager.default.fileExists(atPath: dest.path) {
                _ = try FileManager.default.replaceItemAt(dest, withItemAt: part)
            } else {
                try FileManager.default.moveItem(at: part, to: dest)
            }
        } catch {
            try? FileManager.default.removeItem(at: part)
            throw FetchError(code: "disk")
        }
        var url = dest
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        let recent = file(PhishingDb.shared.RECENT[f.kind]!)
        if f.tier == "full" {
            try? FileManager.default.removeItem(at: recent)              // a fresh ACTIVE copy already has them
        } else {
            let old = try? String(contentsOf: recent, encoding: .utf8)
            let merged = PhishingDb.shared.mergeRecent(existing: old, newText: text, kind: f.kind)
            try? merged.write(to: recent, atomically: true, encoding: .utf8)
        }
        state[f.key] = FileState(etag: http?.value(forHTTPHeaderField: "ETag"), lastModified: http?.value(forHTTPHeaderField: "Last-Modified"),
                                 lines: lines.intValue, fetchedAt: now(), checkedAt: now(), lastError: nil)
    }

    private func stamp(_ url: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: url.path))?[.modificationDate] as? Date
    }

    private var inputs: (links: [URL], domains: [URL]) {
        let r = PhishingDb.shared.RECENT
        return ([file("phishing-links-ACTIVE.txt"), file(r["links"]!)], [file("phishing-domains-ACTIVE.txt"), file(r["domains"]!)])
    }

    /// The index over the files on this phone, rebuilt only when one of them changed; nil when none exists.
    func loadedIndex() -> PhishingDbIndex? {
        let (links, domains) = inputs
        var stamps: [String: Date] = [:]
        for u in links + domains { if let d = stamp(u) { stamps[u.lastPathComponent] = d } }
        if stamps.isEmpty { index = nil; return nil }
        if let index, stamps == indexStamp { return index }
        let date = listDate()
        index = PhishingDb.shared.buildFromFiles(linkPaths: links.map(\.path), domainPaths: domains.map(\.path),
                                                 listDate: date, maxEntries: PhishingDb.shared.MAX_ENTRIES)
        indexStamp = stamps
        return index
    }

    private static let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    /// The newest Last-Modified among the files (the list's own date), else when they were fetched.
    func listDate() -> String? {
        let iso = ISO8601DateFormatter()
        let dates = state.values.compactMap { $0.lastModified.flatMap(Self.httpDate.date(from:)) ?? $0.fetchedAt }
        return dates.max().map(iso.string(from:))
    }

    func remove() {
        try? FileManager.default.removeItem(at: dir)
        state = [:]
        index = nil
        indexStamp = [:]
    }
}
