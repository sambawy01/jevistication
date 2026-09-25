import SwiftUI

struct WebSettingsSheet: View {
    @EnvironmentObject private var web: WebModel
    @EnvironmentObject private var library: WebLibraryModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmRemove = false

    var body: some View {
        NavigationStack {
            Form {
                NeonSection {
                    Toggle("Online helper", isOn: $web.helperEnabled)
                        .accessibilityIdentifier("settings.helper")
                    ForEach(WebBuild.sectors().filter { $0 != .flights }) { s in
                        Toggle(WS.t("source.toggle", ["sector": s.title]), isOn: Binding(
                            get: { library.isEnabled(s) }, set: { library.setEnabled(s, $0) }))
                            .disabled(!web.helperEnabled)
                            .accessibilityIdentifier("settings.source.\(s.rawValue)")
                    }
                    if WebBuild.flightsDevFlag {
                        Toggle("Flights (development only)", isOn: $web.flightsEnabled)
                            .disabled(!web.helperEnabled)
                            .accessibilityIdentifier("settings.flights")
                    }
                } footer: {
                    Text("Each source is off until you turn it on. Off means no request is made. With the helper off, the Web tab says so and nothing else in Loupe changes.")
                }
                if WebBuild.flightsDevFlag {
                    NeonSection {
                        if web.hasKey {
                            Button("Remove key", role: .destructive) { confirmRemove = true }
                                .accessibilityIdentifier("settings.removeKey")
                        } else {
                            Text("No Duffel key on this phone.").foregroundStyle(Palette.inkSoft)
                        }
                    } header: { Text("Duffel key") } footer: {
                        Text("Stored in the Keychain, this device only. Removing it deletes it from the Keychain.")
                    }
                }
            }
            .neonList()
            .navigationTitle("Web settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Remove the Duffel key from this phone?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove key", role: .destructive) { web.removeKey() }
            }
        }
    }
}
