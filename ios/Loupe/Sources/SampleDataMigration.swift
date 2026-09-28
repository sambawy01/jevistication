import Foundation
import LoupeKit

/// Removes the synthetic sample from a phone that ran an older build (owner decision E, 2026-09-28: no sample data
/// in the app at all). Runs once, at launch, before any store opens (`LoupeApp.init`), and only on the phone's own
/// home (never under XCTest or DEBUG fixtures, whose homes are throwaway and use the sample on purpose).
///
/// What goes — everything that refers to a sample item, and nothing else:
/// - the sample source's cached scan (`sources/scan-sample.json`) and its switch (`"sample"` in `sources/enabled.json`);
/// - decision rows (`ledger.jsonl`) and corrections (`corrections.jsonl`) whose `itemId` is a sample item or a finding
///   key built on one (`impersonation:sample:…`, `privacy:passport_number:sample:…`, `term-change:sample:…>sample:…`),
///   or a key the sample alone raises that names no item (its recurring merchants, `recurring:Streamflix` and
///   `subscription:Streamflix`; its duplicate group, `privacy:duplicate:…`) — worked out from the sample's own cached
///   items by running the watchers and the privacy check over them (48 items; rules only);
/// - review proposals (`review-items.json`) whose source key, proposal or action names a sample item, and their log
///   lines (`review-log.jsonl`);
/// - saved results (`results/*.json`): findings, census rows, expiry rows, mail rows and link checks of sample items;
/// - the watchers' "seen" keys of sample findings;
/// - Spotted and recent link checks of the sample's websites: every sample link is on the reserved `.example` domain
///   (RFC 2606), which no real website can use, so only those entries go.
///
/// Every file is rewritten only when something in it goes, atomically; a line that cannot be parsed is kept. Running it
/// again removes nothing more (idempotent), and the marker `migrations/sample-removed-v1` keeps it from running twice.
struct SampleDataMigration {
    static let marker = "migrations/sample-removed-v1"

    let home: URL
    /// The App Group's protection folder (Spotted, recent checks); nil to leave it alone.
    let protectionDir: URL?
    let defaults: UserDefaults

    /// What one pass removed.
    struct Report: Equatable {
        var sourceCache = false
        var sourceSwitch = false
        var ledgerRows = 0
        var corrections = 0
        var reviewItems = 0
        var reviewLog = 0
        var results = 0
        var seenKeys = 0
        var spotted = 0
        var recentChecks = 0

        var total: Int {
            (sourceCache ? 1 : 0) + (sourceSwitch ? 1 : 0) + ledgerRows + corrections + reviewItems + reviewLog + results
                + seenKeys + spotted + recentChecks
        }
    }

    /// At launch on the phone's own home, once.
    static func runAtLaunch() {
        guard ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil, !LaunchOptions.current.fixtureMode else { return }
        let m = SampleDataMigration(home: LedgerService.defaultHome(), protectionDir: ProtectionGroup.protectionDir(), defaults: .standard)
        if let r = m.runIfNeeded() {
            Log.run.info("sample migration: removed \(r.total, privacy: .public) (ledger \(r.ledgerRows, privacy: .public), corrections \(r.corrections, privacy: .public), review \(r.reviewItems, privacy: .public), results \(r.results, privacy: .public), spotted \(r.spotted, privacy: .public), recent \(r.recentChecks, privacy: .public))")
        }
    }

