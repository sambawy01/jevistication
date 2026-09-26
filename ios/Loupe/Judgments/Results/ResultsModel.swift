import Foundation
import LoupeKit
import SwiftUI

/// The results dashboard's state for one judgment: the index (built off the main thread once per run), the
/// filter and sort, the visible sections, selection and the undo stack. Corrections go through
/// `JudgmentsService.recordCorrections` (the queue's records: ledger, corrections log, calibration) and move
/// one record in the index; the summary is never recounted for them.
@MainActor
final class ResultsModel: ObservableObject {
    @Published private(set) var index: ResultsIndex?
    /// Bumped whenever the index's numbers change (a correction, a rebuild): views read the index through it.
    @Published private(set) var version = 0
    @Published private(set) var building = false
    @Published var filter = ResultsFilter() { didSet { if filter != oldValue { refilter() } } }
    @Published var sort: ResultsSort = .confidence { didSet { if sort != oldValue { refilter() } } }
    @Published private(set) var sections: [ResultSection] = []
    @Published private(set) var shown = 0
    @Published var selecting = false { didSet { if !selecting { selected.removeAll() } } }
    @Published var selected: Set<Int32> = []
    /// The last change made here, for the Undo bar.
    @Published private(set) var lastChange: Change?
    /// DEBUG `-LoupeResultsPerf` and the perf test: milliseconds to build the index, to first paint, per filter.
    @Published private(set) var perf = Perf()

    struct Perf: Equatable {
        var buildMs: Double?
        var firstPaintMs: Double?
        var lastFilterMs: Double?
        var items = 0
        var line: String {
            func f(_ v: Double?) -> String { v.map { String(format: "%.0f", $0) } ?? "–" }
            return "items=\(items) build=\(f(buildMs))ms paint=\(f(firstPaintMs))ms filter=\(lastFilterMs.map { String(format: "%.1f", $0) } ?? "–")ms"
        }
    }

    /// One correction action (one item or a bulk one): what each item's answer was before, for Undo.
    struct Change: Equatable {
        let id = UUID()
        let summary: String
        let before: [(index: Int, option: Int?)]
        static func == (a: Change, b: Change) -> Bool { a.id == b.id }
    }

    let judgmentId: String
    private(set) var judgment: UserJudgment?
    /// The Kotlin rows behind the records (same order), for the item detail's full distribution and notes.
    private(set) var rows: [ResultRow] = []
    private var stamp = ""
    private var undo: [Change] = []
    private var buildStart: CFAbsoluteTime?

    init(judgmentId: String) { self.judgmentId = judgmentId }

    var options: [String] { index?.options ?? [] }

    // MARK: Building

    /// What a rebuild depends on: new ledger rows (a run), a reworded or re-thresholded judgment, rescanned items.
    private func currentStamp(_ service: JudgmentsService, _ j: UserJudgment) -> String {
        "\(service.rows.count)|\(j.criteriaHash)|\(j.threshold)|\(j.onFailure.name)|\(SourcesService.shared.revision)"
    }

    /// Builds (or rebuilds after a run) the index; otherwise only takes in corrections made elsewhere.
    func refresh(_ service: JudgmentsService) async {
        guard let j = service.judgment(judgmentId) else { return }
        let s = currentStamp(service, j)
        if s == stamp, index != nil {
            judgment = j
            reconcile(service)
            return
        }
        stamp = s
        judgment = j
        building = true
        let t0 = CFAbsoluteTimeGetCurrent()
        buildStart = buildStart ?? t0
        let all = service.rows
        let corrections = service.corrections
        let items = service.sampleItems()
        let fallback = Self.fallback
        let (rows, index) = await Task.detached(priority: .userInitiated) { () -> ([ResultRow], ResultsIndex) in
            let rows = JudgmentResults.shared.rows(all: all, judgment: j, corrections: corrections, items: items)
            let records = ResultsBuilder.records(rows, judgment: j, fallback: fallback)
            return (rows, ResultsIndex(options: ResultsBuilder.options(j), threshold: j.threshold, records: records))
        }.value
        guard stamp == s else { return }   // a newer refresh started meanwhile
        self.rows = rows
        self.index = index
        undo.removeAll()
        lastChange = nil
        selected.removeAll()
        perf.buildMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        perf.items = index.count
        building = false
        version += 1
        refilter()
    }

