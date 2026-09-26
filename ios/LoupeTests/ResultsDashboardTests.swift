import XCTest
import LoupeKit
@testable import Loupe

/// The results dashboard's aggregation (ResultsIndex), its correction path through JudgmentsService, and its
/// performance at 10,000 items.
@MainActor
final class ResultsDashboardTests: XCTestCase {
    private let options = ["yes", "no"]

    private func rec(_ id: String, top: Int, mass: Double, acted: Bool? = nil, unusable: Bool = false, check: String? = nil,
                     source: String = "files", kind: String = "PDF", day: String? = "2026-03-02", name: String? = nil,
                     correction: Int? = nil) -> ResultRecord {
        ResultRecord(itemId: id, name: name ?? id, source: source, kind: kind, day: day, top: top, mass: mass,
                     acted: acted ?? (mass >= 0.8), unusable: unusable, check: check, correctable: 0b11, correction: correction)
    }

    /// 2 model yes, 1 model no, 2 unsure, 1 rule no, 1 unusable, 1 corrected (unsure → yes).
    private func sample() -> ResultsIndex {
        ResultsIndex(options: options, threshold: 0.8, records: [
            rec("a", top: 0, mass: 0.95, source: "files", day: "2026-01-10", name: "Tesco receipt"),
            rec("b", top: 0, mass: 0.85, source: "mail", kind: "email", day: "2026-02-11", name: "Uber trip"),
            rec("c", top: 1, mass: 0.90, source: "photos", kind: "image", day: nil, name: "Garden photo"),
            rec("d", top: 0, mass: 0.62, source: "files", day: "2026-02-01", name: "Pret order"),
            rec("e", top: 1, mass: 0.55, source: "mail", kind: "email", day: "2026-02-20", name: "Café newsletter"),
            rec("f", top: 1, mass: 1.0, check: "no-transaction-evidence", source: "files", day: "2026-01-05", name: "Notes"),
            rec("g", top: 0, mass: 0.5, acted: false, unusable: true, source: "files", day: "2026-03-01", name: "Broken"),
            rec("h", top: 1, mass: 0.7, source: "mail", kind: "email", day: "2026-03-03", name: "Refund", correction: 0),
        ])
    }

    // MARK: Counts and percentages

    func testBucketsAnswerersAndBreakdowns() {
        let s = sample().summary
        XCTAssertEqual(s.total, 8)
        XCTAssertEqual(s.count(.answer(0)), 3, "two model yes + one corrected to yes")
        XCTAssertEqual(s.count(.answer(1)), 1)
        XCTAssertEqual(s.count(.unsure), 2)
        XCTAssertEqual(s.count(.rule(1)), 1)
        XCTAssertEqual(s.count(.unusable), 1)
        XCTAssertEqual(s.orderedBuckets, [.unusable, .unsure, .answer(0), .answer(1), .rule(1)])
        XCTAssertEqual(s.count(.model), 3)
        XCTAssertEqual(s.count(.you), 1)
        XCTAssertEqual(s.count(.rule), 1)
        XCTAssertEqual(s.count(.waiting), 2)
        XCTAssertEqual(s.count(.failed), 1)
        XCTAssertEqual(s.checks, ["no-transaction-evidence": 1])
        XCTAssertEqual(s.sources["files"]?.total, 4)
        XCTAssertEqual(s.sources["mail"]?.total, 3)
        XCTAssertEqual(s.sources["mail"]?.buckets[.unsure], 1)
        XCTAssertEqual(s.kinds["email"]?.total, 3)
        XCTAssertEqual(s.months["2026-02"]?.total, 3)
        XCTAssertEqual(s.undated, 1)
    }

