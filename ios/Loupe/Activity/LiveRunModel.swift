import Foundation
import SwiftUI
import LoupeKit

/// What the live run view shows for one job snapshot: text, numbers and states, no drawing. Built from LoupeKit's
/// shared `LiveRun` rules (stage nodes, diamonds, particles, the status line), so it is tested without a screen.
struct LiveRunModel {
    struct Node: Identifiable, Equatable {
        let id: String
        let title: String
        let sub: String
        let diamond: Bool
        var active: Bool
        /// Diamonds: the question, its latest answer and confidence ("yes · 83%").
        var question: String = ""
        var answer: String = ""
        var fresh = false
    }

    struct Counter: Identifiable, Equatable {
        let id: String
        let label: String
        let value: Int
    }

    struct LogRow: Identifiable, Equatable {
        let id: Int
        let question: String
        let answer: String
        let confidence: String
        let source: String
    }

    struct Share: Identifiable, Equatable {
        let id: String
        let label: String
        let count: Int
        let fraction: Double
    }

    let job: JobSnapshot
    let title: String
    let stageText: String
    let running: Bool
    let fraction: Double?
    let progressText: String
    let nodes: [Node]
    let counters: [Counter]
    let log: [LogRow]
    let shares: [Share]
    let gates: [Share]
    let costCloud: String
    let costCloudTitle: String
    let costLocal: String
    let costDecisions: String
    let costLabel: String
    let poweredBy: String
    let mascot: MascotState
    let statusLine: String
    let hasLoop: Bool

    /// [question] maps an opaque judgment question id (`j:…`) back to its name, on this device only.
    init(_ job: JobSnapshot, previous: JobSnapshot?, reference: CostReference, question: (String) -> String? = { _ in nil }) {
        func t(_ k: String, _ a: [String: String] = [:]) -> String { ActStrings.t(k, a) }
        self.job = job
        running = job.running
        title = Self.message(job.title)
        stageText = job.stage.map(Self.message) ?? ""
        fraction = job.fraction?.doubleValue
        hasLoop = LiveRun.shared.pipeFor(kind: job.kind) != nil

        let done = Int(job.progress?.done ?? 0)
        let total = job.progress?.total.map { Int($0.doubleValue) }
        var p: [String] = []
        if let total { p.append(t("act.of", ["done": "\(done)", "total": "\(total)"])) }
        if let r = job.rate?.doubleValue, r > 0 { p.append(t("act.ratePer", ["n": Self.num(r)])) }
        if running, let e = job.etaS?.doubleValue { p.append(t("act.eta", ["d": Self.duration(e)])) }
        p.append(t(running ? "act.elapsed" : "act.state.\(job.state)", ["d": Self.duration(job.elapsedS)]))
        progressText = p.joined(separator: " · ")

        // Nodes and diamonds.
        let active = LiveRun.shared.activeNode(job: job)
        let qs = LiveRun.shared.diamondQuestions(job: job)
        let fresh = Set(LiveRun.shared.freshQuestions(previous: previous, job: job))
        var nodes: [Node] = []
        if let pipe = LiveRun.shared.pipeFor(kind: job.kind) {
            var qi = 0
            for n in pipe.nodes {
                if n.diamond {
                    var node = Node(id: n.id, title: "", sub: "", diamond: true, active: false)
                    if qi < qs.count {
                        let q = qs[qi]
                        node.question = Self.questionLabel(q, question)
                        if let d = LiveRun.shared.latestAnswer(job: job, question: q) {
                            node.answer = [d.a ?? "—", d.c.map { Self.pct($0.doubleValue) }].compactMap { $0 }.joined(separator: " · ")
                        }
                        node.fresh = fresh.contains(q)
                    }
                    qi += 1
                    nodes.append(node)
                } else {
                    nodes.append(Node(id: n.id, title: n.key.map { t($0) } ?? n.id, sub: n.sub.map { t($0) } ?? "", diamond: false, active: n.id == active))
                }
            }
        }
        self.nodes = nodes

        counters = ["read", "decisions", "flagged", "to_you"].map {
            Counter(id: $0, label: t("act.ctr.\($0)"), value: Int(job.counters[$0]?.int32Value ?? 0))
        }
        log = job.decisions.reversed().map { d in
            LogRow(id: Int(d.seq), question: d.q.map { Self.questionLabel($0, question) } ?? "—", answer: d.a ?? "—",
                   confidence: d.c.map { Self.pct($0.doubleValue) } ?? "—", source: t("act.src.\(d.src)"))
        }

        func shareRows(_ m: [String: KotlinInt], keys: [String], label: (String) -> String) -> [Share] {
            let total = keys.map { Int(m[$0]?.int32Value ?? 0) }.reduce(0, +)
            return keys.map { k in
                let n = Int(m[k]?.int32Value ?? 0)
                return Share(id: k, label: label(k), count: n, fraction: total == 0 ? 0 : Double(n) / Double(total))
            }
        }
        // Who answered: Laya by model, then the rules, the keyword baseline, the user's corrections.
        let src = job.sharesSource
        let mdl = job.sharesModel
        var who: [Share] = []
        let answered = ["laya", "rule", "baseline", "personal"].map { Int(src[$0]?.int32Value ?? 0) }.reduce(0, +)
        for m in ["english", "multilingual"] {
            let n = Int(mdl[m]?.int32Value ?? 0)
            who.append(Share(id: m, label: t("act.share.\(m)"), count: n, fraction: answered == 0 ? 0 : Double(n) / Double(answered)))
        }
        for s in ["rule", "baseline", "personal"] {
            let n = Int(src[s]?.int32Value ?? 0)
            who.append(Share(id: s, label: t("act.share.\(s)"), count: n, fraction: answered == 0 ? 0 : Double(n) / Double(answered)))
        }
        shares = who.filter { $0.id != "english" || $0.count > 0 }    // the phone has no English model
        gates = shareRows(job.gates, keys: ["accepted", "uncertain", "flagged", "skipped"]) { t("act.gate.\($0)") }

        // Cost of asking: the contract's formula; nothing is ever called.
        let decisions = Int(job.counters["decisions"]?.int32Value ?? 0)
        costCloudTitle = t("act.cost.cloud", ["model": reference.model])
        costCloud = Self.dollars(reference.cost(decisions: Int32(decisions)))
        costLocal = t("act.cost.onDevice")
        costDecisions = t("act.cost.decisions", ["n": "\(decisions)"])
        costLabel = reference.custom ? t("act.cost.custom")
            : t("act.cost.estimate", ["model": reference.model, "date": Self.checkedDate(reference.checked)])

        let model = job.model ?? "multilingual"
        poweredBy = t("act.poweredBy", ["model": t("act.model.\(model)")])

        // The engine: scanning while items pass, happy when done, unsure when stopped, a shrug on an error.
        switch job.state {
        case "running": mascot = (job.counters["read"]?.int32Value ?? 0) > 0 || job.progress != nil ? .scanning : .thinking
        case "done":
            let doubtful = (job.gates["flagged"]?.int32Value ?? 0) + (job.gates["uncertain"]?.int32Value ?? 0)
            mascot = doubtful > 0 ? .thinking : .happy     // .thinking is the rig's "unsure" pose
        case "error": mascot = .empty
        default: mascot = .thinking
        }

        statusLine = running
            ? [title, stageText, fraction.map { Self.pct($0) } ?? "", running ? job.etaS.map { t("act.eta", ["d": Self.duration($0.doubleValue)]) } ?? "" : ""]
                .filter { !$0.isEmpty }.joined(separator: ", ")
            : [title, job.result.map(Self.message) ?? t("act.state.\(job.state)")].filter { !$0.isEmpty }.joined(separator: ": ")
    }

