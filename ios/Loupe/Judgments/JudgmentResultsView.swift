import SwiftUI
import LoupeKit

/// One judgment's results over the scanned items (F2), the phone twin of the desktop's Results
/// screen: run over the sample with Laya (off the main thread, cancellable), or say plainly that
/// the model is not installed — never fake scores. Numbers are the model's raw probabilities.
struct JudgmentResultsView: View {
    @ObservedObject var service: JudgmentsService
    let judgmentId: String
    var autoRun = false
    @State private var confirmCriteria = false
    @State private var didAutoRun = false

    var body: some View {
        ScrollView {
            if let j = service.judgment(judgmentId) {
                LazyVStack(alignment: .leading, spacing: 12) {
                    header(j)
                    modelCard(j)
                    if let s = service.sweep, s.judgmentId == j.id { sweepCard(s) }
                    criteriaCard(j)
                    results(j)
                }
                .padding(16)
            } else {
                Text("This judgment was deleted.").foregroundStyle(Palette.inkSoft).padding(24)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("Results")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await service.checkModel()
            if autoRun && !didAutoRun && service.gate == .ready {
                didAutoRun = true
                await service.startSweep(judgmentId)
            }
        }
    }

    private func header(_ j: UserJudgment) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(j.title).font(Typeface.display(26)).foregroundStyle(Palette.ink)
                Spacer()
                if j.warnOnly { Pill(text: "warn-only", color: Palette.amber) }
            }
            Text(j.question).font(.subheadline).foregroundStyle(Palette.blue)
            Text("\(j.shapeName) · acts at \(pct(j.threshold)) raw · \(j.templateId == nil ? "written by you" : "from a template")")
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            if j.useBaseline { Pill(text: "answers by baseline rule", color: Palette.amber) }
            NavigationLink(value: JudgmentRoute.measure(j.id)) {
                Label("Measure: calibration, baseline, threshold", systemImage: "chart.bar.xaxis")
            }
            .accessibilityIdentifier("results.measure")
        }
    }

    // MARK: Model and run

    @ViewBuilder private func modelCard(_ j: UserJudgment) -> some View {
        switch service.gate {
        case .notInstalled:
            VStack(alignment: .leading, spacing: 8) {
                Label("Model not installed", systemImage: "cpu").font(.headline).foregroundStyle(Palette.ink)
                Text("Judgments run on Laya, on this phone. Until it is installed nothing is judged, so no scores are shown.")
                    .font(.subheadline).foregroundStyle(Palette.inkSoft)
                NavigationLink { LayaModelView() } label: { Label("Get the on-device model", systemImage: "arrow.down.circle") }
            }
            .frame(maxWidth: .infinity, alignment: .leading).card()
            .accessibilityIdentifier("results.modelMissing")
        case .failed(let why):
            VStack(alignment: .leading, spacing: 6) {
                Label("The model could not be opened", systemImage: "exclamationmark.triangle.fill").foregroundStyle(Palette.red)
                Text(why).font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            .frame(maxWidth: .infinity, alignment: .leading).card()
        case .unknown:
            HStack { ProgressView(); Text("Checking the model…").foregroundStyle(Palette.inkSoft) }
                .frame(maxWidth: .infinity, alignment: .leading).card()
        case .ready:
            let items = service.sampleItems()
            VStack(alignment: .leading, spacing: 8) {
                Text("A run judges every sample item that has text, skipping ones already judged under this wording. It runs on this phone, in the background, and can be cancelled.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                HStack {
                    Button { Task { await service.startSweep(j.id) } } label: { Label("Run on new items", systemImage: "play.fill") }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("results.run")
                    Button("Re-run all") { Task { await service.startSweep(j.id, rerunAll: true) } }
                        .buttonStyle(.bordered)
                }
                .disabled(service.running || items.isEmpty)
                Text(items.isEmpty ? "No items scanned yet — turn on the sample in Sources." : "\(items.count) items · \(SourcesService.sampleLabel)")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            }
            .frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }

    private func sweepCard(_ s: SweepProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(s.running ? "Running" : s.cancelled ? "Cancelled" : s.error != nil ? "Stopped" : "Last run")
                    .font(.headline)
                Spacer()
                if s.running {
                    Button("Cancel", role: .cancel) { service.cancelSweep() }
                        .accessibilityIdentifier("results.cancel")
                }
            }
            ProgressView(value: s.fraction)
                .tint(Palette.blue)
                .accessibilityIdentifier("results.progress")
            HStack(spacing: 14) {
                stat("\(s.done) / \(s.total)", "judged this run")
                stat(String(format: "%.1f", s.itemsPerSecond), "items / s")
                stat(s.medianMillis.map { String(format: "%.0f ms", $0.doubleValue) } ?? "—", "median model call")
            }
            Text("\(s.alreadyDecided) already judged (skipped) · \(s.withoutText) without text (not sent) · \(s.mechanical) answered by rule · \(s.unusable) unusable")
                .font(.caption).foregroundStyle(Palette.inkSoft)
            if let e = s.error { Text("The run stopped: \(e)").font(.footnote).foregroundStyle(Palette.red) }
        }
        .frame(maxWidth: .infinity, alignment: .leading).card()
    }

    // MARK: Criteria in prompt

    private func criteriaCard(_ j: UserJudgment) -> some View {
        let applies = JudgmentBook.shared.criteriaApplicable(judgment: j)
        return VStack(alignment: .leading, spacing: 6) {
            Toggle("Show the criteria to the model", isOn: Binding(
                get: { j.criteriaInPrompt },
                set: { _ in confirmCriteria = true }))
                .disabled(!applies || service.running)
                .accessibilityIdentifier("results.criteriaInPrompt")
            Text(applies ? JudgmentBook.shared.CRITERIA_WARNING : "This judgment has no per-option criteria to show.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        }
        .card()
        .confirmationDialog("Calibration starts again", isPresented: $confirmCriteria, titleVisibility: .visible) {
            Button(j.criteriaInPrompt ? "Stop showing the criteria" : "Show the criteria") {
                service.setCriteriaInPrompt(j.id, on: !j.criteriaInPrompt)
            }
        } message: {
            Text("This changes what the model reads, so earlier decisions stop counting and calibration restarts.")
        }
    }

    // MARK: Results

    @ViewBuilder private func results(_ j: UserJudgment) -> some View {
        let rows = service.results(j)
        let counts = service.counts(j)
        if rows.isEmpty {
            Text(service.gate == .ready ? "\"\(j.title)\" has not run yet. Run it and results appear here." : "No results yet.")
                .font(.subheadline).foregroundStyle(Palette.inkSoft).padding(.vertical, 8)
                .accessibilityIdentifier("results.empty")
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Caption(text: "\(counts.decisions) judged · \(counts.acted) answered · \(counts.unsure) unsure")
                Text("Raw model probabilities, uncalibrated. At or above \(pct(j.threshold)) the engine answers; below it, it is unsure and the item waits for you.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                if counts.unusable > 0 {
                    Text("\(counts.unusable) item(s) could not be judged. \(j.onFailure == .loud ? "This judgment is loud: check them yourself; silence is not \"nothing found\"." : "Nothing was done with them.")")
                        .font(.caption).foregroundStyle(Palette.red)
                }
            }
            ForEach(rows, id: \.itemId) { r in
                NavigationLink { ResultDetailView(judgment: j, result: r) } label: { ResultRowView(judgment: j, result: r) }
                    .buttonStyle(.plain)
            }
        }
    }

    private func stat(_ n: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.display(20)).foregroundStyle(Palette.blue)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
        }
    }
}

