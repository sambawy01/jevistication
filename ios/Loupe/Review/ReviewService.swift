import Foundation
import LoupeKit

/// The Review queue on the iPhone (epic #7 child 13): LoupeKit's `ReviewQueue` — Loupe Station's
/// review state machine (pending → approved → applied | failed → retry, reject with a reason, an
/// append-only log) — holding the **actions** the checks propose: remove a duplicate copy (privacy
/// check), confirm a phishing message (mail triage), keep a finding on Now (watchers), add a judgment
/// from a pack (judgments). Nothing runs until the person approves it. The queue's two files live
/// beside the decision ledger; an approved verdict also lands in the ledger's corrections log.
///
/// Not the Unsure queue: that one asks for labels the model learns from. This one is actions.
@MainActor
final class ReviewService: ObservableObject {
    static let shared = ReviewService(
        home: LedgerService.defaultHomeShared, ledger: LedgerService.shared,
        privacy: { PrivacyService.shared }, mail: { MailTriageService.shared },
        watchers: { WatchersService.shared }, judgments: { JudgmentsService.shared })

    @Published private(set) var items: [ReviewItem] = []
    @Published var notice: String?
    @Published private(set) var problem: String?
    /// The last action applied here that can be undone (the notice's Undo).
    @Published private(set) var lastApplied: ReviewItem?

    private let queue: ReviewQueue
    private let ledger: LedgerService
    private let privacy: () -> PrivacyService
    private let mail: () -> MailTriageService
    private let watchers: () -> WatchersService
    private let judgments: () -> JudgmentsService
    /// Removed copies still held for Undo, by review item id (this session only).
    private var held: [String: PrivacyUndo] = [:]

    init(home: URL, ledger: LedgerService, privacy: @escaping () -> PrivacyService, mail: @escaping () -> MailTriageService,
         watchers: @escaping () -> WatchersService, judgments: @escaping () -> JudgmentsService) {
        self.ledger = ledger
        self.privacy = privacy
        self.mail = mail
        self.watchers = watchers
        self.judgments = judgments
        do {
            try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
            queue = try ReviewQueue.companion.open(home: home.path)
        } catch {
            queue = ReviewQueue.companion.inMemory()
            problem = "The review queue could not be opened; nothing is kept: \(error.localizedDescription)"
        }
        refresh()
    }

    private static let actor = "ui"
    private func now() -> String { JudgmentsService.now() }

    func refresh() { items = queue.all() }

    /// Pending and failed: what Now's card counts.
    var toReview: Int { items.filter(\.isOpen).count }
    var open: [ReviewItem] { items.filter(\.isOpen) }
    var decided: [ReviewItem] { items.filter { !$0.isOpen } }

    // MARK: Producers

    /// Queues what the checks propose now. A source key is never queued twice, so re-running the
    /// checks never brings back something already decided.
    func collect() {
        var proposals: [ReviewProposal] = []
        let p = privacy()
        let reachable = Set(p.findings.compactMap(\.duplicates).flatMap(\.members).map(\.itemId).filter { id in
            if case .file = p.locator.access(for: id) { return true }
            return false
        })
        proposals += ReviewProducers.shared.privacy(findings: p.findings, reachable: reachable)
        proposals += ReviewProducers.shared.mail(rows: mail().rows)
        proposals += ReviewProducers.shared.watchers(findings: watchers().findings)
        submit(proposals)
    }

    /// Queues pack questions to be added one by one (Judgments → pack preview → Review one by one).
    func submitJudgments(_ plans: [PackJudgmentPlan], packSlug: String) -> Int {
        submit(ReviewProducers.shared.judgments(plans: plans, packSlug: packSlug))
    }

    @discardableResult
    private func submit(_ proposals: [ReviewProposal]) -> Int {
        var added = 0
        for pr in proposals where queue.submit(p: pr, at: now()) is ReviewResult.Done { added += 1 }
        if added > 0 { refresh() }
        return added
    }

    // MARK: Deciding

    /// Approves one item (optionally with edits) and runs its action. Returns the item as it ends.
    @discardableResult
    func approve(_ item: ReviewItem, edited: [String: String]? = nil, note: String? = nil) async -> ReviewItem? {
        let r = queue.approve(id: item.id, actor: Self.actor, edited: edited, note: note, at: now())
        guard let claimed = done(r) else { return nil }
        return await run(claimed)
    }

    /// Approves each pending item in turn. Returns how many ended applied.
    func approveAll(_ selected: [ReviewItem]) async -> Int {
        var ok = 0
        for item in selected where item.status == ReviewStatus.shared.PENDING {
            if await approve(item)?.status == ReviewStatus.shared.APPLIED { ok += 1 }
        }
        notice = "\(ok) of \(selected.count) applied." + (ok < selected.count ? " See the ones that failed." : "")
        return ok
    }

    func reject(_ item: ReviewItem, reason: String) {
        if done(queue.reject(id: item.id, actor: Self.actor, note: reason, at: now())) != nil { notice = "Rejected. It will not be proposed again." }
    }

    /// Retry re-runs the producing step (the check that proposed it), then the action.
    func retry(_ item: ReviewItem) async {
        await rerunProducer(item.feature)
        guard let claimed = done(queue.retry(id: item.id, actor: Self.actor, at: now())) else { return }
        await run(claimed)
    }

