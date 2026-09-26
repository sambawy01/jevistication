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
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let line = finding.evidence.first {
                Text(line).font(Typeface.mono(12)).foregroundStyle(Palette.ink).lineLimit(2)
            }
            if more > 0 {
                Text("+ \(more) more below").font(.caption).foregroundStyle(Palette.inkSoft)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Palette.hairline))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("now.topFinding")
    }
}

/// One finding: the watcher, the evidence, why, and the three verdicts plus "Open item".
struct FindingCard: View {
    let finding: WatcherFinding
    let index: Int
    var item: SourceItem? = nil
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
            if let item { ItemRefHeader(item: item) }
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

/// Now's door to Guard: how many warnings, the subscriptions' monthly total and the next expiry, one tap away.
struct GuardDoorCard: View {
    let summary: WatcherSummary

    var body: some View {
        let open = summary.findings.filter { $0.verdict != .confirmed }.count
        let next = summary.expiries.first { $0.daysRemaining >= 0 } ?? summary.expiries.first
        HStack(spacing: 12) {
            NeonIcon(name: "shield.lefthalf.filled", color: Palette.cyan, size: 24, active: open > 0)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 3) {
                Text(open == 0 ? "Guard: nothing raised" : "Guard: \(open) warning\(open == 1 ? "" : "s")")
                    .font(.headline).foregroundStyle(Palette.ink)
                HStack(spacing: 10) {
                    if !summary.census.rows.isEmpty {
                        Label(WatchersService.money(summary.census.monthlyTotalMinor) + "/mo", systemImage: "repeat")
                    }
                    if let next {
                        Label(GuardModel.daysLeftLine(next.daysRemaining), systemImage: "calendar.badge.exclamationmark")
                    }
                }
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                .lineLimit(1).minimumScaleFactor(0.8)
                Text("Subscriptions, expiry dates and every finding")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens Guard")
    }
}

/// Now's card for browsing protection: "Loupe spotted N risky sites this week", opening Guard → Protection → Spotted.
struct SpottedNowCard: View {
    let headline: String
    let dangerous: Int

    var body: some View {
        HStack(spacing: 12) {
            NeonIcon(name: "safari", color: dangerous > 0 ? Palette.dangerText : Palette.warnText, size: 24, active: true)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 3) {
                Text(headline).font(.headline).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(dangerous > 0 ? "\(dangerous) dangerous · see what was spotted" : "See what was spotted")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens Guard, Protection, Spotted")
    }
}

/// "Open item": what the watchers read, verbatim.
struct ItemTextView: View {
    let item: SourceItem
    var finding: PrivacyFinding? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if item.sourceId == SourcesService.sampleId {
                        Pill(text: SourcesService.sampleLabel, color: Palette.inkSoft)
                    }
                    if let imported = item.facts["imported"] {
                        Pill(text: imported, color: Palette.inkSoft)
                    }
                    ItemRefHeader(item: item)
                    ItemActions(item: item, finding: finding)
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
            .neonGround()
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
extension SourceItem: Identifiable {}
