import SwiftUI

/// Me → Advanced (spec §3): the decision model's settings, the online phishing checks, the mascot and, in DEBUG and
/// TestFlight diagnostics builds, the device check.
struct AdvancedView: View {
    @ObservedObject private var online = OnlineChecksService.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @AppStorage(MascotKind.storageKey) private var mascotKind = MascotKind.default.rawValue

    var body: some View {
        List {
            NeonSection("Decision model") {
                NavigationLink { ModelSettingsView() } label: {
                    row(MS.t("title"), settings.settings.changed.isEmpty ? "defaults" : "\(settings.settings.changed.count) changed")
                }
                .accessibilityIdentifier("me.modelSettings")
                Text("Per-judgment calibration, the baseline and the threshold are on each judgment's Measure screen.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            NeonSection("Online phishing checks") {
                NavigationLink { OnlineChecksView(online: online) } label: {
                    row("Online phishing checks", online.settings.anyOn ? "On · Online" : "Off")
                }
                .accessibilityIdentifier("me.onlineChecks")
            }
            NeonSection("Appearance") {
                HStack {
                    Text("Mascot")
                    Spacer()
                    Picker("Mascot", selection: $mascotKind) {
                        ForEach(MascotKind.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    .fixedSize()
                    .accessibilityIdentifier("me.mascot")
                }
            }
            #if DEBUG || LOUPE_DIAGNOSTICS
            if MeView.showDiagnostics {
                NeonSection("Diagnostics") {
                    NavigationLink { DiagnosticsView() } label: { row("Diagnostics", "device check") }
                        .accessibilityIdentifier("me.diagnostics")
                }
            }
            #endif
        }
        .scrollContentBackground(.hidden)
        .neonGround()
        .navigationTitle("Advanced")
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft).lineLimit(1) }
            .frame(minHeight: 44)
    }
}
