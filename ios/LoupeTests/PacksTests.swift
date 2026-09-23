import XCTest
import LoupeKit
@testable import Loupe

/// Preset packs (epic #7 child 14): the bundled example, Station's validation, the lint per
/// question, conflicts by id, and export → import round-trip.
@MainActor
final class PacksTests: XCTestCase {
    private var home: URL!
    private var judgments: JudgmentsService!
    private var packs: PacksService!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupePacks-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        let ledger = LedgerService(home: home)
        judgments = JudgmentsService(ledger: ledger, items: { [] }, model: FakeModel(installed: false))
        judgments.load()
        let j = judgments!
        let review = ReviewService(home: home.appendingPathComponent("review"), ledger: ledger, privacy: { PrivacyService.shared },
                                   mail: { MailTriageService.shared }, watchers: { WatchersService.shared }, judgments: { j })
        packs = PacksService(judgments: { j }, review: { review })
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    func testTheExamplePackIsBundledLabelledAndPreviewedBeforeAdding() throws {
        XCTAssertNotNil(PacksService.bundledExample())
        packs.openExample()
        let p = try XCTUnwrap(packs.preview)
        XCTAssertTrue(p.example)
        XCTAssertEqual(p.pack.slug, "bistro-cloud")
        XCTAssertEqual(p.plans.count, 29)
        XCTAssertEqual(p.addable.count, 14)
        XCTAssertTrue(p.refused.allSatisfy { !$0.problems.isEmpty })
        XCTAssertTrue(judgments.judgments.isEmpty, "a preview adds nothing")
        let r = try XCTUnwrap(packs.add(.skip))
        XCTAssertEqual(r.added.count, 14)
        XCTAssertEqual(judgments.judgments.count, 14)
        XCTAssertNil(packs.preview)
    }

    func testSameIdConflictsAreSkippedReplacedOrKeptBoth() throws {
        packs.openExample()
        packs.add(.skip)
        packs.openExample()
        XCTAssertEqual(try XCTUnwrap(packs.preview).conflicts, 14)
        XCTAssertEqual(packs.add(.skip)?.skipped.count, 14)
        XCTAssertEqual(judgments.judgments.count, 14)
        packs.openExample()
        XCTAssertEqual(packs.add(.replace)?.replaced.count, 14)
        XCTAssertEqual(judgments.judgments.count, 14)
        packs.openExample()
        packs.add(.keepBoth)
        XCTAssertEqual(judgments.judgments.count, 28)
        XCTAssertEqual(Set(judgments.judgments.map(\.id)).count, 28)
    }

    func testAnInvalidPackIsRefusedWithEveryProblemLocated() {
        packs.load(#"{"format": "something-else", "version": 2, "name": "", "presets": []}"#, example: false)
        XCTAssertNil(packs.preview)
        XCTAssertTrue(packs.problems.contains { $0.hasPrefix("format:") })
        XCTAssertTrue(packs.problems.contains { $0.hasPrefix("version:") })
        XCTAssertTrue(packs.problems.contains { $0.hasPrefix("presets:") })
        packs.load("not json", example: false)
        XCTAssertFalse(packs.problems.isEmpty)
    }

    func testExportThenImportRoundTrips() throws {
        packs.openExample()
        packs.add(.skip)
        judgments.create(EditorInput(
            title: "Receipt", question: "Is this a receipt?", shape: .yesNo, positive: "a receipt or proof of purchase",
            negative: "not a receipt", optionsText: "", bandsText: "", invariant: "", breaks: "", lookalikes: "",
            keywordsText: "", onFailure: TemplateLibrary.shared.DEFAULT_POSTURE, criteriaInPrompt: false))
        let before = judgments.judgments
        XCTAssertEqual(before.count, 15)
        let (url, export) = try packs.exportFile()
        XCTAssertEqual(Int(export.exported), 15)
        XCTAssertTrue(url.lastPathComponent.hasSuffix(".laya-pack.json"))
        packs.open(url)
        let p = try XCTUnwrap(packs.preview, packs.problems.joined(separator: "\n"))
        XCTAssertEqual(p.plans.map(\.judgmentId), before.map(\.id))
        XCTAssertTrue(p.plans.allSatisfy { $0.conflict && $0.addable })
        packs.add(.replace)
        XCTAssertEqual(judgments.judgments.map(\.id), before.map(\.id))
        XCTAssertEqual(judgments.judgments.map(\.question), before.map(\.question))
    }
}
