import XCTest
import SwiftUI
import LoupeKit
@testable import Loupe

/// The live run view's models (LiveRunModel, ActivityCenter, ActStrings) and the privacy check's reports.
@MainActor
final class LiveRunTests: XCTestCase {
    private final class Clock: ActivityClock {
        var t = 1_000.0
        func unix() -> Double { t }
        func mono() -> Double { t }
    }

    private struct ReadOnly: PrivacyLocating {
        func access(for itemId: String) -> PrivacyAccess { .suggestOnly("read only") }
    }

    private func center(_ clock: Clock = Clock()) -> ActivityCenter {
        ActivityCenter(clock: clock, reference: { CostReference.companion.DEFAULT })
    }

    func testAScanJobBecomesTheFolderScanPipelineWithCountersCardsAndCost() {
        let c = center()
        let job = c.start("scan", title: "act.title.privacy", view: "scan", total: 4, stage: "act.stage.deciding")
        job.scanned(findings: 0, skipped: false, readContent: true)
        job.scanned(findings: 2, skipped: false, readContent: true)
        job.scanned(findings: 0, skipped: true, readContent: true)
        job.progress(3, of: 4)
        c.refresh()
        let snap = try! XCTUnwrap(c.latest("scan"))
        let m = LiveRunModel(snap, previous: nil, reference: CostReference.companion.DEFAULT)
        XCTAssertTrue(m.running)
        XCTAssertEqual(m.nodes.map(\.id), ["walk", "read", "q1", "q2", "q3", "rules", "evidence", "outcome", "review"])
        XCTAssertEqual(m.nodes.first { $0.active }?.id, "read", "deciding lights the reader")
        XCTAssertEqual(m.counters.first { $0.id == "read" }?.value, 2)
        XCTAssertEqual(m.counters.first { $0.id == "flagged" }?.value, 1)
        XCTAssertEqual(m.counters.first { $0.id == "to_you" }?.value, 1)
        XCTAssertEqual(m.counters.first { $0.id == "decisions" }?.value, 2)
        let q3 = try! XCTUnwrap(m.nodes.first { $0.id == "q3" })
        XCTAssertEqual(q3.question, "sensitive?")
        XCTAssertEqual(q3.answer, "yes · 100%")
        XCTAssertEqual(m.gates.map(\.count), [1, 0, 1, 1])
        XCTAssertEqual(m.shares.first { $0.id == "rule" }?.count, 2)
        XCTAssertEqual(m.poweredBy, "powered by the decision model · multilingual")
        XCTAssertEqual(m.mascot, .scanning)
        // 2 decisions × (700 × $2 + 60 × $10) / 1M = $0.004
        XCTAssertEqual(m.costCloud, "$0.0040")
        XCTAssertEqual(m.costLabel, "estimate · Claude Sonnet 5 list price as of 24 Sep 2026")
        XCTAssertEqual(m.costLocal, "on this device, free")
        XCTAssertTrue(m.statusLine.hasPrefix("Privacy check, Reading and deciding, 75%"), m.statusLine)
    }

    func testCustomPricesSayYourPriceAndDoneJobsEndHappyOrUnsure() {
        let c = center()
        let clean = c.start("judgments", title: "act.title.judgments", view: "judgments", total: 1)
        clean.decision("j:0000abcd", "yes", 0.9, src: "laya", model: "multilingual")
        clean.gate("accepted")
        clean.finish("done", "act.res.judgments", ["done": 1, "uncertain": 0])
        c.refresh()
        let mine = CostReference.companion.DEFAULT.withPrices(inputPerMtok: 3, outputPerMtok: 15, tokensIn: 700, tokensOut: 60)
        let m = LiveRunModel(try! XCTUnwrap(c.latest("judgments")), previous: nil, reference: mine) { q in q == "j:0000abcd" ? "Is it a receipt?" : nil }
        XCTAssertEqual(m.costLabel, "estimate · your price from Model settings")
        XCTAssertEqual(m.mascot, .happy)
        XCTAssertEqual(m.log.first?.question, "Is it a receipt?", "the device maps the opaque id back")
        XCTAssertNil(m.nodes.first { $0.active }, "a finished job lights no node")

        let doubtful = c.start("judgments", title: "act.title.judgments", view: "judgments")
        doubtful.gate("uncertain")
        doubtful.finish()
        c.refresh()
        XCTAssertEqual(LiveRunModel(c.latest("judgments")!, previous: nil, reference: mine).mascot, .thinking)
    }

