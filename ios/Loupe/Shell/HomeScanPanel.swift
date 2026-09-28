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
}

/// Home's scan panel (owner ruling O-5): whatever started a scan (Run now, Scan again, the first check after
/// onboarding), Home shows it happening at the top. While a scan runs: the live display of each running scan (the
/// item being read, each stage's count, the rate and the time left: `ScanDisplay`), then every real source with its
/// live count and its switch, which can be flipped during the scan. Once they have all finished it collapses to one
/// line, and Home's cards below take over.
///
/// Performance: this view does not observe the scans' snapshots (~12 a second): the displays and the counts observe
/// their own scan. It repaints with Home, which a scan's end reaches through `SourcesService` (the source's state and
/// `revision` when it lands, `liveScans` after the settle), so it collapses by the settle at the latest.
struct HomeScanPanel: View {
    /// The scans to show, in `HomeScanPanelModel.scanKeys` order (running or just finished).
    let scans: [LiveScan]
    @ObservedObject var sources: SourcesService

    var body: some View {
        if scans.contains(where: { !$0.finished }) {
            expanded
        } else if let line = HomeScanPanelModel.summaryLine(scans.map(\.snapshot)) {
            HomeScanSummary(line: line)
        }
    }

    private var expanded: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Caption(text: "Reading your sources")
                Spacer(minLength: 0)
            }
            .accessibilityAddTraits(.isHeader)
            ForEach(scans) { scan in
                ScanDisplay(live: scan)
            }
            Divider().overlay(Palette.hairline)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(HomeScanPanelModel.sources(inboxHasImports: !sources.inboxBatches.isEmpty)) { source in
                    HomeScanSourceRow(source: source, sources: sources,
                                      live: scans.first { $0.source == source.id && !$0.finished })
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
}

/// The panel collapsed: one line saying what the scan read.
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
        .accessibilityLabel("Scan finished. \(line)")
        .accessibilityIdentifier("home.scan.summary")
    }
}

/// One source in the panel: its glyph, name, live count and switch (the same switch as on What Loupe reads).
private struct HomeScanSourceRow: View {
    let source: HomeScanPanelModel.Source
    @ObservedObject var sources: SourcesService
    /// The source's scan while it runs: its count then moves live.
    let live: LiveScan?

    private var on: Bool {
        if let phone = source.phone { return sources.isPhoneEnabled(phone) }
        return sources.inboxEnabled
    }

    private var restingCount: Int {
        if let phone = source.phone { return sources.state(phone).itemCount }
        return sources.inboxBatches.reduce(0) { $0 + Int($1.itemCount) }
    }

    var body: some View {
        HStack(spacing: 12) {
            SourceGlyph(id: source.id, on: on, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(source.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if let live {
                    HomeScanLiveCount(live: live)
                } else {
                    Text(on ? Self.countLine(restingCount) : "Off")
                        .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("home.scan.source.\(source.id).count")
                }
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

    static func countLine(_ n: Int) -> String { "\(n.formatted()) \(n == 1 ? "item" : "items")" }
}

/// A running scan's count, observing that scan alone (it publishes ~12 times a second).
private struct HomeScanLiveCount: View {
    @ObservedObject var live: LiveScan

    var body: some View {
        let s = live.snapshot
        Text("Reading · \(s.read.formatted()) read")
            .font(Typeface.mono(11, weight: .medium)).monospacedDigit().foregroundStyle(SourceLook.hue(s.pipeline.source))
            .accessibilityIdentifier("home.scan.source.\(s.pipeline.source).count")
    }
}
