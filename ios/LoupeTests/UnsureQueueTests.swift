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
        return (s, id)
    }

    func testAnsweringDropsTheCountAndUndoRestoresIt() async {
        let (s, _) = await swept()
        let before = s.needsYou
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
}
