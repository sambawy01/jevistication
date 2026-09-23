import XCTest
import LoupeKit
@testable import Loupe

/// The Review queue (epic #7 child 13): proposals from the privacy check, mail triage, watchers and
/// pack judgments; approve / reject / retry / undo / batch approve, over the sample plus a real
/// duplicate pair in a temp folder. Nothing runs until approved.
@MainActor
final class ReviewTests: XCTestCase {
    private var home: URL!
    private var folder: URL!

    private static let sample: [SourceItem] = {
        let root = SourcesService.bundledSample()!
        return try! SourceScanner(extractors: AppleExtractors(timeZone: TimeZone(identifier: "UTC")!),
                                  zone: Kotlinx_datetimeTimeZone.companion.UTC,
                                  limits: SourceScanner.Limits(maxFileBytes: 50 * 1024 * 1024, maxTextChars: 20_000, maxDepth: 16, maxMboxMessages: 20_000))
            .scan(sources: SourcesService.sampleRoots(root), observer: NullScanObserver()).items
    }()

    private struct TempLocator: PrivacyLocating {
        let folder: URL
        func access(for itemId: String) -> PrivacyAccess {
            itemId.hasPrefix("files:t/") ? .file(folder.appendingPathComponent(String(itemId.dropFirst(8))), scope: nil)
                : .suggestOnly("read only")
        }
    }

    private final class NoPhotos: PhotoDeleting { func delete(localId: String) async throws {} }

    /// The items the services read: the sample plus whatever is in the temp folder right now.
    private final class Box { var items: [SourceItem] = [] }
    private let box = Box()

    private var ledger: LedgerService!
    private var privacy: PrivacyService!
    private var mail: MailTriageService!
    private var watchers: WatchersService!
    private var judgments: JudgmentsService!
    private var review: ReviewService!

