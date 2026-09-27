import SwiftUI

/// Me → Your data → "Delete all my Loupe data" (audit P1-4, 2026-09-27). Two steps: this sheet says exactly what
/// goes, offers to keep the decision model (on by default: it is a large download and holds none of your data), and
/// asks you to type DELETE; only then does the red button work. It waits while a scan, a judgment run or the sort
/// is running, so nothing is written back while the files go. Afterwards Loupe starts again from onboarding.
struct DeleteDataView: View {
    @ObservedObject private var laya = LayaModel.shared
    @ObservedObject private var sources = SourcesService.shared
    @ObservedObject private var judgments = JudgmentsService.shared
    @ObservedObject private var sort = SortService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var keepModel = true
    @State private var typed = ""
    @State private var erasing = false
    @State private var failure: String?

    static let confirmWord = "DELETE"

    /// True once the confirmation word is typed exactly (case and surrounding spaces ignored).
    static func confirmed(_ typed: String) -> Bool {
        typed.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == confirmWord
    }

    private var busy: Bool { sources.scanning || judgments.running || sort.running }

    private var modelSize: String {
        laya.manifest?.variant(laya.variantID).map { DeliveryError.bytes($0.totalBytes) } ?? "400 MB"
    }

    var body: some View {
        NavigationStack {
            Form {
                NeonSection {
                    Text("This deletes everything Loupe keeps on this iPhone and cannot be undone. Export your data first if you want a copy.")
                        .font(.callout).foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                NeonSection("What goes") {
                    ForEach(Self.whatGoes, id: \.self) { line in
                        Label(line, systemImage: "trash").font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                    Text("Your photos, files, calendar, contacts and the mail on your server are not touched: Loupe only forgets what it read.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                NeonSection {
                    Toggle(isOn: $keepModel) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Keep the decision model (\(modelSize))")
                            Text("It holds none of your data; keeping it saves downloading it again.")
                                .font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                    }
                    .accessibilityIdentifier("erase.keepModel")
                }
                NeonSection("Type \(Self.confirmWord) to confirm") {
                    TextField(Self.confirmWord, text: $typed)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("erase.confirmField")
                    Button(role: .destructive) {
                        Task { await erase() }
                    } label: {
                        HStack {
                            Text(erasing ? "Deleting…" : "Delete all my Loupe data")
                            Spacer()
                            if erasing { ProgressView() }
                        }
                        .frame(minHeight: 44)
                    }
                    .disabled(!Self.confirmed(typed) || erasing || busy)
                    .accessibilityIdentifier("erase.confirm")
                    if busy {
                        Text("Loupe is reading your sources or running a judgment. Wait for it to finish (or cancel it), then delete.")
                            .font(.footnote).foregroundStyle(Palette.warnText)
                            .accessibilityIdentifier("erase.busy")
                    }
                    if let failure {
                        Text(failure).font(.footnote).foregroundStyle(Palette.dangerText)
                    }
                }
            }
            .neonList()
            .navigationTitle("Delete my data")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(erasing).accessibilityIdentifier("erase.cancel")
                }
            }
            .interactiveDismissDisabled(erasing)
        }
    }

    static let whatGoes = [
        "The decision ledger, your judgments and your corrections",
        "The review queue and what Loupe found (watchers, privacy check, mail triage)",
        "Source caches and indexes, file and folder bookmarks, the Inbox and fetched mail",
        "The Spotted log and your recent link checks",
        "Every setting, and the Keychain items: mail sign-ins, the writing assistant's key, online-check keys",
    ]

    private func erase() async {
        erasing = true
        failure = nil
        let report = await LoupeDataReset.eraseEverything(keepModel: keepModel)
        erasing = false
        if report.ok {
            dismiss()
        } else {
            failure = "Some items could not be deleted: \((report.failed + report.keychainFailed).joined(separator: ", ")). Try again, or delete the app to remove them."
        }
    }
}