    /// Undoes an applied, reversible action.
    func undo(_ item: ReviewItem) async {
        guard item.status == ReviewStatus.shared.APPLIED, item.reversible else { return }
        do {
            try await reverse(item)
            if done(queue.undo(id: item.id, actor: Self.actor, at: now())) != nil { notice = "Undone." }
            if lastApplied?.id == item.id { lastApplied = nil }
        } catch {
            notice = "Could not undo: \(error.localizedDescription)"
        }
    }

    func log(_ item: ReviewItem) -> [ReviewLogEntry] { queue.log(itemId: item.id) }

    private func done(_ r: ReviewResult) -> ReviewItem? {
        defer { refresh() }
        if let d = r as? ReviewResult.Done { return d.item }
        if let refused = r as? ReviewResult.Refused { notice = refused.refusal.message }
        return nil
    }

    @discardableResult
    private func run(_ item: ReviewItem) async -> ReviewItem? {
        do {
            let result = try await perform(item)
            let out = done(queue.finish(id: item.id, actor: Self.actor, result: result, at: now()))
            notice = "Done: \(item.title)"
            lastApplied = out?.reversible == true ? out : nil
            return out
        } catch {
            let out = done(queue.fail(id: item.id, actor: Self.actor, error: error.localizedDescription, at: now()))
            notice = "It did not work: \(error.localizedDescription) You can retry or reject it."
            return out
        }
    }

    private func rerunProducer(_ feature: String) async {
        switch feature {
        case ReviewRegistry.shared.PRIVACY: await privacy().run()
        case ReviewRegistry.shared.MAIL: await mail().run()
        case ReviewRegistry.shared.WATCHER: await watchers().run()
        default: break
        }
    }

    struct ActionFailure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // MARK: The actions (Station's handlers, run only after approval)

    private func perform(_ item: ReviewItem) async throws -> [String: String] {
        let p = item.proposal
        switch item.actionType {
        case "privacy.remove_copy":
            let target = item.actionParams["item_id"] ?? ""
            guard case .file(let url, let scope) = privacy().locator.access(for: target) else {
                throw ActionFailure(message: "Loupe cannot reach this file any more.")
            }
            let undo = try privacy().files.delete(url, scope: scope, key: item.actionParams["finding_key"] ?? "")
            held[item.id] = undo
            await privacy().rescanAfterReview()
            return ["removed": url.lastPathComponent, "held": "until Loupe closes"]
        case "mail.confirm_phishing":
            guard let row = mail().rows.first(where: { $0.itemId == p["item_id"] }) else {
                throw ActionFailure(message: "The message is no longer in your sources.")
            }
            ledger.recordCorrection(MailTriage.shared.confirmPhishing(row: row, at: now()))
            await mail().run()
            return ["verdict": "phishing", "logged": "corrections"]
        case "watcher.confirm":
            guard let f = watchers().findings.first(where: { $0.key == p["finding_key"] }) else {
                throw ActionFailure(message: "The watchers no longer raise this.")
            }
            watchers().answer(f, .confirmed)
            return ["verdict": "confirmed", "logged": "corrections"]
        case "judgment.add":
            let js = judgments()
            js.load()
            let r = ReviewRegistry.shared.judgmentFor(p: p, existing: js.judgments)
            if let refused = r as? BookResult.Refused { throw ActionFailure(message: refused.reasons.joined(separator: "; ")) }
            guard let j = (r as? BookResult.Created)?.judgment else { throw ActionFailure(message: "Could not make the judgment.") }
            guard js.replaceAll(js.judgments + [j]) else { throw ActionFailure(message: js.notice ?? "Could not save.") }
            return ["judgment_id": j.id]
        case "none":
            return ["done": "recorded"]
        default:
            throw ActionFailure(message: "This version cannot run \(item.actionType).")
        }
    }

    private func reverse(_ item: ReviewItem) async throws {
        let p = item.proposal
        switch item.actionType {
        case "privacy.remove_copy":
            guard let u = held[item.id], FileManager.default.fileExists(atPath: u.current.path) else {
                throw ActionFailure(message: "The removed copy is no longer held (Loupe lets go of it when it closes).")
            }
            var scope: URL?
            if case .file(_, let s) = privacy().locator.access(for: item.actionParams["item_id"] ?? "") { scope = s }
            try privacy().files.undo(u, originalScope: scope)
            held[item.id] = nil
            await privacy().rescanAfterReview()
        case "mail.confirm_phishing":
            guard let row = mail().rows.first(where: { $0.itemId == p["item_id"] }) else {
                throw ActionFailure(message: "The message is no longer in your sources.")
            }
            ledger.recordCorrection(MailTriage.shared.retraction(row: row, at: now()))
            await mail().run()
        case "watcher.confirm":
            guard let f = watchers().findings.first(where: { $0.key == p["finding_key"] }) else {
                throw ActionFailure(message: "The watchers no longer raise this.")
            }
            ledger.recordCorrection(WatcherFindings.shared.retraction(finding: f, at: now()))
            await watchers().run()
        case "judgment.add":
            guard let id = item.applyResult["judgment_id"] else { throw ActionFailure(message: "Which judgment was added is not known.") }
            judgments().delete(id)
        default:
            throw ActionFailure(message: "This action cannot be undone.")
        }
    }
}

extension LedgerService {
    /// The directory the shared ledger lives in (the Review queue's files go beside it).
    static var defaultHomeShared: URL { LedgerService.shared.home }
}
