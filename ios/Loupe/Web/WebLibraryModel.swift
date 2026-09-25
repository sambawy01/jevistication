import Foundation
import SwiftUI
import Combine
import LoupeKit

/// One question answered over one fetch: the rows, the rule answer and (when it ran) Laya's.
struct WebRun: Equatable {
    enum Ranking: Equatable {
        case running(done: Int, total: Int)
        case laya
        case rulesOnly(Reason)
        case cancelled
    }
    enum Reason: Equatable { case modelNotInstalled, modelFailed(String), refused([String]), layaOff }

    let sector: WebSector
    let question: String
    let type: AnswerType
    /// Nil for a custom question.
    let variantId: String?
    let rule: RuleKind
    let result: SearchResult
    let rows: [WebRow]
    var rules: [WebAnswerRow]
    var laya: [WebAnswerRow]?
    var ranking: Ranking
    /// The row a yes/no variant is about, when it names one.
    var targetId: String?
}

/// The Web tab's template library: per-source switches (off by default, §4a), the inputs, the
/// chosen question, the fetch, the rule baseline and Laya's answer on the phone.
@MainActor
final class WebLibraryModel: ObservableObject {
    enum Phase: Equatable { case idle, fetching, failed(HelperError), done }
    enum Choice: Equatable, Hashable { case variant(String), custom }

    @Published var inputs = WebInputs()
    @Published var choice: [WebSector: Choice] = [:]
    @Published var customText: [WebSector: String] = [:]
    @Published var customType: [WebSector: AnswerType] = [:]
    @Published private(set) var phase: [WebSector: Phase] = [:]
    @Published private(set) var runs: [WebSector: WebRun] = [:]
    @Published var showRules: [WebSector: Bool] = [:]
    @Published private var enabled: [WebSector: Bool] = [:]

    let isFixtureMode: Bool
    private let helper: SearchHelper
    private let defaults: UserDefaults
    private let laya: () async -> Backend?
    private let ledger: LedgerService
    private let settings: ModelSettingsSource
    private var tasks: [WebSector: Task<Void, Never>] = [:]

    init(helper: SearchHelper, fixtureMode: Bool = false, defaults: UserDefaults = .standard,
         laya: @escaping () async -> Backend? = { await LayaModel.shared.backend() },
         ledger: LedgerService = .shared, settings: ModelSettingsSource = ModelSettingsService.shared) {
        self.helper = helper
        self.isFixtureMode = fixtureMode
        self.defaults = defaults
        self.laya = laya
        self.ledger = ledger
        self.settings = settings
        for s in WebSector.allCases where s != .flights {
            enabled[s] = defaults.object(forKey: Self.key(s)) as? Bool ?? false
        }
    }

    static func key(_ s: WebSector) -> String { "web.source.\(s.rawValue)" }

    // MARK: Per-source switch (off by default: no request is made while off)

    func isEnabled(_ s: WebSector) -> Bool { enabled[s] ?? false }

    func setEnabled(_ s: WebSector, _ on: Bool) {
        enabled[s] = on
        defaults.set(on, forKey: Self.key(s))
        if !on { cancel(s); runs[s] = nil; phase[s] = .idle }
    }

    // MARK: The question

    func choiceFor(_ s: WebSector) -> Choice {
        choice[s] ?? WebCatalog.variants(s).first.map { .variant($0.id) } ?? .custom
    }

    func typeFor(_ s: WebSector) -> AnswerType { customType[s] ?? .rank }

    /// Whether the custom question text is Arabic (the model reads an Arabic frame then).
    static func isArabic(_ text: String) -> Bool { text.unicodeScalars.contains { (0x0600...0x06FF).contains($0.value) } }

    /// The lint's findings for the custom question as it stands; empty means Laya can answer it.
    func customFindings(_ s: WebSector) -> [String] {
        let text = customText[s] ?? ""
        return WebQuestion.shared.findings(question: text, type: typeFor(s).kotlin, arabic: Self.isArabic(text))
    }