    func testPercentagesAlwaysSumTo100() {
        XCTAssertEqual(ResultsIndex.percentages([1, 1, 1]), [34, 33, 33])
        XCTAssertEqual(ResultsIndex.percentages([2, 1]), [67, 33])
        XCTAssertEqual(ResultsIndex.percentages([0, 0]), [0, 0])
        XCTAssertEqual(ResultsIndex.percentages([9_998, 1, 1]), [100, 0, 0])
        XCTAssertEqual(ResultsIndex.percentages([7]), [100])
        var g = SplitMix(seed: 3)
        for _ in 0..<500 {
            let n = 2 + Int(g.next() % 8)
            let counts = (0..<n).map { _ in Int(g.next() % 5000) }
            let p = ResultsIndex.percentages(counts)
            if counts.reduce(0, +) > 0 { XCTAssertEqual(p.reduce(0, +), 100, "\(counts)") }
            for (c, v) in zip(counts, p) where c == 0 { XCTAssertEqual(v, 0) }
        }
        let s = sample().summary
        XCTAssertEqual(ResultsIndex.percentages(s.orderedBuckets.map { s.count($0) }).reduce(0, +), 100)
    }

    // MARK: Histogram

    func testHistogramBinsAgainstTheThreshold() {
        let yesNo = ConfidenceBins(options: 2)
        XCTAssertEqual(yesNo.lower, 0.5, accuracy: 1e-9)
        XCTAssertEqual(yesNo.binCount, 10)
        XCTAssertEqual(yesNo.bin(for: 0.5), 0)
        XCTAssertEqual(yesNo.bin(for: 0.8), 6, "the threshold opens its own bin: 80% is at or above an 80% threshold")
        XCTAssertEqual(yesNo.bin(for: 0.7999), 5)
        XCTAssertEqual(yesNo.bin(for: 0.85), 7, "0.85 is not pulled into the 80% bin by floating point")
        XCTAssertEqual(yesNo.bin(for: 1.0), 9, "1.0 falls in the last bin")
        XCTAssertEqual(yesNo.range(6).lo, 0.8, accuracy: 1e-9)
        XCTAssertEqual(yesNo.borderline(threshold: 0.8), 4...7, "70% to 90%")

        let pick = ConfidenceBins(options: 8)
        XCTAssertEqual(pick.lower, 0.1, accuracy: 1e-9)
        XCTAssertEqual(pick.binCount, 18)
        XCTAssertEqual(pick.bin(for: 0.13), 0)

        let index = sample()
        XCTAssertEqual(index.bins.total, 6, "model answers only (a corrected one included): no rule, no unusable")
        XCTAssertEqual(index.bins.counts[index.bins.bin(for: 0.95)][0], 1)
        XCTAssertEqual(index.bins.counts[index.bins.bin(for: 0.7)][1], 1, "a corrected item keeps what the model said")
        // Every model answer below the threshold sits in a bin that ends at or below it.
        for r in index.records where r.isModel {
            let (lo, hi) = index.bins.range(index.bins.bin(for: r.mass))
            XCTAssertTrue(r.mass >= lo - 1e-9 && (r.mass < hi || hi == 1))
            if r.mass < 0.8 { XCTAssertLessThanOrEqual(hi, 0.8 + 1e-9) }
        }
        XCTAssertEqual(index.borderlineCount, 2, "0.85 and 0.70 are within 10 points of 0.8; 0.90, 0.62 and 0.55 are not")
    }

    // MARK: Filters, search, sort

    private func ids(_ index: ResultsIndex, _ f: ResultsFilter, _ sort: ResultsSort = .confidence) -> [String] {
        index.filtered(f, sort: sort).map { index.records[Int($0)].itemId }
    }