    func testTheDockListsOnlyJobsWhoseScreenIsNotShown() {
        let c = center()
        c.start("feeds", title: "act.title.feeds", view: "protection")
        c.start("scan", title: "act.title.privacy", view: "scan")
        c.show("scan")
        c.refresh()
        XCTAssertEqual(c.offScreen.map(\.kind), ["feeds"])
        c.hide("scan")
        XCTAssertEqual(c.offScreen.count, 2)
    }

    func testTheModelLoadBannerAndCancel() {
        let c = center()
        var cancelled = false
        let load = c.start("model_load", title: "act.title.modelLoad", params: ["model": "multilingual"], view: "setup",
                           stage: "act.stage.loadingModel", expected: 6, cancel: { cancelled = true })
        c.refresh()
        let banner = try! XCTUnwrap(c.modelLoad)
        XCTAssertEqual(LiveRunModel.message(banner.title), "Loading the multilingual model")
        XCTAssertEqual(banner.expectedS?.doubleValue, 6)
        c.cancel(try! XCTUnwrap(load.id))
        XCTAssertTrue(cancelled)
        load.finish("cancelled")
        c.refresh()
        XCTAssertNil(c.modelLoad)
    }

    func testArabicCoversEveryKeyAndTheLoopMirrors() {
        for (key, pair) in ActStrings.table {
            XCTAssertFalse(pair.0.isEmpty, key)
            XCTAssertFalse(pair.1.isEmpty, key)
        }
        for k in ["act.poweredBy", "act.card.cost", "act.cost.onDevice", "act.kind.game", "act.title.privacy", "act.gate.flagged"] {
            XCTAssertTrue(ActStrings.has(k), k)
            XCTAssertNotEqual(ActStrings.table[k]?.0, ActStrings.table[k]?.1, "\(k) is translated")
        }
        XCTAssertEqual(MS.lang == .ar ? LayoutDirection.rightToLeft : .leftToRight, ActStrings.direction)
    }

    func testEveryLoopNodeHasCatalogueWords() {
        for kind in ActivityNames.shared.KINDS {
            guard let pipe = LiveRun.shared.pipeFor(kind: kind) else { continue }
            for n in pipe.nodes where !n.diamond {
                XCTAssertTrue(ActStrings.has(n.key!), "\(kind): \(n.key!)")
                XCTAssertTrue(ActStrings.has(n.sub!), "\(kind): \(n.sub!)")
            }
            XCTAssertTrue(ActStrings.has("act.kind.\(kind)"), kind)
        }
        for key in LiveRun.shared.STAGE_NODE.keys { XCTAssertTrue(ActStrings.has(key), key) }
    }

    /// Privacy: a real privacy check over the bundled sample reports counts only — no file name, path or text
    /// reaches the job.
    func testAPrivacyCheckReportsCountsAndNoNamesOrText() async throws {
        let root = try XCTUnwrap(SourcesService.bundledSample())
        let items = try SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                      zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                      limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
            .scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeLive-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: home) }
        let ledger = LedgerService(home: home)
        let s = PrivacyService(ledger: ledger, items: { items }, locator: ReadOnly(),
                               files: PrivacyFileActions(home: home), photos: PhotoKitDeleter())
        await s.run()
        await Task.yield()
        ActivityCenter.shared.refresh()
        let job = try XCTUnwrap(ActivityCenter.shared.latest("scan"))
        XCTAssertEqual(job.state, "done")
        let checked = items.filter { $0.kind != .contact }.count
        XCTAssertEqual(Int(job.counters["read"]?.int32Value ?? 0), checked)
        XCTAssertGreaterThan(Int(job.gates["flagged"]?.int32Value ?? 0), 0)
        let json = ActivityCenter.shared.snapshot.jsonText()
        for item in items {
            XCTAssertFalse(json.contains(item.fileName), "file name leaked: \(item.fileName)")
            XCTAssertFalse(json.contains(item.path), "path leaked")
            if item.text.count > 12 { XCTAssertFalse(json.contains(String(item.text.prefix(24))), "text leaked from \(item.fileName)") }
        }
        for f in s.findings { XCTAssertFalse(json.contains(f.title) && f.title.count > 12, "finding title leaked") }
    }
}
