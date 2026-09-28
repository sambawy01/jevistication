import LoupeKit
import SwiftUI

/// What Home's scan panel lists and says (owner rulings O-5, C-16), as plain values so they are unit-tested: the real
/// sources in a fixed order (never the sample: owner ruling O-6), the adapter from the run coordinator's live
/// `RunProgress` to the panel's `HomeScanProgress`, which state the panel is in, and the collapsed line's title.
enum HomeScanPanelModel {
    struct Source: Equatable, Identifiable {
        let id: String
        let title: String
        /// The phone source behind the row; nil for the Inbox.
        let phone: PhoneSource?
    }

    static let inboxId = "inbox"
    /// `SourcesService.sampleId` (that one is main-actor isolated; this model is plain values).
    static let sampleKey = "sample"
    /// `LiveRunWork.sourceTitle` of the DEBUG fixture sample.
    static let fixtureTitle = "Test fixture"

    /// Photos, Files, Mail, Calendar, Contacts: the order the panel lists them in.
    static let phoneOrder: [PhoneSource] = [.photos, .files, .mail, .calendar, .contacts]

    /// Every real source with a switch. The Inbox's switch ("Use imported items") only does something once
    /// something was imported, so it is listed only then.
    static func sources(inboxHasImports: Bool) -> [Source] {
        phoneOrder.map { Source(id: $0.id, title: $0.title, phone: $0) }
            + (inboxHasImports ? [Source(id: inboxId, title: "Inbox", phone: nil)] : [])
    }

    // MARK: The adapter (ruling C-16)

    /// The run going now as the panel draws it. `RunCoordinator.current` publishes at most ~8 times a second, and only
    /// the panel observes it, so Home's body never redraws per tick.
    static func progress(_ run: RunProgress) -> HomeScanProgress {
        let stages = run.stages.map { stage -> HomeScanProgress.Stage in
            let c = run.counts[stage]
            return HomeScanProgress.Stage(id: stage.rawValue, name: stage.title, done: c?.done ?? 0, total: c?.total,
                                          started: c != nil, running: stage == run.stage && c?.finished != true)
        }
        // The sources stage names the source it reads ("Photos · 1 of 3"): that source's row moves live.
        var reading: HomeScanProgress.Stage?
        if run.stage == .sources, let title = sourceTitle(part: run.part), let id = sourceId(part: run.part),
           let c = run.counts[.sources], !c.finished {
            reading = HomeScanProgress.Stage(id: id, name: title, done: c.done, total: c.total)
        }
        return HomeScanProgress(reason: "\(run.reason.title) · step \(run.stageNumber) of \(run.stages.count)",
                                stage: run.stage.title + (run.part.map { " · \($0)" } ?? ""),
                                currentItem: run.item.flatMap { $0.isEmpty ? nil : $0 },
                                stages: stages,
                                reading: reading,
                                counts: countLine(run),
                                fraction: run.count.flatMap { c in c.total.map { $0 > 0 ? min(1, Double(c.done) / Double($0)) : 0 } },
                                cancelling: run.cancelling)
    }

    /// "312 of 1,204 items · 48/s · about 20 s left"; "Starting" before the stage has counted anything.
    static func countLine(_ run: RunProgress) -> String {
        var parts: [String] = []
        let unit = run.stage.unit
        if let c = run.count, let t = c.total {
            parts.append("\(c.done.formatted()) of \(t.formatted()) \(unit)")
        } else if let c = run.count, c.done > 0 {
            parts.append("\(c.done.formatted()) \(unit)")
        } else {
            parts.append("Starting")
        }
        if let r = run.rate, r > 0 { parts.append("\(ScanSnapshot.rateText(r))/s") }
        if let eta = run.eta { parts.append("about \(ScanSnapshot.duration(eta)) left") }
        return parts.joined(separator: " · ")
    }

