import SwiftUI
import LoupeKit

/// The small Activity dock at the inline-end corner, above the tab bar: jobs running off-screen. Tapping it
/// opens the Activity panel (running jobs with Cancel, the last ten finished). Hidden when nothing runs
/// off-screen.
struct ActivityDock: View {
    @ObservedObject private var center = ActivityCenter.shared
    @State private var open = false

    var body: some View {
        let off = center.offScreen
        VStack(spacing: 8) {
            if let load = center.modelLoad { ModelLoadBanner(job: load) }
            if !off.isEmpty {
                HStack {
                    Spacer()
                    Button { open = true } label: {
                        HStack(spacing: 6) {
                            NeonIcon(name: off.isEmpty ? "clock.arrow.circlepath" : "bolt.horizontal.circle.fill",
                                     color: Palette.cyan, size: 14, active: !off.isEmpty)
                            Text(off.isEmpty ? ActStrings.t("act.chip.idle")
                                 : ActStrings.t("act.chip.many", ["n": "\(off.count)", "lead": LiveRunModel.message(off[0].title)]))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Palette.ink)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Palette.cardHigh, in: Capsule())
                        .overlay { if off.isEmpty { Capsule().stroke(Palette.border) } else { WorkingBorder(radius: 18) } }
                        .neonGlow(Palette.cyan, radius: 6, on: !off.isEmpty)
                    }
                    .accessibilityLabel(off.isEmpty ? ActStrings.t("act.dock.show")
                                        : ActStrings.t("act.chip.aria", ["n": "\(off.count)", "lead": LiveRunModel.message(off[0].title)]))
                    .accessibilityIdentifier("activity.dock")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 92)
        .environment(\.layoutDirection, ActStrings.direction)
        .sheet(isPresented: $open) { ActivityPanel() }
    }
}

/// "Loading the multilingual model… about 6 s".
struct ModelLoadBanner: View {
    let job: JobSnapshot
    var body: some View {
        let model = ActStrings.t("act.model.\(job.model ?? "multilingual")")
        let text = job.expectedS.map { ActStrings.t("act.banner.loadingEta", ["model": model, "d": LiveRunModel.duration($0.doubleValue)]) }
            ?? ActStrings.t("act.banner.loading", ["model": model])
        HStack(spacing: 10) {
            ProgressView().tint(Palette.cyan)
            Text(text).font(.footnote.weight(.medium)).foregroundStyle(Palette.ink)
            Spacer(minLength: 0)
        }
        .padding(12)
        .card(active: true)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(ActStrings.t("act.banner.aria") + ": " + text)
        .accessibilityIdentifier("activity.modelBanner")
    }
}

/// The Activity panel: running jobs with Cancel, then the last ten finished.
struct ActivityPanel: View {
    @ObservedObject private var center = ActivityCenter.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                NeonSection(ActStrings.t("act.panel.running")) {
                    if center.snapshot.running.isEmpty {
                        Text(ActStrings.t("act.panel.empty")).font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                    ForEach(center.snapshot.running, id: \.id) { j in row(j) }
                }
                NeonSection(ActStrings.t("act.panel.finished")) {
                    ForEach(center.snapshot.finished, id: \.id) { j in row(j) }
                }
            }
            .neonList()
            .navigationTitle(ActStrings.t("act.panel.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(ActStrings.t("act.close")) { dismiss() } } }
        }
        .environment(\.layoutDirection, ActStrings.direction)
        .preferredColorScheme(.dark)
    }

    private func row(_ j: JobSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(LiveRunModel.message(j.title)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Spacer()
                if j.running && j.cancellable {
                    Button(j.cancelRequested ? ActStrings.t("act.cancelling") : ActStrings.t("act.cancel")) { center.cancel(j.id) }
                        .buttonStyle(.borderless)
                        .foregroundStyle(Palette.dangerText)
                        .disabled(j.cancelRequested)
                } else if !j.running {
                    Text(ActStrings.t("act.state.\(j.state)")).font(Typeface.mono(11))
                        .foregroundStyle(j.state == "done" ? Palette.okText : Palette.warnText)
                }
            }
            if j.running {
                Text(j.stage.map(LiveRunModel.message) ?? "").font(.caption).foregroundStyle(Palette.inkSoft)
                if let f = j.fraction?.doubleValue { SweepBar(fraction: f, height: 5, live: true) }
            } else {
                Text(j.result.map(LiveRunModel.message) ?? "").font(.caption).foregroundStyle(Palette.inkSoft)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
