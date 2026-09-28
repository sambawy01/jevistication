import Combine
import LoupeKit
import SwiftUI

/// What Home's scan panel lists and says (owner ruling O-5), as plain values so they are unit-tested: the real
/// sources in a fixed order (never the sample: owner ruling O-6), the running scans in that same order, and the one
/// line the panel collapses to once they have all finished.
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

    /// Photos, Files, Mail, Calendar, Contacts: the order the panel lists them in.
    static let phoneOrder: [PhoneSource] = [.photos, .files, .mail, .calendar, .contacts]

    /// Every real source with a switch. The Inbox's switch ("Use imported items") only does something once
    /// something was imported, so it is listed only then.
    static func sources(inboxHasImports: Bool) -> [Source] {
        phoneOrder.map { Source(id: $0.id, title: $0.title, phone: $0) }
            + (inboxHasImports ? [Source(id: inboxId, title: "Inbox", phone: nil)] : [])
    }

    /// The keys of `SourcesService.liveScans` the panel shows, in the sources' order; the sample's scan is left out.
    static func scanKeys(_ keys: [String]) -> [String] {
        let rank = Dictionary(uniqueKeysWithValues: phoneOrder.enumerated().map { ($1.id, $0) })
        return keys.filter { $0 != sampleKey }
            .sorted { (rank[$0] ?? Int.max, $0) < (rank[$1] ?? Int.max, $1) }
    }

    /// The collapsed line once every scan shown has finished: one scan says its own summary ("Photos: 120 photos ·
    /// 30 with text · 0 bytes out"), several are counted together ("Read 1,234 items from 2 sources"), and a scan that
    /// stopped is named. Nil while any scan still runs, or when there is none.
    static func summaryLine(_ scans: [ScanSnapshot]) -> String? {
        guard !scans.isEmpty, !scans.contains(where: { $0.phase == .running }) else { return nil }
        let finished = scans.filter { $0.phase == .finished }
        let stopped = scans.filter { $0.phase == .failed }.map(\.pipeline.title)
        var line: String
        switch finished.count {
        case 0:
            return "\(stopped.joined(separator: ", ")): the scan stopped"
        case 1:
            let s = finished[0]
            line = "\(s.pipeline.title): \(s.summary ?? "\(s.read.formatted()) \(s.pipeline.unitWord(s.read))")"
        default:
            let read = finished.reduce(0) { $0 + $1.read }
            line = "Read \(read.formatted()) item\(read == 1 ? "" : "s") from \(finished.count) sources"
        }
        if !stopped.isEmpty { line += " · \(stopped.joined(separator: ", ")) stopped" }
        return line
    }

    /// Today's adapter: the live scans' snapshots (in `scanKeys` order) as one progress for the panel. Stages are
    /// the sources being read, each with its done/total; the rate is theirs together, the time left the longest.
    /// When none runs any more, the progress carries the collapsed line (`summaryLine`). Nil when there is no scan.
    static func progress(_ scans: [ScanSnapshot]) -> HomeScanProgress? {
        guard !scans.isEmpty else { return nil }
        let running = scans.filter { $0.phase == .running }
        let stages = scans.map { HomeScanProgress.Stage(id: $0.pipeline.source, name: $0.pipeline.title, done: $0.done, total: $0.total) }
        guard !running.isEmpty else {
            return HomeScanProgress(stage: "Done", currentItem: nil, stages: stages, rate: nil, eta: nil,
                                    summary: summaryLine(scans))
        }
        let rates = running.compactMap(\.rate)
        return HomeScanProgress(stage: "Reading " + running.map(\.pipeline.title).joined(separator: ", "),
                                // Already masked by the scan (initials for contacts, masked names and subjects).
                                currentItem: running.first?.recent.first?.name,
                                stages: stages,
                                rate: rates.isEmpty ? nil : rates.reduce(0, +),
                                eta: running.compactMap(\.eta).max(),
                                summary: nil)
    }

    /// Cancel shows only while the check runs, and only when whoever runs it can cancel it (today's source scans
    /// cannot; the run coordinator's runs will).
    static func showsCancel(_ progress: HomeScanProgress?, canCancel: Bool) -> Bool {
        canCancel && progress?.running == true
    }
}