func pct(_ p: Double) -> String { "\(Int((p * 100).rounded()))%" }

struct ResultRowView: View {
    let judgment: UserJudgment
    let result: ResultRow

    private var tone: Color {
        if result.unusable { return Palette.red }
        return result.acted ? Palette.ink : Palette.amber
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(result.item?.name ?? result.itemId).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                Spacer()
                Text(result.unusable ? "—" : pct(result.topMass)).font(Typeface.mono(12, weight: .medium)).foregroundStyle(tone)
            }
            Text(result.status(judgment: judgment)).font(.footnote.weight(.medium)).foregroundStyle(tone).lineLimit(2)
            ConfidenceBar(value: result.unusable ? 0 : result.topMass, threshold: judgment.threshold, color: tone)
            HStack(spacing: 6) {
                Text(source).font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft).lineLimit(1)
                Spacer()
                if result.mechanical { Pill(text: "answered by rule", color: Palette.mint) }
                if !result.acted && !result.unusable { Pill(text: "unsure", color: Palette.amber, symbol: "questionmark") }
                if result.inputCutNote != nil { Pill(text: "cut", color: Palette.inkSoft, symbol: "scissors") }
            }
            if let note = result.inputCutNote { Text(note).font(.caption2).foregroundStyle(Palette.inkSoft) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("results.row")
    }

    private var source: String {
        guard let i = result.item else { return "no longer scanned" }
        return ([i.sourceLabel, i.kind.title] + [i.dateIso].compactMap { $0 }).joined(separator: " · ")
    }
}

