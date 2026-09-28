import Foundation

// The one run of Loupe's checks (owner decisions 2026-09-28): what a run is, what it reports while it goes and what
// it leaves behind. `RunCoordinator` drives it; these are plain values so the coordinator, the nightly task, the
// panels and the tests share them. PUBLIC NAMES ARE STABLE: the shell and tracking-engine branches build on them.

/// The stages of a run, in the order they always run.
enum RunStage: String, Codable, CaseIterable, Comparable, CodingKeyRepresentable {
    /// Re-reading the sources that are on (Photos, Files, Calendar, Contacts, Mail; the DEBUG fixture sample).
    case sources
    /// The privacy check.
    case privacy
    /// Mail triage (with the site checks on links).
    case mail
    /// The five watchers.
    case watchers
    /// The sort: every judgment over every source (needs the decision model; skipped without it).
    case sort

    var title: String {
        switch self {
        case .sources: return "Reading your sources"
        case .privacy: return "Privacy check"
        case .mail: return "Mail triage"
        case .watchers: return "Watchers"
        case .sort: return "Sorting"
        }
    }

    /// The word for what a stage counts ("items", "emails", …).
    var unit: String {
        switch self {
        case .sources: return "items"
        case .privacy: return "items"
        case .mail: return "emails"
        case .watchers: return "watchers"
        case .sort: return "items"
        }
    }

    static func < (a: RunStage, b: RunStage) -> Bool { allCases.firstIndex(of: a)! < allCases.firstIndex(of: b)! }
}

/// Why a run started.
enum RunReason: Equatable, Hashable, Codable {
    /// The one full check right after onboarding.
    case firstCheck
    /// "Run now" (Me, Now, Guard): everything.
    case manual
    /// "Scan again" / "Scan now" on one source, a source switched on, access allowed, a folder picked, a mailbox added:
    /// that source, then the checks (no sort). The value is the source id ("photos", "files", … or "sample" in DEBUG).
    case scanAgain(String)
    /// The sources' items changed without a scan (a source switched off, an import removed, a mailbox removed): the
    /// checks only.
    case itemsChanged
    /// The daily overnight run while charging (BGProcessingTask).
    case nightly

    /// The stages this reason runs, in order.
    var stages: [RunStage] {
        switch self {
        case .firstCheck, .manual, .nightly: return RunStage.allCases
        case .scanAgain: return [.sources, .privacy, .mail, .watchers]
        case .itemsChanged: return [.privacy, .mail, .watchers]
        }
    }

    /// For `.scanAgain`, the one source; nil means every source that is on.
    var onlySource: String? {
        if case .scanAgain(let s) = self { return s }
        return nil
    }

    /// "Run now", "First check", …
    var title: String {
        switch self {
        case .firstCheck: return "First check"
        case .manual: return "Run now"
        case .scanAgain: return "Scan again"
        case .itemsChanged: return "Re-check"
        case .nightly: return "Overnight check"
        }
    }

    /// Whether this run covers `other` (a queued run it can absorb): a full run covers everything.
    func covers(_ other: RunReason) -> Bool {
        if self == other { return true }
        switch self {
        case .firstCheck, .manual, .nightly: return true
        case .scanAgain: return other == .itemsChanged
        case .itemsChanged: return false
        }
    }
}

/// How a run ended.
enum RunOutcome: String, Codable {
    /// Every stage ran.
    case finished
    /// The user tapped Cancel: the stage stopped at its next item; later stages did not run.
    case cancelled
    /// iOS ended the background time (nightly only): checkpointed, the next night continues.
    case expired
    /// Heat or Low Power Mode stopped it (checkpointed for the nightly run).
    case stopped
    /// Nothing to do (for the nightly: not charging-safe right now, or already ran today).
    case skipped
}

/// How far one stage got.
struct RunStageCount: Equatable, Codable {
    var done: Int
    /// nil while the stage does not know yet.
    var total: Int?
    var finished: Bool
}

/// What a foreground or background run publishes while it goes (`RunCoordinator.current`), at most ~8 times a second.
/// Names are the live scan's masked names (never raw recognised text).
struct RunProgress: Equatable {
    let id: UUID
    let reason: RunReason
    let startedAt: Date
    /// The stages this run does, in order.
    let stages: [RunStage]
    /// The stage running now.
    var stage: RunStage
    /// Within the stage: the source being read ("Photos"), the watcher running ("Expiry radar"), …
    var part: String?
    /// The current item's name, when the stage knows it (the sources stage: the live scan's masked name).
    var item: String?
    /// done / total per stage (a stage not started yet is absent).
    var counts: [RunStage: RunStageCount]
    /// Items a second in the current stage (nil until there is half a second of data).
    var rate: Double?
    /// Seconds left in the current stage (nil when it cannot be said).
    var eta: TimeInterval?
    /// Cancel was tapped; the stage is stopping at its next item.
    var cancelling: Bool

