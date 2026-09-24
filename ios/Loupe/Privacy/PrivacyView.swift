import LoupeKit
import SwiftUI
import UniformTypeIdentifiers

/// Now's card: "Privacy check: N findings".
struct PrivacyCard: View {
    @ObservedObject var privacy: PrivacyService

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock.shield").font(.title2).foregroundStyle(Palette.blue)
            VStack(alignment: .leading, spacing: 2) {
                Text(line).font(.headline).foregroundStyle(Palette.ink)
                Text("Personal data, secrets and duplicate files · on this phone")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft)
        }
        .card()
        .accessibilityElement(children: .combine)
    }

    private var line: String {
        guard let s = privacy.summary else { return privacy.running ? "Privacy check: checking…" : "Privacy check" }
        let n = s.findings.count
        return "Privacy check: \(n) finding\(n == 1 ? "" : "s")"
    }
}

/// The Privacy screen: findings grouped by type, masked previews, the item, and what can be done.
struct PrivacyView: View {
    @ObservedObject var privacy: PrivacyService
    @State private var openItem: SourceItem?
    @State private var confirmDelete: PrivacyFinding?
    @State private var moving: PrivacyFinding?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Loupe Station's checks for ID numbers, card numbers, IBANs, contact lists, keys and tokens, and exact duplicate files. Read on this iPhone; nothing leaves it. Values are masked; \"Show where\" finds a value again when you tap it and never saves it.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                LayaOffBanner(feature: Features.shared.SCAN)
                if let notice = privacy.notice { noticeRow(notice) }
                if let s = privacy.summary {
                    if s.findings.isEmpty {
                        Text(s.itemsChecked == 0
                             ? "No source is on, so nothing was checked. Turn on the sample in Sources."
                             : "Nothing raised in \(s.itemsChecked) item(s)\(s.markedSafe > 0 ? " (\(s.markedSafe) marked safe)" : ""). That is not an all-clear: these checks cover only what they look for.")
                            .font(.subheadline).foregroundStyle(Palette.inkSoft).card()
                            .accessibilityIdentifier("privacy.none")
                    }
                    ForEach(s.groups, id: \.self) { group in
                        section(group, s.inGroup(group: group))
                    }
                    if s.itemsChecked > 0 {
                        Text("\(s.itemsChecked) item(s) checked\(s.markedSafe > 0 ? ", \(s.markedSafe) marked safe" : ""). Warnings only.")
                            .font(.caption).foregroundStyle(Palette.inkSoft)
                    }
                } else {
                    HStack { ProgressView(); Text("Checking your sources…").foregroundStyle(Palette.inkSoft) }.card()
                }
            }
            .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("Privacy check")
        .navigationBarTitleDisplayMode(.inline)
        .task { if privacy.summary == nil { await privacy.run() } }
        .refreshable { await privacy.run() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { Task { await privacy.run() } } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(privacy.running)
                    .accessibilityLabel("Check again")
                    .accessibilityIdentifier("privacy.rerun")
            }
        }
        .onDisappear { privacy.commitPending() }
        .sheet(item: $openItem) { ItemTextView(item: $0) }
        .sheet(item: $moving) { f in
            FolderPicker { url in
                moving = nil
                if let url { Task { await privacy.move(f, to: url) } }
            }
        }
        .confirmationDialog("Delete this file?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
                            titleVisibility: .visible, presenting: confirmDelete) { f in
            Button("Delete", role: .destructive) { Task { await privacy.delete(f) } }
                .accessibilityIdentifier("privacy.confirmDelete")
        } message: { f in
            Text(f.group == .duplicates ? "Another identical copy stays in place. You can undo this until you leave this screen."
                                        : "You can undo this until you leave this screen.")
        }
    }

    private func noticeRow(_ notice: String) -> some View {
        HStack {
            Text(notice).font(.caption).foregroundStyle(Palette.inkSoft)
            Spacer()
            if privacy.pendingUndo != nil {
                Button("Undo") { Task { await privacy.undoFile() } }.font(.caption.weight(.semibold))
                    .accessibilityIdentifier("privacy.undoFile")
            } else if privacy.lastSafe != nil {
                Button("Undo") { privacy.undoSafe() }.font(.caption.weight(.semibold))
                    .accessibilityIdentifier("privacy.undo")
            }
        }
    }

    private func section(_ group: PrivacyGroup, _ list: [PrivacyFinding]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(group.title).font(Typeface.display(20)).foregroundStyle(Palette.ink)
                Spacer()
                Text("\(list.count)").font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("privacy.group.\(group.id)")
            Text(group.blurb).font(.caption).foregroundStyle(Palette.inkSoft)
            ForEach(list, id: \.key) { f in row(f) }
        }
    }

    private func row(_ f: PrivacyFinding) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(f.severityTitle.uppercased()).font(Typeface.mono(11, weight: .medium))
                    .foregroundStyle(f.severity >= 3 ? Palette.dangerText : f.severity == 2 ? Palette.warnText : Palette.inkSoft)
                Text(f.ruleId).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                Spacer()
                if f.sample { Pill(text: "Sample", color: Palette.inkSoft) }
            }
            Text(f.title).font(.headline).foregroundStyle(Palette.ink)
            if let item = ItemIndex.item(f.itemId) { ItemRefHeader(item: item) }
            Text(f.location).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            VStack(alignment: .leading, spacing: 3) {
                ForEach(Array(f.previews.enumerated()), id: \.offset) { _, p in
                    Text(p).font(Typeface.mono(12)).foregroundStyle(Palette.ink)
                }
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.track, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            actions(f)
        }
        .card()
        .accessibilityElement(children: .contain)   // the row's id must not replace its buttons' ids
        .accessibilityIdentifier("privacy.finding.\(f.key)")
    }

    @ViewBuilder private func actions(_ f: PrivacyFinding) -> some View {
        let access = privacy.access(f)
        let target = privacy.access(for: privacy.target(f))
        HStack(spacing: 8) {
            Button { openItem = privacy.item(f.itemId) } label: { Label("Text", systemImage: "doc.text") }
                .accessibilityLabel("What Loupe read")
            Button { privacy.markSafe(f) } label: { Label("Mark safe", systemImage: "checkmark.shield") }
                .accessibilityIdentifier("privacy.safe")
            switch target {
            case .file:
                Button(role: .destructive) { confirmDelete = f } label: { Label("Delete", systemImage: "trash") }
                Button { moving = f } label: { Label("Move", systemImage: "folder") }
            case .photo:
                Button(role: .destructive) { Task { await privacy.delete(f) } } label: { Label("Delete", systemImage: "trash") }
            case .suggestOnly:
                EmptyView()
            }
        }
        .buttonStyle(.bordered).controlSize(.small).font(.caption.weight(.semibold))
        if f.duplicates == nil, let item = ItemIndex.item(f.itemId) {
            // Owner rule 2026-09-24: open the item itself, share it, and "Show where" (masked, re-derived).
            ItemActions(item: item, finding: f)
        }
        if case .suggestOnly(let why) = target, case .suggestOnly = access {
            Text("Suggestion only: \(why)").font(.caption2).foregroundStyle(Palette.inkSoft)
        }
    }
}

extension PrivacyService {
    func access(for itemId: String) -> PrivacyAccess { locator.access(for: itemId) }
}

extension PrivacyFinding: Identifiable {
    public var id: String { key }
}

/// A folder picker for "Move to…" (security-scoped, opened in place).
struct FolderPicker: UIViewControllerRepresentable {
    let done: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(done) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let done: (URL?) -> Void
        init(_ done: @escaping (URL?) -> Void) { self.done = done }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { done(urls.first) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { done(nil) }
    }
}