    /// The source a sources-stage `part` names ("Photos · 1 of 3" → "photos"; the DEBUG fixture → the sample's key).
    static func sourceId(part: String?) -> String? {
        guard let title = sourceTitle(part: part) else { return nil }
        if title == fixtureTitle { return sampleKey }
        return PhoneSource.allCases.first { $0.title == title }?.id
    }

    private static func sourceTitle(part: String?) -> String? {
        guard let part, let first = part.components(separatedBy: " · ").first, !first.isEmpty else { return nil }
        return first
    }

    // MARK: The panel's state

    enum State: Equatable {
        /// A run goes: the expanded panel.
        case running
        /// The saved results are still being read back at launch.
        case loading
        /// Loaded, and the watchers have no results: say so, with Run now.
        case notChecked
        /// Loaded, results in hand: the last run in one line, with Run now.
        case last
        /// Results in hand but no run record (saved before runs were recorded): nothing to say.
        case hidden
    }

    static func state(running: Bool, loaded: Bool, checked: Bool, hasLast: Bool) -> State {
        if running { return .running }
        if !loaded { return .loading }
        if !checked { return .notChecked }
        return hasLast ? .last : .hidden
    }

    /// "Checked on request · 2 minutes ago".
    static func lastTitle(_ r: RunRecord) -> String {
        "\(lastWhat(r.reason)) · \(r.endedAt.formatted(.relative(presentation: .named)))"
    }

    static func lastWhat(_ reason: RunReason) -> String {
        switch reason {
        case .firstCheck: return "First check"
        case .nightly: return "Overnight check"
        case .manual: return "Checked on request"
        case .scanAgain: return "Scan again"
        case .itemsChanged: return "Re-check"
        }
    }

    // MARK: Rows

    /// A source row reads live only while its own source is read (not merely while the run goes).
    static func rowReading(_ stage: HomeScanProgress.Stage?) -> Bool { stage?.running == true }

    /// A source row's line: "Reading · 120 of 300" while it is read; otherwise its resting count, or "Off".
    static func rowLine(stage: HomeScanProgress.Stage?, on: Bool, restingCount: Int) -> String {
        if let stage, rowReading(stage) { return "Reading · \(stageCount(stage))" }
        guard on else { return "Off" }
        return "\(restingCount.formatted()) \(restingCount == 1 ? "item" : "items")"
    }

    /// "120 of 300", or "120" while the total is not known.
    static func stageCount(_ st: HomeScanProgress.Stage) -> String {
        st.total.map { "\(st.done.formatted()) of \($0.formatted())" } ?? st.done.formatted()
    }

    /// A stage's line in the panel: "Waiting" before it starts, its count while it runs, "Done · 1,204" after.
    static func stageLine(_ st: HomeScanProgress.Stage) -> String {
        guard st.started else { return "Waiting" }
        return st.running ? stageCount(st) : "Done · \(st.done.formatted())"
    }
}

/// What the expanded scan panel shows, as a value (built from `RunCoordinator.current` by `HomeScanPanelModel.progress`):
/// the reason and step, the stage, the item being read, each stage's done/total, the source being read, the count
/// line with rate and time left, and whether Cancel was tapped.
struct HomeScanProgress: Equatable {
    struct Stage: Equatable, Identifiable {
        /// A run stage's raw value ("privacy"), or a source id ("photos") for `reading`.
        let id: String
        let name: String
        let done: Int
        let total: Int?
        /// Counted at all (a stage not started yet is not).
        var started = true
        /// Being worked on now.
        var running = true
    }

    /// "Run now · step 2 of 5".
    let reason: String
    /// "Privacy check", "Reading your sources · Photos · 1 of 3".
    let stage: String
    /// The live scan's masked item name.
    let currentItem: String?
    let stages: [Stage]
    /// The source the sources stage reads now, with its count; nil otherwise.
    let reading: Stage?
    /// "312 of 1,204 items · 48/s · about 20 s left".
    let counts: String
    /// The current stage's done over total, when its total is known.
    let fraction: Double?
    /// Cancel was tapped; the run stops at its next item.
    let cancelling: Bool
}

