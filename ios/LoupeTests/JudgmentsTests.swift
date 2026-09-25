import XCTest
import LoupeKit
@testable import Loupe

/// Answers the first option with `p` when the item text mentions "receipt", else 1 - p.
final class FakeJudgmentBackend: NSObject, Backend {
    let p: Double
    var calls = 0
    var onScore: (() -> Void)?
    init(p: Double = 0.95) { self.p = p }
    func score(judgment: JudgmentChoice, state: TextState) -> Scored {
        calls += 1
        XCTAssertFalse(Thread.isMainThread, "Laya must never run on the main thread")
        onScore?()
        let first = state.text.lowercased().contains("receipt") ? p : 1 - p
        return Scored(masses: [judgment.candidates[0]: KotlinDouble(value: first), judgment.candidates[1]: KotlinDouble(value: 1 - first)],
                      modelContext: nil, optionCriteria: nil)
    }
}

@MainActor
final class FakeModel: JudgmentModelProvider {
    var isInstalled: Bool
    var failure: String?
    let fake: FakeJudgmentBackend
    init(installed: Bool, backend: FakeJudgmentBackend = FakeJudgmentBackend()) { isInstalled = installed; fake = backend }
    func backend() async -> Backend? { isInstalled ? fake : nil }
}

@MainActor
final class JudgmentsTests: XCTestCase {
    private var home: URL!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeJudgments-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func item(_ id: String, _ text: String, hasText: Bool = true) -> SourceItem {
        SourceItem(id: id, sourceId: "sample", kind: .text, path: "/s/\(id)", messageIndex: nil, name: id, text: text,
                   hasText: hasText, textTruncated: false, sizeBytes: Int64(text.count), contentHash: "h-\(id)",
                   mime: "text/plain", date: nil, dateOrigin: nil, email: nil, facts: [:], duplicateOf: nil)
    }

    private func service(installed: Bool = true, items: [SourceItem]? = nil, backend: FakeJudgmentBackend = FakeJudgmentBackend()) -> (JudgmentsService, FakeModel) {
        let model = FakeModel(installed: installed, backend: backend)
        let list = items ?? [item("r1", "Receipt for a donation"), item("n1", "Lunch plans, about the payment"), item("img", "", hasText: false)]
        let s = JudgmentsService(ledger: LedgerService(home: home), items: { list }, model: model)
        s.load()
        return (s, model)
    }

    func testUseTemplatePersistsAndReloads() throws {
        let (s, _) = service()
        XCTAssertTrue(s.judgments.isEmpty)
        guard case .success(let id) = s.useTemplate("tax-receipt") else { return XCTFail("refused") }
        XCTAssertEqual(id, "j-tax-receipt")
        guard case .success(let second) = s.useTemplate("tax-receipt") else { return XCTFail("refused") }
        XCTAssertEqual(second, "j-tax-receipt-2")
        s.ledger.flush()
        let (again, _) = service()
        XCTAssertEqual(again.judgments.map(\.id), ["j-tax-receipt", "j-tax-receipt-2"])
        XCTAssertEqual(again.judgments.first?.templateId, "tax-receipt")
    }

    func testTemplateWithParametersIsRefusedWithoutValues() {
        let (s, _) = service()
        guard case .failure(let r) = s.useTemplate("about-project") else { return XCTFail("should refuse") }
        XCTAssertFalse(r.reasons.isEmpty)
        XCTAssertTrue(s.judgments.isEmpty)
    }

    func testEditorLintsLiveAndRefusesBareYesNo() {
        let m = EditorModel()
        XCTAssertTrue(m.findings.isEmpty, "nothing flagged before a question is typed")
        XCTAssertFalse(m.canCreate)
        m.question = "Is this from my landlord?"
        m.positive = "yes"; m.negative = "no"
        XCTAssertTrue(m.findings.contains { $0.rule == "bare-yes-no" })
        m.positive = "from my landlord"; m.negative = "not from my landlord"
        XCTAssertTrue(m.findings.isEmpty, "\(m.findings)")
        XCTAssertTrue(m.canCreate)
        m.question = "Explain why this is from my landlord?"
        XCTAssertTrue(m.findings.contains { $0.rule == "asks-for-prose" })
        m.question = "How urgent is this?"
        m.shape = .score
        m.bandsText = "no deadline\nthis month\nthis week"
        XCTAssertTrue(m.canCreate, "\(m.findings)")
        XCTAssertEqual(m.compiledOptions, ["1", "2", "3"])
        XCTAssertFalse(m.criteriaInPrompt, "criteria-in-prompt is off by default")
    }