    // MARK: Text helpers (shared with the dock)

    static func message(_ m: ActMessage) -> String {
        var args: [String: String] = [:]
        for name in m.paramNames {
            guard let v = m.param(name: name) else { continue }
            args[name] = name == "model" && ActStrings.has("act.model.\(v)") ? ActStrings.t("act.model.\(v)") : v
        }
        return ActStrings.t(m.key, args)
    }

    static func questionLabel(_ q: String, _ resolve: (String) -> String?) -> String {
        if ActStrings.has("act.q.\(q)") { return ActStrings.t("act.q.\(q)") }
        return resolve(q) ?? q
    }

    static func pct(_ x: Double) -> String { "\(Int((x * 100).rounded()))%" }

    static func num(_ x: Double) -> String { x >= 10 ? "\(Int(x.rounded()))" : String(format: "%.1f", x) }

    static func duration(_ s: Double) -> String {
        func t(_ k: String, _ a: [String: String]) -> String { ActStrings.t(k, a) }
        if s < 60 { return t("act.dur.s", ["n": "\(max(0, Int(s.rounded())))"]) }
        if s < 3600 { return t("act.dur.min", ["n": "\(Int((s / 60).rounded()))"]) }
        return t("act.dur.h", ["h": "\(Int(s / 3600))", "n": "\(Int(s.truncatingRemainder(dividingBy: 3600) / 60))"])
    }

    static func dollars(_ x: Double) -> String {
        if x == 0 { return "$0" }
        if x < 0.01 { return String(format: "$%.4f", x) }
        return String(format: "$%.2f", x)
    }

    /// "2026-09-24" as "24 Sep 2026" (the card's words; the same in both languages, as Station).
    static func checkedDate(_ iso: String) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
        guard let d = f.date(from: iso) else { return iso }
        let o = DateFormatter(); o.dateFormat = "d MMM yyyy"; o.locale = Locale(identifier: "en_GB")
        return o.string(from: d)
    }
}
