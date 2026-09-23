import SwiftUI

struct ResultsView: View {
    @EnvironmentObject private var web: WebModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let r = web.response {
                    header(r)
                    if !r.live { testBanner }
                    prioritiesCard
                    ForEach(web.ranked) { OfferCard(item: $0) }
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
            MascotView(state: .found, size: 44)
        }
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
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Caption(text: "Ranked on this phone · \(web.rankerName)")
                Spacer()
                Text("\(web.ranked.filter(\.fitsAll).count) of \(web.ranked.count) fit")
                    .font(Typeface.mono(12, weight: .medium)).foregroundStyle(Palette.mint)
            }
            if web.priorities.isEmpty {
                Text("No priorities given, so offers are in price order.").font(.footnote).foregroundStyle(Palette.inkSoft)
            } else {
                Text(web.form.priorities).font(.subheadline).foregroundStyle(Palette.ink)
            }
            if !web.priorities.unrecognised.isEmpty {
                Text("Not understood, ignored: \(web.priorities.unrecognised.joined(separator: ", "))")
                    .font(.footnote).foregroundStyle(Palette.amber)
            }
            Text("Laya ranking arrives with the on-device model; this is the rule baseline.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        }
        .card()
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
            HStack {
                if item.unsure { Pill(text: "unsure", color: Palette.amber, symbol: "questionmark") }
                Pill(text: "fits \(Int((item.score * 100).rounded()))% of rules", color: item.fitsAll ? Palette.mint : Palette.inkSoft)
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

    private func icon(_ o: RuleCheck.Outcome) -> String {
        switch o { case .pass: "checkmark.circle.fill"; case .fail: "xmark.circle.fill"; case .unknown: "questionmark.circle.fill" }
    }
    private func color(_ o: RuleCheck.Outcome) -> Color {
        switch o { case .pass: Palette.mint; case .fail: Palette.red; case .unknown: Palette.amber }
    }
}
