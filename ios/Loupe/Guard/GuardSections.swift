import LoupeKit
import SwiftUI

// The Guard's sections: Subscriptions (the recurring-money watcher), Expiring soon (the expiry radar) and one section
// per other watcher. Each takes plain values from the latest run, so it redraws only when the run's summary changes.

// MARK: - Subscriptions

struct SubscriptionsSection: View {
    let census: SubscriptionCensus
    let findings: [WatcherFinding]
    let coverage: GuardCoverage
    let openSources: () -> Void

    private static let hues: [Color] = [Palette.cyan, Palette.blueBright, Palette.mint, Palette.amber, Palette.blue, Palette.red]

    var body: some View {
        let rows = GuardModel.sortedSubscriptions(census.rows)
        // One total per currency (task T-B, part 2): never one number mixing currencies.
        let byCurrency = GuardModel.monthlyByCurrency(rows)
        VStack(alignment: .leading, spacing: 14) {
            if rows.isEmpty {
                empty
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(byCurrency.enumerated()), id: \.offset) { _, group in
                        totalRow(rows.filter { $0.currency == group.currency }, currency: group.currency, total: group.minor, allRows: rows)
                    }
                }
                Divider().overlay(Palette.hairline)
                VStack(spacing: 4) {
                    ForEach(Array(rows.enumerated()), id: \.element.merchant) { i, row in
                        let ownTotal = byCurrency.first { $0.currency == row.currency }?.minor ?? 0
                        NavigationLink(value: GuardRoute.subscription(row.merchant)) {
                            SubscriptionRow(row: row, hue: Self.hues[i % Self.hues.count], share: GuardModel.share(row, total: ownTotal),
                                            quiet: GuardModel.quietFinding(row, in: findings))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("guard.subscription.\(i)")
                    }
                }
                Text("From \(census.chargesFound) charge\(census.chargesFound == 1 ? "" : "s") in receipts and statements. Amounts as written, currency not converted\(census.setAside > 0 ? "; \(census.setAside) set aside by you" : "").")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("guard.subscriptions")
    }

    /// One currency's total and its own donut (each merchant's slice sized against its own currency's total, never
    /// mixed with another currency's amounts). `allRows` is the full, sorted list, so a merchant's hue here matches
    /// its hue in the list below regardless of which currency group it falls into.
    private func totalRow(_ rowsInCurrency: [CensusRow], currency: String, total: Int64, allRows: [CensusRow]) -> some View {
        let line = currency.isEmpty ? WatchersService.money(total) : "\(currency) \(WatchersService.money(total))"
        return HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Caption(text: currency.isEmpty ? "Every month" : "Every month · \(currency)")
                Text(line)
                    .font(Typeface.mono(38, weight: .bold)).monospacedDigit()
                    .foregroundStyle(Palette.ink)
                    .minimumScaleFactor(0.6).lineLimit(1)
                    .accessibilityIdentifier("guard.subscriptions.total")
                Text("\(rowsInCurrency.count) recurring charge\(rowsInCurrency.count == 1 ? "" : "s")")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Subscriptions: \(line) a month, \(rowsInCurrency.count) recurring charge\(rowsInCurrency.count == 1 ? "" : "s")")
            Spacer(minLength: 8)
            Donut(parts: rowsInCurrency.compactMap { r in
                guard let m = r.monthlyMinor, let i = allRows.firstIndex(where: { $0.merchant == r.merchant }) else { return nil }
                return (Self.hues[i % Self.hues.count], Double(m.int64Value))
            }, lineWidth: 9)
            .frame(width: 64, height: 64)
        }
    }

    @ViewBuilder private var empty: some View {
        if !coverage.mail {
            GuardEmpty(symbol: "envelope.badge", title: "Turn on Mail to find subscriptions",
                       message: "The census reads receipts and \"you've been charged\" mail, and statement CSVs you import. Nothing is read until a source is on.",
                       action: "Open Sources", id: "guard.subscriptions.empty", perform: openSources)
        } else {
            GuardEmpty(symbol: "repeat", title: "No recurring charges yet",
                       message: "No merchant charged three or more times in the \(census.chargesFound) charge\(census.chargesFound == 1 ? "" : "s") read. A cadence needs three.",
                       id: "guard.subscriptions.empty")
        }
    }
}

