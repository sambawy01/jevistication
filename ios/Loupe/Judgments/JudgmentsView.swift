import SwiftUI
import LoupeKit

/// The Judgments tab (epic #7 child 3): My judgments, the template Library (C1) and Write your own
/// (C2). Mirrors the desktop's Library / Judgments / Results screens.
struct JudgmentsView: View {
    enum Section: String, CaseIterable { case mine = "My judgments", library = "Library" }

    @ObservedObject var service: JudgmentsService
    @State private var section: Section = .mine
    @State private var path = NavigationPath()
    @State private var writing = false
    @State private var demoDone = false

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                Picker("Section", selection: $section) {
                    ForEach(Section.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .accessibilityIdentifier("judgments.section")
                switch section {
                case .mine: MyJudgmentsList(service: service, openLibrary: { section = .library })
                case .library: LibraryView(service: service)
                }
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle("Judgments")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { writing = true } label: { Label("Write your own", systemImage: "square.and.pencil") }
                        .accessibilityIdentifier("judgments.write")
                }
            }
            .navigationDestination(for: JudgmentRoute.self) { route in
                switch route {
                case .results(let id): JudgmentResultsView(service: service, judgmentId: id, autoRun: route == demoRoute)
                case .template(let id): TemplateDetailView(service: service, templateId: id)
                case .measure(let id): MeasureView(service: service, judgmentId: id)
                case .queue: UnsureQueueView(service: service)
                }
            }
            .sheet(isPresented: $writing) {
                WriteJudgmentView(service: service) { id in
                    writing = false
                    section = .mine
                    path.append(JudgmentRoute.results(id))
                }
            }
        }
        .onAppear {
            service.load()
            runDemo()
        }
    }

    private var demoRoute: JudgmentRoute? {
        guard let t = LaunchOptions.current.judgmentDemo else { return nil }
        return service.judgments.first { $0.templateId == t }.map { .results($0.id) }
    }

    /// DEBUG `-LoupeJudgmentDemo <template>`: makes sure a judgment from it exists and opens its
    /// results (which then run over the sample). `-LoupeLibrary` opens the Library.
    private func runDemo() {
        guard !demoDone else { return }
        demoDone = true
        let launch = LaunchOptions.current
        if launch.openLibrary { section = .library }
        guard let t = launch.judgmentDemo else { return }
        if service.judgments.first(where: { $0.templateId == t }) == nil { service.useTemplate(t) }
        if let route = demoRoute { path.append(route) }
        if launch.openScreen == "measure", let j = service.judgments.first(where: { $0.templateId == t }) {
            path.append(JudgmentRoute.measure(j.id))
        }
    }
}

enum JudgmentRoute: Hashable {
    case results(String)
    case template(String)
    case measure(String)
    case queue
}

// MARK: - My judgments

struct MyJudgmentsList: View {
    @ObservedObject var service: JudgmentsService
    let openLibrary: () -> Void

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                if service.judgments.isEmpty {
                    VStack(spacing: 12) {
                        MascotView(state: .empty, size: 80)
                        Text("No judgments yet").font(Typeface.display(26)).foregroundStyle(Palette.ink)
                        Text("Pick a template from the Library, or write your own question.")
                            .multilineTextAlignment(.center).foregroundStyle(Palette.inkSoft)
                        Button("Open the Library", action: openLibrary)
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("judgments.openLibrary")
                    }
                    .padding(24).frame(maxWidth: .infinity).card()
                } else {
                    NavigationLink(value: JudgmentRoute.queue) { NeedsYouCard(count: service.needsYou) }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("judgments.needsYou")
                    ForEach(service.judgments, id: \.id) { j in
                        NavigationLink(value: JudgmentRoute.results(j.id)) { JudgmentCard(judgment: j, counts: service.counts(j)) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("judgments.mine.\(j.templateId ?? j.id)")
                            .contextMenu {
                                Button(role: .destructive) { service.delete(j.id) } label: { Label("Delete", systemImage: "trash") }
                            }
                    }
                    Text("Decisions stay in the ledger when a judgment is deleted.")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                }
                if let n = service.notice {
                    Text(n).font(.footnote).foregroundStyle(Palette.inkSoft).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16)
        }
    }
}

struct JudgmentCard: View {
    let judgment: UserJudgment
    let counts: JudgmentCounts

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(judgment.title).font(.headline).foregroundStyle(Palette.ink)
                Spacer()
                if judgment.warnOnly { Pill(text: "warn-only", color: Palette.amber) }
            }
            Text(judgment.question).font(.subheadline).foregroundStyle(Palette.inkSoft).lineLimit(2)
            HStack(spacing: 6) {
                Pill(text: judgment.shapeName)
                Pill(text: judgment.templateId == nil ? "written by you" : "template", color: Palette.inkSoft)
                if judgment.criteriaInPrompt { Pill(text: "criteria in prompt", color: Palette.cyan) }
            }
            HStack(spacing: 16) {
                stat("\(counts.decisions)", "judged")
                stat("\(counts.acted)", "answered")
                stat("\(counts.unsure)", "unsure")
                if counts.unusable > 0 { stat("\(counts.unusable)", "could not judge", color: Palette.red) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("judgments.counts")
            if counts.earlierWording > 0 {
                Text("\(counts.earlierWording) decision(s) under earlier wording are kept, not counted.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func stat(_ n: String, _ label: String, color: Color = Palette.blue) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.display(22)).foregroundStyle(color)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
        }
    }
}

/// "Needs you: N" — the Unsure queue's door, on Now and in My judgments.
struct NeedsYouCard: View {
    let count: Int

    var body: some View {
        HStack(spacing: 12) {
            MascotView(state: count > 0 ? .thinking : .idle, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text("Needs you: \(count)").font(.headline).foregroundStyle(Palette.ink)
                Text(count > 0 ? "Items Laya is unsure about, plus a few random checks. One tap each."
                               : "Nothing waiting for your answer.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .combine)
    }
}
