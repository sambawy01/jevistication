import XCTest
import LoupeKit
@testable import Loupe

/// The Guard tab's view model (owner decision 2026-09-26): the expiry timeline's grouping by days left, the
/// subscriptions' sort and totals, the locked model half, the progress strip, the tab routing (the old Web tab), and
/// the watchers service's Guard answers over the bundled sample.
@MainActor
final class GuardModelTests: XCTestCase {
    // MARK: Builders

    private func expiry(_ days: Int64, _ name: String = "doc", type: String? = nil, breaches: Bool = true, ambiguous: Bool = false) -> ExpiryRow {
        ExpiryRow(itemId: "id:\(name)", itemName: name, expiryIso: "2027-01-14", daysRemaining: days, ambiguous: ambiguous,
                  breachesRule: breaches, documentType: type, findingKey: breaches ? "expiry:id:\(name)" : nil, line: nil, sample: false)
    }

    private func sub(_ merchant: String, monthly: Int64?, typical: Int64 = 0, cadence: String = "monthly") -> CensusRow {
        CensusRow(merchant: merchant, cadence: cadence, occurrences: 3, typicalMinor: typical == 0 ? (monthly ?? 0) : typical,
                  lastChargedIso: "2026-09-01", daysSinceLastCharge: 22, monthlyMinor: monthly.map { KotlinLong(value: $0) },
                  sample: false, itemIds: ["a", "b", "c"], nextExpectedIso: monthly == nil ? nil : "2026-10-01", verdict: nil)
    }

    // MARK: Expiring soon