    override func setUp() async throws {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeReview-\(UUID().uuidString)")
        folder = home.appendingPathComponent("picked")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for name in ["receipt.txt", "receipt copy.txt"] {
            try "Fresh Basket receipt\nTotal 12.40\n".write(to: folder.appendingPathComponent(name), atomically: true, encoding: .utf8)
        }
        rescanFolder()
        ledger = LedgerService(home: home)
        let box = self.box
        privacy = PrivacyService(ledger: ledger, items: { box.items }, locator: TempLocator(folder: folder),
                                 files: PrivacyFileActions(home: home), photos: NoPhotos(), rescan: { [weak self] in await self?.rescanFolder() })
        mail = MailTriageService(ledger: ledger, items: { box.items })
        let defaults = UserDefaults(suiteName: "review-\(UUID().uuidString)")!
        watchers = WatchersService(ledger: ledger, items: { box.items }, model: FakeModel(installed: false), seen: defaults)
        judgments = JudgmentsService(ledger: ledger, items: { box.items }, model: FakeModel(installed: false))
        judgments.load()
        review = makeReview()
        await privacy.run()
        await mail.run()
        await watchers.run()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: home) }

    private func makeReview() -> ReviewService {
        let (p, m, w, j) = (privacy!, mail!, watchers!, judgments!)
        return ReviewService(home: home.appendingPathComponent("review"), ledger: ledger,
                             privacy: { p }, mail: { m }, watchers: { w }, judgments: { j })
    }

    private func rescanFolder() {
        let scan = try! SourceScanner(extractors: AppleExtractors()).scan(
            sources: [SourceRoot(id: "files", type: .folder, path: folder.path, idPrefix: "files:t/")], observer: NullScanObserver())
        box.items = Self.sample + scan.items
    }

    private func open(_ feature: String) -> [ReviewItem] { review.open.filter { $0.feature == feature } }

    func testChecksProposeActionsAndNothingRunsUntilApproved() throws {
        review.collect()
        let privacyItems = open(ReviewRegistry.shared.PRIVACY)
        // Only the reachable copy is proposed; the sample's duplicates are suggest-only.
        XCTAssertEqual(privacyItems.count, 1)
        let item = try XCTUnwrap(privacyItems.first)
        XCTAssertTrue(item.actionParams["item_id"]!.hasPrefix("files:t/"))
        XCTAssertEqual(item.status, ReviewStatus.shared.PENDING)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path).count, 2, "nothing removed before approval")
        let mailItems = open(ReviewRegistry.shared.MAIL)
        XCTAssertEqual(mailItems.count, 1)
        XCTAssertTrue(mailItems[0].proposal["item_id"]!.hasSuffix("phishing-paypal.eml"))
        XCTAssertEqual(open(ReviewRegistry.shared.WATCHER).count, watchers.findings.count)
        XCTAssertEqual(review.toReview, review.open.count)
        // Re-running the checks queues nothing new.
        let before = review.items.count
        review.collect()
        XCTAssertEqual(review.items.count, before)
    }

    func testApprovePrivacyRemovesTheCopyAndUndoPutsItBack() async throws {
        review.collect()
        let item = try XCTUnwrap(open(ReviewRegistry.shared.PRIVACY).first)
        let done = await review.approve(item)
        XCTAssertEqual(done?.status, ReviewStatus.shared.APPLIED, done?.error ?? "")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path).count, 1)
        await review.undo(try XCTUnwrap(done))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: folder.path).count, 2)
        let after = try XCTUnwrap(review.items.first { $0.id == item.id })
        XCTAssertEqual(after.status, ReviewStatus.shared.UNDONE)
        XCTAssertEqual(review.log(after).reversed().map(\.event), ["submitted", "approved", "applied", "undone"])
    }

    func testApproveMailPhishingIsALedgerCorrectionWithUndo() async throws {
        review.collect()
        let item = try XCTUnwrap(open(ReviewRegistry.shared.MAIL).first)
        let doneResult = await review.approve(item)
        let done = try XCTUnwrap(doneResult)
        XCTAssertEqual(done.status, ReviewStatus.shared.APPLIED)
        ledger.flush()
        XCTAssertTrue(ledger.correctionIndex().contains { $0.key.itemId == item.proposal["item_id"] })
        await review.undo(done)
        ledger.flush()
        XCTAssertFalse(ledger.correctionIndex().contains { $0.key.itemId == item.proposal["item_id"] })
    }

    func testRejectNeedsAReasonAndIsNotProposedAgain() throws {
        review.collect()
        let item = try XCTUnwrap(open(ReviewRegistry.shared.MAIL).first)
        review.reject(item, reason: "  ")
        XCTAssertEqual(review.items.first { $0.id == item.id }?.status, ReviewStatus.shared.PENDING)
        review.reject(item, reason: "I will do it myself")
        XCTAssertEqual(review.items.first { $0.id == item.id }?.status, ReviewStatus.shared.REJECTED)
        review.collect()
        XCTAssertTrue(open(ReviewRegistry.shared.MAIL).isEmpty)
    }

    func testAFailedActionCanBeRetriedAfterTheProducerReruns() async throws {
        review.collect()
        let item = try XCTUnwrap(open(ReviewRegistry.shared.PRIVACY).first)
        let target = folder.appendingPathComponent(String(item.actionParams["item_id"]!.dropFirst(8)))
        let saved = try Data(contentsOf: target)
        try FileManager.default.removeItem(at: target)
        let failedResult = await review.approve(item)
        let failed = try XCTUnwrap(failedResult)
        XCTAssertEqual(failed.status, ReviewStatus.shared.FAILED)
        XCTAssertNotNil(failed.error)
        try saved.write(to: target)
        rescanFolder()
        await review.retry(failed)
        let after = try XCTUnwrap(review.items.first { $0.id == item.id })
        XCTAssertEqual(after.status, ReviewStatus.shared.APPLIED)
        XCTAssertEqual(after.attempts, 2)
    }

    func testBatchApprove() async throws {
        review.collect()
        let chosen = [try XCTUnwrap(open(ReviewRegistry.shared.MAIL).first), try XCTUnwrap(open(ReviewRegistry.shared.WATCHER).first)]
        let ok = await review.approveAll(chosen)
        XCTAssertEqual(ok, 2)
        XCTAssertTrue(chosen.allSatisfy { c in review.items.first { $0.id == c.id }?.status == ReviewStatus.shared.APPLIED })
    }

    func testPackJudgmentsCanBeApprovedOneByOne() async throws {
        let packs = PacksService(judgments: { [judgments] in judgments! }, review: { [review] in review! })
        packs.openExample()
        let preview = try XCTUnwrap(packs.preview)
        XCTAssertEqual(packs.reviewOneByOne(), preview.addable.count)
        XCTAssertTrue(judgments.judgments.isEmpty, "nothing added before approval")
        let item = try XCTUnwrap(open(ReviewRegistry.shared.JUDGMENT).first { $0.proposal["judgment_id"] == "j-complaint-triage-team" })
        let doneResult = await review.approve(item)
        let done = try XCTUnwrap(doneResult)
        XCTAssertEqual(done.status, ReviewStatus.shared.APPLIED, done.error ?? "")
        XCTAssertEqual(judgments.judgments.map(\.id), ["j-complaint-triage-team"])
        await review.undo(done)
        XCTAssertTrue(judgments.judgments.isEmpty)
    }

    func testTheQueueSurvivesARelaunch() async throws {
        review.collect()
        let n = review.items.count
        let item = try XCTUnwrap(open(ReviewRegistry.shared.MAIL).first)
        review.reject(item, reason: "Not needed")
        let again = makeReview()
        XCTAssertEqual(again.items.count, n)
        XCTAssertEqual(again.items.first { $0.id == item.id }?.status, ReviewStatus.shared.REJECTED)
    }
}
