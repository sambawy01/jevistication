import Foundation
import LoupeKit

/// The known-phishing lists the app downloaded into the App Group, read by an extension without
/// loading them whole: Phishing.Database through its mapped index, and OpenPhish / PhishTank's
/// text lists only while they are small enough for an extension's memory ([maxFeedBytes]).
/// Nothing here downloads anything; the app keeps the lists fresh.
final class ProtectionLists {
    static let maxFeedBytes = 3 * 1024 * 1024

    let phishingDbDir: URL
    let feedsDir: URL
    private let lock = NSLock()
    private var mapped: (index: MappedPhishingIndex, stamp: Date)?
    private var feeds: (index: FeedIndex, key: String, fetchedAt: String?)?

    init(phishingDbDir: URL = ProtectionGroup.phishingDbDir(), feedsDir: URL = ProtectionGroup.feedsDir()) {
        self.phishingDbDir = phishingDbDir
        self.feedsDir = feedsDir
    }

    private static func stamp(_ url: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: url.path))?[.modificationDate] as? Date
    }

    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// Phishing.Database's mapped index, re-mapped when the app wrote a newer one; nil when absent.
    func phishingDb() -> MappedPhishingIndex? {
        let file = phishingDbDir.appendingPathComponent(ProtectionGroup.phishingDbIndexName)
        lock.lock(); defer { lock.unlock() }
        guard let at = Self.stamp(file) else { mapped = nil; return nil }
        if let m = mapped, m.stamp == at { return m.index }
        guard let index = MappedPhishingIndex(file: file) else { mapped = nil; return nil }
        mapped = (index, at)
        return index
    }

    /// OpenPhish / PhishTank as one FeedIndex, or nil when none is on this phone or they are too big
    /// for an extension (then [skipped] says which).
    func feedIndex(_ lists: [String]) -> (index: FeedIndex, fetchedAt: String?, skipped: [String])? {
        let files = lists.map { ($0, feedsDir.appendingPathComponent("\($0).txt")) }
        var present: [(String, URL, Date, Int)] = []
        var skipped: [String] = []
        for (list, url) in files {
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                  let at = attrs[.modificationDate] as? Date, let size = attrs[.size] as? Int else { continue }
            if size > Self.maxFeedBytes { skipped.append(list); continue }
            present.append((list, url, at, size))
        }
        guard !present.isEmpty else { return nil }
        let key = present.map { "\($0.0)@\($0.2.timeIntervalSince1970)" }.joined(separator: ",")
        lock.lock(); defer { lock.unlock() }
        if let f = feeds, f.key == key { return (f.index, f.fetchedAt, skipped) }
        var entries: [String: [String]] = [:]
        for (list, url, _, _) in present {
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            entries[list] = text.split(separator: "\n").map(String.init)
        }
        let index = FeedIndex(entries: entries)
        let at = present.map(\.2).min().map(Self.iso.string(from:))
        feeds = (index, key, at)
        return (index, at, skipped)
    }

    /// The lists' part of a verdict for [url], on this phone only (nothing is sent): Phishing.Database's
    /// listed hashes for this URL, the small feeds, and the lines saying what was matched here.
    func onDevice(url: String, settings s: OnlinePhishingSettings) -> (phishingDb: PhishingDbIndex?, feeds: FeedIndex?, feedsAt: String?, notes: [String]) {
        guard s.feeds else { return (nil, nil, nil, []) }
        var notes: [String] = []
        var slice: PhishingDbIndex?
        var feedsOut: FeedIndex?
        var feedsAt: String?
        if s.phishingDb, let pdb = phishingDb() {
            slice = pdb.slice(for: url)
            notes.append("Phishing.Database list on this iPhone (\(pdb.entryCount) entries\(pdb.listDate.map { ", list date \($0.prefix(10))" } ?? ""))")
        }
        let small = s.lists.filter { $0 != "phishingdb" }
        if !small.isEmpty, let f = feedIndex(small) {
            feedsOut = f.index
            feedsAt = f.fetchedAt
            let names = f.index.counts.keys.sorted().map { $0 == "openphish" ? "OpenPhish" : "PhishTank" }
            if !names.isEmpty { notes.append("\(names.joined(separator: ", ")) list on this iPhone") }
            for l in f.skipped { notes.append("\(l == "phishtank" ? "PhishTank" : "OpenPhish") is too large to read here; the app's link check uses it") }
        }
        return (slice, feedsOut, feedsAt, notes)
    }
}

/// A verdict with no network at all: the mechanical checks and the lists already on this phone. The
/// share sheet's fast path ("Send to Loupe") uses it; nothing leaves the phone.
enum DeviceLinkCheck {
    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func verdict(_ input: LinkInput.Normalized, origin: ProtectionOrigin,
                        settings: OnlinePhishingSettings = OnlinePhishingSettings.loadShared(),
                        lists: ProtectionLists = ProtectionLists(), now: Date = Date()) -> LinkVerdict {
        let local = lists.onDevice(url: input.url, settings: settings)
        var d = OnlineDisclosure()
        d.matchedOnDevice = local.notes
        let ctx: OnlineContext? = (local.phishingDb == nil && local.feeds == nil) ? nil
            : OnlineContext(nowIso: iso.string(from: now), facts: [:], feeds: local.feeds, safeBrowsingHits: [], safeBrowsingFetchedAt: nil,
                            feedsFetchedAt: local.feedsAt, phishingDb: local.phishingDb, dns: [:], dnsbl: [:], dnsblFetchedAt: nil)
        return LinkChecker.verdict(input, online: ctx, disclosure: d, origin: origin, now: now)
    }
}
