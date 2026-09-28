import SwiftUI

/// The live run panel on top of Now (2026-09-28, owner decision D: visible, never frozen, cancellable). While a run
/// goes: which stage of how many, the counts, the item being read, the rate and time left, and Cancel. When idle:
/// the last run in one line with Run now. Draws only from `RunCoordinator.current` / `last`, which publish at most
/// ~8 times a second, so nothing here redraws per item. The shell branch replaces Now with Home and draws its own
/// HomeScanPanel from the same `RunCoordinator.current`; this one stays simple on purpose.
struct RunPanel: View {
    @ObservedObject var runs: RunCoordinator = .shared

    var body: some View {
        if let p = runs.current {
            running(p)
        } else if let last = runs.last {
            idle(last)
        } else {
            neverRan
        }
    }

    private func running(_ p: RunProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(p.reason.title) · step \(p.stageNumber) of \(p.stages.count)")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("run.reason")
                Spacer(minLength: 8)
                Button(role: .cancel) { runs.cancel() } label: {
                    Text(p.cancelling ? "Stopping…" : "Cancel")
                        .font(.footnote.weight(.semibold))
                        .padding(.horizontal, 12)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .disabled(p.cancelling)
                .accessibilityIdentifier("run.cancel")
            }
            Text(p.stage.title + (p.part.map { " · \($0)" } ?? ""))
                .font(.headline).foregroundStyle(Palette.ink)
                .accessibilityIdentifier("run.stage")
            if let c = p.count {
                ProgressView(value: c.total.map { $0 > 0 ? min(1, Double(c.done) / Double($0)) : 0 } ?? 0)
                    .tint(Palette.cyan)
                    .opacity(c.total == nil ? 0.3 : 1)
                Text(countLine(c, p))
                    .font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("run.counts")
            }
            if let item = p.item, !item.isEmpty {
                Text(item).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
                    .accessibilityIdentifier("run.item")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(active: true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run.panel")
    }

    private func countLine(_ c: RunStageCount, _ p: RunProgress) -> String {
        var parts: [String] = []
        if let t = c.total { parts.append("\(c.done.formatted()) of \(t.formatted()) \(p.stage.unit)") }
        else if c.done > 0 { parts.append("\(c.done.formatted()) \(p.stage.unit)") }
        else { parts.append("Starting") }
        if let r = p.rate, r > 0 { parts.append("\(ScanSnapshot.rateText(r))/s") }
        if let eta = p.eta { parts.append("about \(ScanSnapshot.duration(eta)) left") }
        return parts.joined(separator: " · ")
    }

    private func idle(_ last: RunRecord) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(lastTitle(last)).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("run.lastTitle")
                Text(last.outcomeLine)
                    .font(.footnote).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("run.last")
            }
            Spacer(minLength: 8)
            runNowButton
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run.panel")
    }

    private var neverRan: some View {
        HStack(spacing: 10) {
            Text("No check has run yet. Loupe checks overnight while charging, or now if you ask.")
                .font(.footnote).foregroundStyle(Palette.ink)
                .accessibilityIdentifier("run.last")
            Spacer(minLength: 8)
            runNowButton
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("run.panel")
    }

    private var runNowButton: some View {
        Button { runs.runNow(reason: .manual) } label: {
            Label("Run now", systemImage: "arrow.clockwise")
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
        }
        .buttonStyle(.neonPrimaryCompact)
        .accessibilityIdentifier("run.runNow")
    }

    private func lastTitle(_ r: RunRecord) -> String {
        let what: String
        switch r.reason {
        case .firstCheck: what = "First check"
        case .nightly: what = "Overnight check"
        case .manual: what = "Checked on request"
        case .scanAgain: what = "Scan again"
        case .itemsChanged: what = "Re-check"
        }
        return "\(what) · \(r.endedAt.formatted(.relative(presentation: .named)))"
    }

}
