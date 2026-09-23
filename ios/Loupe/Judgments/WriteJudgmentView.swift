import SwiftUI
import LoupeKit

/// The C2 editor's fields, linted live through LoupeKit's `JudgmentBook.findings`.
@MainActor
final class EditorModel: ObservableObject {
    @Published var title = ""
    @Published var question = ""
    @Published var shape: DraftShape = .yesNo
    @Published var positive = ""
    @Published var negative = ""
    @Published var optionsText = ""
    @Published var bandsText = ""
    @Published var invariant = ""
    @Published var breaks = ""
    @Published var lookalikes = ""
    @Published var keywordsText = ""
    @Published var posture: FailurePosture = TemplateLibrary.shared.DEFAULT_POSTURE
    @Published var criteriaInPrompt = false

    var input: EditorInput {
        EditorInput(title: title, question: question, shape: shape, positive: positive, negative: negative,
                    optionsText: optionsText, bandsText: bandsText, invariant: invariant, breaks: breaks,
                    lookalikes: lookalikes, keywordsText: keywordsText, onFailure: posture, criteriaInPrompt: criteriaInPrompt)
    }

    /// Nothing is flagged before a question is typed; after that, every finding, live.
    var findings: [LintFinding] {
        question.trimmingCharacters(in: .whitespaces).isEmpty ? [] : JudgmentBook.shared.findings(input: input)
    }

    var canCreate: Bool { !question.trimmingCharacters(in: .whitespaces).isEmpty && findings.isEmpty }

    /// What the model would score, when it compiles.
    var compiledOptions: [String] { input.candidates }

    /// A pick has no per-option criteria for the model to read, so the toggle does nothing there.
    var criteriaApplies: Bool { shape != .pick }
}

struct WriteJudgmentView: View {
    @ObservedObject var service: JudgmentsService
    let onCreated: (String) -> Void
    @StateObject private var m = EditorModel()
    @State private var refused: [String] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name (for your lists)", text: $m.title).accessibilityIdentifier("write.title")
                    TextField("The question, in plain language", text: $m.question, prompt: Text("Is this from my landlord?"), axis: .vertical)
                        .accessibilityIdentifier("write.question")
                } header: { Text("Question") }

                Section {
                    Picker("Shape", selection: $m.shape) {
                        ForEach([DraftShape.yesNo, .score, .pick], id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("write.shape")
                    switch m.shape {
                    case .yesNo:
                        TextField("What yes means", text: $m.positive, prompt: Text("from my landlord")).accessibilityIdentifier("write.positive")
                        TextField("What no means", text: $m.negative, prompt: Text("not from my landlord")).accessibilityIdentifier("write.negative")
                    case .score:
                        TextField("Bands, lowest first, one per line", text: $m.bandsText, prompt: Text("no deadline\nwithin a month\nthis week"), axis: .vertical)
                            .lineLimit(3...8)
                    default:
                        TextField("Options, one per line", text: $m.optionsText, prompt: Text("energy\nwater\nnone of these"), axis: .vertical)
                            .lineLimit(3...8)
                    }
                } header: { Text("Answers") } footer: {
                    Text(shapeHelp)
                }

                Section {
                    TextField("True when…", text: $m.invariant, axis: .vertical)
                    TextField("False when…", text: $m.breaks, axis: .vertical)
                    TextField("Looks like it but isn't…", text: $m.lookalikes, axis: .vertical)
                    if m.shape == .yesNo {
                        TextField("Baseline keywords, comma-separated", text: $m.keywordsText)
                            .textInputAutocapitalization(.never)
                    }
                } header: { Text("What it means") }

                Section {
                    Picker("If the model's answer cannot be used", selection: $m.posture) {
                        Text("Null action").tag(FailurePosture.nullAction)
                        Text("Loud").tag(FailurePosture.loud)
                        Text("Open").tag(FailurePosture.open)
                    }
                    Text(JudgmentResults.shared.postureText(p: m.posture)).font(.caption).foregroundStyle(Palette.inkSoft)
                    Toggle("Show the criteria to the model", isOn: $m.criteriaInPrompt)
                        .disabled(!m.criteriaApplies)
                        .accessibilityIdentifier("write.criteriaInPrompt")
                    Text(m.criteriaApplies ? JudgmentBook.shared.CRITERIA_WARNING : "A pick has no per-option criteria to show.")
                        .font(.caption).foregroundStyle(m.criteriaInPrompt ? Palette.amber : Palette.inkSoft)
                } header: { Text("Behaviour") }

                Section {
                    if m.question.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text("Start with a question. It is checked as you type: this is a classifier, so it refuses rating scales, requests for explanations or writing, and two questions in one.")
                            .font(.footnote).foregroundStyle(Palette.inkSoft)
                    } else if m.findings.isEmpty {
                        Label("Compiles: \(m.compiledOptions.joined(separator: " · "))", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(Palette.mint)
                            .accessibilityIdentifier("write.compiles")
                    } else {
                        ForEach(m.findings, id: \.self) { f in
                            Label("\(f.message)", systemImage: "exclamationmark.triangle.fill")
                                .font(.footnote).foregroundStyle(Palette.amber)
                                .accessibilityIdentifier("write.finding.\(f.rule)")
                        }
                    }
                    ForEach(refused, id: \.self) { Text($0).font(.footnote).foregroundStyle(Palette.red) }
                } header: { Text("Checked as you type") }
            }
            .navigationTitle("Write your own")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        switch service.create(m.input) {
                        case .success(let id): onCreated(id)
                        case .failure(let r): refused = r.reasons
                        }
                    }
                    .disabled(!m.canCreate)
                    .accessibilityIdentifier("write.create")
                }
            }
        }
    }

    private var shapeHelp: String {
        switch m.shape {
        case .yesNo: "Say what each answer means (\"a receipt\" / \"not a receipt\"). Bare yes/no makes the model ignore the question."
        case .score: "Each value is a band you name in words, so the model picks between described bands rather than writing a number."
        default: "Add a no-op such as \"none of these\" so the model is not forced to pick something that does not apply."
        }
    }
}