    /// Called when the summary first appears on screen.
    func didPaint() {
        guard perf.firstPaintMs == nil, let t = buildStart, index != nil else { return }
        perf.firstPaintMs = (CFAbsoluteTimeGetCurrent() - t) * 1000
    }

    func markStart() { if buildStart == nil { buildStart = CFAbsoluteTimeGetCurrent() } }

    /// Items the index could not find among the scanned ones (DEBUG: the results fixture's own items).
    nonisolated private static var fallback: (String) -> SourceItem? {
        #if DEBUG
        let items = ResultsFixture.items
        return { items[$0] }
        #else
        return { _ in nil }
        #endif
    }

    /// Corrections made elsewhere (the Unsure queue) since the index was built: moves just those records.
    func reconcile(_ service: JudgmentsService) {
        guard let index, let j = judgment else { return }
        var theirs: [String: Int] = [:]
        let position = Dictionary(index.options.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        for (k, v) in service.corrections where k.judgmentId == j.id && k.criteriaHash == j.criteriaHash {
            if let o = position[v] { theirs[k.itemId] = o }
        }
        var changed = false
        for (i, r) in index.records.enumerated() {
            let want = theirs[r.itemId].flatMap { r.canCorrect(to: $0) ? $0 : nil }
            if r.correction != want { index.setCorrection(i, want); changed = true }
        }
        if changed { version += 1; refilter() }
    }

    // MARK: Filtering

    func refilter() {
        guard let index else { sections = []; shown = 0; return }
        let t0 = CFAbsoluteTimeGetCurrent()
        let matched = index.filtered(filter, sort: sort)
        sections = index.sections(matched)
        shown = matched.count
        perf.lastFilterMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000
    }

    /// Tapping the same facet again clears it.
    func toggle(bucket b: ResultBucket) { filter.bucket = filter.bucket == b ? nil : b }
    func toggle(answerer a: Answerer) { filter.answerer = filter.answerer == a && filter.check == nil ? nil : a; filter.check = nil }
    func toggle(check c: String) {
        if filter.check == c { filter.check = nil; filter.answerer = nil } else { filter.check = c; filter.answerer = .rule }
    }
    func toggle(bins b: ClosedRange<Int>) { filter.bins = filter.bins == b ? nil : b }
    func toggle(source s: String) { filter.source = filter.source == s ? nil : s }
    func toggle(kind k: String) { filter.kind = filter.kind == k ? nil : k }
    func toggle(month m: String) { filter.month = filter.month == m ? nil : m }
    func clearFilters() { filter = ResultsFilter() }

    /// The active facets as removable chips, in a fixed order.
    struct Chip: Identifiable, Equatable {
        let id: String
        let label: String
    }

    var chips: [Chip] {
        var out: [Chip] = []
        if let b = filter.bucket { out.append(Chip(id: "bucket", label: bucketTitle(b))) }
        if let c = filter.check { out.append(Chip(id: "check", label: "Rule: " + ResultsNames.check(c))) }
        else if let a = filter.answerer { out.append(Chip(id: "answerer", label: "By: " + a.title)) }
        if let r = filter.bins, let index {
            let lo = index.bins.range(r.lowerBound).lo, hi = index.bins.range(r.upperBound).hi
            out.append(Chip(id: "bins", label: "Confidence \(pct(lo))–\(pct(hi))"))
        }
        if let s = filter.source { out.append(Chip(id: "source", label: ResultsNames.source(s))) }
        if let k = filter.kind { out.append(Chip(id: "kind", label: k)) }
        if let m = filter.month { out.append(Chip(id: "month", label: ResultsNames.month(m))) }
        return out
    }

    func remove(chip id: String) {
        switch id {
        case "bucket": filter.bucket = nil
        case "check": filter.check = nil; filter.answerer = nil
        case "answerer": filter.answerer = nil
        case "bins": filter.bins = nil
        case "source": filter.source = nil
        case "kind": filter.kind = nil
        case "month": filter.month = nil
        default: break
        }
    }

    // MARK: Names

    func optionTitle(_ i: Int) -> String {
        guard let j = judgment, options.indices.contains(i) else { return "?" }
        return j.shown(options[i])
    }

    func bucketTitle(_ b: ResultBucket) -> String {
        switch b {
        case .unusable: return "Could not judge"
        case .unsure: return "Unsure · needs you"
        case .answer(let i): return optionTitle(i)
        case .rule(let i): return optionTitle(i) + " · by rule"
        }
    }

    func record(_ i: Int32) -> ResultRecord? {
        guard let index, index.records.indices.contains(Int(i)) else { return nil }
        return index.records[Int(i)]
    }

    func row(_ i: Int32) -> ResultRow? { rows.indices.contains(Int(i)) ? rows[Int(i)] : nil }

    // MARK: Corrections

    /// Answers `indices` with `option`; nil confirms each item's current answer (or the model's lean when it
    /// was unsure). Unusable items without an answer, and items already answered so, are left alone.
    func correct(_ indices: [Int32], to option: Int?, service: JudgmentsService) {
        guard let index, let j = judgment else { return }
        var before: [(index: Int, option: Int?)] = []
        var targets: [(index: Int, option: Int)] = []
        var changes: [(itemId: String, label: String?, modelPick: String)] = []
        for raw in indices {
            let i = Int(raw)
            guard index.records.indices.contains(i) else { continue }
            let r = index.records[i]
            let target = option ?? r.answer ?? (r.top >= 0 && !r.unusable ? r.top : nil)
            guard let t = target, r.canCorrect(to: t), r.correction != t else { continue }
            before.append((i, r.correction))
            targets.append((i, t))
            changes.append((r.itemId, index.options[t], r.top >= 0 ? index.options[r.top] : ""))
        }
        guard !changes.isEmpty else { return }
        service.recordCorrections(j, changes)
        for t in targets { index.setCorrection(t.index, t.option) }
        let what = option.map { "Marked as \(optionTitle($0))" } ?? "Confirmed"
        let change = Change(summary: before.count == 1 ? "\(what): \(index.records[before[0].index].name)" : "\(what): \(before.count) items",
                            before: before)
        undo.append(change)
        lastChange = change
        selected.removeAll()
        version += 1
        refilter()
    }

    /// Removes your answer from one item (a retraction in the log).
    func clearCorrection(_ raw: Int32, service: JudgmentsService) {
        guard let index, let j = judgment, index.records.indices.contains(Int(raw)) else { return }
        let r = index.records[Int(raw)]
        guard let old = r.correction else { return }
        service.recordCorrections(j, [(r.itemId, nil, r.top >= 0 ? index.options[r.top] : "")])
        index.setCorrection(Int(raw), nil)
        let change = Change(summary: "Removed your answer: \(r.name)", before: [(Int(raw), old)])
        undo.append(change)
        lastChange = change
        version += 1
        refilter()
    }

    var canUndo: Bool { !undo.isEmpty }

    /// Puts back what the last change replaced: the earlier answer, or a retraction where there was none.
    func undoLast(service: JudgmentsService) {
        guard let index, let j = judgment, let change = undo.popLast() else { return }
        let changes = change.before.map { b -> (itemId: String, label: String?, modelPick: String) in
            let r = index.records[b.index]
            return (r.itemId, b.option.map { index.options[$0] }, r.top >= 0 ? index.options[r.top] : "")
        }
        service.recordCorrections(j, changes)
        for b in change.before { index.setCorrection(b.index, b.option) }
        lastChange = undo.last
        version += 1
        refilter()
    }

    func dismissChange() { lastChange = nil }

    /// All rows currently shown (Select all).
    var shownIndices: [Int32] { sections.flatMap(\.rows) }
}

func pct(_ p: Double) -> String { "\(Int((p * 100).rounded()))%" }
