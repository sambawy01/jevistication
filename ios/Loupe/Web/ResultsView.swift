import SwiftUI
import LoupeKit

struct ResultsView: View {
    @EnvironmentObject private var web: WebModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let r = web.response {
                    header(r)
                    if !r.live { testBanner }
                    prioritiesCard
                    ForEach(web.shown) { OfferCard(item: $0) }
                    Text("Loupe never books or pays. Copy the offer and finish on Duffel or the airline's site.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft).padding(.top, 4)
                }
            }
            .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("results.screen")
    }

    private var title: String {
        let o = web.form.origin.uppercased(), d = web.form.destination.uppercased()
        return o.isEmpty || d.isEmpty ? "Results" : "\(o) → \(d)"
    }

    private func header(_ r: SearchResponse) -> some View {
        HStack {
            Pill(text: "Online · \(r.source.capitalized) · fetched \(Self.fetchedTime(r.fetchedAt))", color: Palette.cyan, symbol: "globe")
                .accessibilityIdentifier("results.onlineBadge")
            Spacer()
            MascotView(state: mascot, size: 44)
                .accessibilityIdentifier("results.mascot.\(String(describing: mascot))")
        }
    }

    private var mascot: MascotState {
        if case .running = web.ranking { return .scanning }
        return .found
    }

    private var testBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "flask")
            Text("Test data. Duffel test mode: these offers are not real fares.")
                .font(.footnote.weight(.medium))
        }
        .foregroundStyle(Palette.ink)
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.amber.opacity(0.18), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier("results.testBanner")
    }

    private var prioritiesCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Caption(text: "Ranked by: \(web.rankerName)")
                    .accessibilityIdentifier("results.rankedBy")
                Spacer()
                Text("\(web.shown.filter(\.fitsAll).count) of \(web.shown.count) fit the rules")
                    .font(Typeface.mono(12, weight: .medium)).foregroundStyle(Palette.mint)
            }
            if web.priorities.isEmpty {
                Text("No priorities given, so the rules keep price order.").font(.footnote).foregroundStyle(Palette.inkSoft)
            } else {
                Text(web.form.priorities).font(.subheadline).foregroundStyle(Palette.ink)
            }
            if !web.priorities.unrecognised.isEmpty {
                Text("The rules did not understand, and ignored: \(web.priorities.unrecognised.joined(separator: ", "))")
                    .font(.footnote).foregroundStyle(Palette.amber)
            }
            rankingStatus
        }
        .card()
    }

    @ViewBuilder private var rankingStatus: some View {
        switch web.ranking {
        case .idle:
            EmptyView()
        case let .running(done, total):
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: Double(done), total: Double(max(total, 1))).tint(Palette.blue)
                HStack {
                    Text(done == 0 ? "Laya is checking the model on this phone…" : "Laya is reading offer \(min(done + 1, total)) of \(total), on this phone")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                    Spacer()
                    Button("Cancel") { web.cancelRanking() }.font(.caption.weight(.semibold))
                        .accessibilityIdentifier("results.cancelRanking")
                }
                Text("Showing the rule ranking until Laya is done.").font(.caption2).foregroundStyle(Palette.inkSoft)
            }
            .accessibilityIdentifier("results.rankingProgress")
        case .laya:
            Toggle(isOn: $web.showRules) {
                Text("View the rule ranking").font(.footnote)
            }
            .accessibilityIdentifier("results.showRules")
            if let d = web.topDisagreement {
                Label("Laya and the rules disagree on #1: Laya picks \(d.laya.offer.owner) \(d.laya.offer.currency) \(d.laya.offer.totalAmount), the rules pick \(d.rules.offer.owner) \(d.rules.offer.currency) \(d.rules.offer.totalAmount).",
                      systemImage: "arrow.left.arrow.right")
                    .font(.footnote).foregroundStyle(Palette.amber)
                    .accessibilityIdentifier("results.disagreement")
            } else {
                Text("Laya and the rules agree on #1.").font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            let unsure = (web.layaRanked ?? []).filter(\.unsure).count
            Text(unsure == 0 ? "Laya is sure of every answer here. Its percentages are not yet calibrated to your corrections."
                             : "Laya is unsure about \(unsure) offer\(unsure == 1 ? "" : "s"), marked below. Its percentages are not yet calibrated to your corrections.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        case let .rulesOnly(why):
            VStack(alignment: .leading, spacing: 4) {
                Text(rulesText(why)).font(.footnote).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("results.rulesOnly")
                if case .layaOff = why {
                    LayaOffBanner(feature: Features.shared.FLIGHTS)
                } else if case .prioritiesRefused = why {} else {
                    NavigationLink("Get the on-device model") { LayaModelView() }
                        .font(.footnote.weight(.semibold))
                        .accessibilityIdentifier("results.getModel")
                }
            }
        case .cancelled:
            HStack {
                Text("Laya ranking cancelled; showing the rules.").font(.footnote).foregroundStyle(Palette.inkSoft)
                Spacer()
                Button("Rank with Laya") { web.rerank() }.font(.footnote.weight(.semibold))
            }
        }
    }

    private func rulesText(_ why: WebModel.RulesReason) -> String {
        switch why {
        case .modelNotInstalled:
            return "Ranked by the rules only: the on-device model is not on this phone yet."
        case .modelFailed(let m):
            return "Ranked by the rules only: the on-device model could not be used (\(m))."
        case .prioritiesRefused(let reasons):
            return "Ranked by the rules only: Laya cannot use these priorities (\(reasons.joined(separator: "; ")))."
        case .layaOff:
            return "Ranked by the rules only: Laya is off for flights in Model settings."
        }
    }

    static func fetchedTime(_ iso: String) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let d = f.date(from: iso) ?? {
            f.formatOptions = [.withInternetDateTime]
            return f.date(from: iso)
        }()
        guard let d else { return "?" }
        let out = DateFormatter()
        out.dateFormat = "HH:mm"
        return out.string(from: d)
    }
}