    func testBucketsByDaysLeft() {
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: -1), .overdue)
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: 0), .thisWeek)
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: 7), .thisWeek)
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: 8), .thisMonth)
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: 30), .thisMonth)
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: 31), .later)
    }

    func testGroupsAreInTimelineOrderSoonestFirstAndEmptyOnesLeftOut() {
        let rows = [expiry(200, "warranty"), expiry(-4, "card"), expiry(3, "id"), expiry(113, "passport"), expiry(-40, "policy")]
        let groups = GuardModel.groupExpiries(rows)
        XCTAssertEqual(groups.map(\.bucket), [.overdue, .thisWeek, .later], "no document this month: no empty heading")
        XCTAssertEqual(groups[0].rows.map(\.itemName), ["policy", "card"])
        XCTAssertEqual(groups[2].rows.map(\.itemName), ["passport", "warranty"])
        XCTAssertTrue(GuardModel.groupExpiries([]).isEmpty)
    }

    func testDaysLeftWords() {
        XCTAssertEqual(GuardModel.daysLeftLine(113), "113 days left")
        XCTAssertEqual(GuardModel.daysLeftLine(1), "Tomorrow")
        XCTAssertEqual(GuardModel.daysLeftLine(0), "Expires today")
        XCTAssertEqual(GuardModel.daysLeftLine(-1), "Expired yesterday")
        XCTAssertEqual(GuardModel.daysLeftLine(-4), "Expired 4 days ago")
    }

    func testTheModelHalfIsLockedWithoutTheModelWhileTheDatesStillShow() {
        XCTAssertEqual(GuardModel.modelHalf(modelRan: false, modelReady: false, turnedOff: false), .locked)
        XCTAssertEqual(GuardModel.modelHalf(modelRan: false, modelReady: true, turnedOff: false), .pending)
        XCTAssertEqual(GuardModel.modelHalf(modelRan: false, modelReady: true, turnedOff: true), .turnedOff)
        XCTAssertEqual(GuardModel.modelHalf(modelRan: true, modelReady: true, turnedOff: false), .ran)

        let passport = expiry(113, "passport-scan.txt")
        XCTAssertEqual(GuardModel.typeLine(passport, half: .locked), "Type: needs the decision model")
        XCTAssertEqual(GuardModel.typeLine(expiry(113, type: "passport"), half: .ran), "The decision model: passport")
        XCTAssertEqual(GuardModel.typeLine(passport, half: .ran), "The decision model did not judge it a listed type")
        XCTAssertEqual(GuardModel.typeLine(expiry(300, breaches: false), half: .ran), "Not judged: outside the rule")
        // Locked or not, the mechanical rows are all there.
        XCTAssertEqual(GuardModel.groupExpiries([passport]).first?.rows.count, 1)
    }

    // MARK: Subscriptions

    func testSubscriptionsSortByMonthlyCostIrregularLast() {
        let rows = [sub("Cheap", monthly: 199), sub("Irregular big", monthly: nil, typical: 9000, cadence: "irregular"),
                    sub("Dear", monthly: 1599), sub("Irregular small", monthly: nil, typical: 500, cadence: "irregular"),
                    sub("Also dear", monthly: 1599)]
        XCTAssertEqual(GuardModel.sortedSubscriptions(rows).map(\.merchant),
                       ["Also dear", "Dear", "Cheap", "Irregular big", "Irregular small"])
    }

    func testTheMonthlyTotalSumsRegularChargesOnly() {
        let rows = [sub("A", monthly: 999), sub("B", monthly: 433), sub("C", monthly: nil, typical: 5000, cadence: "irregular")]
        XCTAssertEqual(GuardModel.monthlyTotal(rows), 1432)
        XCTAssertEqual(GuardModel.share(rows[0], total: 1432), 999.0 / 1432.0, accuracy: 1e-9)
        XCTAssertEqual(GuardModel.share(rows[2], total: 1432), 0)
        XCTAssertEqual(GuardModel.perMonth(rows[0]), "9.99/mo")
        XCTAssertEqual(GuardModel.perMonth(rows[2]), "irregular")
    }

    func testSettingASubscriptionAsideTakesItOutOfTheTotalAndUndoPutsItBack() {
        let a = sub("A", monthly: 999), b = sub("B", monthly: 433)
        let census = SubscriptionCensus(rows: [a, b], monthlyTotalMinor: 1432, chargesFound: 6, sample: false, setAside: 0)
        let without = GuardModel.census(census, replacing: "A", with: nil)
        XCTAssertEqual(without.rows.map(\.merchant), ["B"])
        XCTAssertEqual(without.monthlyTotalMinor, 433)
        XCTAssertEqual(without.setAside, 1)
        let back = GuardModel.census(without, restoring: a)
        XCTAssertEqual(back.monthlyTotalMinor, 1432)
        XCTAssertEqual(back.setAside, 0)
        let confirmed = GuardModel.census(census, replacing: "A", with: a.withVerdict(.confirmed))
        XCTAssertEqual(confirmed.rows.first { $0.merchant == "A" }?.verdict, .confirmed)
        XCTAssertEqual(confirmed.monthlyTotalMinor, 1432)
    }

    // MARK: Progress and routing

    func testTheStripSaysWhichWatcherRunsAndHowFarTheModelHalfIs() {
        let feed = WatcherProgressFeed()
        feed.begin()
        feed.apply("expiry", done: 0, total: 1)
        XCTAssertEqual(GuardProgress.line(feed.state!), "1 of 5 · Expiry radar")
        feed.apply("expiry", done: 2, total: 7)
        XCTAssertEqual(GuardProgress.line(feed.state!), "1 of 5 · Expiry radar · the decision model: document 3 of 7")
        XCTAssertEqual(GuardProgress.fraction(feed.state!), (2.0 / 7.0) / 5.0, accuracy: 1e-9)
        feed.apply("expiry", done: 1, total: 1)
        feed.apply("recurring", done: 0, total: 1)
        XCTAssertEqual(feed.state?.done, ["expiry"])
        XCTAssertEqual(GuardProgress.line(feed.state!), "2 of 5 · Recurring money")
        for w in ["recurring", "term-change", "impersonation", "site-fraud"] {
            feed.apply(w, done: 0, total: 1); feed.apply(w, done: 1, total: 1)
        }
        XCTAssertEqual(GuardProgress.fraction(feed.state!), 1, accuracy: 1e-9)
        feed.end()
        XCTAssertNil(feed.state)
    }

    func testTheOldWebTabOpensJudgmentsWebQuestions() {
        XCTAssertEqual(AppTab.route("web")?.tab, .judgments)
        XCTAssertEqual(AppTab.route("web")?.judgments, .web)
        XCTAssertEqual(AppTab.route("guard")?.tab, .guardTab)
        XCTAssertNil(AppTab.route("guard")?.judgments)
        XCTAssertEqual(AppTab.route("now")?.tab, .now)
        XCTAssertNil(AppTab.route("nowhere"))
        XCTAssertEqual(AppTab.allCases.map(\.rawValue), ["now", "guard", "judgments", "sources", "me"])
    }

    func testTheHeaderSaysWhatTheOnlineChecksSend() {
        var s = OnlinePhishingSettings()
        s.domainFacts = true
        s.safeBrowsing = true
        let line = GuardHeader.onlineLine(s)
        XCTAssertTrue(line.hasPrefix("The watchers send nothing."), line)
        XCTAssertTrue(line.contains("one link domain at a time"), line)
        XCTAssertTrue(line.contains("4-byte hash prefixes"), line)
    }

    // MARK: Over the sample

    private static let sample: [SourceItem] = {
        let root = SourcesService.bundledSample()!
        let scanner = SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                    zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                    limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
        return try! scanner.scan(sources: SourcesService.sampleRoots(root), observer: Quiet()).items
    }()

    private final class Quiet: NSObject, ScanObserver {
        func onProgress(progress: ScanProgress) {}
        func isCancelled() -> Bool { false }
    }

    private let today: Date = {
        var c = DateComponents(); c.year = 2026; c.month = 9; c.day = 23; c.hour = 12
        return Calendar(identifier: .gregorian).date(from: c)!
    }()

    private func service() -> (WatchersService, URL, UserDefaults, String) {
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeGuard-\(UUID().uuidString)")
        let suite = "GuardModelTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let s = WatchersService(ledger: LedgerService(home: home), items: { GuardModelTests.sample },
                                model: FakeModel(installed: false), seen: defaults)
        return (s, home, defaults, suite)
    }

    func testTheSampleFillsTheGuard() async throws {
        let (s, home, defaults, suite) = service()
        defer { try? FileManager.default.removeItem(at: home); defaults.removePersistentDomain(forName: suite) }
        await s.run(today: today)
        let summary = try XCTUnwrap(s.summary)
        XCTAssertNotNil(s.lastRun)
        XCTAssertNil(s.progress.state, "the strip clears when the run ends")

        // Expiring soon: the passport, 113 days out, later this year, type locked without the model.
        let passport = try XCTUnwrap(summary.expiries.first { $0.itemName.contains("passport") })
        XCTAssertEqual(passport.daysRemaining, 113)
        XCTAssertEqual(GuardModel.ExpiryBucket.of(daysLeft: passport.daysRemaining), .later)
        XCTAssertNil(passport.documentType)
        XCTAssertEqual(GuardModel.modelHalf(modelRan: summary.modelRan, modelReady: false, turnedOff: false), .locked)

        // Subscriptions: Streamflix with its four receipts and a total that adds up.
        let rows = GuardModel.sortedSubscriptions(summary.census.rows)
        let streamflix = try XCTUnwrap(rows.first { $0.merchant == "Streamflix" })
        XCTAssertEqual(streamflix.itemIds.count, 4)
        XCTAssertEqual(GuardModel.monthlyTotal(rows), summary.census.monthlyTotalMinor)

        // Now shows two, Guard the rest.
        XCTAssertEqual(s.nowFindings().count, 2)
        XCTAssertEqual(Set(s.nowFindings().map(\.key)).count, 2)
    }

    func testNotASubscriptionIsWrittenAndSurvivesARerunAndUndoRestoresIt() async throws {
        let (s, home, defaults, suite) = service()
        defer { try? FileManager.default.removeItem(at: home); defaults.removePersistentDomain(forName: suite) }
        await s.run(today: today)
        let total = try XCTUnwrap(s.summary?.census.monthlyTotalMinor)
        let streamflix = try XCTUnwrap(s.summary?.census.rows.first { $0.merchant == "Streamflix" })

        s.answer(streamflix, .notRelevant)
        XCTAssertNil(s.summary?.census.rows.first { $0.merchant == "Streamflix" })
        XCTAssertEqual(s.summary?.census.monthlyTotalMinor, total - 999)
        XCTAssertNotNil(s.lastSetAsideRow)

        await s.run(today: today)
        XCTAssertNil(s.summary?.census.rows.first { $0.merchant == "Streamflix" }, "the answer is in the corrections log")
        XCTAssertEqual(s.summary?.census.setAside, 1)

        s.answer(try XCTUnwrap(s.summary?.census.rows.first), .confirmed)
        XCTAssertEqual(s.summary?.census.rows.first?.verdict, .confirmed)

        // Undo (of the last set-aside) needs one: set CloudBox aside, then undo it.
        let cloud = try XCTUnwrap(s.summary?.census.rows.first { $0.merchant == "CloudBox" })
        s.answer(cloud, .dismissed)
        XCTAssertNil(s.summary?.census.rows.first { $0.merchant == "CloudBox" })
        s.undoSetAside()
        XCTAssertNotNil(s.summary?.census.rows.first { $0.merchant == "CloudBox" })
    }

    func testDismissingTheExpiryFindingTakesItOffTheTimeline() async throws {
        let (s, home, defaults, suite) = service()
        defer { try? FileManager.default.removeItem(at: home); defaults.removePersistentDomain(forName: suite) }
        await s.run(today: today)
        let finding = try XCTUnwrap(s.findings.first { $0.watcher == .expiry && $0.itemName.contains("passport") })
        s.answer(finding, .dismissed)
        XCTAssertFalse(s.summary?.expiries.contains { $0.findingKey == finding.key } ?? true)
        s.undoSetAside()
        XCTAssertTrue(s.summary?.expiries.contains { $0.findingKey == finding.key } ?? false, "Undo puts the row back")
        XCTAssertEqual(s.summary?.expiries.map(\.daysRemaining), s.summary?.expiries.map(\.daysRemaining).sorted())
    }
}
