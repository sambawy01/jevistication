import SwiftUI
import LoupeKit

/// Me → "Sort while charging" and "Run now" (F1/F2 on the iPhone).
struct SortSection: View {
    @ObservedObject var sort: SortService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared

    var body: some View {
        NeonSection {
            if case .locked = ModelGate.decide(needsModel: true, readiness.state), !sort.running {
                NeedsLayaCard(feature: "sort", what: "Sorting")
            } else {
                controls
            }
            if let last = sort.last {
                Text("Last run: \(last.line)\(last.finished ? "" : " (stopped early)") · \(last.at.formatted(.relative(presentation: .named)))")
                    .font(.footnote).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("me.sort.summary")
            }
        } header: {
            Text("Sorting")
        } footer: {
            Text("Runs every judgment over every source that is on, skipping what is already sorted under the current wording, then the watchers. While charging it waits for iOS to offer a window; it stops when the phone is hot or in Low Power Mode, and pauses whenever you use the model yourself. Off unless you turn it on.")
        }
    }

    @ViewBuilder private var controls: some View {
        Toggle("Sort while charging", isOn: Binding(
            get: { sort.enabled },
            set: { on in
                sort.enabled = on
                Task { await BackgroundSorter.shared.settingChanged(on: on) }
            }))
            .accessibilityIdentifier("me.sort.toggle")
        if sort.running, let p = sort.progress {
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: p.fraction)
                Text(SortService.progressLine(p))
                    .font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("me.sort.progress")
            }
            Button("Cancel", role: .cancel) { sort.cancel() }
                .accessibilityIdentifier("me.sort.cancel")
        } else {
            Button("Run now") { Task { await sort.run(.manual) } }
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
