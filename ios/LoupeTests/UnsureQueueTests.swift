import XCTest
import LoupeKit
@testable import Loupe

/// The Unsure queue and Measure screen's view-model half (epic #7 child 4), over a fake model.
@MainActor
final class UnsureQueueTests: XCTestCase {
    private var home: URL!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeQueue-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func item(_ id: String, _ text: String) -> SourceItem {
        SourceItem(id: id, sourceId: "sample", kind: .text, path: "/s/\(id)", messageIndex: nil, name: id, text: text,
                   hasText: true, textTruncated: false, sizeBytes: Int64(text.count), contentHash: "h-\(id)",
                   mime: "text/plain", date: nil, dateOrigin: nil, email: nil, facts: [:], duplicateOf: nil)
    }

    private lazy var items: [SourceItem] = (0..<64).map { i in item("i\(i)", i % 2 == 0 ? "receipt total paid \(i)" : "lunch, about the payment \(i)") }

    private func service() -> JudgmentsService {
        let list = items
        let s = JudgmentsService(ledger: LedgerService(home: home), items: { list }, model: FakeModel(installed: true, backend: FakeJudgmentBackend(p: 0.7)))
        s.load()
        return s
    }

    private func swept() async -> (JudgmentsService, String) {
        let s = service()
        guard case .success(let id) = s.useTemplate("is-receipt") else { XCTFail("refused"); return (s, "") }
        await s.startSweep(id)
        await s.settleQueue()   // the queue is drawn in the background
        return (s, id)
    }

    func testAnsweringDropsTheCountAndUndoRestoresIt() async throws {
        let (s, _) = await swept()
        let before = try XCTUnwrap(s.needsYou)
        XCTAssertGreaterThan(before, 0)
        XCTAssertTrue(s.unsure().contains { $0.isAudit }, "the random audit arm is present")
        let first = s.unsure()[0]
        s.answer(first, label: first.modelPick)
        XCTAssertEqual(s.needsYou, before - 1)
        XCTAssertFalse(s.unsure().contains { $0.itemId == first.itemId && $0.judgment.id == first.judgment.id })
        XCTAssertTrue(s.canUndo)
        s.undoLastAnswer()
        XCTAssertEqual(s.needsYou, before)
        XCTAssertFalse(s.canUndo)
    }

    func testAnswersAreKeyedByCriteriaHashAndSurviveARestart() async {
        let (s, id) = await swept()
        let e = s.unsure()[0]
        let other = e.options.map { $0.first! as String }.first { $0 != e.modelPick }!
        s.answer(e, label: other)
        s.ledger.flush()
        let again = service()
        again.refreshLedger()
        let j = again.judgment(id)!
        XCTAssertEqual(again.corrections[CorrectionKey(judgmentId: id, criteriaHash: j.criteriaHash, itemId: e.itemId)], other)
        XCTAssertEqual(again.measure(j).corrections, 1)
        // Rewording re-keys: under the new hash nothing counts.
        again.setCriteriaInPrompt(id, on: true)
        XCTAssertEqual(again.measure(again.judgment(id)!).corrections, 0)
    }

    func testMeasureIsGatedAndMeNeedsTenCorrections() async {
        let (s, id) = await swept()
        let j = s.judgment(id)!
        XCTAssertNotNil(s.measure(j).gateMessage)
        XCTAssertNil(s.overall().agreement)
        XCTAssertTrue(s.overall().line.hasPrefix("10 more correction(s)"))
        for e in s.unsure().prefix(10) { s.answer(e, label: e.modelPick) }
        XCTAssertEqual(s.overall().line, "Agrees with you 100% over 10 corrections")
        XCTAssertNotNil(s.measure(j).gateMessage, "calibration waits for 30")
        XCTAssertNotNil(s.baseline(j))
    }

    func testSliderPreviewsAndApplyWritesTheThreshold() async {
        let (s, id) = await swept()
        let j = s.judgment(id)!
        let p = s.preview(j, candidate: 0.5)
        XCTAssertGreaterThan(p.preview.additionalActions, 0)
        XCTAssertTrue(p.line.contains("more acted on"))
        s.setThreshold(id, 0.5)
        s.setUseBaseline(id, true)
        s.ledger.flush()
        let again = service()
        XCTAssertEqual(again.judgment(id)?.threshold, 0.5)
        XCTAssertEqual(again.judgment(id)?.useBaseline, true)
    }

    // MARK: 2026-09-28 launch hang: the count is drawn off the main thread, never in a view body

    func testNeedsYouIsNilUntilTheFirstDrawAndReadingItIsFree() async {
        let s = service()
        XCTAssertNil(s.needsYou, "before any draw: shown as a dash, not zero")
        let start = Date()
        for _ in 0..<10_000 { _ = s.needsYou; _ = s.unsure() }
        XCTAssertLessThan(Date().timeIntervalSince(start), 0.1, "reading the count does no work")
        await s.settleQueue()
        XCTAssertEqual(s.needsYou, 0, "an empty ledger has nothing waiting")
    }

    func testThreeThousandLongRowsAreCountedOffTheMainThreadQuickly() async throws {
        final class Draws: @unchecked Sendable {
            let lock = NSLock()
            var list: [(onMain: Bool, seconds: Double)] = []
            func add(_ d: (Bool, Double)) { lock.lock(); list.append(d); lock.unlock() }
            var all: [(onMain: Bool, seconds: Double)] { lock.lock(); defer { lock.unlock() }; return list }
        }
        let draws = Draws()
        JudgmentsService.drawObserver = { draws.add(($0, $1)) }
        defer { JudgmentsService.drawObserver = nil }

        let s = JudgmentsService(ledger: LedgerService(home: home), items: { [] }, model: FakeModel(installed: true, backend: FakeJudgmentBackend(p: 0.7)))
        await s.seedLargeLedger(count: 3_000)   // 20–50 KB each, every one a model answer the gate must re-check
        XCTAssertEqual(s.rows.count, 3_000)
        XCTAssertGreaterThanOrEqual(s.sampleItems().map(\.text.count).min() ?? 0, 20_000)

        // While the (cold) draw runs, the main actor keeps running: ticks land on time.
        var worstTick = 0.0
        while s.drawingQueue {
            let t = Date()
            try await Task.sleep(nanoseconds: 10_000_000)
            worstTick = max(worstTick, Date().timeIntervalSince(t))
        }
        await s.settleQueue()
        XCTAssertEqual(s.needsYou, Int(JudgmentMeasure.shared.QUEUE_SIZE))
        XCTAssertLessThan(worstTick, 0.25, "the main thread stayed free during the draw")
        let cold = try XCTUnwrap(draws.all.last)
        XCTAssertFalse(cold.onMain, "the draw ran off the main thread")
        XCTAssertTrue(draws.all.allSatisfy { !$0.onMain })

        // A redraw (a run added rows, a judgment changed) reads no text again: well under a second.
        s.scheduleQueueDraw()
        await s.settleQueue()
        let warm = try XCTUnwrap(draws.all.last)
        print("UNSURE-DRAW 3000 rows cold=\(Int(cold.seconds * 1000))ms warm=\(Int(warm.seconds * 1000))ms")
        XCTAssertLessThan(warm.seconds, 1.0)
        XCTAssertLessThan(cold.seconds, 3.0)
        XCTAssertEqual(s.needsYou, Int(JudgmentMeasure.shared.QUEUE_SIZE))
    }
}
