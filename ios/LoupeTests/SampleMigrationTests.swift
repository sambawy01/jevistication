import XCTest
import LoupeKit
@testable import Loupe

/// The sample migration (owner decision E, 2026-09-28): on a phone that ran an older build, everything derived from
/// the bundled sample goes — its cached scan and switch, its decisions and corrections, its review proposals, its
/// saved findings and seen keys, its Spotted and recent link checks — and nothing else does. Idempotent; runs once.
/// Plus the build check: the app bundle carries no sample.
@MainActor
final class SampleMigrationTests: XCTestCase {
    private var home: URL!
    private var protection: URL!
    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeMigration-\(UUID().uuidString)")
        protection = home.appendingPathComponent("group-protection", isDirectory: true)
        suite = "SampleMigrationTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: home)
        defaults.removePersistentDomain(forName: suite)
    }

    // MARK: The build check

    func testTheAppBundleCarriesNoSample() {
        XCTAssertNil(Bundle.main.url(forResource: "sample", withExtension: nil), "no sample folder in the app bundle")
        // The test bundles are injected into the app's PlugIns while the tests run; they carry the sample on purpose.
        let listing = ((FileManager.default.enumerator(atPath: Bundle.main.bundlePath)?.allObjects as? [String]) ?? [])
            .filter { !$0.contains(".xctest") }
        XCTAssertFalse(listing.contains { $0.contains("passport-scan-SPECIMEN") || $0.hasSuffix("phishing-paypal.eml") },
                       "none of the sample's files anywhere in the app")
        XCTAssertNotNil(TestSample.root(), "the tests still have it")
        XCTAssertNil(SourcesService.fixtureSampleRoot(), "no fixture sample outside a DEBUG fixture launch")
    }

    // MARK: The migration

    private func sampleItems() throws -> [SourceItem] {
        let root = try XCTUnwrap(TestSample.root())
        return try SourceScanner(extractors: AppleExtractors()).scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
    }

    /// A real item: a copy of the sample passport's text, picked in Files (its findings are the user's own).
    private func realItems(_ sample: [SourceItem]) throws -> [SourceItem] {
        let passport = try XCTUnwrap(sample.first { $0.name.contains("passport") })
        return [SourceItem(id: "files:loc1/passport-scan.txt", sourceId: "files", kind: passport.kind, path: "/real/passport-scan.txt",
                           messageIndex: nil, name: "passport-scan.txt", text: passport.text, hasText: true, textTruncated: false,
                           sizeBytes: passport.sizeBytes, contentHash: "real-" + passport.contentHash, mime: passport.mime,
                           date: passport.date, dateOrigin: passport.dateOrigin, email: nil, facts: [:], duplicateOf: nil)]
    }

    private func ledgerLine(_ item: String) -> String {
        #"{"judgmentId":"j-is-receipt","itemId":"\#(item)","criteriaHash":"h1","action":"yes","propensity":0.9,"correction":null,"failure":null,"resolvedBy":"model","distribution":{"yes":0.9,"no":0.1}}"#
    }

    private func correctionLine(_ item: String) -> String {
        #"{"judgmentId":"watcher:expiry","criteriaHash":"c","itemId":"\#(item)","label":"confirmed","at":"2026-09-27T00:00:00Z","confirmed":true}"#
    }

    /// A home as an older build left it on the owner's phone: the sample scanned, decided, checked and proposed on,
    /// beside real data.
    private func seed() throws -> (sample: [SourceItem], real: [SourceItem]) {
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        let sample = try sampleItems(), real = try realItems(sample)
        let library = try SourceLibrary(home: home.path)
        _ = try library.store(sourceId: "sample", result: .of(sample))
        _ = try library.store(sourceId: "files", result: .of(real))
        try library.setEnabled(sourceId: "sample", enabled: true)
        try library.setEnabled(sourceId: "files", enabled: true)

        let realKey = "expiry:files:loc1/passport-scan.txt"
        let lines = [ledgerLine("sample:documents/receipts/cafe-luna-2026-09-02.txt"), ledgerLine("files:loc1/passport-scan.txt"),
                     "this line is not json and stays", ledgerLine("sample:mail/subscriptions-2026.mbox#2")]
        try Data((lines.joined(separator: "\n") + "\n").utf8).write(to: home.appendingPathComponent("ledger.jsonl"))
        let fixes = [correctionLine("expiry:sample:documents/identity/passport-scan-SPECIMEN.txt"), correctionLine(realKey),
                     correctionLine("term-change:sample:documents/insurance/a.pdf>sample:documents/insurance/b.pdf:annual premium"),
                     correctionLine("privacy:passport_number:sample:documents/identity/passport-scan-SPECIMEN.txt"),
                     correctionLine("subscription:Streamflix"), correctionLine("recurring:Streamflix")]
        try Data((fixes.joined(separator: "\n") + "\n").utf8).write(to: home.appendingPathComponent("corrections.jsonl"))

        let all = sample + real
        let today = "2026-09-28"
        let watchers = WatcherFindings.shared.summarise(report: WatcherRun.shared.runIso(items: all, todayIso: today, backend: nil),
                                                        items: all, sampleSourceIds: [], corrections: [:])
        let privacy = PrivacyCheck.shared.summariseToday(items: all, sampleSourceIds: [], corrections: [:])
        let mail = MailTriage.shared.summariseWithRaw(items: all, raws: MailTriageService.rawSources(all), corrections: [:])
        let results = ResultsStore(home: home)
        results.saveWatchers(watchers, meta: .init(savedAt: Date(), newKeys: watchers.findings.map(\.key)))
        results.savePrivacy(privacy, meta: .init(savedAt: Date()))
        results.saveMail(mail, meta: .init(savedAt: Date()))
        results.flush()

        let queue = try ReviewQueue.companion.open(home: home.path)
        for p in ReviewProducers.shared.mail(rows: mail.rows) + ReviewProducers.shared.watchers(findings: watchers.findings) {
            _ = queue.submit(p: p, at: "2026-09-27T00:00:00Z")
        }

        defaults.set(watchers.findings.map(\.key), forKey: WatchersService.seenKeysKey)

        let spotted = SpottedLog(dir: protection)
        spotted.record(domain: "paypal.account-verify.example", host: "paypal.account-verify.example", level: .dangerous, score: 80,
                       reasons: ["Look-alike"], brand: "PayPal", origin: .manual)
        spotted.record(domain: "paypa1-login.com", host: "paypa1-login.com", level: .dangerous, score: 80, reasons: ["Look-alike"],
                       brand: "PayPal", origin: .safari)
        let recent = RecentChecksStore(dir: protection)
        for host in ["streamflix.example", "bbc.co.uk"] {
            recent.add(LinkVerdict(checkedAt: Date(), origin: .manual, displayURL: "https://\(host)/", host: host, unicodeHost: host,
                                   registrable: host, level: .safe, score: 0, reasons: [], brand: nil, onDevice: [], disclosure: .none))
        }
        return (sample, real)
    }

    func testItRemovesOnlyWhatCameFromTheSample() throws {
        let (sample, real) = try seed()
        let queueBefore = try ReviewQueue.companion.open(home: home.path).all()
        XCTAssertTrue(queueBefore.contains { $0.sourceKey.contains("sample:") } && queueBefore.contains { !$0.sourceKey.contains("sample:") })

        let report = SampleDataMigration(home: home, protectionDir: protection, defaults: defaults).run()

        // The sample's cache and switch.
        let library = try SourceLibrary(home: home.path)
        XCTAssertNil(library.cached(sourceId: "sample"))
        XCTAssertEqual(library.cached(sourceId: "files")?.result.items.map(\.id), real.map(\.id), "the real cache is untouched")
        XCTAssertTrue(report.sourceCache && report.sourceSwitch)
        let enabled = try JSONSerialization.jsonObject(with: Data(contentsOf: home.appendingPathComponent("sources/enabled.json"))) as? [String: Bool]
        XCTAssertEqual(enabled, ["files": true])

        // Decisions and corrections: the real ones and the unreadable line stay, byte for byte.
        let ledger = try String(contentsOf: home.appendingPathComponent("ledger.jsonl"), encoding: .utf8)
        XCTAssertEqual(ledger.split(separator: "\n").map(String.init), [ledgerLine("files:loc1/passport-scan.txt"), "this line is not json and stays"])
        XCTAssertEqual(report.ledgerRows, 2)
        let opened = try PhoneLedger.companion.open(home: home.path)
        XCTAssertEqual(opened.rows().map(\.itemId), ["files:loc1/passport-scan.txt"])
        XCTAssertEqual(opened.correctionIndex().keys.map(\.itemId), ["expiry:files:loc1/passport-scan.txt"])
        XCTAssertEqual(report.corrections, 5, "the sample's merchant answers too (keys that name no item)")

        // Review: only the real proposals, and their log.
        let queue = try ReviewQueue.companion.open(home: home.path)
        XCTAssertFalse(queue.all().isEmpty)
        XCTAssertTrue(queue.all().allSatisfy { !SampleDataMigration.refers($0.sourceKey) && !$0.proposal.values.contains(where: SampleDataMigration.refers) })
        XCTAssertFalse(queue.all().contains { $0.title.contains("Streamflix") }, "the sample merchant's proposal went too")
        XCTAssertEqual(report.reviewItems, queueBefore.count - queue.all().count)
        XCTAssertTrue(queue.log(itemId: nil).allSatisfy { e in queue.all().contains { $0.id == e.itemId } })

        // Saved results: the real findings stay, the sample's go.
        let results = ResultsStore(home: home)
        let watchers = try XCTUnwrap(results.loadWatchers()?.0)
        XCTAssertTrue(watchers.findings.contains { $0.itemId == "files:loc1/passport-scan.txt" }, "the real passport's expiry stays")
        XCTAssertFalse(watchers.findings.contains { SampleDataMigration.refers($0.itemId) || SampleDataMigration.refers($0.key) })
        XCTAssertTrue(watchers.census.rows.isEmpty, "every recurring charge was the sample's")
        XCTAssertEqual(watchers.census.monthlyTotalMinor, 0)
        XCTAssertFalse(watchers.expiries.contains { SampleDataMigration.refers($0.itemId) })
        let privacy = try XCTUnwrap(results.loadPrivacy()?.0)
        XCTAssertFalse(privacy.findings.isEmpty)
        XCTAssertTrue(privacy.findings.allSatisfy { $0.itemId == "files:loc1/passport-scan.txt" })
        let mail = try XCTUnwrap(results.loadMail()?.0)
        XCTAssertTrue(mail.rows.isEmpty, "every email was the sample's")
        XCTAssertGreaterThan(report.results, 0)

        // Seen keys, Spotted and recent checks.
        XCTAssertFalse((defaults.stringArray(forKey: WatchersService.seenKeysKey) ?? []).contains(where: SampleDataMigration.refers))
        XCTAssertTrue((defaults.stringArray(forKey: WatchersService.seenKeysKey) ?? []).contains("expiry:files:loc1/passport-scan.txt"))
        XCTAssertEqual(SpottedLog(dir: protection).entries().map(\.host), ["paypa1-login.com"])
        XCTAssertEqual(RecentChecksStore(dir: protection).all().map(\.host), ["bbc.co.uk"])
        XCTAssertEqual(report.spotted, 1)
        XCTAssertEqual(report.recentChecks, 1)
        XCTAssertFalse(sample.isEmpty)
    }

    func testItIsIdempotentAndRunsOnce() throws {
        _ = try seed()
        let m = SampleDataMigration(home: home, protectionDir: protection, defaults: defaults)
        let first = try XCTUnwrap(m.runIfNeeded())
        XCTAssertGreaterThan(first.total, 0)
        let ledger = try Data(contentsOf: home.appendingPathComponent("ledger.jsonl"))
        let corrections = try Data(contentsOf: home.appendingPathComponent("corrections.jsonl"))
        XCTAssertNil(m.runIfNeeded(), "the marker keeps it from running twice")
        XCTAssertEqual(m.run(), SampleDataMigration.Report(), "a second pass finds nothing more")
        XCTAssertEqual(try Data(contentsOf: home.appendingPathComponent("ledger.jsonl")), ledger, "and rewrites nothing")
        XCTAssertEqual(try Data(contentsOf: home.appendingPathComponent("corrections.jsonl")), corrections)
    }

    func testAHomeWithoutTheSampleIsLeftAlone() throws {
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        let line = ledgerLine("photos:ABC-123")
        try Data((line + "\n").utf8).write(to: home.appendingPathComponent("ledger.jsonl"))
        let report = SampleDataMigration(home: home, protectionDir: protection, defaults: defaults).run()
        XCTAssertEqual(report, SampleDataMigration.Report())
        XCTAssertEqual(try String(contentsOf: home.appendingPathComponent("ledger.jsonl"), encoding: .utf8), line + "\n")
    }

    func testWhatCountsAsTheSamples() {
        for id in ["sample:documents/a.txt", "sample:mail/x.mbox#3", "expiry:sample:documents/p.txt", "privacy:iban:sample:documents/b.csv",
                   "term-change:sample:documents/a.pdf>sample:documents/b.pdf:premium", "mail:sample:mail/inbox/x.eml"] {
            XCTAssertTrue(SampleDataMigration.refers(id), id)
        }
        for id in ["files:loc/sample.txt", "photos:ABC", "shared:my sample notes.txt", "subscription:Streamflix", "files:x/samples:y"] {
            XCTAssertFalse(SampleDataMigration.refers(id), id)
        }
        XCTAssertTrue(SampleDataMigration.sampleHost("paypal.account-verify.example"))
        XCTAssertTrue(SampleDataMigration.sampleHost("STREAMFLIX.EXAMPLE."))
        XCTAssertFalse(SampleDataMigration.sampleHost("example.com"))
        XCTAssertFalse(SampleDataMigration.sampleHost("myexample.org"))
    }
}
