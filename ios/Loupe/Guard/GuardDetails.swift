import LoupeKit
import SwiftUI

// The Guard's detail screens. Each looks its subject up in the watchers' latest run by key, so it follows a re-run,
// and leaves when the subject is set aside (the notice on Guard offers Undo).

// MARK: - A subscription

struct SubscriptionDetailView: View {
    @ObservedObject var watchers: WatchersService
    let merchant: String
    @Environment(\.dismiss) private var dismiss
    @State private var openItem: SourceItem?

    private var row: CensusRow? { watchers.summary?.census.rows.first { $0.merchant == merchant } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let row {
                    header(row)
                    facts(row)
                    if let quiet = GuardModel.quietFinding(row, in: watchers.findings) {
                        FindingCard(finding: quiet, index: 0, onVerdict: { watchers.answer(quiet, $0) }, onOpen: {})
                    }
                    evidence(row)
                    answers(row)
                } else {
                    Text("\(merchant) is no longer in the census.").font(.subheadline).foregroundStyle(Palette.inkSoft).card()
                }
            }
            .padding(16)
        }
        .neonGround()
        .navigationTitle(merchant)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $openItem) { ItemTextView(item: $0) }
    }

    private func header(_ row: CensusRow) -> some View {
        HStack(alignment: .center, spacing: 14) {
            MerchantTile(name: row.merchant, hue: Palette.cyan, size: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.merchant).font(Typeface.display(26)).foregroundStyle(Palette.ink)
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(row.monthlyMinor.map { WatchersService.money($0.int64Value) } ?? "irregular")
                        .font(Typeface.mono(30, weight: .bold)).monospacedDigit().foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("guard.subscription.detail.amount")
                    if row.monthlyMinor != nil { Text("a month").font(.subheadline).foregroundStyle(Palette.inkSoft) }
                }
                HStack(spacing: 6) {
                    if row.verdict == .confirmed { Pill(text: "Confirmed by you", color: Palette.okText, symbol: "checkmark") }
                    if row.sample { Pill(text: "Sample", color: Palette.inkSoft) }
                }
            }
            Spacer(minLength: 0)
        }
        .card()
        .accessibilityElement(children: .combine)
    }

    private func facts(_ row: CensusRow) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            fact("Cadence", row.cadence.capitalized)
            fact("Charges seen", "\(row.occurrences)")
            fact("Typical charge", WatchersService.money(row.typicalMinor))
            fact("Last charge", "\(GuardModel.day(row.lastChargedIso)) · \(row.daysSinceLastCharge) days ago")
            if let next = row.nextExpectedIso {
                fact("Next expected", GuardModel.day(next))
            } else {
                fact("Next expected", row.monthlyMinor == nil ? "not predicted: the charges are irregular" : "not predicted: the expected day passed without a charge")
            }
            Text("Arithmetic on the charges found, on this iPhone. The typical charge is the median, so one odd bill does not skew it; the monthly figure brings weekly, quarterly and annual charges to a month.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityIdentifier("guard.subscription.detail.facts")
    }

    private func fact(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(.subheadline).foregroundStyle(Palette.inkSoft)
            Spacer(minLength: 12)
            Text(v).font(Typeface.mono(13)).monospacedDigit().foregroundStyle(Palette.ink).multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }

    private func evidence(_ row: CensusRow) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Caption(text: "Where it was found · \(row.itemIds.count)")
            if row.itemIds.isEmpty {
                Text("The items behind these charges are no longer in a source that is on.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            ForEach(Array(row.itemIds.reversed().enumerated()), id: \.element) { i, id in
                if let item = ItemIndex.item(id) {
                    Button { openItem = item } label: {
                        HStack {
                            ItemRefHeader(item: item)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Opens what the watcher read")
                    .accessibilityIdentifier("guard.subscription.evidence.\(i)")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("guard.subscription.evidence")
    }

    private func answers(_ row: CensusRow) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Caption(text: "Your answer")
            if row.verdict != .confirmed {
                GuardActionButton(title: "Confirm subscription", symbol: "checkmark", tint: Palette.okText, id: "guard.subscription.confirm") {
                    watchers.answer(row, .confirmed)
                }
            }
            HStack(spacing: 10) {
                GuardActionButton(title: "Not a subscription", symbol: "minus.circle", id: "guard.subscription.notSubscription") {
                    watchers.answer(row, .notRelevant)
                    dismiss()
                }
                GuardActionButton(title: "Set aside", symbol: "xmark", id: "guard.subscription.setAside") {
                    watchers.answer(row, .dismissed)
                    dismiss()
                }
            }
            Text("Written to the corrections log on this iPhone, never rewritten. \"Not a subscription\" and \"Set aside\" leave it out of the total; Undo on Guard puts it back.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .card()
    }
}

// MARK: - A document with an expiry date

struct ExpiryDetailView: View {
    @ObservedObject var watchers: WatchersService
    let itemId: String
    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @State private var openItem: SourceItem?

    private var row: ExpiryRow? { watchers.summary?.expiries.first { $0.itemId == itemId } }
    private var half: GuardModel.ModelHalf {
        GuardModel.modelHalf(modelRan: watchers.summary?.modelRan ?? false, modelReady: readiness.isReady,
                             turnedOff: !settings.useLaya(Features.shared.WATCHERS))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let row {
                    let bucket = GuardModel.ExpiryBucket.of(daysLeft: row.daysRemaining)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(bucket.title.uppercased()).font(Typeface.mono(11, weight: .bold)).tracking(1).foregroundStyle(bucket.hue)
                        Text(GuardModel.day(row.expiryIso)).font(Typeface.mono(30, weight: .bold)).monospacedDigit().foregroundStyle(Palette.ink)
                        Text(GuardModel.daysLeftLine(row.daysRemaining)).font(.headline).foregroundStyle(bucket.hue)
                        HStack(spacing: 6) {
                            if row.breachesRule { Pill(text: "Inside the rule", color: Palette.warnText) }
                            if row.ambiguous { Pill(text: "Ambiguous date", color: Palette.amber, symbol: "questionmark") }
                            if row.sample { Pill(text: "Sample", color: Palette.inkSoft) }
                        }
                        if let line = row.line {
                            Text(line).font(Typeface.mono(12)).foregroundStyle(Palette.ink)
                                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                                .background(Palette.track, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .accessibilityLabel("On the document: \(line)")
                        }
                        if row.ambiguous {
                            Text("The date could be read two ways; the earlier reading is used, since warning early is recoverable and warning late is not.")
                                .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                        }
                        Text("The date is arithmetic, so it is certain once read. Rule: less than \(watchers.summary?.ruleName ?? "six months") is inside it.")
                            .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .card()
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("guard.expiry.detail")
                    modelHalf(row)
                    if let key = row.findingKey, let finding = watchers.findings.first(where: { $0.key == key }) {
                        FindingCard(finding: finding, index: 0, item: ItemIndex.item(row.itemId),
                                    onVerdict: { watchers.answer(finding, $0) },
                                    onOpen: { openItem = watchers.item(row.itemId) })
                    } else if let item = ItemIndex.item(row.itemId) {
                        Button { openItem = item } label: { ItemRefHeader(item: item).frame(minHeight: 44).contentShape(Rectangle()) }
                            .buttonStyle(.plain).card()
                            .accessibilityIdentifier("guard.expiry.detail.open")
                    }
                } else {
                    Text("This document is no longer on the timeline.").font(.subheadline).foregroundStyle(Palette.inkSoft).card()
                }
            }
            .padding(16)
        }
        .neonGround()
        .navigationTitle(row?.itemName ?? "Expiry")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $openItem) { ItemTextView(item: $0) }
    }

    @ViewBuilder private func modelHalf(_ row: ExpiryRow) -> some View {
        switch half {
        case .locked:
            NeedsLayaCard(feature: "guardExpiry", what: "Judging what this document is")
        case .turnedOff:
            LayaOffBanner(feature: Features.shared.WATCHERS)
        case .ran, .pending:
            HStack(spacing: 10) {
                NeonIcon(name: "cpu", color: Palette.cyan, size: 18)
                Text(GuardModel.typeLine(row, half: half)).font(.subheadline).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .card()
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("guard.expiry.detail.type")
        }
    }
}

// MARK: - Any other finding

struct FindingDetailView: View {
    @ObservedObject var watchers: WatchersService
    let key: String
    @State private var openItem: SourceItem?
    @Environment(\.dismiss) private var dismiss

    private var finding: WatcherFinding? { watchers.findings.first { $0.key == key } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let f = finding {
                    FindingCard(finding: f, index: 0, item: ItemIndex.item(f.itemId),
                                onVerdict: { v in
                                    watchers.answer(f, v)
                                    if v != .confirmed { dismiss() }
                                },
                                onOpen: { openItem = watchers.item(f.itemId) })
                    if let other = f.otherItemId, let item = ItemIndex.item(other) {
                        VStack(alignment: .leading, spacing: 8) {
                            Caption(text: "The earlier version")
                            Button { openItem = item } label: { ItemRefHeader(item: item).frame(minHeight: 44).contentShape(Rectangle()) }
                                .buttonStyle(.plain)
                        }
                        .card()
                    }
                } else {
                    Text("This finding was set aside.").font(.subheadline).foregroundStyle(Palette.inkSoft).card()
                }
            }
            .padding(16)
        }
        .neonGround()
        .navigationTitle(finding?.watcherTitle ?? "Finding")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $openItem) { ItemTextView(item: $0) }
    }
}