/// Home's scan slot (owner rulings O-5, C-16): whatever started a run (Run now, Scan again, the first check after
/// onboarding, the night), Home shows it here live, with Cancel and the per-source switches; idle, the last run in one
/// line with Run now; before anything was checked, "Not checked yet" with Run now; while the saved results load at
/// launch, "Loading the last results…". Observes `RunCoordinator` itself (it publishes at most ~8 times a second), so
/// only this view repaints with a run's progress, never Home's body.
struct HomeRunPanel: View {
    @ObservedObject var runs: RunCoordinator = .shared
    @ObservedObject var sources: SourcesService
    /// Every check's saved results have been read back (the watchers', the privacy check's, mail triage's).
    let loaded: Bool
    /// The watchers have results (from a run, or saved).
    let checked: Bool
    /// A source is on, so a run has something to read; otherwise the way to What Loupe reads.
    let canRun: Bool
    let openReads: () -> Void

    var body: some View {
        // No `home.scan` id on a wrapper: SwiftUI folds a one-child wrapper into its child, and the wrapper's id then
        // replaced the child's own (`findings.notChecked`, `run.panel`), which main's UI tests read.
        Group {
            switch HomeScanPanelModel.state(running: runs.current != nil, loaded: loaded, checked: checked, hasLast: runs.last != nil) {
            case .running:
                if let p = runs.current {
                    HomeScanPanel(progress: HomeScanPanelModel.progress(p), sources: sources, onCancel: { runs.cancel() })
                }
            case .loading:
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Loading the last results…").font(.subheadline).foregroundStyle(Palette.inkSoft)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .card()
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("findings.loading")
            case .notChecked:
                notChecked
            case .last:
                if let last = runs.last { HomeRunSummary(last: last) { runNowButton } }
            case .hidden:
                EmptyView()
            }
        }
    }

    private var notChecked: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Not checked yet").font(Typeface.display(20)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("Loupe checks your sources overnight while the phone charges. Run the check now to see what it finds.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // A run that stopped before the watchers (cancelled, say) still says what happened.
            if let last = runs.last { HomeRunLastLines(last: last) }
            runNowButton
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("findings.notChecked")
    }

    @ViewBuilder private var runNowButton: some View {
        if canRun {
            CardAction(title: "Run now", symbol: "arrow.clockwise", hue: Palette.cyan) { runs.runNow(reason: .manual) }
                .accessibilityLabel("Run the check now")
                .accessibilityHint("Reads every source that is on, then runs the privacy check, mail triage, the watchers and the sort")
                .accessibilityIdentifier("run.runNow")
        } else {
            CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                .accessibilityIdentifier("run.connect")
        }
    }
}

/// The expanded panel while a run goes: the reason and step, Cancel, the stage, the item being read, the progress, the
/// count line with rate and time left, each stage, then every real source with its live count and its switch, which
/// can be flipped during the run. Draws a `HomeScanProgress` only.
struct HomeScanPanel: View {
    let progress: HomeScanProgress
    @ObservedObject var sources: SourcesService
    /// Cancels the running check; nil when it cannot be cancelled (no Cancel button then).
    let onCancel: (() -> Void)?