    /// The question, type, rows and rule the current choice asks.
    func plan(_ s: WebSector) -> (question: String, type: AnswerType, rows: RowSet, rule: RuleKind, variant: String?, arabic: Bool) {
        switch choiceFor(s) {
        case .variant(let id):
            let v = WebCatalog.variant(id) ?? WebCatalog.variants(s)[0]
            return (v.question(inputs), v.type, v.rows, v.rule, v.id, MS.lang == .ar)
        case .custom:
            let text = (customText[s] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return (text, typeFor(s), WebCatalog.customRows(s), WebCatalog.defaultRule(s), nil, Self.isArabic(text))
        }
    }

    func canAsk(_ s: WebSector) -> Bool {
        guard isEnabled(s), phase[s] != .fetching, inputs.problem(for: s) == nil else { return false }
        if case .custom = choiceFor(s) { return customFindings(s).isEmpty }
        return true
    }

    // MARK: Ask: fetch, rules, then Laya

    func ask(_ s: WebSector, online: Bool) async {
        guard isEnabled(s) else { return }                // off: no request is made
        guard online || isFixtureMode else { phase[s] = .failed(.offline); return }
        if let p = inputs.problem(for: s) { phase[s] = .failed(.badRequest(p)); return }
        let plan = plan(s)
        cancel(s)
        phase[s] = .fetching
        let result: SearchResult
        do {
            switch s {
            case .currency: result = .currency(try await helper.currency(WebCatalog.currencyQuery(plan.rows, inputs)))
            case .weather: result = .weather(try await helper.weather(WebCatalog.weatherQuery(inputs)))
            case .trains: result = .trains(try await helper.trains(WebCatalog.trainsQuery(inputs)))
            case .flights: return
            }
        } catch let e as HelperError {
            phase[s] = .failed(e); runs[s] = nil; return
        } catch {
            phase[s] = .failed(.unexpected(error.localizedDescription)); runs[s] = nil; return
        }
        let rows = WebRows.build(plan.rows, from: result, inputs: inputs)
        guard !rows.isEmpty else { phase[s] = .failed(.noResults); runs[s] = nil; return }
        let ruleAnswer = WebRules.answer(plan.rule, type: plan.type, rows: rows)
        let run = WebRun(sector: s, question: plan.question, type: plan.type, variantId: plan.variant, rule: plan.rule,
                         result: result, rows: rows, rules: ruleAnswer, laya: nil, ranking: .running(done: 0, total: rows.count),
                         targetId: plan.variant == nil ? nil : WebRules.target(plan.rule, rows: rows)?.id)
        runs[s] = run
        showRules[s] = false
        phase[s] = .done
        startLaya(s, run: run, arabic: plan.arabic)
    }

    private func finishRules(_ s: WebSector, _ why: WebRun.Reason) {
        runs[s]?.ranking = .rulesOnly(why)
    }

    func cancel(_ s: WebSector) {
        tasks[s]?.cancel()
        tasks[s] = nil
        if case .running = runs[s]?.ranking { runs[s]?.ranking = .cancelled }
    }

    func retryLaya(_ s: WebSector) {
        guard let run = runs[s] else { return }
        startLaya(s, run: run, arabic: Self.isArabic(run.question) || MS.lang == .ar)
    }

    private func startLaya(_ s: WebSector, run: WebRun, arabic: Bool) {
        tasks[s]?.cancel()
        // Model settings: the Web tab reads `features.flights` (its only online-ranking feature today).
        let policy = settings.policy(Features.shared.FLIGHTS)
        settings.recordRun(Features.shared.FLIGHTS, layaOff: !policy.useLaya)
        guard policy.useLaya else { finishRules(s, .layaOff); return }
        let judgment: JudgmentChoice
        switch WebQuestion.shared.compile(question: run.question, type: run.type.kotlin, arabic: arabic) {
        case let ready as WebQuestionResultReady: judgment = ready.judgment
        case let refused as WebQuestionResultRefused: finishRules(s, .refused(refused.reasons)); return
        default: finishRules(s, .refused(["could not compile the question"])); return
        }
        runs[s]?.ranking = .running(done: 0, total: run.rows.count)
        let items = run.rules.map(\.row)      // judged in the rule order: a tie falls back to the baseline
        let job = ActivityCenter.shared.start("web_search", title: "act.title.webSearch", view: "web", total: items.count,
                                              stage: "act.stage.ranking", cancel: { [weak self] in self?.cancel(s) })
        let threshold = WebJudge.companion.threshold(type: run.type.kotlin, policy: policy)
        let source = s.source
        let kind = run.type.kotlin
        tasks[s] = Task { [weak self, laya, ledger] in
            guard let backend = await laya() else {
                job.finish("error", "act.res.failed")
                let failed: String? = await MainActor.run {
                    if case let .failed(m) = LayaModel.shared.status { return m } else { return nil }
                }
                self?.finishRules(s, failed.map { .modelFailed($0) } ?? .modelNotInstalled)
                return
            }
            let judge = WebJudge.companion.forPolicy(backend: backend, source: source, type: kind, policy: policy)
            let work = Task.detached(priority: .userInitiated) { () -> Result<[WebVerdict], Error> in
                await ModelWork.run(.foreground) { Result(catching: {
                    var verdicts: [WebVerdict] = [], rows: [LedgerRow] = []
                    for (i, r) in items.enumerated() {
                        try Task.checkCancellation()
                        let d = judge.decide(judgment: judgment, item: WebItem(id: r.id, lines: r.lines))
                        verdicts.append(d.verdict)
                        rows.append(d.row)
                        job.progress(i + 1, of: items.count)
                        Task { @MainActor [weak self] in
                            if case .running = self?.runs[s]?.ranking { self?.runs[s]?.ranking = .running(done: i + 1, total: items.count) }
                        }
                    }
                    // Every item Laya judged is a decision: logged on this phone only (A5).
                    ledger.record(rows)
                    job.rows(rows, threshold: threshold)
                    return verdicts
                }) }
            }
            let result = await withTaskCancellationHandler { await work.value } onCancel: { work.cancel() }
            if case .success(let v) = result { job.finish("done", "act.res.webSearch", ["items": v.count]) }
            else if Task.isCancelled { job.finish("cancelled", "act.res.stopped") } else { job.finish("error", "act.res.failed") }
            guard let self, !Task.isCancelled else { return }
            switch result {
            case .success(let verdicts):
                guard self.runs[s]?.rows == run.rows else { return }
                self.runs[s]?.laya = Self.layaAnswer(verdicts, rows: items, type: run.type)
                self.runs[s]?.ranking = .laya
            case .failure(is CancellationError):
                self.runs[s]?.ranking = .cancelled
            case .failure(let e):
                self.finishRules(s, .modelFailed(e.localizedDescription))
            }
        }
    }

    /// Laya's verdicts as answer rows, ordered for [type] as the rules' are.
    static func layaAnswer(_ verdicts: [WebVerdict], rows: [WebRow], type: AnswerType) -> [WebAnswerRow] {
        let byId = Dictionary(uniqueKeysWithValues: verdicts.map { ($0.id, $0) })
        let answers: [WebAnswerRow] = rows.compactMap { r in
            guard let v = byId[r.id] else { return nil }
            let note = v.failure.map { "Model answer unusable: \($0)" } ?? (v.truncated ? "The model read only part of this item" : nil)
            return WebAnswerRow(row: r, rank: 0, value: v.failure == nil ? v.value : -1, label: "", unsure: v.unsure, note: note)
        }
        // Items keep their order for yes/no and score; pick and rank sort (unusable last).
        return WebRules.finish(answers, type: type) { a in
            let pct = Int((max(0, a.value) * 100).rounded())
            if a.value < 0 { return "–" }
            switch type {
            case .yesNo: return "\(a.yes ? WS.t("yes") : WS.t("no")) · \(pct)%"
            case .pick: return "\(pct)%"
            case .score, .rank:
                let level = WebRules.level(a.value)
                return "\(level)/5 · \(WebQuestion.shared.LEVELS[level - 1])"
            }
        }
    }

    // MARK: What the screen shows

    func shown(_ s: WebSector) -> [WebAnswerRow] {
        guard let run = runs[s] else { return [] }
        if showRules[s] == true { return run.rules }
        return run.laya ?? run.rules
    }

    func answeredByLaya(_ s: WebSector) -> Bool { showRules[s] != true && runs[s]?.laya != nil }

    /// One line summing up an answer.
    static func headline(_ answer: [WebAnswerRow], run: WebRun, who: String) -> String {
        switch run.type {
        case .pick, .rank:
            guard let top = answer.first else { return "" }
            return WS.t(run.type == .pick ? "answer.pick" : "answer.top", ["who": who, "item": top.row.title])
        case .yesNo:
            if let t = run.targetId, let row = answer.first(where: { $0.row.id == t }) {
                return WS.t("answer.yesno", ["who": who, "answer": row.yes ? WS.t("yes") : WS.t("no"), "item": row.row.title])
            }
            let yes = answer.filter(\.yes).map(\.row.title)
            return WS.t("answer.yesno", ["who": who, "answer": yes.isEmpty ? WS.t("no") : WS.t("yes"),
                                         "item": yes.isEmpty ? "–" : yes.prefix(3).joined(separator: ", ")])
        case .score:
            let best = answer.max { $0.value < $1.value }
            return WS.t("answer.scored", ["who": who, "n": "\(answer.count)"]) + (best.map { " · #1 \($0.row.title)" } ?? "")
        }
    }

    /// Set when Laya and the rules give a different headline answer.
    static func disagreement(_ run: WebRun) -> String? {
        guard let laya = run.laya else { return nil }
        switch run.type {
        case .pick, .rank:
            guard let l = laya.first, let r = run.rules.first, l.row.id != r.row.id else { return nil }
            return r.row.title
        case .yesNo:
            if let t = run.targetId, let l = laya.first(where: { $0.row.id == t }), let r = run.rules.first(where: { $0.row.id == t }), l.yes != r.yes {
                return r.yes ? WS.t("yes") : WS.t("no")
            }
            return nil
        case .score:
            guard let l = laya.max(by: { $0.value < $1.value }), let r = run.rules.max(by: { $0.value < $1.value }), l.row.id != r.row.id else { return nil }
            return r.row.title
        }
    }
}
