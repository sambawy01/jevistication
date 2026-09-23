import Foundation
import LoupeKit

/// Preset packs on the iPhone (epic #7 child 14): LoupeKit's `PackFormat` validates a pack exactly as
/// Loupe Station does (whole pack refused on any problem, every problem listed), then `PackJudgments`
/// turns each question into a judgment through the C2 lint and marks id conflicts. Nothing is added
/// until the person has seen the preview. Import from Files, from the share sheet ("Open in Loupe"),
/// or the bundled example; export your judgments as a pack another Loupe (or Station) can import.
@MainActor
final class PacksService: ObservableObject {
    static let shared = PacksService(judgments: { JudgmentsService.shared }, review: { ReviewService.shared })

    /// What the preview sheet shows.
    struct Preview: Identifiable {
        let pack: Pack
        var plans: [PackJudgmentPlan]
        /// The bundled example: shown with its label, never as yours.
        let example: Bool
        var id: String { pack.slug }
        var addable: [PackJudgmentPlan] { plans.filter(\.addable) }
        var refused: [PackJudgmentPlan] { plans.filter { !$0.addable } }
        var conflicts: Int { addable.filter(\.conflict).count }
    }

    @Published var preview: Preview?
    /// Why the last file was refused (Station's problem lines, at most a handful shown).
    @Published var problems: [String] = []
    /// Shown on My judgments (the tab's one-line notice).
    @Published var notice: String? { didSet { if let n = notice { judgments().notice = n } } }

    private let judgments: () -> JudgmentsService
    private let review: () -> ReviewService

    init(judgments: @escaping () -> JudgmentsService, review: @escaping () -> ReviewService) {
        self.judgments = judgments
        self.review = review
    }

    static let exampleLabel = "Example pack: a delivery kitchen's own rules (Bistro Cloud), copied from Loupe Station. It shows the format; it is not tuned for you."

    static func bundledExample() -> URL? {
        Bundle.main.url(forResource: "bistro-cloud", withExtension: "json", subdirectory: "packs")
    }

    func openExample() {
        guard let url = Self.bundledExample() else { problems = ["The example pack is missing from this build."]; return }
        open(url, example: true)
    }

    /// Reads and validates a pack file (Files, share sheet, or the example). Shows the preview or the problems.
    func open(_ url: URL, example: Bool = false) {
        let on = url.startAccessingSecurityScopedResource()
        defer { if on { url.stopAccessingSecurityScopedResource() } }
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? Int, size <= Int(PackFormat.shared.MAX_PACK_BYTES),
              let data = FileManager.default.contents(atPath: url.path) else {
            problems = ["The file could not be read, or it is larger than \(PackFormat.shared.MAX_PACK_BYTES / 1000) KB."]
            preview = nil
            return
        }
        load(String(decoding: data, as: UTF8.self), example: example)
    }

    func load(_ text: String, example: Bool) {
        switch PackFormat.shared.parse(text: text) {
        case let valid as PackParse.Valid:
            let js = judgments()
            js.load()
            problems = []
            preview = Preview(pack: valid.pack, plans: PackJudgments.shared.plan(pack: valid.pack, existing: js.judgments), example: example)
        case let invalid as PackParse.Invalid:
            preview = nil
            problems = invalid.lines
        default:
            problems = ["Not a pack."]
        }
    }

    /// Adds every question the lint accepts; a conflict (same id) is settled by [choice].
    @discardableResult
    func add(_ choice: ConflictChoice) -> PackAddResult? {
        guard let p = preview else { return nil }
        let js = judgments()
        let r = PackJudgments.shared.add(plans: p.plans, existing: js.judgments, choice: choice, only: [])
        guard js.replaceAll(r.judgments) else { notice = js.notice; return nil }
        notice = "\(p.pack.name): \(r.summary)."
        preview = nil
        return r
    }

    /// Queues the addable questions in Review, to approve one by one.
    @discardableResult
    func reviewOneByOne() -> Int {
        guard let p = preview else { return 0 }
        let n = review().submitJudgments(p.addable, packSlug: p.pack.slug)
        notice = n == 0 ? "Already in Review." : "\(n) judgment\(n == 1 ? "" : "s") waiting in Review."
        preview = nil
        return n
    }

    /// Your judgments as a pack file, ready to share.
    func exportFile() throws -> (URL, PackExport) {
        let js = judgments()
        js.load()
        let export = PackJudgments.shared.export(judgments: js.judgments)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("LoupePacks", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(PackJudgments.shared.EXPORT_SLUG).laya-pack.json")
        try Data(export.text.utf8).write(to: url, options: .atomic)
        return (url, export)
    }
}
