import SwiftUI
import UniformTypeIdentifiers
import LoupeKit

/// The Judgments tab's Packs menu: import from Files, the bundled example, export yours.
struct PacksMenu: View {
    @ObservedObject var packs: PacksService
    @State private var importing = false
    @State private var shared: SharedFile?

    var body: some View {
        Menu {
            Button { importing = true } label: { Label("Import a pack from Files…", systemImage: "square.and.arrow.down") }
                .accessibilityIdentifier("packs.import")
            Button { packs.openExample() } label: { Label("Try the example pack", systemImage: "shippingbox") }
                .accessibilityIdentifier("packs.example")
            Button {
                do {
                    let (url, export) = try packs.exportFile()
                    packs.notice = "\(export.exported) judgment\(export.exported == 1 ? "" : "s") exported" +
                        (export.left.isEmpty ? "." : "; \(export.left.count) left out: \(export.left.joined(separator: "; "))")
                    shared = SharedFile(url: url)
                } catch { packs.notice = "Could not export: \(error.localizedDescription)" }
            } label: { Label("Export my judgments as a pack", systemImage: "square.and.arrow.up") }
                .accessibilityIdentifier("packs.export")
        } label: { Label("Packs", systemImage: "shippingbox") }
            .accessibilityIdentifier("judgments.packs")
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                if case .success(let url) = result { packs.open(url) }
            }
            .sheet(item: $shared) { ShareSheet(items: [$0.url]) }
    }
}

/// Before anything is added: the pack, each question as a judgment, what the lint refused and why,
/// and what to do when you already have a judgment with the same id.
struct PackPreviewView: View {
    @ObservedObject var packs: PacksService
    let preview: PacksService.Preview
    @Environment(\.dismiss) private var dismiss
    @State private var choice: ConflictChoice = .skip

    var body: some View {
        NavigationStack {
            List {
                NeonSection {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(preview.pack.name).font(.headline)
                        if !preview.pack.description_.isEmpty {
                            Text(preview.pack.description_).font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                        Text("\(preview.pack.presets.count) preset\(preview.pack.presets.count == 1 ? "" : "s") · \(preview.plans.count) question\(preview.plans.count == 1 ? "" : "s") · \(preview.pack.slug)")
                            .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    }
                    if preview.example {
                        Label(PacksService.exampleLabel, systemImage: "info.circle")
                            .font(.caption).foregroundStyle(Palette.warnText)
                            .accessibilityIdentifier("packs.exampleLabel")
                    }
                }
                NeonSection("Will be added (\(preview.addable.count))") {
                    ForEach(preview.addable, id: \.judgmentId) { plan in PlanRow(plan: plan) }
                }
                if !preview.refused.isEmpty {
                    NeonSection {
                        ForEach(preview.refused, id: \.judgmentId) { plan in PlanRow(plan: plan) }
                    } header: {
                        Text("Not added: the lint refused them (\(preview.refused.count))")
                    } footer: {
                        Text("The same checks as Write your own. A yes/no question without \"true\" and \"false\" descriptions would be bare yes/no, which the model largely ignores.")
                    }
                }
                if preview.conflicts > 0 {
                    NeonSection("You already have \(preview.conflicts) of these") {
                        Picker("Same id", selection: $choice) {
                            Text("Skip them").tag(ConflictChoice.skip)
                            Text("Replace mine").tag(ConflictChoice.replace)
                            Text("Keep both").tag(ConflictChoice.keepBoth)
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("packs.conflict")
                        if choice == .replace {
                            Text("Replacing a judgment restarts its calibration; its logged decisions stay in the ledger.")
                                .font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                    }
                }
                NeonSection {
                    Button {
                        packs.add(choice)
                        dismiss()
                    } label: { Text("Add \(preview.addable.count) judgment\(preview.addable.count == 1 ? "" : "s")").bold() }
                        .disabled(preview.addable.isEmpty)
                        .accessibilityIdentifier("packs.add")
                    Button {
                        packs.reviewOneByOne()
                        dismiss()
                    } label: { Text("Send to Review, to approve one by one") }
                        .disabled(preview.addable.isEmpty)
                        .accessibilityIdentifier("packs.review")
                } footer: {
                    Text("Nothing is added until you choose. Judgments from a pack are yours to change or delete like any other.")
                }
            }
            .neonList()
            .navigationTitle("Pack preview")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { packs.preview = nil; dismiss() } } }
        }
    }
}

private struct PlanRow: View {
    let plan: PackJudgmentPlan

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(plan.title).font(.subheadline.weight(.semibold))
                Spacer()
                Pill(text: plan.shape.title, color: plan.addable ? Palette.blue : Palette.inkSoft)
                if plan.conflict { Pill(text: "same id", color: Palette.warnText) }
            }
            Text(plan.question).font(.caption).foregroundStyle(Palette.ink).lineLimit(3)
            Text(plan.options.joined(separator: " · ")).font(.caption2).foregroundStyle(Palette.inkSoft).lineLimit(2)
            ForEach(plan.problems, id: \.self) { p in
                Label(p, systemImage: "exclamationmark.triangle").font(.caption2).foregroundStyle(Palette.dangerText)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("packs.plan.\(plan.judgmentId)")
    }
}

/// Station's refusal list: every problem, located (`presets.2.questions.team: …`).
struct PackProblemsView: View {
    @ObservedObject var packs: PacksService

    var body: some View {
        if !packs.problems.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("This is not a pack Loupe can import:").font(.caption.weight(.semibold))
                    Spacer()
                    Button("Dismiss") { packs.problems = [] }.font(.caption)
                }
                ForEach(Array(packs.problems.prefix(6).enumerated()), id: \.offset) { _, line in
                    Text(line).font(Typeface.mono(11)).foregroundStyle(Palette.dangerText)
                }
                if packs.problems.count > 6 { Text("and \(packs.problems.count - 6) more").font(.caption2) }
            }
            .padding(12)
            .background(Palette.dangerSoft, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)
            .accessibilityIdentifier("packs.problems")
        }
    }
}