    func testFilterCombinations() {
        let index = sample()
        XCTAssertEqual(Set(ids(index, ResultsFilter(bucket: .unsure))), ["d", "e"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(bucket: .answer(0)))), ["a", "b", "h"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(bucket: .answer(0), source: "mail"))), ["b", "h"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(answerer: .you))), ["h"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(answerer: .rule, check: "no-transaction-evidence"))), ["f"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(check: "exact-duplicate"))), [])
        XCTAssertEqual(Set(ids(index, ResultsFilter(bins: index.borderlineBins))), ["b", "h"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(bins: index.borderlineBins, source: "files"))), [])
        XCTAssertEqual(Set(ids(index, ResultsFilter(kind: "email", month: "2026-02"))), ["b", "e"])
        XCTAssertEqual(Set(ids(index, ResultsFilter(month: "2026-01"))), ["a", "f"])
        XCTAssertEqual(ids(index, ResultsFilter()).count, 8)
    }

    func testSearchIsFoldedAndIncremental() {
        let index = sample()
        XCTAssertEqual(ids(index, ResultsFilter(query: "cafe")), ["e"], "diacritics folded")
        XCTAssertEqual(ids(index, ResultsFilter(query: "  TESCO ")), ["a"], "case folded, trimmed")
        XCTAssertEqual(Set(ids(index, ResultsFilter(query: "mail"))), ["b", "e", "h"], "the source name is searchable")
        // Typing letter by letter narrows; deleting comes back; each answer equals a fresh index's.
        var f = ResultsFilter(bucket: nil, source: "files")
        for q in ["", "p", "pr", "pre", "pret", "pre", "p", ""] {
            f.query = q
            XCTAssertEqual(ids(index, f), ids(sample(), f), "query '\(q)'")
        }
        f.query = "zzz"
        XCTAssertTrue(ids(index, f).isEmpty)
    }

    func testSortOrders() {
        let index = sample()
        XCTAssertEqual(ids(index, ResultsFilter(), .confidence).first, "g", "unusable first, then least sure")
        XCTAssertEqual(Array(ids(index, ResultsFilter(), .confidence).dropFirst().prefix(2)), ["e", "d"])
        XCTAssertEqual(ids(index, ResultsFilter(), .date).first, "h", "newest first")
        XCTAssertEqual(ids(index, ResultsFilter(), .date).last, "c", "undated last")
        XCTAssertEqual(ids(index, ResultsFilter(), .name).prefix(3), ["g", "e", "c"], "Broken, Café, Garden")
        let sections = index.sections(index.filtered(ResultsFilter(), sort: .confidence))
        XCTAssertEqual(sections.map(\.bucket), [.unusable, .unsure, .answer(0), .answer(1), .rule(1)])
        XCTAssertEqual(sections[1].rows.map { index.records[Int($0)].itemId }, ["e", "d"], "sort order kept inside a group")
    }

    // MARK: Corrections move one record

    func testCorrectionUpdatesCountsIncrementally() {
        let index = sample()
        let d = index.index(of: "d")!
        _ = ids(index, ResultsFilter(bucket: .unsure))          // warm the filter cache
        index.setCorrection(d, 1)
        XCTAssertEqual(index.summary.count(.unsure), 1)
        XCTAssertEqual(index.summary.count(.answer(1)), 2)
        XCTAssertEqual(index.summary.count(.you), 2)
        XCTAssertEqual(index.summary.count(.waiting), 1)
        XCTAssertEqual(Set(ids(index, ResultsFilter(bucket: .unsure))), ["e"], "the cache is not stale after a correction")
        // A rule answer corrected: it leaves the rule counts.
        index.setCorrection(index.index(of: "f")!, 0)
        XCTAssertNil(index.summary.checks["no-transaction-evidence"])
        XCTAssertEqual(index.summary.count(.rule(1)), 0)
        // Equal to a from-scratch count of the same records.
        XCTAssertEqual(index.summary, ResultsIndex(options: options, threshold: 0.8, records: index.records).summary)
        // Undo back to nothing: the original summary exactly.
        index.setCorrection(d, nil)
        index.setCorrection(index.index(of: "f")!, nil)
        XCTAssertEqual(index.summary, sample().summary)
    }

    // MARK: Through the service: the queue's correction records

    private var home: URL!
    override func setUp() { home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeResults-\(UUID().uuidString)") }
    override func tearDown() { if let home { try? FileManager.default.removeItem(at: home) } }

    private func item(_ id: String, _ text: String, source: String = "sample", date: String? = nil) -> SourceItem {
        SourceItem(id: id, sourceId: source, kind: .text, path: "/s/\(id)", messageIndex: nil, name: id, text: text,
                   hasText: true, textTruncated: false, sizeBytes: Int64(text.count), contentHash: "h-\(id)",
                   mime: "text/plain", date: date.flatMap { PhoneItems.companion.day(iso: $0) }, dateOrigin: nil, email: nil,
                   facts: [:], duplicateOf: nil)
    }

    func testCorrectionGoesToTheLedgerAndUpdatesTheSummary() async throws {
        let list = [item("r1", "Receipt: total paid £4.20, Visa ending 4242"), item("r2", "Receipt for your order, amount paid £9"),
                    item("n1", "Order confirmation, paid by card"), item("x", "Lunch plans for the weekend")]
        let s = JudgmentsService(ledger: LedgerService(home: home), items: { list }, model: FakeModel(installed: true, backend: FakeJudgmentBackend(p: 0.6)))
        s.load()
        guard case .success(let id) = s.useTemplate("is-receipt") else { return XCTFail() }
        await s.startSweep(id)
        let m = ResultsModel(judgmentId: id)
        await m.refresh(s)
        let index = try XCTUnwrap(m.index)
        XCTAssertEqual(index.count, 4)
        XCTAssertEqual(index.summary.count(.rule(1)), 1, "no payment words: answered no by rule")
        XCTAssertEqual(index.summary.count(.unsure), 3, "0.6 is below the threshold")
        XCTAssertNotNil(JudgmentRunLog.last(id), "a run is stamped")

        // Correct one unsure item to the first option.
        let r1 = Int32(index.index(of: "r1")!)
        m.correct([r1], to: 0, service: s)
        XCTAssertEqual(m.index?.summary.count(.unsure), 2)
        XCTAssertEqual(m.index?.summary.count(.you), 1)
        let j = s.judgment(id)!
        let key = CorrectionKey(judgmentId: id, criteriaHash: j.criteriaHash, itemId: "r1")
        XCTAssertEqual(s.corrections[key], j.shape.candidates[0])
        s.ledger.flush()
        XCTAssertEqual(s.ledger.correctionIndex()[key], j.shape.candidates[0], "written to the corrections log")
        XCTAssertEqual(s.measure(j).corrections, 1, "Measure counts it, as a queue answer")
        XCTAssertFalse(s.unsure(judgmentId: id).contains { $0.itemId == "r1" }, "it leaves the queue")

        // Bulk: confirm the rest (the model's lean), then undo all of them at once.
        let rest = ["r2", "n1"].map { Int32(index.index(of: $0)!) }
        m.correct(rest, to: nil, service: s)
        XCTAssertEqual(m.index?.summary.count(.unsure), 0)
        XCTAssertEqual(m.index?.summary.count(.you), 3)
        m.undoLast(service: s)
        XCTAssertEqual(m.index?.summary.count(.unsure), 2)
        m.undoLast(service: s)
        XCTAssertEqual(m.index?.summary.count(.unsure), 3)
        s.ledger.flush()
        XCTAssertNil(s.ledger.correctionIndex()[key], "undo appends a retraction")

        // A correction made in the queue shows up when the dashboard comes back.
        let entry = try XCTUnwrap(s.unsure(judgmentId: id).first { $0.itemId != "x" })
        s.answer(entry, label: j.shape.candidates[1])
        await m.refresh(s)
        XCTAssertEqual(m.index?.summary.count(.unsure), 2)
        XCTAssertEqual(m.index?.records[m.index!.index(of: entry.itemId)!].correction, 1)

        // Filter + chip removal on the model.
        m.toggle(bucket: .unsure)
        XCTAssertEqual(m.shown, 2)
        XCTAssertEqual(m.chips.map(\.id), ["bucket"])
        m.remove(chip: "bucket")
        XCTAssertEqual(m.shown, 4)
    }

    // MARK: Performance at 10,000 items

    private func synthetic(_ n: Int) -> [ResultRecord] {
        let sources = ["files", "mail", "photos", "sample", "inbox"]
        let kinds = ["PDF", "email", "image", "text", "CSV"]
        var g = SplitMix(seed: 42)
        return (0..<n).map { i in
            let mass = 0.5 + Double(g.next() % 500) / 1000
            let rule = g.next() % 4 == 0
            return ResultRecord(itemId: "i\(i)", name: "Item \(g.next() % 100_000) receipt \(i)", source: sources[i % 5],
                                kind: kinds[Int(g.next() % 5)], day: String(format: "2026-%02d-%02d", 1 + i % 12, 1 + i % 28),
                                top: Int(g.next() % 2), mass: rule ? 1 : mass, acted: rule || mass >= 0.8, unusable: false,
                                check: rule ? "no-transaction-evidence" : nil, correctable: 0b11)
        }
    }

    private func ms(_ block: () -> Void) -> Double {
        let t0 = CFAbsoluteTimeGetCurrent()
        block()
        return (CFAbsoluteTimeGetCurrent() - t0) * 1000
    }

    func testTenThousandItemsIndexFilterAndCorrect() {
        let records = synthetic(10_000)
        var index: ResultsIndex!
        let build = ms { index = ResultsIndex(options: options, threshold: 0.8, records: records) }
        XCTAssertEqual(index.summary.total, 10_000)
        let tap = ms { _ = index.sections(index.filtered(ResultsFilter(bucket: .unsure), sort: .confidence)) }
        let facets = ms { _ = index.filtered(ResultsFilter(bucket: .answer(0), source: "mail", month: "2026-03"), sort: .date) }
        var keys: [Double] = []
        var f = ResultsFilter()
        for q in ["r", "re", "rec", "rece", "recei", "receip", "receipt", "receipt ", "receipt 9"] {
            f.query = q
            keys.append(ms { _ = index.sections(index.filtered(f, sort: .confidence)) })
        }
        let correct = ms { index.setCorrection(17, 1) }
        let refilter = ms { _ = index.sections(index.filtered(ResultsFilter(bucket: .unsure), sort: .confidence)) }
        let report = String(format: "LOUPE-PERF index10k build=%.1fms tap=%.2fms facets=%.2fms keystroke(max)=%.2fms keystroke(avg)=%.2fms correct=%.3fms refilter=%.2fms",
                            build, tap, facets, keys.max()!, keys.reduce(0, +) / Double(keys.count), correct, refilter)
        print(report)
        XCTContext.runActivity(named: report) { _ in }
        // Budgets on the simulator (a frame is 16 ms): generous, to catch an accidental O(n²) not noise.
        XCTAssertLessThan(build, 1500)
        XCTAssertLessThan(tap, 50)
        XCTAssertLessThan(keys.max()!, 50)
        XCTAssertLessThan(correct, 5)
    }

    /// End to end with the real engine and ledger: 10,000 fixture items judged by the stand-in scorer, then the
    /// shared selection (`JudgmentResults.rows`) and the index built from it — what the screen waits for.
    func testTenThousandItemsFromTheLedger() async throws {
        let items = ResultsFixture.makeItems(10_000)
        let s = JudgmentsService(ledger: LedgerService(home: home), items: { items }, model: FakeModel(installed: false))
        s.load()
        guard case .success(let id) = s.useTemplate("is-receipt") else { return XCTFail() }
        let j = s.judgment(id)!
        let plan = JudgmentResults.shared.plan(all: [], judgment: j, items: items, rerunAll: true)
        let ledger = s.ledger
        let t0 = CFAbsoluteTimeGetCurrent()
        _ = JudgmentSweep(backend: FixtureResultsBackend()).run(judgment: j, plan: plan,
                                                               observer: SweepBridge(progress: { _ in }, rows: { ledger.record($0) }),
                                                               autoBaseline: false)
        ledger.flush()
        let sweep = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        s.refreshLedger()
        let m = ResultsModel(judgmentId: id)
        let t1 = CFAbsoluteTimeGetCurrent()
        await m.refresh(s)
        let build = (CFAbsoluteTimeGetCurrent() - t1) * 1000
        let index = try XCTUnwrap(m.index)
        XCTAssertEqual(index.count, 10_000)
        XCTAssertGreaterThan(index.summary.count(.rule(1)), 0, "the payment-evidence rule answered the notes")
        XCTAssertGreaterThan(index.summary.count(.unsure), 0)
        let t2 = CFAbsoluteTimeGetCurrent()
        m.toggle(bucket: .unsure)
        let tap = (CFAbsoluteTimeGetCurrent() - t2) * 1000
        let first = m.sections.first!.rows.first!
        let t3 = CFAbsoluteTimeGetCurrent()
        m.correct([first], to: 1, service: s)
        let correct = (CFAbsoluteTimeGetCurrent() - t3) * 1000
        let report = String(format: "LOUPE-PERF ledger10k sweep=%.0fms build(rows+records+index)=%.0fms tap=%.1fms correct+refilter=%.1fms",
                            sweep, build, tap, correct)
        print(report)
        XCTContext.runActivity(named: report) { _ in }
        XCTAssertLessThan(tap, 50)
        XCTAssertLessThan(correct, 100)
    }
}
