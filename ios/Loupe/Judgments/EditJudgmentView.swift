import SwiftUI
import LoupeKit

/// Edit a judgment (audit P1-3, 2026-09-27), from its results screen's menu: the name, the question, the options
/// (a two-option judgment's two, a pick's list; a score's bands and a bare yes/no's are not edited) and the
/// criteria text. Saved through LoupeKit's `JudgmentBook.reword`, the same lint as "Write your own", keeping the
/// id, threshold, baseline mode and history. New wording restarts calibration: the sheet says so before Save.
/// "Show the criteria to the model" stays on the results screen; the threshold stays in Measure.
struct EditJudgmentView: View {
    @ObservedObject var service: JudgmentsService
    let judgment: UserJudgment
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var question: String
    @State private var optionsText: String
    @State private var invariant: String
    @State private var breaks: String
    @State private var lookalikes: String
    @State private var refused: [String] = []
    @State private var confirmRestart = false

    init(service: JudgmentsService, judgment: UserJudgment) {
        self.service = service
        self.judgment = judgment
        let unwritten = UserJudgment.companion.UNWRITTEN
        func text(_ s: String) -> String { s == unwritten ? "" : s }
        _title = State(initialValue: judgment.title)
        _question = State(initialValue: judgment.question)
        _optionsText = State(initialValue: JudgmentBook.shared.optionsText(judgment: judgment) ?? "")
        _invariant = State(initialValue: text(judgment.invariant))
        _breaks = State(initialValue: text(judgment.breaks))
        _lookalikes = State(initialValue: text(judgment.lookalikes))
    }

    private var optionsEditable: Bool { JudgmentBook.shared.optionsEditable(judgment: judgment) }

    /// What Save would make, or nil when the lint refuses it.
    private var draft: BookResult {
        JudgmentBook.shared.reword(judgment: judgment, title: title, question: question,
                                   optionsText: optionsEditable ? optionsText : nil,
                                   invariant: invariant, breaks: breaks, lookalikes: lookalikes)
    }

    private var edited: UserJudgment? { (draft as? BookResult.Created)?.judgment }
    private var problems: [String] { (draft as? BookResult.Refused)?.reasons ?? [] }
    private var restarts: Bool { edited.map { JudgmentBook.shared.calibrationRestarts(before: judgment, edited: $0) } ?? false }

    var body: some View {
        NavigationStack {
            Form {
                NeonSection {
                    TextField("Name (for your lists)", text: $title).accessibilityIdentifier("edit.title")
                    TextField("The question, in plain language", text: $question, axis: .vertical)
                        .accessibilityIdentifier("edit.question")
                } header: { Text("Question") }

                NeonSection {
                    if optionsEditable {
                        TextField("Options, one per line", text: $optionsText, axis: .vertical)
                            .lineLimit(2...10)
                            .accessibilityIdentifier("edit.options")
                    } else {
                        Text(judgment.shape.candidates.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                } header: { Text("Answers") } footer: {
                    Text(optionsEditable
                         ? (judgment.shape is ShapeBinary ? "Keep exactly two, each saying what it means." : "One option per line.")
                         : "A score's bands are kept as they are. To change them, write a new judgment.")
                }

                NeonSection {
                    TextField("True when…", text: $invariant, axis: .vertical).accessibilityIdentifier("edit.invariant")
                    TextField("False when…", text: $breaks, axis: .vertical)
                    TextField("Looks like it but isn't…", text: $lookalikes, axis: .vertical)
                } header: { Text("What it means") } footer: {
                    Text("Show the criteria to the model is on the results screen; the threshold is in Measure.")
                }

                NeonSection {
                    if !problems.isEmpty {
                        ForEach(problems, id: \.self) { p in
                            Label(p, systemImage: "exclamationmark.triangle.fill")
                                .font(.footnote).foregroundStyle(Palette.amber)
                        }
                    } else if restarts {
                        Label("The wording changes what the model is asked, so this judgment's calibration starts again. Earlier decisions stay in the ledger and stop counting.",
                              systemImage: "arrow.counterclockwise")
                            .font(.footnote).foregroundStyle(Palette.amber)
                            .accessibilityIdentifier("edit.restarts")
                    } else {
                        Label("Calibration is kept: the model is asked the same thing.", systemImage: "checkmark.circle.fill")
                            .font(.footnote).foregroundStyle(Palette.mint)
                    }
                    ForEach(refused, id: \.self) { Text($0).font(.footnote).foregroundStyle(Palette.red) }
                } header: { Text("Checked as you type") }
            }
            .neonList()
            .navigationTitle("Edit judgment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.accessibilityIdentifier("edit.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { if restarts { confirmRestart = true } else { save() } }
                        .disabled(edited == nil)
                        .accessibilityIdentifier("edit.save")
                }
            }
            .confirmationDialog("Calibration starts again", isPresented: $confirmRestart, titleVisibility: .visible) {
                Button("Save the new wording") { save() }
            } message: {
                Text("Earlier decisions were made under the old wording: they stay in the ledger and stop counting.")
            }
        }
    }

    private func save() {
        switch service.update(judgment.id, title: title, question: question, optionsText: optionsEditable ? optionsText : nil,
                              invariant: invariant, breaks: breaks, lookalikes: lookalikes) {
        case .success: dismiss()
        case .failure(let r): refused = r.reasons
        }
    }
}
