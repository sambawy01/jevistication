import SwiftUI

struct MeView: View {
    @EnvironmentObject private var web: WebModel
    @ObservedObject private var laya = LayaModel.shared
    @State private var showWebSettings = false
    private let engine = EngineInfo.load()

    var body: some View {
        NavigationStack {
            List {
                Section("Engine (on device)") {
                    row("LoupeKit", engine.linked ? "linked" : "missing")
                    row("Built-in judgments", "\(engine.builtInJudgments)")
                    row("Public Suffix List", engine.pslVersion)
                    NavigationLink { LayaModelView() } label: {
                        row("Laya model", layaStatus)
                    }
                    .accessibilityIdentifier("me.model")
                }
                Section("Web") {
                    Button("Web settings") { showWebSettings = true }
                }
                Section("About") {
                    NavigationLink("Licences") { LicencesView() }
                        .accessibilityIdentifier("me.licences")
                }
                Section {
                    Text("Coming with phone sources: calibration, decision history and export.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle("Me")
            .sheet(isPresented: $showWebSettings) { WebSettingsSheet().environmentObject(web) }
        }
    }

    private var layaStatus: String {
        switch laya.status {
        case .ready: return "on this phone"
        case .checking: return "checking"
        case .downloading(let p): return "downloading \(Int(p * 100))%"
        case .failed: return "needs attention"
        case .notInstalled: return "not on this phone"
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft).lineLimit(1) }
    }
}
