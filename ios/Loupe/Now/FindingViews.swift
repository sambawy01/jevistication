import SwiftUI
import LoupeKit

/// Colour and symbol per watcher.
extension WatcherKind {
    var symbol: String {
        switch self {
        case .impersonation: return "person.crop.circle.badge.exclamationmark"
        case .siteFraud: return "link.badge.plus"
        case .expiry: return "calendar.badge.exclamationmark"
        case .termChange: return "arrow.up.right"
        default: return "repeat"
        }
    }

    var tint: Color {
        switch self {
        case .impersonation, .siteFraud: return Palette.dangerText
        case .expiry, .termChange: return Palette.warnText
        default: return Palette.blue
        }
    }
}

/// The top finding, inside the navy hero.
struct HeroFindingCard: View {
    let finding: WatcherFinding
    let more: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: finding.watcher.symbol)
                Text(finding.watcherTitle.uppercased()).font(Typeface.mono(11, weight: .medium))
                Spacer()
                if finding.sample { Pill(text: "Sample", color: .white) }
            }
            .foregroundStyle(Palette.cyan)
            Text(finding.title)
                .font(Typeface.display(22))
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            if let line = finding.evidence.first {
                Text(line).font(Typeface.mono(12)).foregroundStyle(.white.opacity(0.85)).lineLimit(2)
            }
            if more > 0 {
                Text("+ \(more) more below").font(.caption).foregroundStyle(.white.opacity(0.7))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(.white.opacity(0.15)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("now.topFinding")
    }
}

/// One finding: the watcher, the evidence, why, and the three verdicts plus "Open item".
struct FindingCard: View {
    let finding: WatcherFinding
    let index: Int
    let onVerdict: (FindingVerdict) -> Void
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: finding.watcher.symbol)
                Text(finding.watcherTitle).font(Typeface.mono(11, weight: .medium))
                Spacer()
                if finding.verdict == .confirmed { Pill(text: "Confirmed", color: Palette.okText, symbol: "checkmark") }
                if finding.sample { Pill(text: "Sample", color: Palette.inkSoft) }
            }
            .foregroundStyle(finding.watcher.tint)
            Text(finding.title)
                .font(.headline)
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("finding.title.\(index)")
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(finding.evidence.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(Typeface.mono(12))
                        .foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.track, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Evidence: " + finding.evidence.joined(separator: ". "))
            Text(finding.why).font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                if finding.verdict != .confirmed {
                    verdictButton(.confirmed, "checkmark")
                }
                verdictButton(.dismissed, "xmark")
                verdictButton(.notRelevant, "minus.circle")
                Spacer(minLength: 0)
                if !finding.itemId.isEmpty {
                    Button("Open item", action: onOpen)
                        .font(.caption.weight(.semibold))
                        .accessibilityIdentifier("finding.open.\(index)")
                }
            }
        }
        .card()
        .accessibilityIdentifier("finding.\(index)")
    }

    private func verdictButton(_ v: FindingVerdict, _ symbol: String) -> some View {
        Button { onVerdict(v) } label: {
            Label(v.title, systemImage: symbol).font(.caption.weight(.semibold)).labelStyle(.titleAndIcon)
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
        .tint(v == .confirmed ? Palette.okText : Palette.inkSoft)
        .accessibilityIdentifier("finding.\(v.label.replacingOccurrences(of: " ", with: "-")).\(index)")
    }
}

/// The subscriptions census: a monthly total and every recurring merchant.
struct CensusCard: View {
    let census: SubscriptionCensus

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Subscriptions").font(.headline).foregroundStyle(Palette.ink)
                Spacer()
                if census.sample { Pill(text: "Sample", color: Palette.inkSoft) }
            }
            if census.rows.isEmpty {
                Text("No merchant charged three or more times in \(census.chargesFound) charge(s) read. A cadence needs three.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            } else {
                HStack(alignment: .firstTextBaseline) {
                    Text(WatchersService.money(census.monthlyTotalMinor))
                        .font(Typeface.display(30)).foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("census.total")
                    Text("a month").font(.subheadline).foregroundStyle(Palette.inkSoft)
                }
                ForEach(census.rows, id: \.merchant) { r in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.merchant).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                            Text("\(r.cadence), \(r.occurrences) charges, last \(r.lastChargedIso)")
                                .font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                        Spacer()
                        Text(r.monthlyMinor.map { WatchersService.money($0.int64Value) + "/mo" } ?? "irregular")
                            .font(Typeface.mono(13)).foregroundStyle(Palette.ink)
                    }
                }
                Text("From \(census.chargesFound) charge(s) in receipts and statements. Amounts as written, currency not converted.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
        }
        .card()
        .accessibilityIdentifier("now.census")
    }
}

/// "Open item": what the watchers read, verbatim.
struct ItemTextView: View {
    let item: SourceItem
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if item.sourceId == SourcesService.sampleId {
                        Pill(text: SourcesService.sampleLabel, color: Palette.inkSoft)
                    }
                    Text(item.location).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    Text(item.text)
                        .font(Typeface.mono(13))
                        .foregroundStyle(Palette.ink)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .card()
                }
                .padding(16)
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
extension SourceItem: Identifiable {}
