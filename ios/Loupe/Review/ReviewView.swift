import SwiftUI
import LoupeKit

/// Now's card: "To review: N" (pending plus failed actions).
struct ReviewCard: View {
    @ObservedObject var review: ReviewService

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checklist").font(.title2).foregroundStyle(Palette.blue)
            VStack(alignment: .leading, spacing: 2) {
                Text("To review: \(review.toReview)").font(.headline).foregroundStyle(Palette.ink)
                Text("Actions Loupe proposes · nothing runs until you approve")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft)
        }
        .card()
        .accessibilityElement(children: .combine)
    }
}

/// The Review screen (epic #7 child 13, Loupe Station's Review tab): proposed actions from the
/// privacy check, mail triage, the watchers and judgments. Approve (one, or several at once),
/// reject with a reason, retry a failed one, undo a reversible one.
struct ReviewView: View {
    @ObservedObject var review: ReviewService
    @State private var selected: Set<String> = []
    @State private var rejecting: ReviewItem?
    @State private var showDecided = false
    @State private var working = false

    var body: some View {
        List {
            Section {
                Text("Actions the checks propose: remove a duplicate copy, confirm a phishing message, keep a finding on Now, add a judgment. Nothing happens until you approve it.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                Text("Not the Unsure queue: that one asks you for answers the model learns from. This one is actions.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("review.notUnsure")
                if let n = review.notice {
                    HStack {
                        Text(n).font(.caption.weight(.semibold)).foregroundStyle(Palette.ink)
                            .accessibilityIdentifier("review.notice")
                        Spacer()
                        if let last = review.lastApplied {
                            Button("Undo") { act { await review.undo(last) } }
                                .font(.caption.weight(.semibold)).buttonStyle(.bordered)
                                .accessibilityIdentifier("review.undoLast")
                        }
                    }
                }
                if let p = review.problem { Text(p).font(.caption).foregroundStyle(Palette.dangerText) }
            }
            if review.open.isEmpty {
                Section {
                    Text("Nothing to review. When a check proposes an action, it waits here.")
                        .font(.subheadline).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("review.empty")
                }
            } else {
                ForEach(ReviewRegistry.shared.FEATURES, id: \.self) { feature in
                    let rows = review.open.filter { $0.feature == feature }
                    if !rows.isEmpty {
                        Section(ReviewRegistry.shared.featureTitle(feature: feature)) {
                            ForEach(rows, id: \.id) { item in
                                ReviewRow(item: item, selected: selected.contains(item.id), working: working,
                                          toggle: { toggle(item) },
                                          approve: { act { await review.approve(item) } },
                                          reject: { rejecting = item },
                                          retry: { act { await review.retry(item) } },
                                          undo: {})
                            }
                        }
                    }
                }
            }
            if !review.decided.isEmpty {
                Section {
                    DisclosureGroup("Decided (\(review.decided.count))", isExpanded: $showDecided) {
                        ForEach(review.decided, id: \.id) { item in
                            ReviewRow(item: item, selected: false, working: working, toggle: {}, approve: {}, reject: {}, retry: {},
                                      undo: { act { await review.undo(item) } })
                        }
                    }
                }
            }
        }
        .navigationTitle("Review")
        .toolbar {
            if !selected.isEmpty {
                ToolbarItem(placement: .bottomBar) {
                    Button("Approve \(selected.count) selected") {
                        let chosen = review.open.filter { selected.contains($0.id) }
                        selected = []
                        act { _ = await review.approveAll(chosen) }
                    }
                    .disabled(working)
                    .accessibilityIdentifier("review.approveSelected")
                }
            }
        }
        .sheet(item: $rejecting) { item in RejectSheet(item: item) { reason in review.reject(item, reason: reason) } }
        .onAppear { review.collect() }
    }

    private func toggle(_ item: ReviewItem) {
        if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
    }

    private func act(_ work: @escaping () async -> Void) {
        working = true
        Task { await work(); working = false }
    }
}

extension ReviewItem: Identifiable {}

private struct ReviewRow: View {
    let item: ReviewItem
    let selected: Bool
    let working: Bool
    let toggle: () -> Void
    let approve: () -> Void
    let reject: () -> Void
    let retry: () -> Void
    let undo: () -> Void

    private var verb: String { ReviewRegistry.shared.action(type: item.actionType)?.verb ?? "Approve" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                if item.status == ReviewStatus.shared.PENDING {
                    Button(action: toggle) {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle").font(.title3)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(selected ? "Selected" : "Select")
                    .accessibilityIdentifier("review.select")
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("review.title")
                    if !item.inputSummary.isEmpty {
                        Text(item.inputSummary).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(4)
                    }
                    HStack(spacing: 6) {
                        Pill(text: item.status, color: color)
                        if item.edited { Pill(text: "edited", color: Palette.inkSoft) }
                        if item.reversible && item.status == ReviewStatus.shared.PENDING { Pill(text: "can undo", color: Palette.okText) }
                    }
                    if let e = item.error {
                        Text(e).font(.caption).foregroundStyle(Palette.dangerText).accessibilityIdentifier("review.error")
                    }
                    if let note = item.decisionNote { Text("Reason: \(note)").font(.caption2).foregroundStyle(Palette.inkSoft) }
                }
            }
            HStack {
                if item.status == ReviewStatus.shared.PENDING {
                    Button(verb, action: approve).buttonStyle(.borderedProminent).accessibilityIdentifier("review.approve")
                    Button("Reject", role: .destructive, action: reject).buttonStyle(.bordered).accessibilityIdentifier("review.reject")
                } else if item.status == ReviewStatus.shared.FAILED {
                    Button("Retry", action: retry).buttonStyle(.borderedProminent).accessibilityIdentifier("review.retry")
                    Button("Reject", role: .destructive, action: reject).buttonStyle(.bordered).accessibilityIdentifier("review.reject")
                } else if item.status == ReviewStatus.shared.APPLIED && item.reversible {
                    Button("Undo", action: undo).buttonStyle(.bordered).accessibilityIdentifier("review.undo")
                }
            }
            .disabled(working)
            .font(.caption.weight(.semibold))
        }
        .padding(.vertical, 4)
    }

    private var color: Color {
        switch item.status {
        case ReviewStatus.shared.APPLIED: return Palette.okText
        case ReviewStatus.shared.FAILED, ReviewStatus.shared.REJECTED: return Palette.dangerText
        case ReviewStatus.shared.PENDING: return Palette.blue
        default: return Palette.inkSoft
        }
    }
}

/// Station requires a reason to reject; the common ones are one tap.
private struct RejectSheet: View {
    let item: ReviewItem
    let onReject: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Why not?") {
                    ForEach(["Not needed", "Wrong", "I will do it myself"], id: \.self) { r in
                        Button(r) { onReject(r); dismiss() }
                    }
                    TextField("Another reason", text: $reason).accessibilityIdentifier("review.reason")
                }
                Section { Text("A rejected proposal is not made again.").font(.caption) }
            }
            .navigationTitle("Reject")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Reject") { onReject(reason); dismiss() }
                        .disabled(reason.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}
