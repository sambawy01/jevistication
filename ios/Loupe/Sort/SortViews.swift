import SwiftUI
import LoupeKit

/// Me → Checks (2026-09-28): the nightly run ("Check overnight while charging", F1's "Sort while charging" grown into
/// every check), the morning notification, Run now with the live progress and Cancel, and what the last runs did.
struct SortSection: View {
    @ObservedObject var sort: SortService = .shared
    @ObservedObject var runs: RunCoordinator = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @AppStorage(NightlySettings.notifyKey) private var notify = true

    var body: some View {
        NeonSection {
            controls
            if case .locked = ModelGate.decide(needsModel: true, readiness.state), !sort.running {
                NeedsLayaCard(feature: "sort", what: "Sorting")
            }
            if let night = runs.lastNightly {
                Text("Last night: \(night.outcomeLine) · \(night.endedAt.formatted(.relative(presentation: .named)))")
                    .font(.footnote).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("me.nightly.last")
            }
            if let last = sort.last {
                Text("Last run: \(last.line)\(last.finished ? "" : " (stopped early)") · \(last.at.formatted(.relative(presentation: .named)))")
                    .font(.footnote).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("me.sort.summary")
            }
        } header: {
            Text("Checks")
        } footer: {
            Text("Once a night while the phone charges, Loupe reads your sources again, runs the privacy check, mail triage and the watchers, then sorts every judgment over what is new. It stops when the phone is hot or in Low Power Mode and carries on the next night. Opening the app runs nothing: it shows the last results. On unless you turn it off.")
        }
    }

    @ViewBuilder private var controls: some View {
        Toggle("Check overnight while charging", isOn: Binding(
            get: { sort.enabled },
            set: { on in
                sort.enabled = on
                Task { await BackgroundSorter.shared.settingChanged(on: on) }
            }))
            .accessibilityIdentifier("me.sort.toggle")
        Toggle("Tell me in the morning when it finds something", isOn: $notify)
            .disabled(!sort.enabled)
            .accessibilityIdentifier("me.nightly.notify")
        if let p = runs.current {
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: p.count.flatMap { c in c.total.map { $0 > 0 ? min(1, Double(c.done) / Double($0)) : 0 } } ?? 0)
                Text(p.stage == .sort && sort.running ? sort.progress.map(SortService.progressLine) ?? p.line : p.line)
                    .font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("me.sort.progress")
            }
            Button(p.cancelling ? "Stopping…" : "Cancel", role: .cancel) { runs.cancel() }
                .disabled(p.cancelling)
                .accessibilityIdentifier("me.sort.cancel")
        } else {
            Button("Run now") { runs.runNow(reason: .manual) }
                .accessibilityIdentifier("me.sort.run")
        }
        if let notice = sort.notice {
            Text(notice).font(.footnote).foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("me.sort.notice")
        }
        LayaOffBanner(feature: Features.shared.JUDGMENTS)
    }
}

/// Now's card: the last run in real counts ("412 sorted, 9 need you").
struct SortedCard: View {
    let record: SortRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(record.line).font(.headline).foregroundStyle(Palette.ink)
                .accessibilityIdentifier("now.sorted.line")
            Text("\(record.trigger == .background ? "Sorted while charging" : "Sorted on request") \(record.at.formatted(.relative(presentation: .named)))\(record.finished ? "" : " · stopped early, carries on next time")")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}