    func testCreateFromEditorWithCriteriaInPrompt() {
        let (s, _) = service()
        let m = EditorModel()
        m.question = "Is this from my landlord?"
        m.positive = "from my landlord"; m.negative = "not from my landlord"
        m.invariant = "sent by the landlord"
        m.criteriaInPrompt = true
        guard case .success(let id) = s.create(m.input) else { return XCTFail("refused") }
        XCTAssertEqual(s.judgment(id)?.criteriaInPrompt, true)
        let hash = s.judgment(id)!.criteriaHash
        s.setCriteriaInPrompt(id, on: false)
        XCTAssertNotEqual(s.judgment(id)!.criteriaHash, hash, "the model reads different text, so calibration restarts")
        XCTAssertTrue(s.notice?.contains("starts again") ?? false)
    }

    func testLibraryFiltersByCategoryAndSearch() {
        let lib = LibraryModel()
        XCTAssertEqual(lib.total, 55)
        XCTAssertEqual(lib.categories.count, 10)
        XCTAssertEqual(lib.categories.map(\.count).reduce(0, +), 55)
        XCTAssertEqual(lib.matches.count, 55)
        lib.category = lib.categories.first!.name
        XCTAssertEqual(lib.matches.count, lib.categories.first!.count)
        lib.category = nil
        lib.query = "tax"
        XCTAssertTrue(lib.matches.contains { $0.id == "tax-receipt" })
        lib.query = "zzzz-nothing"
        XCTAssertTrue(lib.matches.isEmpty)
    }

    func testSweepRunsOffMainWritesLedgerAndShowsResults() async {
        let (s, model) = service()
        guard case .success(let id) = s.useTemplate("tax-receipt") else { return XCTFail() }
        await s.startSweep(id)
        let end = try! XCTUnwrap(s.sweep)
        XCTAssertFalse(end.running)
        XCTAssertEqual(end.done, 2)
        XCTAssertEqual(end.withoutText, 1)
        XCTAssertEqual(model.fake.calls, 2)
        XCTAssertEqual(s.ledger.rows(judgmentId: id).count, 2, "every decision is a ledger row")
        let j = s.judgment(id)!
        let rows = s.results(j)
        XCTAssertEqual(rows.count, 2)
        XCTAssertTrue(rows.allSatisfy { $0.acted })
        XCTAssertEqual(s.counts(j).decisions, 2)
        // Already judged under this wording: a second run judges nothing new.
        await s.startSweep(id)
        XCTAssertEqual(s.sweep?.done, 0)
        XCTAssertEqual(s.sweep?.alreadyDecided, 2)
        XCTAssertEqual(model.fake.calls, 2)
    }

    func testUnsureRowsAreMarked() async {
        let (s, _) = service(backend: FakeJudgmentBackend(p: 0.6))
        guard case .success(let id) = s.useTemplate("tax-receipt") else { return XCTFail() }
        await s.startSweep(id)
        let j = s.judgment(id)!
        let rows = s.results(j)
        XCTAssertEqual(rows.count, 2)
        XCTAssertTrue(rows.allSatisfy { !$0.acted && !$0.unusable })
        XCTAssertTrue(rows[0].status(judgment: j).hasPrefix("unsure — leans"))
        XCTAssertEqual(s.counts(j).unsure, 2)
    }

    func testModelNotInstalledRunsNothingAndFakesNothing() async {
        let (s, model) = service(installed: false)
        guard case .success(let id) = s.useTemplate("tax-receipt") else { return XCTFail() }
        await s.checkModel()
        XCTAssertEqual(s.gate, .notInstalled)
        await s.startSweep(id)
        XCTAssertNil(s.sweep)
        XCTAssertEqual(model.fake.calls, 0)
        XCTAssertTrue(s.results(s.judgment(id)!).isEmpty)
        XCTAssertEqual(s.ledger.rows(judgmentId: id).count, 0)
    }

    func testCancelStopsBetweenItemsAndKeepsWhatRan() async {
        let many = (1...30).map { item("r\($0)", "receipt \($0)") }
        let backend = FakeJudgmentBackend()
        let (s, _) = service(items: many, backend: backend)
        guard case .success(let id) = s.useTemplate("tax-receipt") else { return XCTFail() }
        backend.onScore = { [weak backend] in
            if backend?.calls == 3 { Task { @MainActor in s.cancelSweep() } }
            Thread.sleep(forTimeInterval: 0.02)
        }
        await s.startSweep(id)
        let end = try! XCTUnwrap(s.sweep)
        XCTAssertTrue(end.cancelled)
        XCTAssertLessThan(end.done, 30)
        XCTAssertEqual(s.ledger.rows(judgmentId: id).count, Int(end.done), "what ran is saved")
    }
}
