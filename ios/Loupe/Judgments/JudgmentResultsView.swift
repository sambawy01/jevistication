import SwiftUI
import LoupeKit

/// One judgment's results (F2) as a dashboard (owner feedback 2026-09-26): the question and the answer split
/// as a tappable donut, who answered, the model's confidence against its threshold, breakdowns by source, kind
/// and date, what decided, the way into the Unsure queue, then the list itself — filtered by whatever was
/// tapped, searchable, sortable, grouped by answer, correctable inline and in bulk. Runs stay as before: over
/// the scanned items with the decision model (off the main thread, cancellable), or it says plainly that the
/// model is not installed — never fake scores. Numbers are the model's raw probabilities.
struct JudgmentResultsView: View {
    @ObservedObject var service: JudgmentsService
    let judgmentId: String
    var autoRun = false
    @StateObject private var model: ResultsModel
    @State private var confirmCriteria = false
    @State private var didAutoRun = false
    @State private var openItem: Int32?
    @State private var changing: Int32?
    @State private var showQueue = false
    @State private var showMeasure = false
    @ObservedObject private var readiness = ModelReadiness.shared
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    init(service: JudgmentsService, judgmentId: String, autoRun: Bool = false) {
        self.service = service
        self.judgmentId = judgmentId
        self.autoRun = autoRun
        _model = StateObject(wrappedValue: ResultsModel(judgmentId: judgmentId))
    }