/// What the scan panel shows, as a value: the stage, the item being read, each stage's done/total, the rate, the
/// time left, and (once finished or stopped) the one-line summary. Built today from the live scans
/// (`HomeScanPanelModel.progress`); after the rebase from main's `RunCoordinator.current` (a `RunProgress`).
struct HomeScanProgress: Equatable {
    struct Stage: Equatable, Identifiable {
        /// A source id for a source's stage ("photos"), so its row shows the live count.
        let id: String
        let name: String
        let done: Int
        let total: Int?
    }

    let stage: String
    let currentItem: String?
    let stages: [Stage]
    /// Items a second.
    let rate: Double?
    /// Seconds left.
    let eta: TimeInterval?
    /// The finished or stopped line; nil while it runs.
    let summary: String?

    var running: Bool { summary == nil }

    /// Done over total across the stages, when every total is known.
    var fraction: Double? {
        guard !stages.isEmpty, stages.allSatisfy({ $0.total != nil }) else { return nil }
        let total = stages.reduce(0) { $0 + ($1.total ?? 0) }
        guard total > 0 else { return nil }
        return min(1, Double(stages.reduce(0) { $0 + $1.done }) / Double(total))
    }

    /// "12/s · about 20 s left".
    var telemetry: String {
        var parts: [String] = []
        if let rate, rate > 0 { parts.append("\(ScanSnapshot.rateText(rate))/s") }
        if let eta { parts.append("about \(ScanSnapshot.duration(eta)) left") }
        return parts.joined(separator: " · ")
    }
}

/// Home's scan panel (owner ruling O-5): whatever started a check (Run now, Scan again, the first check after
/// onboarding), Home shows it happening at the top. While it runs: the stage, the item being read, each stage's
/// count, the rate and the time left, Cancel when the run can be cancelled, then every real source with its live
/// count and its switch, which can be flipped during the check. Once finished it collapses to one line, and Home's
/// cards below take over. It draws a `HomeScanProgress` only, so it binds to any producer of one.
struct HomeScanPanel: View {
    let progress: HomeScanProgress?
    @ObservedObject var sources: SourcesService
    /// Cancels the running check; nil when it cannot be cancelled (no Cancel button then).
    let onCancel: (() -> Void)?

    var body: some View {
        if let progress {
            if progress.running {
                expanded(progress)
            } else if let line = progress.summary {
                HomeScanSummary(line: line)
            }
        }
    }

    private func expanded(_ p: HomeScanProgress) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Caption(text: "Checking now")
                Spacer(minLength: 0)
            }
            .accessibilityAddTraits(.isHeader)
            Text(p.stage)
                .font(Typeface.display(20)).foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("home.scan.stage")
            if let item = p.currentItem {
                Text("Now reading \(item)")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .lineLimit(2)
                    .accessibilityIdentifier("home.scan.current")
            }
            SweepBar(fraction: p.fraction ?? 0, color: Palette.cyan, height: 6, live: true)
                .accessibilityElement()
                .accessibilityLabel("Progress")
                .accessibilityValue(p.fraction.map { "\(Int(($0 * 100).rounded())) percent" } ?? "counting")
            VStack(alignment: .leading, spacing: 4) {
                ForEach(p.stages) { st in
                    HStack(spacing: 8) {
                        Text(st.name).font(.footnote.weight(.semibold)).foregroundStyle(Palette.ink)
                        Spacer(minLength: 8)
                        Text(Self.count(st)).font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("home.scan.stage.\(st.id)")
                }
            }
            if !p.telemetry.isEmpty {
                Text(p.telemetry)
                    .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("home.scan.telemetry")
            }
            if HomeScanPanelModel.showsCancel(p, canCancel: onCancel != nil), let onCancel {
                Button(action: onCancel) {
                    Label("Cancel", systemImage: "xmark.circle")
                        .font(.footnote.weight(.semibold))
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Cancel the check")
                .accessibilityIdentifier("home.scan.cancel")
            }
            Divider().overlay(Palette.hairline)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(HomeScanPanelModel.sources(inboxHasImports: !sources.inboxBatches.isEmpty)) { source in
                    HomeScanSourceRow(source: source, sources: sources,
                                      live: p.stages.first { $0.id == source.id })
                }
            }
            Text("Turning a source off takes its items out of every check. Nothing leaves this iPhone except Mail, which you connected.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(active: true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.scan")
    }

    static func count(_ st: HomeScanProgress.Stage) -> String {
        st.total.map { "\(st.done.formatted()) of \($0.formatted())" } ?? st.done.formatted()
    }
}

