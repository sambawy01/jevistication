import SwiftUI

/// Where Guard → Protection's rows lead (registered by `ProtectionSection` itself, so the Guard tab's
/// own routes stay untouched).
enum ProtectionRoute: Hashable {
    case checkLink
    case spotted
    case spottedEntry(UUID)
}

/// Guard → Protection's "Spotted" row: what Loupe flagged, this week.
struct SpottedRow: View {
    @ObservedObject var store: ProtectionStore

    var body: some View {
        let s = store.summary
        HStack(spacing: 12) {
            NeonIcon(name: "eye.trianglebadge.exclamationmark", color: s.sitesThisWeek > 0 ? Palette.warnText : Palette.inkSoft, size: 20)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text("Spotted").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text(s.headline ?? (store.spotted.isEmpty ? "Nothing risky spotted yet. Safari, shared links and your checks all land here."
                                                          : "Nothing risky this week · \(store.spotted.count) in the last 90 days"))
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            if store.unseenCount > 0 {
                Text("\(store.unseenCount) new").font(Typeface.mono(11, weight: .bold)).foregroundStyle(Palette.onAccent)
                    .padding(.horizontal, 8).padding(.vertical, 3).background(Palette.warnText, in: Capsule())
                    .accessibilityIdentifier("protect.spotted.unseen")
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("protect.spotted")
    }
}

/// The Spotted log: newest first, 90 days, domains only. Viewing it clears the Guard badge.
struct SpottedListView: View {
    @ObservedObject var store: ProtectionStore
    @State private var confirmClear = false

    var body: some View {
        List {
            NeonSection {
                Text("Every suspicious or dangerous site Loupe flagged in Safari, in a link shared to Loupe or in Check a link. Loupe keeps the website name only (never the full address or the page), for 90 days, on this iPhone.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            if store.spotted.isEmpty {
                NeonSection {
                    Text("Nothing spotted.").font(.callout).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("protect.spotted.empty")
                }
            } else {
                NeonSection {
                    ForEach(store.spotted) { e in
                        NavigationLink(value: ProtectionRoute.spottedEntry(e.id)) { SpottedEntryRow(entry: e) }
                            .accessibilityIdentifier("protect.spotted.entry")
                    }
                    .onDelete { idx in idx.map { store.spotted[$0].id }.forEach(store.removeSpotted) }
                } header: { Text("Last 90 days") }
                NeonSection {
                    Button("Clear the Spotted log", role: .destructive) { confirmClear = true }
                        .accessibilityIdentifier("protect.spotted.clear")
                }
            }
        }
        .neonList()
        .navigationTitle("Spotted")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { store.reload(); store.markSeen() }
        .confirmationDialog("Clear the Spotted log?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { store.clearSpotted() }
        } message: {
            Text("Every entry is removed from this iPhone.")
        }
    }
}

struct SpottedEntryRow: View {
    let entry: SpottedEntry

    var body: some View {
        HStack(spacing: 10) {
            NeonIcon(name: entry.level.symbol, color: entry.level.color, size: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.domain).font(Typeface.mono(13)).foregroundStyle(Palette.ink).lineLimit(1)
                Text("\(entry.level.title) · \(entry.origin.title) · \(entry.at.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
            }
            Spacer(minLength: 0)
            if !entry.seen { Circle().fill(Palette.warnText).frame(width: 8, height: 8).accessibilityLabel("New") }
        }
        .accessibilityElement(children: .combine)
    }
}

struct SpottedDetailView: View {
    @ObservedObject var store: ProtectionStore
    let id: UUID
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            if let e = store.spotted.first(where: { $0.id == id }) {
                NeonSection {
                    Label(e.level.title, systemImage: e.level.symbol).font(Typeface.display(22)).foregroundStyle(e.level.color)
                    Text(e.domain).font(Typeface.mono(15)).foregroundStyle(Palette.ink).textSelection(.enabled)
                    if e.domain != e.host { Text("Written as \(e.host)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft) }
                    Text("Risk score \(e.score) of 100").font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                }
                NeonSection("Why") {
                    ForEach(Array(e.reasons.enumerated()), id: \.offset) { _, r in Text(r).font(.callout).foregroundStyle(Palette.ink) }
                }
                NeonSection("What happened") {
                    LabeledContent("Where", value: e.origin.title)
                    LabeledContent("When", value: e.at.formatted(date: .abbreviated, time: .shortened))
                    if e.count > 1 { LabeledContent("Times", value: "\(e.count)") }
                    LabeledContent("You", value: e.origin == .safari ? e.action.title : "Checked it")
                }
                NeonSection {
                    Text("Loupe kept the website name, the verdict and its reasons. Not the full address, not the page.")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                    Button("Delete this entry", role: .destructive) { store.removeSpotted(e.id); dismiss() }
                }
            } else {
                Text("This entry is gone.").foregroundStyle(Palette.inkSoft)
            }
        }
        .neonList()
        .navigationTitle("Spotted")
        .navigationBarTitleDisplayMode(.inline)
    }
}