    /// Runs the pass unless the marker says it ran; returns its report, or nil when it had run before.
    @discardableResult
    func runIfNeeded() -> Report? {
        let markerURL = home.appendingPathComponent(Self.marker)
        guard !FileManager.default.fileExists(atPath: markerURL.path) else { return nil }
        let report = run()
        try? FileManager.default.createDirectory(at: markerURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? Data("removed \(report.total)\n".utf8).write(to: markerURL, options: .atomic)
        return report
    }

    /// One pass (idempotent on its own).
    func run() -> Report {
        var r = Report()
        let fm = FileManager.default
        let sources = home.appendingPathComponent("sources", isDirectory: true)
        let cache = sources.appendingPathComponent("scan-\(SourcesService.sampleId).json")
        // The keys the sample alone raises, from its cached items, before the cache goes.
        let keys = Self.sampleKeys((try? SourceLibrary(home: home.path))?.cached(sourceId: SourcesService.sampleId)?.result.items ?? [])
        let isSample: (String) -> Bool = { Self.refers($0) || keys.contains($0) }
        if fm.fileExists(atPath: cache.path) {
            try? fm.removeItem(at: cache)
            r.sourceCache = true
        }
        r.sourceSwitch = removeKey(SourcesService.sampleId, fromObjectIn: sources.appendingPathComponent("enabled.json"))
        r.ledgerRows = filterLines(home.appendingPathComponent(LedgerStore.companion.LEDGER_FILE)) { Self.refers(($0["itemId"] as? String) ?? "") }
        r.corrections = filterLines(home.appendingPathComponent(LedgerStore.companion.CORRECTIONS_FILE)) { isSample(($0["itemId"] as? String) ?? "") }
        let dropped = filterReviewItems(home.appendingPathComponent(ReviewQueue.companion.ITEMS_FILE), isSample: isSample)
        r.reviewItems = dropped.count
        if !dropped.isEmpty {
            r.reviewLog = filterLines(home.appendingPathComponent(ReviewQueue.companion.LOG_FILE)) { dropped.contains(($0["item_id"] as? String) ?? "") }
        }
        r.results = filterResults(ResultsStore(home: home), isSample: isSample)
        if var seen = defaults.stringArray(forKey: WatchersService.seenKeysKey) {
            let before = seen.count
            seen.removeAll(where: isSample)
            if seen.count != before {
                defaults.set(seen, forKey: WatchersService.seenKeysKey)
                r.seenKeys = before - seen.count
            }
        }
        if let dir = protectionDir {
            r.spotted = SpottedLog(dir: dir).removeAll { Self.sampleHost($0.host) }
            r.recentChecks = RecentChecksStore(dir: dir).removeAll { Self.sampleHost($0.host) }
        }
        return r
    }

    // MARK: What counts as the sample's

    /// An item id of the sample, or a key built on one.
    static func refers(_ id: String) -> Bool {
        id.hasPrefix("sample:") || id.contains(":sample:") || id.contains(">sample:")
    }

    /// The finding keys the sample's items raise on their own that name no item: recurring merchants (their finding and
    /// their census answer) and duplicate groups. Item-named keys are caught by `refers`.
    static func sampleKeys(_ items: [SourceItem]) -> Set<String> {
        guard !items.isEmpty else { return [] }
        let watchers = WatcherFindings.shared.summarise(report: WatcherRun.shared.runIso(items: items, todayIso: WatchersService.isoDay(Date()), backend: nil),
                                                        items: items, sampleSourceIds: [], corrections: [:])
        let privacy = PrivacyCheck.shared.summariseToday(items: items, sampleSourceIds: [], corrections: [:])
        let merchants = watchers.census.rows.flatMap { [$0.key, "recurring:" + $0.merchant] }
        let keys = watchers.findings.map(\.key) + merchants + privacy.findings.map(\.key)
        return Set(keys.filter { !refers($0) })
    }

    /// A website of the sample's: on the reserved `.example` domain, which no real website can have.
    static func sampleHost(_ host: String) -> Bool {
        let h = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return h == "example" || h.hasSuffix(".example")
    }

    // MARK: Files

    /// Drops the JSON lines [drop] picks; unparseable lines stay. Returns how many went.
    private func filterLines(_ url: URL, drop: ([String: Any]) -> Bool) -> Int {
        guard let data = try? Data(contentsOf: url) else { return 0 }
        let text = String(decoding: data, as: UTF8.self)
        var kept: [Substring] = []
        var removed = 0
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if let obj = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any], drop(obj) {
                removed += 1
            } else {
                kept.append(line)
            }
        }
        guard removed > 0 else { return 0 }
        try? Data(kept.joined(separator: "\n").utf8).write(to: url, options: .atomic)
        return removed
    }

    private func removeKey(_ key: String, fromObjectIn url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url),
              var obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any], obj[key] != nil else { return false }
        obj.removeValue(forKey: key)
        guard let out = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]) else { return false }
        try? out.write(to: url, options: .atomic)
        return true
    }

    /// Drops review items that name a sample item; returns their ids.
    private func filterReviewItems(_ url: URL, isSample: (String) -> Bool) -> Set<String> {
        guard let data = try? Data(contentsOf: url),
              var root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = root["items"] as? [[String: Any]] else { return [] }
        func strings(_ v: Any?) -> [String] { (v as? [String: Any])?.values.compactMap { $0 as? String } ?? [] }
        var dropped = Set<String>()
        let kept = items.filter { item in
            let action = item["action"] as? [String: Any]
            let named = [item["source_key"] as? String ?? ""] + strings(item["proposal"]) + strings(item["original_proposal"])
                + strings(action?["params"])
            guard named.contains(where: isSample) else { return true }
            if let id = item["id"] as? String { dropped.insert(id) }
            return false
        }
        guard !dropped.isEmpty else { return [] }
        root["items"] = kept
        if let out = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted]) {
            try? out.write(to: url, options: .atomic)
        }
        return dropped
    }

    /// Drops the sample's rows from the saved results; returns how many rows went.
    private func filterResults(_ store: ResultsStore, isSample: (String) -> Bool) -> Int {
        var removed = 0
        if let (s, meta) = store.loadWatchers() {
            let findings = s.findings.filter { !Self.refers($0.itemId) && !isSample($0.key) && !Self.refers($0.otherItemId ?? "") }
            let rows = s.census.rows.filter { row in !(row.itemIds.contains(where: Self.refers)) && !isSample(row.key) }
            let expiries = s.expiries.filter { !Self.refers($0.itemId) }
            let n = (s.findings.count - findings.count) + (s.census.rows.count - rows.count) + (s.expiries.count - expiries.count)
            if n > 0 {
                let census = SubscriptionCensus(rows: rows, monthlyTotalMinor: rows.compactMap { $0.monthlyMinor?.int64Value }.reduce(0, +),
                                                chargesFound: s.census.chargesFound, sample: false, setAside: s.census.setAside)
                let out = s.with(census: census, findings: findings).with(expiries: expiries)
                var m = meta ?? ResultsStore.Meta(savedAt: Date())
                m.newKeys = m.newKeys?.filter { !isSample($0) }
                store.saveWatchers(out, meta: m)
                removed += n
            }
        }
        if let (s, meta) = store.loadPrivacy() {
            let findings = s.findings.filter { f in
                !Self.refers(f.itemId) && !(f.duplicates?.members.contains { Self.refers($0.itemId) } ?? false)
            }
            if findings.count != s.findings.count {
                store.savePrivacy(PrivacySummary(findings: findings, markedSafe: s.markedSafe, itemsChecked: s.itemsChecked),
                                  meta: meta ?? ResultsStore.Meta(savedAt: Date()))
                removed += s.findings.count - findings.count
            }
        }
        if let (s, meta) = store.loadMail() {
            let rows = s.rows.filter { !Self.refers($0.itemId) }
            let links = s.webLinks.filter { !Self.refers($0.itemId) }
            let n = (s.rows.count - rows.count) + (s.webLinks.count - links.count)
            if n > 0 {
                store.saveMail(MailSummary(rows: rows, webLinks: links), meta: meta ?? ResultsStore.Meta(savedAt: Date()))
                removed += n
            }
        }
        store.flush()
        return removed
    }
}