/// One merchant: its tile, the cadence, last seen and (when the census can say) the next charge, the monthly figure
/// and its share of the total.
struct SubscriptionRow: View {
    let row: CensusRow
    let hue: Color
    let share: Double
    let quiet: WatcherFinding?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            MerchantTile(name: row.merchant, hue: hue)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row.merchant).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                    if row.verdict == .confirmed { Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(Palette.okText) }
                }
                Text(detailLine).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(2)
                if let quiet {
                    Pill(text: "No charge for \(row.daysSinceLastCharge) days", color: Palette.warnText, symbol: "exclamationmark.triangle.fill")
                        .accessibilityLabel(quiet.title)
                }
                SweepBar(fraction: share, color: hue, height: 3)
                    .frame(maxWidth: 160)
                    .accessibilityHidden(true)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 2) {
                Text(GuardModel.monthlyAmount(row) ?? "irregular")
                    .font(Typeface.mono(15, weight: .semibold)).monospacedDigit()
                    .foregroundStyle(row.monthlyMinor == nil ? Palette.inkSoft : Palette.ink)
                Text(row.monthlyMinor == nil ? "typ. \(WatchersService.money(row.typicalMinor))" : "/ month")
                    .font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft)
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .padding(.vertical, 6)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLine)
        .accessibilityAddTraits(.isButton)
    }

    private var detailLine: String {
        var parts = [row.cadence.capitalized, "last \(GuardModel.day(row.lastChargedIso))"]
        if let next = row.nextExpectedIso { parts.append("next \(GuardModel.day(next))") }
        return parts.joined(separator: " · ")
    }

    private var accessibilityLine: String {
        var parts = [row.merchant, GuardModel.perMonth(row).replacingOccurrences(of: "/mo", with: " a month"), row.cadence,
                     "last charged \(GuardModel.day(row.lastChargedIso))"]
        if let next = row.nextExpectedIso { parts.append("next expected \(GuardModel.day(next))") }
        if let quiet { parts.append(quiet.title) }
        if row.verdict == .confirmed { parts.append("confirmed by you") }
        return parts.joined(separator: ", ")
    }
}