    var body: some View {
        let p = progress
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Caption(text: "Checking now")
                        .accessibilityAddTraits(.isHeader)
                    Text(p.reason)
                        .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("run.reason")
                }
                Spacer(minLength: 8)
                if let onCancel {
                    Button(role: .cancel, action: onCancel) {
                        Label(p.cancelling ? "Stopping…" : "Cancel", systemImage: "xmark.circle")
                            .font(.footnote.weight(.semibold))
                            .padding(.horizontal, 8)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .disabled(p.cancelling)
                    .accessibilityLabel(p.cancelling ? "Stopping the check" : "Cancel the check")
                    .accessibilityIdentifier("run.cancel")
                }
            }
            Text(p.stage)
                .font(Typeface.display(20)).foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("run.stage")
            if let item = p.currentItem {
                Text(item)
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .lineLimit(2)
                    .accessibilityIdentifier("run.item")
            }
            SweepBar(fraction: p.fraction ?? 0, color: Palette.cyan, height: 6, live: true)
                .accessibilityElement()
                .accessibilityLabel("Progress")
                .accessibilityValue(p.fraction.map { "\(Int(($0 * 100).rounded())) percent" } ?? "counting")
            Text(p.counts)
                .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("run.counts")
            VStack(alignment: .leading, spacing: 4) {
                ForEach(p.stages) { st in
                    HStack(spacing: 8) {
                        Text(st.name).font(.footnote.weight(.semibold))
                            .foregroundStyle(st.running ? Palette.ink : Palette.inkSoft)
                        Spacer(minLength: 8)
                        Text(HomeScanPanelModel.stageLine(st)).font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("home.scan.stage.\(st.id)")
                }
            }
            Divider().overlay(Palette.hairline)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(HomeScanPanelModel.sources(inboxHasImports: !sources.inboxBatches.isEmpty)) { source in
                    HomeScanSourceRow(source: source, sources: sources,
                                      live: p.reading?.id == source.id ? p.reading : nil)
                }
            }
            Text("Turning a source off takes its items out of every check. Nothing leaves this iPhone except Mail, which you connected.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(active: true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run.panel")
    }
}

/// The last run's title and outcome ("Checked on request · 2 minutes ago" / "Checked 1,204 items · 3 new findings").
private struct HomeRunLastLines: View {
    let last: RunRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(HomeScanPanelModel.lastTitle(last))
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("run.lastTitle")
            Text(last.outcomeLine)
                .font(.subheadline).foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("run.last")
        }
    }
}

/// The panel collapsed: the last run in one line, with Run now.
private struct HomeRunSummary<RunNow: View>: View {
    let last: RunRecord
    @ViewBuilder let runNow: () -> RunNow

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: last.outcome == .finished ? "checkmark.seal.fill" : "pause.circle.fill")
                    .foregroundStyle(last.outcome == .finished ? Palette.okText : Palette.warnText)
                    .accessibilityHidden(true)
                HomeRunLastLines(last: last)
                Spacer(minLength: 0)
            }
            runNow()
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .padding(12)
        .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run.panel")
    }
}

/// One source in the panel: its glyph, name, live count and switch (the same switch as on What Loupe reads).
private struct HomeScanSourceRow: View {
    let source: HomeScanPanelModel.Source
    @ObservedObject var sources: SourcesService
    /// The source while the run reads it: its count then moves live.
    let live: HomeScanProgress.Stage?

    private var on: Bool {
        if let phone = source.phone { return sources.isPhoneEnabled(phone) }
        return sources.inboxEnabled
    }

    private var restingCount: Int {
        if let phone = source.phone { return sources.state(phone).itemCount }
        return sources.inboxBatches.reduce(0) { $0 + Int($1.itemCount) }
    }

    private var countLine: String { HomeScanPanelModel.rowLine(stage: live, on: on, restingCount: restingCount) }

    var body: some View {
        HStack(spacing: 12) {
            SourceGlyph(id: source.id, on: on, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(source.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(countLine)
                    .font(Typeface.mono(11)).monospacedDigit()
                    .foregroundStyle(HomeScanPanelModel.rowReading(live) ? SourceLook.hue(source.id) : Palette.inkSoft)
                    .accessibilityIdentifier("home.scan.source.\(source.id).count")
            }
            Spacer(minLength: 8)
            Toggle(source.title, isOn: Binding(get: { on }, set: { value in
                if let phone = source.phone {
                    Task { await sources.setPhoneEnabled(phone, value) }
                } else {
                    sources.setInboxEnabled(value)
                }
            }))
            .labelsHidden()
            .accessibilityLabel(source.title)
            .accessibilityHint(on ? "Turns \(source.title) off; its items leave every check." : "Turns \(source.title) on and reads it.")
            .accessibilityIdentifier("home.scan.source.\(source.id).toggle")
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.scan.source.\(source.id)")
    }
}