/// Today's producer: follows the live scans' snapshots (each publishes ~12 times a second) inside this small view,
/// so only the panel repaints with them, never Home's body.
struct HomeLiveScanPanel: View {
    let scans: [LiveScan]
    @ObservedObject var sources: SourcesService
    @StateObject private var feed = HomeScanFeed()

    var body: some View {
        // RunCoordinator: after the rebase this becomes
        // HomeScanPanel(progress: runs.current.map(HomeScanProgress.init), sources: sources, onCancel: runs.cancel)
        HomeScanPanel(progress: HomeScanPanelModel.progress(snapshots), sources: sources, onCancel: nil)
            .onAppear { feed.track(scans) }
            .onChange(of: scans.map(\.id)) { _, _ in feed.track(scans) }
    }

    /// The feed's once it follows these scans; until then (the first pass) their snapshots as they are now.
    private var snapshots: [ScanSnapshot] { feed.follows(scans) ? feed.snapshots : scans.map(\.snapshot) }
}

/// The live scans' latest snapshots, in order; re-subscribes only when the set of scans changes.
@MainActor
final class HomeScanFeed: ObservableObject {
    @Published private(set) var snapshots: [ScanSnapshot] = []
    private var ids: [UUID] = []
    private var subscription: AnyCancellable?

    func follows(_ scans: [LiveScan]) -> Bool { ids == scans.map(\.id) }

    func track(_ scans: [LiveScan]) {
        let next = scans.map(\.id)
        guard next != ids else { return }
        ids = next
        snapshots = scans.map(\.snapshot)
        subscription = Publishers.MergeMany(scans.enumerated().map { i, scan in scan.$snapshot.dropFirst().map { (i, $0) } })
            .receive(on: RunLoop.main)
            .sink { [weak self] i, snap in
                guard let self, i < self.snapshots.count else { return }
                self.snapshots[i] = snap
            }
    }
}

/// The panel collapsed: one line saying what the check read.
struct HomeScanSummary: View {
    let line: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "checkmark.seal.fill").foregroundStyle(Palette.okText).accessibilityHidden(true)
            Text(line)
                .font(.subheadline).foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .padding(.horizontal, 12)
        .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Check finished. \(line)")
        .accessibilityIdentifier("home.scan.summary")
    }
}

/// One source in the panel: its glyph, name, live count and switch (the same switch as on What Loupe reads).
private struct HomeScanSourceRow: View {
    let source: HomeScanPanelModel.Source
    @ObservedObject var sources: SourcesService
    /// The source's stage while the check reads it: its count then moves live.
    let live: HomeScanProgress.Stage?

    private var on: Bool {
        if let phone = source.phone { return sources.isPhoneEnabled(phone) }
        return sources.inboxEnabled
    }

    private var restingCount: Int {
        if let phone = source.phone { return sources.state(phone).itemCount }
        return sources.inboxBatches.reduce(0) { $0 + Int($1.itemCount) }
    }

    private var countLine: String {
        if let live { return "Reading · \(HomeScanPanel.count(live))" }
        guard on else { return "Off" }
        return "\(restingCount.formatted()) \(restingCount == 1 ? "item" : "items")"
    }

    var body: some View {
        HStack(spacing: 12) {
            SourceGlyph(id: source.id, on: on, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(source.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(countLine)
                    .font(Typeface.mono(11)).monospacedDigit()
                    .foregroundStyle(live == nil ? Palette.inkSoft : SourceLook.hue(source.id))
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