struct OfferCard: View {
    let item: RankedOffer
    @State private var showHandOff = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("#\(item.rank)").font(Typeface.display(22)).foregroundStyle(item.fitsAll ? Palette.blue : Palette.inkSoft)
                Text(item.offer.owner).font(.headline).foregroundStyle(Palette.ink).lineLimit(1)
                Spacer()
                Text("\(item.offer.currency) \(item.offer.totalAmount)")
                    .font(Typeface.display(24)).foregroundStyle(Palette.ink)
            }
            ForEach(Array(item.offer.slices.enumerated()), id: \.offset) { _, s in
                HStack(spacing: 8) {
                    Text("\(s.segments.first?.from ?? "") \(LocalTime.hhmm(s.segments.first?.departAt ?? ""))")
                    Image(systemName: "arrow.right").font(.caption2)
                    Text("\(s.segments.last?.to ?? "") \(LocalTime.hhmm(s.segments.last?.arriveAt ?? ""))")
                    Spacer()
                    Text(s.segments.count == 1 ? "nonstop" : "\(s.segments.count - 1) stop")
                    Text(s.segments.map(\.flight).joined(separator: "+")).foregroundStyle(Palette.inkSoft)
                }
                .font(Typeface.mono(12))
                .foregroundStyle(Palette.ink)
            }
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(item.checks.enumerated()), id: \.offset) { _, c in
                    HStack(spacing: 6) {
                        Image(systemName: icon(c.outcome)).foregroundStyle(color(c.outcome)).font(.caption)
                        Text(c.text).font(.caption).foregroundStyle(Palette.ink)
                    }
                }
            }
            if let note = item.note {
                Label(note, systemImage: "scissors").font(.caption2).foregroundStyle(Palette.amber)
            }
            HStack {
                if item.unsure {
                    if item.scoreKind == .model { MascotView(state: .thinking, size: 26) }
                    Pill(text: "unsure", color: Palette.amber, symbol: "questionmark")
                        .accessibilityIdentifier("offer.unsure")
                }
                Pill(text: scoreText, color: item.fitsAll ? Palette.mint : Palette.inkSoft)
                Spacer()
                Button("Open in Duffel/airline") { showHandOff.toggle() }.font(.caption.weight(.semibold))
            }
            if showHandOff {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Loupe does not book. Copy these to finish on Duffel or the airline:")
                        .font(.caption2).foregroundStyle(Palette.inkSoft)
                    Text("Duffel offer \(item.offer.id)\n\(item.offer.slices.flatMap(\.segments).map { "\($0.flight) \($0.from)-\($0.to) \($0.departAt)" }.joined(separator: "\n"))")
                        .font(Typeface.mono(11))
                        .textSelection(.enabled)
                }
            }
        }
        .card()
        .accessibilityElement(children: .contain)
    }

    private var scoreText: String {
        let pct = Int((item.score * 100).rounded())
        return item.scoreKind == .model ? "Laya: \(pct)% fits" : "fits \(pct)% of rules"
    }

    private func icon(_ o: RuleCheck.Outcome) -> String {
        switch o { case .pass: "checkmark.circle.fill"; case .fail: "xmark.circle.fill"; case .unknown: "questionmark.circle.fill" }
    }
    private func color(_ o: RuleCheck.Outcome) -> Color {
        switch o { case .pass: Palette.mint; case .fail: Palette.red; case .unknown: Palette.amber }
    }
}