    var count: RunStageCount? { counts[stage] }
    /// 1-based position of the current stage ("2 of 5").
    var stageNumber: Int { (stages.firstIndex(of: stage) ?? 0) + 1 }

    /// "Privacy check · 312 of 1,204 · 48/s · about 20 s left"
    var line: String {
        var parts = [stage.title]
        if let part { parts.append(part) }
        if let c = count {
            if let t = c.total { parts.append("\(c.done.formatted()) of \(t.formatted())") } else if c.done > 0 { parts.append(c.done.formatted()) }
        }
        if let rate, rate > 0 { parts.append("\(ScanSnapshot.rateText(rate))/s") }
        if let eta { parts.append("about \(ScanSnapshot.duration(eta)) left") }
        return parts.joined(separator: " · ")
    }
}

/// New findings a run raised, by check (keys not in the previous saved results).
struct RunFindings: Equatable, Codable {
    var privacy = 0
    var mail = 0
    var watchers = 0
    var total: Int { privacy + mail + watchers }
}

/// One run, as recorded (the last one on Now and Me; the nightly's for the morning notification). Saved in
/// `<home>/run/state.json`.
struct RunRecord: Equatable, Codable, Identifiable {
    let id: UUID
    let reason: RunReason
    let startedAt: Date
    var endedAt: Date
    /// The stages that ran to the end, in order.
    var stagesRun: [RunStage]
    /// What each stage counted: sources → items read, privacy → items checked, mail → emails, watchers → items
    /// checked, sort → items sorted.
    var counts: [RunStage: Int]
    var newFindings: RunFindings
    var outcome: RunOutcome
    /// Why it stopped or skipped, in words ("The phone is hot, so the run stopped.").
    var note: String?

    /// "Checked 1,204 items · 3 new findings"
    var line: String {
        let items = counts[.sources] ?? counts[.privacy] ?? counts[.watchers] ?? 0
        var s = "Checked \(items.formatted()) item\(items == 1 ? "" : "s")"
        let n = newFindings.total
        s += n == 0 ? " · nothing new" : " · \(n) new finding\(n == 1 ? "" : "s")"
        return s
    }

    /// `line` for a finished run, else what happened, in words.
    var outcomeLine: String {
        switch outcome {
        case .finished: return line
        case .cancelled: return "Cancelled. What it read is saved."
        case .expired: return "iOS ended it early; it carries on next night."
        case .stopped: return note ?? "Stopped early."
        case .skipped: return note ?? "Nothing ran."
        }
    }
}

/// A run's cancel flag, read from any thread between items (the sources' producers, the checks' loops).
final class RunCancel: @unchecked Sendable {
    private let lock = NSLock()
    private var why: RunOutcome?

    init() {}

    /// Stops the run at the next item; the first reason given wins.
    func cancel(_ reason: RunOutcome = .cancelled) {
        lock.lock(); if why == nil { why = reason }; lock.unlock()
    }

    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return why != nil }
    var reason: RunOutcome? { lock.lock(); defer { lock.unlock() }; return why }
}

/// Where a stage reports its progress from any thread. Throttled by the coordinator.
final class RunReporter: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [(t: TimeInterval, done: Int)] = []
    private let clock: @Sendable () -> TimeInterval
    private let sink: @Sendable (RunReport) -> Void

    init(clock: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSinceReferenceDate },
         sink: @escaping @Sendable (RunReport) -> Void) {
        self.clock = clock
        self.sink = sink
    }

    /// Item [done] of [total] (total nil when unknown), optionally naming the part and the item.
    func report(done: Int, total: Int?, part: String? = nil, item: String? = nil) {
        lock.lock()
        let now = clock()
        if samples.isEmpty, done > 0 { samples.append((now, 0)) }
        samples.append((now, done))
        while samples.count > 2, now - samples[1].t > 4 { samples.removeFirst() }
        var rate: Double?
        if let base = samples.first, let last = samples.last, now - base.t >= 0.5 {
            rate = Double(max(0, last.done - base.done)) / (now - base.t)
        }
        lock.unlock()
        var eta: TimeInterval?
        if let total, let rate, rate > 0 { eta = Double(max(0, total - done)) / rate }
        sink(RunReport(done: done, total: total, part: part, item: item, rate: rate, eta: eta))
    }

    /// A report whose rate and ETA the stage already knows (the sources' live scan, the sort's coordinator).
    func report(_ r: RunReport) { sink(r) }
}

/// One progress report from a stage.
struct RunReport: Equatable, Sendable {
    var done: Int
    var total: Int?
    var part: String?
    var item: String?
    var rate: Double?
    var eta: TimeInterval?
}
