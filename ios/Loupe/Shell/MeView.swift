import SwiftUI

struct MeView: View {
    @EnvironmentObject private var web: WebModel
    @State private var showWebSettings = false
    private let engine = EngineInfo.load()

    var body: some View {
        NavigationStack {
            List {
                Section("Engine (on device)") {
                    row("LoupeKit", engine.linked ? "linked" : "missing")
                    row("Built-in judgments", "\(engine.builtInJudgments)")
                    row("Public Suffix List", engine.pslVersion)
                    row("Laya model", "not on this phone yet")
                }
                Section("Web") {
                    Button("Web settings") { showWebSettings = true }
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

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft).lineLimit(1) }
    }
}
