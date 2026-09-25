import LoupeKit
import SwiftUI
import UniformTypeIdentifiers

/// The Inbox's row in Sources: how many imports, how many items.
struct InboxCard: View {
    @ObservedObject var sources: SourcesService

    var body: some View {
        HStack(spacing: 12) {
            SourceGlyph(id: "inbox", on: sources.inboxEnabled && !sources.inboxBatches.isEmpty)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text("Inbox").font(Typeface.display(22)).foregroundStyle(Palette.ink)
                    SourceBadge(online: false)
                }
                Text(summary).font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                    .multilineTextAlignment(.leading)
                    .accessibilityIdentifier("sources.inbox.summary")
            }
            Spacer(minLength: 4)
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .card()
    }

    private var summary: String {
        let batches = sources.inboxBatches
        if batches.isEmpty { return "Nothing imported yet. Import CSVs, .eml or .mbox mail, ZIP archives, or send text and links here." }
        let items = batches.reduce(0) { $0 + Int($1.itemCount) }
        let off = sources.inboxEnabled ? "" : " · Off"
        return "\(batches.count) import\(batches.count == 1 ? "" : "s") · \(items) item\(items == 1 ? "" : "s")\(off)"
    }
}

/// Every import, with its counts; each can be opened and removed.
struct InboxView: View {
    @ObservedObject var sources: SourcesService
    @State private var importing = false
    @State private var pasting = false
    @State private var pasted = ""

    static let importTypes: [UTType] = [.commaSeparatedText, .tabSeparatedText, .emailMessage, .zip, .plainText, .text]
        + [UTType(filenameExtension: "mbox"), UTType(filenameExtension: "eml")].compactMap { $0 }

    var body: some View {
        List {
            if ActivityCenter.shared.latest("sources")?.running == true {
                NeonSection { LiveRunSection(view: "sources", whileRunning: true).listRowInsets(EdgeInsets()) }
            }
            NeonSection {
                Toggle(isOn: Binding(get: { sources.inboxEnabled }, set: { sources.setInboxEnabled($0) })) {
                    Text("Use imported items").font(.subheadline)
                }
                .accessibilityIdentifier("inbox.toggle")
                Button { importing = true } label: { Label("Import files…", systemImage: "square.and.arrow.down") }
                    .accessibilityIdentifier("inbox.import")
                Button { pasting = true } label: { Label("Paste text or a link", systemImage: "doc.on.clipboard") }
                    .accessibilityIdentifier("inbox.paste")
                if sources.inboxBusy {
                    ProgressView { Text("Importing…").font(.footnote) }.accessibilityIdentifier("inbox.progress")
                }
                if let problem = sources.inboxProblem {
                    Text(problem).font(.footnote).foregroundStyle(Palette.dangerText).accessibilityIdentifier("inbox.problem")
                }
            } footer: {
                Text("CSV files become one item per row, with the column names beside each value. Mail files, ZIP archives (unpacked on this iPhone, with unsafe paths, links and zip bombs refused) and text shared from other apps land here too. Nothing leaves the phone.")
            }
            NeonSection {
                if sources.inboxBatches.isEmpty {
                    Text("Nothing imported yet.").font(.footnote).foregroundStyle(Palette.inkSoft)
                }
                ForEach(Array(sources.inboxBatches.enumerated()), id: \.element.id) { index, batch in
                    NavigationLink {
                        InboxBatchView(sources: sources, batch: batch)
                    } label: {
                        BatchRow(batch: batch)
                    }
                    .accessibilityIdentifier("inbox.batch.\(index)")
                    .swipeActions {
                        Button("Remove", role: .destructive) { sources.removeInboxBatch(batch.id) }
                    }
                }
            } header: {
                Text("Imports")
            }
        }
        .scrollContentBackground(.hidden)
        .neonGround()
        .navigationTitle("Inbox")
        .fileImporter(isPresented: $importing, allowedContentTypes: Self.importTypes, allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { Task { await sources.importToInbox(urls) } }
        }
        .alert("Paste text or a link", isPresented: $pasting) {
            TextField("Text or https://…", text: $pasted)
            Button("Import") {
                let text = pasted
                pasted = ""
                Task { await sources.importTextToInbox(text) }
            }
            Button("Cancel", role: .cancel) { pasted = "" }
        }
    }
}

private struct BatchRow: View {
    let batch: InboxBatch

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(batch.name).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(InboxBatchView.counts(batch)).font(.footnote).foregroundStyle(Palette.inkSoft)
            Text("\(batch.origin) · \(Date(timeIntervalSince1970: Double(batch.createdAtEpochMillis) / 1000).formatted(date: .abbreviated, time: .shortened))")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        }
    }
}

/// One import: its items (a CSV's rows, the messages, the files an archive held) and what was skipped.
struct InboxBatchView: View {
    @ObservedObject var sources: SourcesService
    let batch: InboxBatch
    @State private var open: SourceItem?
    @Environment(\.dismiss) private var dismiss

    static func counts(_ b: InboxBatch) -> String {
        var parts = ["\(b.itemCount) item\(b.itemCount == 1 ? "" : "s")"]
        if b.rowCount > 0 { parts.append("\(b.rowCount) CSV row\(b.rowCount == 1 ? "" : "s")") }
        if b.emailCount > 0 { parts.append("\(b.emailCount) email\(b.emailCount == 1 ? "" : "s")") }
        if b.skippedCount > 0 { parts.append("\(b.skippedCount) skipped") }
        if b.alreadyImported > 0 { parts.append("\(b.alreadyImported) already imported") }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        let items = sources.inboxItems(batch)
        let skipped = sources.inboxSkipped(batch)
        List {
            NeonSection {
                Text(Self.counts(batch)).font(.subheadline).accessibilityIdentifier("inbox.batch.counts")
                if let first = items.first, let label = first.facts["imported"] {
                    Text(label).font(.footnote).foregroundStyle(Palette.inkSoft).accessibilityIdentifier("inbox.batch.label")
                }
            }
            NeonSection("Items") {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    Button { open = item } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).font(.subheadline).foregroundStyle(Palette.ink).lineLimit(1)
                            Text(detail(item)).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
                        }
                    }
                    .accessibilityIdentifier("inbox.item.\(index)")
                }
            }
            if !skipped.isEmpty {
                NeonSection("Skipped") {
                    ForEach(Array(skipped.enumerated()), id: \.offset) { _, s in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.path).font(.footnote).lineLimit(1)
                            Text(s.reason).font(.caption).foregroundStyle(Palette.warnText)
                        }
                    }
                }
            }
            NeonSection {
                Button("Remove this import", role: .destructive) {
                    sources.removeInboxBatch(batch.id)
                    dismiss()
                }
                .accessibilityIdentifier("inbox.batch.remove")
            } footer: {
                Text("Removes its items from every judgment and watcher, and deletes Loupe's copy. The original files are not touched.")
            }
        }
        .scrollContentBackground(.hidden)
        .neonGround()
        .navigationTitle(batch.name)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: Binding(get: { open.map(IdentifiedItem.init) }, set: { open = $0?.item })) { wrapped in
            ItemTextView(item: wrapped.item)
        }
    }

    private func detail(_ item: SourceItem) -> String {
        var parts = [item.kind.title]
        if let row = item.facts["row"] { parts.append("row \(row)") }
        if let amount = item.facts["amount"] { parts.append(amount) }
        if let d = item.dateIso { parts.append(d) }
        if item.duplicateOf != nil { parts.append("already imported") }
        return parts.joined(separator: " · ")
    }
}

private struct IdentifiedItem: Identifiable {
    let item: SourceItem
    var id: String { item.id }
}