struct ConfidenceBar: View {
    let value: Double
    let threshold: Double
    var color: Color = Palette.blue

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.hairline)
                Capsule().fill(color.opacity(0.8)).frame(width: max(0, min(1, value)) * g.size.width)
                Rectangle().fill(Palette.ink.opacity(0.5)).frame(width: 1.5).offset(x: threshold * g.size.width)
            }
        }
        .frame(height: 6)
        .accessibilityHidden(true)
    }
}

/// One item: the whole raw distribution, the notes, its facts and what the model read.
struct ResultDetailView: View {
    let judgment: UserJudgment
    let result: ResultRow

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(result.item?.name ?? result.itemId).font(Typeface.display(24)).foregroundStyle(Palette.ink)
                VStack(alignment: .leading, spacing: 8) {
                    Caption(text: result.mechanical ? "What the rule said" : "What the model said (raw)")
                    ForEach(result.masses(judgment: judgment), id: \.first) { pair in
                        let label = (pair.first as String?) ?? ""
                        let mass = (pair.second as? KotlinDouble)?.doubleValue ?? 0
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                Text(judgment.shown(label)).font(.subheadline)
                                Spacer()
                                Text(pct(mass)).font(Typeface.mono(12))
                            }
                            ConfidenceBar(value: mass, threshold: judgment.threshold)
                        }
                    }
                    Text(result.status(judgment: judgment)).font(.footnote.weight(.semibold))
                    ForEach([result.ruleNote, result.row.failure.map { "Unusable answer: \($0)" }, result.inputCutNote, result.criteriaCutNote].compactMap { $0 }, id: \.self) {
                        Text($0).font(.caption).foregroundStyle(Palette.inkSoft)
                    }
                    Text("Preview only: Loupe changes nothing on this phone.").font(.caption).foregroundStyle(Palette.inkSoft)
                }
                .frame(maxWidth: .infinity, alignment: .leading).card()
                SecondOpinionSection(judgment: judgment, result: result, assist: AssistService.shared)
                if let item = result.item {
                    VStack(alignment: .leading, spacing: 6) {
                        Caption(text: "Mechanical facts")
                        fact("Source", item.sourceLabel)
                        fact("Type", "\(item.kind.title) (\(item.mime))")
                        if let d = item.dateIso { fact("Date", "\(d) (from the \(item.dateOrigin?.title ?? "file"))") }
                        if let e = item.email {
                            fact("From", [e.fromName, e.fromAddress.map { "<\($0)>" }].compactMap { $0 }.joined(separator: " "))
                            if let s = e.subject { fact("Subject", s) }
                        }
                        if let dup = item.duplicateOf { fact("Exact duplicate of", dup) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).card()
                    VStack(alignment: .leading, spacing: 6) {
                        Caption(text: "What the model read")
                        Text(String(item.text.prefix(2000)) + (item.text.count > 2000 ? "\n…" : ""))
                            .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).card()
                }
            }
            .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("Item")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func fact(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(.caption.weight(.semibold)).foregroundStyle(Palette.inkSoft).frame(width: 90, alignment: .leading)
            Text(v).font(.caption).foregroundStyle(Palette.ink)
        }
    }
}