    var body: some View {
        Group {
            if let j = service.judgment(judgmentId) {
                content(j)
            } else {
                Text("This judgment was deleted.").foregroundStyle(Palette.inkSoft).padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).neonGround()
            }
        }
        .navigationTitle(service.judgment(judgmentId)?.title ?? "Results")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            #if DEBUG
            if let n = ResultsFixture.requested {
                let t0 = CFAbsoluteTimeGetCurrent()
                await ResultsFixture.seed(service, judgmentId: judgmentId, n: n)
                print("LOUPE-PERF results fixture seed n=\(n) \(Int((CFAbsoluteTimeGetCurrent() - t0) * 1000))ms")
            }
            #endif
            model.markStart()
            await model.refresh(service)
            await service.checkModel()
            #if DEBUG
            if ResultsFixture.requested != nil { return }
            #endif
            if autoRun && !didAutoRun && readiness.isReady && service.gate == .ready {
                didAutoRun = true
                await service.startSweep(judgmentId)
            }
        }
        .onChange(of: rebuildStamp) { _, _ in Task { await model.refresh(service) } }
    }

    /// A run added rows, or the judgment was reworded / re-thresholded: rebuild the index.
    private var rebuildStamp: String {
        let j = service.judgment(judgmentId)
        return "\(service.rows.count)|\(j?.criteriaHash ?? "")|\(j?.threshold ?? 0)"
    }

    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }

    private func content(_ j: UserJudgment) -> some View {
        let muted = ResultsBuilder.mutedOption(j)
        return ScrollViewReader { proxy in
            let toList = {
                if reduceMotion { proxy.scrollTo("list", anchor: .top) } else {
                    withAnimation(.easeOut(duration: 0.35)) { proxy.scrollTo("list", anchor: .top) }
                }
            }
            List {
                Section {
                    Group {
                        LayaOffBanner(feature: Features.shared.JUDGMENTS)
                        // With results, the summary leads and the run controls follow it; without, the run leads.
                        let hasResults = (model.index?.count ?? 0) > 0
                        if !hasResults { modelCard(j) }
                        if let s = service.sweep, s.judgmentId == j.id { sweepCard(s) }
                        // The run, live and in place: the pipeline, the mascot engine, the decision diamonds and cards.
                        LiveRunSection(view: "judgments") { q in
                            service.judgments.first { Activity.shared.questionId(id: $0.id) == q }?.title
                        }
                        dashboard(j, muted: muted, toList: toList)
                        if hasResults { modelCard(j) }
                        criteriaCard(j)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                    // Each button in a card acts on its own (a default-style button would make the whole row one tap).
                    .buttonStyle(.borderless)
                }
                if let index = model.index, index.count > 0 {
                    Section {
                        Color.clear.frame(height: 1).id("list")
                            .listRowBackground(Color.clear).listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets())
                        if model.sections.isEmpty {
                            Text(model.filter.isEmpty ? "No results." : "Nothing matches these filters.")
                                .font(.subheadline).foregroundStyle(Palette.inkSoft)
                                .listRowBackground(Color.clear).listRowSeparator(.hidden)
                                .accessibilityIdentifier("results.noMatch")
                        }
                        ForEach(model.sections) { section in
                            ResultsGroupHeader(title: model.bucketTitle(section.bucket),
                                               color: ResultsLook.bucket(section.bucket, muted: muted),
                                               count: section.rows.count, key: section.bucket.key)
                                .listRowBackground(Color.clear).listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                            ForEach(section.rows, id: \.self) { i in
                                row(i, j, muted: muted)
                            }
                        }
                    } header: {
                        ResultsFilterBar(model: model)
                            .listRowInsets(EdgeInsets())
                    }
                } else {
                    Section {
                        Text(model.building ? "Reading the results…" :
                                service.gate == .ready ? "\"\(j.title)\" has not run yet. Run it and results appear here." : "No results yet.")
                            .font(.subheadline).foregroundStyle(Palette.inkSoft)
                            .listRowBackground(Color.clear).listRowSeparator(.hidden)
                            .accessibilityIdentifier("results.empty")
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 1)
            .neonGround()
            .scrollDismissesKeyboard(.immediately)
            .navigationDestination(item: $openItem) { i in
                ResultItemView(model: model, service: service, index: i, muted: muted)
            }
            .navigationDestination(isPresented: $showQueue) { UnsureQueueView(service: service, judgmentId: j.id) }
            .navigationDestination(isPresented: $showMeasure) { MeasureView(service: service, judgmentId: j.id) }
            .safeAreaInset(edge: .bottom) {
                if model.selecting {
                    ResultsBulkBar(model: model) { option in
                        model.correct(Array(model.selected), to: option, service: service)
                        model.selecting = false
                    }
                } else if let change = model.lastChange {
                    ResultsUndoBar(text: change.summary, undo: { model.undoLast(service: service) }, dismiss: { model.dismissChange() })
                }
            }
            .confirmationDialog("Change the answer", isPresented: Binding(get: { changing != nil }, set: { if !$0 { changing = nil } }),
                                titleVisibility: .visible) {
                if let i = changing, let r = model.record(i) {
                    ForEach(model.options.indices, id: \.self) { o in
                        if r.canCorrect(to: o) && r.answer != o {
                            Button(model.optionTitle(o)) { model.correct([i], to: o, service: service) }
                        }
                    }
                }
            }
            .onAppear { model.reconcile(service) }
            #if DEBUG
            .overlay(alignment: .topTrailing) {
                if ResultsFixture.showPerf {
                    Text(model.perf.line).font(Typeface.mono(9)).foregroundStyle(Palette.inkSoft)
                        .padding(4).background(Palette.ground.opacity(0.8))
                        .accessibilityIdentifier("results.perf")
                }
            }
            #endif
        }
    }

    // MARK: Dashboard

    @ViewBuilder private func dashboard(_ j: UserJudgment, muted: Int?, toList: @escaping () -> Void) -> some View {
        if let index = model.index, index.count > 0 {
            ResultsSummaryCard(model: model, judgment: j, muted: muted, onFilter: toList)
                .onAppear { model.didPaint() }
            Button { showQueue = true } label: {
                NeedsYouEntry(count: index.summary.count(.unsure))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("results.needsYou")
            WhoAnsweredCard(model: model, onFilter: toList)
            ConfidenceCard(model: model, muted: muted, onFilter: toList)
            BreakdownsCard(model: model, muted: muted, onFilter: toList)
            ReasonsCard(model: model, judgment: j, onFilter: toList)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text(j.question).font(Typeface.display(24)).foregroundStyle(Palette.ink)
                Text("\(JudgmentRunLog.line(j.id)) · \(j.shapeName) · acts at \(pct(j.threshold))")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
        }
    }

    private func row(_ i: Int32, _ j: UserJudgment, muted: Int?) -> some View {
        let r = model.record(i)!
        let item = model.row(i)?.item ?? fixtureItem(r.itemId)
        let selected = model.selected.contains(i)
        return ResultRowCell(
            record: r, item: item, options: model.options,
            answerTitle: r.answer.map { model.optionTitle($0) } ?? (r.unusable ? "could not judge" : "unsure"),
            answerColor: ResultsLook.bucket(r.bucket, muted: muted),
            optionTitle: { model.optionTitle($0) },
            selecting: model.selecting, selected: selected,
            open: {
                if model.selecting {
                    if selected { model.selected.remove(i) } else { model.selected.insert(i) }
                } else {
                    openItem = i
                }
            },
            correct: { model.correct([i], to: $0, service: service) })
        .listRowBackground(selected ? Palette.accentSoft : Color.clear)
        .listRowSeparatorTint(Palette.hairline)
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if r.correction == nil, r.answer != nil || (r.top >= 0 && !r.unusable) {
                Button { model.correct([i], to: nil, service: service) } label: { Label("Confirm", systemImage: "checkmark.seal") }
                    .tint(Palette.mint)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            let others = model.options.indices.filter { r.canCorrect(to: $0) && r.answer != $0 }
            if others.count <= 2 {
                ForEach(others, id: \.self) { o in
                    Button { model.correct([i], to: o, service: service) } label: { Text("Mark: \(model.optionTitle(o))") }
                        .tint(o == muted ? Palette.blueBright : Palette.blue)
                }
            } else {
                Button { changing = i } label: { Label("Change…", systemImage: "tag") }.tint(Palette.blue)
            }
        }
        .contextMenu {
            ForEach(model.options.indices, id: \.self) { o in
                if r.canCorrect(to: o) {
                    Button("Mark as \(model.optionTitle(o))") { model.correct([i], to: o, service: service) }
                }
            }
        }
    }

    private func fixtureItem(_ id: String) -> SourceItem? {
        #if DEBUG
        return ResultsFixture.items[id]
        #else
        return nil
        #endif
    }

    // MARK: Model and run

    @ViewBuilder private func modelCard(_ j: UserJudgment) -> some View {
        if case .locked = ModelGate.decide(needsModel: true, readiness.state), !service.running {
            NeedsLayaCard(feature: "judgments", what: "Running a judgment")
        } else {
            runCard(j)
        }
    }

    @ViewBuilder private func runCard(_ j: UserJudgment) -> some View {
        switch service.gate {
        case .notInstalled:
            NeedsLayaCard(feature: "judgments", what: "Running a judgment")
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
                HStack {
                    Button { Task { await service.startSweep(j.id) } } label: { Label("Run on new items", systemImage: "play.fill") }
                        .buttonStyle(.neonPrimary)
                        .accessibilityIdentifier("results.run")
                    Button("Re-run all") { Task { await service.startSweep(j.id, rerunAll: true) } }
                        .buttonStyle(.bordered)
                        .frame(minHeight: 44)
                }
                .disabled(service.running || items.isEmpty)
                Text(items.isEmpty ? "No items scanned yet — turn on the sample in Sources."
                     : "\(items.count) items · a run judges the ones with text not yet judged under this wording, on this phone, cancellable.")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
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
                        .frame(minHeight: 44)
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

    // MARK: Criteria in prompt, Measure

    private func criteriaCard(_ j: UserJudgment) -> some View {
        let applies = JudgmentBook.shared.criteriaApplicable(judgment: j)
        let counts = service.counts(j)
        return VStack(alignment: .leading, spacing: 8) {
            Button { showMeasure = true } label: {
                HStack {
                    Label("Measure: calibration, baseline, threshold", systemImage: "chart.bar.xaxis")
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(Palette.cyan)
            .accessibilityIdentifier("results.measure")
            Toggle("Show the criteria to the model", isOn: Binding(
                get: { j.criteriaInPrompt },
                set: { _ in confirmCriteria = true }))
                .disabled(!applies || service.running)
                .accessibilityIdentifier("results.criteriaInPrompt")
            Text(applies ? JudgmentBook.shared.CRITERIA_WARNING : "This judgment has no per-option criteria to show.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
            if j.baseline != nil, service.autoBaseline(j).baselineAnswers {
                Pill(text: j.useBaseline ? "answers by baseline rule" : "baseline rule answers (auto)", color: Palette.amber)
            }
            if counts.unusable > 0 {
                Text("\(counts.unusable) item(s) could not be judged. \(j.onFailure == .loud ? "This judgment is loud: check them yourself; silence is not \"nothing found\"." : "Nothing was done with them.")")
                    .font(.caption).foregroundStyle(Palette.red)
            }
            if counts.earlierWording > 0 {
                Text("\(counts.earlierWording) decision(s) under earlier wording are kept in the ledger and not counted here.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
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

    private func stat(_ n: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.display(20)).monospacedDigit().foregroundStyle(Palette.blue)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
        }
    }
}