/// A merchant's initial in a tile of its hue (the census has no logos, and Loupe fetches none).
struct MerchantTile: View {
    let name: String
    let hue: Color
    var size: CGFloat = 40

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
        ZStack {
            shape.fill(LinearGradient(colors: [hue.opacity(0.30), hue.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing))
            shape.stroke(hue.opacity(0.6), lineWidth: 1)
            Text(String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                .font(Typeface.display(size * 0.5))
                .foregroundStyle(hue)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - Expiring soon

struct ExpirySection: View {
    let rows: [ExpiryRow]
    let ruleName: String
    let itemsChecked: Int
    let half: GuardModel.ModelHalf
    let coverage: GuardCoverage
    let openSources: () -> Void

    var body: some View {
        let groups = GuardModel.groupExpiries(rows)
        // Older (expired more than a year ago) is last and collapsed by default, never an alert (task T-B, part 2).
        // `groupExpiries` already places it last (it iterates `ExpiryBucket.allCases`, `.older` being the final
        // case), so this only changes how it renders, not its position.
        let activeGroups = groups.filter { $0.bucket != .older }
        let olderGroup = groups.first { $0.bucket == .older }
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 12) {
                if groups.isEmpty {
                    empty
                } else {
                    ForEach(activeGroups) { group in
                        ExpiryGroupView(group: group, half: half)
                    }
                    if let olderGroup {
                        OlderExpiryDisclosure(group: olderGroup, half: half)
                    }
                    Text("Dates are arithmetic, read on this iPhone: an expiry word near a date, however far away, or already passed. \"Inside the rule\" means less than \(ruleName).")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("guard.expiry")
            // The model half: what each document is. The mechanical timeline above shows either way.
            switch half {
            case .locked:
                NeedsLayaCard(feature: "guardExpiry", what: "Judging what each document is (a passport, an insurance policy, a card)")
            case .turnedOff:
                LayaOffBanner(feature: Features.shared.WATCHERS)
            case .ran, .pending:
                EmptyView()
            }
        }
    }

    @ViewBuilder private var empty: some View {
        if !coverage.documents {
            GuardEmpty(symbol: "doc.text.magnifyingglass", title: "Turn on Files or Photos to find expiry dates",
                       message: "The radar reads documents, scans and screenshots for passports, IDs, insurance, warranties, cards and contracts. Nothing is read until a source is on.",
                       action: "Open Sources", id: "guard.expiry.empty", perform: openSources)
        } else {
            GuardEmpty(symbol: "calendar", title: "No expiry dates found",
                       message: "None of the \(itemsChecked) item\(itemsChecked == 1 ? "" : "s") read has an expiry word near a date. Not an all-clear: a document Loupe cannot read is not checked.",
                       id: "guard.expiry.empty")
        }
    }
}

extension GuardModel.ExpiryBucket {
    var hue: Color {
        switch self {
        case .overdue: return Palette.dangerText
        case .thisWeek: return Palette.warnText
        case .thisMonth: return Palette.amber
        case .later: return Palette.blue
        case .older: return Palette.inkSoft
        }
    }
}

/// One bucket of the timeline: its heading, then each document on a rail.
struct ExpiryGroupView: View {
    let group: GuardModel.ExpiryGroup
    let half: GuardModel.ModelHalf

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(group.bucket.hue).frame(width: 8, height: 8).neonGlow(group.bucket.hue, radius: 3)
                Text(group.bucket.title.uppercased())
                    .font(Typeface.mono(11, weight: .bold)).tracking(1)
                    .foregroundStyle(group.bucket.hue)
                Text("\(group.rows.count)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                Spacer()
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("guard.expiry.group.\(group.bucket)")
            ForEach(Array(group.rows.enumerated()), id: \.element.itemId) { i, row in
                NavigationLink(value: GuardRoute.expiry(row.itemId)) {
                    ExpiryTimelineRow(row: row, bucket: group.bucket, half: half, last: i == group.rows.count - 1)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("guard.expiry.row")
            }
        }
    }
}

/// The collapsed "Older" group (task T-B, part 2): documents expired more than a year ago. Last on the timeline,
/// closed by default (a missed expiry that old is not urgent), disclosed on demand behind a ≥44 pt row with a
/// VoiceOver label naming the count. Never an alert: no "Inside the rule" badge shows inside it either
/// (`ExpiryTimelineRow` suppresses it for this bucket).
struct OlderExpiryDisclosure: View {
    let group: GuardModel.ExpiryGroup
    let half: GuardModel.ModelHalf
    @State private var expanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(group.rows.enumerated()), id: \.element.itemId) { i, row in
                    NavigationLink(value: GuardRoute.expiry(row.itemId)) {
                        ExpiryTimelineRow(row: row, bucket: .older, half: half, last: i == group.rows.count - 1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("guard.expiry.row")
                }
            }
            .padding(.top, 6)
        } label: {
            HStack(spacing: 8) {
                Circle().fill(GuardModel.ExpiryBucket.older.hue).frame(width: 8, height: 8)
                Text(GuardModel.ExpiryBucket.older.title.uppercased())
                    .font(Typeface.mono(11, weight: .bold)).tracking(1)
                    .foregroundStyle(GuardModel.ExpiryBucket.older.hue)
                Text("\(group.rows.count)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                Spacer()
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Older, \(group.rows.count) document\(group.rows.count == 1 ? "" : "s") expired more than a year ago, collapsed")
        .accessibilityIdentifier("guard.expiry.group.older")
    }
}

struct ExpiryTimelineRow: View {
    /// Item headers come from `ItemIndex`, built off the main thread: repaint when it lands.
    @ObservedObject private var itemIndex = ItemIndex.Store.shared
    let row: ExpiryRow
    let bucket: GuardModel.ExpiryBucket
    let half: GuardModel.ModelHalf
    let last: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // The rail: a line down the bucket, the days left in a badge on it.
            ZStack(alignment: .top) {
                if !last {
                    Rectangle().fill(bucket.hue.opacity(0.3)).frame(width: 2).padding(.top, 50)
                }
                VStack(spacing: -2) {
                    Text("\(abs(row.daysRemaining))")
                        .font(Typeface.mono(17, weight: .bold)).monospacedDigit()
                        .foregroundStyle(bucket.hue)
                        .minimumScaleFactor(0.6).lineLimit(1)
                    Text(row.daysRemaining < 0 ? "AGO" : abs(row.daysRemaining) == 1 ? "DAY" : "DAYS")
                        .font(Typeface.mono(8, weight: .medium)).tracking(0.6)
                        .foregroundStyle(Palette.inkSoft)
                }
                .frame(width: 50, height: 46)
                .background(bucket.hue.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(bucket.hue.opacity(0.45), lineWidth: 1))
            }
            .frame(width: 50)
            .frame(maxHeight: .infinity, alignment: .top)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text("\(GuardModel.day(row.expiryIso)) · \(GuardModel.daysLeftLine(row.daysRemaining))")
                    .font(Typeface.mono(12)).monospacedDigit().foregroundStyle(Palette.ink)
                Text(source).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
                HStack(spacing: 6) {
                    if half != .ran || row.documentType == nil {
                        Label(GuardModel.typeLine(row, half: half), systemImage: half == .locked ? "lock.fill" : "questionmark.circle")
                            .font(.caption2).foregroundStyle(half == .locked ? Palette.amber : Palette.inkSoft)
                            .lineLimit(1).minimumScaleFactor(0.8)
                    }
                }
                HStack(spacing: 6) {
                    if GuardModel.showsRuleBadge(row, in: bucket) { Pill(text: "Inside the rule", color: Palette.warnText) }
                    if row.ambiguous { Pill(text: "Ambiguous date", color: Palette.amber, symbol: "questionmark") }
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).padding(.top, 4).accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLine)
        .accessibilityAddTraits(.isButton)
    }

    /// The decision model's judgment first, then the rules' `documentKind`, then the item name (task T-B, part 2;
    /// `GuardModel.documentTitle` — the one titling rule Home's Documents card shares).
    private var title: String { GuardModel.documentTitle(row) }

    /// Where the document is; with its file name too when the title above is not the item's own name.
    private var source: String {
        let place = ItemIndex.item(row.itemId)?.sourceAndFolder
        let namedByKindOrType = row.documentType != nil || row.documentKind != nil
        let parts = (namedByKindOrType ? [row.itemName] : []) + [place].compactMap { $0 }
        return parts.isEmpty ? row.itemName : parts.joined(separator: " · ")
    }

    private var accessibilityLine: String {
        var parts = [title, "\(GuardModel.daysLeftLine(row.daysRemaining)), \(GuardModel.day(row.expiryIso))"]
        if GuardModel.showsRuleBadge(row, in: bucket) { parts.append("inside the rule") }
        if row.ambiguous { parts.append("the date could be read two ways; the earlier reading is used") }
        if half != .ran || row.documentType == nil { parts.append(GuardModel.typeLine(row, half: half)) }
        return parts.joined(separator: ", ")
    }
}

// MARK: - The other watchers

/// A watcher's section: each finding as a row (its title, the first line of evidence), opening the full finding with
/// its answers; or, when it raised nothing, what it read and what to turn on.
struct WatcherSection: View {
    let kind: WatcherKind
    let findings: [WatcherFinding]
    let summary: WatcherSummary
    let coverage: GuardCoverage
    let openSources: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if findings.isEmpty {
                empty
            } else {
                ForEach(Array(findings.enumerated()), id: \.element.key) { i, f in
                    NavigationLink(value: GuardRoute.finding(f.key)) { GuardFindingRow(finding: f) }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("guard.\(kind.id).\(i)")
                    if i < findings.count - 1 { Divider().overlay(Palette.hairline) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("guard.watcher.\(kind.id)")
    }

    @ViewBuilder private var empty: some View {
        switch kind {
        case .impersonation:
            if !coverage.mail {
                GuardEmpty(symbol: "person.crop.circle.badge.questionmark", title: "Turn on Mail to check who writes to you",
                           message: "The watcher learns who your contacts are from the mail you already have (and Contacts, when it is on), then warns when a known name writes from an address it has never used.",
                           action: "Open Sources", id: "guard.impersonation.empty", perform: openSources)
            } else {
                nothingRaised("No known name wrote from a new address in \(summary.emailsChecked) email\(summary.emailsChecked == 1 ? "" : "s").\(coverage.contacts ? "" : " Turn on Contacts to add your address book.")")
            }
        case .siteFraud:
            if !coverage.mail {
                GuardEmpty(symbol: "link.badge.plus", title: "Turn on Mail to check links and senders",
                           message: "Every link in your mail, and each sender's own domain, is scored by the phishing formula Loupe shares with Loupe Station. Origin facts only, on this iPhone.",
                           action: "Open Sources", id: "guard.site-fraud.empty", perform: openSources)
            } else {
                nothingRaised("No caution or danger among \(summary.linksChecked) link\(summary.linksChecked == 1 ? "" : "s") and \(summary.emailsChecked) sender\(summary.emailsChecked == 1 ? "" : "s").")
            }
        default:
            if !coverage.documents && !coverage.mail {
                GuardEmpty(symbol: "arrow.up.right", title: "Turn on Files or Mail to compare versions",
                           message: "Two versions of one document (renewal-2025.pdf and renewal-2026.pdf, or successive mails from one sender) are compared line by line for labelled amounts that moved.",
                           action: "Open Sources", id: "guard.term-change.empty", perform: openSources)
            } else {
                nothingRaised("No labelled amount moved between two versions of a document.")
            }
        }
    }

    private func nothingRaised(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            NeonIcon(name: kind.symbol, color: Palette.inkSoft, size: 16)
            VStack(alignment: .leading, spacing: 2) {
                Text("Nothing raised").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text(text + " That is not an all-clear.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("guard.\(kind.id).none")
    }
}

/// A finding as a row on Guard.
struct GuardFindingRow: View {
    let finding: WatcherFinding

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(finding.watcher.tint.opacity(0.12))
                RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(finding.watcher.tint.opacity(0.5), lineWidth: 1)
                Image(systemName: finding.watcher.symbol).symbolRenderingMode(.hierarchical)
                    .font(.system(size: 16, weight: .semibold)).foregroundStyle(finding.watcher.tint)
            }
            .frame(width: 36, height: 36)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(finding.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if let line = finding.evidence.first {
                    Text(line).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft).lineLimit(2)
                }
                HStack(spacing: 6) {
                    if finding.verdict == .confirmed { Pill(text: "Confirmed", color: Palette.okText, symbol: "checkmark") }
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).padding(.top, 4).accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([finding.watcherTitle, finding.title, finding.evidence.first ?? "",
                             finding.verdict == .confirmed ? "confirmed by you" : ""]
            .filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
    }
}

/// A section with nothing to show: what it needs, and (when a source would help) a button to Sources.
struct GuardEmpty: View {
    let symbol: String
    let title: String
    let message: String
    var action: String? = nil
    let id: String
    var perform: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                NeonIcon(name: symbol, color: Palette.blue, size: 20)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(message).font(.caption).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            if let action {
                CardAction(title: action, symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: perform)
                    .accessibilityIdentifier(id + ".sources")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }
}
